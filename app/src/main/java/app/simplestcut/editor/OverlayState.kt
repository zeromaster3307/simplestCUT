package app.simplestcut.editor

import app.simplestcut.effects.SectionTiming
import app.simplestcut.effects.fadeAlpha
import app.simplestcut.mp4.Segment

/**
 * Was die Vorschau bei Original-Bild [frame] über das Video legt: Zensur und Text des ganzen Videos, dann die des
 * Abschnitts (gleiche Reihenfolge wie im Export). Beim Bearbeiten ([tool] TEXT/CENSOR) sind die Einträge der
 * gewählten Ebene ([fxWhole] bzw. Baustein [block]) bearbeitbar; Text ist dann voll sichtbar (kein Ein-/Ausblenden).
 */
fun overlayShown(
    cut: CutState,
    frame: Int,
    block: Int?,
    tool: FxEditing,
    fxWhole: Boolean,
    selected: Int?,
    onSelect: (Int?) -> Unit,
): OverlayShown {
    val wholeScope = fxWhole || block == null || cut.blockCount <= 1
    val section = cut.fxOfFrame(frame)
    val layout = cut.layout
    val now = layout.virtualOfFrame(frame) ?: 0L
    val times = cut.frames.timesUs
    val editingText = tool == FxEditing.TEXT
    val run = if (section.texts.any { it.fade } && !editingText) cut.runOfFrame(frame) else null
    val texts = cut.whole.texts.mapIndexed { i, t ->
        val alpha = if (t.fade && !editingText) fadeAlpha(now, 0L, layout.totalUs) else 1f
        ShownText(t, alpha, if (editingText && wholeScope) i else null)
    } + section.texts.mapIndexed { i, t ->
        val alpha = if (t.fade && run != null) fadeAlpha(now, run.startUs, run.endUs) else 1f
        ShownText(t, alpha, if (editingText && !wholeScope) i else null)
    }
    val editingCensor = tool == FxEditing.CENSOR
    val t = times[frame]
    val censors = cut.whole.censors.mapIndexed { i, c ->
        ShownCensor(c, c.rectAt(t) { times[it] }, if (editingCensor && wholeScope) i else null)
    } + section.censors.mapIndexed { i, c ->
        ShownCensor(c, c.rectAt(t) { times[it] }, if (editingCensor && !wholeScope) i else null)
    }
    return OverlayShown(
        texts, censors, tool, selected, onSelect,
        onText = { i, fx ->
            val list = cut.texts(block, wholeScope)
            if (i in list.indices) cut.setTexts(block, wholeScope, list.toMutableList().also { it[i] = fx })
        },
        onCensorRect = { i, rect ->
            val list = cut.censors(block, wholeScope)
            if (i in list.indices) cut.setCensors(block, wholeScope, list.toMutableList().also { it[i] = it[i].withRect(frame, rect) })
        },
    )
}

/**
 * Zeitbezug je Export-Abschnitt (siehe [SectionTiming]): Lage im Stück mit demselben Effekt und im ganzen
 * Ergebnis, Zeiten der Schlüsselbilder; alles relativ zum ersten Bild des Abschnitts.
 */
fun sectionTimings(cut: CutState, segments: List<Segment>): List<SectionTiming> {
    val frames = cut.frames
    val layout = cut.layout
    val runs = cut.effectRuns()
    return segments.map { seg ->
        val first = frames.clipStart[seg.source] + seg.frames.first
        val v0 = layout.virtualOfFrame(first) ?: 0L
        val run = runs.firstOrNull { r -> r.ranges.any { first in it } }
        val t0 = frames.timesUs[first]
        SectionTiming(
            runStartUs = (run?.startUs ?: v0) - v0,
            runEndUs = (run?.endUs ?: layout.totalUs) - v0,
            videoStartUs = -v0,
            videoEndUs = layout.totalUs - v0,
            keyTimeUs = { f -> frames.timesUs[f.coerceIn(0, frames.size - 1)] - t0 },
        )
    }
}
