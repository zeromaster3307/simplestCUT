package app.simplestcut.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

data class VideoItem(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val folderId: Long,
    val folder: String,
    /** Voller Dateipfad, z. B. /storage/emulated/0/DCIM/Camera/VID_1.mp4 */
    val path: String,
    /** Bestes bekanntes Datum in ms seit 1970, siehe [bestDateMs]. */
    val dateMs: Long,
    /** Liegt im Android-Papierkorb; [expiresMs] = wann Android es endgültig löscht. */
    val isTrashed: Boolean = false,
    val expiresMs: Long = 0,
)

/** Liest alle Videos, auf die die App Zugriff hat, neueste zuerst. */
suspend fun loadVideos(context: Context): List<VideoItem> = queryVideos(context, null, null)

/** Videos im Android-Papierkorb. */
suspend fun loadTrashedVideos(context: Context): List<VideoItem> = queryVideos(context, null, null, trashed = true)

/** Das Video mit dem Dateipfad [path], falls Android es schon kennt (z. B. gerade gespeichert). */
suspend fun loadVideoAt(context: Context, path: String): VideoItem? =
    queryVideos(context, "${MediaStore.Video.Media.DATA} = ?", arrayOf(path)).firstOrNull()

private suspend fun queryVideos(
    context: Context,
    selection: String?,
    args: Array<String>?,
    trashed: Boolean = false,
): List<VideoItem> = withContext(Dispatchers.IO) {
    val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.WIDTH,
        MediaStore.Video.Media.HEIGHT,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.BUCKET_ID,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Video.Media.DATE_TAKEN,
        MediaStore.Video.Media.DATE_MODIFIED,
        MediaStore.Video.Media.DATA,
        MediaStore.Video.Media.DATE_EXPIRES,
    )
    val query = Bundle().apply {
        if (selection != null) {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
        }
        if (trashed) putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
    }

    val result = mutableListOf<VideoItem>()
    context.contentResolver.query(collection, projection, query, null)?.use { c ->
        val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
        val name = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
        val duration = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
        val width = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
        val height = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
        val size = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        val bucketId = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
        val bucket = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        val taken = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
        val modified = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
        val data = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
        val expires = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_EXPIRES)
        while (c.moveToNext()) {
            // Android benennt Dateien im Papierkorb um: ".trashed-1760000000-VID_1.mp4"
            val fileName = (c.getString(name) ?: "").replace(trashedPrefix, "")
            result += VideoItem(
                uri = ContentUris.withAppendedId(collection, c.getLong(id)),
                name = fileName,
                durationMs = c.getLong(duration),
                width = c.getInt(width),
                height = c.getInt(height),
                sizeBytes = c.getLong(size),
                folderId = c.getLong(bucketId),
                folder = c.getString(bucket) ?: "",
                path = c.getString(data) ?: "",
                dateMs = bestDateMs(c.getLong(taken), fileName, c.getLong(modified)),
                isTrashed = trashed,
                expiresMs = c.getLong(expires) * 1000,
            )
        }
    }
    result.sortedByDescending { it.dateMs }
}

private val trashedPrefix = Regex("""^\.trashed-\d+-""")

private val whatsAppName = Regex("""^VID-(\d{4})(\d{2})(\d{2})-WA(\d+)""")

/**
 * Viele Videos (WhatsApp, Downloads) haben in Android kein Aufnahmedatum. Reihenfolge der Quellen:
 * 1. Aufnahmedatum aus Android
 * 2. Datum im WhatsApp-Dateinamen (VID-20240512-WA0003.mp4); die laufende Nummer hält die Reihenfolge innerhalb des Tages
 * 3. Änderungsdatum der Datei
 */
private fun bestDateMs(takenMs: Long, fileName: String, modifiedSec: Long): Long {
    if (takenMs > 0) return takenMs
    whatsAppName.find(fileName)?.let { m ->
        val (y, mo, d, n) = m.destructured
        val day = runCatching {
            LocalDate.of(y.toInt(), mo.toInt(), d.toInt()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
        if (day != null) return day + (n.toLongOrNull() ?: 0L).coerceAtMost(86_399L) * 1000
    }
    return modifiedSec * 1000
}

/** Platzhalter-Ordner für den Android-Papierkorb. */
const val TRASH_FOLDER = Long.MIN_VALUE + 1

data class Folder(val id: Long, val name: String, val count: Int)

/** Ordner, die Videos enthalten, nach Anzahl sortiert. */
fun foldersOf(videos: List<VideoItem>): List<Folder> =
    videos.groupBy { it.folderId }
        .map { (id, list) -> Folder(id, list.first().folder, list.size) }
        .sortedByDescending { it.count }

/** Platzhalter beim Start: "Kamera-Ordner, sobald die Videos geladen sind". */
const val CAMERA_FOLDER = Long.MIN_VALUE

/** Ordner-ID der Kamera (DCIM/Camera), falls dort Videos liegen. */
fun cameraFolderId(videos: List<VideoItem>): Long? =
    videos.firstOrNull { it.path.contains("/DCIM/Camera/", ignoreCase = true) }?.folderId

/** "1:23" bzw. "1:02:03". */
fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
