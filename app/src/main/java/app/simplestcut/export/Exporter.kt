package app.simplestcut.export

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.provider.DocumentsContract
import app.simplestcut.storage.MediaAccess
import app.simplestcut.storage.StorageFolders
import app.simplestcut.effects.EffectPlan
import app.simplestcut.mp4.Mp4Cutter
import app.simplestcut.mp4.Mp4Source
import app.simplestcut.mp4.Segment
import app.simplestcut.merge.ClipFormat
import app.simplestcut.merge.MixPlan
import app.simplestcut.merge.MixSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.io.File

class ExportFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Zielordner des Ergebnisses und wie die App dorthin schreiben darf (siehe SPEC "Speicherzugriff"). */
sealed interface OutputDir {
    val dir: File

    /** DCIM, Movies, Pictures, Download: die App legt die Datei direkt an. */
    data class Direct(override val dir: File) : OutputDir

    /** Andere Ordner (z. B. WhatsApp): über die Ordnerfreigabe [tree]; die Datei entsteht erst im Cache. */
    data class Granted(override val dir: File, val tree: Uri) : OutputDir
}

/**
 * Speichert die behaltenen Bildbereiche (Anzeige-Reihenfolge, beide Enden eingeschlossen) verlustfrei
 * als <name>_cut.mp4 (bzw. _cut2, ...) neben dem Original. Siehe SPEC "Schnittverfahren".
 */
suspend fun exportCut(
    context: Context,
    source: File,
    ranges: List<IntRange>,
    onProgress: (Float) -> Unit = {},
): File = exportCut(context, listOf(source), ranges.map { Segment(0, it) }, OutputDir.Direct(source.parentFile!!), onProgress)

/**
 * Speichert Abschnitte aus einer oder mehreren Quellen verlustfrei hintereinander, in [output] (normalerweise der
 * Ordner der ersten Quelle): eine Quelle als <name>_cut.mp4, mehrere als <name>_merged.mp4 (bei Namenskonflikt mit 2, 3, ...).
 */
suspend fun exportCut(
    context: Context,
    sources: List<File>,
    segments: List<Segment>,
    output: OutputDir,
    onProgress: (Float) -> Unit = {},
    /** Exportieren: Schnittkanten neu kodieren statt versteckter Bilder (siehe SPEC "Schnittverfahren"). */
    reencode: Boolean = false,
    /** Unpassende Clips zusammenfügen: alles neu kodieren, siehe [transcodeMix]. */
    mix: MixSettings? = null,
    /** Exportieren ohne Smart Cut: alles neu kodieren (z. B. nachdem der Smart Cut gescheitert ist). */
    fullReencode: Boolean = false,
    /** Effekte (je Abschnitt in [segments] ein Eintrag): Drehen des ganzen Videos geht verlustfrei, alles andere neu kodiert. */
    effects: EffectPlan? = null,
): File {
    val first = sources.first()
    val target = freeName(output.dir, first.name, if (sources.size > 1) "_merged" else "_cut")
    // Über eine Ordnerfreigabe wird erst im Cache geschrieben und am Ende hineinkopiert.
    val work = if (output is OutputDir.Granted) File(context.cacheDir, "export.mp4").apply { delete() } else target
    try {
        val mp4 = withContext(Dispatchers.IO) { sources.map { Mp4Source.read(it) } }
        // Ohne Smart Cut (z. B. AV1 in 4K): alles neu kodieren, in der Grösse der Quelle.
        val fxReencode = effects?.needsReencode == true
        val fullMix = mix ?: if (fxReencode || (reencode && (fullReencode || !smartCutPossible(mp4)))) fullReencodeSettings(mp4) else null
        if (fullMix != null) {
            transcodeMix(context, mp4, segments, fullMix, work, onProgress, effects)
        } else {
            val job = coroutineContext.job
            withContext(Dispatchers.IO) {
                // Abbrechen: der Fortschritt wird regelmässig gemeldet, dort wird geprüft.
                Mp4Cutter(mp4, effects?.whole?.rotation ?: 0).write(segments, work, { job.ensureActive(); onProgress(it) }, if (reencode) MediaCodecReencoder() else null)
            }
        }
        if (output is OutputDir.Granted) withContext(Dispatchers.IO) { copyInto(context, output, work, target) }
        // Gleiches Änderungsdatum wie das (erste) Original, damit die Galerie das Ergebnis daneben einsortiert
        // (geht nur bei selbst angelegten Dateien).
        target.setLastModified(first.lastModified())
    } catch (e: CancellationException) {
        work.delete()
        target.delete()
        throw e
    } catch (e: ExportFailed) {
        work.delete()
        target.delete()
        throw e
    } catch (e: Exception) {
        work.delete()
        target.delete()
        throw ExportFailed(e.message ?: e.toString(), e)
    }
    val out = target
    // Warten, bis Android die neue Datei kennt (damit sie danach gleich geöffnet werden kann).
    withTimeoutOrNull(5_000) {
        suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), null) { _, _ -> cont.resume(Unit) }
        }
    }
    return out
}

/**
 * Wohin das Ergebnis kommt: neben das Original [dir]. In Ordnern ausserhalb von DCIM/Movies/Pictures/Download
 * (z. B. WhatsApp) per Ordnerfreigabe; ohne Freigabe in den Ausweichordner Movies/simplestCUT.
 */
suspend fun outputDirFor(access: MediaAccess, dir: File): OutputDir {
    if (StorageFolders.canCreateIn(dir)) return OutputDir.Direct(dir)
    access.requestFolder(dir)?.let { return OutputDir.Granted(dir, it) }
    val fallback = StorageFolders.fallback
    withContext(Dispatchers.IO) { fallback.mkdirs() }
    return OutputDir.Direct(fallback)
}

/** Kopiert das fertige Ergebnis [work] über die Ordnerfreigabe nach [target] und löscht es danach. */
private fun copyInto(context: Context, output: OutputDir.Granted, work: File, target: File) {
    val resolver = context.contentResolver
    val dirDoc = DocumentsContract.buildDocumentUriUsingTree(
        output.tree, "primary:" + (StorageFolders.relative(output.dir) ?: throw ExportFailed("Folder not reachable")),
    )
    val doc = DocumentsContract.createDocument(resolver, dirDoc, "video/mp4", target.name)
        ?: throw ExportFailed("Could not create the file in the folder")
    try {
        resolver.openOutputStream(doc)!!.use { out -> work.inputStream().use { it.copyTo(out, 1 shl 20) } }
    } catch (e: Exception) {
        runCatching { DocumentsContract.deleteDocument(resolver, doc) }
        throw e
    } finally {
        work.delete()
    }
}

/** Können die Quellen beim Exportieren nur an den Schnittkanten neu kodiert werden (H.264/H.265, AV1 bis 1080p)? */
fun smartCutPossible(sources: List<Mp4Source>) = sources.all { MediaCodecReencoder.canReencode(it) }

private fun fullReencodeSettings(sources: List<Mp4Source>): MixSettings {
    val plan = MixPlan(sources.map { ClipFormat.of(it) })
    return plan.settings(plan.resolutions(plan.defaultOrientation).first())
}

private fun freeName(dir: File, sourceName: String, suffix: String): File {
    val base = sourceName.substringBeforeLast('.')
    var candidate = File(dir, "$base$suffix.mp4")
    var n = 2
    while (candidate.exists()) {
        candidate = File(dir, "$base$suffix$n.mp4")
        n++
    }
    return candidate
}
