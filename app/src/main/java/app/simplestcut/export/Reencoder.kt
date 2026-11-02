package app.simplestcut.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import app.simplestcut.mp4.EncodedRun
import app.simplestcut.mp4.FrameEncoder
import app.simplestcut.mp4.Mp4Exception
import app.simplestcut.mp4.Mp4Source
import app.simplestcut.mp4.VideoConfig
import app.simplestcut.mp4.VideoFrame
import app.simplestcut.mp4.readSample
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.math.roundToLong

/**
 * Neukodieren der Bilder an Schnittkanten mit den Hardware-Codecs: Decoder schreibt direkt in die Eingabefläche
 * des Encoders (kein Umweg über die CPU, 10 Bit/HDR bleiben erhalten). Pro Aufruf ein neuer Decoder/Encoder, der
 * erste ausgegebene Frame ist damit immer ein Keyframe.
 */
class MediaCodecReencoder : FrameEncoder {
    private val configs = HashMap<Mp4Source, VideoConfig>()

    override fun encode(src: Mp4Source, frames: List<VideoFrame>, out: RandomAccessFile): EncodedRun {
        val t0 = SystemClock.elapsedRealtime()
        val cfg = configs.getOrPut(src) { VideoConfig.of(src) }
        val v = src.video
        val mime = mimeOf(cfg) ?: throw Mp4Exception("Video format ${cfg.entryType} cannot be re-encoded")
        val usPerTick = 1_000_000.0 / v.timescale
        fun us(raw: Long) = (raw * usPerTick).roundToLong()
        val wanted = frames.map { us(it.raw) }.toHashSet()
        if (wanted.size != frames.size) throw Mp4Exception("Frames with identical timestamps")
        // Ab einem Keyframe dekodieren, der vor allen gewünschten Bildern liegt – in Dekodier- und in Anzeige-Reihenfolge
        // (offene GOPs: Bilder kurz vor einem Keyframe brauchen die GOP davor).
        val minRaw = frames.minOf { it.raw }
        var firstSample = frames.minOf { it.sample }
        while (firstSample > 0 && !(v.sync[firstSample] && v.raw(firstSample) <= minRaw)) firstSample--
        val lastSample = frames.maxOf { it.sample }

        val encoder = createEncoder(mime, cfg, src)
        val surface = encoder.createInputSurface()
        encoder.start()
        // Software-Encoder (AV1) können die Bilder des Hardware-Decoders (Qualcomm-eigenes Speicherformat) nicht lesen:
        // dann auch in Software dekodieren.
        val decoder = if (encoder.codecInfo.isHardwareAccelerated) {
            MediaCodec.createDecoderByType(mime)
        } else {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { !it.isEncoder && it.isSoftwareOnly && it.supportedTypes.any { t -> t.equals(mime, true) } }
                ?.let { MediaCodec.createByCodecName(it.name) }
                ?: MediaCodec.createDecoderByType(mime)
        }
        Log.i(TAG, "Decoder ${decoder.name}")
        val offsets = ArrayList<Long>()
        val sizes = ArrayList<Int>()
        val sync = ArrayList<Boolean>()
        try {
            decoder.configure(decoderFormat(mime, cfg, v.sizes.maxOrNull() ?: 0), surface, null, 0)
            decoder.start()
            RandomAccessFile(src.file, "r").use { input ->
                val info = MediaCodec.BufferInfo()
                var next = firstSample
                var inputDone = false
                var decoderDone = false
                var eosSent = false
                var encoderDone = false
                var rendered = 0
                var decoderDoneAt = 0L
                var encodedParams: List<ByteArray> = emptyList()
                while (!encoderDone) {
                    if (!inputDone) {
                        val idx = decoder.dequeueInputBuffer(2_000)
                        if (idx >= 0) {
                            val buf = decoder.getInputBuffer(idx)!!
                            if (next <= lastSample) {
                                val data = cfg.toAnnexB(readSample(input, v, next))
                                buf.clear()
                                buf.put(data)
                                decoder.queueInputBuffer(idx, 0, data.size, us(v.raw(next)), 0)
                                next++
                            } else {
                                decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            }
                        }
                    }
                    if (!decoderDone) {
                        val idx = decoder.dequeueOutputBuffer(info, 2_000)
                        if (idx >= 0) {
                            val render = info.size > 0 && info.presentationTimeUs in wanted
                            decoder.releaseOutputBuffer(idx, render)
                            if (render) rendered++
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                decoderDone = true
                                decoderDoneAt = SystemClock.elapsedRealtime()
                            }
                        }
                    }
                    // Erst Ende melden, wenn der Encoder alle Bilder hat (sonst geht das letzte manchmal verloren).
                    if (decoderDone && !eosSent &&
                        (sizes.size >= rendered || SystemClock.elapsedRealtime() - decoderDoneAt > 3_000)
                    ) {
                        encoder.signalEndOfInputStream()
                        eosSent = true
                    }
                    val idx = encoder.dequeueOutputBuffer(info, 2_000)
                    if (idx >= 0) {
                        val buf = encoder.getOutputBuffer(idx)!!
                        val bytes = ByteArray(info.size)
                        buf.position(info.offset)
                        buf.get(bytes)
                        encoder.releaseOutputBuffer(idx, false)
                        val nals = cfg.splitEncoded(bytes)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            encodedParams = nals
                        } else if (nals.isNotEmpty()) {
                            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            // Keyframes tragen ihre Parameter selbst (im Datenstrom wechseln sie, siehe VideoConfig.inBandEntryType).
                            val withParams = if (key && !cfg.isParamSet(nals[0])) encodedParams + nals else nals
                            val sample = cfg.lengthPrefixed(withParams)
                            val offset = out.length()
                            out.seek(offset)
                            out.write(sample)
                            offsets += offset
                            sizes += sample.size
                            sync += key
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                    }
                }
            }
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
            runCatching { encoder.stop() }
            encoder.release()
            surface.release()
        }
        Log.i(TAG, "${frames.size} Bilder neu kodiert in ${SystemClock.elapsedRealtime() - t0} ms (${sizes.size} erhalten)")
        if (sizes.size != frames.size) {
            throw Mp4Exception("Re-encoding returned ${sizes.size} instead of ${frames.size} frames")
        }
        return EncodedRun(offsets.toLongArray(), sizes.toIntArray(), sync.toBooleanArray())
    }

    private fun decoderFormat(mime: String, cfg: VideoConfig, maxSample: Int) =
        MediaFormat.createVideoFormat(mime, cfg.width, cfg.height).apply {
            val annexB = cfg.paramSets.map { byteArrayOf(0, 0, 0, 1) + it }
            if (cfg.av1Config != null) {
                setByteBuffer("csd-0", ByteBuffer.wrap(cfg.av1Config))
            } else if (cfg.codec == "avc") {
                setByteBuffer("csd-0", ByteBuffer.wrap(annexB.filterIndexed { i, _ -> cfg.paramSets[i][0].toInt() and 0x1f == 7 }.join()))
                setByteBuffer("csd-1", ByteBuffer.wrap(annexB.filterIndexed { i, _ -> cfg.paramSets[i][0].toInt() and 0x1f == 8 }.join()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(annexB.join()))
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxSample + 1024)
            applyColor(cfg)
        }

    private fun createEncoder(mime: String, cfg: VideoConfig, src: Mp4Source): MediaCodec {
        val v = src.video
        val seconds = (v.dts.last() + v.deltas.last()).toDouble() / v.timescale
        val sourceBitrate = v.sizes.sumOf { it.toLong() } * 8 / seconds.coerceAtLeast(0.1)
        val format = encoderFormat(mime, cfg, v.timescale.toFloat() / v.typicalDelta)
        val name = findEncoder(format)
            ?: throw Mp4Exception("No suitable encoder for ${cfg.codec}, ${cfg.bitDepth} bit, ${cfg.width}×${cfg.height}")
        // Kurze Stücke beginnen mit einem Keyframe (teuer): deutlich mehr Datenrate als die Quelle. Software-Encoder
        // (AV1) können oft nur wenig, dann deren Höchstwert.
        val range = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first { it.name == name }
            .getCapabilitiesForType(mime).videoCapabilities?.bitrateRange
        val bitrate = (sourceBitrate * 2).roundToLong().coerceIn(4_000_000, 200_000_000).toInt()
        format.setInteger(MediaFormat.KEY_BIT_RATE, if (range != null) bitrate.coerceIn(range.lower, range.upper) else bitrate)
        Log.i(TAG, "Encoder $name: $format")
        return MediaCodec.createByCodecName(name).apply { configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
    }

    companion object {
        private const val TAG = "Reencoder"

        private fun mimeOf(cfg: VideoConfig) = when (cfg.codec) {
            "avc" -> MediaFormat.MIMETYPE_VIDEO_AVC
            "hevc" -> MediaFormat.MIMETYPE_VIDEO_HEVC
            "av1" -> MediaFormat.MIMETYPE_VIDEO_AV1
            else -> null
        }

        private fun encoderFormat(mime: String, cfg: VideoConfig, fps: Float) =
            MediaFormat.createVideoFormat(mime, cfg.width, cfg.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setFloat(MediaFormat.KEY_FRAME_RATE, fps)
                setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 10f)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                when (cfg.codec) {
                    "hevc" -> setInteger(
                        MediaFormat.KEY_PROFILE,
                        if (cfg.bitDepth > 8) MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                        else MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,
                    )
                    "av1" -> setInteger(
                        MediaFormat.KEY_PROFILE,
                        if (cfg.bitDepth > 8) MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10
                        else MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8,
                    )
                }
                applyColor(cfg)
            }

        /**
         * Encoder, der dieses Format kann (Grösse, Bildrate, Farbtiefe), sonst null. Der AV1-Software-Encoder meldet
         * eine Flächengrenze knapp unter 1080p (1920×1072), kodiert 1920×1080 aber einwandfrei (Xiaomi 14 getestet):
         * bei AV1 reicht deshalb die Kantenlänge.
         */
        private fun findEncoder(format: MediaFormat): String? {
            val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            list.findEncoderForFormat(format)?.let { return it }
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime != MediaFormat.MIMETYPE_VIDEO_AV1) return null
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)
            return list.codecInfos.firstOrNull { info ->
                info.isEncoder && info.supportedTypes.contains(mime) &&
                    info.getCapabilitiesForType(mime).videoCapabilities?.let { w <= it.supportedWidths.upper && h <= it.supportedHeights.upper } == true
            }?.name
        }

        /**
         * Kann [src] an den Schnittkanten neu kodiert werden? H.264/H.265 immer (Hardware), AV1 nur mit 8 Bit (10 Bit
         * über den Software-Weg ungetestet) und wenn ein Encoder die Grösse schafft (Xiaomi 14: nur Software, bis
         * 1920 Pixel Kantenlänge).
         */
        fun canReencode(src: Mp4Source): Boolean {
            val cfg = VideoConfig.of(src)
            val mime = mimeOf(cfg) ?: return false
            if (cfg.codec != "av1") return true
            if (cfg.bitDepth != 8) return false
            val v = src.video
            return findEncoder(encoderFormat(mime, cfg, v.timescale.toFloat() / v.typicalDelta)) != null
        }

        private fun MediaFormat.applyColor(cfg: VideoConfig) {
            when (cfg.colorPrimaries) {
                1 -> setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                5 -> setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_PAL)
                6 -> setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC)
                9 -> setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            }
            when (cfg.transfer) {
                1, 6, 14, 15 -> setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                16 -> setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_ST2084)
                18 -> setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
            }
            cfg.fullRange?.let {
                setInteger(MediaFormat.KEY_COLOR_RANGE, if (it) MediaFormat.COLOR_RANGE_FULL else MediaFormat.COLOR_RANGE_LIMITED)
            }
        }

        private fun List<ByteArray>.join(): ByteArray {
            val out = ByteArrayOutputStream()
            forEach { out.write(it) }
            return out.toByteArray()
        }
    }
}
