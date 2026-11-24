package app.simplestcut.effects

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.min

/**
 * Text zeichnen: dieselbe Routine für die Vorschau (Bildschirm, Bild [w]×[h] in Bildschirm-Pixeln) und den Export
 * (Ergebnisbild in Video-Pixeln). Alle Masse hängen nur von [w]/[h] ab, darum sieht beides gleich aus.
 */
object TextRender {
    private val typefaces = HashMap<TextFont, Typeface>()

    private fun typeface(font: TextFont) = typefaces.getOrPut(font) {
        Typeface.create(font.family, if (font.bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private class Prepared(val layout: StaticLayout, val left: Float, val top: Float, val px: Float)

    private fun prepare(t: TextFx, w: Float, h: Float, paint: TextPaint): Prepared {
        val px = t.size * min(w, h)
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = typeface(t.font)
        paint.textSize = px.coerceAtLeast(1f)
        val maxWidth = (w * 0.92f).toInt().coerceAtLeast(1)
        val text = t.text.ifEmpty { " " }
        val natural = text.split('\n').maxOf { paint.measureText(it) }.toInt() + 2
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, natural.coerceIn(1, maxWidth))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
        return Prepared(layout, t.x * w - layout.width / 2f, t.y * h - layout.height / 2f, px)
    }

    /** Fläche, die der Text einnimmt (mit Kasten bzw. Kontur), in Pixeln von [w]×[h]. */
    fun bounds(t: TextFx, w: Float, h: Float): RectF {
        val p = prepare(t, w, h, TextPaint())
        val pad = p.px * 0.3f
        return RectF(p.left - pad, p.top - pad, p.left + p.layout.width + pad, p.top + p.layout.height + pad)
    }

    /** [t] in ein Bild [w]×[h] zeichnen (Ursprung oben links), mit Deckkraft [alpha]. */
    fun draw(canvas: Canvas, t: TextFx, w: Float, h: Float, alpha: Float = 1f) {
        if (t.text.isBlank() || alpha <= 0f) return
        val paint = TextPaint()
        val p = prepare(t, w, h, paint)
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.translate(p.left, p.top)
        when (t.style) {
            TextStyle.BOX -> {
                val pad = p.px * 0.25f
                val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt(); this.alpha = (0x99 * alpha).toInt() }
                for (line in 0 until p.layout.lineCount) {
                    val l = p.layout.getLineLeft(line)
                    val r = p.layout.getLineRight(line)
                    canvas.drawRoundRect(
                        l - pad, p.layout.getLineTop(line) - pad * 0.4f, r + pad, p.layout.getLineBottom(line) + pad * 0.4f,
                        pad, pad, box,
                    )
                }
            }
            TextStyle.OUTLINE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = p.px * 0.14f
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = if (isDark(t.color)) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
                paint.alpha = a
                p.layout.draw(canvas)
                paint.style = Paint.Style.FILL
            }
            TextStyle.SHADOW -> paint.setShadowLayer(p.px * 0.09f, p.px * 0.03f, p.px * 0.04f, (0xB0 * alpha).toInt() shl 24)
        }
        paint.color = t.color
        paint.alpha = a
        p.layout.draw(canvas)
        canvas.restore()
    }

    private fun isDark(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return r * 0.3 + g * 0.59 + b * 0.11 < 90
    }
}
