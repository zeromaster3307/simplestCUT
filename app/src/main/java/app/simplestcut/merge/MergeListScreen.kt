package app.simplestcut.merge

import androidx.compose.ui.platform.LocalContext
import app.simplestcut.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.simplestcut.gallery.DragHandleIcon
import app.simplestcut.gallery.VideoItem
import app.simplestcut.gallery.VideoThumbnail
import app.simplestcut.gallery.formatDuration
import app.simplestcut.mp4.Mp4Source
import app.simplestcut.mp4.incompatibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val ROW_HEIGHT = 72.dp

/**
 * Reihenfolge der Clips zum Zusammenfügen festlegen (am Griff ziehen), Clips entfernen. "Weiter" prüft, ob die
 * Clips ohne Neukodieren zusammenpassen, und öffnet dann den Schnittmodus mit allen Clips. Passen sie nicht,
 * erklärt ein Dialog, dass nur Exportieren (alles neu kodieren) geht, und fragt Ausrichtung und Auflösung ab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeListScreen(
    videos: List<VideoItem>,
    onOrderChange: (List<VideoItem>) -> Unit,
    onBack: () -> Unit,
    /** null = Clips passen zusammen (Speichern und Exportieren), sonst Einstellungen fürs Neukodieren. */
    onContinue: (MixSettings?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var mixing by remember { mutableStateOf<Pair<String, MixPlan>?>(null) }

    fun next() {
        checking = true
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val sources = videos.map { Mp4Source.read(File(it.path)) }
                    val reason = incompatibility(sources, videos.map { it.name }, context.resources)
                    reason?.let { it to MixPlan(sources.map { s -> ClipFormat.of(s) }) }
                }
            }
            checking = false
            result.onSuccess { mix -> if (mix == null) onContinue(null) else mixing = mix }
                .onFailure { problem = context.getString(R.string.error_read_video, it.message) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_merge)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = ::next, enabled = videos.size >= 2 && !checking) { Text(stringResource(R.string.next)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                stringResource(R.string.merge_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Box(Modifier.fillMaxSize()) {
                ReorderableList(videos, onOrderChange)
                if (checking) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }

    problem?.let { msg ->
        AlertDialog(
            onDismissRequest = { problem = null },
            title = { Text(stringResource(R.string.error)) },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { problem = null }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
    mixing?.let { (reason, plan) ->
        MixDialog(
            reason = reason,
            plan = plan,
            onDismiss = { mixing = null },
            onContinue = {
                mixing = null
                onContinue(it)
            },
        )
    }
}

/** Unterschiedliche Formate: Hinweis aufs Neukodieren, Ausrichtung (falls gemischt) und Auflösung wählen. */
@Composable
private fun MixDialog(reason: String, plan: MixPlan, onDismiss: () -> Unit, onContinue: (MixSettings) -> Unit) {
    var orientation by remember { mutableStateOf(plan.defaultOrientation) }
    val resolutions = remember(orientation) { plan.resolutions(orientation) }
    var resolution by remember(orientation) { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mix_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(reason)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.mix_text) +
                        if (plan.anyHdr && !plan.allHdr) stringResource(R.string.mix_tonemap) else ""
                )
                if (plan.mixedOrientation) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.orientation), style = MaterialTheme.typography.titleSmall)
                    Orientation.entries.forEach { o ->
                        ChoiceRow(stringResource(o.label), selected = orientation == o) { orientation = o }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.resolution), style = MaterialTheme.typography.titleSmall)
                resolutions.forEachIndexed { i, r ->
                    ChoiceRow(r.label, selected = resolution == i) { resolution = i }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onContinue(plan.settings(resolutions[resolution])) }) { Text(stringResource(R.string.next)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun ReorderableList(videos: List<VideoItem>, onOrderChange: (List<VideoItem>) -> Unit) {
    val rowPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
    var dragged by remember { mutableStateOf<VideoItem?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // Aktuelle Liste auch innerhalb der laufenden Ziehgeste.
    var current by remember { mutableStateOf(videos) }
    current = videos

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        videos.forEachIndexed { index, video ->
            key(video.uri) {
                val isDragged = dragged?.uri == video.uri
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(ROW_HEIGHT)
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                        .background(
                            if (isDragged) MaterialTheme.colorScheme.surfaceContainerHighest
                            else MaterialTheme.colorScheme.surface
                        )
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("${index + 1}.", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(28.dp))
                    VideoThumbnail(video.uri, Modifier.size(60.dp).clip(RoundedCornerShape(6.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(video.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            formatDuration(video.durationMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { onOrderChange(videos.filter { it.uri != video.uri }) },
                        enabled = videos.size > 2,
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.remove_from_list))
                    }
                    Icon(
                        DragHandleIcon,
                        contentDescription = stringResource(R.string.drag_to_move),
                        modifier = Modifier
                            .size(40.dp)
                            .padding(8.dp)
                            .pointerInput(video.uri) {
                                detectDragGestures(
                                    onDragStart = { dragged = video; dragOffset = 0f },
                                    onDragEnd = { dragged = null; dragOffset = 0f },
                                    onDragCancel = { dragged = null; dragOffset = 0f },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        // Mehr als eine halbe Zeile verschoben: mit dem Nachbarn tauschen.
                                        val list = current.toMutableList()
                                        val i = list.indexOfFirst { it.uri == video.uri }
                                        if (dragOffset > rowPx / 2 && i < list.lastIndex) {
                                            list.add(i + 1, list.removeAt(i))
                                            dragOffset -= rowPx
                                            current = list
                                            onOrderChange(list)
                                        } else if (dragOffset < -rowPx / 2 && i > 0) {
                                            list.add(i - 1, list.removeAt(i))
                                            dragOffset += rowPx
                                            current = list
                                            onOrderChange(list)
                                        }
                                    },
                                )
                            },
                    )
                }
            }
        }
    }
}
