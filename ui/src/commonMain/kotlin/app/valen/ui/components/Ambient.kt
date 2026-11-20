/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import app.valen.ui.artwork.ArtworkTones
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.ValenColors
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Where a wash's new colour is poured from: the cover that brought it. The cover marks itself
 * with [Modifier.repaintFrom]; the wash reads where that is.
 */
@Stable
class RepaintSpot {
    internal var centre by mutableStateOf<Offset?>(null)
}

/** The spot the screen's wash pours from; the stage and Home provide one around their cover. */
val LocalRepaintSpot = androidx.compose.runtime.staticCompositionLocalOf<RepaintSpot?> { null }

/** This is where the wash's new colour comes from (its centre, in the window). */
fun Modifier.repaintFrom(spot: RepaintSpot): Modifier = onGloballyPositioned { spot.centre = it.boundsInRoot().center }

/**
 * The artwork's colour as light in the room: three soft pools over a dark floor, one leaning the
 * way the left of the cover does, one the right, one its accent. It is the only decoration in the
 * app, and there is at most one on a screen.
 *
 * [fade] is how far the wash dissolves into the black page by its bottom edge: 1 for a band that
 * content continues under, less for a stage the wash fills. [strength] thins the pools, for a
 * wash with no artwork behind it, where grey at full strength would read as fog.
 *
 * With [drift] the pools wander slowly while a song plays. The clock is read in the draw phase
 * only, so drifting never recomposes anything, and it stops under reduced motion.
 *
 * With [spot], a new colour is not faded in: it is poured out from the cover that brought it,
 * across the old one, a band of its paint running ahead with a few runs reaching further (the
 * pages' own pour, from a point). With [lit] false (the song paused) the lights go down.
 *
 * The cover's colours as drifting pools is Mochy Music's idea (its ambient backdrop); this
 * implementation is Valen's own. See NOTICE.
 */
@Composable
fun AmbientWash(
    tones: ArtworkTones,
    modifier: Modifier = Modifier,
    drift: Boolean = false,
    fade: Float = 1f,
    strength: Float = 1f,
    spot: RepaintSpot? = null,
    lit: Boolean = true,
) {
    val reduced = LocalReducedMotion.current
    val pouring = spot != null && !reduced
    // Without a spot to pour from, a new colour eases in as before.
    val spec = if (reduced) Motion.fade<Color>() else Motion.ambient()
    val floor by animateColorAsState(tones.deep, spec, label = "washFloor")
    val left by animateColorAsState(tones.poolLeft, spec, label = "washLeft")
    val right by animateColorAsState(tones.poolRight, spec, label = "washRight")
    val accent by animateColorAsState(tones.poolAccent, spec, label = "washAccent")
    val seconds = rememberDrift(running = drift && !reduced)
    val light by animateFloatAsState(
        targetValue = if (lit || reduced) 1f else Motion.PausedLight,
        animationSpec = if (lit) tween(Motion.LightsUpMs, easing = Motion.Enter) else tween(Motion.LightsDownMs, easing = Motion.Standard),
        label = "washLight",
    )

    // The pour: what is on the wall, what is being poured over it, and how far it has run.
    var under by remember { mutableStateOf(tones) }
    var over by remember { mutableStateOf(tones) }
    val flood = remember { Animatable(1f) }
    var pourSeed by remember { mutableStateOf(0) }
    LaunchedEffect(tones, pouring) {
        if (!pouring || tones === over) {
            under = tones
            over = tones
            flood.snapTo(1f)
            return@LaunchedEffect
        }
        // A pour still running when the next colour comes is finished at once.
        under = over
        over = tones
        pourSeed++
        flood.snapTo(0f)
        flood.animateTo(1f, tween(Motion.RepaintMs, easing = Motion.Enter))
        under = over
    }
    var origin by remember { mutableStateOf(Offset.Zero) }

    // Dithered, because a dark gradient across a whole window bands visibly without it.
    val paints = remember { List(6) { Paint().also { it.dither() } } }

    Spacer(
        modifier = modifier
            .then(if (spot != null) Modifier.onGloballyPositioned { origin = it.positionInRoot() } else Modifier)
            .drawWithCache {
                val reach = maxOf(size.width, size.height)
                val radii = floatArrayOf(reach * 0.74f, reach * 0.62f, reach * 0.66f)
                val eased = arrayOf(left, right, accent)
                val pourBack = arrayOf(under.poolLeft, under.poolRight, under.poolAccent)
                val pourFront = arrayOf(over.poolLeft, over.poolRight, over.poolAccent)
                for (i in 0 until 3) {
                    val back = if (pouring) pourBack[i] else eased[i]
                    paints[i].shader = RadialGradientShader(
                        center = Offset.Zero,
                        radius = radii[i],
                        colors = PoolAlphas.map { back.copy(alpha = it * strength) },
                        colorStops = PoolStops,
                    )
                    paints[i + 3].shader = RadialGradientShader(
                        center = Offset.Zero,
                        radius = radii[i],
                        colors = PoolAlphas.map { pourFront[i].copy(alpha = it * strength) },
                        colorStops = PoolStops,
                    )
                }
                val veil = Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to ValenColors.Background.copy(alpha = 0.22f * fade),
                    0.8f to ValenColors.Background.copy(alpha = 0.78f * fade),
                    1f to ValenColors.Background.copy(alpha = fade),
                )
                val backFloor = if (pouring) under.deep else floor
                onDrawBehind {
                    // Kept to its own area: the pour's paint is a circle far bigger than a band,
                    // and unclipped it painted the page under Home's band until the pour was done.
                    clipRect {
                    val turn = seconds.floatValue * (2f * PI.toFloat() / Motion.AmbientLoopSeconds)
                    drawPools(backFloor, paints, 0, turn)
                    val p = flood.value
                    if (pouring && p < 1f) {
                        val from = spot?.centre?.let { it - origin }
                            ?: Offset(size.width * PoolX[0], size.height * PoolY[0])
                        // The paint leads, then the light of the new colour fills in behind it.
                        val lead = (p + Motion.RepaintLead).coerceAtMost(1f)
                        drawPath(splash(from, lead, pourSeed, density), over.poolLeft.copy(alpha = 0.92f * strength))
                        clipPath(splash(from, p, pourSeed, density, runs = false)) {
                            drawPools(over.deep, paints, 3, turn)
                        }
                    }
                    if (light < 1f) drawRect(ValenColors.Background.copy(alpha = (1f - light) * 0.85f))
                    drawRect(veil)
                    }
                }
            },
    )
}

/** The floor and the three pools, wherever the drift has them now. */
private fun DrawScope.drawPools(floor: Color, paints: List<Paint>, first: Int, turn: Float) {
    drawRect(floor)
    drawIntoCanvas { canvas ->
        for (i in 0 until 3) {
            val cx = size.width * (PoolX[i] + 0.05f * sin(turn * (i + 1) + i * 1.7f))
            val cy = size.height * (PoolY[i] + 0.05f * cos(turn * (i + 2) + i))
            canvas.save()
            canvas.translate(cx, cy)
            canvas.drawRect(Rect(-cx, -cy, size.width - cx, size.height - cy), paints[first + i])
            canvas.restore()
        }
    }
}

/**
 * Paint poured from [from] at [progress] (0 nothing, 1 the whole area): a wavering round edge,
 * with a few runs reaching out ahead of it. Seeded, so one pour always runs the same way.
 */
private fun DrawScope.splash(from: Offset, progress: Float, seed: Int, density: Float, runs: Boolean = true): Path {
    val far = maxOf(
        hypot(from.x, from.y),
        hypot(size.width - from.x, from.y),
        hypot(from.x, size.height - from.y),
        hypot(size.width - from.x, size.height - from.y),
    )
    // Large enough that its deepest wave still covers the farthest corner when it is done.
    val reach = far / 0.88f
    val random = Random(seed * 7919 + 13)
    val phase = random.nextFloat() * 6.2832f
    val runAngles = FloatArray(7) { random.nextFloat() * 6.2832f }
    val runWidths = FloatArray(7) { 0.05f + random.nextFloat() * 0.09f }
    val runLengths = FloatArray(7) { (50f + random.nextFloat() * 130f) * density }
    val r0 = reach * progress
    val path = Path()
    val steps = 120
    for (k in 0..steps) {
        val a = k * 6.2832f / steps
        var r = r0 * (1f + 0.055f * sin(5f * a + phase) + 0.03f * sin(13f * a + phase * 2f))
        if (runs) {
            for (j in 0 until 7) {
                var d = a - runAngles[j]
                if (d > 3.1416f) d -= 6.2832f
                if (d < -3.1416f) d += 6.2832f
                r += runLengths[j] * (progress * 2.2f).coerceAtMost(1f) * exp(-(d / runWidths[j]) * (d / runWidths[j]))
            }
        }
        val x = from.x + cos(a) * r
        val y = from.y + sin(a) * r
        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/** Where each pool rests, as a fraction of the area: top left, right of centre, low centre. */
private val PoolX = floatArrayOf(0.14f, 0.90f, 0.46f)
private val PoolY = floatArrayOf(0.08f, 0.34f, 0.98f)

/** A smooth shoulder rather than a straight ramp, so a pool has no visible rim. */
private val PoolStops = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
private val PoolAlphas = listOf(0.95f, 0.80f, 0.47f, 0.15f, 0f)

/**
 * The wash moves in steps of this length. Its pools travel about 10 px a second at most, so five
 * steps a second look the same as sixty; and a full-screen redraw (which also re-renders every
 * glass surface's blur over it) at the screen's own rate was most of the app's battery use on a
 * phone while a song played.
 */
private const val DriftStepMs = 200L

@Composable
private fun rememberDrift(running: Boolean): FloatState {
    val seconds = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        // A timer, not a frame loop: nothing asks for a frame between two steps.
        while (true) {
            delay(DriftStepMs)
            seconds.floatValue = (seconds.floatValue + DriftStepMs / 1000f) % Motion.AmbientLoopSeconds
        }
    }
    return seconds
}

/** Turns dithering on for this paint; each platform reaches its own native paint to do it. */
internal expect fun Paint.dither()
