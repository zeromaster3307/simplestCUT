package app.simplestcut.mp4

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.math.roundToLong

class Mp4Exception(message: String) : Exception(message)

/** Eine Box als Bereich innerhalb eines Byte-Arrays. */
class Box(val type: String, val start: Int, val body: Int, val end: Int)

fun boxes(buf: ByteBuffer, from: Int, to: Int): List<Box> {
    val list = mutableListOf<Box>()
    var p = from
    while (p + 8 <= to) {
        var size = buf.getInt(p).toLong() and 0xffffffffL
        val type = String(ByteArray(4) { buf.get(p + 4 + it) }, Charsets.ISO_8859_1)
        var header = 8
        if (size == 1L) {
            size = buf.getLong(p + 8); header = 16
        } else if (size == 0L) {
            size = (to - p).toLong()
        }
        if (size < header || p + size > to) throw Mp4Exception("Damaged box $type")
        list += Box(type, p, p + header, (p + size).toInt())
        p += size.toInt()
    }
    return list
}

fun ByteBuffer.child(parent: Box, type: String): Box? = boxes(this, parent.body, parent.end).firstOrNull { it.type == type }

/** Eintrag der Edit-List: ab [mediaTime] (Spur-Zeitbasis, -1 = leer) wird [duration] lang gezeigt. */
class Edit(val mediaTime: Long, val duration: Long)

/**
 * Eine Spur mit ihrer Sample-Tabelle. Zeiten in der Zeitbasis der Spur.
 * Präsentationszeit eines Samples = dts + ct - mediaOffset (erster Eintrag der Edit-List eingerechnet).
 */
class Mp4Track(
    val kind: String,
    val timescale: Int,
    val mediaOffset: Long,
    /** Edit-List (Dauer in Spur-Zeitbasis); ohne Edit-List ein offener Eintrag ab 0. */
    val edits: List<Edit>,
    val dts: LongArray,
    val deltas: IntArray,
    val ct: IntArray,
    val hasCtts: Boolean,
    val sync: BooleanArray,
    val hasStss: Boolean,
    val sizes: IntArray,
    val offsets: LongArray,
    val trak: Box,
    val tkhd: Box,
    val mdhd: Box,
    val hdlr: Box,
    val minf: Box,
    val stsd: Box,
) {
    val count get() = dts.size
    fun pt(i: Int): Long = dts[i] + ct[i] - mediaOffset

    /** Typische Sample-Dauer (Median); die erste ist bei Kameras oft ungewöhnlich lang oder kurz. */
    val typicalDelta: Long by lazy { deltas.sorted()[deltas.size / 2].toLong().coerceAtLeast(1) }

    /** Leere Einträge am Anfang der Edit-List: so viel später beginnt die Spur (z. B. Ton 48 ms nach dem Bild). */
    val startDelay: Long get() = edits.takeWhile { it.mediaTime < 0 }.sumOf { it.duration }
    fun raw(i: Int): Long = dts[i] + ct[i]
}

/**
 * Ein sichtbares Videobild: [sample] (Dekodier-Reihenfolge), [raw] = dts + ct, [time] = Anzeigezeit im Film
 * (Spur-Zeitbasis, Edit-List eingerechnet), [edit] = Eintrag der Edit-List, zu dem es gehört.
 */
class VideoFrame(val sample: Int, val raw: Long, val time: Long, val edit: Int)

/** Kopf (ftyp + moov) einer MP4-Datei, ohne die eigentlichen Videodaten. */
class Mp4Source(val file: File, val ftyp: ByteArray, val moov: ByteArray) {
    val buf: ByteBuffer = ByteBuffer.wrap(moov)
    val moovBox = Box("moov", 0, headerSize(moov), moov.size)
    val mvhd: Box = buf.child(moovBox, "mvhd") ?: throw Mp4Exception("mvhd missing")
    val movieTimescale: Int = buf.getInt(mvhd.body + if (moov[mvhd.body].toInt() == 1) 20 else 12)
    val tracks: List<Mp4Track> = boxes(buf, moovBox.body, moovBox.end).filter { it.type == "trak" }
        .map { parseTrack(it) }.filter { it.kind == "vide" || it.kind == "soun" }
    val video: Mp4Track = tracks.firstOrNull { it.kind == "vide" } ?: throw Mp4Exception("No video track")
    val audio: List<Mp4Track> = tracks.filter { it.kind == "soun" }

    /**
     * Sichtbare Videobilder in Anzeige-Reihenfolge, so wie ein Player die Edit-List abspielt. Bei bereits
     * mehrteilig geschnittenen Dateien liefert jeder Eintrag seine Bilder; versteckte Bilder fehlen.
     */
    val videoFrames: List<VideoFrame> by lazy {
        val v = video
        val byRaw = (0 until v.count).sortedBy { v.raw(it) }
        val raws = LongArray(byRaw.size) { v.raw(byRaw[it]) }
        val result = ArrayList<VideoFrame>(v.count)
        var editStart = 0L
        for ((e, edit) in v.edits.withIndex()) {
            if (edit.mediaTime >= 0) {
                // Ältere eigene Schnitte ragen durch Aufrunden ein paar Zeiteinheiten in den nächsten Abschnitt:
                // Bilder, die erst ganz am Ende beginnen, gehören nicht dazu.
                val end = if (edit.duration == Long.MAX_VALUE) Long.MAX_VALUE
                else edit.mediaTime + edit.duration - v.typicalDelta / 4
                var k = lowerBound(raws, edit.mediaTime)
                while (k < raws.size && raws[k] < end) {
                    result += VideoFrame(byRaw[k], raws[k], editStart + raws[k] - edit.mediaTime, e)
                    k++
                }
            }
            if (edit.duration != Long.MAX_VALUE) editStart += edit.duration
        }
        if (result.isEmpty()) throw Mp4Exception("No visible video frames")
        result
    }

    private fun lowerBound(a: LongArray, x: Long): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (a[mid] < x) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun parseTrack(trak: Box): Mp4Track {
        val b = buf
        val mdia = b.child(trak, "mdia") ?: throw Mp4Exception("mdia missing")
        val tkhd = b.child(trak, "tkhd") ?: throw Mp4Exception("tkhd missing")
        val mdhd = b.child(mdia, "mdhd") ?: throw Mp4Exception("mdhd missing")
        val hdlr = b.child(mdia, "hdlr") ?: throw Mp4Exception("hdlr missing")
        val kind = String(moov, hdlr.body + 8, 4, Charsets.ISO_8859_1)
        val timescale = b.getInt(mdhd.body + if (moov[mdhd.body].toInt() == 1) 20 else 12)
        val minf = b.child(mdia, "minf") ?: throw Mp4Exception("minf missing")
        val stbl = b.child(minf, "stbl") ?: throw Mp4Exception("stbl missing")
        val stsd = b.child(stbl, "stsd") ?: throw Mp4Exception("stsd missing")

        // Edit-List der Quelle (Dauer von Film- in Spur-Zeitbasis umgerechnet).
        val edits = mutableListOf<Edit>()
        b.child(trak, "edts")?.let { edts ->
            val elst = b.child(edts, "elst") ?: return@let
            val v = moov[elst.body].toInt()
            val n = b.getInt(elst.body + 4)
            var p = elst.body + 8
            repeat(n) {
                val dur = if (v == 1) b.getLong(p) else b.getInt(p).toLong() and 0xffffffffL
                val mt = if (v == 1) b.getLong(p + 8) else b.getInt(p + 4).toLong()
                p += if (v == 1) 20 else 12
                edits += Edit(mt, (dur.toDouble() * timescale / movieTimescale).roundToLong())
            }
        }
        // Ein einzelner Eintrag mit Dauer 0 bzw. ohne Edit-List: alles zeigen.
        if (edits.none { it.mediaTime >= 0 && it.duration > 0 }) {
            edits.clear()
            edits += Edit(0, Long.MAX_VALUE)
        } else if (edits.size == 1) {
            edits[0] = Edit(edits[0].mediaTime, Long.MAX_VALUE)
        }
        val mediaOffset = edits.first { it.mediaTime >= 0 }.mediaTime

        // stts -> dts
        val stts = b.child(stbl, "stts") ?: throw Mp4Exception("stts missing")
        val sttsCount = b.getInt(stts.body + 4)
        var total = 0L
        for (k in 0 until sttsCount) total += b.getInt(stts.body + 8 + 8 * k).toLong() and 0xffffffffL
        val n = total.toInt()
        val deltas = IntArray(n)
        var i = 0
        for (k in 0 until sttsCount) {
            val cnt = b.getInt(stts.body + 8 + 8 * k)
            val d = b.getInt(stts.body + 12 + 8 * k)
            repeat(cnt) { deltas[i++] = d }
        }
        val dts = LongArray(n)
        for (j in 1 until n) dts[j] = dts[j - 1] + (deltas[j - 1].toLong() and 0xffffffffL)

        // ctts
        val ct = IntArray(n)
        val ctts = b.child(stbl, "ctts")
        if (ctts != null) {
            val cnt = b.getInt(ctts.body + 4)
            var j = 0
            for (k in 0 until cnt) {
                val c = b.getInt(ctts.body + 8 + 8 * k)
                val off = b.getInt(ctts.body + 12 + 8 * k)
                repeat(c) { if (j < n) ct[j++] = off }
            }
        }

        // stss
        val stss = b.child(stbl, "stss")
        val sync = BooleanArray(n) { stss == null }
        if (stss != null) {
            val cnt = b.getInt(stss.body + 4)
            for (k in 0 until cnt) {
                val idx = b.getInt(stss.body + 8 + 4 * k) - 1
                if (idx in 0 until n) sync[idx] = true
            }
        }

        // stsz
        val stsz = b.child(stbl, "stsz") ?: throw Mp4Exception("stsz missing")
        val fixed = b.getInt(stsz.body + 4)
        val sizes = IntArray(n) { if (fixed != 0) fixed else b.getInt(stsz.body + 12 + 4 * it) }

        // Chunk-Offsets + stsc -> Offset je Sample
        val stco = b.child(stbl, "stco")
        val co64 = b.child(stbl, "co64")
        val chunkOffsets: LongArray = when {
            stco != null -> LongArray(b.getInt(stco.body + 4)) { b.getInt(stco.body + 8 + 4 * it).toLong() and 0xffffffffL }
            co64 != null -> LongArray(b.getInt(co64.body + 4)) { b.getLong(co64.body + 8 + 8 * it) }
            else -> throw Mp4Exception("Chunk table missing (fragmented MP4 is not supported)")
        }
        val stsc = b.child(stbl, "stsc") ?: throw Mp4Exception("stsc missing")
        val stscCount = b.getInt(stsc.body + 4)
        val offsets = LongArray(n)
        var s = 0
        for (k in 0 until stscCount) {
            val first = b.getInt(stsc.body + 8 + 12 * k)
            val per = b.getInt(stsc.body + 12 + 12 * k)
            val last = if (k + 1 < stscCount) b.getInt(stsc.body + 8 + 12 * (k + 1)) - 1 else chunkOffsets.size
            for (c in first - 1 until last) {
                var o = chunkOffsets[c]
                repeat(per) {
                    if (s < n) {
                        offsets[s] = o; o += sizes[s]; s++
                    }
                }
            }
        }
        if (n == 0) throw Mp4Exception("Track without samples (fragmented MP4 is not supported)")
        return Mp4Track(kind, timescale, mediaOffset, edits, dts, deltas, ct, ctts != null, sync, stss != null, sizes, offsets,
            trak, tkhd, mdhd, hdlr, minf, stsd)
    }

    companion object {
        private fun headerSize(moov: ByteArray) = if (ByteBuffer.wrap(moov).getInt(0) == 1) 16 else 8

        /** Liest nur ftyp und moov (die Videodaten bleiben auf der Platte). */
        fun read(file: File): Mp4Source {
            RandomAccessFile(file, "r").use { raf ->
                var pos = 0L
                val header = ByteArray(16)
                var ftyp: ByteArray? = null
                var moov: ByteArray? = null
                while (pos + 8 <= raf.length() && (ftyp == null || moov == null)) {
                    raf.seek(pos)
                    raf.readFully(header, 0, 8)
                    var size = ByteBuffer.wrap(header, 0, 4).int.toLong() and 0xffffffffL
                    val type = String(header, 4, 4, Charsets.ISO_8859_1)
                    if (size == 1L) {
                        raf.readFully(header, 8, 8); size = ByteBuffer.wrap(header, 8, 8).long
                    } else if (size == 0L) {
                        size = raf.length() - pos
                    }
                    if (size < 8) throw Mp4Exception("Damaged file")
                    if (type == "ftyp" || type == "moov") {
                        if (size > 256L * 1024 * 1024) throw Mp4Exception("Header too large")
                        val bytes = ByteArray(size.toInt())
                        raf.seek(pos)
                        raf.readFully(bytes)
                        if (type == "ftyp") ftyp = bytes else moov = bytes
                    }
                    pos += size
                }
                return Mp4Source(file, ftyp ?: throw Mp4Exception("Not an MP4 file"), moov ?: throw Mp4Exception("moov missing"))
            }
        }
    }
}
