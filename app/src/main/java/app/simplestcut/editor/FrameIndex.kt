package app.simplestcut.editor

import app.simplestcut.mp4.Mp4Source
import app.simplestcut.mp4.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToLong

/**
 * Alle Bilder eines oder mehrerer hintereinander gelegter Videos (Clips) mit Zeitstempel (Mikrosekunden,
 * aufsteigend, über alle Clips durchgehend) und Keyframe-Kennung.
 * Grundlage für bildgenaue Marker: Marker liegen immer genau auf einem Bild.
 */
class FrameIndex(
    val timesUs: LongArray,
    val isKey: BooleanArray,
    /** Ende des letzten Bildes (= Gesamtlänge). */
    val endUs: Long,
    /** Erstes Bild jedes Clips (beginnt mit 0). */
    val clipStart: IntArray = intArrayOf(0),
    /** Beginn jedes Clips im Gesamtablauf. */
    val clipOffsetUs: LongArray = longArrayOf(0),
    /** Erste Bilder zusammenhängender Stücke: Clip-Anfänge und alte Schnitte innerhalb der Quellen. */
    val segmentStart: IntArray = intArrayOf(0),
) {
    val size get() = timesUs.size
    val clipCount get() = clipStart.size

    /** Start des Bildes i; für i == size das Videoende. */
    fun boundaryUs(i: Int): Long = if (i >= size) endUs else timesUs[i]

    /** Index des Bildes, das zum Zeitpunkt us zu sehen ist. */
    fun frameAt(us: Long): Int {
        var lo = 0
        var hi = size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (timesUs[mid] <= us) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Index der Bildgrenze (0..size), die us am nächsten liegt. */
    fun nearestBoundary(us: Long): Int {
        val i = frameAt(us)
        val before = boundaryUs(i)
        val after = boundaryUs(i + 1)
        return if (us - before <= after - us) i else i + 1
    }

    /** Clip, zu dem Bild [frame] gehört. */
    fun clipOf(frame: Int): Int = clipStart.indexOfLast { it <= frame }.coerceAtLeast(0)

    /** Zeit von Bild [frame] innerhalb seines Clips. */
    fun localUs(frame: Int): Long = timesUs[frame] - clipOffsetUs[clipOf(frame)]

    /** Bildbereiche (Gesamt-Nummern) an Clip-Grenzen aufteilen, in Abschnitte je Quelle. */
    fun segments(ranges: List<IntRange>): List<Segment> = ranges.flatMap { r ->
        (clipOf(r.first)..clipOf(r.last)).map { c ->
            val end = if (c + 1 < clipCount) clipStart[c + 1] else size
            val from = maxOf(r.first, clipStart[c])
            val to = minOf(r.last, end - 1)
            Segment(c, (from - clipStart[c])..(to - clipStart[c]))
        }
    }

    /** Typischer Bildabstand (für Zoomgrenzen). */
    val frameDurationUs: Long by lazy {
        if (size < 2) 33_333 else ((timesUs[size - 1] - timesUs[0]) / (size - 1)).coerceAtLeast(1)
    }
}

suspend fun loadFrameIndex(file: File): FrameIndex =
    loadFrameIndex(listOf(withContext(Dispatchers.IO) { Mp4Source.read(file) }))

/**
 * Bild-Index aus den MP4-Köpfen, Clips hintereinander; dieselben Bilder und Zeiten verwendet der Export
 * (siehe Mp4Cutter).
 */
suspend fun loadFrameIndex(sources: List<Mp4Source>): FrameIndex = withContext(Dispatchers.Default) {
    val times = ArrayList<Long>()
    val keys = ArrayList<Boolean>()
    val clipStart = IntArray(sources.size)
    val clipOffset = LongArray(sources.size)
    val segmentStart = ArrayList<Int>()
    var offset = 0L
    for ((c, src) in sources.withIndex()) {
        val v = src.video
        val toUs = 1_000_000.0 / v.timescale
        clipStart[c] = times.size
        clipOffset[c] = offset
        var lastEdit = -1
        for (f in src.videoFrames) {
            if (f.edit != lastEdit) {
                segmentStart += times.size
                lastEdit = f.edit
            }
            times += offset + (f.time * toUs).roundToLong()
            keys += v.sync[f.sample]
        }
        val frameUs = (v.typicalDelta * toUs).roundToLong().coerceAtLeast(1)
        offset = times.last() + frameUs
    }
    FrameIndex(times.toLongArray(), keys.toBooleanArray(), offset, clipStart, clipOffset, segmentStart.toIntArray())
}
