package app.simplestcut.gallery

import android.content.res.Resources
import app.simplestcut.R
import androidx.compose.ui.res.stringResource
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.simplestcut.merge.ClipFormat
import app.simplestcut.mp4.Mp4Source
import app.simplestcut.mp4.VideoConfig
import app.simplestcut.storage.LocalMediaAccess
import app.simplestcut.storage.MediaAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Infos zu einem Video (Galerie mit einem markierten Video, "i" im Player): Datei und Kodierung.
 * [onRenamed] = Umbenennen möglich (nicht im Papierkorb), bekommt das Video mit neuem Namen.
 */
@Composable
fun VideoInfoDialog(video: VideoItem, onDismiss: () -> Unit, onRenamed: ((VideoItem) -> Unit)?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var details by remember(video) { mutableStateOf<List<Pair<String, String>>?>(null) }
    LaunchedEffect(video) { details = videoDetails(video, context.resources) }
    var renaming by remember { mutableStateOf(false) }
    var newName by remember(video) { mutableStateOf(video.name.substringBeforeLast('.')) }
    var renameError by remember { mutableStateOf<String?>(null) }
    val access = LocalMediaAccess.current

    if (renaming && onRenamed != null) {
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.rename)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it; renameError = null },
                    singleLine = true,
                    suffix = { Text(extensionOf(video.name)) },
                    isError = renameError != null,
                    supportingText = renameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            val renamed = renameVideo(context, access, video, newName)
                            renaming = false
                            onRenamed(renamed)
                        } catch (e: RenameFailed) {
                            renameError = e.message
                        }
                    }
                }) { Text(stringResource(R.string.rename)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.info)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for ((label, value) in fileRows(video, context.resources) + details.orEmpty()) {
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(96.dp),
                        )
                        Text(value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (details == null) Text(stringResource(R.string.reading_file), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = {
            if (onRenamed != null) {
                TextButton(onClick = { renaming = true }) { Text(stringResource(R.string.rename)) }
            }
        },
    )
}

private fun extensionOf(name: String) = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }

/** Angaben, die Android schon kennt. */
private fun fileRows(video: VideoItem, res: Resources): List<Pair<String, String>> = listOf(
    res.getString(R.string.info_name) to video.name,
    res.getString(R.string.info_folder) to (File(video.path).parentFile?.let { relativeToStorage(it) } ?: "?"),
    res.getString(R.string.info_size) to formatBytes(video.sizeBytes),
    res.getString(R.string.info_date) to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(video.dateMs)),
    res.getString(R.string.info_duration) to formatDuration(video.durationMs),
)

/** Angaben aus der Datei selbst (eigener MP4-Leser); bei anderen Formaten nur die Auflösung aus Android. */
private suspend fun videoDetails(video: VideoItem, res: Resources): List<Pair<String, String>> = withContext(Dispatchers.IO) {
    try {
        val src = Mp4Source.read(File(video.path))
        val cfg = VideoConfig.of(src)
        val clip = ClipFormat.of(src)
        val v = src.video
        val seconds = (v.dts.last() + v.deltas.last() - v.dts.first()).toDouble() / v.timescale
        val fps = if (seconds > 0) v.count / seconds else 0.0
        val format = codecName(cfg.entryType) + if (cfg.bitDepth > 8) res.getString(R.string.info_bits, cfg.bitDepth) else ""
        val colors = buildList {
            add(
                when (cfg.transfer) {
                    16 -> "HDR10"
                    18 -> "HDR (HLG)"
                    else -> "SDR"
                }
            )
            if (cfg.dolbyVision || cfg.entryType in listOf("dvh1", "dvhe", "dva1", "dvav", "dav1")) add("Dolby Vision")
        }.joinToString(" + ")
        listOf(
            res.getString(R.string.info_resolution) to "${clip.width} × ${clip.height}",
            res.getString(R.string.info_fps) to formatFps(fps),
            res.getString(R.string.info_format) to format,
            res.getString(R.string.info_colors) to colors,
            res.getString(R.string.info_bitrate) to "%.1f Mbit/s".format(Locale.getDefault(), clip.bitrate / 1_000_000),
            res.getString(R.string.info_audio) to audioText(src, res),
        )
    } catch (e: Exception) {
        listOf(
            res.getString(R.string.info_resolution) to "${video.width} × ${video.height}",
            res.getString(R.string.info_details) to res.getString(R.string.info_unreadable, e.message ?: res.getString(R.string.info_unknown_format)),
        )
    }
}

private fun codecName(entryType: String) = when (entryType) {
    "avc1", "avc3" -> "H.264 (AVC)"
    "hvc1", "hev1", "dvh1", "dvhe" -> "H.265 (HEVC)"
    "av01", "dav1" -> "AV1"
    "vp09" -> "VP9"
    "mp4v" -> "MPEG-4"
    else -> entryType
}

private fun formatFps(fps: Double): String {
    val rounded = fps.roundToInt()
    return if (abs(fps - rounded) < 0.05) "$rounded fps" else "%.2f fps".format(Locale.getDefault(), fps)
}

private fun audioText(src: Mp4Source, res: Resources): String {
    val track = src.audio.firstOrNull() ?: return res.getString(R.string.audio_none)
    val entry = track.stsd.body + 8
    val type = String(src.moov, entry + 4, 4, Charsets.ISO_8859_1)
    val name = when (type) {
        "mp4a" -> "AAC"
        "Opus" -> "Opus"
        "ac-3" -> "AC-3"
        "ec-3" -> "E-AC-3"
        "fLaC" -> "FLAC"
        else -> type
    }
    // AudioSampleEntry: Kanalzahl nach Grösse/Typ (8), Reserviert/Index (8) und Version usw. (8).
    val channels = src.buf.getShort(entry + 24).toInt() and 0xffff
    val channelText = when (channels) {
        1 -> res.getString(R.string.audio_mono)
        2 -> res.getString(R.string.audio_stereo)
        else -> res.getString(R.string.audio_channels, channels)
    }
    val khz = track.timescale / 1000.0
    val rate = if (khz == khz.roundToInt().toDouble()) "${khz.roundToInt()} kHz" else "%.1f kHz".format(Locale.getDefault(), khz)
    return "$name, $rate, $channelText"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.2f GB".format(Locale.getDefault(), bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(Locale.getDefault(), bytes / 1e6)
    else -> "%.0f kB".format(Locale.getDefault(), bytes / 1e3)
}

class RenameFailed(message: String) : Exception(message)

/**
 * Benennt das Video im selben Ordner um (Endung bleibt), über Android: Eintrag und Datei zusammen, die Video-ID
 * bleibt gleich. Android fragt vorher nach, ausser mit der "Medienverwaltung" (siehe [MediaAccess.requestWrite]).
 */
suspend fun renameVideo(context: Context, access: MediaAccess, video: VideoItem, newBase: String): VideoItem {
    val base = newBase.trim()
    if (base.isEmpty()) throw RenameFailed(context.getString(R.string.rename_empty))
    if (base.startsWith(".") || base.any { it in "/\\:*?\"<>|" || it.code < 32 }) {
        throw RenameFailed(context.getString(R.string.rename_invalid))
    }
    val source = File(video.path)
    val dir = source.parentFile ?: throw RenameFailed(context.getString(R.string.rename_no_folder))
    val target = File(dir, base + extensionOf(video.name))
    if (target.name == source.name) return video
    // Nur Gross-/Kleinschreibung geändert: das Dateisystem hält das für dieselbe Datei.
    if (withContext(Dispatchers.IO) { target.exists() } && !target.name.equals(source.name, ignoreCase = true)) {
        throw RenameFailed(context.getString(R.string.rename_exists))
    }
    if (!access.requestWrite(listOf(video.uri))) throw RenameFailed(context.getString(R.string.rename_denied))
    return withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, target.name) }
        val ok = runCatching { context.contentResolver.update(video.uri, values, null, null) > 0 }.getOrDefault(false)
        if (!ok) throw RenameFailed(context.getString(R.string.rename_failed))
        loadVideoAt(context, target.absolutePath) ?: video.copy(name = target.name, path = target.absolutePath)
    }
}
