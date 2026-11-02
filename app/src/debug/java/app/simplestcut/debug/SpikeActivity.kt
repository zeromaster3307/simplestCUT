package app.simplestcut.debug

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import app.simplestcut.editor.loadFrameIndex
import app.simplestcut.export.exportCut
import app.simplestcut.mp4.Mp4Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

/**
 * Technischer Test für Schnitte (nur Debug-Version). Start per adb:
 *   am start -n app.simplestcut/.debug.SpikeActivity --es src <pfad> --es out <pfad> --el start <ms> --el end <ms> --es mode <smart|editlist|plain>
 * Ergebnis steht im Log unter dem Tag "Spike".
 */
@OptIn(UnstableApi::class)
class SpikeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val src = intent.getStringExtra("src") ?: return finishWith("FEHLER kein src")
        val out = intent.getStringExtra("out") ?: return finishWith("FEHLER kein out")
        val startMs = intent.getLongExtra("start", 0)
        val endMs = intent.getLongExtra("end", Long.MIN_VALUE)
        val mode = intent.getStringExtra("mode") ?: "smart"

        if (mode == "mix") {
            // Komplettes Neukodieren unterschiedlicher Clips mit Media3: je Quelle ein Abschnitt "s-e" (ms) in segs,
            // Ausgabegrösse --es size WxH (einpassen mit Balken), --ez tonemap true = HDR -> SDR.
            val files = src.split(",")
            val segs = (intent.getStringExtra("segs") ?: "").split(",")
            val (w, h) = (intent.getStringExtra("size") ?: "1080x1920").split("x").map { it.toInt() }
            val tonemap = intent.getBooleanExtra("tonemap", false)
            val items = files.mapIndexed { i, f ->
                val (s, e) = segs[i].split("-").map { it.toLong() * 1000 }
                val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionUs(s).setEndPositionUs(e).build()
                EditedMediaItem.Builder(MediaItem.Builder().setUri(File(f).toURI().toString()).setClippingConfiguration(clip).build())
                    .setEffects(androidx.media3.transformer.Effects(emptyList(),
                        listOf(androidx.media3.effect.Presentation.createForWidthAndHeight(w, h, androidx.media3.effect.Presentation.LAYOUT_SCALE_TO_FIT))))
                    .build()
            }
            val sequence = EditedMediaItemSequence.Builder(items).experimentalSetForceAudioTrack(true).build()
            val composition = Composition.Builder(sequence)
                .setHdrMode(if (tonemap) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL else Composition.HDR_MODE_KEEP_HDR)
                .build()
            File(out).delete()
            val t0 = SystemClock.elapsedRealtime()
            Transformer.Builder(this)
                .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H265)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        finishWith("OK mode=mix zeit=${SystemClock.elapsedRealtime() - t0}ms encoder=${result.videoEncoderName} " +
                            "video=${result.videoConversionProcess} audio=${result.audioConversionProcess} out=$out")
                    }
                    override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                        Log.e(TAG, "Mix-Fehler", exception)
                        finishWith("FEHLER mode=mix ${exception.errorCodeName} ${exception.message} ${exception.cause}")
                    }
                })
                .build()
                .start(composition, out)
            return
        }
        if (mode == "decode") {
            // Datei so dekodieren, wie es Galerie-Apps tun (MediaExtractor + Standard-Decoder für den gemeldeten Typ).
            Thread {
                val result = runCatching {
                    val ex = android.media.MediaExtractor().apply { setDataSource(src) }
                    val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)!!.startsWith("video/") }
                    ex.selectTrack(track)
                    val format = ex.getTrackFormat(track)
                    val mime = format.getString(android.media.MediaFormat.KEY_MIME)!!
                    val name = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
                    val dec = android.media.MediaCodec.createByCodecName(name)
                    dec.configure(format, null, null, 0)
                    dec.start()
                    val info = android.media.MediaCodec.BufferInfo()
                    var inDone = false
                    var frames = 0
                    var inputs = 0
                    while (true) {
                        if (!inDone) {
                            val i = dec.dequeueInputBuffer(5_000)
                            if (i >= 0) {
                                val n = ex.readSampleData(dec.getInputBuffer(i)!!, 0)
                                if (n < 0) {
                                    dec.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                                } else {
                                    dec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance(); inputs++
                                }
                            }
                        }
                        val o = dec.dequeueOutputBuffer(info, 5_000)
                        if (o >= 0) {
                            if (info.size > 0) frames++
                            dec.releaseOutputBuffer(o, false)
                            if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                    dec.stop(); dec.release(); ex.release()
                    "mime=$mime decoder=$name eingabe=$inputs bilder=$frames"
                }
                runOnUiThread {
                    result.onSuccess { finishWith("OK mode=decode $it") }
                        .onFailure { Log.e(TAG, "Dekodierfehler", it); finishWith("FEHLER mode=decode $it") }
                }
            }.start()
            return
        }
        if (mode == "codecs") {
            // Welche Video-Encoder gibt es, mit welchen Profilen (Main10/HDR?) und Funktionen?
            for (info in android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos) {
                if (!info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (!type.startsWith("video/")) continue
                    val caps = info.getCapabilitiesForType(type)
                    val profiles = caps.profileLevels.joinToString(" ") { "${it.profile}/${it.level}" }
                    val features = listOf("hdr-editing", "qp-bounds", "intra-refresh", "encoding-statistics")
                        .filter { runCatching { caps.isFeatureSupported(it) }.getOrDefault(false) }
                    val v = caps.videoCapabilities ?: continue
                    val e = caps.encoderCapabilities ?: continue
                    Log.i(TAG, "ENC ${info.name} hw=${info.isHardwareAccelerated} $type profiles=[$profiles] " +
                        "features=$features size=${v.supportedWidths}x${v.supportedHeights} " +
                        "cq=${e.isBitrateModeSupported(0)} vbr=${e.isBitrateModeSupported(1)} cbr=${e.isBitrateModeSupported(2)} " +
                        "quality=${e.qualityRange}")
                }
            }
            return finishWith("OK mode=codecs")
        }
        if (mode == "app" || mode == "export") {
            // Echter App-Export: --es segs "s1-e1,s2-e2" in ms, je Abschnitt alle Bilder mit Zeit in [s, e).
            // Mehrere Quellen (Zusammenfügen) kommagetrennt in src, Zeiten dann über alle Clips durchgehend.
            val t0 = SystemClock.elapsedRealtime()
            MainScope().launch {
                try {
                    val files = src.split(",").map { File(it) }
                    val frames = loadFrameIndex(withContext(Dispatchers.IO) { files.map { Mp4Source.read(it) } })
                    val ranges = (intent.getStringExtra("segs") ?: "$startMs-$endMs").split(",").map { part ->
                        val (s, e) = part.split("-").map { it.toLong() * 1000 }
                        val first = (0 until frames.size).first { frames.timesUs[it] >= s }
                        val last = (0 until frames.size).last { frames.timesUs[it] < e }
                        first..last
                    }
                    val sources = withContext(Dispatchers.IO) { files.map { Mp4Source.read(it) } }
                    // --es mix BxH: wie in der App bei unpassenden Clips (Auflösung fest vorgegeben).
                    val mix = intent.getStringExtra("mix")?.let { m ->
                        val (w, h) = m.split("x").map { it.toInt() }
                        app.simplestcut.merge.MixPlan(sources.map { app.simplestcut.merge.ClipFormat.of(it) })
                            .settings(app.simplestcut.merge.Resolution(w, h))
                    }
                    val segments = frames.segments(ranges)
                    // Effekte (für alle Abschnitte gleich): --ei wrot 90, --es wcrop l,t,r,b, --ei srot 90, --es szoom l,t,r,b
                    fun rect(key: String) = intent.getStringExtra(key)?.split(",")?.map { it.toFloat() }
                        ?.let { (l, t, r, b) -> app.simplestcut.effects.CropRect(l, t, r, b) }
                    val whole = app.simplestcut.effects.WholeFx(intent.getIntExtra("wrot", 0), rect("wcrop"))
                    val section = app.simplestcut.effects.SectionFx(intent.getIntExtra("srot", 0), rect("szoom"))
                    val effects = if (whole.isNone && section.isNone) null
                    else app.simplestcut.effects.EffectPlan(whole, segments.map { section })
                    Log.i(TAG, "Soll: ${segments.sumOf { it.frames.last - it.frames.first + 1 }} Bilder, mix=${mix?.let { "${it.width}x${it.height} hdr=${it.keepHdr} bitrate=${it.bitrate}" }} effekte=$whole $section")
                    val file = exportCut(this@SpikeActivity, files, segments, app.simplestcut.export.OutputDir.Direct(files.first().parentFile!!), reencode = mode == "export", mix = mix, effects = effects)
                    finishWith("OK mode=$mode zeit=${SystemClock.elapsedRealtime() - t0}ms out=$file")
                } catch (e: Exception) {
                    Log.e(TAG, "App-Export-Fehler", e)
                    finishWith("FEHLER mode=$mode ${e.message}")
                }
            }
            return
        }
        File(out).parentFile?.mkdirs()
        File(out).delete()

        val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs)
        if (endMs != Long.MIN_VALUE) clipping.setEndPositionMs(endMs)
        val item = MediaItem.Builder().setUri(File(src).toURI().toString()).setClippingConfiguration(clipping.build()).build()

        val t0 = SystemClock.elapsedRealtime()
        val transformer = Transformer.Builder(this)
            .experimentalSetTrimOptimizationEnabled(mode == "smart")
            .experimentalSetMp4EditListTrimEnabled(mode == "editlist")
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    val ms = SystemClock.elapsedRealtime() - t0
                    finishWith(
                        "OK mode=$mode zeit=${ms}ms optimization=${result.optimizationResult} " +
                            "video=${result.videoConversionProcess} audio=${result.audioConversionProcess} " +
                            "encoder=${result.videoEncoderName} groesse=${result.fileSizeBytes} out=$out"
                    )
                }

                override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                    val ms = SystemClock.elapsedRealtime() - t0
                    Log.e(TAG, "Export-Fehler", exception)
                    finishWith(
                        "FEHLER mode=$mode zeit=${ms}ms code=${exception.errorCodeName} " +
                            "optimization=${result.optimizationResult} msg=${exception.message} cause=${exception.cause}"
                    )
                }
            })
            .build()
        Log.i(TAG, "START mode=$mode src=$src start=$startMs end=$endMs")
        val segs = intent.getStringExtra("segs")
        if (segs != null) {
            // Mehrere Abschnitte "s1-e1,s2-e2" (ms) als Sequenz, ohne Neukodieren.
            val items = segs.split(",").map { part ->
                val (s, e) = part.split("-").map { it.toLong() }
                val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(s).setEndPositionMs(e).build()
                EditedMediaItem.Builder(
                    MediaItem.Builder().setUri(File(src).toURI().toString()).setClippingConfiguration(clip).build()
                ).build()
            }
            val composition = Composition.Builder(EditedMediaItemSequence.Builder(items).build())
                .setTransmuxVideo(intent.getBooleanExtra("transmux", true))
                .setTransmuxAudio(intent.getBooleanExtra("transmux", true))
                .build()
            Log.i(TAG, "START multi segs=$segs")
            transformer.start(composition, out)
        } else {
            transformer.start(item, out)
        }
    }

    private fun finishWith(message: String) {
        Log.i(TAG, message)
        finish()
    }

    private companion object {
        const val TAG = "Spike"
    }
}
