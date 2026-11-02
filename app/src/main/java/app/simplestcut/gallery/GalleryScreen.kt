package app.simplestcut.gallery

import app.simplestcut.storage.StorageFolders
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.runtime.snapshotFlow
import app.simplestcut.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.delay
import app.simplestcut.settings.AppSettings
import app.simplestcut.settings.SortKey
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.simplestcut.storage.LocalMediaAccess

private enum class Access { FULL, PARTIAL, NONE }

private fun currentAccess(context: Context): Access {
    fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    return when {
        granted(Manifest.permission.READ_MEDIA_VIDEO) -> Access.FULL
        granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
        else -> Access.NONE
    }
}

private val mediaPermissions = arrayOf(
    Manifest.permission.READ_MEDIA_VIDEO,
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
)

/** Gewählter Ordner (null = alle Videos). Liegt beim Aufrufer, damit er beim Öffnen eines Videos erhalten bleibt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    folderId: Long?,
    onFolderChange: (Long?) -> Unit,
    gridState: LazyGridState,
    onOpen: (VideoItem) -> Unit,
    /** Mehrere Videos zusammenfügen (in Auswahl-Reihenfolge der Galerie übergeben). */
    onMerge: (List<VideoItem>) -> Unit,
    /** Ein markiertes Video direkt im Schnittmodus öffnen. */
    onCut: (VideoItem) -> Unit,
    onSettings: () -> Unit,
    settings: AppSettings,
    deleter: Deleter,
) {
    val context = LocalContext.current
    val mediaAccess = LocalMediaAccess.current
    var access by remember { mutableStateOf(currentAccess(context)) }
    var videos by remember { mutableStateOf<List<VideoItem>?>(null) }
    var trash by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    // Wird erhöht, sobald sich die Videos geändert haben könnten (Rückkehr in die App, Export, Löschen, neue Aufnahme).
    var reloadKey by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(setOf<Uri>()) }
    var folderMenuOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }
    var infoVideo by remember { mutableStateOf<VideoItem?>(null) }
    val scope = rememberCoroutineScope()
    fun chooseFolder(id: Long?) {
        folderMenuOpen = false
        if (id != folderId) {
            onFolderChange(id)
            scope.launch { gridState.scrollToItem(0) }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        access = currentAccess(context)
        reloadKey++
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = currentAccess(context)
        reloadKey++
    }

    // Android meldet jede Änderung an den Videos; daraufhin neu laden.
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reloadKey++
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    // Gleich nach der Leseerlaubnis einmalig die "Medienverwaltung" anbieten und, falls es Messenger-Videos gibt,
    // die Freigabe ihres Ordners (spart später viele Nachfragen). Beides lässt sich überspringen.
    LaunchedEffect(access) {
        if (access == Access.NONE) return@LaunchedEffect
        mediaAccess.offerManageMedia()
        val loaded = snapshotFlow { videos }.filterNotNull().first()
        val messengers = StorageFolders.messengers.absolutePath + "/"
        if (loaded.any { it.path.startsWith(messengers) }) mediaAccess.offerMessengerFolder()
    }

    LaunchedEffect(access, reloadKey) {
        // Kurz warten, weil Android bei einer Änderung oft mehrere Meldungen schickt; jede neue bricht das Warten ab.
        if (videos != null) delay(300)
        val loaded = if (access == Access.NONE) emptyList() else loadVideos(context)
        videos = loaded
        trash = if (access == Access.NONE) emptyList() else loadTrashedVideos(context)
        val existing = (loaded + trash).map { it.uri }.toSet()
        selected = selected.intersect(existing)
    }

    val all = videos.orEmpty()
    val folders = remember(all) { foldersOf(all) }
    // Ordner existiert nicht mehr (z. B. letztes Video gelöscht): zurück zu "Alle Videos".
    val inTrash = folderId == TRASH_FOLDER
    LaunchedEffect(folders, folderId) {
        if (videos == null || folderId == null) return@LaunchedEffect
        when {
            inTrash -> Unit
            folderId == CAMERA_FOLDER -> onFolderChange(cameraFolderId(all))
            folders.none { it.id == folderId } -> onFolderChange(null)
        }
    }
    val sortKey = settings.sortKey
    val descending = settings.sortDescending
    val shown = remember(all, trash, folderId, sortKey, descending) {
        // Solange der Kamera-Ordner beim Start noch nicht ermittelt ist, alles zeigen statt einer leeren Liste.
        val inFolder = when (folderId) {
            null, CAMERA_FOLDER -> all
            TRASH_FOLDER -> trash
            else -> all.filter { it.folderId == folderId }
        }
        val comparator = when (sortKey) {
            SortKey.DATE -> compareBy<VideoItem> { it.dateMs }
            SortKey.DURATION -> compareBy { it.durationMs }
            SortKey.SIZE -> compareBy { it.sizeBytes }
        }
        inFolder.sortedWith(if (descending) comparator.reversed() else comparator)
    }
    val selecting = selected.isNotEmpty()

    BackHandler(enabled = selecting) { selected = emptySet() }

    Scaffold(
        snackbarHost = { SnackbarHost(deleter.snackbar) },
        topBar = {
            if (selecting) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.selection_end))
                        }
                    },
                    title = { Text(stringResource(R.string.selected_count, selected.size)) },
                    actions = {
                        if (inTrash) {
                            if (selected.size < shown.size) {
                                IconButton(onClick = { selected = shown.map { it.uri }.toSet() }) {
                                    Icon(SelectAllIcon, contentDescription = stringResource(R.string.select_all))
                                }
                            }
                            IconButton(onClick = { deleter.restore(selected.toList()) { selected = emptySet() } }) {
                                Icon(RestoreIcon, contentDescription = stringResource(R.string.restore))
                            }
                            IconButton(onClick = {
                                deleter.delete(selected.toList(), toTrash = false) { selected = emptySet() }
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_permanently))
                            }
                            return@TopAppBar
                        }
                        val single = if (selected.size == 1) all.firstOrNull { it.uri in selected } else null
                        if (single != null) {
                            IconButton(onClick = { infoVideo = single }) {
                                Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.info))
                            }
                            IconButton(onClick = {
                                onCut(single)
                                selected = emptySet()
                            }) {
                                Icon(ScissorsIcon, contentDescription = stringResource(R.string.title_cut))
                            }
                        }
                        if (selected.size >= 2) {
                            IconButton(onClick = {
                                // Älteste zuerst, meist die gewünschte Reihenfolge; änderbar in der Liste.
                                onMerge(all.filter { it.uri in selected }.sortedBy { it.dateMs })
                                selected = emptySet()
                            }) {
                                Icon(JoinIcon, contentDescription = stringResource(R.string.title_merge))
                            }
                        }
                        IconButton(onClick = {
                            shareVideos(context, shown.map { it.uri }.filter { it in selected })
                            selected = emptySet()
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share))
                        }
                        IconButton(onClick = { showMoveDialog = true }) {
                            Icon(MoveIcon, contentDescription = stringResource(R.string.move))
                        }
                        IconButton(onClick = {
                            deleter.delete(selected.toList(), toTrash = true) { selected = emptySet() }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.combinedClickable(onClick = { folderMenuOpen = true }),
                            ) {
                                val name = if (inTrash) stringResource(R.string.trash)
                                else folders.firstOrNull { it.id == folderId }?.name ?: stringResource(R.string.all_videos)
                                Text(name)
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.choose_folder))
                            }
                            DropdownMenu(expanded = folderMenuOpen, onDismissRequest = { folderMenuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.folder_with_count, stringResource(R.string.all_videos), all.size)) },
                                    onClick = { chooseFolder(null) },
                                )
                                folders.forEach { folder ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.folder_with_count, folder.name, folder.count)) },
                                        onClick = { chooseFolder(folder.id) },
                                    )
                                }
                                if (trash.isNotEmpty() || inTrash) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.folder_with_count, stringResource(R.string.trash), trash.size)) },
                                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                        onClick = { chooseFolder(TRASH_FOLDER) },
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { sortMenuOpen = true }) {
                                Icon(SortIcon, contentDescription = stringResource(R.string.sort))
                            }
                            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                                SortKey.entries.forEach { key ->
                                    val active = key == sortKey
                                    DropdownMenuItem(
                                        text = { Text(stringResource(key.label)) },
                                        trailingIcon = {
                                            if (active) Text(sortDirectionLabel(key, descending))
                                        },
                                        onClick = {
                                            settings.chooseSort(key)
                                            sortMenuOpen = false
                                            scope.launch { gridState.scrollToItem(0) }
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                        }
                    },
                )
            }
        }
    ) { padding ->
        infoVideo?.let { v ->
            VideoInfoDialog(
                v,
                onDismiss = { infoVideo = null },
                onRenamed = {
                    infoVideo = null
                    selected = emptySet()
                },
            )
        }
        if (showMoveDialog) {
            val toMove = all.filter { it.uri in selected }
            MoveTargetDialog(
                count = toMove.size,
                mediaOnly = remember(toMove) { needsMediaTarget(toMove) },
                folders = remember(all, toMove) { targetFoldersOf(all, mediaOnly = needsMediaTarget(toMove)) },
                onMove = { target ->
                    showMoveDialog = false
                    scope.launch {
                        val result = moveVideos(context, mediaAccess, toMove, target)
                        val message = buildString {
                            append(context.resources.getQuantityString(R.plurals.moved_count, result.moved, result.moved, relativeToStorage(target)))
                            if (result.failed.isNotEmpty()) append(context.getString(R.string.move_failed_list, result.failed.joinToString()))
                            if (result.notAllowed.isNotEmpty()) append(context.getString(R.string.move_not_allowed, result.notAllowed.joinToString()))
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        selected = emptySet()
                    }
                },
                onDismiss = { showMoveDialog = false },
            )
        }
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                access == Access.NONE -> NoAccess(
                    onRequest = { permissionLauncher.launch(mediaPermissions) },
                    onSettings = { openAppSettings(context) },
                )
                videos == null -> Unit
                else -> VideoGrid(
                    videos = shown,
                    gridState = gridState,
                    columns = settings.gridColumns,
                    onColumnsChange = settings::updateGridColumns,
                    partial = access == Access.PARTIAL,
                    inTrash = inTrash,
                    selected = selected,
                    onSelectMore = { permissionLauncher.launch(mediaPermissions) },
                    onTap = { video ->
                        if (selecting) selected = selected.toggle(video.uri) else onOpen(video)
                    },
                    onLongPress = { video -> selected = selected.toggle(video.uri) },
                )
            }
        }
    }
}

@Composable
private fun sortDirectionLabel(key: SortKey, descending: Boolean) = stringResource(
    when (key) {
        SortKey.DATE -> if (descending) R.string.sort_newest else R.string.sort_oldest
        SortKey.DURATION -> if (descending) R.string.sort_longest else R.string.sort_shortest
        SortKey.SIZE -> if (descending) R.string.sort_largest else R.string.sort_smallest
    }
)

private fun Set<Uri>.toggle(uri: Uri) = if (uri in this) this - uri else this + uri

@Composable
private fun NoAccess(onRequest: () -> Unit, onSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.no_access_text),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text(stringResource(R.string.allow_access)) }
        Spacer(Modifier.height(8.dp))
        // Falls der Systemdialog nicht mehr erscheint (zweimal abgelehnt), hilft nur noch der Weg über die Einstellungen.
        TextButton(onClick = onSettings) { Text(stringResource(R.string.open_app_settings)) }
    }
}

@Composable
private fun VideoGrid(
    videos: List<VideoItem>,
    gridState: LazyGridState,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    partial: Boolean,
    inTrash: Boolean,
    selected: Set<Uri>,
    onSelectMore: () -> Unit,
    onTap: (VideoItem) -> Unit,
    onLongPress: (VideoItem) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentColumns by rememberUpdatedState(columns)
    val currentOnColumnsChange by rememberUpdatedState(onColumnsChange)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxSize()
            // Zwei Finger aufziehen/zusammenschieben: weniger/mehr Spalten (2..5). Ein Finger scrollt wie gewohnt.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var zoom = 1f
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            zoom *= event.calculateZoom()
                            val centroid = event.calculateCentroid()
                            val step = when {
                                zoom > 1.5f -> -1
                                zoom < 0.67f -> 1
                                else -> 0
                            }
                            val target = (currentColumns + step).coerceIn(2, 5)
                            if (step != 0) zoom = 1f
                            if (target != currentColumns && centroid != Offset.Unspecified) {
                                // Das Video unter den Fingern soll danach wieder ungefähr dort liegen.
                                val under = gridState.layoutInfo.visibleItemsInfo.firstOrNull {
                                    centroid.y >= it.offset.y && centroid.y < it.offset.y + it.size.height &&
                                        centroid.x >= it.offset.x && centroid.x < it.offset.x + it.size.width
                                }
                                currentOnColumnsChange(target)
                                if (under != null) {
                                    val rowHeight = size.width / target.toFloat()
                                    scope.launch {
                                        gridState.scrollToItem(under.index)
                                        gridState.scrollBy(-(centroid.y - rowHeight / 2))
                                    }
                                }
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        if (partial) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.partial_access),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = onSelectMore) { Text(stringResource(R.string.change)) }
                    }
                }
            }
        }
        if (videos.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(if (inTrash) R.string.trash_empty else R.string.no_videos),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }
        items(videos, key = { it.uri }) { video ->
            val isSelected = video.uri in selected
            Box(
                Modifier
                    .animateItem()
                    .aspectRatio(1f)
                    .combinedClickable(onClick = { onTap(video) }, onLongClick = { onLongPress(video) })
            ) {
                VideoThumbnail(video.uri, Modifier.fillMaxSize())
                if (isSelected) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f))
                            .border(3.dp, MaterialTheme.colorScheme.primary)
                    )
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = stringResource(R.string.selected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    )
                }
                Text(
                    formatDuration(video.durationMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
                if (video.isTrashed) {
                    Text(
                        daysLeftLabel(video.expiresMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/** "noch 27 Tage" bis Android das Video aus dem Papierkorb endgültig löscht. */
@Composable
private fun daysLeftLabel(expiresMs: Long): String {
    val days = ((expiresMs - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0).toInt()
    return if (days == 0) stringResource(R.string.trash_today) else pluralStringResource(R.plurals.trash_days_left, days, days)
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    )
}
