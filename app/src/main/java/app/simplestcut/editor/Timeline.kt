package app.simplestcut.editor

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

private val thumbDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

@Composable
fun Timeline(
    /** Ein Eintrag je Clip (siehe [FrameIndex.clipStart]). */
    uris: List<Uri>,
    state: CutState,
    playheadFrame: Int,
    playheadDraggable: Boolean,
    onSeekFrame: (Int) -> Unit,
    onEdgeDrag: (Int) -> Unit,
    onEdgeDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val frames = state.frames
    val colors = MaterialTheme.colorScheme
    val handleTouchPx = with(density) { 24.dp.toPx() }
    val thumbHeightPx = with(density) { 56.dp.toPx() }
    // Abstand zum Bildschirmrand, damit Start/Ende gut greifbar sind.
    val marginPx = with(density) { 28.dp.toPx() }

    var widthPx by remember { mutableIntStateOf(0) }
    val innerPx = (widthPx - 2 * marginPx).coerceAtLeast(1f)
    val layout = state.layout
    val totalUs = layout.totalUs.toDouble()

    // Für das Zoomen mit zwei Fingern im ganzen Bedienbereich (siehe CutState.zoomAround).
    state.timelineMarginPx = marginPx
    state.timelineInnerPx = if (widthPx == 0) 0f else innerPx

    fun clampView() = state.clampView()

    // Anfangs bzw. nach Änderungen der Länge passt alles Mögliche in die Ansicht.
    LaunchedEffect(widthPx, layout) {
        if (widthPx > 0 && state.usPerPx == 0.0) {
            state.usPerPx = totalUs / innerPx
            state.viewStartUs = 0.0
        }
        clampView()
    }

    // Vorschaubilder: je Keyframe ein Bild (schnell zu dekodieren), nur für den sichtbaren Bereich.
    val context = LocalContext.current
    val thumbs = remember(uris) { mutableStateMapOf<Int, ImageBitmap>() }
    val retrievers = remember(uris) { uris.map { MediaMetadataRetriever() } }
    DisposableEffect(uris) {
        onDispose {
            // Erst freigeben, wenn kein Vorschaubild mehr dekodiert wird.
            thumbDispatcher.executor.execute { retrievers.forEach { it.release() } }
        }
    }
    LaunchedEffect(uris) {
        withContext(thumbDispatcher) { retrievers.zip(uris).forEach { (r, u) -> r.setDataSource(context, u) } }
        // Breite hier jedes Mal neu lesen: innerPx aus dem ersten Aufbau wäre noch 1 px (Leiste nicht ausgemessen).
        snapshotFlow { listOf(state.viewStartUs, state.usPerPx, widthPx.toDouble(), state.layout) }.collectLatest { _ ->
            val usPx = state.usPerPx
            val l = state.layout
            if (widthPx == 0 || usPx == 0.0) return@collectLatest
            val innerPx = widthPx - 2 * marginPx
            val tileUs = thumbHeightPx * usPx
            val spanUs = innerPx * usPx
            val needed = mutableListOf<Int>()
            fun collect(from: Double, to: Double) {
                // Auf dieselben Kachelgrenzen wie beim Zeichnen runden, sonst fehlt links mal ein Bild.
                var t = floor(from.coerceAtLeast(0.0) / tileUs) * tileUs
                while (t < to && t < l.totalUs) {
                    val key = keyframeAtOrBefore(frames, l.frameAtVirtual(t.toLong()))
                    if (key !in thumbs && key !in needed) needed += key
                    t += tileUs
                }
            }
            // Erst das Sichtbare, dann je eine Breite rechts und links vorausladen (fürs Verschieben).
            val viewStart = state.viewStartUs
            collect(viewStart, viewStart + spanUs)
            collect(viewStart + spanUs, viewStart + 2 * spanUs)
            collect(viewStart - spanUs, viewStart)
            for (key in needed) {
                // Ein angefangenes Bild fertig dekodieren und behalten, auch wenn Zoomen/Verschieben neu plant.
                val bmp = withContext(NonCancellable + thumbDispatcher) {
                    runCatching {
                        retrievers[frames.clipOf(key)].getScaledFrameAtTime(
                            frames.localUs(key), MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            thumbHeightPx.toInt(), thumbHeightPx.toInt(),
                        )
                    }.getOrNull()
                } ?: continue
                thumbs[key] = cropSquare(bmp).asImageBitmap()
            }
        }
    }

    val currentPlayhead by rememberUpdatedState(playheadFrame)
    val currentPlayheadDraggable by rememberUpdatedState(playheadDraggable)
    val currentOnSeek by rememberUpdatedState(onSeekFrame)
    val currentHaptics by rememberUpdatedState(LocalHapticFeedback.current)
    val edgeZonePx = with(density) { 40.dp.toPx() }
    val currentOnDrag by rememberUpdatedState(onEdgeDrag)
    val currentOnDragEnd by rememberUpdatedState(onEdgeDragEnd)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(80.dp)
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val l = state.layout
                    fun xOf(v: Long) = marginPx + ((v - state.viewStartUs) / state.usPerPx).toFloat()
                    fun vAt(x: Float) = (state.viewStartUs + (x - marginPx) * state.usPerPx).toLong()

                    // Nächster Balken in Reichweite?
                    var target: Int? = state.markers.indices
                        .map { it to abs(down.position.x - xOf(l.virtualOfResult(state.markers[it]))) }
                        .filter { it.second <= handleTouchPx }
                        .minByOrNull { it.second }?.first
                    // Sonst die Abspielposition (Balken haben Vorrang, wenn beide an derselben Stelle liegen).
                    var grabPlayhead = target == null && currentPlayheadDraggable &&
                        l.virtualOfFrame(currentPlayhead)?.let { abs(down.position.x - xOf(it)) <= handleTouchPx } == true
                    var moved = false
                    var dragged = false
                    // Lange drücken (ohne Balken unter dem Finger): Baustein anheben und verschieben.
                    var blockDrag = false

                    /** Lücke (Balken-Nummer), die der Fingerposition x am nächsten liegt. */
                    fun gapAt(x: Float): Int {
                        val r = l.resultNearVirtual(vAt(x))
                        return state.markers.indices.minBy { abs(state.markers[it] - r) }
                    }

                    while (true) {
                        val event = when {
                            !moved && !blockDrag && target == null -> {
                                val waited = SystemClock.uptimeMillis() - down.uptimeMillis
                                withTimeoutOrNull((viewConfiguration.longPressTimeoutMillis - waited).coerceAtLeast(1)) {
                                    awaitPointerEvent()
                                }
                            }
                            // Beim Ziehen eines Bausteins regelmäßig aufwachen, um am Rand mitzurollen.
                            blockDrag -> withTimeoutOrNull(16) { awaitPointerEvent() }
                            else -> awaitPointerEvent()
                        }
                        if (event == null) {
                            if (blockDrag) {
                                // Finger am Rand: Ansicht mitrollen (schneller, je näher am Rand)
                                val zone = edgeZonePx
                                val x = state.dragX
                                val speed = when {
                                    x < zone -> -(zone - x) / zone
                                    x > size.width - zone -> (x - (size.width - zone)) / zone
                                    else -> 0f
                                }
                                if (speed != 0f) {
                                    state.viewStartUs += speed * 12f * state.usPerPx
                                    clampView()
                                    state.dropGap = gapAt(x)
                                }
                            } else {
                                val frame = l.frameAtVirtual(vAt(down.position.x).coerceIn(0L, (l.totalUs - 1).coerceAtLeast(0L)))
                                state.draggedBlock = state.blockOfFrame(frame)
                                state.dropGap = null
                                state.dragX = down.position.x
                                state.selectedEdge = null
                                blockDrag = state.draggedBlock != null
                                grabPlayhead = false
                                moved = true
                                if (blockDrag) currentHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            continue
                        }
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            // Zwei Finger: Zoom übernimmt der Bedienbereich, hier nichts mehr bewegen.
                            target = null
                            grabPlayhead = false
                            moved = true
                            if (blockDrag) {
                                blockDrag = false
                                state.draggedBlock = null
                                state.dropGap = null
                            }
                        } else if (blockDrag) {
                            val x = pressed.first().position.x
                            state.dragX = x
                            state.dropGap = gapAt(x)
                        } else {
                            val change = pressed.first()
                            val dx = change.positionChange().x
                            if (!moved && abs(change.position.x - down.position.x) > viewConfiguration.touchSlop) moved = true
                            if (moved) {
                                val edge = target
                                if (grabPlayhead) {
                                    val v = vAt(change.position.x).coerceIn(0L, (l.totalUs - 1).coerceAtLeast(0L))
                                    state.selectedEdge = null
                                    dragged = true
                                    currentOnDrag(l.frameAtVirtual(v))
                                } else if (edge == null) {
                                    state.viewStartUs -= dx * state.usPerPx
                                    clampView()
                                } else {
                                    // Start-/End-Balken erzeugen beim Ziehen einen neuen Balken, der weiter gezogen wird.
                                    val i = state.moveEdge(edge, l.resultNearVirtual(vAt(change.position.x)))
                                    target = i
                                    state.selectedEdge = i
                                    dragged = true
                                    currentOnDrag(state.edgeFrame(i))
                                }
                            }
                        }
                        event.changes.forEach { it.consume() }
                    }
                    if (dragged) {
                        state.normalize()
                        currentOnDragEnd()
                    }
                    if (blockDrag) {
                        val b = state.draggedBlock
                        val gap = state.dropGap
                        state.draggedBlock = null
                        state.dropGap = null
                        if (b != null && gap != null) {
                            val newIndex = state.moveBlock(b, gap)
                            currentOnSeek(state.edgeFrame(newIndex))
                        }
                    }
                    if (!moved) {
                        val edge = target
                        if (edge != null) {
                            state.selectedEdge = edge
                            currentOnSeek(state.edgeFrame(edge))
                        } else {
                            val v = vAt(down.position.x).coerceIn(0L, (l.totalUs - 1).coerceAtLeast(0L))
                            state.selectedEdge = null
                            currentOnSeek(l.frameAtVirtual(v))
                        }
                    }
                }
            }
    ) {
        if (widthPx != size.width.toInt()) widthPx = size.width.toInt()
        if (state.usPerPx == 0.0) return@Canvas
        val l = state.layout
        val usPx = state.usPerPx
        val viewStart = state.viewStartUs
        fun x(v: Long) = marginPx + ((v - viewStart) / usPx).toFloat()
        val top = (size.height - thumbHeightPx) / 2f
        val contentEnd = x(l.totalUs).coerceAtMost(size.width - marginPx)
        val contentStart = x(0).coerceAtLeast(marginPx)

        // Vorschaubilder
        clipRect(contentStart, top, contentEnd, top + thumbHeightPx) {
            drawRect(colors.surfaceVariant, Offset(contentStart, top), Size(contentEnd - contentStart, thumbHeightPx))
            val tileUs = thumbHeightPx * usPx
            var i = (viewStart / tileUs).toLong()
            while (true) {
                val v = (i * tileUs).toLong()
                val tileX = x(v)
                if (tileX > contentEnd || v >= l.totalUs) break
                val key = keyframeAtOrBefore(frames, l.frameAtVirtual(v))
                thumbs[key]?.let { img ->
                    drawImage(
                        img,
                        dstOffset = IntOffset(tileX.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(thumbHeightPx.roundToInt() + 1, thumbHeightPx.roundToInt()),
                    )
                }
                i++
            }
        }

        // Gewählter Baustein = immer der unter der Abspielposition (auf einem Balken: der danach)
        state.blockOfFrame(playheadFrame)?.let { b ->
            val sx = x(l.virtualOfResult(state.markers[b]))
            val ex = x(l.virtualOfResult(state.markers[b + 1]))
            val w = 3.dp.toPx()
            drawRect(colors.primary, Offset(sx + w / 2, top + w / 2), Size(ex - sx - w, thumbHeightPx - w), style = Stroke(w))
        }

        // Bausteine mit Effekt: kleiner Punkt oben links
        if (state.spans.isNotEmpty()) for (b in 0 until state.blockCount) {
            if (!state.blockHasFx(b)) continue
            val sx = x(l.virtualOfResult(state.markers[b]))
            val r = 4.dp.toPx()
            drawCircle(Color.Black.copy(alpha = 0.6f), r + 1.5.dp.toPx(), Offset(sx + 9.dp.toPx(), top + 9.dp.toPx()))
            drawCircle(colors.tertiary, r, Offset(sx + 9.dp.toPx(), top + 9.dp.toPx()))
        }

        drawFrameTicks(l, viewStart, usPx, marginPx, size.width - marginPx, top, colors.primary)

        // Baustein wird verschoben: alte Stelle abgedunkelt, Einfügestelle als dicker Strich, Baustein am Finger
        state.draggedBlock?.let { b ->
            val sx = x(l.virtualOfResult(state.markers[b]))
            val ex = x(l.virtualOfResult(state.markers[b + 1]))
            drawRect(Color.Black.copy(alpha = 0.55f), Offset(sx, top), Size(ex - sx, thumbHeightPx))
            state.dropGap?.takeIf { it != b && it != b + 1 }?.let { gap ->
                val gx = x(l.virtualOfResult(state.markers[gap]))
                val w = 6.dp.toPx()
                drawRect(colors.tertiary, Offset(gx - w / 2, top - 10.dp.toPx()), Size(w, thumbHeightPx + 20.dp.toPx()))
            }
            val bw = (ex - sx).coerceIn(24.dp.toPx(), size.width * 0.4f)
            val fx = state.dragX - bw / 2
            drawRect(colors.tertiary.copy(alpha = 0.35f), Offset(fx, top - 4.dp.toPx()), Size(bw, thumbHeightPx + 8.dp.toPx()))
            drawRect(colors.tertiary, Offset(fx, top - 4.dp.toPx()), Size(bw, thumbHeightPx + 8.dp.toPx()), style = Stroke(2.dp.toPx()))
        }

        // Abspielposition
        l.virtualOfFrame(playheadFrame)?.let { v ->
            val ph = x(v)
            if (ph in 0f..size.width) drawLine(Color.White, Offset(ph, 0f), Offset(ph, size.height), strokeWidth = 2.dp.toPx())
        }

        // Nähte (hier wurde etwas entfernt) nur als dünne Linie zur Orientierung
        for (i in 1 until l.parts.size) {
            if (l.resultStart[i] in state.markers) continue
            val sx = x(l.virtStart[i])
            drawLine(colors.onSurface.copy(alpha = 0.6f), Offset(sx, top), Offset(sx, top + thumbHeightPx), strokeWidth = 1.dp.toPx())
        }

        // Balken: Start/Ende/Teilungen, ziehbar (mit Griff)
        for ((edge, r) in state.markers.withIndex()) {
            val selected = state.selectedEdge == edge
            drawSplit(x(l.virtualOfResult(r)), if (selected) colors.tertiary else colors.primary, top, thumbHeightPx, selected)
        }
    }
}

private fun keyframeAtOrBefore(frames: FrameIndex, frame: Int): Int {
    var i = frame
    while (i > 0 && !frames.isKey[i]) i--
    return i
}

/** Striche für Bilder (wenn genug Platz) und Keyframes, je Baustein. */
private fun DrawScope.drawFrameTicks(
    l: Layout, viewStart: Double, usPx: Double, minX: Float, maxX: Float, top: Float, keyColor: Color,
) {
    val frames = l.frames
    val framePx = frames.frameDurationUs / usPx
    val showFrames = framePx >= 4.0
    val showKeys = framePx * 10 >= 3.0
    if (!showFrames && !showKeys) return
    for ((pi, p) in l.parts.withIndex()) {
        val partStartUs = frames.boundaryUs(p.start)
        if (l.virtStart[pi] + l.durationUs(p) < viewStart) continue
        val from = frames.frameAt((partStartUs + viewStart - l.virtStart[pi]).toLong()).coerceIn(p.start, p.end)
        for (i in from until p.end) {
            val xi = minX + ((l.virtStart[pi] + frames.timesUs[i] - partStartUs - viewStart) / usPx).toFloat()
            if (xi < minX) continue
            if (xi > maxX) break
            if (frames.isKey[i] && showKeys) {
                drawLine(keyColor, Offset(xi, top - 8.dp.toPx()), Offset(xi, top), strokeWidth = 2.dp.toPx())
            } else if (showFrames) {
                drawLine(Color.White.copy(alpha = 0.7f), Offset(xi, top - 4.dp.toPx()), Offset(xi, top), strokeWidth = 1.dp.toPx())
            }
        }
    }
}

private fun DrawScope.drawSplit(x: Float, color: Color, top: Float, h: Float, selected: Boolean) {
    if (x < -50f || x > size.width + 50f) return
    val w = (if (selected) 5 else 3).dp.toPx()
    drawRect(color, Offset(x - w / 2, top - 6.dp.toPx()), Size(w, h + 12.dp.toPx()))
    // Griff in der Mitte
    val tabW = 14.dp.toPx()
    val tabH = 24.dp.toPx()
    drawRoundRect(color, Offset(x - tabW / 2, top + h / 2 - tabH / 2), Size(tabW, tabH), CornerRadius(4.dp.toPx()))
}

private fun cropSquare(b: Bitmap): Bitmap {
    val s = minOf(b.width, b.height)
    return Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s)
}
