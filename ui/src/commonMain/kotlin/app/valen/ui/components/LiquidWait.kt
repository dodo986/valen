/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.ValenColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Waiting for a song to start, in glass rather than a stock spinner: a drop going round a ring.
 * Its two ends move like the droplet that marks a place in the side panel, the head on the quick
 * spring and the tail on the slow one, chasing a point that steps on every [Motion.WaitStepMs] —
 * so each step the drop stretches out along the ring and gathers itself into a bead again.
 *
 * It asks for frames only while it is on screen, which is only while something is loading.
 * Under reduced motion a short arc turns steadily instead.
 */
@Composable
fun LiquidWait(
    modifier: Modifier = Modifier,
    color: Color = ValenColors.Text,
    track: Color = ValenColors.Fill,
) {
    val reduced = LocalReducedMotion.current
    val head = remember { mutableFloatStateOf(0f) }
    val tail = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduced) {
        val lead = Motion.liquidLead<Float>()
        val trail = Motion.liquidTrail<Float>()
        val stepNanos = Motion.WaitStepMs * 1_000_000L
        var target = 0f
        var h = head.floatValue
        var t = tail.floatValue
        var hv = 0f
        var tv = 0f
        var start = -1L
        var last = -1L
        var nextStep = 0L
        while (true) {
            withFrameNanos { now ->
                if (start < 0L) {
                    start = now
                    last = now
                    nextStep = now
                }
                if (reduced) {
                    h = (now - start) / (Motion.WaitReducedTurnMs * 1_000_000f)
                    t = h - ReducedArc
                } else {
                    while (now >= nextStep) {
                        target += Motion.WaitStep
                        nextStep += stepNanos
                    }
                    // Small fixed steps: the screenshot run hands out 80ms frames, too coarse for
                    // a stiff spring integrated in one go.
                    var left = ((now - last) / 1_000_000_000f).coerceAtMost(0.25f)
                    while (left > 0f) {
                        val dt = min(left, SubStep)
                        left -= dt
                        hv += accel(h, hv, target, lead.stiffness, lead.dampingRatio) * dt
                        h += hv * dt
                        tv += accel(t, tv, target, trail.stiffness, trail.dampingRatio) * dt
                        t += tv * dt
                    }
                }
                last = now
                head.floatValue = h
                tail.floatValue = t
            }
        }
    }
    Canvas(modifier) {
        val dim = size.minDimension
        val thick = (dim * 0.11f).coerceAtLeast(3f * density)
        val radius = dim / 2f - thick
        val centre = Offset(size.width / 2f, size.height / 2f)
        drawCircle(track, radius = radius, center = centre, style = Stroke(thick))
        val h = head.floatValue
        val t = tail.floatValue
        val from = minOf(h, t)
        val sweep = abs(h - t) * 360f
        val box = Size(radius * 2f, radius * 2f)
        val corner = Offset(centre.x - radius, centre.y - radius)
        if (sweep > 0.5f) {
            // The body thins toward its tail; the head is a bead a little wider than the body.
            drawArc(
                color = color,
                startAngle = from * 360f - 90f,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = corner,
                size = box,
                style = Stroke(thick * 0.78f, cap = StrokeCap.Round),
            )
        }
        val angle = (h * 360f - 90f) * (PI.toFloat() / 180f)
        drawCircle(color, radius = thick * 0.56f, center = Offset(centre.x + cos(angle) * radius, centre.y + sin(angle) * radius))
    }
}

/** A spring's pull on one end (mass 1), toward [target]. */
private fun accel(x: Float, v: Float, target: Float, stiffness: Float, damping: Float): Float =
    -stiffness * (x - target) - 2f * damping * sqrt(stiffness) * v

private const val SubStep = 0.004f
private const val ReducedArc = 0.22f
