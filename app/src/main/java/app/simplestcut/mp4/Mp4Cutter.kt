package app.simplestcut.mp4

import app.simplestcut.R
import android.content.res.Resources
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToLong

/** Ein Abschnitt zum Übernehmen: sichtbare Bilder [frames] (Indizes in [Mp4Source.videoFrames], beide Enden
 * eingeschlossen) der Quelle Nummer [source]. */
class Segment(val source: Int, val frames: IntRange)

/**
 * Verlustfreier Mehrfachschnitt aus einer oder mehreren Quellen: kopiert für jeden Abschnitt die nötigen
 * Videobilder 1:1 (ab dem Keyframe davor) und schreibt eine Edit-List mit einem Eintrag pro Abschnitt, die dem
 * Player sagt, welche Bilder er zeigen soll. Ton wird lückenlos aneinandergehängt (kumulativ gerundet, damit er
 * synchron bleibt). ftyp, "meta" und "udta" (Aufnahmeort, Hersteller-Tags, ...) kommen unverändert von der
 * ersten Quelle. Mehrere Quellen müssen zusammenpassen, siehe [incompatibility].
 */
class Mp4Cutter(
    private val sources: List<Mp4Source>,
    /** Ganzes Video zusätzlich im Uhrzeigersinn drehen (0/90/180/270): nur die Drehmatrix in tkhd, verlustfrei. */
    private val rotation: Int = 0,
) {
    constructor(src: Mp4Source) : this(listOf(src))

    private val main = sources[0]
    private val video = main.video

    /** Ein Sample der Ausgabe: [size] Bytes aus [file] ab [offset]; Zeiten in Spur-Zeitbasis. */
    private data class OutSample(val file: File, val offset: Long, val size: Int, val dts: Long, val ct: Int, val sync: Boolean, val duration: Int)

    /** Videospur der Ausgabe: Blöcke je Abschnitt, Edit-List (Dauer Film-Zeitbasis, media_time), Abschnittszeiten. */
    private class VideoPlan(
        val blocks: List<Block>,
        val edits: List<Pair<Long, Long>>,
        val segSeconds: List<Double>,
        val startSeconds: List<Double>,
        val hasCtts: Boolean,
        val hasStss: Boolean,
        val entryType: String? = null,
    )

    /** Zusammenhängender Block einer Spur in mdat; [slot] = Ausgabespur (0 = Video, 1.. = Ton). */
    private class Block(val slot: Int, val samples: List<OutSample>) {
        val bytes get() = samples.sumOf { it.size.toLong() }
    }

    private fun copied(src: Mp4Source, t: Mp4Track, i: Int, dts: Long) =
        OutSample(src.file, t.offsets[i], t.sizes[i], dts, t.ct[i], t.sync[i], t.deltas[i])

    private fun frameDur(t: Mp4Track) = t.typicalDelta

    /**
     * Schreibt die Abschnitte nach [out]. Mit [encoder] ("Exportieren"): Bilder an den Schnittkanten werden neu
     * kodiert, dazwischen ganze GOPs 1:1 kopiert – keine versteckten Bilder, eine einfache Zeitleiste ohne Sprünge.
     */
    fun write(segments: List<Segment>, out: File, onProgress: (Float) -> Unit = {}, encoder: FrameEncoder? = null) {
        require(segments.isNotEmpty()) { "No segments" }
        // Abschnitte an alten Schnitten der Quelle (Einträge ihrer Edit-List) aufteilen.
        val parts = segments.flatMap { seg ->
            val frames = sources[seg.source].videoFrames
            val list = mutableListOf<Segment>()
            var from = seg.frames.first
            for (k in seg.frames.first + 1..seg.frames.last) {
                if (frames[k].edit != frames[k - 1].edit) {
                    list += Segment(seg.source, from until k)
                    from = k
                }
            }
            list += Segment(seg.source, from..seg.frames.last)
            list
        }

        // Zwischendatei im Cache der App (java.io.tmpdir): neben dem Ziel darf die App ohne Vollzugriff nur
        // Videodateien anlegen, eine .tmp-Datei in DCIM/Movies/Download scheitert mit EPERM.
        val temp = if (encoder != null) File.createTempFile("reencode", ".tmp") else File(out.path + ".tmp")
        val tempOut = if (encoder != null) RandomAccessFile(temp, "rw").apply { setLength(0) } else null
        try {
            val plan = if (encoder != null && tempOut != null) {
                exportVideo(parts, encoder, tempOut, temp) { onProgress(it * 0.85f) }
            } else {
                copyVideo(parts)
            }
            tempOut?.fd?.sync()
            writeFile(parts, plan, out) { if (encoder != null) onProgress(0.85f + it * 0.15f) else onProgress(it) }
        } finally {
            tempOut?.close()
            temp.delete()
        }
    }

    /** Verlustfrei: je Abschnitt ab dem Keyframe davor kopieren, die Edit-List zeigt nur die gewünschten Bilder. */
    private fun copyVideo(parts: List<Segment>): VideoPlan {
        val videoChunks = mutableListOf<Block>()
        val edits = mutableListOf<Pair<Long, Long>>() // (Dauer Film-Zeitbasis, media_time)
        val segSeconds = mutableListOf<Double>()
        val startSeconds = mutableListOf<Double>()
        var base = 0L
        for (part in parts) {
            val src = sources[part.source]
            val v = src.video
            val frames = src.videoFrames
            val r = part.frames
            var firstDec = Int.MAX_VALUE
            var lastDec = -1
            for (k in r) {
                firstDec = minOf(firstDec, frames[k].sample); lastDec = maxOf(lastDec, frames[k].sample)
            }
            val first = frames[r.first]
            // Ab einem Keyframe, der auch in Anzeige-Reihenfolge vor dem ersten Bild liegt (offene GOPs, x265: Bilder
            // kurz vor einem Keyframe brauchen die GOP davor).
            var kd = firstDec
            while (kd > 0 && !(v.sync[kd] && v.raw(kd) <= first.raw)) kd--
            val shift = base - v.dts[kd]
            val next = frames.getOrNull(r.last + 1)
            val endRaw = if (next != null && next.edit == frames[r.last].edit) next.raw else frames[r.last].raw + frameDur(v)
            val durMedia = endRaw - first.raw
            // Abrunden: aufgerundet ragte der Abschnitt ein Stück in den nächsten (Player zeigt dort ein falsches Bild).
            edits += (durMedia * main.movieTimescale.toDouble() / video.timescale).toLong() to (first.raw + shift)
            segSeconds += durMedia.toDouble() / v.timescale
            startSeconds += first.time.toDouble() / v.timescale
            videoChunks += Block(0, (kd..lastDec).map { copied(src, v, it, v.dts[it] + shift) })
            var maxT = v.dts[lastDec] + shift
            for (i in kd..lastDec) maxT = maxOf(maxT, v.dts[i] + v.ct[i] + shift)
            // Nächster Abschnitt erst nach dem Ende dieses Abschnitts (sonst ragt er hinein).
            base = maxOf(maxT + frameDur(v), endRaw + shift)
        }
        return VideoPlan(
            videoChunks, edits, segSeconds, startSeconds,
            hasCtts = parts.any { sources[it.source].video.hasCtts },
            hasStss = parts.any { sources[it.source].video.hasStss },
        )
    }

    /**
     * Exportieren: Ganze GOPs innerhalb eines Abschnitts werden kopiert, angeschnittene GOPs (Schnittkanten) neu
     * kodiert. Die Ausgabe-Zeitleiste ist lückenlos, die Edit-List hat nur einen Eintrag.
     */
    private fun exportVideo(
        parts: List<Segment>,
        encoder: FrameEncoder,
        temp: RandomAccessFile,
        tempFile: File,
        onProgress: (Float) -> Unit,
    ): VideoPlan {
        val config = VideoConfig.of(main)
        // Neu kodierte Bilder bekommen diesen Versatz zwischen Dekodier- und Anzeigezeit, damit sie sich mit den
        // kopierten (B-Frames!) nicht überschneiden.
        val maxCt = sources.maxOf { s -> s.video.ct.maxOrNull() ?: 0 }.coerceAtLeast(0)
        val partSamples = mutableListOf<MutableList<OutSample>>()
        val partPts = mutableListOf<MutableList<Long>>()
        val segSeconds = mutableListOf<Double>()
        val startSeconds = mutableListOf<Double>()
        val inputs = HashMap<Mp4Source, RandomAccessFile>()
        var encodedAny = false
        // Hat der Decoder gerade die Parameter der Quelle (true) oder die eines neu kodierten Stücks?
        var origInStream = true
        var t = 0L
        val totalFrames = parts.sumOf { it.frames.last - it.frames.first + 1 }.coerceAtLeast(1)
        var doneFrames = 0
        try {
            for (part in parts) {
                val src = sources[part.source]
                val v = src.video
                val frames = src.videoFrames
                val r = part.frames
                val first = frames[r.first]
                val samples = mutableListOf<OutSample>()
                val pts = mutableListOf<Long>()
                partSamples += samples
                partPts += pts
                fun ptsOut(raw: Long) = t + raw - first.raw
                val keys = keyframes(v)
                var k = r.first
                while (k <= r.last) {
                    // Nach Anzeigezeit gruppieren: bei offenen GOPs (x265) werden Bilder kurz vor einem Keyframe erst
                    // nach ihm dekodiert, gehören aber zur Zeitspanne davor.
                    val gop = displayGop(v, keys, frames[k].raw)
                    var e = k
                    while (e + 1 <= r.last && displayGop(v, keys, frames[e + 1].raw) == gop) e++
                    val group = frames.subList(k, e + 1)
                    val gopEnd = gopEnd(v, gop)
                    val whole = group.size == gopEnd - gop && group.all { it.sample in gop until gopEnd } &&
                        group.map { it.sample }.toSet().size == group.size && isClosed(v, gop, gopEnd)
                    if (whole) {
                        for (i in gop until gopEnd) {
                            val p = ptsOut(v.raw(i))
                            var sample = copied(src, v, i, p - v.ct[i])
                            if (i == gop && !origInStream) {
                                // Nach einem neu kodierten Stück: Parameter der Quelle wieder in den Datenstrom setzen.
                                val input = inputs.getOrPut(src) { RandomAccessFile(src.file, "r") }
                                val bytes = config.withParams(readSample(input, v, i))
                                val offset = temp.length()
                                temp.seek(offset)
                                temp.write(bytes)
                                sample = sample.copy(file = tempFile, offset = offset, size = bytes.size)
                            }
                            samples += sample
                            pts += p
                        }
                        origInStream = true
                    } else {
                        val run = encoder.encode(src, group, temp)
                        if (!run.sync[0]) throw Mp4Exception("Re-encoded part does not start with a keyframe")
                        for ((j, f) in group.withIndex()) {
                            val p = ptsOut(f.raw)
                            samples += OutSample(tempFile, run.offsets[j], run.sizes[j], p - maxCt, maxCt, run.sync[j], frameDur(v).toInt())
                            pts += p
                        }
                        origInStream = false
                        encodedAny = true
                    }
                    doneFrames += group.size
                    onProgress(doneFrames.toFloat() / totalFrames)
                    k = e + 1
                }
                val next = frames.getOrNull(r.last + 1)
                val endRaw = if (next != null && next.edit == frames[r.last].edit) next.raw else frames[r.last].raw + frameDur(v)
                val durMedia = endRaw - first.raw
                segSeconds += durMedia.toDouble() / v.timescale
                startSeconds += first.time.toDouble() / v.timescale
                t += durMedia
            }
        } finally {
            inputs.values.forEach { it.close() }
        }

        // Dekodierzeiten streng steigend machen (an Nahtstellen nötig) und so verschieben, dass die erste 0 ist.
        var prev = Long.MIN_VALUE
        val fixed = partSamples.indices.map { pi ->
            partSamples[pi].mapIndexed { si, s ->
                val dts = if (prev != Long.MIN_VALUE && s.dts <= prev) prev + 1 else s.dts
                prev = dts
                val ct = partPts[pi][si] - dts
                if (ct < 0) throw Mp4Exception("Timestamps do not match")
                s.copy(dts = dts, ct = ct.toInt())
            }
        }
        val offset = -fixed.first().first().dts
        val blocks = fixed.map { list -> Block(0, list.map { it.copy(dts = it.dts + offset) }) }
        val edits = listOf((t * main.movieTimescale.toDouble() / video.timescale).toLong() to offset)
        return VideoPlan(
            blocks, edits, segSeconds, startSeconds,
            hasCtts = blocks.any { b -> b.samples.any { it.ct != 0 } },
            hasStss = true,
            entryType = if (encodedAny) config.inBandEntryType else null,
        )
    }

    /** Keyframes (Sample-Nummern), nach Anzeigezeit sortiert. */
    private fun keyframes(t: Mp4Track): IntArray = (0 until t.count).filter { t.sync[it] }.sortedBy { t.raw(it) }.toIntArray()

    /** Keyframe, ab dem ein Bild mit Anzeigezeit [raw] gezeigt wird (letzter mit Anzeigezeit <= raw). */
    private fun displayGop(t: Mp4Track, keys: IntArray, raw: Long): Int {
        var lo = 0
        var hi = keys.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (t.raw(keys[mid]) <= raw) lo = mid else hi = mid - 1
        }
        return keys[lo]
    }

    /** Erstes Sample nach der GOP, die bei [start] beginnt. */
    private fun gopEnd(t: Mp4Track, start: Int): Int {
        var k = start + 1
        while (k < t.count && !t.sync[k]) k++
        return k
    }

    /** Geschlossene GOP: alle ihre Bilder werden zwischen ihrem Keyframe und dem nächsten angezeigt. */
    private fun isClosed(t: Mp4Track, start: Int, end: Int): Boolean {
        val first = t.raw(start)
        val limit = if (end < t.count) t.raw(end) else Long.MAX_VALUE
        return (start until end).all { t.raw(it) in first until limit }
    }

    private fun writeFile(parts: List<Segment>, plan: VideoPlan, out: File, onProgress: (Float) -> Unit) {
        val videoChunks = plan.blocks
        val edits = plan.edits
        val segSeconds = plan.segSeconds
        val startSeconds = plan.startSeconds

        // Ton: lückenlos aneinander, pro Abschnitt ab der Stelle, die zum ersten Bild gehört, und so lang, dass die
        // Gesamtlänge (nach echter Paketdauer) der Videolänge möglichst genau entspricht.
        // Beginnt der Ton der Quelle später als das Bild (Kamera: ~48 ms), fehlt am Abschnittsanfang Ton: am
        // Dateianfang bleibt diese Zeit still (leerer Edit-List-Eintrag), sonst läuft der vorige Abschnitt so viel
        // länger.
        val audioLeads = main.audio.indices.map { ai ->
            parts.indices.map { k ->
                val a = sources[parts[k].source].audio[ai]
                maxOf(0L, a.startDelay - (startSeconds[k] * a.timescale).roundToLong())
            }
        }
        val audioChunks = main.audio.indices.map { ai ->
            val list = mutableListOf<Block?>()
            val leads = audioLeads[ai]
            val ts = main.audio[ai].timescale
            var videoEnd = 0.0
            var have = leads[0] // Ausgabe-Ton-Zeit, inkl. Stille am Anfang
            var dts = 0L
            for ((k, seconds) in segSeconds.withIndex()) {
                val src = sources[parts[k].source]
                val a = src.audio[ai]
                videoEnd += seconds
                val target = (videoEnd * ts).roundToLong() + leads.getOrElse(k + 1) { 0L }
                val start = closestSample(a, (startSeconds[k] * ts).roundToLong() - a.startDelay + leads[k])
                var last = start - 1
                while (last + 1 < a.count && have + a.deltas[last + 1] / 2 < target) {
                    last++
                    have += a.deltas[last]
                }
                list += if (last >= start) {
                    Block(1 + ai, (start..last).map { i -> copied(src, a, i, dts).also { dts += a.deltas[i] } })
                } else {
                    null
                }
            }
            list
        }

        // Reihenfolge in mdat: pro Abschnitt Video, dann Ton.
        val layout = mutableListOf<Block>()
        for ((k, vc) in videoChunks.withIndex()) {
            layout += vc
            for (a in audioChunks) a[k]?.let { layout += it }
        }
        val dataSize = layout.sumOf { it.bytes }

        fun moovFor(dataStart: Long): ByteArray {
            val chunkOffsets = HashMap<Int, MutableList<Long>>()
            var o = dataStart
            for (c in layout) {
                chunkOffsets.getOrPut(c.slot) { mutableListOf() } += o
                o += c.bytes
            }
            return buildMoov(videoChunks, audioChunks.map { it.filterNotNull() }, audioLeads.map { it[0] }, edits, chunkOffsets,
                videoCtts = plan.hasCtts,
                videoStss = plan.hasStss,
                audioCtts = main.audio.indices.map { ai -> parts.any { sources[it.source].audio[ai].hasCtts } },
                audioStss = main.audio.indices.map { ai -> parts.any { sources[it.source].audio[ai].hasStss } },
                videoEntryType = plan.entryType)
        }

        val headerLen = main.ftyp.size + moovFor(0).size + 16L
        val moov = moovFor(headerLen)

        val inputs = layout.flatMap { b -> b.samples.map { it.file } }.distinct().associateWith { RandomAccessFile(it, "r") }
        try {
            FileOutputStream(out).use { fos ->
                fos.write(main.ftyp)
                fos.write(moov)
                fos.write(DataBytes { writeInt(1); writeBytes("mdat"); writeLong(16 + dataSize) })
                val outCh = fos.channel
                var copied = 0L
                for (c in layout) {
                    val list = c.samples
                    // Auf der Platte zusammenhängende Samples in einem Rutsch kopieren.
                    var i = 0
                    while (i < list.size) {
                        val inCh = inputs.getValue(list[i].file).channel
                        val start = list[i].offset
                        var len = list[i].size.toLong()
                        var j = i + 1
                        while (j < list.size && list[j].file == list[i].file && list[j].offset == start + len) {
                            len += list[j].size; j++
                        }
                        var pos = 0L
                        while (pos < len) pos += inCh.transferTo(start + pos, len - pos, outCh)
                        copied += len
                        onProgress((copied.toDouble() / dataSize).toFloat())
                        i = j
                    }
                }
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    private fun closestSample(t: Mp4Track, pt: Long): Int {
        var lo = 0
        var hi = t.count - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (t.pt(mid) < pt) lo = mid + 1 else hi = mid
        }
        return if (lo > 0 && abs(t.pt(lo - 1) - pt) <= abs(t.pt(lo) - pt)) lo - 1 else lo
    }

    private fun buildMoov(
        videoChunks: List<Block>,
        audioChunks: List<List<Block>>,
        audioLeads: List<Long>,
        edits: List<Pair<Long, Long>>,
        chunkOffsets: Map<Int, List<Long>>,
        videoCtts: Boolean,
        videoStss: Boolean,
        audioCtts: List<Boolean>,
        audioStss: List<Boolean>,
        /** Ersatz für den Typ des Videoeintrags in stsd (z. B. "hev1"), wenn Parameter im Datenstrom wechseln. */
        videoEntryType: String? = null,
    ): ByteArray {
        val movieDur = edits.sumOf { it.first }
        val traks = ByteArrayOutputStream()
        val vEdits = fullBox("elst", 1, 0) {
            writeInt(edits.size)
            for ((dur, mt) in edits) { writeLong(dur); writeLong(mt); writeShort(1); writeShort(0) }
        }
        traks.write(trak(video, videoChunks, vEdits, movieDur, chunkOffsets[0].orEmpty(), videoCtts, videoStss, videoEntryType))
        for ((ai, a) in main.audio.withIndex()) {
            val chunks = audioChunks[ai]
            if (chunks.isEmpty()) continue
            // Stille am Anfang (Spur-Zeitbasis -> Film-Zeitbasis), danach der Ton.
            val lead = (audioLeads[ai] * main.movieTimescale.toDouble() / a.timescale).roundToLong().coerceAtMost(movieDur)
            val aEdits = fullBox("elst", 1, 0) {
                if (lead > 0) {
                    writeInt(2); writeLong(lead); writeLong(-1); writeShort(1); writeShort(0)
                } else {
                    writeInt(1)
                }
                writeLong(movieDur - lead); writeLong(a.mediaOffset); writeShort(1); writeShort(0)
            }
            traks.write(trak(a, chunks, aEdits, movieDur, chunkOffsets[1 + ai].orEmpty(), audioCtts[ai], audioStss[ai]))
        }
        val extra = ByteArrayOutputStream()
        for (b in boxes(main.buf, main.moovBox.body, main.moovBox.end)) {
            if (b.type == "meta" || b.type == "udta") extra.write(main.moov, b.start, b.end - b.start)
        }
        return box("moov") {
            write(patchDuration(main.mvhd, movieDur, tkhd = false))
            write(traks.toByteArray())
            write(extra.toByteArray())
        }
    }

    /** Ausgabespur nach dem Vorbild [t] (Spur der ersten Quelle) mit den Samples aus [chunks]. */
    private fun trak(
        t: Mp4Track,
        chunks: List<Block>,
        elst: ByteArray,
        movieDur: Long,
        offsets: List<Long>,
        hasCtts: Boolean,
        hasStss: Boolean,
        entryType: String? = null,
    ): ByteArray {
        val samples = chunks.flatMap { it.samples }
        val n = samples.size
        val sizes = samples.map { it.size }
        val cts = samples.map { it.ct }
        val sync = samples.map { it.sync }
        val deltas = IntArray(n) { if (it + 1 < n) (samples[it + 1].dts - samples[it].dts).toInt() else samples[it].duration }
        val mediaDur = if (n > 0) samples[n - 1].dts + deltas[n - 1] else 0L
        val src = main

        val stbl = box("stbl") {
            val stsd = src.moov.copyOfRange(t.stsd.start, t.stsd.end)
            // Erster Eintrag: Grösse(4) Typ(4) nach stsd-Kopf (8) + Version/Flags (4) + Anzahl (4).
            entryType?.let { System.arraycopy(it.toByteArray(Charsets.ISO_8859_1), 0, stsd, t.stsd.body - t.stsd.start + 12, 4) }
            write(stsd)
            write(fullBox("stts", 0, 0) { runs(deltas.toList()) { c, v -> writeInt(c); writeInt(v) } })
            if (hasCtts) {
                val neg = cts.any { it < 0 }
                write(fullBox("ctts", if (neg) 1 else 0, 0) { runs(cts) { c, v -> writeInt(c); writeInt(v) } })
            }
            if (hasStss) {
                val idx = sync.indices.filter { sync[it] }
                write(fullBox("stss", 0, 0) { writeInt(idx.size); idx.forEach { writeInt(it + 1) } })
            }
            write(fullBox("stsz", 0, 0) { writeInt(0); writeInt(n); sizes.forEach { writeInt(it) } })
            val perChunk = chunks.map { it.samples.size }
            write(fullBox("stsc", 0, 0) {
                val entries = mutableListOf<Pair<Int, Int>>()
                perChunk.forEachIndexed { ci, cnt -> if (entries.isEmpty() || entries.last().second != cnt) entries += (ci + 1) to cnt }
                writeInt(entries.size)
                entries.forEach { (first, cnt) -> writeInt(first); writeInt(cnt); writeInt(1) }
            })
            write(fullBox("co64", 0, 0) { writeInt(offsets.size); offsets.forEach { writeLong(it) } })
        }
        val minf = box("minf") {
            for (b in boxes(src.buf, t.minf.body, t.minf.end)) {
                if (b.type == "vmhd" || b.type == "smhd" || b.type == "dinf") write(src.moov, b.start, b.end - b.start)
            }
            write(stbl)
        }
        val mdia = box("mdia") {
            write(patchDuration(t.mdhd, mediaDur, tkhd = false))
            write(src.moov, t.hdlr.start, t.hdlr.end - t.hdlr.start)
            write(minf)
        }
        return box("trak") {
            val tkhd = patchDuration(t.tkhd, movieDur, tkhd = true)
            if (t === video && rotation != 0) rotateMatrix(tkhd, rotation)
            write(tkhd)
            write(box("edts") { write(elst) })
            write(mdia)
        }
    }

    /** Kopie einer mvhd/mdhd/tkhd-Box (der ersten Quelle) mit neuer Dauer. */
    private fun patchDuration(b: Box, duration: Long, tkhd: Boolean): ByteArray {
        val bytes = main.moov.copyOfRange(b.start, b.end)
        val h = b.body - b.start
        val v = bytes[h].toInt()
        val off = h + if (v == 1) (if (tkhd) 28 else 24) else (if (tkhd) 20 else 16)
        val buf = java.nio.ByteBuffer.wrap(bytes)
        if (v == 1) buf.putLong(off, duration) else buf.putInt(off, duration.coerceAtMost(0xffffffffL).toInt())
        return bytes
    }
}

private fun <T> DataOutputStream.runs(values: List<T>, write: DataOutputStream.(Int, T) -> Unit) {
    val runs = mutableListOf<Pair<Int, T>>()
    for (v in values) {
        if (runs.isNotEmpty() && runs.last().second == v) runs[runs.size - 1] = runs.last().first + 1 to v else runs += 1 to v
    }
    writeInt(runs.size)
    for ((c, v) in runs) write(c, v)
}

private fun DataBytes(block: DataOutputStream.() -> Unit): ByteArray {
    val bos = ByteArrayOutputStream()
    DataOutputStream(bos).block()
    return bos.toByteArray()
}

private fun box(type: String, body: DataOutputStream.() -> Unit): ByteArray {
    val payload = DataBytes(body)
    return DataBytes { writeInt(8 + payload.size); writeBytes(type); write(payload) }
}

private fun fullBox(type: String, version: Int, flags: Int, body: DataOutputStream.() -> Unit): ByteArray =
    box(type) { writeInt((version shl 24) or flags); body() }

/**
 * Prüft, ob die Quellen ohne Neukodieren aneinandergehängt werden können. Liefert den Grund in Klartext
 * (mit Dateinamen aus [names]) oder null, wenn alles passt.
 */
fun incompatibility(sources: List<Mp4Source>, names: List<String>, res: Resources): String? {
    val first = sources[0]
    for (i in 1 until sources.size) {
        val other = sources[i]
        val reason = differenceBetween(first, other, res) ?: continue
        return res.getString(R.string.merge_mismatch, names[i], names[0], reason)
    }
    return null
}

private fun differenceBetween(a: Mp4Source, b: Mp4Source, res: Resources): String? {
    val va = a.video
    val vb = b.video
    val codecA = sampleEntryType(a, va)
    val codecB = sampleEntryType(b, vb)
    if (codecA != codecB) return res.getString(R.string.reason_codec, codecA, codecB)
    val (wa, ha) = visualSize(a, va)
    val (wb, hb) = visualSize(b, vb)
    if (wa != wb || ha != hb) return res.getString(R.string.reason_resolution, wa, ha, wb, hb)
    if (!a.moov.copyOfRange(matrixStart(a, va), matrixStart(a, va) + 36)
            .contentEquals(b.moov.copyOfRange(matrixStart(b, vb), matrixStart(b, vb) + 36))
    ) return res.getString(R.string.reason_orientation)
    if (va.timescale != vb.timescale || !sameBox(a, va.stsd, b, vb.stsd)) {
        return res.getString(R.string.reason_encoding)
    }
    if (a.audio.size != b.audio.size) return res.getString(if (b.audio.isEmpty() || a.audio.isEmpty()) R.string.reason_no_audio else R.string.reason_audio_tracks)
    for ((ta, tb) in a.audio.zip(b.audio)) {
        if (ta.timescale != tb.timescale || !sameBox(a, ta.stsd, b, tb.stsd)) return res.getString(R.string.reason_audio_format)
    }
    return null
}

private fun sameBox(a: Mp4Source, ba: Box, b: Mp4Source, bb: Box) =
    withoutBitrate(a.moov.copyOfRange(ba.start, ba.end)).contentEquals(withoutBitrate(b.moov.copyOfRange(bb.start, bb.end)))

/** Ohne "btrt"-Boxen (Datenrate): ist bei jedem Video anders, für das Dekodieren aber unwichtig. */
private fun withoutBitrate(bytes: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < bytes.size) {
        val isBtrt = i + 20 <= bytes.size && ByteBuffer.wrap(bytes, i, 4).int == 20 &&
            String(bytes, i + 4, 4, Charsets.ISO_8859_1) == "btrt"
        if (isBtrt) i += 20 else out.write(bytes[i++].toInt())
    }
    return out.toByteArray()
}

/** Typ des ersten Eintrags in stsd, z. B. "avc1" oder "hvc1". */
private fun sampleEntryType(s: Mp4Source, t: Mp4Track) = String(s.moov, t.stsd.body + 12, 4, Charsets.ISO_8859_1)

private fun visualSize(s: Mp4Source, t: Mp4Track): Pair<Int, Int> {
    val entry = t.stsd.body + 8
    return (s.buf.getShort(entry + 32).toInt() and 0xffff) to (s.buf.getShort(entry + 34).toInt() and 0xffff)
}

/**
 * Drehmatrix einer tkhd-Box ([bytes] samt Kopf) um [by] Grad im Uhrzeigersinn weiterdrehen. Geschrieben wird die
 * Matrix, die auch Android (MediaMuxer.setOrientationHint) schreibt; Spiegelungen kommen bei Handy-Videos nicht vor.
 */
internal fun rotateMatrix(bytes: ByteArray, by: Int) {
    val buf = java.nio.ByteBuffer.wrap(bytes)
    val headerSize = if (buf.getInt(0) == 1) 16 else 8
    val m = headerSize + if (bytes[headerSize].toInt() == 1) 52 else 40
    val a = buf.getInt(m)
    val b = buf.getInt(m + 4)
    val current = when {
        a == 0 && b > 0 -> 90
        a == 0 && b < 0 -> 270
        a < 0 -> 180
        else -> 0
    }
    val one = 0x10000
    val (na, nb, nc, nd) = when (((current + by) % 360 + 360) % 360) {
        90 -> listOf(0, one, -one, 0)
        180 -> listOf(-one, 0, 0, -one)
        270 -> listOf(0, -one, one, 0)
        else -> listOf(one, 0, 0, one)
    }
    buf.putInt(m, na); buf.putInt(m + 4, nb); buf.putInt(m + 8, 0)
    buf.putInt(m + 12, nc); buf.putInt(m + 16, nd); buf.putInt(m + 20, 0)
    buf.putInt(m + 24, 0); buf.putInt(m + 28, 0); buf.putInt(m + 32, 0x40000000)
}

/** Beginn der Drehmatrix in tkhd. */
private fun matrixStart(s: Mp4Source, t: Mp4Track) = t.tkhd.body + if (s.moov[t.tkhd.body].toInt() == 1) 52 else 40
