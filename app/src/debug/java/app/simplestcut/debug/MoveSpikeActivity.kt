package app.simplestcut.debug

import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log

/**
 * Verschieben per MediaStore testen (nur Debug). Start per adb:
 *   am start -n app.simplestcut/.debug.MoveSpikeActivity --es src <pfad> --es to <video|files>:<RELATIVE_PATH>[,...]
 * Ergebnis im Log unter "MoveSpike".
 */
class MoveSpikeActivity : Activity() {
    private var uri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val src = intent.getStringExtra("src") ?: return done("FEHLER kein src")
        val col = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val id = contentResolver.query(col, arrayOf(MediaStore.MediaColumns._ID), "${MediaStore.MediaColumns.DATA}=?", arrayOf(src), null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return done("FEHLER nicht gefunden")
        // --ez files true: Erlaubnis und Änderungen über die allgemeine Dateiliste statt der Videoliste.
        val files = intent.getBooleanExtra("files", false)
        uri = ContentUris.withAppendedId(
            if (files) MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id)
        val request = runCatching { MediaStore.createWriteRequest(contentResolver, listOf(uri!!)) }
            .getOrElse { return done("FEHLER createWriteRequest: $it") }
        startIntentSenderForResult(request.intentSender, 1, null, 0, 0, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.i(TAG, "Schreib-Erlaubnis: ${resultCode == RESULT_OK}")
        val video = uri!!
        val id = ContentUris.parseId(video)
        for (step in (intent.getStringExtra("to") ?: "").split(",")) {
            val (kind, rel) = step.split(":", limit = 2)
            val target = if (kind == "files") ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), id)
                else ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id)
            val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, rel) }
            val r = runCatching { contentResolver.update(target, values, null, null) }.fold({ "OK n=$it" }, { "FEHLER $it" })
            val path = contentResolver.query(video, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
            Log.i(TAG, "$kind -> $rel: $r => $path")
        }
        done("FERTIG")
    }

    private fun done(m: String) {
        Log.i(TAG, m)
        finish()
    }

    private companion object {
        const val TAG = "MoveSpike"
    }
}
