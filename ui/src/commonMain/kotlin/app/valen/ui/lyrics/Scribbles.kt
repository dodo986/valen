/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import app.valen.ui.components.drawDrip
import app.valen.ui.theme.Motion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Where a word sits in its paragraph: its ends, its row and baseline, and the size of its letters. */
internal class WordBox(val left: Float, val right: Float, val baseline: Float, val em: Float) {
    val width: Float get() = right - left
}

/**
 * One mark ready to draw: its strokes in the order the pen makes them, each measured once, so a
 * frame only cuts the drawn length out of a known path. Strokes are separate because the pen
 * lifts between them (Compose's path measure reads one contour at a time anyway).
 */
internal class ScribbleInk(
    val kind: Scribble,
    private val strokes: List<Path>,
    /** The pen's width, in px. */
    val width: Float,
    /** Where paint runs from, and how far, for an underline. */
    private val drip: Offset?,
    private val dripLength: Float,
    private val mist: List<Speck>,
) {
    private val measures = strokes.map { PathMeasure().apply { setPath(it, false) } }
    private val lengths = measures.map { it.length }
    private val total = lengths.sum().coerceAtLeast(1f)
    private val piece = Path()

    /** How many times the pen touches down for this mark (one path each). */
    val strokeCount: Int get() = strokes.size

    /** The mark drawn up to [progress] (0–1) in [color]. */
    fun draw(scope: DrawScope, progress: Float, color: Color) = with(scope) {
        if (progress <= 0f) return@with
        val pen = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
        for (speck in mist) {
            drawCircle(color.copy(alpha = color.alpha * speck.alpha * min(1f, progress * 3f)), speck.radius, speck.at)
        }
        if (progress >= 1f) {
            strokes.forEach { drawPath(it, color, style = pen) }
        } else {
            // The pen's time shared out by stroke length, with a small lift between strokes.
            val lift = if (strokes.size > 1) LIFT_SHARE / (strokes.size - 1) else 0f
            val drawing = 1f - lift * (strokes.size - 1)
            var from = 0f
            for (i in strokes.indices) {
                val share = drawing * lengths[i] / total
                val local = ((progress - from) / share).coerceIn(0f, 1f)
                from += share + lift
                if (local <= 0f) break
                if (local >= 1f) {
                    drawPath(strokes[i], color, style = pen)
                } else {
                    piece.reset()
                    measures[i].getSegment(0f, lengths[i] * Motion.Enter.transform(local), piece, true)
                    drawPath(piece, color, style = pen)
                }
            }
        }
        if (drip != null && progress > DRIP_FROM) {
            val run = ((progress - DRIP_FROM) / (1f - DRIP_FROM)).coerceIn(0f, 1f)
            drawDrip(drip, dripLength * Motion.Enter.transform(run), width * 0.62f, color)
        }
    }

    private companion object {
        const val LIFT_SHARE = 0.08f
        const val DRIP_FROM = 0.86f
    }
}

internal class Speck(val at: Offset, val radius: Float, val alpha: Float)

/**
 * The strokes of [kind] over [box], jittered by [seed] so no two marks are the same. Sizes are in
 * ems of the word's letters, so a mark on a phone's smaller line is the same mark, smaller.
 */
internal fun scribbleInk(kind: Scribble, box: WordBox, seed: Int, density: Float, drip: Float = 0f): ScribbleInk {
    val r = Random(seed)
    val em = box.em
    fun j(amount: Float) = (r.nextFloat() - 0.5f) * 2f * amount * em
    val x0 = box.left
    val x1 = box.right
    val w = box.width
    val b = box.baseline
    val mid = b - 0.34f * em
    val cap = b - 0.72f * em
    val low = b + 0.08f * em
    val pen = em * 0.1f
    val strokes = mutableListOf<Path>()
    var width = pen
    var dripAt: Offset? = null
    var dripLength = 0f
    when (kind) {
        Scribble.Strike -> strokes += Path().apply {
            moveTo(x0 - 0.06f * em, mid + 0.05f * em + j(0.03f))
            quadraticTo((x0 + x1) / 2f, mid - 0.03f * em + j(0.04f), x1 + 0.08f * em, mid - 0.09f * em + j(0.03f))
        }
        Scribble.Double -> {
            strokes += Path().apply {
                moveTo(x0 - 0.05f * em, mid - 0.04f * em + j(0.02f))
                quadraticTo((x0 + x1) / 2f, mid - 0.12f * em, x1 + 0.07f * em, mid - 0.16f * em + j(0.02f))
            }
            strokes += Path().apply {
                moveTo(x1 + 0.03f * em, mid + 0.12f * em + j(0.02f))
                quadraticTo((x0 + x1) / 2f, mid + 0.06f * em, x0 + 0.08f * em, mid + 0.1f * em + j(0.03f))
            }
        }
        Scribble.Cross -> {
            strokes += Path().apply {
                moveTo(x0 - 0.03f * em, cap + 0.06f * em + j(0.03f))
                quadraticTo((x0 + x1) / 2f + j(0.05f), (cap + low) / 2f - 0.06f * em, x1 + 0.05f * em, low + 0.05f * em + j(0.03f))
            }
            strokes += Path().apply {
                moveTo(x1 + 0.01f * em, cap + 0.02f * em + j(0.03f))
                quadraticTo((x0 + x1) / 2f + j(0.05f), (cap + low) / 2f + 0.05f * em, x0 - 0.05f * em, low + 0.08f * em + j(0.03f))
            }
        }
        Scribble.Scratch -> {
            width = pen * 0.85f
            // A short word is scratched over a little wider than itself, so it reads as crossed out.
            val reach = max(w, 0.95f * em)
            val from = (x0 + x1) / 2f - reach / 2f - 0.05f * em
            val step = 0.26f * em
            val n = max(4, (reach / step).toInt() + 1)
            val points = ArrayList<Offset>()
            for (i in 0..n) {
                val x = from + (reach + 0.1f * em) * i / n + j(0.03f)
                points += Offset(x, if (i % 2 == 0) b - 0.02f * em + j(0.04f) else cap + 0.16f * em + j(0.04f))
            }
            // A long word gets a second pass back over it, the way a hand scratches harder.
            if (reach > 1.8f * em) {
                for (i in n - 1 downTo 1) {
                    val x = from + (reach + 0.1f * em) * (i + 0.5f) / (n + 1) + j(0.04f)
                    points += Offset(x, if (i % 2 == 0) mid - 0.17f * em + j(0.03f) else mid + 0.19f * em + j(0.03f))
                }
            }
            strokes += smooth(points)
        }
        Scribble.Hatch -> {
            width = pen * 0.8f
            val m = (w / (0.34f * em)).toInt().coerceIn(3, 7)
            for (i in 0 until m) {
                val x = x0 + w * (i + 0.5f) / m
                strokes += Path().apply {
                    moveTo(x - 0.13f * em + j(0.03f), low + j(0.03f))
                    lineTo(x + 0.15f * em + j(0.03f), cap + 0.04f * em + j(0.03f))
                }
            }
        }
        Scribble.Zigzag -> {
            width = pen * 0.85f
            val y = b + 0.18f * em
            val step = 0.17f * em
            val n = max(4, ((w + 0.1f * em) / step).toInt())
            strokes += Path().apply {
                moveTo(x0 - 0.05f * em, y)
                for (i in 1..n) lineTo(x0 - 0.05f * em + (w + 0.12f * em) * i / n, y + if (i % 2 == 1) -0.07f * em else 0.07f * em)
            }
        }
        Scribble.Box -> {
            // Drawn in one go and a little crooked: the corners miss each other, the last side
            // runs on past where the pen started.
            val cx = (x0 + x1) / 2f
            val cy = (cap + low) / 2f
            val tilt = (r.nextFloat() - 0.5f) * 0.07f
            fun turn(x: Float, y: Float): Offset {
                val dx = x - cx
                val dy = y - cy
                return Offset(cx + dx * cos(tilt) - dy * sin(tilt), cy + dx * sin(tilt) + dy * cos(tilt))
            }
            val t = cap - 0.08f * em
            val bt = low + 0.16f * em
            val l = x0 - 0.16f * em
            val rt = x1 + 0.18f * em
            val a = turn(l + j(0.05f), t + j(0.05f))
            val b1 = turn(rt + 0.08f * em + j(0.05f), t - 0.03f * em + j(0.05f))
            val c = turn(rt + j(0.06f), bt + 0.04f * em + j(0.05f))
            val d = turn(l + 0.04f * em + j(0.06f), bt + j(0.05f))
            val e = turn(l - 0.03f * em + j(0.04f), t - 0.22f * em + j(0.05f))
            strokes += Path().apply {
                moveTo(a.x, a.y)
                quadraticTo((a.x + b1.x) / 2f + j(0.03f), (a.y + b1.y) / 2f - 0.05f * em, b1.x, b1.y)
                quadraticTo((b1.x + c.x) / 2f + 0.05f * em, (b1.y + c.y) / 2f + j(0.03f), c.x, c.y)
                quadraticTo((c.x + d.x) / 2f + j(0.03f), (c.y + d.y) / 2f + 0.06f * em, d.x, d.y)
                quadraticTo((d.x + e.x) / 2f - 0.06f * em, (d.y + e.y) / 2f, e.x, e.y)
            }
        }
        Scribble.Loop -> {
            width = pen * 0.9f
            val cx = (x0 + x1) / 2f
            val cy = b - 0.36f * em
            val rx = w / 2f + 0.3f * em
            val ry = 0.6f * em
            val tilt = j(0.06f) / em
            val start = (200f + j(0.2f) / em * 30f) * PI.toFloat() / 180f
            val sweep = (385f * PI / 180f).toFloat()
            val phase = r.nextFloat() * 6f
            val points = (0..44).map { i ->
                val a = start + sweep * i / 44f
                // The loop opens a little as it goes round, so its end passes outside its start.
                val grow = 1f + 0.07f * i / 44f + 0.03f * sin(a * 3f + phase)
                val px = cos(a) * rx * grow
                val py = sin(a) * ry * grow
                Offset(cx + px * cos(tilt) - py * sin(tilt), cy + px * sin(tilt) + py * cos(tilt))
            }
            strokes += smooth(points)
        }
        Scribble.Underline -> {
            val y = b + 0.17f * em
            val length = w + 0.14f * em
            val phase = r.nextFloat() * 6f
            strokes += Path().apply {
                moveTo(x0 - 0.04f * em, y + j(0.02f))
                val steps = 14
                for (i in 1..steps) {
                    val s = i / steps.toFloat()
                    val sag = sin(s * PI.toFloat()) * 0.05f * em
                    val ripple = sin(s * 7f + phase) * 0.015f * em
                    val lift = if (s > 0.84f) -((s - 0.84f) / 0.16f) * 0.12f * em else 0f
                    lineTo(x0 - 0.04f * em + length * s, y + sag + ripple + lift)
                }
            }
            if (drip > 0f) {
                val at = 0.2f + r.nextFloat() * 0.45f
                dripAt = Offset(x0 - 0.04f * em + length * at, y + sin(at * PI.toFloat()) * 0.05f * em)
                dripLength = (0.35f + 0.9f * drip) * em
            }
        }
        Scribble.Swipe -> {
            width = em * 0.62f
            strokes += Path().apply {
                moveTo(x0 - 0.06f * em, mid + 0.05f * em + j(0.03f))
                quadraticTo((x0 + x1) / 2f, mid - 0.02f * em + j(0.04f), x1 + 0.06f * em, mid - 0.04f * em + j(0.03f))
            }
        }
        Scribble.Burst -> {
            width = pen * 0.85f
            val corner = Offset(x1 + 0.06f * em, cap - 0.02f * em)
            for (deg in listOf(-78f, -40f, -6f)) {
                val a = (deg + j(0.08f) / em * 40f) * PI.toFloat() / 180f
                val near = 0.12f * em
                val far = (0.4f + j(0.05f) / em) * em
                strokes += Path().apply {
                    moveTo(corner.x + cos(a) * near, corner.y + sin(a) * near)
                    lineTo(corner.x + cos(a) * far, corner.y + sin(a) * far)
                }
            }
        }
        Scribble.Brackets -> {
            // A [ at the left and a ] at the right, each opened in two strokes; the side stops
            // short of the foot, so the ends never meet. Drawn left to right, as a hand would.
            width = pen * 0.9f
            val top = cap - 0.02f * em
            val foot = low + 0.13f * em
            strokes += Path().apply {
                moveTo(x0 - 0.02f * em + j(0.02f), top + j(0.03f))
                lineTo(x0 - 0.15f * em + j(0.02f), top + 0.03f * em + j(0.03f))
                lineTo(x0 - 0.14f * em + j(0.02f), b + 0.04f * em + j(0.03f))
            }
            strokes += Path().apply {
                moveTo(x0 - 0.14f * em + j(0.02f), foot - 0.05f * em + j(0.03f))
                lineTo(x0 - 0.01f * em + j(0.02f), foot + j(0.03f))
            }
            strokes += Path().apply {
                moveTo(x1 + 0.02f * em + j(0.02f), top + j(0.03f))
                lineTo(x1 + 0.15f * em + j(0.02f), top + 0.03f * em + j(0.03f))
                lineTo(x1 + 0.14f * em + j(0.02f), b + 0.04f * em + j(0.03f))
            }
            strokes += Path().apply {
                moveTo(x1 + 0.14f * em + j(0.02f), foot - 0.05f * em + j(0.03f))
                lineTo(x1 + 0.01f * em + j(0.02f), foot + j(0.03f))
            }
        }
        Scribble.DoubleUnderline -> {
            val y1 = b + 0.17f * em
            val length1 = w + 0.12f * em
            val phase = r.nextFloat() * 6f
            strokes += Path().apply {
                moveTo(x0 - 0.04f * em, y1 + j(0.02f))
                val steps = 12
                for (i in 1..steps) {
                    val s = i / steps.toFloat()
                    val sag = sin(s * PI.toFloat()) * 0.05f * em
                    val ripple = sin(s * 6f + phase) * 0.015f * em
                    lineTo(x0 - 0.04f * em + length1 * s, y1 + sag + ripple)
                }
            }
            // The second line sits lower, runs on past the word and flicks up where it leaves it.
            val y2 = b + 0.3f * em
            val from = x0 - 0.05f * em
            val to = x1 + 0.24f * em
            strokes += Path().apply {
                moveTo(from, y2 + j(0.02f))
                val steps = 14
                for (i in 1..steps) {
                    val s = i / steps.toFloat()
                    val sag = sin(s * PI.toFloat()) * 0.05f * em
                    val flick = if (s > 0.78f) ((s - 0.78f) / 0.22f) * 0.16f * em else 0f
                    lineTo(from + (to - from) * s, y2 + sag - flick)
                }
            }
            if (drip > 0f) {
                val at = 0.25f + r.nextFloat() * 0.4f
                dripAt = Offset(from + (to - from) * at, y2 + sin(at * PI.toFloat()) * 0.05f * em)
                dripLength = (0.35f + 0.9f * drip) * em
            }
        }
        Scribble.DotTrail -> {
            // Small dabs under the word, each its own touch of the pen (so progress unwrites them
            // one at a time), the dabs shorter the further the trail runs.
            width = pen * 0.8f
            val n = 5 + r.nextInt(4)
            val y = b + 0.18f * em
            val run = w + 0.14f * em
            for (i in 0 until n) {
                val s = (i + 0.5f) / n
                val dab = (0.1f * (1f - s) + 0.005f) * em
                val cx = x0 - 0.04f * em + run * s + j(0.03f)
                val cy = y + s * 0.05f * em + j(0.03f)
                strokes += Path().apply {
                    moveTo(cx - dab, cy)
                    lineTo(cx + dab, cy)
                }
            }
        }
        Scribble.EchoLoop -> {
            width = pen * 0.9f
            val cx = (x0 + x1) / 2f
            val cy = b - 0.36f * em
            val rx = w / 2f + 0.28f * em
            val ry = 0.6f * em
            val tilt = j(0.06f) / em
            val phase = r.nextFloat() * 6f
            // The loop is left open: the pen stops before its own start.
            strokes += smooth(loopPoints(cx, cy, rx, ry, tilt, phase, r, 205f, 320f, 0.06f))
            // A smaller echo of it, dropped beside the word and just as open.
            val erx = (rx * 0.45f).coerceAtMost(0.9f * em)
            val ery = ry * 0.55f
            strokes += smooth(loopPoints(cx + rx * 0.7f, cy + 0.34f * em, erx, ery, -tilt, phase + 1.7f, r, 230f, 315f, 0.1f))
        }
        Scribble.Caret -> {
            // One stroke, two arms, thrown over the word like a hat.
            width = pen * 0.95f
            val arms = cap - 0.06f * em
            val apex = cap - 0.36f * em + j(0.05f)
            strokes += Path().apply {
                moveTo(x0 - 0.06f * em + j(0.03f), arms + j(0.03f))
                lineTo((x0 + x1) / 2f + j(0.09f), apex)
                lineTo(x1 + 0.07f * em + j(0.03f), arms + j(0.03f))
            }
        }
        Scribble.Scallop -> {
            // A row of small round arcs under the word: one running stroke, no sharp corners.
            width = pen * 0.9f
            val y = b + 0.18f * em
            val n = max(3, (w / (0.28f * em)).toInt())
            val run = w + 0.1f * em
            val from = x0 - 0.05f * em
            strokes += Path().apply {
                moveTo(from, y + j(0.02f))
                for (i in 0 until n) {
                    val xa = from + run * i / n
                    val xb = from + run * (i + 1) / n
                    quadraticTo((xa + xb) / 2f, y + 0.09f * em + j(0.015f), xb, y + j(0.02f))
                }
            }
        }
    }
    // A fine overspray where the pen first touched, like the marker under a title.
    val first = when (kind) {
        Scribble.Burst -> Offset(x1 + 0.1f * em, cap - 0.1f * em)
        Scribble.Caret -> Offset((x0 + x1) / 2f, cap - 0.28f * em)
        Scribble.Brackets -> Offset(x0 - 0.2f * em, cap)
        Scribble.Double, Scribble.Strike, Scribble.Swipe -> Offset(x0 - 0.06f * em, mid)
        Scribble.Underline, Scribble.Zigzag, Scribble.DoubleUnderline, Scribble.DotTrail, Scribble.Scallop ->
            Offset(x0 - 0.04f * em, b + 0.17f * em)
        else -> Offset(x0 - 0.04f * em, cap + 0.06f * em)
    }
    val mist = if (kind == Scribble.Swipe) emptyList() else List(5 + r.nextInt(4)) {
        Speck(
            at = Offset(first.x + (r.nextFloat() - 0.3f) * 0.42f * em, first.y + (r.nextFloat() - 0.5f) * 0.36f * em),
            radius = (0.5f + r.nextFloat() * 0.9f) * density,
            alpha = 0.2f + r.nextFloat() * 0.4f,
        )
    }
    return ScribbleInk(kind, strokes, width, dripAt, dripLength, mist)
}

/** A path through [points] with its corners rounded, the way a fast pen makes them. */
private fun smooth(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size - 1) {
        val p = points[i]
        val n = points[i + 1]
        path.quadraticTo(p.x, p.y, (p.x + n.x) / 2f, (p.y + n.y) / 2f)
    }
    points.last().let { path.lineTo(it.x, it.y) }
    return path
}

/**
 * The points of a loop round ([cx], [cy]) made by a wobbling hand: it opens as it goes and stops
 * after [sweepDeg] of turn, so its ends never meet.
 */
private fun loopPoints(
    cx: Float,
    cy: Float,
    rx: Float,
    ry: Float,
    tilt: Float,
    phase: Float,
    r: Random,
    startDeg: Float,
    sweepDeg: Float,
    grow: Float,
): List<Offset> {
    val start = (startDeg + (r.nextFloat() - 0.5f) * 24f) * PI.toFloat() / 180f
    val sweep = sweepDeg * PI.toFloat() / 180f
    return (0..44).map { i ->
        val a = start + sweep * i / 44f
        val g = 1f + grow * i / 44f + 0.03f * sin(a * 3f + phase)
        val px = cos(a) * rx * g
        val py = sin(a) * ry * g
        Offset(cx + px * cos(tilt) - py * sin(tilt), cy + px * sin(tilt) + py * cos(tilt))
    }
}
