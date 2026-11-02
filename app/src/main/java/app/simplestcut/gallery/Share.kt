package app.simplestcut.gallery

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Öffnet das Android-Teilen-Menü (WhatsApp, Threema, ...) für ein oder mehrere Videos. */
fun shareVideos(context: Context, uris: List<Uri>) {
    if (uris.isEmpty()) return
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }.apply {
        type = "video/*"
        // Damit die Ziel-App die Dateien lesen darf.
        clipData = ClipData.newRawUri(null, uris.first()).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}
