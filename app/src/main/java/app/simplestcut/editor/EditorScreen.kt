package app.simplestcut.editor

import app.simplestcut.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.size
import android.Manifest
import android.content.pm.PackageManager
import android.app.Activity
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import app.simplestcut.gallery.RestoreIcon
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import app.simplestcut.export.ExportService
import app.simplestcut.export.exportCut
import app.simplestcut.export.smartCutPossible
import app.simplestcut.mp4.Mp4Source
import app.simplestcut.gallery.VideoInfoDialog
import app.simplestcut.gallery.relativeToStorage
import app.simplestcut.export.outputDirFor
import app.simplestcut.storage.LocalMediaAccess
import app.simplestcut.gallery.PauseIcon
import app.simplestcut.gallery.MergeIcon
import app.simplestcut.gallery.ExportIcon
import app.simplestcut.merge.ClipFormat
import app.simplestcut.merge.MixSettings
import app.simplestcut.effects.EffectPlan
import app.simplestcut.effects.CropAspect
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import app.simplestcut.gallery.SaveIcon
import app.simplestcut.gallery.ScissorsIcon
import app.simplestcut.gallery.SplitIcon
import app.simplestcut.gallery.VideoItem
import app.simplestcut.gallery.loadVideoAt
import app.simplestcut.gallery.shareVideos
import app.simplestcut.settings.AppSettings
import app.simplestcut.settings.ControlsDim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Player für ein Video; über "Schneiden" in den Schnittmodus. Mit mehreren Videos (Zusammenfügen) direkt im
 * Schnittmodus, alle Clips hintereinander, an den Grenzen vorgesetzte Balken.
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    videos: List<VideoItem>,
    settings: AppSettings,
    onBack: () -> Unit,
    onSaved: (VideoItem) -> Unit,
    onDelete: () -> Unit = {},
    /** Nur für Videos aus dem Papierkorb. */
    onRestore: () -> Unit = {},
    /** Unpassende Clips zusammenfügen: nur Exportieren (alles neu kodieren) möglich. */
    mix: MixSettings? = null,
    /** Aus der Galerie geschnitten: direkt im Schnittmodus, Verlassen geht zurück zur Galerie. */
    startInCut: Boolean = false,
    /** Video wurde in den Infos umbenannt. */
    onRenamed: (VideoItem) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val video = videos.first()
    val merge = videos.size > 1
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Nach dem Umbenennen bleibt der Player (gleiche Videos, nur neuer Name).
    val player = remember(videos.map { it.uri }) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItems(videos.map { MediaItem.fromUri(it.uri) })
            // Im Hintergrund geöffnet (z. B. nach einem Export): nicht losspielen, wie nach dem Verlassen der App.
            playWhenReady = settings.autoplay && !merge && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            repeatMode = if (settings.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    // Wenn die App in den Hintergrund geht, anhalten.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.pause() }

    var cut by remember(videos.map { it.uri }) { mutableStateOf<CutState?>(null) }
    var loading by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    /** Exportieren geht nur durch komplettes Neukodieren (z. B. AV1, kein Smart Cut). */
    var noSmartCut by remember { mutableStateOf(false) }
    var confirmFullReencode by remember { mutableStateOf(false) }
    /** Smart Cut ist gescheitert (Grund): anbieten, das ganze Video neu zu kodieren. */
    var smartCutFailed by remember { mutableStateOf<String?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    /** Grösse der Clips wie angezeigt (für die Vorschau der Effekte). */
    var clipFormats by remember { mutableStateOf<List<ClipFormat>>(emptyList()) }
    /** Effekt, der gerade bearbeitet wird (Drehen oder Zuschneiden), und ob fürs ganze Video. */
    var fxTool by remember { mutableStateOf(FxEditing.NONE) }
    var fxWhole by remember { mutableStateOf(false) }
    var fxMenu by remember { mutableStateOf(false) }

    fun startCutting() {
        player.pause()
        player.repeatMode = Player.REPEAT_MODE_OFF
        loading = true
        scope.launch {
            runCatching {
                val sources = withContext(Dispatchers.IO) { videos.map { Mp4Source.read(File(it.path)) } }
                noSmartCut = !smartCutPossible(sources)
                clipFormats = sources.map { ClipFormat.of(it) }
                loadFrameIndex(sources)
            }
                .onSuccess { cut = CutState(it) }
                .onFailure {
                    errorText = if (merge) context.getString(R.string.error_load_merge, it.message)
                    else context.getString(R.string.error_load_cut, it.message)
                }
            loading = false
        }
    }
    // Zusammenfügen (und Schneiden aus der Galerie) beginnt direkt im Schnittmodus.
    LaunchedEffect(videos) { if (merge || startInCut) startCutting() }

    fun leaveCutting() {
        fxTool = FxEditing.NONE
        if (merge || startInCut) return onBack()
        cut = null
        player.repeatMode = if (settings.loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    /** Beim Zusammenfügen fragen wir immer nach, sonst nur bei Änderungen. */
    fun askBeforeLeaving(state: CutState) = merge || state.hasChanges

    /** Zu Bild [index] (Gesamt-Nummer über alle Clips) springen. */
    fun seekToFrame(f: FrameIndex, index: Int) {
        val i = index.coerceIn(0, f.size - 1)
        // Abrunden: der Player zeigt das erste Bild ab dieser Position, also genau dieses Bild.
        player.seekTo(f.clipOf(i), f.localUs(i) / 1000)
    }

    val c = cut
    BackHandler(enabled = c != null) {
        if (fxTool != FxEditing.NONE) fxTool = FxEditing.NONE
        else if (c != null && askBeforeLeaving(c)) confirmDiscard = true else leaveCutting()
    }

    // Abspielposition laufend abfragen (für Timeline und Vorschau).
    var playheadUs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    // Im Schnittmodus: Stück (in layout.parts), das gerade läuft.
    var playingPiece by remember { mutableStateOf(0) }
    LaunchedEffect(player) {
        while (true) {
            withFrameNanos { }
            val state = cut
            // Gesamtzeit über alle Clips: Beginn des laufenden Clips + Position darin.
            val clipOffset = state?.frames?.clipOffsetUs?.getOrNull(player.currentMediaItemIndex) ?: 0L
            playheadUs = clipOffset + player.currentPosition * 1000
            isPlaying = player.isPlaying
            if (state != null && player.isPlaying) {
                // Im Schnittmodus läuft nur das Ergebnis: Stück für Stück in seiner Reihenfolge (auch verschobene
                // Bausteine). Am Ende eines Stücks zum Anfang des nächsten springen.
                val frame = state.frames.frameAt(playheadUs + 999)
                val parts = state.layout.parts
                val piece = parts.getOrNull(playingPiece)
                if (piece == null) {
                    player.pause()
                } else if (frame !in piece.start until piece.end) {
                    val next = parts.getOrNull(playingPiece + 1)
                    if (next == null) {
                        player.pause()
                        seekToFrame(state.frames, piece.end - 1)
                    } else {
                        playingPiece++
                        if (next.start != frame) seekToFrame(state.frames, next.start)
                    }
                }
            }
        }
    }

    fun showFrame(index: Int) {
        val f = cut?.frames ?: return
        player.pause()
        seekToFrame(f, index)
    }

    /**
     * Doppeltippen: vor/zurück wie die Spul-Knöpfe des Players (15 s / 5 s), im Cut auf der Zeit des Ergebnisses;
     * läuft weiter, wenn es lief.
     */
    val seekFlash = remember { SeekFlash() }
    fun seekBy(forward: Boolean) {
        val stepMs = if (forward) player.seekForwardIncrement else player.seekBackIncrement
        val deltaUs = stepMs * 1000L * (if (forward) 1 else -1)
        val state = cut
        if (state == null) {
            val d = player.duration
            val max = if (d > 0) d else Long.MAX_VALUE
            player.seekTo((player.currentPosition + deltaUs / 1000).coerceIn(0L, max))
        } else {
            val layout = state.layout
            if (layout.totalFrames == 0) return
            val frame = state.frames.frameAt(playheadUs + 999)
            val v = layout.virtualOfFrame(frame) ?: 0L
            val target = layout.frameAtVirtual((v + deltaUs).coerceIn(0L, maxOf(0L, layout.totalUs - 1)))
            state.selectedEdge = null
            if (player.isPlaying) {
                playingPiece = state.pieceOfFrame(target) ?: 0
                seekToFrame(state.frames, target)
            } else {
                showFrame(target)
            }
        }
        seekFlash.add(forward, (stepMs / 1000).toInt())
    }

    var exportProgress by remember { mutableStateOf<Float?>(null) }
    var exportJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var progressValue by remember { mutableFloatStateOf(0f) }
    val mediaAccess = LocalMediaAccess.current
    // Ohne Erlaubnis läuft der Export-Dienst trotzdem, nur die Benachrichtigung fehlt.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    /**
     * [reencode] = Exportieren (Schnittkanten neu kodiert), sonst Speichern (verlustfrei mit Schnittliste).
     * [full] = Exportieren mit komplettem Neukodieren (nach gescheitertem Smart Cut, vom Nutzer bestätigt).
     */
    fun export(reencode: Boolean, confirmed: Boolean = false, full: Boolean = false) {
        val state = cut ?: return
        // Ohne Speichern-Möglichkeit (Effekte) wird ohnehin alles neu kodiert: keine Rückfrage.
        if (reencode && mix == null && noSmartCut && !confirmed && !state.needsReencode) {
            confirmFullReencode = true
            return
        }
        if (!reencode && state.needsReencode) return
        val ranges = state.keptRanges()
        if (ranges.isEmpty()) {
            errorText = context.getString(R.string.error_nothing_left)
            return
        }
        // Ziel klären (in App-Ordnern wie WhatsApp ggf. Ordnerfreigabe erfragen), dann erst loslegen.
        scope.launch {
            val original = File(videos.first().path).parentFile!!
            val output = outputDirFor(mediaAccess, original)
            val elsewhere = output.dir.absolutePath != original.absolutePath
            player.pause()
            progressValue = 0f
            exportProgress = 0f
            exporting = reencode || mix != null
            // Neukodieren kann lange dauern: Dienst mit Benachrichtigung, damit Android die App im Hintergrund nicht beendet.
            val background = exporting
            if (background) {
                if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                ExportService.start(context, context.getString(R.string.notification_exporting, video.name)) { exportJob?.cancel() }
            }
            // Liegt die App beim Ende im Hintergrund, bleibt eine Benachrichtigung mit dem Ergebnis stehen.
            var doneText: String? = null
            fun inBackground() = !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            // Mit Effekten: Abschnitte an den Grenzen der Effekte teilen, je Abschnitt sein Effekt.
            val withFx = if (state.hasEffects) state.effectRanges().flatMap { (r, fx) -> state.frames.segments(listOf(r)).map { it to fx } } else null
            val segments = withFx?.map { it.first } ?: state.frames.segments(ranges)
            val effects = withFx?.let { list -> EffectPlan(state.whole, list.map { it.second }) }
            exportJob = scope.launch {
                try {
                    val out = exportCut(context, videos.map { File(it.path) }, segments, output, onProgress = { p ->
                        progressValue = p
                        if (background) ExportService.progress(context, p)
                    }, reencode = reencode, mix = mix, fullReencode = full, effects = effects)
                    if (inBackground()) doneText = context.getString(R.string.exported_name, out.name)
                    val where = if (elsewhere) context.getString(R.string.saved_elsewhere, relativeToStorage(output.dir)) else ""
                        Toast.makeText(context, context.getString(if (exporting) R.string.exported_name else R.string.saved_name, out.name) + where, Toast.LENGTH_LONG).show()
                    // Gleich das neue Video zeigen; falls Android es (noch) nicht kennt, wie bisher.
                    val saved = loadVideoAt(context, out.absolutePath)
                    if (saved != null) onSaved(saved) else leaveCutting()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Abgebrochen: die angefangene Datei ist schon gelöscht.
                } catch (e: Exception) {
                    // Smart Cut gescheitert (z. B. Eigenheiten eines AV1-Encoders): nachfragen, ob alles neu kodiert werden soll.
                    if (reencode && mix == null && !noSmartCut && !full && effects?.needsReencode != true) smartCutFailed = e.message ?: e.toString()
                    else errorText = context.getString(if (exporting) R.string.export_failed_detail else R.string.save_failed_detail, e.message)
                    if (inBackground()) doneText = context.getString(R.string.export_failed)
                } finally {
                    if (background) ExportService.stop(context, doneText)
                    exportProgress = null
                    exportJob = null
                }
            }
        }
    }

    // Cut im Querformat: keine Titelzeile und keine Statusleiste, damit das Video mehr Höhe bekommt.
    // Exportieren/Speichern stehen dann unten in der Knopfreihe, Zurück geht per Geste (mit Rückfrage).
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val compact = c != null && landscape
    // Player im Querformat: Video über den ganzen Bildschirm, Titelzeile liegt darüber und
    // erscheint/verschwindet zusammen mit der Media3-Steuerung.
    val overlayBar = c == null && !(merge || startInCut) && landscape
    var controlsVisible by remember { mutableStateOf(true) }
    val hideStatusBar = compact || overlayBar
    val view = LocalView.current
    DisposableEffect(hideStatusBar) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        if (hideStatusBar && bars != null) {
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.statusBars())
        }
        onDispose { if (hideStatusBar) bars?.show(WindowInsetsCompat.Type.statusBars()) }
    }

    @Composable
    fun RowScope.saveActions() {
        IconButton(onClick = { export(reencode = true) }, enabled = exportProgress == null) {
            Icon(ExportIcon, contentDescription = stringResource(R.string.export))
        }
        IconButton(onClick = { export(reencode = false) }, enabled = exportProgress == null && mix == null && c?.needsReencode != true) {
            Icon(SaveIcon, contentDescription = stringResource(R.string.save))
        }
    }

    /** Effekte hinter den drei Punkten (in der Knopfreihe unten). */
    @Composable
    fun effectsMenu() {
        androidx.compose.foundation.layout.Box {
            IconButton(onClick = { fxMenu = true }, enabled = exportProgress == null && c != null) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.effects))
            }
            DropdownMenu(expanded = fxMenu, onDismissRequest = { fxMenu = false }) {
                fun open(tool: FxEditing) {
                    fxMenu = false
                    player.pause()
                    c?.selectedEdge = null
                    fxTool = tool
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.fx_rotate)) }, onClick = { open(FxEditing.ROTATE) })
                DropdownMenuItem(text = { Text(stringResource(R.string.fx_crop)) }, onClick = { open(FxEditing.CROP_WHOLE) })
                val block = c?.blockOfFrame(c.frames.frameAt(playheadUs + 999))
                if (c != null && block != null && c.blockHasFx(block)) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.fx_clear)) },
                        onClick = { fxMenu = false; c.setBlockFx(block, app.simplestcut.effects.SectionFx.NONE) },
                    )
                }
            }
    }
    }

    @Composable
    fun PlayerTopBar(colors: androidx.compose.material3.TopAppBarColors = TopAppBarDefaults.topAppBarColors()) {
        TopAppBar(
            colors = colors,
            title = { Text(video.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            },
            actions = {
                IconButton(onClick = { showInfo = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.info))
                }
                if (video.isTrashed) {
                    // Im Papierkorb: nur ansehen, zurückholen oder endgültig löschen.
                    IconButton(onClick = onRestore) {
                        Icon(RestoreIcon, contentDescription = stringResource(R.string.restore))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete_permanently))
                    }
                    return@TopAppBar
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete))
                }
                IconButton(onClick = { shareVideos(context, listOf(video.uri)) }) {
                    Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share))
                }
                IconButton(onClick = ::startCutting, enabled = !loading) {
                    Icon(ScissorsIcon, contentDescription = stringResource(R.string.title_cut))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            if (compact) {
                // keine Titelzeile
            } else if (c == null && (merge || startInCut)) {
                // Während die Clips geladen werden
                TopAppBar(
                    title = { Text(stringResource(R.string.title_editor)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                )
            } else if (overlayBar) {
                // Titelzeile liegt über dem Video (siehe unten)
            } else if (c == null) {
                PlayerTopBar()
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.title_editor)) },
                    navigationIcon = {
                        IconButton(onClick = { if (askBeforeLeaving(c)) confirmDiscard = true else leaveCutting() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cut_finish))
                        }
                    },
                    actions = { saveActions() },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val density = LocalDensity.current
            Box(
                Modifier.weight(1f).fillMaxWidth().background(Color.Black).doubleTapSeek(
                    // Nicht beim Zuschneiden/Drehen (dort gehören die Gesten dem Rahmen) und nicht während des Ladens/Exports.
                    enabled = !loading && exportProgress == null && fxTool == FxEditing.NONE && (c != null || !(merge || startInCut)),
                    blocked = { y, height ->
                        // Im Player: sichtbare Bedienleiste unten (und Titelzeile oben im Querformat) bleibt frei.
                        c == null && controlsVisible && with(density) {
                            y > height - 140.dp.toPx() || (overlayBar && y < 64.dp.toPx())
                        }
                    },
                    onSeek = ::seekBy,
                )
            ) {
                val effectFrame = c?.frames?.frameAt(playheadUs + 999) ?: 0
                val clip = clipFormats.getOrNull(c?.frames?.clipOf(effectFrame) ?: 0)
                val base = mix?.let { it.width to it.height } ?: clipFormats.firstOrNull()?.let { it.width to it.height }
                if (c != null && clip != null && base != null && (c.hasEffects || fxTool != FxEditing.NONE)) {
                    // Effekte in der Vorschau: eigene Fläche, die sich drehen und zuschneiden lässt.
                    val block = c.blockOfFrame(effectFrame)
                    EffectVideo(
                        player, clip.width, clip.height, base.first, base.second,
                        whole = c.whole,
                        section = c.fxOfFrame(effectFrame),
                        editing = when {
                            fxTool != FxEditing.CROP_WHOLE -> fxTool
                            fxWhole || block == null || c.blockCount <= 1 -> FxEditing.CROP_WHOLE
                            else -> FxEditing.ZOOM_SECTION
                        },
                        onWholeCrop = { c.whole = c.whole.copy(crop = it, aspect = if (c.whole.aspect == CropAspect.ORIGINAL) CropAspect.FREE else c.whole.aspect) },
                        onSectionZoom = { z -> block?.let { c.setBlockFx(it, c.blockFx(it).copy(zoom = z.takeUnless { r -> r.isFull })) } },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    VideoPlayer(
                        player, showControls = c == null, dim = settings.controlsDim,
                        onControlsVisible = { controlsVisible = it }, modifier = Modifier.fillMaxSize(),
                    )
                }
                SeekFlashOverlay(seekFlash)
                if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
                if (overlayBar) {
                    // Voll qualifiziert: sonst greift die ColumnScope-Variante des umgebenden Column.
                    androidx.compose.animation.AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut()) {
                        PlayerTopBar(
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Black.copy(alpha = 0.45f),
                                titleContentColor = Color.White,
                                navigationIconContentColor = Color.White,
                                actionIconContentColor = Color.White,
                            )
                        )
                    }
                }
            }
            if (c != null && fxTool != FxEditing.NONE) {
                val base = mix?.let { it.width to it.height } ?: clipFormats.firstOrNull()?.let { it.width to it.height } ?: (16 to 9)
                val (rw, rh) = c.whole.rotatedSize(base.first, base.second)
                EffectPanel(
                    cut = c,
                    block = c.blockOfFrame(c.frames.frameAt(playheadUs + 999)),
                    editing = fxTool,
                    wholeScope = fxWhole,
                    onScope = { fxWhole = it },
                    rotatedRatio = rw.toFloat() / rh,
                    onDone = { fxTool = FxEditing.NONE },
                )
            } else if (c != null) {
                CutControls(
                    uris = videos.map { it.uri },
                    cut = c,
                    playheadDraggable = settings.dragPlayhead,
                    // Position kommt auf ms abgerundet; +999 µs ergibt genau das angezeigte Bild.
                    playheadFrame = c.frames.frameAt(playheadUs + 999),
                    isPlaying = isPlaying,
                    compact = compact,
                    extraActions = { saveActions() },
                    effectsMenu = { effectsMenu() },
                    onShowFrame = ::showFrame,
                    onEdgeDrag = { frame ->
                        if (!player.isScrubbingModeEnabled) player.setScrubbingModeEnabled(true)
                        showFrame(frame)
                    },
                    onEdgeDragEnd = {
                        player.setScrubbingModeEnabled(false)
                        c.selectedEdge?.let { showFrame(c.edgeFrame(it)) }
                    },
                    onPlayPause = {
                        c.selectedEdge = null
                        if (player.isPlaying) {
                            player.pause()
                        } else {
                            val frame = c.frames.frameAt(playheadUs + 999)
                            // Ab der Abspielposition; am Ende des Ergebnisses von vorn.
                            var piece = c.pieceOfFrame(frame)
                            var start = frame
                            val atEnd = piece == c.layout.parts.lastIndex && frame == c.layout.parts.last().end - 1
                            if (piece == null || atEnd) {
                                piece = 0
                                start = c.layout.parts[0].start
                            }
                            playingPiece = piece
                            if (start != frame || player.playbackState == Player.STATE_ENDED) seekToFrame(c.frames, start)
                            player.play()
                        }
                    },
                )
            }
        }
    }

    if (showInfo) {
        VideoInfoDialog(
            video,
            onDismiss = { showInfo = false },
            onRenamed = if (video.isTrashed) null else { renamed -> showInfo = false; onRenamed(renamed) },
        )
    }
    if (exportProgress != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(if (exporting) R.string.exporting else R.string.saving)) },
            text = { LinearProgressIndicator(progress = { progressValue }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { exportJob?.cancel() }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (confirmFullReencode) {
        AlertDialog(
            onDismissRequest = { confirmFullReencode = false },
            title = { Text(stringResource(R.string.full_reencode_title)) },
            text = {
                Text(
                    stringResource(R.string.full_reencode_format)
                )
            },
            confirmButton = { TextButton(onClick = { confirmFullReencode = false; export(reencode = true, confirmed = true) }) { Text(stringResource(R.string.export)) } },
            dismissButton = { TextButton(onClick = { confirmFullReencode = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    smartCutFailed?.let { reason ->
        AlertDialog(
            onDismissRequest = { smartCutFailed = null },
            title = { Text(stringResource(R.string.full_reencode_title)) },
            text = {
                Text(
                    stringResource(R.string.full_reencode_failed, reason)
                )
            },
            confirmButton = { TextButton(onClick = { smartCutFailed = null; export(reencode = true, confirmed = true, full = true) }) { Text(stringResource(R.string.export)) } },
            dismissButton = { TextButton(onClick = { smartCutFailed = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.discard_title)) },
            text = { Text(stringResource(R.string.discard_text)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; leaveCutting() }) { Text(stringResource(R.string.discard)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.keep_cutting)) } },
        )
    }
    errorText?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorText = null },
            title = { Text(stringResource(R.string.notice)) },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = {
                    errorText = null
                    // Konnten die Videos zum Zusammenfügen nicht geladen werden, zurück zur Auswahl.
                    if (merge && cut == null) onBack()
                }) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
}

@Composable
private fun CutControls(
    uris: List<android.net.Uri>,
    cut: CutState,
    playheadDraggable: Boolean,
    playheadFrame: Int,
    isPlaying: Boolean,
    /** Querformat: Zeiten kleiner ohne Beschriftung, [extraActions] am Ende der Knopfreihe. */
    compact: Boolean,
    extraActions: @Composable RowScope.() -> Unit,
    effectsMenu: @Composable () -> Unit,
    onShowFrame: (Int) -> Unit,
    onEdgeDrag: (Int) -> Unit,
    onEdgeDragEnd: () -> Unit,
    onPlayPause: () -> Unit,
) {
    val frames = cut.frames
    val layout = cut.layout
    Column(
        Modifier
            .fillMaxWidth()
            // Zwei Finger irgendwo im Bedienbereich zoomen die Timeline (nicht nur auf dem schmalen Streifen).
            .pointerInput(cut) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoom = event.calculateZoom()
                            val centroid = event.calculateCentroid()
                            if (zoom != 1f && centroid != Offset.Unspecified) cut.zoomAround(zoom, centroid.x)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .padding(vertical = if (compact) 4.dp else 8.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TimeLabel(stringResource(R.string.position), layout.virtualOfFrame(playheadFrame) ?: 0L, compact)
            TimeLabel(stringResource(R.string.length), layout.totalUs, compact)
        }
        if (!compact) Spacer(Modifier.height(4.dp))
        Timeline(
            uris = uris,
            state = cut,
            playheadFrame = playheadFrame,
            playheadDraggable = playheadDraggable,
            onSeekFrame = onShowFrame,
            onEdgeDrag = onEdgeDrag,
            onEdgeDragEnd = onEdgeDragEnd,
        )
        Spacer(Modifier.height(8.dp))

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ◀ / ▶: gewählten Balken bzw. Abspielposition um ein Bild verschieben
            OutlinedIconButton(onClick = { stepFrame(cut, playheadFrame, -1, onShowFrame) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.frame_back))
            }
            FilledIconButton(onClick = onPlayPause) {
                if (isPlaying) Icon(PauseIcon, contentDescription = stringResource(R.string.pause))
                else Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.play))
            }
            OutlinedIconButton(onClick = { stepFrame(cut, playheadFrame, 1, onShowFrame) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.frame_forward))
            }
            // Ist eine Teilung gewählt, wird aus "Teilen" "Balken löschen".
            val edge = cut.selectedEdge
            if (edge != null && cut.isInnerSplit(edge)) {
                val context = LocalContext.current
                OutlinedIconButton(onClick = {
                    if (!cut.mergeAt(edge)) Toast.makeText(context, context.getString(R.string.fx_keep_bar), Toast.LENGTH_SHORT).show()
                }) { Icon(MergeIcon, contentDescription = stringResource(R.string.remove_bar)) }
            } else {
                OutlinedIconButton(onClick = {
                    if (cut.split(playheadFrame)) onShowFrame(playheadFrame)
                }) { Icon(SplitIcon, contentDescription = stringResource(R.string.split)) }
            }

            // Mülleimer: immer der Baustein unter der Abspielposition
            val block = cut.blockOfFrame(playheadFrame)
            IconButton(
                onClick = {
                    val b = block ?: return@IconButton
                    val at = cut.markers[b]
                    cut.deleteBlock(b)
                    // Dort weiter, wo der Baustein war (am Ende: letztes Bild).
                    onShowFrame(cut.layout.frameOfResult(minOf(at, cut.layout.totalFrames - 1)))
                },
                enabled = block != null && cut.blockCount > 1,
            ) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.remove_piece)) }

            effectsMenu()

            if (compact) Row(Modifier.padding(start = 16.dp)) { extraActions() }
        }
    }
}

private fun stepFrame(cut: CutState, playheadFrame: Int, n: Int, onShowFrame: (Int) -> Unit) {
    val edge = cut.selectedEdge
    if (edge != null && edge in cut.markers.indices) {
        cut.selectedEdge = cut.moveEdge(edge, cut.markers[edge] + n)
        cut.normalize()
        cut.selectedEdge?.let { onShowFrame(cut.edgeFrame(it)) }
    } else {
        // Abspielposition bildweise im Ergebnis (entfernte Teile fehlen, verschobene in ihrer neuen Reihenfolge)
        val r = (cut.layout.resultOfFrame(playheadFrame) ?: 0) + n
        cut.selectedEdge = null
        if (r in 0 until cut.layout.totalFrames) onShowFrame(cut.layout.frameOfResult(r))
    }
}

@Composable
private fun TimeLabel(label: String, us: Long, compact: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (!compact) Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            formatPrecise(us), fontFamily = FontFamily.Monospace,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
        )
    }
}

/** "1:02.345" */
private fun formatPrecise(us: Long): String {
    val ms = us / 1000
    return "%d:%02d.%03d".format(ms / 60000, (ms / 1000) % 60, ms % 1000)
}

/**
 * Dunkle Flächen der Media3-Bedienung je nach Einstellung: Schleier übers ganze Bild,
 * Hintergrund der unteren Leiste, bei "Nur hinter Knöpfen" Kreise hinter den Mittelknöpfen.
 */
private fun applyControlsDim(view: PlayerView, dim: ControlsDim) {
    val ctx = view.context
    val scrim = androidx.core.content.ContextCompat.getColor(ctx, androidx.media3.ui.R.color.exo_black_opacity_60)
    val bar = androidx.core.content.ContextCompat.getColor(ctx, androidx.media3.ui.R.color.exo_bottom_bar_background)
    val clear = android.graphics.Color.TRANSPARENT
    view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_controls_background)
        ?.setBackgroundColor(if (dim == ControlsDim.FULL) scrim else clear)
    view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_bottom_bar)
        ?.setBackgroundColor(if (dim == ControlsDim.NONE) clear else bar)
    (view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_center_controls) as? android.view.ViewGroup)
        ?.let { row -> row.background = if (dim == ControlsDim.BUTTONS) CirclesBehindButtons(row) else null }
}

/**
 * Hintergrund der Mittelknöpfe: ein leichter Kreis (35 % schwarz) pro sichtbarem Knopf, etwas kleiner
 * als der Knopf (ragt knapp über das Symbol), dazwischen bleibt das Bild frei.
 */
private class CirclesBehindButtons(private val row: android.view.ViewGroup) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x59000000 }
    private val shrink = 3f * row.resources.displayMetrics.density

    override fun draw(canvas: android.graphics.Canvas) {
        for (i in 0 until row.childCount) {
            val b = row.getChildAt(i)
            if (b.visibility != android.view.View.VISIBLE) continue
            val r = minOf(b.width, b.height) / 2f - shrink
            canvas.drawCircle(b.left + b.width / 2f, b.top + b.height / 2f, r, paint)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(
    player: ExoPlayer,
    showControls: Boolean,
    dim: ControlsDim,
    onControlsVisible: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val currentOnControlsVisible by rememberUpdatedState(onControlsVisible)
    // Neues Video (z. B. nach dem Speichern) = neuer Player: Fläche neu anlegen, sonst hängt sie am alten.
    key(player) {
        // Zoom ins Bild (nur Anzeige)
        val zoomPan = remember { ZoomPan() }
        var playerView by remember { mutableStateOf<PlayerView?>(null) }
        val fallbackBarPx = with(LocalDensity.current) { 140.dp.toPx() }
        // Der Listener meldet nur Wechsel: Anfangszustand (auch nach dem Drehen) einmal abfragen.
        val orientation = LocalConfiguration.current.orientation
        LaunchedEffect(playerView, showControls, orientation) {
            playerView?.let { currentOnControlsVisible(showControls && it.isControllerFullyVisible) }
        }
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    playerView = this
                    this.player = WithoutTrackSelection(player)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                        currentOnControlsVisible(v == android.view.View.VISIBLE)
                    })
                    // Bild läuft beim Ziehen der Zeitleiste mit; Scrubbing-Modus macht das schnelle Springen flüssiger.
                    // (Media3s eigenes setTimeBarScrubbingEnabled wirkt nur ohne unsere Hülle um den Player.)
                    (findViewById<android.view.View>(androidx.media3.ui.R.id.exo_progress) as? TimeBar)?.addListener(object : TimeBar.OnScrubListener {
                        override fun onScrubStart(timeBar: TimeBar, position: Long) = player.setScrubbingModeEnabled(true)
                        override fun onScrubMove(timeBar: TimeBar, position: Long) = player.seekTo(position)
                        override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) =
                            player.setScrubbingModeEnabled(false)
                    })
                }
            },
            update = { view ->
                view.useController = showControls
                if (!showControls) view.hideController()
                applyControlsDim(view, dim)
                view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_content_frame)?.apply {
                    scaleX = zoomPan.zoom
                    scaleY = zoomPan.zoom
                    translationX = zoomPan.pan.x
                    translationY = zoomPan.pan.y
                }
            },
            modifier = modifier
                .clipToBounds()
                .zoomPanGestures(zoomPan, showControls) { height ->
                    val controlsFallbackTop = height - fallbackBarPx
                    // Oberkante der Bedienleiste (Zeitleiste + Knöpfe): darunter hat sie immer Vorrang.
                    if (!showControls) return@zoomPanGestures Float.MAX_VALUE
                    val view = playerView ?: return@zoomPanGestures controlsFallbackTop
                    val bar = view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_progress)
                        ?: return@zoomPanGestures controlsFallbackTop
                    val a = IntArray(2).also { view.getLocationInWindow(it) }
                    val b = IntArray(2).also { bar.getLocationInWindow(it) }
                    val top = (b[1] - a[1]).toFloat()
                    if (top > 0f) top - bar.height else controlsFallbackTop
                },
        )
    }
}

/** Für die Bedienleiste: Tonspur-Auswahl ("Audio" im Menü) ausblenden, Geschwindigkeit bleibt. */
@OptIn(UnstableApi::class)
private class WithoutTrackSelection(player: Player) : ForwardingPlayer(player) {
    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().remove(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS).build()

    override fun isCommandAvailable(command: Int): Boolean =
        command != Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS && super.isCommandAvailable(command)
}

