package app.simplestcut.editor

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.simplestcut.effects.Affine
import app.simplestcut.effects.CensorFx
import app.simplestcut.effects.CensorMode
import app.simplestcut.effects.CropRect
import app.simplestcut.effects.PreviewGeometry
import app.simplestcut.effects.ScreenRect
import app.simplestcut.effects.TextFx
import app.simplestcut.effects.TextRender
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Text, wie er gerade zu sehen ist; [editIndex] = Nummer in der bearbeiteten Liste (null = nicht bearbeitbar). */
data class ShownText(val fx: TextFx, val alpha: Float, val editIndex: Int?)

/** Zensur, wie sie gerade zu sehen ist (Bereich zur Abspielposition). */
data class ShownCensor(val fx: CensorFx, val rect: CropRect, val editIndex: Int?)

/** Was die Vorschau über das Video legt, und was davon gerade bearbeitet wird. */
class OverlayShown(
    val texts: List<ShownText>,
    val censors: List<ShownCensor>,
    /** Text bzw. Zensur wird bearbeitet (Gesten gehören dann den Rahmen). */
    val editing: FxEditing,
    /** Gewählter Eintrag der bearbeiteten Liste. */
    val selected: Int?,
    val onSelect: (Int?) -> Unit,
    val onText: (Int, TextFx) -> Unit,
    val onCensorRect: (Int, CropRect) -> Unit,
) {
    val isEmpty get() = texts.isEmpty() && censors.isEmpty() && editing != FxEditing.TEXT && editing != FxEditing.CENSOR
}

/**
 * Zensur und Text in der Vorschau, im Ergebnisbild [g].frame. Verpixeln und Weichzeichnen brauchen das Videobild:
 * eine verkleinerte Kopie der TextureView (getBitmap liefert das Videobild ohne die Drehung/Verschiebung der
 * Ansicht, gestreckt auf die Grösse der Ansicht; [clipW]×[clipH] = Clip wie angezeigt).
 */
@Composable
fun OverlayLayer(g: PreviewGeometry, texture: TextureView?, clipW: Int, clipH: Int, shown: OverlayShown) {
    val grab = remember { FrameGrab() }
    var tick by remember { mutableIntStateOf(0) }
    val needGrab = texture != null && shown.censors.any { it.fx.mode != CensorMode.BLACK }
    if (needGrab) LaunchedEffect(texture) {
        var lastStamp = Long.MIN_VALUE
        while (true) {
            withFrameNanos { }
            val st = texture!!.surfaceTexture ?: continue
            // Nur neu kopieren, wenn ein neues Bild da ist (Zeitstempel der Fläche ändert sich).
            val stamp = st.timestamp
            if (stamp != lastStamp || grab.bitmap == null) {
                if (grab.capture(texture)) {
                    lastStamp = stamp
                    tick++
                }
            }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION") tick
        val f = g.frame
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            nc.save()
            nc.clipRect(f.left, f.top, f.right, f.bottom)
            val bmp = grab.bitmap
            // Kopie (Pixel) -> Clip-Pixel -> Bildschirm
            val toScreen = bmp?.let { Affine.scale(clipW.toFloat() / it.width, clipH.toFloat() / it.height).then(g.transform) }
            for (c in shown.censors) drawCensor(nc, c.fx, screenRect(f, c.rect), min(f.width, f.height), bmp, toScreen, grab)
            for (t in shown.texts) {
                nc.save()
                nc.translate(f.left, f.top)
                TextRender.draw(nc, t.fx, f.width, f.height, t.alpha)
                nc.restore()
            }
            nc.restore()
        }
    }
}

private fun screenRect(f: ScreenRect, r: CropRect) =
    RectF(f.left + r.left * f.width, f.top + r.top * f.height, f.left + r.right * f.width, f.top + r.bottom * f.height)

private fun Affine.toMatrix() = Matrix().apply { setValues(floatArrayOf(a, b, c, d, e, f, 0f, 0f, 1f)) }

/** Verkleinerte Kopie des Videobildes und Hilfsbilder fürs Verpixeln/Weichzeichnen. */
private class FrameGrab {
    var bitmap: Bitmap? = null
    private val small = HashMap<Long, Bitmap>()

    fun capture(view: TextureView): Boolean {
        if (!view.isAvailable || view.width <= 0 || view.height <= 0) return false
        val w = (view.width / 3).coerceAtLeast(1)
        val h = (view.height / 3).coerceAtLeast(1)
        val b = bitmap?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.getBitmap(b)
        bitmap = b
        return true
    }

    fun small(w: Int, h: Int): Bitmap = small.getOrPut((w.toLong() shl 32) or h.toLong()) {
        if (small.size > 16) small.clear()
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }
}

/**
 * Ein Zensur-Bereich [r] (Bildschirm) wie im Export (CensorEffect): Zellen ab der Ecke des Bereichs, Grösse als
 * Bruchteil der kürzeren Bildseite [short].
 */
private fun drawCensor(
    nc: android.graphics.Canvas, c: CensorFx, r: RectF, short: Float,
    bmp: Bitmap?, toScreen: Affine?, grab: FrameGrab,
) {
    if (r.width() < 1f || r.height() < 1f) return
    nc.save()
    if (c.oval) nc.clipPath(Path().apply { addOval(r, Path.Direction.CW) }) else nc.clipRect(r)
    if (c.mode == CensorMode.BLACK || bmp == null || toScreen == null) {
        nc.drawColor(android.graphics.Color.BLACK)
        nc.restore()
        return
    }
    val filter = Paint(Paint.FILTER_BITMAP_FLAG)
    when (c.mode) {
        CensorMode.PIXELATE -> {
            val cell = (c.cellSize * short).coerceAtLeast(2f)
            val nx = ceil(r.width() / cell).toInt().coerceIn(1, 256)
            val ny = ceil(r.height() / cell).toInt().coerceIn(1, 256)
            val s = grab.small(nx, ny)
            // Je Zelle ein Pixel (Mitte der Zelle, aus der verkleinerten Kopie gefiltert)
            val m = toScreen.then(Affine.translate(-r.left, -r.top)).then(Affine.scale(1 / cell))
            android.graphics.Canvas(s).apply {
                drawColor(android.graphics.Color.BLACK)
                drawBitmap(bmp, m.toMatrix(), filter)
            }
            nc.drawBitmap(s, null, RectF(r.left, r.top, r.left + nx * cell, r.top + ny * cell), Paint())
        }
        CensorMode.BLUR -> {
            val sigma = (c.blurSigma * short).coerceAtLeast(1f)
            val e = RectF(r.left - 3 * sigma, r.top - 3 * sigma, r.right + 3 * sigma, r.bottom + 3 * sigma)
            // Verkleinert (1,5 Sigma = 1 Pixel), dreimal Kastenfilter (zusammen etwa Gauss mit Sigma 1,4 Pixel)
            val k = sigma / 1.5f
            val nx = ceil(e.width() / k).toInt().coerceIn(1, 256)
            val ny = ceil(e.height() / k).toInt().coerceIn(1, 256)
            val s = grab.small(nx, ny)
            val m = toScreen.then(Affine.translate(-e.left, -e.top)).then(Affine.scale(1 / k))
            android.graphics.Canvas(s).apply {
                drawColor(android.graphics.Color.BLACK)
                drawBitmap(bmp, m.toMatrix(), filter)
            }
            boxBlur(s, 3)
            nc.drawBitmap(s, null, RectF(e.left, e.top, e.left + nx * k, e.top + ny * k), filter)
        }
        CensorMode.BLACK -> {}
    }
    nc.restore()
}

/** [passes] mal Kastenfilter mit Radius 1 (waagrecht und senkrecht). */
private fun boxBlur(b: Bitmap, passes: Int) {
    val w = b.width
    val h = b.height
    var src = IntArray(w * h)
    b.getPixels(src, 0, w, 0, 0, w, h)
    var dst = IntArray(w * h)
    repeat(passes) {
        for (dir in 0..1) {
            for (y in 0 until h) for (x in 0 until w) {
                var r = 0
                var g = 0
                var bl = 0
                for (d in -1..1) {
                    val xx = if (dir == 0) (x + d).coerceIn(0, w - 1) else x
                    val yy = if (dir == 1) (y + d).coerceIn(0, h - 1) else y
                    val p = src[yy * w + xx]
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    bl += p and 0xFF
                }
                dst[y * w + x] = (0xFF shl 24) or ((r / 3) shl 16) or ((g / 3) shl 8) or (bl / 3)
            }
            val t = src
            src = dst
            dst = t
        }
    }
    b.setPixels(src, 0, w, 0, 0, w, h)
}

/**
 * Bearbeiten von Text bzw. Zensur im Bild [frame]: antippen wählt, ziehen verschiebt, zwei Finger ändern die
 * Grösse, bei Zensur auch die Ecken des gewählten Bereichs. Tippen daneben hebt die Wahl auf.
 */
@Composable
fun OverlayEditLayer(frame: ScreenRect, shown: OverlayShown) {
    val current by rememberUpdatedState(shown)
    val handlePx = with(LocalDensity.current) { 28.dp.toPx() }
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(frame, shown.editing) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val s = current
                    val pos = down.position
                    if (s.editing == FxEditing.TEXT) {
                        val hit = s.texts.lastOrNull { t ->
                            t.editIndex != null && TextRender.bounds(t.fx, frame.width, frame.height).let { b ->
                                b.inset(-handlePx / 2, -handlePx / 2)
                                b.contains(pos.x - frame.left, pos.y - frame.top)
                            }
                        }
                        if (hit == null) {
                            s.onSelect(null)
                            return@awaitEachGesture
                        }
                        val index = hit.editIndex!!
                        s.onSelect(index)
                        down.consume()
                        var t = hit.fx
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                val z = event.calculateZoom()
                                if (z != 1f) t = t.copy(size = (t.size * z).coerceIn(0.02f, 0.3f))
                            } else if (pressed.size == 1) {
                                val d = pressed.first().positionChange()
                                t = t.copy(x = (t.x + d.x / frame.width).coerceIn(0f, 1f), y = (t.y + d.y / frame.height).coerceIn(0f, 1f))
                            }
                            current.onText(index, t)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    } else if (s.editing == FxEditing.CENSOR) {
                        fun fx(x: Float) = ((x - frame.left) / frame.width)
                        fun fy(y: Float) = ((y - frame.top) / frame.height)
                        val sel = s.censors.firstOrNull { it.editIndex != null && it.editIndex == s.selected }
                        // Ecke des gewählten Bereichs?
                        val corner = sel?.rect?.let { r ->
                            listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom).indexOfFirst { (cx, cy) ->
                                abs(frame.left + cx * frame.width - pos.x) < handlePx && abs(frame.top + cy * frame.height - pos.y) < handlePx
                            }
                        } ?: -1
                        val target = if (corner >= 0) sel else s.censors.lastOrNull { c ->
                            c.editIndex != null && fx(pos.x) in c.rect.left..c.rect.right && fy(pos.y) in c.rect.top..c.rect.bottom
                        }
                        if (target == null) {
                            s.onSelect(null)
                            return@awaitEachGesture
                        }
                        val index = target.editIndex!!
                        s.onSelect(index)
                        down.consume()
                        var r = target.rect
                        val minSize = 0.03f
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                val z = event.calculateZoom()
                                if (z != 1f) {
                                    val w = (r.width * z).coerceIn(minSize, 1f)
                                    val h = (r.height * z).coerceIn(minSize, 1f)
                                    r = placedRect((r.left + r.right) / 2, (r.top + r.bottom) / 2, w, h)
                                }
                            } else if (pressed.size == 1) {
                                val p = pressed.first()
                                if (corner < 0) {
                                    val d = p.positionChange()
                                    r = placedRect((r.left + r.right) / 2 + d.x / frame.width, (r.top + r.bottom) / 2 + d.y / frame.height, r.width, r.height)
                                } else {
                                    val x = fx(p.position.x).coerceIn(0f, 1f)
                                    val y = fy(p.position.y).coerceIn(0f, 1f)
                                    var left = r.left
                                    var top = r.top
                                    var right = r.right
                                    var bottom = r.bottom
                                    if (corner % 2 == 0) left = min(x, right - minSize) else right = max(x, left + minSize)
                                    if (corner < 2) top = min(y, bottom - minSize) else bottom = max(y, top + minSize)
                                    r = CropRect(left, top, right, bottom)
                                }
                            }
                            current.onCensorRect(index, r)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
            }
    ) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
        if (shown.editing == FxEditing.TEXT) {
            for (t in shown.texts) {
                val i = t.editIndex ?: continue
                val b = TextRender.bounds(t.fx, frame.width, frame.height)
                val selected = i == shown.selected
                drawRect(
                    if (selected) accent else Color.White.copy(alpha = 0.7f),
                    Offset(frame.left + b.left, frame.top + b.top), Size(b.width(), b.height()),
                    style = Stroke(if (selected) 2.dp.toPx() else 1.dp.toPx(), pathEffect = if (selected) null else dash),
                )
            }
        } else if (shown.editing == FxEditing.CENSOR) {
            for (c in shown.censors) {
                val i = c.editIndex ?: continue
                val l = frame.left + c.rect.left * frame.width
                val t = frame.top + c.rect.top * frame.height
                val r = frame.left + c.rect.right * frame.width
                val b = frame.top + c.rect.bottom * frame.height
                val selected = i == shown.selected
                drawRect(
                    if (selected) Color.White else Color.White.copy(alpha = 0.7f), Offset(l, t), Size(r - l, b - t),
                    style = Stroke(if (selected) 2.dp.toPx() else 1.dp.toPx(), pathEffect = if (selected) null else dash),
                )
                if (selected) {
                    val len = 16.dp.toPx()
                    val w = 4.dp.toPx()
                    for ((cx, cy) in listOf(l to t, r to t, l to b, r to b)) {
                        val dx = if (cx == l) 1f else -1f
                        val dy = if (cy == t) 1f else -1f
                        drawLine(accent, Offset(cx, cy), Offset(cx + dx * len, cy), w)
                        drawLine(accent, Offset(cx, cy), Offset(cx, cy + dy * len), w)
                    }
                }
            }
        }
    }
}

/** Rechteck der Grösse [w]×[h] um die Mitte ([cx], [cy]), ins Bild geschoben. */
private fun placedRect(cx: Float, cy: Float, w: Float, h: Float): CropRect {
    val left = (cx - w / 2).coerceIn(0f, 1f - w)
    val top = (cy - h / 2).coerceIn(0f, 1f - h)
    return CropRect(left, top, left + w, top + h)
}

/** Neuer Zensur-Bereich: in der Bildmitte, quadratisch (in Pixeln), 30 % der kürzeren Seite. */
fun newCensorRect(frameRatio: Float): CropRect {
    val side = 0.3f
    val w = if (frameRatio >= 1f) side / frameRatio else side
    val h = if (frameRatio >= 1f) side else side * frameRatio
    return CropRect(0.5f - w / 2, 0.5f - h / 2, 0.5f + w / 2, 0.5f + h / 2)
}
