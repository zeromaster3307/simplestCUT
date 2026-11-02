package app.simplestcut.export

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import app.simplestcut.effects.CropRect
import app.simplestcut.effects.EffectPlan
import app.simplestcut.effects.SectionFx
import app.simplestcut.merge.MixSettings
import app.simplestcut.mp4.Mp4Source
import app.simplestcut.mp4.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToLong

/**
 * Zusammenfügen unpassender Clips (Auflösung, Ausrichtung, Bildrate, HDR): alles neu kodieren mit Media3. Jeder
 * Abschnitt wird ins Ausgabebild eingepasst (schwarze Balken), Drehung und Ton erledigt Media3, HDR wird je nach
 * [MixSettings.keepHdr] behalten oder auf normale Farben umgerechnet.
 *
 * Mit [effects] (je Abschnitt ein Eintrag): nach dem Einpassen ins Grundbild das ganze Video drehen und zuschneiden
 * (ändert die Ausgabegrösse), dann je Abschnitt drehen (ins Ergebnisbild eingepasst) und zoomen. Die Vorschau
 * im Schnittmodus rechnet gleich (PreviewGeometry).
 */
@OptIn(UnstableApi::class)
suspend fun transcodeMix(
    context: Context,
    sources: List<Mp4Source>,
    segments: List<Segment>,
    mix: MixSettings,
    out: File,
    onProgress: (Float) -> Unit,
    effects: EffectPlan? = null,
) {
    val presentation = Presentation.createForWidthAndHeight(mix.width, mix.height, Presentation.LAYOUT_SCALE_TO_FIT)
    val whole = effects?.whole
    val (outW, outH) = whole?.outputSize(mix.width, mix.height) ?: (mix.width to mix.height)
    // Presentation merkt sich beim Einrichten die Eingangsgrösse: pro Verwendung ein eigenes Objekt.
    val fitOut = { Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT) }
    // Datenrate wie beim Grundbild pro Bildpunkt
    val bitrate = (mix.bitrate.toDouble() * outW * outH / (mix.width.toDouble() * mix.height)).roundToLong()
        .coerceIn(4_000_000, 120_000_000).toInt()
    val items = segments.mapIndexed { index, seg ->
        val src = sources[seg.source]
        val v = src.video
        val frames = src.videoFrames
        val toUs = 1_000_000.0 / v.timescale
        val first = frames[seg.frames.first]
        val last = frames[seg.frames.last]
        val next = frames.getOrNull(seg.frames.last + 1)
        val endTime = next?.time ?: (last.time + v.typicalDelta)
        // Media3 nimmt die Bilder mit Zeit in [Anfang, Ende): je ein Viertelbild Abstand, damit Rundungen nicht stören.
        val quarter = (v.typicalDelta * toUs / 4).roundToLong()
        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionUs(((first.time * toUs).roundToLong() - quarter).coerceAtLeast(0))
            .setEndPositionUs((endTime * toUs).roundToLong() - quarter)
            .build()
        EditedMediaItem.Builder(
            MediaItem.Builder().setUri(src.file.toURI().toString()).setClippingConfiguration(clip).build()
        ).setEffects(Effects(emptyList(), videoEffects(presentation, fitOut, effects, effects?.sections?.getOrNull(index)))).build()
    }
    // Clips ohne Ton bekommen Stille, damit der Ton der anderen synchron bleibt.
    val sequence = EditedMediaItemSequence.Builder(items).experimentalSetForceAudioTrack(true).build()
    val composition = Composition.Builder(sequence)
        .setHdrMode(if (mix.keepHdr) Composition.HDR_MODE_KEEP_HDR else Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        .build()
    out.delete()

    // Transformer verlangt einen Thread mit Looper: im Haupt-Thread starten und abfragen.
    withContext(Dispatchers.Main) {
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                    .setEnableFallback(true)
                    .build()
            )
            .build()
        val progress = launch {
            val holder = ProgressHolder()
            while (true) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                delay(200)
            }
        }
        try {
            suspendCancellableCoroutine { cont ->
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) = cont.resume(Unit)
                    override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                        cont.resumeWithException(ExportFailed("Re-encoding failed: ${exception.errorCodeName}", exception))
                    }
                })
                cont.invokeOnCancellation { transformer.cancel() }
                transformer.start(composition, out.absolutePath)
            }
        } finally {
            progress.cancel()
        }
    }
}

/** Bildeffekte eines Abschnitts in Media3-Reihenfolge, siehe [transcodeMix]. */
@OptIn(UnstableApi::class)
private fun videoEffects(base: Presentation, fitOut: () -> Presentation, plan: EffectPlan?, section: SectionFx?): List<Effect> {
    val list = mutableListOf<Effect>(base)
    val whole = plan?.whole ?: return list
    rotate(whole.rotation)?.let { list += it }
    whole.crop?.let { list += crop(it) }
    if (section != null && section.rotation != 0) {
        list += rotate(section.rotation)!!
        list += fitOut()
    }
    section?.zoom?.let { list += crop(it) }
    if (list.size > 1) list += fitOut()
    return list
}

/** Media3 dreht gegen den Uhrzeigersinn. */
@OptIn(UnstableApi::class)
private fun rotate(clockwise: Int): Effect? =
    if (clockwise == 0) null else ScaleAndRotateTransformation.Builder().setRotationDegrees((360 - clockwise) % 360f).build()

/** Ausschnitt in Bruchteilen (y nach unten) als Media3-Crop (Koordinaten -1..1, y nach oben). */
@OptIn(UnstableApi::class)
private fun crop(r: CropRect): Effect = Crop(r.left * 2 - 1, r.right * 2 - 1, 1 - r.bottom * 2, 1 - r.top * 2)
