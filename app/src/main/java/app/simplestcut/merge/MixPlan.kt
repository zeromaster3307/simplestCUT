package app.simplestcut.merge

import app.simplestcut.R
import androidx.annotation.StringRes
import app.simplestcut.mp4.Mp4Source
import app.simplestcut.mp4.VideoConfig
import kotlin.math.roundToInt

enum class Orientation(@StringRes val label: Int) { PORTRAIT(R.string.portrait), LANDSCAPE(R.string.landscape) }

/**
 * Zusammenfügen unpassender Clips: alles wird neu kodiert, in ein Bild der Grösse [width]×[height] (so wie es
 * angezeigt wird) eingepasst, mit schwarzen Balken. [keepHdr] = Ergebnis bleibt HDR (nur wenn alle Clips HDR sind),
 * sonst werden HDR-Clips auf normale Farben umgerechnet (Tone-Mapping).
 */
class MixSettings(val width: Int, val height: Int, val keepHdr: Boolean, val bitrate: Int)

/** Was beim Zusammenfügen über einen Clip bekannt sein muss. */
class ClipFormat(
    /** Grösse wie angezeigt (Drehung eingerechnet). */
    val width: Int,
    val height: Int,
    val hdr: Boolean,
    /** Datenrate des Videos in Bit/s. */
    val bitrate: Double,
) {
    val orientation get() = if (height > width) Orientation.PORTRAIT else Orientation.LANDSCAPE
    val shortSide get() = minOf(width, height)
    val longSide get() = maxOf(width, height)

    companion object {
        fun of(src: Mp4Source): ClipFormat {
            val cfg = VideoConfig.of(src)
            val v = src.video
            // Drehmatrix in tkhd: a = 0 bedeutet um 90° bzw. 270° gedreht.
            val tkhd = v.tkhd.body + if (src.moov[v.tkhd.body].toInt() == 1) 52 else 40
            val rotated = src.buf.getInt(tkhd) == 0
            val seconds = (v.dts.last() + v.deltas.last()).toDouble() / v.timescale
            return ClipFormat(
                width = if (rotated) cfg.height else cfg.width,
                height = if (rotated) cfg.width else cfg.height,
                hdr = cfg.transfer == 16 || cfg.transfer == 18,
                bitrate = v.sizes.sumOf { it.toLong() } * 8 / seconds.coerceAtLeast(0.1),
            )
        }
    }
}

/** Eine wählbare Auflösung, z. B. "1080p (1080 × 1920)". */
class Resolution(val width: Int, val height: Int) {
    val label get() = "${minOf(width, height)}p ($width × $height)"
}

class MixPlan(val clips: List<ClipFormat>) {
    val mixedOrientation get() = clips.map { it.orientation }.distinct().size > 1
    val anyHdr get() = clips.any { it.hdr }
    val allHdr get() = clips.all { it.hdr }

    /** Vorgabe: was die Mehrheit ist, bei Gleichstand wie der erste Clip. */
    val defaultOrientation: Orientation
        get() {
            val portrait = clips.count { it.orientation == Orientation.PORTRAIT }
            val landscape = clips.size - portrait
            return when {
                portrait > landscape -> Orientation.PORTRAIT
                landscape > portrait -> Orientation.LANDSCAPE
                else -> clips.first().orientation
            }
        }

    /**
     * Auflösungen für [orientation], grösste zuerst: die des grössten Clips und gängige kleinere Stufen.
     * Seitenverhältnis wie beim grössten Clip dieser Ausrichtung (sonst des grössten überhaupt, gedreht).
     */
    fun resolutions(orientation: Orientation): List<Resolution> {
        val ref = clips.filter { it.orientation == orientation }.maxByOrNull { it.width * it.height }
            ?: clips.maxBy { it.width * it.height }
        val aspect = ref.longSide.toDouble() / ref.shortSide
        val largest = clips.maxOf { it.shortSide }
        val shorts = (listOf(largest) + listOf(2160, 1440, 1080, 720).filter { it < largest }).distinct()
        return shorts.map { short ->
            val long = even(short * aspect)
            if (orientation == Orientation.PORTRAIT) Resolution(even(short.toDouble()), long) else Resolution(long, even(short.toDouble()))
        }
    }

    fun settings(resolution: Resolution): MixSettings {
        val pixels = resolution.width.toDouble() * resolution.height
        // So viel Datenrate, wie der beste Clip pro Bildpunkt hatte.
        val bitrate = clips.maxOf { it.bitrate * pixels / (it.width.toDouble() * it.height) }
        return MixSettings(resolution.width, resolution.height, keepHdr = allHdr, bitrate = bitrate.roundToInt().coerceIn(4_000_000, 120_000_000))
    }

    private fun even(x: Double) = ((x / 2).roundToInt() * 2).coerceAtLeast(2)
}
