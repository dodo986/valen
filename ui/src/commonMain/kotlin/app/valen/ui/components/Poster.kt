/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.util.lerp
import app.valen.core.Track
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Covers that stand alone on the wall (the stage, the Home hero) change like posters on a street,
 * not like slides. The next song's cover is slapped on over the one before: solid within a frame,
 * a little big and turned, slapped flat on the kinetic curve with a small bounce, while the one
 * under it sinks back and darkens and is scraped off once it is covered. Going back lifts the top
 * poster away, and the earlier song is there underneath, where it was left. "Your week" turns its
 * pages the same way.
 */

/**
 * [item]'s poster on top of whatever was up before it. A new item goes up only once its picture
 * has arrived ([Motion.PosterWaitMs] at most): [poster] calls `onReady` when it has. [back] says
 * the change went back (an earlier song of the same queue), which lifts the top poster away
 * instead of slapping a new one on. [PosterEntry] tells [poster] how it went up, so the one up
 * first fades its picture in as usual and one found again under a lifted poster is already taped.
 */
@Composable
fun <T> Posters(
    item: T,
    key: (T) -> Any,
    back: Boolean,
    modifier: Modifier = Modifier,
    /** A new item [same] as the one up (the same cover) keeps the poster up: nothing is torn. */
    same: (T, T) -> Boolean = { _, _ -> false },
    poster: @Composable (item: T, entry: PosterEntry, onReady: () -> Unit) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val wanted = key(item)
    val sheets = remember { mutableStateListOf(Sheet(item, wanted, id = 0, entry = PosterEntry.First)) }
    val counter = remember { IntArray(1) }
    val latestBack by rememberUpdatedState(back)
    val latestItem by rememberUpdatedState(item)

    LaunchedEffect(wanted) {
        val up = sheets.lastOrNull()
        if (up?.key == wanted) return@LaunchedEffect
        val shown = latestItem
        if (up != null && up.gone.value == 0f && same(up.item, shown)) {
            // The same poster: it stays up, now showing this item (re-taped by its caller).
            up.key = wanted
            up.item = shown
            return@LaunchedEffect
        }
        if (reduced) {
            // No paper: the cover simply changes, with the picture's own cross-fade.
            sheets.clear()
            sheets.add(Sheet(shown, wanted, ++counter[0], entry = PosterEntry.First))
            return@LaunchedEffect
        }
        if (!latestBack) {
            val sheet = Sheet(shown, wanted, ++counter[0], entry = PosterEntry.Slapped).apply {
                // Which way it is turned as it lands is the song's own, like its resting angle.
                turn = if (wanted.hashCode() and 1 == 0) -Motion.PosterSlapDegrees else Motion.PosterSlapDegrees * 0.85f
            }
            val torn = sheets.lastOrNull()
            sheets.add(sheet)
            // Rapid skips: three sheets are plenty to look like a wall; older ones are long covered.
            while (sheets.size > MaxSheets) sheets.removeAt(0)
            try {
                withTimeoutOrNull(Motion.PosterWaitMs) { snapshotFlow { sheet.ready }.first { it } }
                coroutineScope {
                    // The tape gives at one corner and the old poster swings down from the other
                    // and drops; the new one is slapped on as it swings.
                    var lead = 0
                    if (torn != null && torn.fall.value == 0f) {
                        torn.hinge = if (torn.key.hashCode() and 2 == 0) Hinge.Left else Hinge.Right
                        launch { torn.fall.animateTo(1f, tween(Motion.TearMs, easing = LinearEasing)) }
                        lead = Motion.TearLeadMs.toInt()
                    }
                    // On the frame clock (a delay in the animation, not a coroutine's): the swing
                    // shows alone for its first frames however busy the device is.
                    sheet.up.animateTo(1f, tween(Motion.SlapMs, delayMillis = lead, easing = LinearEasing))
                }
            } finally {
                withContext(NonCancellable) {
                    // Interrupted by the next song: this one is up, flat, and about to be covered.
                    if (sheet.up.value < 1f) sheet.up.snapTo(1f)
                    sheet.ready = true
                    val at = sheets.indexOf(sheet)
                    if (at > 0 && sheets.last() == sheet) repeat(at) { sheets.removeAt(0) }
                }
            }
        } else {
            val top = sheets.last()
            // The earlier poster is put back under the top one first, so lifting it shows a picture.
            val under = Sheet(shown, wanted, ++counter[0], entry = PosterEntry.Returned).apply { ready = false }
            sheets.add(sheets.size - 1, under)
            try {
                withTimeoutOrNull(Motion.PosterWaitMs) { snapshotFlow { under.ready }.first { it } }
                top.hinge = if (top.key.hashCode() and 2 == 0) Hinge.Left else Hinge.Right
                top.gone.animateTo(1f, tween(Motion.ExitMs + Motion.PressMs / 2, easing = Motion.Exit))
            } finally {
                withContext(NonCancellable) {
                    under.ready = true
                    sheets.remove(top)
                    val at = sheets.indexOf(under)
                    if (at > 0 && sheets.last() == under) repeat(at) { sheets.removeAt(0) }
                }
            }
        }
    }

    Box(modifier) {
        val count = sheets.size
        sheets.forEachIndexed { i, sheet ->
            key(sheet.id) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // How far the sheets above this one have covered it.
                            var covered = 0f
                            var lifted = 0f
                            for (j in i + 1 until count) {
                                val above = sheets.getOrNull(j) ?: continue
                                if (above.ready) covered = maxOf(covered, above.up.value)
                                lifted = maxOf(lifted, above.gone.value)
                            }
                            val p = sheet.up.value
                            val k = Motion.Kinetic.transform(p.coerceIn(0f, 1f))
                            val f = sheet.fall.value
                            val g = sheet.gone.value
                            val side = if (sheet.hinge == Hinge.Left) 1f else -1f
                            // Torn: it hangs from one corner, swings past and back like a
                            // pendulum, then drops off the wall; it no longer sinks under the new one.
                            val sink = if (f > 0f) 0f else Motion.Standard.transform(covered.coerceIn(0f, 1f)) * (1f - lifted)
                            val swing = Motion.Kinetic.transform((f / 0.5f).coerceIn(0f, 1f))
                            val drop = ((f - 0.42f) / 0.58f).coerceIn(0f, 1f).let { it * it }
                            val slap = Motion.PosterSlapScale - (Motion.PosterSlapScale - 1f) * k
                            val s = slap * lerp(1f, Motion.PouredOverScale, sink) * lerp(1f, Motion.LiftScale, g)
                            scaleX = s
                            scaleY = s
                            rotationZ = sheet.turn * (1f - k) +
                                side * (Motion.TearDegrees * swing + Motion.TearDegrees * 0.35f * drop) -
                                side * Motion.PeelDegrees * g
                            translationY = drop * size.height * 0.6f - Motion.PeelRise * size.height * g
                            alpha = when {
                                !sheet.ready -> 0f
                                else -> (p * 20f).coerceIn(0f, 1f) *
                                    lerp(1f, Motion.PouredOverAlpha, sink) *
                                    (1f - ((f - 0.62f) / 0.38f).coerceIn(0f, 1f)) *
                                    (1f - g)
                            }
                            transformOrigin = when {
                                f > 0f -> TransformOrigin(if (side > 0f) 0.06f else 0.94f, 0.04f)
                                g > 0f -> TransformOrigin(if (side > 0f) 0.94f else 0.06f, 1f)
                                else -> TransformOrigin(0.5f, 0.42f)
                            }
                        },
                ) {
                    // The poster on top is the item as it is now (a cover that arrives late
                    // fills in); those under it stay as they were put up.
                    poster(if (sheet.key == wanted) item else sheet.item, sheet.entry) { sheet.ready = true }
                }
            }
        }
    }
}

/** One poster on the wall: how far it is up (slapped on) and how far it is gone (lifted off). */
private class Sheet<T>(item: T, key: Any, val id: Int, val entry: PosterEntry) {
    var item = item
    var key by mutableStateOf(key)
    val up = Animatable(if (entry == PosterEntry.Slapped) 0f else 1f)
    val gone = Animatable(0f)

    /** Torn off: the tape at one corner gave, and it swings from the other and drops. */
    val fall = Animatable(0f)
    var hinge = Hinge.Left
    var ready by mutableStateOf(entry != PosterEntry.Slapped)
    var turn = 0f
}

private const val MaxSheets = 3

/** Which top corner's tape holds while the other gives. */
private enum class Hinge { Left, Right }

/** How a poster went up: the first one there, slapped on over another, or found again under one lifted away. */
enum class PosterEntry { First, Slapped, Returned }

/**
 * Whether the song on show was reached by going back in the same queue: the song shown before is
 * still in the queue, after the one shown now. A new queue (a search's song, a playlist) never is.
 */
@Composable
fun rememberWentBack(key: Any?, index: Int, queue: List<Track>): Boolean {
    val seen = remember { arrayOf(key, index) }
    val back = remember(key) { wentBack(before = seen[0], beforeIndex = seen[1] as Int, key = key, index = index, queue = queue) }
    SideEffect {
        seen[0] = key
        seen[1] = index
    }
    return back
}

/**
 * The rule under [rememberWentBack]: the song shown before ([before], at [beforeIndex]) is still
 * in [queue] at that place, and the song shown now ([key]) is earlier in it.
 */
internal fun wentBack(before: Any?, beforeIndex: Int, key: Any?, index: Int, queue: List<Track>): Boolean =
    before != null && before != key && index in 0 until beforeIndex && queue.getOrNull(beforeIndex)?.key == before
