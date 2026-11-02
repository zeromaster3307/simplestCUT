package app.simplestcut.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.simplestcut.effects.FxSpan
import app.simplestcut.effects.SectionFx
import app.simplestcut.effects.WholeFx

/** Stärkster Zoom: so viel Zeit passt mindestens auf die Timeline-Breite. */
private const val MIN_VISIBLE_US = 3_000_000.0

/** Ein behaltenes Stück des Originals: Bilder [start, end). */
data class Part(val start: Int, val end: Int)

/**
 * Anordnung der behaltenen Stücke: entfernte Teile sind nicht mehr sichtbar, die übrigen liegen
 * lückenlos hintereinander ("Ergebnis"), in beliebiger Reihenfolge (Bausteine lassen sich verschieben);
 * jedes Bild kommt höchstens einmal vor. Zwischen zwei Stücken liegt eine Naht.
 * Ergebnis-Bilder werden durchgezählt (0..totalFrames), Ergebnis-Zeit in Mikrosekunden.
 */
class Layout(val frames: FrameIndex, val parts: List<Part>) {
    val virtStart = LongArray(parts.size)
    val resultStart = IntArray(parts.size)
    val totalUs: Long
    val totalFrames: Int

    init {
        var t = 0L
        var r = 0
        for ((i, p) in parts.withIndex()) {
            virtStart[i] = t
            resultStart[i] = r
            t += durationUs(p)
            r += p.end - p.start
        }
        totalUs = t
        totalFrames = r
    }

    fun durationUs(p: Part) = frames.boundaryUs(p.end) - frames.boundaryUs(p.start)

    /** Stück an der Ergebnis-Zeit v. */
    fun partAtVirtual(v: Long): Int {
        for (i in parts.indices.reversed()) {
            if (virtStart[i] <= v) return i
        }
        return 0
    }

    /** Echte Zeit im Original für eine Ergebnis-Zeit, gerechnet mit dem Stück [part]. */
    fun realUs(v: Long, part: Int = partAtVirtual(v)): Long = frames.boundaryUs(parts[part].start) + (v - virtStart[part])

    fun frameAtVirtual(v: Long): Int {
        val i = partAtVirtual(v)
        return frames.frameAt(realUs(v, i)).coerceIn(parts[i].start, parts[i].end - 1)
    }

    /** Ergebnis-Zeit eines Bildes, oder null, wenn es entfernt ist. */
    fun virtualOfFrame(frame: Int): Long? {
        val i = parts.indexOfFirst { frame >= it.start && frame < it.end }
        if (i < 0) return null
        return virtStart[i] + frames.timesUs[frame] - frames.boundaryUs(parts[i].start)
    }

    /** Ergebnis-Bildnummer eines Original-Bildes, oder null, wenn es entfernt ist. */
    fun resultOfFrame(frame: Int): Int? {
        val i = parts.indexOfFirst { frame >= it.start && frame < it.end }
        if (i < 0) return null
        return resultStart[i] + frame - parts[i].start
    }

    /** Original-Bild zu einer Ergebnis-Bildnummer (0 until totalFrames). */
    fun frameOfResult(r: Int): Int {
        val i = resultStart.indexOfLast { it <= r }.coerceAtLeast(0)
        return (parts[i].start + r - resultStart[i]).coerceIn(parts[i].start, parts[i].end - 1)
    }

    /** Ergebnis-Zeit einer Ergebnis-Bildgrenze (0..totalFrames). */
    fun virtualOfResult(r: Int): Long = if (r >= totalFrames) totalUs else virtualOfFrame(frameOfResult(r)) ?: 0L

    /** Ergebnis-Bildgrenze, die der Ergebnis-Zeit v am nächsten liegt. */
    fun resultNearVirtual(v: Long): Int {
        val i = partAtVirtual(v.coerceIn(0L, totalUs))
        val p = parts[i]
        val b = frames.nearestBoundary(realUs(v.coerceIn(0L, totalUs), i)).coerceIn(p.start, p.end)
        return resultStart[i] + b - p.start
    }

    /** Die Stücke, die die Ergebnis-Bilder [from, to) zeigen. */
    fun slice(from: Int, to: Int): List<Part> = parts.withIndex().mapNotNull { (i, p) ->
        val rs = resultStart[i]
        val a = maxOf(rs, from)
        val b = minOf(rs + p.end - p.start, to)
        if (a < b) Part(p.start + a - rs, p.start + b - rs) else null
    }
}

/**
 * Schnitt als Bausteine. Ein Baustein reicht immer von einem sichtbaren Balken zum nächsten, auch über
 * Nähte (Stellen, an denen etwas entfernt wurde) hinweg. Balken liegen auf Ergebnis-Bildgrenzen
 * ([markers], aufsteigend); der erste ist der Start-, der letzte der End-Balken. Zieht man diese nach innen,
 * entsteht ein neuer Balken, der Rand-Balken bleibt stehen (davor/dahinter entsteht ein eigener Baustein).
 * Anfangs: Nähte an alten Schnitten der Quellen, Balken an den Grenzen zwischen zusammengefügten Clips.
 */
class CutState(val frames: FrameIndex) {
    private val initialParts = frames.segmentStart.indices.map { i ->
        Part(frames.segmentStart[i], frames.segmentStart.getOrElse(i + 1) { frames.size })
    }
    private val initialMarkers = frames.clipStart.toList() + frames.size

    /** Behaltene Stücke der Quellen; ändert sich nur beim Entfernen oder Verschieben eines Bausteins. */
    var layout by mutableStateOf(Layout(frames, initialParts))
        private set

    /** Während ein Baustein gezogen wird (lange drücken): welcher, wohin (Lücke vor Baustein n) und Fingerposition. */
    var draggedBlock by mutableStateOf<Int?>(null)
    var dropGap by mutableStateOf<Int?>(null)
    var dragX by mutableFloatStateOf(0f)

    val markers = mutableStateListOf(*initialMarkers.toTypedArray())

    /** Gewählter Balken (Index in [markers]); ohne Balken gilt der Baustein unter der Abspielposition als gewählt. */
    var selectedEdge by mutableStateOf<Int?>(null)

    /** Ansicht der Timeline: linker Rand (Ergebnis-Zeit) und Mikrosekunden pro Pixel. */
    var viewStartUs by mutableDoubleStateOf(0.0)
    var usPerPx by mutableDoubleStateOf(0.0)

    /** Von der Timeline gesetzt: linker Rand und nutzbare Breite in Pixeln (0 = noch nicht gemessen). */
    var timelineMarginPx = 0f
    var timelineInnerPx = 0f

    /** Ansicht in gültige Grenzen bringen: höchstens alles, mindestens [MIN_VISIBLE_US] auf der Breite. */
    fun clampView() {
        if (timelineInnerPx <= 0f) return
        val totalUs = layout.totalUs.toDouble()
        val minUsPerPx = minOf(MIN_VISIBLE_US, totalUs) / timelineInnerPx
        usPerPx = usPerPx.coerceIn(minUsPerPx, totalUs / timelineInnerPx)
        viewStartUs = viewStartUs.coerceIn(0.0, maxOf(0.0, totalUs - usPerPx * timelineInnerPx))
    }

    /** Zoomen um die Bildschirm-x-Position [x] (gleiche Koordinaten wie die Timeline). */
    fun zoomAround(zoom: Float, x: Float) {
        if (timelineInnerPx <= 0f || usPerPx == 0.0) return
        val anchor = viewStartUs + (x - timelineMarginPx) * usPerPx
        usPerPx /= zoom
        clampView()
        viewStartUs = anchor - (x - timelineMarginPx) * usPerPx
        clampView()
    }

    /** Effekte fürs ganze Video. */
    var whole by mutableStateOf(WholeFx())

    /**
     * Effekte einzelner Abschnitte, auf Original-Bildern (aufsteigend, ohne Überlappung): so wandern sie beim
     * Verschieben mit dem Baustein mit und bleiben beim Teilen auf beiden Hälften.
     */
    var spans by mutableStateOf(listOf<FxSpan>())
        private set

    val hasEffects get() = !whole.isNone || spans.isNotEmpty()

    /** Effekte, die nur mit Neukodieren gehen (alles ausser Drehen des ganzen Videos): dann kein Speichern. */
    val needsReencode get() = whole.crop != null || spans.any { s -> layout.parts.any { s.start < it.end && it.start < s.end } }

    val hasChanges get() = layout.parts != initialParts || markers.toList() != initialMarkers || hasEffects

    /** Effekt auf Original-Bild [frame]. */
    fun fxOfFrame(frame: Int): SectionFx = spans.firstOrNull { frame >= it.start && frame < it.end }?.fx ?: SectionFx.NONE

    /** Effekt von Baustein [b] (seines ersten Bildes). */
    fun blockFx(b: Int): SectionFx = fxOfFrame(layout.frameOfResult(markers[b]))

    /** Hat Baustein [b] irgendwo einen Effekt? */
    fun blockHasFx(b: Int): Boolean = layout.slice(markers[b], markers[b + 1]).any { p ->
        spans.any { it.start < p.end && p.start < it.end }
    }

    /** Effekt [fx] auf den ganzen Baustein [b] legen (ersetzt, was dort war). */
    fun setBlockFx(b: Int, fx: SectionFx) {
        if (b !in 0 until blockCount) return
        var result = spans
        for (p in layout.slice(markers[b], markers[b + 1])) result = withFx(result, p.start, p.end, fx)
        spans = result
    }

    /** Behaltene Bildbereiche mit ihrem Effekt, in Ergebnis-Reihenfolge (gleicher Effekt zusammengefasst). */
    fun effectRanges(): List<Pair<IntRange, SectionFx>> {
        val result = mutableListOf<Pair<IntRange, SectionFx>>()
        for (p in layout.parts) {
            var from = p.start
            while (from < p.end) {
                val fx = fxOfFrame(from)
                // Bis zur nächsten Grenze eines Effekts
                val to = spans.flatMap { listOf(it.start, it.end) }.filter { it > from && it < p.end }.minOrNull() ?: p.end
                val last = result.lastOrNull()
                if (last != null && last.second == fx && last.first.last + 1 == from) result[result.lastIndex] = (last.first.first until to) to fx
                else result += (from until to) to fx
                from = to
            }
        }
        return result
    }

    val blockCount get() = markers.size - 1

    /** Teilung zwischen zwei Bausteinen (kann gelöscht werden). */
    fun isInnerSplit(edge: Int) = edge in 1 until markers.lastIndex

    /** Baustein, der Bild [frame] enthält (auf einem Balken der danach). */
    fun blockOfFrame(frame: Int): Int? {
        val r = layout.resultOfFrame(frame) ?: return null
        return markers.indexOfLast { it <= r }.coerceIn(0, markers.size - 2)
    }

    /** Bild, das beim Balken gezeigt wird: erstes Bild danach, am Ende das letzte davor. */
    fun edgeFrame(edge: Int): Int = layout.frameOfResult(markers[edge].coerceAtMost(layout.totalFrames - 1))

    fun split(frame: Int): Boolean {
        val r = layout.resultOfFrame(frame) ?: return false
        if (r in markers) return false
        val i = markers.indexOfFirst { it > r }
        markers.add(i, r)
        selectedEdge = i
        return true
    }

    /** Baustein [b] entfernen. Die Balken an beiden Seiten verschwinden, übrig bleibt eine Naht. */
    fun deleteBlock(b: Int) {
        if (blockCount <= 1 || b !in 0 until blockCount) return
        val from = markers[b]
        val to = markers[b + 1]
        // Verschmelzen die Nachbarn, haben aber verschiedene Effekte: Grenze bleibt als Balken stehen.
        val keepSeam = from > 0 && to < layout.totalFrames &&
            fxOfFrame(layout.frameOfResult(from - 1)) != fxOfFrame(layout.frameOfResult(to))
        layout = Layout(frames, joined(layout.slice(0, from) + layout.slice(to, layout.totalFrames)))
        val rest = markers.filterIndexed { j, _ -> j != b && j != b + 1 }
            .map { if (it > from) it - (to - from) else it }.toMutableList()
        if (keepSeam && from !in rest) rest.add(rest.indexOfFirst { it > from }.let { if (it < 0) rest.size else it }, from)
        if (rest.firstOrNull() != 0) rest.add(0, 0)
        if (rest.last() != layout.totalFrames) rest.add(layout.totalFrames)
        markers.clear()
        markers.addAll(rest)
        selectedEdge = null
    }

    /**
     * Baustein [b] in die Lücke [gap] verschieben (vor den Baustein, der jetzt Nummer [gap] hat; blockCount = ans
     * Ende). Liefert die neue Nummer des Bausteins.
     */
    fun moveBlock(b: Int, gap: Int): Int {
        if (b !in 0 until blockCount || gap !in 0..blockCount || gap == b || gap == b + 1) return b
        val blocks = (0 until blockCount).map { layout.slice(markers[it], markers[it + 1]) }.toMutableList()
        val moving = blocks.removeAt(b)
        val target = if (gap > b) gap - 1 else gap
        blocks.add(target, moving)
        layout = Layout(frames, joined(blocks.flatten()))
        val newMarkers = mutableListOf(0)
        for (block in blocks) newMarkers += newMarkers.last() + block.sumOf { it.end - it.start }
        markers.clear()
        markers.addAll(newMarkers)
        selectedEdge = null
        return target
    }

    /** Aneinanderstoßende Stücke wieder zusammenfassen (nicht über Clip-Grenzen oder alte Schnitte der Quellen). */
    private fun joined(parts: List<Part>): List<Part> {
        val result = mutableListOf<Part>()
        for (p in parts) {
            val last = result.lastOrNull()
            if (last != null && last.end == p.start && p.start !in frames.segmentStart) result[result.lastIndex] = Part(last.start, p.end)
            else result += p
        }
        return result
    }

    /** Teilung entfernen, die beiden Bausteine verschmelzen; nicht bei verschiedenen Effekten (liefert dann false). */
    fun mergeAt(edge: Int): Boolean {
        if (!isInnerSplit(edge)) return false
        if (blockFx(edge - 1) != blockFx(edge)) return false
        markers.removeAt(edge)
        selectedEdge = null
        return true
    }

    /**
     * Balken auf Ergebnis-Bildgrenze [to] verschieben (begrenzt durch die Nachbarn). Start-/End-Balken nach
     * innen erzeugen einen neuen Balken. Liefert den Index des bewegten Balkens.
     */
    fun moveEdge(edge: Int, to: Int): Int {
        var i = edge
        if (i == 0) {
            if (to <= 0) return 0
            markers.add(1, 0)
            i = 1
        } else if (i == markers.lastIndex) {
            if (to >= layout.totalFrames) return i
            markers.add(i, layout.totalFrames)
        }
        // Bausteine behalten mindestens ein Bild; der Randbaustein darf beim Ziehen leer werden (verschwindet dann).
        val lower = markers[i - 1] + if (i - 1 == 0) 0 else 1
        val upper = markers[i + 1] - if (i + 1 == markers.lastIndex) 0 else 1
        markers[i] = to.coerceIn(lower, maxOf(lower, upper))
        return i
    }

    /** Doppelte Balken (leer gezogene Bausteine) entfernen; hält die Auswahl auf derselben Stelle. */
    fun normalize() {
        val sel = selectedEdge?.let { markers.getOrNull(it) }
        val distinct = markers.distinct()
        if (distinct.size != markers.size) {
            markers.clear()
            markers.addAll(distinct)
        }
        selectedEdge = sel?.let { distinct.indexOf(it) }
    }

    /** Behaltene Bildbereiche (beide Enden eingeschlossen), zusammenhängende zusammengefasst. */
    fun keptRanges(): List<IntRange> {
        val result = mutableListOf<IntRange>()
        for (p in layout.parts) {
            if (p.end <= p.start) continue
            val last = result.lastOrNull()
            if (last != null && last.last + 1 == p.start) result[result.size - 1] = last.first until p.end
            else result += p.start until p.end
        }
        return result
    }

    /** Nummer des Stücks (in [Layout.parts]), das Bild [frame] enthält. */
    fun pieceOfFrame(frame: Int): Int? = layout.parts.indexOfFirst { frame >= it.start && frame < it.end }.takeIf { it >= 0 }
}

/** [spans] mit Effekt [fx] auf den Original-Bildern [start, end) (NONE entfernt dort jeden Effekt). */
private fun withFx(spans: List<FxSpan>, start: Int, end: Int, fx: SectionFx): List<FxSpan> {
    val result = mutableListOf<FxSpan>()
    for (s in spans) {
        if (s.end <= start || s.start >= end) {
            result += s
            continue
        }
        if (s.start < start) result += s.copy(end = start)
        if (s.end > end) result += s.copy(start = end)
    }
    if (!fx.isNone) result += FxSpan(start, end, fx)
    result.sortBy { it.start }
    // Aneinanderstossende Stücke mit gleichem Effekt zusammenfassen
    val merged = mutableListOf<FxSpan>()
    for (s in result) {
        val last = merged.lastOrNull()
        if (last != null && last.end == s.start && last.fx == s.fx) merged[merged.lastIndex] = last.copy(end = s.end) else merged += s
    }
    return merged
}
