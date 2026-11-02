package app.simplestcut.effects

import kotlin.math.min

/** Affine Abbildung (x, y) -> (a·x + b·y + c, d·x + e·y + f). */
class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    fun mapX(x: Float, y: Float) = a * x + b * y + c
    fun mapY(x: Float, y: Float) = d * x + e * y + f

    /** Erst diese Abbildung, danach [o]. */
    fun then(o: Affine) = Affine(
        o.a * a + o.b * d, o.a * b + o.b * e, o.a * c + o.b * f + o.c,
        o.d * a + o.e * d, o.d * b + o.e * e, o.d * c + o.e * f + o.f,
    )

    companion object {
        val IDENTITY = Affine(1f, 0f, 0f, 0f, 1f, 0f)
        fun scale(sx: Float, sy: Float = sx) = Affine(sx, 0f, 0f, 0f, sy, 0f)
        fun translate(tx: Float, ty: Float) = Affine(1f, 0f, tx, 0f, 1f, ty)
    }
}

/** Rechteck in Bildschirm-Pixeln. */
data class ScreenRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right get() = left + width
    val bottom get() = top + height
}

/** Bild der Grösse [w]×[h] im Uhrzeigersinn um [rotation] drehen: Abbildung und neue Grösse. */
private fun rotateFrame(rotation: Int, w: Float, h: Float): Pair<Affine, Pair<Float, Float>> = when (rotation) {
    90 -> Affine(0f, -1f, h, 1f, 0f, 0f) to (h to w)
    180 -> Affine(-1f, 0f, w, 0f, -1f, h) to (w to h)
    270 -> Affine(0f, 1f, 0f, -1f, 0f, w) to (h to w)
    else -> Affine.IDENTITY to (w to h)
}

/** Bild [w]×[h] mittig in [bw]×[bh] einpassen (schwarze Balken). */
private fun fit(w: Float, h: Float, bw: Float, bh: Float): Affine {
    val k = min(bw / w, bh / h)
    return Affine.scale(k).then(Affine.translate((bw - k * w) / 2, (bh - k * h) / 2))
}

/** Wie weit die Vorschau die Effekte zeigt (beim Bearbeiten eines Effekts ohne diesen, mit Rahmen darüber). */
enum class PreviewStage {
    /** Alles. */
    FULL,

    /** Ganzes Video nur gedreht (Zuschneiden des ganzen Videos wird bearbeitet). */
    WHOLE_ROTATED,

    /** Alles ausser dem Zoom des Abschnitts (der wird bearbeitet). */
    WITHOUT_ZOOM,
}

/**
 * Vorschau der Effekte: Wohin ein Bild des laufenden Clips (Grösse [clipW]×[clipH] wie angezeigt) auf dem
 * Bildschirm ([viewW]×[viewH]) kommt. Gleiche Reihenfolge wie beim Export (siehe Transcoder): Clip ins
 * Grundbild [baseW]×[baseH] einpassen, ganzes Video drehen und zuschneiden, Abschnitt drehen (eingepasst) und zoomen.
 */
class PreviewGeometry(
    clipW: Float, clipH: Float,
    baseW: Float, baseH: Float,
    whole: WholeFx, section: SectionFx,
    viewW: Float, viewH: Float,
    stage: PreviewStage,
) {
    /** Clip-Pixel -> Bildschirm. */
    val transform: Affine

    /** Wo das gezeigte Bild (Ergebnis bzw. beim Bearbeiten das bearbeitete Bild) auf dem Bildschirm liegt. */
    val frame: ScreenRect

    /** Seitenverhältnis (Breite/Höhe in Pixeln) des gezeigten Bildes. */
    val frameRatio: Float

    init {
        var m = fit(clipW, clipH, baseW, baseH)
        val (rot, rotated) = rotateFrame(whole.rotation, baseW, baseH)
        m = m.then(rot)
        var (w, h) = rotated
        if (stage != PreviewStage.WHOLE_ROTATED) {
            whole.crop?.let { c ->
                m = m.then(Affine.translate(-c.left * w, -c.top * h))
                w *= c.width
                h *= c.height
            }
            if (section.rotation != 0) {
                val (srot, size) = rotateFrame(section.rotation, w, h)
                m = m.then(srot).then(fit(size.first, size.second, w, h))
            }
            if (stage == PreviewStage.FULL) section.zoom?.let { z ->
                m = m.then(Affine.translate(-z.left * w, -z.top * h)).then(Affine.scale(1 / z.width, 1 / z.height))
            }
        }
        val toView = fit(w, h, viewW, viewH)
        transform = m.then(toView)
        frame = ScreenRect(toView.mapX(0f, 0f), toView.mapY(0f, 0f), toView.a * w, toView.e * h)
        frameRatio = w / h
    }
}
