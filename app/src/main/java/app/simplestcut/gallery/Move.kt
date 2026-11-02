package app.simplestcut.gallery

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import app.simplestcut.storage.MediaAccess
import app.simplestcut.storage.StorageFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Ordner relativ zum internen Speicher anzeigen, z. B. "DCIM/Camera". */
fun relativeToStorage(dir: File): String = StorageFolders.relative(dir)?.ifEmpty { "/" } ?: dir.absolutePath

data class TargetFolder(val dir: File, val count: Int)

/**
 * Alle Ordner, in denen Videos liegen, die meisten Videos zuerst. Ohne den Download-Hauptordner und den Speicher
 * selbst: dorthin kann Android Videos weder verschieben noch eine Ordnerfreigabe erteilen.
 */
fun targetFoldersOf(videos: List<VideoItem>, mediaOnly: Boolean = false): List<TargetFolder> =
    videos.mapNotNull { File(it.path).parentFile }
        .filter { canMoveInto(it) && (!mediaOnly || StorageFolders.isMediaFolder(it)) }
        .groupingBy { it.absolutePath }
        .eachCount()
        .map { (path, count) -> TargetFolder(File(path), count) }
        .sortedByDescending { it.count }

/**
 * Liegt eines der Videos in einem Ordner ohne mögliche Freigabe (Download-Hauptordner)? Dann geht Verschieben nur
 * nach DCIM/Movies/Pictures (siehe [moveVideos]).
 */
fun needsMediaTarget(videos: List<VideoItem>): Boolean =
    videos.any { v -> File(v.path).parentFile?.let { !StorageFolders.canGrant(it) } == true }

/** Kommt [dir] als Ziel in Frage (Medienordner oder per Ordnerfreigabe erreichbar)? */
fun canMoveInto(dir: File): Boolean {
    val rel = StorageFolders.relative(dir) ?: return false
    return rel.isNotEmpty() && rel != Environment.DIRECTORY_DOWNLOADS
}

/** [notAllowed] = Videos, die Android ohne Kopieren nicht dorthin verschieben lässt (z. B. aus dem Download-Hauptordner in einen App-Ordner). */
data class MoveResult(val moved: Int, val failed: List<String>, val notAllowed: List<String> = emptyList())

/**
 * Verschiebt ohne Kopieren (siehe SPEC "Speicherzugriff"):
 * - nach DCIM/Movies/Pictures aus diesen Ordnern oder aus Download über Android, nach einer Nachfrage pro Aktion
 *   (entfällt mit der "Medienverwaltung", ausser bei Dateien aus Download);
 * - sonst (z. B. aus dem WhatsApp-Ordner, nach Download/…) über Ordnerfreigaben für Quelle und Ziel.
 * Gleichnamige Dateien im Ziel werden nicht überschrieben, sondern bekommen " (2)", " (3)" usw. angehängt.
 * Abgelehnte Nachfragen zählen als fehlgeschlagen.
 */
suspend fun moveVideos(context: Context, access: MediaAccess, videos: List<VideoItem>, target: File): MoveResult {
    val failed = mutableListOf<String>()
    val toMove = videos.filter { File(it.path).parentFile?.absolutePath != target.absolutePath }
    if (!target.isDirectory && !withContext(Dispatchers.IO) { target.mkdirs() } && !StorageFolders.isMediaFolder(target)) {
        // Neue Ordner ausserhalb von DCIM/Movies/Pictures legt die App selbst an (geht nur unter Download/).
        return MoveResult(0, toMove.map { it.name })
    }
    val (viaMedia, others) = toMove.partition {
        StorageFolders.isMediaFolder(target) && File(it.path).parentFile?.let(StorageFolders::isMediaSource) == true
    }
    // Ohne mögliche Ordnerfreigabe (Download-Hauptordner) gar nicht erst fragen.
    val (viaFolders, notAllowed) = others.partition {
        StorageFolders.canGrant(target) && File(it.path).parentFile?.let(StorageFolders::canGrant) == true
    }
    var moved = 0

    if (viaMedia.isNotEmpty()) {
        if (access.requestWrite(viaMedia.map { it.uri })) {
            val relPath = StorageFolders.relative(target) + "/"
            withContext(Dispatchers.IO) {
                for (video in viaMedia) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                        put(MediaStore.MediaColumns.DISPLAY_NAME, freeName(target, video.name).name)
                    }
                    val ok = runCatching { context.contentResolver.update(video.uri, values, null, null) > 0 }.getOrDefault(false)
                    if (ok) moved++ else failed += video.name
                }
            }
        } else {
            failed += viaMedia.map { it.name }
        }
    }

    if (viaFolders.isNotEmpty()) {
        val targetTree = access.requestFolder(target)
        val changedPaths = mutableListOf<String>()
        for (video in viaFolders) {
            val source = File(video.path)
            val sourceDir = source.parentFile
            val sourceTree = sourceDir?.let { access.requestFolder(it) }
            if (targetTree == null || sourceTree == null) {
                failed += video.name
                continue
            }
            val dest = withContext(Dispatchers.IO) {
                runCatching { moveDocument(context, access, source, sourceTree, targetTree, target) }.getOrNull()
            }
            if (dest != null) {
                moved++
                changedPaths += source.absolutePath
                changedPaths += dest.absolutePath
            } else {
                failed += video.name
            }
        }
        if (changedPaths.isNotEmpty()) {
            MediaScannerConnection.scanFile(context, changedPaths.toTypedArray(), null, null)
        }
    }
    return MoveResult(moved, failed, notAllowed.map { it.name })
}

/** Verschiebt per Ordnerfreigaben; bei Namensgleichheit im Ziel wird die Quelle vorher umbenannt. */
private fun moveDocument(context: Context, access: MediaAccess, source: File, sourceTree: Uri, targetTree: Uri, target: File): File? {
    val resolver = context.contentResolver
    val sourceDir = source.parentFile ?: return null
    var doc = access.documentIn(sourceTree, source) ?: return null
    val dest = freeName(target, source.name)
    if (dest.name != source.name) {
        doc = DocumentsContract.renameDocument(resolver, doc, dest.name) ?: return null
    }
    val parent = access.documentIn(sourceTree, sourceDir) ?: return null
    val targetDoc = access.documentIn(targetTree, target) ?: return null
    val result = runCatching { DocumentsContract.moveDocument(resolver, doc, parent, targetDoc) }.getOrNull()
    if (result == null && dest.name != source.name) {
        // Zurück zum alten Namen, damit nichts halb erledigt bleibt.
        runCatching { DocumentsContract.renameDocument(resolver, doc, source.name) }
    }
    return if (result != null) dest else null
}

private fun freeName(dir: File, name: String): File {
    var candidate = File(dir, name)
    if (!candidate.exists()) return candidate
    val base = name.substringBeforeLast('.')
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var n = 2
    while (candidate.exists()) {
        candidate = File(dir, "$base ($n)$ext")
        n++
    }
    return candidate
}
