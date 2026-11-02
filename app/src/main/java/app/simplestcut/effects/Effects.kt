package app.simplestcut.effects

import kotlin.math.roundToInt

/** Rechteck in Bruchteilen (0..1) eines Bildes, y nach unten. */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val isFull get() = left <= 0.0005f && top <= 0.0005f && right >= 0.9995f && bottom >= 0.9995f

    companion object {
        val FULL = CropRect(0f, 0f, 1f, 1f)

        /**
         * Grösstes Rechteck mit dem Seitenverhältnis [ratio] (Breite/Höhe in Bildpunkten), mittig in einem Bild
         * mit dem Seitenverhältnis [frameRatio].
         */
        fun centered(ratio: Float, frameRatio: Float): CropRect {
            // Breite und Höhe in Bruchteilen: (w * frameRatio) / h = ratio
            var w = 1f
            var h = frameRatio / ratio
            if (h > 1f) {
                w = ratio / frameRatio
                h = 1f
            }
            return CropRect((1 - w) / 2, (1 - h) / 2, (1 + w) / 2, (1 + h) / 2)
        }
    }
}

/** Formatvorlagen fürs Zuschneiden des ganzen Videos. [ratio] = Breite/Höhe, null = frei. */
enum class CropAspect(val ratio: Float?) {
    ORIGINAL(null), FREE(null), PORTRAIT_9_16(9f / 16f), SQUARE(1f), LANDSCAPE_16_9(16f / 9f);

    val label get() = when (this) {
        PORTRAIT_9_16 -> "9:16"
        SQUARE -> "1:1"
        LANDSCAPE_16_9 -> "16:9"
        else -> ""
    }
}

/**
 * Effekte eines Abschnitts (Baustein): [rotation] im Uhrzeigersinn (0, 90, 180, 270), das gedrehte Bild wird ins
 * Ergebnisbild eingepasst; [zoom] = gezeigter Ausschnitt des Ergebnisbildes (gleiches Seitenverhältnis), die
 * Ausgabegrösse bleibt.
 */
data class SectionFx(val rotation: Int = 0, val zoom: CropRect? = null) {
    val isNone get() = rotation == 0 && zoom == null

    companion object {
        val NONE = SectionFx()
    }
}

/**
 * Effekte fürs ganze Video: [rotation] im Uhrzeigersinn, danach [crop] (Bruchteile des gedrehten Bildes) mit der
 * Vorlage [aspect]. Ändert die Grösse des Ergebnisses.
 */
data class WholeFx(val rotation: Int = 0, val crop: CropRect? = null, val aspect: CropAspect = CropAspect.ORIGINAL) {
    val isNone get() = rotation == 0 && crop == null

    /** Grösse des gedrehten Bildes bei Ausgangsgrösse [w]×[h]. */
    fun rotatedSize(w: Int, h: Int): Pair<Int, Int> = if (rotation % 180 == 0) w to h else h to w

    /** Grösse des Ergebnisses (gerade Zahlen, wie Encoder sie brauchen). */
    fun outputSize(w: Int, h: Int): Pair<Int, Int> {
        val (rw, rh) = rotatedSize(w, h)
        val c = crop ?: return even(rw.toFloat()) to even(rh.toFloat())
        return even(rw * c.width) to even(rh * c.height)
    }

    private fun even(x: Float) = ((x / 2).roundToInt() * 2).coerceAtLeast(2)
}

/** Effekt eines Abschnitts auf den Original-Bildern [start, end) (Gesamt-Nummern über alle Clips). */
data class FxSpan(val start: Int, val end: Int, val fx: SectionFx)

/**
 * Für den Export: Effekte fürs ganze Video und je Abschnitt (gleiche Reihenfolge wie die Abschnitte).
 * Nur Drehen des ganzen Videos geht ohne Neukodieren.
 */
class EffectPlan(val whole: WholeFx, val sections: List<SectionFx>) {
    val needsReencode get() = whole.crop != null || sections.any { !it.isNone }
}

/** Im Uhrzeigersinn gedreht, auf 0/90/180/270 gebracht. */
fun turn(rotation: Int, by: Int) = ((rotation + by) % 360 + 360) % 360
