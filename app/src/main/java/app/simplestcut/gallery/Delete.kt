package app.simplestcut.gallery

import app.simplestcut.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Löschen, in den Papierkorb legen und wiederherstellen – gemeinsam für Galerie und Player.
 * Papierkorb: danach Hinweis mit "Rückgängig". Endgültig: Nachfrage. Mit der "Medienverwaltung" fragt Android
 * nicht selbst nach, dann zeigt die App einen eigenen Dialog ([DeleteConfirmDialog]); sonst nur den von Android.
 */
class Deleter internal constructor(private val context: Context, private val scope: CoroutineScope) {
    private enum class Kind { TRASH, DELETE, RESTORE, UNDO }

    private class Request(val kind: Kind, val uris: List<Uri>, val onDone: () -> Unit)

    /** Hinweise ("In den Papierkorb verschoben – Rückgängig"); die Galerie zeigt sie an. */
    val snackbar = SnackbarHostState()

    private var confirming by mutableStateOf<Request?>(null)
    private var pending: Request? = null
    internal var launcher: ActivityResultLauncher<IntentSenderRequest>? = null

    /** [onDone] läuft nur, wenn wirklich gelöscht wurde (nicht bei "Abbrechen"). */
    fun delete(uris: List<Uri>, toTrash: Boolean, onDone: () -> Unit = {}) {
        val request = Request(if (toTrash) Kind.TRASH else Kind.DELETE, uris, onDone)
        if (!toTrash && MediaStore.canManageMedia(context)) confirming = request else launch(request)
    }

    /** Aus dem Papierkorb zurückholen. */
    fun restore(uris: List<Uri>, onDone: () -> Unit = {}) = launch(Request(Kind.RESTORE, uris, onDone))

    private fun launch(request: Request) {
        val resolver = context.contentResolver
        val intent = when (request.kind) {
            Kind.TRASH -> MediaStore.createTrashRequest(resolver, request.uris, true)
            Kind.RESTORE, Kind.UNDO -> MediaStore.createTrashRequest(resolver, request.uris, false)
            Kind.DELETE -> MediaStore.createDeleteRequest(resolver, request.uris)
        }
        pending = request
        launcher?.launch(IntentSenderRequest.Builder(intent.intentSender).build())
    }

    internal fun onResult(ok: Boolean) {
        val request = pending ?: return
        pending = null
        if (!ok) return
        request.onDone()
        val n = request.uris.size
        when (request.kind) {
            Kind.TRASH -> scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val answer = snackbar.showSnackbar(
                    message = context.resources.getQuantityString(R.plurals.trashed_count, n, n),
                    actionLabel = context.getString(R.string.undo),
                    duration = SnackbarDuration.Long,
                )
                if (answer == SnackbarResult.ActionPerformed) launch(Request(Kind.UNDO, request.uris) {})
            }
            Kind.RESTORE -> scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.restored_count, n, n))
            }
            Kind.DELETE, Kind.UNDO -> Unit
        }
    }

    @Composable
    internal fun ConfirmDialog() {
        val request = confirming ?: return
        val n = request.uris.size
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(pluralStringResource(R.plurals.delete_confirm_title, n, n)) },
            text = { Text(stringResource(R.string.cannot_undo)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    launch(request)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/** Einmal oben in der App anlegen, damit der Hinweis auch nach Verlassen des Players noch erscheint. */
@Composable
fun rememberDeleter(): Deleter {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deleter = remember { Deleter(context, scope) }
    deleter.launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        deleter.onResult(it.resultCode == Activity.RESULT_OK)
    }
    return deleter
}

/** Eigene Nachfrage vor dem endgültigen Löschen (siehe [Deleter]). */
@Composable
fun DeleteConfirmDialog(deleter: Deleter) = deleter.ConfirmDialog()

/** Material-Symbol "restore_from_trash". */
val RestoreIcon: ImageVector by lazy {
    ImageVector.Builder("Restore", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(
            "M19,4h-3.5l-1,-1h-5l-1,1H5v2h14zM6,7v12c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6z" +
                "M14,14v4h-4v-4H8l4,-4 4,4h-2z"
        ),
        fill = SolidColor(Color.Black),
    ).build()
}
