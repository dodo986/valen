/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.util.lerp
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii

/*
 * A song added to the queue goes somewhere you can see: its cover leaves the row as a drop and
 * arcs into the queue's door — the dock's queue glyph on a wide window, the mini player on a
 * phone — which swallows it as it lands. The drop is drawn above the chrome by the shell.
 */

/** The drops in the air, and the door they fly to. Provided by the shell as [LocalFlights]. */
@Stable
class Flights {
    internal val list = mutableStateListOf<Flight>()
    private var next = 0L

    /** Where the queue's door is, in the window; null while no door is on screen. */
    internal var door by mutableStateOf<Rect?>(null)

    /** Bumped each time a drop lands, for the door to swallow it. */
    var landed by mutableIntStateOf(0)
        private set

    /** Drops still in the air: the door waits for them rather than swallowing at the click. */
    val inAir: Int get() = list.size

    /** Sends [art] from [from] (in the window) to the door; nothing flies when there is none. */
    fun launch(from: Rect, art: String?, reduced: Boolean) {
        val to = door ?: return
        if (reduced || from.isEmpty) return
        list += Flight(next++, from, to, art)
    }

    internal fun land(flight: Flight) {
        list.remove(flight)
        landed++
    }
}

internal class Flight(val id: Long, val from: Rect, val to: Rect, val art: String?) {
    val t = Animatable(0f)
}

val LocalFlights = staticCompositionLocalOf { Flights() }

/** This is the queue's door: queued songs fly here. It stops being one when it leaves the screen. */
@Composable
fun Modifier.flightDoor(flights: Flights): Modifier {
    DisposableEffect(flights) { onDispose { flights.door = null } }
    return onGloballyPositioned { flights.door = it.boundsInRoot() }
}

/**
 * Draws the drops in the air, over everything. It takes no input: what is under it is pressed
 * as if it were not there.
 */
@Composable
fun FlightHost(flights: Flights, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    var host by remember { mutableStateOf(Offset.Zero) }
    val reduced = LocalReducedMotion.current
    Box(modifier.fillMaxSize().onGloballyPositioned { host = it.positionInRoot() }) {
        for (flight in flights.list) {
            key(flight.id) {
                LaunchedEffect(flight) {
                    if (!reduced) flight.t.animateTo(1f, tween(Motion.FlightMs, easing = LinearEasing))
                    flights.land(flight)
                }
                val start = flight.from.width
                val end = with(density) { Motion.FlightEndDp.toPx() }
                val arc = with(density) { Motion.FlightArcDp.toPx() }
                val corner = with(density) { Radii.cover.toPx() }
                val size = with(density) { start.toDp() }
                Box(
                    Modifier
                        .size(size)
                        .graphicsLayer {
                            val raw = flight.t.value
                            val t = Motion.Flight.transform(raw)
                            val a = flight.from.center - host
                            val c = flight.to.center - host
                            // The arc's top: above both ends, nearer the start, like a toss.
                            val b = Offset(lerp(a.x, c.x, 0.4f), minOf(a.y, c.y) - arc)
                            val u = 1f - t
                            val x = u * u * a.x + 2f * u * t * b.x + t * t * c.x
                            val y = u * u * a.y + 2f * u * t * b.y + t * t * c.y
                            // Tossed up bigger toward the top of its arc, then drawn in to a bead.
                            val pick = 1f + (Motion.FlightSwell - 1f) * kotlin.math.sin(3.1416f * (raw / 0.7f).coerceIn(0f, 1f))
                            val shrink = lerp(1f, end / start, raw.coerceIn(0f, 1f).let { it * it * kotlin.math.sqrt(it) })
                            val s = pick * shrink
                            translationX = x - start / 2f
                            translationY = y - start / 2f
                            scaleX = s
                            scaleY = s
                            rotationZ = -10f * kotlin.math.sin(raw * 3.1416f)
                            transformOrigin = TransformOrigin.Center
                            // Rounding into a bead as it goes.
                            clip = true
                            shape = RoundedCornerShape(lerp(corner, start / 2f, raw.coerceIn(0f, 1f)))
                        },
                ) {
                    Artwork(url = flight.art, shape = Radii.coverShape, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}
