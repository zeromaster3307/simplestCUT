package app.simplestcut

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.simplestcut.editor.EditorScreen
import app.simplestcut.gallery.GalleryScreen
import app.simplestcut.merge.MergeListScreen
import app.simplestcut.merge.MixSettings
import app.simplestcut.gallery.VideoItem
import app.simplestcut.gallery.CAMERA_FOLDER
import app.simplestcut.gallery.DeleteConfirmDialog
import app.simplestcut.gallery.TRASH_FOLDER
import app.simplestcut.gallery.rememberDeleter
import app.simplestcut.settings.SettingsScreen
import app.simplestcut.settings.StartFolder
import app.simplestcut.settings.rememberAppSettings
import app.simplestcut.storage.LocalMediaAccess
import app.simplestcut.storage.MediaAccessDialogs
import app.simplestcut.storage.rememberMediaAccess
import app.simplestcut.ui.SimplestCutTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings = rememberAppSettings()
            val mediaAccess = rememberMediaAccess(settings)
            SimplestCutTheme(settings.themeMode) {
                CompositionLocalProvider(LocalMediaAccess provides mediaAccess) {
                    // Bewusst ohne Speichern: Projekte werden laut SPEC nicht über das Schliessen hinaus gehalten.
                    var openVideo by remember { mutableStateOf<VideoItem?>(null) }
                    // Aus der Galerie geschnitten: direkt im Schnittmodus, nach dem Speichern ins neue Video.
                    var cutVideo by remember { mutableStateOf<VideoItem?>(null) }
                    // Zusammenfügen: Liste der Clips (Reihenfolge), dann Schnittmodus mit allen.
                    var mergeList by remember { mutableStateOf<List<VideoItem>?>(null) }
                    var mergeEditing by remember { mutableStateOf(false) }
                    // Unpassende Clips: Einstellungen fürs komplette Neukodieren (dann nur Exportieren).
                    var mergeMix by remember { mutableStateOf<MixSettings?>(null) }
                    // Gespeichert: ein Sprachwechsel baut die Activity neu auf, man soll in den Einstellungen bleiben.
                    var showSettings by rememberSaveable { mutableStateOf(false) }
                    // Ordnerwahl und Scrollposition der Galerie bleiben erhalten, während ein Video offen ist.
                    var folderId by remember {
                        mutableStateOf(
                            when (settings.startFolder) {
                                StartFolder.ALL -> null
                                StartFolder.CAMERA -> CAMERA_FOLDER
                                StartFolder.LAST -> settings.lastFolderId
                            }
                        )
                    }
                    val gridState = rememberLazyGridState()
                    val deleter = rememberDeleter()
                    val video = openVideo
                    val cutting = cutVideo
                    val merge = mergeList
                    when {
                        showSettings -> {
                            BackHandler { showSettings = false }
                            SettingsScreen(settings, onBack = { showSettings = false })
                        }
                        cutting != null -> {
                            BackHandler { cutVideo = null }
                            EditorScreen(
                                listOf(cutting), settings,
                                onBack = { cutVideo = null },
                                onSaved = {
                                    cutVideo = null
                                    openVideo = it
                                },
                                startInCut = true,
                            )
                        }
                        video != null -> {
                            BackHandler { openVideo = null }
                            EditorScreen(
                                listOf(video), settings,
                                onBack = { openVideo = null },
                                onSaved = { openVideo = it },
                                // Löschen = Papierkorb; aus dem Papierkorb heraus endgültig. Danach zurück zur Galerie.
                                onDelete = {
                                    deleter.delete(listOf(video.uri), toTrash = !video.isTrashed) {
                                        openVideo = null
                                    }
                                },
                                onRestore = { deleter.restore(listOf(video.uri)) { openVideo = null } },
                                onRenamed = { openVideo = it },
                            )
                        }
                        merge != null && mergeEditing -> {
                            BackHandler { mergeEditing = false }
                            EditorScreen(merge, settings, onBack = { mergeEditing = false }, onSaved = {
                                mergeEditing = false
                                mergeList = null
                                openVideo = it
                            }, mix = mergeMix)
                        }
                        merge != null -> {
                            BackHandler { mergeList = null }
                            MergeListScreen(
                                videos = merge,
                                onOrderChange = { mergeList = it },
                                onBack = { mergeList = null },
                                onContinue = {
                                    mergeMix = it
                                    mergeEditing = true
                                },
                            )
                        }
                        else -> GalleryScreen(
                            folderId = folderId,
                            onFolderChange = {
                                folderId = it
                                if (it != TRASH_FOLDER) settings.rememberFolder(it)
                            },
                            gridState = gridState,
                            onOpen = { openVideo = it },
                            onMerge = { mergeList = it },
                            onCut = { cutVideo = it },
                            onSettings = { showSettings = true },
                            settings = settings,
                            deleter = deleter,
                        )
                    }
                    DeleteConfirmDialog(deleter)
                    MediaAccessDialogs(mediaAccess)
                }
            }
        }
    }
}
