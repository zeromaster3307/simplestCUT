package app.simplestcut.gallery

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import app.simplestcut.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import app.simplestcut.storage.StorageFolders
import java.io.File

/** Hier darf die App ohne "Zugriff auf alle Dateien" neue Ordner anlegen. */
private val newFolderTops = listOf("DCIM", "Movies", "Pictures", "Download")

/** Auswahl des Zielordners; "Neuer Ordner…" legt einen Unterordner von DCIM, Movies, Pictures oder Download an. */
@Composable
fun MoveTargetDialog(
    count: Int,
    folders: List<TargetFolder>,
    /** Nur DCIM/Movies/Pictures als Ziel (Videos aus dem Download-Hauptordner), mit Hinweis. */
    mediaOnly: Boolean,
    onMove: (File) -> Unit,
    onDismiss: () -> Unit,
) {
    var newFolderMode by remember { mutableStateOf(false) }
    var newPath by remember { mutableStateOf("Movies/") }

    if (newFolderMode) {
        val cleaned = newPath.trim().trim('/')
        val valid = !cleaned.contains("..") && newFolderTops.filter { !mediaOnly || it != "Download" }.any { cleaned.startsWith("$it/") && cleaned.length > it.length + 1 }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.new_folder)) },
            text = {
                Column {
                    Text(stringResource(if (mediaOnly) R.string.new_folder_hint_media else R.string.new_folder_hint))
                    OutlinedTextField(
                        value = newPath,
                        onValueChange = { newPath = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = { onMove(File(StorageFolders.root, cleaned)) },
                ) { Text(stringResource(R.string.move_here)) }
            },
            dismissButton = { TextButton(onClick = { newFolderMode = false }) { Text(stringResource(R.string.back)) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.move_to_title, count, count)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (mediaOnly) {
                    item {
                        Text(
                            stringResource(R.string.move_media_only),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.new_folder_item)) },
                        leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                        modifier = Modifier.clickable { newFolderMode = true },
                    )
                }
                items(folders, key = { it.dir.absolutePath }) { folder ->
                    ListItem(
                        headlineContent = { Text(folder.dir.name) },
                        supportingContent = { Text("${relativeToStorage(folder.dir)} · ${folder.count}") },
                        modifier = Modifier.clickable { onMove(folder.dir) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Material-Symbol "drive_file_move" (Ordner mit Pfeil); nicht im Basis-Symbolsatz enthalten. */
val MoveIcon: ImageVector by lazy {
    ImageVector.Builder("Move", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(
            "M20,6h-8l-2,-2H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2z" +
                "M12,17v-3H8v-4h4V7l5,5 -5,5z"
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material-Symbol "select_all" (Alle markieren). */
val SelectAllIcon: ImageVector by lazy {
    ImageVector.Builder("SelectAll", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(
            "M3,5h2V3c-1.1,0 -2,0.9 -2,2zM3,13h2v-2H3v2zM7,21h2v-2H7v2zM3,9h2V7H3v2zM13,3h-2v2h2V3zM19,3v2h2c0,-1.1 -0.9,-2 -2,-2z" +
                "M5,21v-2H3c0,1.1 0.9,2 2,2zM3,17h2v-2H3v2zM9,3H7v2h2V3zM11,21h2v-2h-2v2zM19,13h2v-2h-2v2zM19,21c1.1,0 2,-0.9 2,-2h-2v2z" +
                "M19,9h2V7h-2v2zM19,17h2v-2h-2v2zM15,21h2v-2h-2v2zM15,5h2V3h-2v2zM7,17h10V7H7v10zM9,9h6v6H9V9z"
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material-Symbol "sort". */
val SortIcon: ImageVector by lazy {
    ImageVector.Builder("Sort", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M3,18h6v-2H3v2zM3,6v2h18V6H3zM3,13h12v-2H3v2z"),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material-Symbol "pause". */
val PauseIcon: ImageVector by lazy {
    ImageVector.Builder("Pause", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M6,19h4V5H6v14zM14,5v14h4V5h-4z"),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material-Symbol "file_upload" (Pfeil aus der Ablage) = Exportieren, wie in gängigen Video-Apps. */
val ExportIcon: ImageVector by lazy {
    ImageVector.Builder("Export", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M9,16h6v-6h4l-7,-7 -7,7h4zM5,18h14v2H5z"),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material-Symbol "save" (Diskette). */
val SaveIcon: ImageVector by lazy {
    ImageVector.Builder("Save", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(
            "M17,3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V7l-4,-4z" +
                "M12,19c-1.66,0 -3,-1.34 -3,-3s1.34,-3 3,-3 3,1.34 3,3 -1.34,3 -3,3zM15,9H5V5h10v4z"
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material-Symbol "content_cut" (Schere). */
val ScissorsIcon: ImageVector by lazy {
    ImageVector.Builder("Cut", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(
            "M9.64,7.64c0.23,-0.5 0.36,-1.05 0.36,-1.64c0,-2.21 -1.79,-4 -4,-4S2,3.79 2,6s1.79,4 4,4c0.59,0 1.14,-0.13 1.64,-0.36L10,12" +
                "l-2.36,2.36C7.14,14.13 6.59,14 6,14c-2.21,0 -4,1.79 -4,4s1.79,4 4,4s4,-1.79 4,-4c0,-0.59 -0.13,-1.14 -0.36,-1.64L12,14" +
                "l7,7h3v-1L9.64,7.64zM6,8c-1.1,0 -2,-0.89 -2,-2s0.9,-2 2,-2s2,0.89 2,2S7.1,8 6,8zM6,20c-1.1,0 -2,-0.89 -2,-2s0.9,-2 2,-2" +
                "s2,0.89 2,2S7.1,20 6,20zM12,12.5c-0.28,0 -0.5,-0.22 -0.5,-0.5s0.22,-0.5 0.5,-0.5s0.5,0.22 0.5,0.5S12.28,12.5 12,12.5z" +
                "M19,3l-6,6l2,2l7,-7V3z"
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Teilen: "][" – zwei Bausteine, die an einem Balken aneinanderstoßen. */
val SplitIcon: ImageVector by lazy {
    ImageVector.Builder("Split", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M4,4h7v16H4v-2h5V6H4zM20,4h-7v16h7v-2h-5V6h5z"),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Balken löschen: "[ ]" – die beiden Bausteine werden wieder einer. */
val MergeIcon: ImageVector by lazy {
    ImageVector.Builder("Merge", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M3,4h6v2H5v12h4v2H3zM21,4h-6v2h4v12h-4v2h6z"),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material-Symbol "drag_handle" (Griff zum Verschieben). */
val DragHandleIcon: ImageVector by lazy {
    ImageVector.Builder("DragHandle", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M20,9H4v2h16V9zM4,15h16v-2H4v2z"),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material-Symbol "merge_type" (Zusammenfügen). */
val JoinIcon: ImageVector by lazy {
    ImageVector.Builder("Join", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes("M17,20.41L18.41,19 15,15.59 13.59,17 17,20.41zM7.5,8H11v5.59L5.59,19 7,20.41l6,-6V8h3.5L12,3.5 7.5,8z"),
        fill = SolidColor(Color.Black),
    ).build()
}
