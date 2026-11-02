package app.simplestcut.mp4

import java.io.RandomAccessFile

/**
 * Kodier-Angaben aus dem Videoeintrag in stsd (avcC/hvcC/av1C, colr) – das braucht das Neukodieren an den
 * Schnittkanten (Decoder einrichten, Encoder passend einstellen, Parameter in den Datenstrom setzen).
 *
 * "Einheiten" sind bei H.264/H.265 NAL-Einheiten (im Sample mit Längenfeld davor), bei AV1 OBUs (tragen ihre Länge
 * selbst, ein Sample ist eine Folge von OBUs).
 */
class VideoConfig(
    /**
     * "avc" (H.264), "hevc" (H.265, auch Dolby Vision Profil 8) oder "av1"; null = andere Formate: kein Smart Cut,
     * beim Exportieren wird alles neu kodiert.
     */
    val codec: String?,
    val entryType: String,
    val width: Int,
    val height: Int,
    /** Länge des Längenfelds vor jeder NAL-Einheit in den Samples (meist 4). */
    val nalLengthSize: Int,
    /** Parameter-Einheiten ohne Längenfeld, in Reihenfolge: VPS/SPS/PPS bzw. bei AV1 der Sequence Header. */
    val paramSets: List<ByteArray>,
    val bitDepth: Int,
    /** Farbangaben aus "colr" (nclx) nach ISO/IEC 23091-2, null = unbekannt. */
    val colorPrimaries: Int?,
    val transfer: Int?,
    val fullRange: Boolean?,
    /** Dolby-Vision-Angaben (dvcC/dvvC) vorhanden. */
    val dolbyVision: Boolean = false,
    /** Inhalt der av1C-Box (für den Decoder, "csd-0"), sonst null. */
    val av1Config: ByteArray? = null,
) {
    /**
     * Typ des Eintrags, wenn die Parameter im Datenstrom wechseln (neu kodierte Stücke haben eigene SPS/PPS).
     * Die Varianten mit "Parametern im Datenstrom" erlauben das ausdrücklich.
     * H.264 bleibt avc1/dva1: Androids Medien-Scanner kennt avc3 nicht und trägt die Datei sonst als Audio ein
     * (fehlt dann in jeder Galerie). Decoder lesen die SPS/PPS im Datenstrom trotzdem.
     */
    val inBandEntryType: String
        get() = when (entryType) {
            "hvc1" -> "hev1"
            "dvh1" -> "dvhe"
            else -> entryType
        }

    /** Ist die Einheit eine Parameter-Menge (VPS/SPS/PPS bzw. AV1-Sequence-Header)? */
    fun isParamSet(nal: ByteArray, at: Int = 0): Boolean = when (codec) {
        "avc" -> (nal[at].toInt() and 0x1f) in 7..8
        "av1" -> obuType(nal[at]) == OBU_SEQUENCE_HEADER
        else -> ((nal[at].toInt() shr 1) and 0x3f) in 32..34
    }

    /** Einheiten so, wie sie in den Samples stehen (H.26x mit Längenfeld, AV1-OBUs einfach hintereinander). */
    fun lengthPrefixed(nals: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (nal in nals) {
            if (codec != "av1") for (k in nalLengthSize - 1 downTo 0) out.write((nal.size shr (8 * k)) and 0xff)
            out.write(nal)
        }
        return out.toByteArray()
    }

    /**
     * Ein Sample der Quelle mit den Parametern der Quelle davor (nach einem neu kodierten Stück, dessen Parameter
     * der Decoder sonst weiter benutzen würde). AV1: nur wenn der Sequence Header fehlt, und hinter einem
     * Temporal Delimiter (der muss vorn stehen).
     */
    fun withParams(sample: ByteArray): ByteArray {
        if (codec != "av1") return lengthPrefixed(paramSets) + sample
        val obus = splitObus(sample)
        if (obus.any { isParamSet(it) }) return sample
        val td = obus.takeWhile { obuType(it[0]) == OBU_TEMPORAL_DELIMITER }
        return lengthPrefixed(td + paramSets + obus.drop(td.size))
    }

    /**
     * Ausgabe des Encoders (ein Bild bzw. die Parameter) in Einheiten zerlegen: H.26x aus dem Annex-B-Strom, AV1 als
     * OBUs ohne Temporal Delimiter (die stehen in MP4 nicht im Sample). Die Parameter von AV1-Encodern kommen
     * teils als av1C-Inhalt (4 Bytes Kopf, dann OBUs).
     */
    fun splitEncoded(data: ByteArray): List<ByteArray> {
        if (codec != "av1") return splitAnnexB(data)
        val obus = if (data.isNotEmpty() && data[0].toInt() and 0xff == 0x81) data.copyOfRange(4, data.size) else data
        return splitObus(obus).filter { obuType(it[0]) != OBU_TEMPORAL_DELIMITER }
    }

    /** Sample (Längenfelder) als Annex-B-Strom (Startcodes), so wie ihn MediaCodec erwartet. AV1: unverändert. */
    fun toAnnexB(sample: ByteArray, size: Int = sample.size): ByteArray {
        if (codec == "av1") return if (size == sample.size) sample else sample.copyOf(size)
        val out = java.io.ByteArrayOutputStream(size + 64)
        var p = 0
        while (p + nalLengthSize <= size) {
            var len = 0
            for (k in 0 until nalLengthSize) len = (len shl 8) or (sample[p + k].toInt() and 0xff)
            p += nalLengthSize
            if (len <= 0 || p + len > size) break
            out.write(START_CODE)
            out.write(sample, p, len)
            p += len
        }
        return out.toByteArray()
    }

    companion object {
        private val START_CODE = byteArrayOf(0, 0, 0, 1)
        private const val OBU_SEQUENCE_HEADER = 1
        private const val OBU_TEMPORAL_DELIMITER = 2

        private fun obuType(header: Byte) = (header.toInt() shr 3) and 0x0f

        /**
         * AV1-OBUs (mit Kopf und Längenfeld) einzeln. Ein OBU ohne Längenfeld reicht bis zum Ende. Kaputte Daten:
         * Exception, damit nichts Falsches geschrieben wird.
         */
        fun splitObus(data: ByteArray): List<ByteArray> {
            val list = mutableListOf<ByteArray>()
            var p = 0
            while (p < data.size) {
                val header = data[p].toInt()
                var q = p + 1 + if (header and 0x04 != 0) 1 else 0
                val end = if (header and 0x02 != 0) {
                    var size = 0L
                    var shift = 0
                    do {
                        if (q >= data.size || shift > 56) throw Mp4Exception("AV1 data incomplete")
                        val b = data[q++].toInt() and 0xff
                        size = size or ((b and 0x7f).toLong() shl shift)
                        shift += 7
                    } while (b and 0x80 != 0)
                    q + size
                } else {
                    data.size.toLong()
                }
                if (end > data.size) throw Mp4Exception("AV1 data incomplete")
                list += data.copyOfRange(p, end.toInt())
                p = end.toInt()
            }
            return list
        }

        /** Annex-B-Strom (Startcodes 00 00 01 / 00 00 00 01) in NAL-Einheiten zerlegen. */
        fun splitAnnexB(data: ByteArray): List<ByteArray> {
            val starts = mutableListOf<Int>() // Beginn der NAL-Einheit (nach dem Startcode)
            val codeStarts = mutableListOf<Int>()
            var i = 0
            while (i + 3 <= data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                    codeStarts += if (i > 0 && data[i - 1].toInt() == 0) i - 1 else i
                    starts += i + 3
                    i += 3
                } else {
                    i++
                }
            }
            return starts.indices.map { k ->
                val end = if (k + 1 < starts.size) codeStarts[k + 1] else data.size
                data.copyOfRange(starts[k], end)
            }.filter { it.isNotEmpty() }
        }

        fun of(src: Mp4Source): VideoConfig {
            val b = src.buf
            val stsd = src.video.stsd
            val entry = stsd.body + 8
            val entryEnd = entry + b.getInt(entry)
            val type = String(src.moov, entry + 4, 4, Charsets.ISO_8859_1)
            val width = b.getShort(entry + 32).toInt() and 0xffff
            val height = b.getShort(entry + 34).toInt() and 0xffff
            val children = boxes(b, entry + 86, entryEnd)
            var codec: String? = null
            var nalLen = 4
            var bitDepth = 8
            val params = mutableListOf<ByteArray>()
            fun nal(p: Int, len: Int) = src.moov.copyOfRange(p, p + len)
            children.firstOrNull { it.type == "hvcC" }?.let { c ->
                codec = "hevc"
                val p0 = c.body
                bitDepth = (src.moov[p0 + 17].toInt() and 7) + 8
                nalLen = (src.moov[p0 + 21].toInt() and 3) + 1
                val arrays = src.moov[p0 + 22].toInt() and 0xff
                var p = p0 + 23
                repeat(arrays) {
                    val count = b.getShort(p + 1).toInt() and 0xffff
                    p += 3
                    repeat(count) {
                        val len = b.getShort(p).toInt() and 0xffff
                        params += nal(p + 2, len)
                        p += 2 + len
                    }
                }
            }
            if (codec == null) children.firstOrNull { it.type == "avcC" }?.let { c ->
                codec = "avc"
                val p0 = c.body
                nalLen = (src.moov[p0 + 4].toInt() and 3) + 1
                var p = p0 + 6
                repeat(src.moov[p0 + 5].toInt() and 0x1f) {
                    val len = b.getShort(p).toInt() and 0xffff
                    params += nal(p + 2, len); p += 2 + len
                }
                val pps = src.moov[p].toInt() and 0xff
                p++
                repeat(pps) {
                    val len = b.getShort(p).toInt() and 0xffff
                    params += nal(p + 2, len); p += 2 + len
                }
            }
            // AV1: Farbtiefe aus av1C; der Sequence Header steht dort (configOBUs) oder im ersten Keyframe.
            var av1Config: ByteArray? = null
            if (codec == null) children.firstOrNull { it.type == "av1C" }?.let { c ->
                val flags = src.moov[c.body + 2].toInt()
                bitDepth = if (flags and 0x40 != 0) (if (flags and 0x20 != 0) 12 else 10) else 8
                av1Config = src.moov.copyOfRange(c.body, c.end)
                val fromBox = splitObus(src.moov.copyOfRange(c.body + 4, c.end)).filter { obuType(it[0]) == OBU_SEQUENCE_HEADER }
                val seqHeader = fromBox.firstOrNull() ?: run {
                    val v = src.video
                    val key = (0 until v.count).firstOrNull { v.sync[it] } ?: 0
                    val sample = RandomAccessFile(src.file, "r").use { readSample(it, v, key) }
                    splitObus(sample).firstOrNull { obuType(it[0]) == OBU_SEQUENCE_HEADER }
                }
                if (seqHeader != null) {
                    codec = "av1"
                    params += seqHeader
                }
            }
            var primaries: Int? = null
            var transfer: Int? = null
            var fullRange: Boolean? = null
            children.firstOrNull { it.type == "colr" }?.let { c ->
                if (String(src.moov, c.body, 4, Charsets.ISO_8859_1) == "nclx") {
                    primaries = b.getShort(c.body + 4).toInt() and 0xffff
                    transfer = b.getShort(c.body + 6).toInt() and 0xffff
                    fullRange = (src.moov[c.body + 10].toInt() and 0x80) != 0
                }
            }
            return VideoConfig(
                codec,
                type, width, height, nalLen, params, bitDepth, primaries, transfer, fullRange,
                dolbyVision = children.any { it.type == "dvcC" || it.type == "dvvC" },
                av1Config = av1Config,
            )
        }
    }
}

/** Liest die Bytes von Sample [i] der Spur [t] aus [input]. */
fun readSample(input: RandomAccessFile, t: Mp4Track, i: Int): ByteArray {
    val bytes = ByteArray(t.sizes[i])
    input.seek(t.offsets[i])
    input.readFully(bytes)
    return bytes
}

/** Neu kodierte Bilder (je gewünschtem Bild eins, Anzeige-Reihenfolge) in einer Zwischendatei. */
class EncodedRun(val offsets: LongArray, val sizes: IntArray, val sync: BooleanArray)

/**
 * Kodiert die Bilder [frames] (Anzeige-Reihenfolge, alle aus derselben GOP bzw. zusammenhängend) aus [src] neu und
 * hängt sie an [out] an: Samples mit Längenfeldern wie in der Quelle, Keyframes mit Parametern davor.
 */
fun interface FrameEncoder {
    fun encode(src: Mp4Source, frames: List<VideoFrame>, out: RandomAccessFile): EncodedRun
}
