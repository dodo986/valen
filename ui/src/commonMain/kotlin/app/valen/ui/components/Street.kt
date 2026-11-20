/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.ValenColors
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/*
 * The street, in one pen. Everything here is drawn with the round marker the owner tagged
 * "Valen" with: the tag itself, written stroke by stroke; the line under a page's title; the
 * swipe behind the song that is playing; the drips. Paint pours a new page over the old one, and
 * the page has the grain of a sprayed wall. Nothing a person reads to use the app is hand-drawn.
 */

/**
 * The tag, written. Each stroke is laid down in the order the hand drew it, at a marker's pace,
 * with a fine overspray of specks; paint then runs from the two lowest points. It writes itself
 * when it first appears and again each time [replay] changes, and under the pointer the mist
 * brightens and the drips run further, as if freshly sprayed. [initial] writes only the V.
 */
@Composable
fun Tag(
    ink: Color,
    mist: Color,
    modifier: Modifier = Modifier,
    initial: Boolean = false,
    replay: Any? = null,
    source: MutableInteractionSource? = null,
) {
    val reduced = LocalReducedMotion.current
    val write = remember { Animatable(if (reduced) 1f else 0f) }
    val drip = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(replay) {
        if (reduced) return@LaunchedEffect
        drip.snapTo(0f)
        write.snapTo(0f)
        write.animateTo(1f, tween(if (initial) Motion.TagWriteMs / 2 else Motion.TagWriteMs, easing = LinearEasing))
        drip.animateTo(1f, tween(Motion.DripMs * 2, easing = Motion.Enter))
    }
    val hovered = source?.collectIsHoveredAsState()?.value == true
    val glow = remember { Animatable(0f) }
    LaunchedEffect(hovered) { glow.animateTo(if (hovered) 1f else 0f, Motion.standard()) }

    val strokes = if (initial) TagStrokes.strokes.take(TagStrokes.InitialStrokes) else TagStrokes.strokes
    val bounds = remember(initial) { boundsOf(strokes) }
    Spacer(
        modifier
            .aspectRatio(bounds.width / bounds.height)
            .drawWithCache {
                val scale = size.width / bounds.width
                val nib = TagStrokes.Nib * scale
                val paths = strokes.map { smoothPath(it, scale, bounds.topLeft) }
                val measures = paths.map { PathMeasure().apply { setPath(it, false) } }
                val lengths = measures.map { it.length }
                val total = lengths.sum().coerceAtLeast(1f)
                val piece = Path()
                // The two lowest stroke ends are where paint gathers and runs.
                val drips = paths.indices
                    .map { measures[it].getPosition(lengths[it]) }
                    .sortedByDescending { it.y }
                    .take(2)
                // Overspray: specks thrown off the line, fixed per stroke so they never crawl.
                val specks = paths.indices.map { i ->
                    val random = Random(i * 31 + 7)
                    List((lengths[i] / nib * 1.6f).toInt().coerceIn(6, 160)) {
                        val along = random.nextFloat()
                        val at = measures[i].getPosition(lengths[i] * along)
                        val spread = nib * (0.9f + random.nextFloat() * 2.6f)
                        val angle = random.nextFloat() * 2f * PI.toFloat()
                        Triple(along, Offset(at.x + kotlin.math.cos(angle) * spread, at.y + sin(angle) * spread), nib * (0.08f + random.nextFloat() * 0.22f))
                    }
                }
                val pen = Stroke(nib, cap = StrokeCap.Round, join = StrokeJoin.Round)
                onDrawBehind {
                    val spray = mist.copy(alpha = mist.alpha * (0.6f + 0.4f * glow.value))
                    for (layer in 0..2) {
                        var budget = total * write.value
                        for (i in paths.indices) {
                            if (budget <= 0f) break
                            val take = budget.coerceAtMost(lengths[i])
                            budget -= take
                            piece.reset()
                            measures[i].getSegment(0f, take, piece, true)
                            when (layer) {
                                // Mist under the paint: wide and soft, then the paint itself.
                                // No halo: paint is flat. Only a fine overspray of specks around it.
                                0 -> Unit
                                1 -> {
                                    val reached = take / lengths[i]
                                    for ((along, at, radius) in specks[i]) {
                                        if (along <= reached) drawCircle(spray.copy(alpha = spray.alpha * 0.45f), radius, at)
                                    }
                                }
                                else -> drawPath(piece, ink, style = pen)
                            }
                        }
                    }
                    if (drip.value > 0f) {
                        drips.forEachIndexed { n, at ->
                            val length = nib * (if (n == 0) 3.2f else 2.1f) * (drip.value + 0.4f * glow.value)
                            drawDrip(at, length, nib * 0.9f, ink)
                        }
                    }
                }
            },
    )
}

private fun boundsOf(strokes: List<FloatArray>): Rect {
    var l = Float.MAX_VALUE
    var t = Float.MAX_VALUE
    var r = -Float.MAX_VALUE
    var b = -Float.MAX_VALUE
    for (s in strokes) {
        for (i in s.indices step 2) {
            l = minOf(l, s[i]); r = maxOf(r, s[i])
            t = minOf(t, s[i + 1]); b = maxOf(b, s[i + 1])
        }
    }
    // Room for the nib all round, and for the drips that run below the lowest stroke.
    val pad = TagStrokes.Nib
    return Rect(l - pad, t - pad, r + pad, b + pad * 5f)
}

/** A stroke through its points, rounded at each corner the way a moving hand rounds them. */
private fun smoothPath(points: FloatArray, scale: Float, origin: Offset): Path {
    val path = Path()
    fun x(i: Int) = (points[i * 2] - origin.x) * scale
    fun y(i: Int) = (points[i * 2 + 1] - origin.y) * scale
    val n = points.size / 2
    path.moveTo(x(0), y(0))
    if (n == 2) {
        path.lineTo(x(1), y(1))
        return path
    }
    for (i in 1 until n - 1) {
        path.quadraticTo(x(i), y(i), (x(i) + x(i + 1)) / 2f, (y(i) + y(i + 1)) / 2f)
    }
    path.lineTo(x(n - 1), y(n - 1))
    return path
}

/** A run of paint: wide where it leaves the stroke, thinning as it falls, ending in a bead. */
internal fun DrawScope.drawDrip(from: Offset, length: Float, width: Float, color: Color) {
    if (length <= 0.5f) return
    val end = from.y + length
    val neck = width * 0.42f
    val path = Path().apply {
        moveTo(from.x - width / 2f, from.y)
        lineTo(from.x + width / 2f, from.y)
        quadraticTo(from.x + neck / 2f, from.y + length * 0.35f, from.x + neck / 2f, end)
        lineTo(from.x - neck / 2f, end)
        quadraticTo(from.x - neck / 2f, from.y + length * 0.35f, from.x - width / 2f, from.y)
        close()
    }
    drawPath(path, color)
    drawCircle(color, radius = width * 0.5f, center = Offset(from.x, end))
}

/**
 * Text whose letters rise into place one after another through the line they sit on, overshooting
 * a little and settling, each time [text] changes. Drawn as one paragraph (each letter is the
 * paragraph clipped to that letter), so kerning, wrapping and the ellipsis are the paragraph's own.
 *
 * With [underline], a marker line is then drawn under the last line, a little overspray at its
 * start and paint running from one point.
 */
@Composable
fun KineticText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    underline: Color? = null,
) {
    val reduced = LocalReducedMotion.current
    val clock = remember(text) { Animatable(if (reduced) 1f else 0f) }
    val marker = remember(text) { Animatable(if (reduced) 1f else 0f) }
    val drip = remember(text) { Animatable(if (reduced) 1f else 0f) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val letters = text.length.coerceAtMost(KineticLetters)
    val span = (Motion.KineticMs + Motion.KineticStaggerMs * letters).toFloat()
    LaunchedEffect(text) {
        if (reduced) return@LaunchedEffect
        clock.animateTo(1f, tween(span.toInt(), easing = LinearEasing))
    }
    LaunchedEffect(text) {
        if (reduced || underline == null) return@LaunchedEffect
        marker.animateTo(1f, tween(Motion.MarkerMs, delayMillis = Motion.KineticMs / 2, easing = Motion.Standard))
        drip.animateTo(1f, tween(Motion.DripMs, easing = Motion.Enter))
    }
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
        modifier = modifier
            .then(if (underline != null) Modifier.padding(bottom = 10.dp) else Modifier)
            .drawWithCache {
                val result = layout
                val stroke = if (underline != null && result != null) {
                    val last = result.lineCount - 1
                    val width = result.getLineRight(last).coerceAtMost(size.width)
                    markerStroke(text.hashCode(), width, result.getLineBaseline(last) + 9.dp.toPx(), 3.dp.toPx())
                } else {
                    null
                }
                val measure = stroke?.let { PathMeasure().apply { setPath(it.main, false) } }
                val flick = stroke?.let { PathMeasure().apply { setPath(it.flick, false) } }
                val segment = Path()
                val thick = 5.dp.toPx()
                onDrawWithContent {
                    if (stroke != null && underline != null && marker.value > 0f) {
                        drawMarker(stroke, measure!!, flick!!, segment, marker.value, drip.value, underline, thick)
                    }
                    val t = clock.value
                    if (t >= 1f || result == null) {
                        drawContent()
                        return@onDrawWithContent
                    }
                    val elapsed = t * span
                    for (i in 0 until result.layoutInput.text.length) {
                        val box = result.getBoundingBox(i)
                        if (box.width <= 0f) continue
                        val line = result.getLineForOffset(i)
                        val top = result.getLineTop(line)
                        val bottom = result.getLineBottom(line)
                        val local = ((elapsed - i.coerceAtMost(KineticLetters) * Motion.KineticStaggerMs) / Motion.KineticMs)
                            .coerceIn(0f, 1f)
                        if (local <= 0f) continue
                        val eased = Motion.Kinetic.transform(local)
                        val lineHeight = bottom - top
                        clipRect(box.left - 1f, top - lineHeight * 0.3f, box.right + 1f, bottom) {
                            translate(0f, (1f - eased) * lineHeight) { this@onDrawWithContent.drawContent() }
                        }
                    }
                }
            },
    )
}

/** Past this many letters the rest of a title rises with the last of them. */
private const val KineticLetters = 40

/**
 * The swipe of a marker behind a line of text, drawn on from left to right when [active] turns
 * on — the way the song that is playing is marked in a list, as if someone had gone over it.
 */
@Composable
fun Modifier.markerSwipe(active: Boolean, color: Color, seed: Int): Modifier {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(if (active) 1f else 0f) }
    LaunchedEffect(active) {
        if (reduced) {
            progress.snapTo(if (active) 1f else 0f)
        } else {
            progress.animateTo(if (active) 1f else 0f, tween(if (active) Motion.MarkerMs else Motion.ExitMs, easing = Motion.Standard))
        }
    }
    return drawWithCache {
        val random = Random(seed)
        val thick = size.height * 0.5f
        val y = size.height * 0.6f
        val path = Path().apply {
            moveTo(-2.dp.toPx(), y + (random.nextFloat() - 0.5f) * 3.dp.toPx())
            val steps = 8
            for (i in 1..steps) {
                val t = i / steps.toFloat()
                lineTo(size.width * t, y + sin(t * 5f + seed) * 1.6f * density - t * 2.dp.toPx())
            }
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val piece = Path()
        val pen = Stroke(thick, cap = StrokeCap.Round, join = StrokeJoin.Round)
        onDrawBehind {
            if (progress.value <= 0f) return@onDrawBehind
            piece.reset()
            measure.getSegment(0f, measure.length * progress.value, piece, true)
            drawPath(piece, color, style = pen)
        }
    }
}

private class Mist(val at: Offset, val radius: Float, val alpha: Float)

private class MarkerStroke(
    val main: Path,
    val flick: Path,
    val mist: List<Mist>,
    val dripAt: Offset,
    val dripLength: Float,
)

private fun DrawScope.drawMarker(
    stroke: MarkerStroke,
    measure: PathMeasure,
    flickMeasure: PathMeasure,
    segment: Path,
    p: Float,
    drip: Float,
    mark: Color,
    thick: Float,
) {
    for (dot in stroke.mist) {
        drawCircle(mark.copy(alpha = mark.alpha * dot.alpha * p), radius = dot.radius, center = dot.at)
    }
    val mainShare = 0.8f
    segment.reset()
    measure.getSegment(0f, measure.length * (p / mainShare).coerceAtMost(1f), segment, true)
    drawPath(segment, mark, style = Stroke(thick, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (p > mainShare) {
        segment.reset()
        flickMeasure.getSegment(0f, flickMeasure.length * ((p - mainShare) / (1f - mainShare)), segment, true)
        drawPath(segment, mark, style = Stroke(thick * 0.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    if (drip > 0f) drawDrip(stroke.dripAt, stroke.dripLength * drip, thick * 0.6f, mark)
}

/**
 * A marker line across [width] at [y]: a long stroke with a slight sag and a lift at its end, a
 * thin flick back under it, a sprinkle of overspray at its start, and one drip. Seeded by the
 * words, so a title is always underlined the same way.
 */
private fun Density.markerStroke(seed: Int, width: Float, y: Float, wobble: Float): MarkerStroke {
    val random = Random(seed)
    val length = width * (0.58f + random.nextFloat() * 0.24f)
    val start = Offset(-2.dp.toPx(), y + random.nextFloat() * wobble)
    val phase = random.nextFloat() * 2f * PI.toFloat()
    val main = Path().apply {
        moveTo(start.x, start.y)
        val steps = 18
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            val sag = sin(t * PI.toFloat()) * wobble * 0.9f
            val ripple = sin(t * 7f + phase) * wobble * 0.25f
            val lift = if (t > 0.86f) -((t - 0.86f) / 0.14f) * wobble * 2.2f else 0f
            lineTo(start.x + length * t, start.y + sag + ripple + lift)
        }
    }
    val end = Offset(start.x + length, start.y - wobble * 2.2f)
    val flick = Path().apply {
        moveTo(end.x - length * 0.04f, end.y + wobble * 1.4f)
        quadraticTo(end.x - length * 0.22f, end.y + wobble * 3.6f, end.x - length * 0.42f, end.y + wobble * 3.2f)
    }
    val spread = 14.dp.toPx()
    val mist = List(14) {
        Mist(
            at = Offset(start.x + random.nextFloat() * spread * 1.6f, start.y + (random.nextFloat() - 0.5f) * spread),
            radius = (0.6f + random.nextFloat() * 1.3f) * density,
            alpha = 0.25f + random.nextFloat() * 0.45f,
        )
    }
    val dripT = 0.18f + random.nextFloat() * 0.3f
    val dripAt = Offset(start.x + length * dripT, start.y + sin(dripT * PI.toFloat()) * wobble * 0.9f)
    return MarkerStroke(main, flick, mist, dripAt, (8 + random.nextInt(10)).dp.toPx())
}

/**
 * The leading edge of paint poured down a wall, at [progress] (0 nothing, 1 all covered) over an
 * area [width] x [height]: a wavering line with drips running ahead of it. Everything above the
 * edge, and inside the drips, is painted. Seeded, so one pour always runs the same way.
 */
internal fun pourPath(width: Float, height: Float, progress: Float, seed: Int, density: Float, drips: Boolean = true): Path {
    val random = Random(seed)
    val dripMax = 150f * density
    val wave = 10f * density
    val base = -wave * 2f + (height + dripMax + wave * 3f) * progress
    val path = Path()
    path.moveTo(0f, -1f)
    path.lineTo(width, -1f)
    val steps = 32
    val phase = random.nextFloat() * 6f
    for (i in steps downTo 0) {
        val x = width * i / steps
        val y = base + sin(x / width * 9f + phase) * wave + sin(x / width * 23f + phase * 2f) * wave * 0.35f
        path.lineTo(x, y)
    }
    path.close()
    if (!drips) return path
    // Drips: tongues of paint ahead of the edge, each running at its own speed.
    repeat(8 + random.nextInt(5)) {
        val x = random.nextFloat() * width
        val w = (8f + random.nextFloat() * 24f) * density
        val run = (0.25f + random.nextFloat() * 0.75f) * dripMax * (progress * 1.8f).coerceAtMost(1f)
        val top = base - wave * 1.5f
        val bottom = base + run
        if (bottom > top + w) {
            path.addRoundRect(RoundRect(Rect(x - w / 2f, top, x + w / 2f, bottom), CornerRadius(w / 2f, w / 2f)))
        }
    }
    return path
}

/**
 * The wall: a fine grain of light and dark specks over everything under the chrome, like sprayed
 * concrete or a photocopied flyer. Glass blurs it away, which is what makes the glass read as
 * smooth. It is a fixed tile, so it costs one bitmap, made once.
 */
fun Modifier.wallGrain(): Modifier = drawWithCache {
    val brush = ShaderBrush(ImageShader(GrainTile.bitmap, TileMode.Repeated, TileMode.Repeated))
    onDrawWithContent {
        drawContent()
        drawRect(brush)
    }
}

private object GrainTile {
    private const val Size = 160

    val bitmap: ImageBitmap by lazy {
        val image = ImageBitmap(Size, Size)
        val canvas = Canvas(image)
        val random = Random(986)
        fun specks(count: Int, color: Color) {
            val points = List(count) { Offset(random.nextInt(Size) + 0.5f, random.nextInt(Size) + 0.5f) }
            val paint = Paint().apply {
                this.color = color
                strokeWidth = 1f
            }
            canvas.drawPoints(PointMode.Points, points, paint)
        }
        specks(Size * Size / 5, ValenColors.GrainDark)
        specks(Size * Size / 7, ValenColors.GrainLight)
        image
    }
}

/**
 * A cover pasted to the wall by hand: turned a degree or so, the way [seed] falls. Under the
 * pointer it straightens and comes up to meet it.
 */
@Composable
fun Modifier.pasted(seed: Int, source: MutableInteractionSource): Modifier {
    val reduced = LocalReducedMotion.current
    if (reduced) return this
    val hovered by source.collectIsHoveredAsState()
    val rest = remember(seed) { (Random(seed).nextFloat() * 2f - 1f) * Motion.PasteDegrees }
    val turn = remember { Animatable(rest) }
    LaunchedEffect(hovered, rest) { turn.animateTo(if (hovered) 0f else rest, Motion.lean()) }
    return graphicsLayer { rotationZ = turn.value }
}

/**
 * Two strips of tape holding a cover to the wall at its top corners, slapped on — overshooting
 * down onto the cover and settling — each time [key] (the song) changes, unless it is [already] on.
 */
@Composable
fun Modifier.taped(key: Any?, already: Boolean = false): Modifier {
    val reduced = LocalReducedMotion.current
    // [already]: a poster found again (going back) still has the tape it was put up with.
    val slap = remember(key) { Animatable(if (reduced || already) 1f else 0f) }
    LaunchedEffect(key) { if (!reduced && slap.value < 1f) slap.animateTo(1f, tween(Motion.KineticMs, delayMillis = Motion.EnterMs, easing = Motion.Kinetic)) }
    return drawWithContent {
        drawContent()
        val p = slap.value
        if (p <= 0f) return@drawWithContent
        val length = (size.minDimension * 0.24f).coerceIn(48.dp.toPx(), 120.dp.toPx())
        val width = length * 0.34f
        val inX = length * 0.16f
        val inY = length * 0.08f
        val corners = listOf(Offset(inX, inY) to -38f, Offset(size.width - inX, inY) to 38f)
        corners.forEachIndexed { n, (at, angle) ->
            val s = 1.35f - 0.35f * p
            rotate(angle, pivot = at) {
                scale(s, s, pivot = at) {
                    drawPath(tapePath(at, length, width, key.hashCode() + n), ValenColors.Tape.copy(alpha = ValenColors.Tape.alpha * p.coerceAtMost(1f)))
                    drawLine(
                        ValenColors.Tape.copy(alpha = 0.18f * p.coerceAtMost(1f)),
                        Offset(at.x - length / 2f, at.y - width * 0.18f),
                        Offset(at.x + length / 2f, at.y - width * 0.18f),
                        strokeWidth = width * 0.12f,
                    )
                }
            }
        }
    }
}

/** A strip of tape centred on [at]: straight along its length, torn in small teeth at both ends. */
private fun tapePath(at: Offset, length: Float, width: Float, seed: Int): Path {
    val random = Random(seed)
    val l = at.x - length / 2f
    val r = at.x + length / 2f
    val t = at.y - width / 2f
    val b = at.y + width / 2f
    val teeth = 6
    val bite = width * 0.09f
    return Path().apply {
        moveTo(l, t)
        lineTo(r, t)
        for (i in 1..teeth) lineTo(r + (if (i % 2 == 0) 0f else -bite) * (0.5f + random.nextFloat()), t + width * i / teeth)
        lineTo(l, b)
        for (i in teeth - 1 downTo 0) lineTo(l + (if (i % 2 == 0) 0f else bite) * (0.5f + random.nextFloat()), t + width * i / teeth)
        close()
    }
}
