package app.simplestcut.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.composed
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Anzeige nach dem Doppeltippen: Seite und bisher gesprungene Sekunden (mehrmals tippen addiert). */
@Stable
class SeekFlash {
    var forward by mutableStateOf(true)
    var seconds by mutableIntStateOf(0)
    var tick by mutableIntStateOf(0)
    /** Hinweis gerade sichtbar: weiteres Tippen zählt dazu, sonst beginnt die Zählung neu. */
    var active = false

    fun add(forward: Boolean, step: Int) {
        if (!active || this.forward != forward) seconds = 0
        active = true
        this.forward = forward
        seconds += step
        tick++
    }
}

/**
 * Doppeltippen links (ab 40 % von links) springt zurück, rechts vor; die Mitte bleibt frei.
 * Der erste Tipp geht normal an das Video darunter (Steuerung ein/aus), der zweite wird abgefangen.
 * [blocked] = an dieser Stelle (y, Höhe) nicht, z. B. über einer Bedienleiste.
 */
fun Modifier.doubleTapSeek(
    enabled: Boolean,
    blocked: (y: Float, height: Float) -> Boolean = { _, _ -> false },
    onSeek: (forward: Boolean) -> Unit,
): Modifier = if (!enabled) this else composed {
    // Aktuelle Rückrufe lesen, ohne die Geste bei jedem Neuzeichnen neu zu starten.
    val currentBlocked by rememberUpdatedState(blocked)
    val currentOnSeek by rememberUpdatedState(onSeek)
    pointerInput(Unit) { detectDoubleTapSeek({ y, h -> currentBlocked(y, h) }, { currentOnSeek(it) }) }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectDoubleTapSeek(
    blocked: (y: Float, height: Float) -> Boolean,
    onSeek: (forward: Boolean) -> Unit,
) {
    fun sideOf(x: Float): Boolean? = when {
        x < size.width * 0.4f -> false
        x > size.width * 0.6f -> true
        else -> null
    }
    // Ein Tipp: nicht weit bewegt, kein zweiter Finger, nicht lange gehalten.
    suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.isTap(down: PointerInputChange): Boolean {
        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var result: PointerInputChange? = null
            while (result == null) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size > 1) break
                val c = event.changes.first()
                if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                if (!c.pressed) result = c
            }
            result
        }
        return up != null
    }
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val side = sideOf(first.position.x) ?: return@awaitEachGesture
        if (blocked(first.position.y, size.height.toFloat())) return@awaitEachGesture
        if (!isTap(first)) return@awaitEachGesture
        val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        } ?: return@awaitEachGesture
        if (sideOf(second.position.x) != side) return@awaitEachGesture
        // Zweiten Tipp abfangen: das Video darunter bekommt ihn nicht (sonst Steuerung gleich wieder aus).
        second.consume()
        onSeek(side)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/** Spul-Symbol mit Sekunden auf der Seite, wohin gesprungen wurde; verschwindet nach kurzer Zeit. */
@Composable
fun BoxScope.SeekFlashOverlay(flash: SeekFlash) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(flash.tick) {
        if (flash.tick == 0) return@LaunchedEffect
        visible = true
        delay(700)
        // Zahl bleibt stehen, solange das Symbol ausblendet; zurückgesetzt wird erst beim nächsten Mal.
        visible = false
        flash.active = false
    }
    AnimatedVisibility(
        visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(if (flash.forward) Alignment.CenterEnd else Alignment.CenterStart)
            .fillMaxHeight()
            .fillMaxWidth(0.35f),
    ) {
        // Kein Hintergrund: das Bild wird nie abgedunkelt. Symbol wie die Spul-Knöpfe der Media3-Steuerung
        // (Pfeilkreis 52 dp, Zahl 9 dp fett mittig im Kreis), hier mit den gesprungenen Sekunden.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(
                        if (flash.forward) androidx.media3.ui.R.drawable.exo_styled_controls_fastforward
                        else androidx.media3.ui.R.drawable.exo_styled_controls_rewind
                    ),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    flash.seconds.toString(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = with(LocalDensity.current) { 9.dp.toSp() },
                    // Ohne Schriftränder, damit die Ziffern wirklich mittig stehen.
                    style = TextStyle(
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                    ),
                    // Der Kreis im Symbol liegt bei 27,5 von 52 dp (Pfeilspitze oben), also leicht unter der Mitte.
                    modifier = Modifier.offset(y = 1.5.dp),
                )
            }
        }
    }
}
