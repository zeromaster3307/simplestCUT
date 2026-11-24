package app.simplestcut.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.simplestcut.R
import app.simplestcut.effects.CensorFx
import app.simplestcut.effects.CensorKey
import app.simplestcut.effects.CensorMode
import app.simplestcut.effects.TextFont
import app.simplestcut.effects.TextFx
import app.simplestcut.effects.TextStyle
import app.simplestcut.gallery.PauseIcon

/** Wahl "Dieser Baustein" / "Ganzes Video" (nur bei mehreren Bausteinen). */
@Composable
fun ScopeToggle(whole: Boolean, onScope: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(selected = !whole, onClick = { onScope(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
            Text(stringResource(R.string.fx_this_piece))
        }
        SegmentedButton(selected = whole, onClick = { onScope(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
            Text(stringResource(R.string.fx_whole_video))
        }
    }
}

/** Gemeinsamer Rahmen der Leisten: höchstens halbe Bildschirmhöhe, darin rollbar; unten Zurücksetzen / Fertig. */
@Composable
private fun PanelFrame(
    showScope: Boolean,
    whole: Boolean,
    onScope: (Boolean) -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
    content: @Composable () -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
            if (showScope) {
                ScopeToggle(whole, onScope)
                Spacer(Modifier.padding(top = 8.dp))
            }
            content()
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onReset) { Text(stringResource(R.string.fx_reset)) }
            TextButton(onClick = onDone) { Text(stringResource(R.string.fx_done)) }
        }
    }
}

private val textColors = listOf(
    0xFFFFFFFF, 0xFFFFEB3B, 0xFF000000, 0xFFF44336, 0xFF2196F3, 0xFF4CAF50, 0xFFFF9800, 0xFFE91E63,
).map { it.toInt() }

/**
 * Text: hinzufügen, gewählten Text bearbeiten (Inhalt, Farbe, Darstellung, Schrift, Grösse, Ein-/Ausblenden).
 * Verschieben und Grösse auch direkt im Bild.
 */
@Composable
fun TextPanel(
    cut: CutState,
    block: Int?,
    wholeScope: Boolean,
    onScope: (Boolean) -> Unit,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    onDone: () -> Unit,
) {
    val wholeOnly = block == null || cut.blockCount <= 1
    val whole = wholeScope || wholeOnly
    val texts = cut.texts(block, whole)
    val sel = selected?.let { texts.getOrNull(it) }
    fun update(t: TextFx) {
        val i = selected ?: return
        cut.setTexts(block, whole, texts.toMutableList().also { it[i] = t })
    }
    var focusNew by remember { mutableStateOf(false) }
    PanelFrame(
        showScope = !wholeOnly, whole = whole,
        onScope = { onSelect(null); onScope(it) },
        onReset = { cut.setTexts(block, whole, emptyList()); onSelect(null); onDone() },
        onDone = onDone,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (sel != null) {
                val focus = remember { FocusRequester() }
                OutlinedTextField(
                    value = sel.text,
                    onValueChange = { update(sel.copy(text = it)) },
                    placeholder = { Text(stringResource(R.string.fx_text_hint)) },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    maxLines = 3,
                )
                LaunchedEffect(selected, focusNew) {
                    if (focusNew) {
                        focus.requestFocus()
                        focusNew = false
                    }
                }
                IconButton(onClick = {
                    cut.setTexts(block, whole, texts.filterIndexed { i, _ -> i != selected })
                    onSelect(null)
                }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.fx_remove)) }
            } else {
                Text(
                    stringResource(if (texts.isEmpty()) R.string.fx_text_none else R.string.fx_text_pick),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                // Neuer Text unten mittig, weitere etwas darüber, damit sie sich nicht verdecken.
                val y = (0.82f - texts.size * 0.1f).coerceAtLeast(0.15f)
                cut.setTexts(block, whole, texts + TextFx("", y = y))
                onSelect(texts.size)
                focusNew = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.fx_text_add))
            }
        }
        if (sel != null) {
            Spacer(Modifier.padding(top = 8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (color in textColors) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .background(Color(color), CircleShape)
                            .border(
                                if (sel.color == color) 3.dp else 1.dp,
                                if (sel.color == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                CircleShape,
                            )
                            .clickable { update(sel.copy(color = color)) }
                    )
                }
            }
            Spacer(Modifier.padding(top = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val styles = listOf(TextStyle.SHADOW to R.string.fx_text_shadow, TextStyle.OUTLINE to R.string.fx_text_outline, TextStyle.BOX to R.string.fx_text_box)
                styles.forEachIndexed { i, (style, label) ->
                    SegmentedButton(selected = sel.style == style, onClick = { update(sel.copy(style = style)) }, shape = SegmentedButtonDefaults.itemShape(i, styles.size)) {
                        Text(stringResource(label))
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (font in TextFont.entries) {
                    FilterChip(
                        selected = sel.font == font,
                        onClick = { update(sel.copy(font = font)) },
                        label = { Text(fontLabel(font), fontFamily = fontFamily(font), fontWeight = FontWeight.Bold) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.fx_size), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(12.dp))
                Slider(value = sel.size, onValueChange = { update(sel.copy(size = it)) }, valueRange = 0.02f..0.2f, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.fx_text_fade), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = sel.fade, onCheckedChange = { update(sel.copy(fade = it)) })
            }
        }
    }
}

@Composable
private fun fontLabel(font: TextFont) = stringResource(
    when (font) {
        TextFont.SANS -> R.string.fx_font_sans
        TextFont.SERIF -> R.string.fx_font_serif
        TextFont.MONO -> R.string.fx_font_mono
        TextFont.CONDENSED -> R.string.fx_font_condensed
        TextFont.HANDWRITING -> R.string.fx_font_handwriting
    }
)

private fun fontFamily(font: TextFont) = when (font) {
    TextFont.SANS -> FontFamily.SansSerif
    TextFont.SERIF -> FontFamily.Serif
    TextFont.MONO -> FontFamily.Monospace
    TextFont.CONDENSED -> FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD))
    TextFont.HANDWRITING -> FontFamily.Cursive
}

/** Zum Mitbewegen: Abspielposition im Baustein (bzw. ganzen Video) verschieben und abspielen. */
class ScrubControl(
    /** Ergebnis-Bilder, über die der Regler reicht. */
    val range: IntRange,
    /** Ergebnis-Bild der Abspielposition. */
    val position: Int,
    val isPlaying: Boolean,
    val onScrub: (Int) -> Unit,
    val onPlayPause: () -> Unit,
)

/**
 * Zensur: Bereich hinzufügen, gewählten Bereich einstellen (schwarz / verpixeln / weichzeichnen, Rechteck oder
 * Ellipse, Stärke), mitbewegen mit Schlüsselbildern an der Abspielposition [frame] (Original-Bild).
 */
@Composable
fun CensorPanel(
    cut: CutState,
    block: Int?,
    wholeScope: Boolean,
    onScope: (Boolean) -> Unit,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    frame: Int,
    frameRatio: Float,
    scrub: ScrubControl,
    onDone: () -> Unit,
) {
    val wholeOnly = block == null || cut.blockCount <= 1
    val whole = wholeScope || wholeOnly
    val censors = cut.censors(block, whole)
    val sel = selected?.let { censors.getOrNull(it) }
    fun update(c: CensorFx) {
        val i = selected ?: return
        cut.setCensors(block, whole, censors.toMutableList().also { it[i] = c })
    }
    PanelFrame(
        showScope = !wholeOnly, whole = whole,
        onScope = { onSelect(null); onScope(it) },
        onReset = { cut.setCensors(block, whole, emptyList()); onSelect(null); onDone() },
        onDone = onDone,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(
                    when {
                        sel != null -> R.string.fx_censor_edit_hint
                        censors.isEmpty() -> R.string.fx_censor_none
                        else -> R.string.fx_censor_pick
                    }
                ),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sel != null) {
                IconButton(onClick = {
                    cut.setCensors(block, whole, censors.filterIndexed { i, _ -> i != selected })
                    onSelect(null)
                }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.fx_remove)) }
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                cut.setCensors(block, whole, censors + CensorFx(listOf(CensorKey(frame, newCensorRect(frameRatio)))))
                onSelect(censors.size)
            }, enabled = censors.size < 8) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.fx_censor_add))
            }
        }
        if (sel != null) {
            Spacer(Modifier.padding(top = 8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val modes = listOf(CensorMode.PIXELATE to R.string.fx_censor_pixelate, CensorMode.BLUR to R.string.fx_censor_blur, CensorMode.BLACK to R.string.fx_censor_black)
                modes.forEachIndexed { i, (mode, label) ->
                    SegmentedButton(selected = sel.mode == mode, onClick = { update(sel.copy(mode = mode)) }, shape = SegmentedButtonDefaults.itemShape(i, modes.size)) {
                        Text(stringResource(label))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !sel.oval, onClick = { update(sel.copy(oval = false)) }, label = { Text(stringResource(R.string.fx_censor_rect)) })
                FilterChip(selected = sel.oval, onClick = { update(sel.copy(oval = true)) }, label = { Text(stringResource(R.string.fx_censor_oval)) })
            }
            if (sel.mode != CensorMode.BLACK) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.fx_strength), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(12.dp))
                Slider(value = sel.strength, onValueChange = { update(sel.copy(strength = it)) }, modifier = Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.fx_censor_track), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = sel.track, onCheckedChange = { on ->
                    val timeOf = { f: Int -> cut.frames.timesUs[f] }
                    update(
                        if (on) sel.copy(track = true)
                        // Aus: der Bereich bleibt, wo er gerade ist.
                        else sel.copy(track = false, keys = listOf(CensorKey(frame, sel.rectAt(cut.frames.timesUs[frame], timeOf))))
                    )
                })
            }
            if (sel.track) {
                Text(
                    stringResource(R.string.fx_censor_track_hint, sel.keys.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(onClick = scrub.onPlayPause) {
                        if (scrub.isPlaying) Icon(PauseIcon, contentDescription = stringResource(R.string.pause))
                        else Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.play))
                    }
                    Spacer(Modifier.width(8.dp))
                    if (scrub.range.last > scrub.range.first) Slider(
                        value = scrub.position.coerceIn(scrub.range).toFloat(),
                        onValueChange = { scrub.onScrub(it.toInt()) },
                        valueRange = scrub.range.first.toFloat()..scrub.range.last.toFloat(),
                        modifier = Modifier.weight(1f),
                    )
                }
                val keyHere = sel.keys.any { it.frame == frame }
                if (keyHere && sel.keys.size > 1) TextButton(onClick = {
                    update(sel.copy(keys = sel.keys.filter { it.frame != frame }))
                }) { Text(stringResource(R.string.fx_censor_remove_key)) }
            }
        }
    }
}
