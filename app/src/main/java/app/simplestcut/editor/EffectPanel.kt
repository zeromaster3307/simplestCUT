package app.simplestcut.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.simplestcut.R
import app.simplestcut.effects.CropAspect
import app.simplestcut.effects.CropRect
import app.simplestcut.effects.turn

/**
 * Bedienleiste beim Bearbeiten eines Effekts (Zurücksetzen beendet die Bearbeitung wie Fertig) (statt der Schnitt-Knöpfe): oben die Wahl "Dieser Baustein" /
 * "Ganzes Video", darunter Drehen bzw. Formatvorlagen fürs Zuschneiden. Der Baustein ist der unter der
 * Abspielposition. Zuschneiden des ganzen Videos und Zoomen eines Bausteins geschehen mit dem Rahmen im Bild.
 */
@Composable
fun EffectPanel(
    cut: CutState,
    /** Baustein unter der Abspielposition. */
    block: Int?,
    editing: FxEditing,
    wholeScope: Boolean,
    onScope: (Boolean) -> Unit,
    /** Seitenverhältnis des ganzen Videos nach dem Drehen (für die Formatvorlagen). */
    rotatedRatio: Float,
    onDone: () -> Unit,
) {
    val wholeOnly = block == null || cut.blockCount <= 1
    val whole = wholeScope || wholeOnly
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (!wholeOnly) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !whole, onClick = { onScope(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                    Text(stringResource(R.string.fx_this_piece))
                }
                SegmentedButton(selected = whole, onClick = { onScope(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                    Text(stringResource(R.string.fx_whole_video))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        when (editing) {
            FxEditing.ROTATE -> {
                fun rotate(by: Int) {
                    if (whole) cut.whole = cut.whole.let { w ->
                        // Ein Ausschnitt passt nach dem Drehen nicht mehr: zurück aufs ganze Bild.
                        w.copy(rotation = turn(w.rotation, by), crop = null, aspect = CropAspect.ORIGINAL)
                    }
                    else cut.blockFx(block!!).let { fx -> cut.setBlockFx(block, fx.copy(rotation = turn(fx.rotation, by), zoom = null)) }
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedIconButton(onClick = { rotate(270) }) {
                        Icon(RotateLeftIcon, contentDescription = stringResource(R.string.fx_rotate_left))
                    }
                    Spacer(Modifier.width(32.dp))
                    OutlinedIconButton(onClick = { rotate(90) }) {
                        Icon(RotateRightIcon, contentDescription = stringResource(R.string.fx_rotate_right))
                    }
                }
            }
            FxEditing.CROP_WHOLE, FxEditing.ZOOM_SECTION -> {
                if (whole) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (aspect in CropAspect.entries) {
                            val label = when (aspect) {
                                CropAspect.ORIGINAL -> stringResource(R.string.fx_aspect_original)
                                CropAspect.FREE -> stringResource(R.string.fx_aspect_free)
                                else -> aspect.label
                            }
                            FilterChip(
                                selected = cut.whole.aspect == aspect,
                                onClick = {
                                    cut.whole = cut.whole.copy(
                                        aspect = aspect,
                                        crop = when (aspect) {
                                            CropAspect.ORIGINAL -> null
                                            CropAspect.FREE -> cut.whole.crop ?: CropRect(0.1f, 0.1f, 0.9f, 0.9f)
                                            else -> CropRect.centered(aspect.ratio!!, rotatedRatio)
                                        },
                                    )
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                    if (cut.whole.crop != null) Hint(stringResource(R.string.fx_crop_hint))
                } else {
                    Hint(stringResource(R.string.fx_zoom_hint))
                }
            }
            FxEditing.NONE -> {}
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = {
                when {
                    editing == FxEditing.ROTATE && whole -> cut.whole = cut.whole.copy(rotation = 0, crop = null, aspect = CropAspect.ORIGINAL)
                    editing == FxEditing.ROTATE -> cut.setBlockFx(block!!, cut.blockFx(block).copy(rotation = 0, zoom = null))
                    whole -> cut.whole = cut.whole.copy(crop = null, aspect = CropAspect.ORIGINAL)
                    else -> cut.setBlockFx(block!!, cut.blockFx(block).copy(zoom = null))
                }
                onDone()
            }) { Text(stringResource(R.string.fx_reset)) }
            TextButton(onClick = onDone) { Text(stringResource(R.string.fx_done)) }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text, Modifier.padding(vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Material-Symbol "rotate_left". */
val RotateLeftIcon: ImageVector by lazy {
    ImageVector.Builder("RotateLeft", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(
            "M7.11,8.53L5.7,7.11C4.8,8.27 4.24,9.61 4.07,11h2.02c0.14,-0.87 0.49,-1.72 1.02,-2.47zM6.09,13H4.07" +
                "c0.17,1.39 0.72,2.73 1.62,3.89l1.41,-1.42c-0.52,-0.75 -0.87,-1.59 -1.01,-2.47zM7.1,18.32c1.16,0.9 2.51,1.44 3.9,1.61" +
                "V17.9c-0.87,-0.15 -1.71,-0.49 -2.46,-1.03L7.1,18.32zM13,4.07V1L8.45,5.55 13,10V6.09c2.84,0.48 5,2.94 5,5.91" +
                "s-2.16,5.43 -5,5.91v2.02c3.95,-0.49 7,-3.85 7,-7.93s-3.05,-7.44 -7,-7.93z"
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

/** Material-Symbol "rotate_right". */
val RotateRightIcon: ImageVector by lazy {
    ImageVector.Builder("RotateRight", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(
            "M15.55,5.55L11,1v3.07C7.06,4.56 4,7.92 4,12s3.05,7.44 7,7.93v-2.02c-2.84,-0.48 -5,-2.94 -5,-5.91" +
                "s2.16,-5.43 5,-5.91V10l4.55,-4.45zM19.93,11c-0.17,-1.39 -0.72,-2.73 -1.62,-3.89l-1.42,1.42c0.54,0.75 0.88,1.6 1.02,2.47" +
                "h2.02zM13,17.9v2.02c1.39,-0.17 2.74,-0.71 3.9,-1.61l-1.44,-1.44c-0.75,0.54 -1.59,0.89 -2.46,1.03zM16.89,15.48" +
                "l1.42,1.41c0.9,-1.16 1.45,-2.5 1.62,-3.89h-2.02c-0.14,0.87 -0.48,1.72 -1.02,2.48z"
        ),
        fill = SolidColor(Color.Black),
    ).build()
}
