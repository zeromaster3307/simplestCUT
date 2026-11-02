package app.simplestcut.editor

import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import app.simplestcut.effects.Affine
import app.simplestcut.effects.CropRect
import app.simplestcut.effects.PreviewGeometry
import app.simplestcut.effects.PreviewStage
import app.simplestcut.effects.ScreenRect
import app.simplestcut.effects.SectionFx
import app.simplestcut.effects.WholeFx
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Anzeige-Zoom ins Bild (nur zum Ansehen): zwei Finger zoomen, vergrössert schiebt ein Finger. */
class ZoomPan {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)

    fun reset() {
        zoom = 1f
        pan = Offset.Zero
    }
}

/**
 * Gesten für [ZoomPan] auf einer Fläche. [controlsTop] = Oberkante einer Bedienleiste (aus der Flächenhöhe),
 * darunter gelten die Gesten nicht (Float.MAX_VALUE = keine Leiste).
 */
fun Modifier.zoomPanGestures(z: ZoomPan, key: Any?, controlsTop: (height: Float) -> Float = { Float.MAX_VALUE }): Modifier =
    pointerInput(z, key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.y >= controlsTop(size.height.toFloat())) return@awaitEachGesture
            var moved = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                fun clampPan(p: Offset): Offset {
                    val maxX = (z.zoom - 1f) * size.width / 2f
                    val maxY = (z.zoom - 1f) * size.height / 2f
                    return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
                }
                if (pressed.size >= 2) {
                    moved = true
                    val factor = event.calculateZoom()
                    val centroid = event.calculateCentroid()
                    val newZoom = (z.zoom * factor).coerceIn(1f, 8f)
                    if (centroid != Offset.Unspecified) {
                        // Um die Fingermitte zoomen und mit den Fingern mitschieben
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val f = newZoom / z.zoom
                        z.zoom = newZoom
                        z.pan = clampPan((z.pan - (centroid - center)) * f + (centroid - center) + event.calculatePan())
                    }
                    event.changes.forEach { it.consume() }
                } else if (pressed.size == 1 && z.zoom > 1f) {
                    val change = pressed.first()
                    if (moved || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        moved = true
                        z.pan = clampPan(z.pan + change.positionChange())
                        change.consume()
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }

/** Was gerade im Effekte-Bereich bearbeitet wird (Rahmen über dem Bild). */
enum class FxEditing { NONE, ROTATE, CROP_WHOLE, ZOOM_SECTION }

/**
 * Video im Schnittmodus mit Effekten: eigene TextureView (lässt sich anders als die SurfaceView des PlayerView
 * beliebig drehen), Bild per Matrix so gelegt wie im Ergebnis, ausserhalb des Ergebnisbildes schwarz. Beim
 * Zuschneiden/Zoomen ein Rahmen zum Ziehen.
 */
@Composable
fun EffectVideo(
    player: ExoPlayer,
    /** Grösse des laufenden Clips wie angezeigt (Drehung eingerechnet). */
    clipW: Int, clipH: Int,
    /** Grundbild: Grösse des Ergebnisses vor den Effekten fürs ganze Video. */
    baseW: Int, baseH: Int,
    whole: WholeFx,
    section: SectionFx,
    editing: FxEditing,
    onWholeCrop: (CropRect) -> Unit,
    onSectionZoom: (CropRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val zoomPan = remember { ZoomPan() }
    val stage = when (editing) {
        FxEditing.CROP_WHOLE -> PreviewStage.WHOLE_ROTATED
        FxEditing.ZOOM_SECTION -> PreviewStage.WITHOUT_ZOOM
        else -> PreviewStage.FULL
    }
    // Beim Zuschneiden/Zoomen gehören die Gesten dem Rahmen: Anzeige-Zoom zurück.
    LaunchedEffect(editing) { if (editing == FxEditing.CROP_WHOLE || editing == FxEditing.ZOOM_SECTION) zoomPan.reset() }
    val geometry = if (size.width > 0 && size.height > 0 && clipW > 0 && clipH > 0) PreviewGeometry(
        clipW.toFloat(), clipH.toFloat(), baseW.toFloat(), baseH.toFloat(), whole, section,
        size.width.toFloat(), size.height.toFloat(), stage,
    ) else null

    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { size = it }
            .then(if (editing == FxEditing.CROP_WHOLE || editing == FxEditing.ZOOM_SECTION) Modifier else Modifier.zoomPanGestures(zoomPan, editing))
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = zoomPan.zoom
                scaleY = zoomPan.zoom
                translationX = zoomPan.pan.x
                translationY = zoomPan.pan.y
            }
        ) {
            var textureView by remember { mutableStateOf<TextureView?>(null) }
            DisposableEffect(player) {
                onDispose { textureView?.let { player.clearVideoTextureView(it) } }
            }
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).also {
                        textureView = it
                        player.setVideoTextureView(it)
                    }
                },
                update = { view ->
                    val g = geometry ?: return@AndroidView
                    // Die TextureView streckt das Bild auf ihre Grösse; erst zurück in Clip-Pixel, dann wie im Ergebnis.
                    val m = Affine.scale(clipW.toFloat() / size.width, clipH.toFloat() / size.height).then(g.transform)
                    view.setTransform(android.graphics.Matrix().apply {
                        setValues(floatArrayOf(m.a, m.b, m.c, m.d, m.e, m.f, 0f, 0f, 1f))
                    })
                    // setTransform zeichnet bei angehaltenem Video nicht neu (erst beim nächsten Bild oder Grössenwechsel).
                    view.invalidate()
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Ausserhalb des gezeigten Bildes schwarz (dort läge sonst der Rest des gedrehten/vergrösserten Videos).
            geometry?.let { g ->
                Canvas(Modifier.fillMaxSize()) {
                    val f = g.frame
                    val black = Color.Black
                    drawRect(black, Offset.Zero, Size(this.size.width, max(0f, f.top)))
                    drawRect(black, Offset(0f, f.bottom), Size(this.size.width, max(0f, this.size.height - f.bottom)))
                    drawRect(black, Offset(0f, f.top), Size(max(0f, f.left), f.height))
                    drawRect(black, Offset(f.right, f.top), Size(max(0f, this.size.width - f.right), f.height))
                }
            }
        }
        if (geometry != null) when (editing) {
            FxEditing.CROP_WHOLE -> CropOverlay(
                geometry.frame, whole.crop ?: CropRect.FULL, whole.aspect.ratio, geometry.frameRatio, onWholeCrop,
            )
            FxEditing.ZOOM_SECTION -> CropOverlay(
                geometry.frame, section.zoom ?: CropRect.FULL, geometry.frameRatio, geometry.frameRatio, onSectionZoom,
            )
            else -> {}
        }
    }
}

/**
 * Rahmen über dem Bild [frame]: innen ziehen verschiebt, an den Ecken ziehen ändert die Grösse, zwei Finger
 * zoomen. [ratio] = festes Seitenverhältnis (Breite/Höhe in Pixeln) oder null = frei; [frameRatio] = das des Bildes.
 */
@Composable
private fun CropOverlay(
    frame: ScreenRect,
    rect: CropRect,
    ratio: Float?,
    frameRatio: Float,
    onChange: (CropRect) -> Unit,
) {
    val current by rememberUpdatedState(rect)
    val change by rememberUpdatedState(onChange)
    val handlePx = with(LocalDensity.current) { 32.dp.toPx() }
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(frame, ratio, frameRatio) {
                // Höhe in Bruchteilen zu einer Breite in Bruchteilen bei festem Seitenverhältnis
                val k = ratio?.let { frameRatio / it }
                val minSize = 0.1f
                fun fx(x: Float) = ((x - frame.left) / frame.width).coerceIn(0f, 1f)
                fun fy(y: Float) = ((y - frame.top) / frame.height).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val r0 = current
                    val corners = listOf(r0.left to r0.top, r0.right to r0.top, r0.left to r0.bottom, r0.right to r0.bottom)
                    val corner = corners.indexOfFirst { (cx, cy) ->
                        abs(frame.left + cx * frame.width - down.position.x) < handlePx &&
                            abs(frame.top + cy * frame.height - down.position.y) < handlePx
                    }
                    val inside = fx(down.position.x) in r0.left..r0.right && fy(down.position.y) in r0.top..r0.bottom
                    if (corner < 0 && !inside) return@awaitEachGesture
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        val r = current
                        if (pressed.size >= 2) {
                            val z = event.calculateZoom()
                            if (z != 1f) {
                                var w = (r.width / z).coerceIn(minSize, 1f)
                                var h = if (k != null) w * k else (r.height / z).coerceIn(minSize, 1f)
                                if (h > 1f) { h = 1f; if (k != null) w = h / k }
                                change(placed((r.left + r.right) / 2, (r.top + r.bottom) / 2, w, h))
                            }
                        } else if (pressed.size == 1) {
                            val p = pressed.first()
                            if (corner < 0) {
                                val d = p.positionChange()
                                change(placed((r.left + r.right) / 2 + d.x / frame.width, (r.top + r.bottom) / 2 + d.y / frame.height, r.width, r.height))
                            } else {
                                // Gegenüberliegende Ecke bleibt stehen
                                val ax = if (corner % 2 == 0) r.right else r.left
                                val ay = if (corner < 2) r.bottom else r.top
                                val x = fx(p.position.x)
                                val y = fy(p.position.y)
                                val maxW = if (corner % 2 == 0) ax else 1f - ax
                                val maxH = if (corner < 2) ay else 1f - ay
                                var w: Float
                                var h: Float
                                if (k != null) {
                                    w = max(abs(x - ax), abs(y - ay) / k)
                                    w = min(w, min(maxW, maxH / k)).coerceAtLeast(min(minSize, min(maxW, maxH / k)))
                                    h = w * k
                                } else {
                                    w = abs(x - ax).coerceIn(min(minSize, maxW), maxW)
                                    h = abs(y - ay).coerceIn(min(minSize, maxH), maxH)
                                }
                                val left = if (corner % 2 == 0) ax - w else ax
                                val top = if (corner < 2) ay - h else ay
                                change(CropRect(left, top, left + w, top + h))
                            }
                        }
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        val l = frame.left + rect.left * frame.width
        val t = frame.top + rect.top * frame.height
        val r = frame.left + rect.right * frame.width
        val b = frame.top + rect.bottom * frame.height
        val dim = Color.Black.copy(alpha = 0.55f)
        // Abgedunkelt, was wegfällt
        drawRect(dim, Offset(frame.left, frame.top), Size(frame.width, t - frame.top))
        drawRect(dim, Offset(frame.left, b), Size(frame.width, frame.bottom - b))
        drawRect(dim, Offset(frame.left, t), Size(l - frame.left, b - t))
        drawRect(dim, Offset(r, t), Size(frame.right - r, b - t))
        // Rahmen, Drittellinien und Ecken
        drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx()))
        for (i in 1..2) {
            val x = l + (r - l) * i / 3
            val y = t + (b - t) * i / 3
            drawLine(Color.White.copy(alpha = 0.4f), Offset(x, t), Offset(x, b), 1.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.4f), Offset(l, y), Offset(r, y), 1.dp.toPx())
        }
        val c = 18.dp.toPx()
        val w = 4.dp.toPx()
        for ((cx, cy) in listOf(l to t, r to t, l to b, r to b)) {
            val dx = if (cx == l) 1f else -1f
            val dy = if (cy == t) 1f else -1f
            drawLine(accent, Offset(cx, cy), Offset(cx + dx * c, cy), w)
            drawLine(accent, Offset(cx, cy), Offset(cx, cy + dy * c), w)
        }
    }
}

/** Rechteck der Grösse [w]×[h] um die Mitte ([cx], [cy]), ins Bild geschoben. */
private fun placed(cx: Float, cy: Float, w: Float, h: Float): CropRect {
    val left = (cx - w / 2).coerceIn(0f, 1f - w)
    val top = (cy - h / 2).coerceIn(0f, 1f - h)
    return CropRect(left, top, left + w, top + h)
}
