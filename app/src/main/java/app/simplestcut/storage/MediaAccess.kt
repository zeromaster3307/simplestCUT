package app.simplestcut.storage

import app.simplestcut.R
import androidx.compose.ui.res.stringResource
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.simplestcut.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/**
 * Zugriff auf fremde Dateien ohne "Zugriff auf alle Dateien" (Play-Store-tauglich, siehe SPEC "Speicherzugriff"):
 * - Ändern fremder Videos (umbenennen, verschieben): Android fragt pro Aktion nach ([requestWrite]); mit der
 *   Sondererlaubnis "Medienverwaltung" ohne Nachfrage.
 * - Ordner ausserhalb von DCIM/Movies/Pictures/Download (z. B. WhatsApp): einmalige Ordnerfreigabe ([requestFolder]).
 * Die Abfragen erscheinen erst, wenn sie gebraucht werden.
 */
class MediaAccess internal constructor(private val context: Context, private val settings: AppSettings) {
    private sealed interface Prompt {
        class Folder(val dir: File, val answer: CompletableDeferred<Boolean>) : Prompt
        class ManageMedia(val answer: CompletableDeferred<Boolean>) : Prompt
        class Messenger(val answer: CompletableDeferred<Boolean>) : Prompt
    }

    private var prompt by mutableStateOf<Prompt?>(null)
    private var writeResult: CompletableDeferred<Boolean>? = null
    private var folderResult: CompletableDeferred<Uri?>? = null
    private var resumed: CompletableDeferred<Unit>? = null
    internal var writeLauncher: ActivityResultLauncher<IntentSenderRequest>? = null
    internal var folderLauncher: ActivityResultLauncher<Uri?>? = null

    /** Darf die App fremde Videos ohne Nachfrage ändern ("Medienverwaltung")? */
    fun canManageMedia(): Boolean = MediaStore.canManageMedia(context)

    fun openManageMediaSettings() {
        val intent = Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.fromParts("package", context.packageName, null))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Erlaubnis, [uris] zu ändern. Beim ersten Mal wird die Medienverwaltung angeboten; danach fragt Android
     * selbst nach (bzw. gar nicht, wenn die Medienverwaltung erlaubt ist). false = abgelehnt.
     */
    suspend fun requestWrite(uris: List<Uri>): Boolean {
        if (uris.isEmpty()) return true
        offerManageMedia()
        val launcher = writeLauncher ?: return false
        val result = CompletableDeferred<Boolean>().also { writeResult = it }
        val request = MediaStore.createWriteRequest(context.contentResolver, uris)
        launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        return result.await()
    }

    /** Bietet einmalig die "Medienverwaltung" an (beim ersten Start bzw. vor der ersten Änderung). */
    suspend fun offerManageMedia() {
        if (canManageMedia() || settings.manageMediaOffered) return
        settings.markManageMediaOffered()
        val answer = CompletableDeferred<Boolean>()
        prompt = Prompt.ManageMedia(answer)
        if (answer.await()) {
            val back = CompletableDeferred<Unit>().also { resumed = it }
            openManageMediaSettings()
            back.await()
        }
    }

    /**
     * Bietet einmalig an, den Ordner der Messenger (Android/media: WhatsApp, Telegram, …) freizugeben. Nur aufrufen,
     * wenn dort Videos liegen.
     */
    suspend fun offerMessengerFolder() {
        val dir = StorageFolders.messengers
        if (settings.messengerFolderOffered || isGranted(dir)) return
        settings.markMessengerFolderOffered()
        val answer = CompletableDeferred<Boolean>()
        prompt = Prompt.Messenger(answer)
        if (answer.await()) pickFolder(dir)
    }

    /** Freigabe, die [dir] abdeckt (der Ordner selbst oder ein übergeordneter), falls vorhanden. */
    fun grantFor(dir: File): Uri? {
        val rel = StorageFolders.relative(dir) ?: return null
        return context.contentResolver.persistedUriPermissions
            .filter { it.isWritePermission && it.uri.authority == EXTERNAL_STORAGE }
            .map { it.uri }
            .firstOrNull { tree ->
                val granted = DocumentsContract.getTreeDocumentId(tree).removePrefix("primary:")
                granted.isNotEmpty() && (rel == granted || rel.startsWith("$granted/"))
            }
    }

    /** Ordner mit Freigabe, für die Einstellungen (relativ zum internen Speicher). */
    fun grantedFolders(): List<Pair<String, Uri>> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isWritePermission && it.uri.authority == EXTERNAL_STORAGE }
            .map { DocumentsContract.getTreeDocumentId(it.uri).removePrefix("primary:") to it.uri }
            .sortedBy { it.first.lowercase() }

    fun revokeFolder(tree: Uri) {
        context.contentResolver.releasePersistableUriPermission(tree, GRANT_FLAGS)
    }

    /**
     * Freigabe für [dir] holen: erst erklären, dann Androids Ordnerauswahl (startet in [dir]).
     * null = abgelehnt, abgebrochen oder ein Ordner gewählt, der [dir] nicht enthält.
     */
    suspend fun requestFolder(dir: File): Uri? {
        grantFor(dir)?.let { return it }
        if (!StorageFolders.canGrant(dir)) return null
        val answer = CompletableDeferred<Boolean>()
        prompt = Prompt.Folder(dir, answer)
        if (!answer.await()) return null
        val launcher = folderLauncher ?: return null
        val result = CompletableDeferred<Uri?>().also { folderResult = it }
        launcher.launch(StorageFolders.relative(dir)?.let { DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:$it") })
        val tree = result.await() ?: return null
        context.contentResolver.takePersistableUriPermission(tree, GRANT_FLAGS)
        return grantFor(dir) ?: run {
            revokeFolder(tree)
            null
        }
    }

    enum class PickResult { ADDED, ALREADY, CANCELLED }

    /**
     * Beliebigen Ordner vorab freigeben (Einstellungen): Androids Ordnerauswahl startet in [initial]
     * (ohne Vorgabe öffnet Android den zuletzt besuchten Ordner).
     * Schon abgedeckte Ordner werden nicht doppelt freigegeben; Freigaben von Unterordnern des neuen Ordners entfallen.
     */
    suspend fun pickFolder(initial: File): PickResult {
        val launcher = folderLauncher ?: return PickResult.CANCELLED
        val result = CompletableDeferred<Uri?>().also { folderResult = it }
        launcher.launch(StorageFolders.relative(initial)?.let { DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:$it") })
        val tree = result.await() ?: return PickResult.CANCELLED
        val rel = DocumentsContract.getTreeDocumentId(tree).removePrefix("primary:")
        val before = grantedFolders()
        if (before.any { (granted, _) -> rel == granted || rel.startsWith("$granted/") }) return PickResult.ALREADY
        context.contentResolver.takePersistableUriPermission(tree, GRANT_FLAGS)
        before.filter { (granted, _) -> granted.startsWith("$rel/") }.forEach { (_, uri) -> revokeFolder(uri) }
        return PickResult.ADDED
    }

    /** Ist [dir] schon durch eine Freigabe abgedeckt? */
    fun isGranted(dir: File) = grantFor(dir) != null

    /** Dokument-Adresse von [file] innerhalb der Freigabe [tree] (Datei oder Ordner). */
    fun documentIn(tree: Uri, file: File): Uri? =
        StorageFolders.relative(file)?.let { DocumentsContract.buildDocumentUriUsingTree(tree, "primary:$it") }

    internal fun onWriteResult(ok: Boolean) {
        writeResult?.complete(ok)
        writeResult = null
    }

    internal fun onFolderResult(tree: Uri?) {
        folderResult?.complete(tree)
        folderResult = null
    }

    internal fun onResume() {
        resumed?.complete(Unit)
        resumed = null
    }

    @Composable
    internal fun Dialogs() {
        when (val p = prompt) {
            is Prompt.Folder -> AlertDialog(
                onDismissRequest = { prompt = null; p.answer.complete(false) },
                title = { Text(stringResource(R.string.folder_grant_title)) },
                text = {
                    Text(stringResource(R.string.folder_grant_text, StorageFolders.relative(p.dir) ?: p.dir.name))
                },
                confirmButton = { TextButton(onClick = { prompt = null; p.answer.complete(true) }) { Text(stringResource(R.string.next)) } },
                dismissButton = { TextButton(onClick = { prompt = null; p.answer.complete(false) }) { Text(stringResource(android.R.string.cancel)) } },
            )
            is Prompt.ManageMedia -> AlertDialog(
                onDismissRequest = { prompt = null; p.answer.complete(false) },
                title = { Text(stringResource(R.string.manage_media_title)) },
                text = {
                    Text(stringResource(R.string.manage_media_text))
                },
                confirmButton = { TextButton(onClick = { prompt = null; p.answer.complete(true) }) { Text(stringResource(R.string.allow)) } },
                dismissButton = { TextButton(onClick = { prompt = null; p.answer.complete(false) }) { Text(stringResource(R.string.not_now)) } },
            )
            is Prompt.Messenger -> AlertDialog(
                onDismissRequest = { prompt = null; p.answer.complete(false) },
                title = { Text(stringResource(R.string.messenger_title)) },
                text = { Text(stringResource(R.string.messenger_text)) },
                confirmButton = { TextButton(onClick = { prompt = null; p.answer.complete(true) }) { Text(stringResource(R.string.next)) } },
                dismissButton = { TextButton(onClick = { prompt = null; p.answer.complete(false) }) { Text(stringResource(R.string.not_now)) } },
            )
            null -> Unit
        }
    }

    private companion object {
        const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
        const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

val LocalMediaAccess = staticCompositionLocalOf<MediaAccess> { error("MediaAccess fehlt") }

/** Einmal oben in der App anlegen und per [LocalMediaAccess] bereitstellen; [MediaAccessDialogs] dazu anzeigen. */
@Composable
fun rememberMediaAccess(settings: AppSettings): MediaAccess {
    val context = LocalContext.current
    val access = remember { MediaAccess(context, settings) }
    access.writeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        access.onWriteResult(it.resultCode == Activity.RESULT_OK)
    }
    access.folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        access.onFolderResult(it)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access.onResume() }
    return access
}

@Composable
fun MediaAccessDialogs(access: MediaAccess) = access.Dialogs()

/** Ordner im internen Speicher und was die App dort ohne Freigabe darf. */
object StorageFolders {
    /** Interner Speicher, z. B. /storage/emulated/0 */
    val root: File get() = Environment.getExternalStorageDirectory()

    /** Videos dürfen per Android (MediaStore) nur hierhin verschoben werden. */
    private val mediaTops = listOf(Environment.DIRECTORY_DCIM, Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_PICTURES)

    /** Ordner der Messenger (WhatsApp, Telegram, …); eine Freigabe hier deckt alle ab. */
    val messengers: File get() = File(root, "Android/media")

    /** Ausweichordner, wenn neben dem Original nicht gespeichert werden darf. */
    val fallback: File get() = File(root, "${Environment.DIRECTORY_MOVIES}/simplestCUT")

    /** Pfad relativ zum internen Speicher ("DCIM/Camera"), "" für den Speicher selbst, null ausserhalb. */
    fun relative(file: File): String? {
        val base = root.absolutePath
        val path = file.absolutePath
        return when {
            path == base -> ""
            path.startsWith("$base/") -> path.removePrefix("$base/")
            else -> null
        }
    }

    private fun top(dir: File) = relative(dir)?.substringBefore('/')

    /** Darf die App hier ohne Freigabe neue Videos anlegen? */
    fun canCreateIn(dir: File) = top(dir).let { it in mediaTops || it == Environment.DIRECTORY_DOWNLOADS }

    /** Kann Android fremde Videos hierhin verschieben (ohne Freigaben)? */
    fun isMediaFolder(dir: File) = top(dir) in mediaTops

    /** Kann Android fremde Videos von hier nach [isMediaFolder] verschieben (ohne Freigaben)? */
    fun isMediaSource(dir: File) = canCreateIn(dir)

    /** Lässt Android eine Ordnerfreigabe zu? Nicht für den Speicher selbst, Download, Android/data und Android/obb. */
    fun canGrant(dir: File): Boolean {
        val rel = relative(dir) ?: return false
        return rel.isNotEmpty() && rel != Environment.DIRECTORY_DOWNLOADS &&
            listOf("Android", "Android/data", "Android/obb").none { rel == it || (it != "Android" && rel.startsWith("$it/")) }
    }
}
