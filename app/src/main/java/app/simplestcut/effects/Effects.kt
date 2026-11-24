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
data class SectionFx(
    val rotation: Int = 0,
    val zoom: CropRect? = null,
    val texts: List<TextFx> = emptyList(),
    val censors: List<CensorFx> = emptyList(),
) {
    val isNone get() = rotation == 0 && zoom == null && texts.isEmpty() && censors.isEmpty()

    /** Ohne Text und Zensur (nur die Bildlage). */
    val geometryOnly get() = SectionFx(rotation, zoom)

    companion object {
        val NONE = SectionFx()
    }
}

/**
 * Effekte fürs ganze Video: [rotation] im Uhrzeigersinn, danach [crop] (Bruchteile des gedrehten Bildes) mit der
 * Vorlage [aspect]. Ändert die Grösse des Ergebnisses.
 */
data class WholeFx(
    val rotation: Int = 0,
    val crop: CropRect? = null,
    val aspect: CropAspect = CropAspect.ORIGINAL,
    val texts: List<TextFx> = emptyList(),
    val censors: List<CensorFx> = emptyList(),
) {
    val isNone get() = rotation == 0 && crop == null && texts.isEmpty() && censors.isEmpty()

    /** Was nur mit Neukodieren geht (alles ausser Drehen). */
    val needsReencode get() = crop != null || texts.isNotEmpty() || censors.isNotEmpty()

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
class EffectPlan(
    val whole: WholeFx,
    val sections: List<SectionFx>,
    /** Zeitbezug je Abschnitt (für Ein-/Ausblenden und mitbewegte Zensur); null = alles fest. */
    val timings: List<SectionTiming>? = null,
) {
    val needsReencode get() = whole.needsReencode || sections.any { !it.isNone }
}

/**
 * Wo ein Abschnitt zeitlich liegt, alles relativ zu seinem ersten Bild (Zeit 0): [runStartUs]/[runEndUs] = Anfang
 * und Ende des zusammenhängenden Stücks mit denselben Effekten (dort blendet Text eines Bausteins ein und aus),
 * [videoStartUs]/[videoEndUs] = Anfang und Ende des ganzen Ergebnisses (für Text des ganzen Videos). [keyTimeUs]
 * liefert die Zeit eines Original-Bildes (Schlüsselbild der Zensur) relativ zum ersten Bild des Abschnitts.
 */
class SectionTiming(
    val runStartUs: Long,
    val runEndUs: Long,
    val videoStartUs: Long,
    val videoEndUs: Long,
    val keyTimeUs: (frame: Int) -> Long,
)

/** Im Uhrzeigersinn gedreht, auf 0/90/180/270 gebracht. */
fun turn(rotation: Int, by: Int) = ((rotation + by) % 360 + 360) % 360

/** Schriften für Text (Android-Systemschriften). */
enum class TextFont(val family: String, val bold: Boolean) {
    SANS("sans-serif", true), SERIF("serif", true), MONO("monospace", true), CONDENSED("sans-serif-condensed", true),
    HANDWRITING("cursive", true)
}

/** Darstellung von Text: Schatten, Kontur oder halbdurchsichtiger Kasten dahinter. */
enum class TextStyle { SHADOW, OUTLINE, BOX }

/**
 * Text im Bild: Mitte bei ([x], [y]) in Bruchteilen des Ergebnisbildes (y nach unten), Schriftgrösse [size] als
 * Bruchteil der kürzeren Bildseite, Farbe [color] (ARGB). [fade] = am Anfang und Ende ein- und ausblenden.
 */
data class TextFx(
    val text: String,
    val x: Float = 0.5f,
    val y: Float = 0.82f,
    val size: Float = 0.06f,
    val color: Int = 0xFFFFFFFF.toInt(),
    val font: TextFont = TextFont.SANS,
    val style: TextStyle = TextStyle.SHADOW,
    val fade: Boolean = false,
)

/** Art der Zensur. */
enum class CensorMode { BLACK, PIXELATE, BLUR }

/** Schlüsselbild der Zensur: auf Original-Bild [frame] liegt der Bereich bei [rect] (Bruchteile des Ergebnisbildes). */
data class CensorKey(val frame: Int, val rect: CropRect)

/**
 * Zensur eines Bereichs: [keys] aufsteigend nach Bild, mindestens eines. Mit einem Schlüsselbild steht der
 * Bereich fest, mit mehreren gleitet er dazwischen (davor/danach wie das erste/letzte). [oval] = Ellipse statt
 * Rechteck. [strength] 0..1 = Grösse der Pixel bzw. Stärke des Weichzeichnens. [track] = mitbewegen: jedes
 * Verschieben setzt ein Schlüsselbild an der Abspielposition.
 */
data class CensorFx(
    val keys: List<CensorKey>,
    val mode: CensorMode = CensorMode.PIXELATE,
    val oval: Boolean = false,
    val strength: Float = 0.5f,
    val track: Boolean = false,
) {
    val moving get() = keys.size > 1

    /** Bereich zur Zeit [t]; [timeOf] liefert die Zeit eines Schlüsselbildes (gleicher Bezug wie [t]). */
    fun rectAt(t: Long, timeOf: (Int) -> Long): CropRect {
        if (keys.size == 1) return keys[0].rect
        val times = keys.map { timeOf(it.frame) }
        if (t <= times.first()) return keys.first().rect
        if (t >= times.last()) return keys.last().rect
        val i = times.indexOfLast { it <= t }
        val a = keys[i].rect
        val b = keys[i + 1].rect
        val span = (times[i + 1] - times[i]).coerceAtLeast(1)
        val k = ((t - times[i]).toDouble() / span).toFloat()
        fun mix(p: Float, q: Float) = p + (q - p) * k
        return CropRect(mix(a.left, b.left), mix(a.top, b.top), mix(a.right, b.right), mix(a.bottom, b.bottom))
    }

    /** Bereich [rect] auf Bild [frame] setzen: mitbewegt als (neues) Schlüsselbild, sonst fest. */
    fun withRect(frame: Int, rect: CropRect): CensorFx =
        if (!track) copy(keys = listOf(CensorKey(frame, rect)))
        else copy(keys = (keys.filter { it.frame != frame } + CensorKey(frame, rect)).sortedBy { it.frame })

    /** Pixelgrösse beim Verpixeln, Bruchteil der kürzeren Bildseite. */
    val cellSize get() = 0.012f + strength * 0.07f

    /** Weichzeichnen: Sigma, Bruchteil der kürzeren Bildseite. */
    val blurSigma get() = 0.006f + strength * 0.03f
}

/** So lange dauert Ein- bzw. Ausblenden von Text (µs). */
const val TEXT_FADE_US = 400_000L

/** Deckkraft eines ein-/ausblendenden Textes zur Zeit [t] in einem Stück [start, end). */
fun fadeAlpha(t: Long, start: Long, end: Long): Float {
    val fade = minOf(TEXT_FADE_US, (end - start) / 3).coerceAtLeast(1)
    val a = minOf((t - start).toFloat() / fade, (end - t).toFloat() / fade)
    return a.coerceIn(0f, 1f)
}
