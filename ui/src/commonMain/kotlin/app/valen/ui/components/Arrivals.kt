/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.valen.ui.Route
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.ValenColors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * How a page arrives. Every place has its own and no two share one, so moving around the app reads
 * as moving between rooms rather than flicking one card. In paint: poured down the wall, sprayed
 * from a can, burst from a balloon, rolled on. In paper: taped up in strips, a poster unrolled, a
 * screen print's dots, a poster slapped on, a notebook's page turned over. And a shop shutter
 * rolled up, a tube switched on. They share one length and one curve ([Motion.pour]), and no visit
 * is quite the last one: the roller goes across or down the wall, the poster is turned either way,
 * the strips start from either side, the page turns from its spine or over the top, and the can,
 * the balloon and the print start where you reached for the place ([Reach]).
 *
 * A page leaving runs its own arrival backwards under the one coming: the strips peel off, the
 * poster rolls back up, the dots shrink away.
 */
enum class PageArrival { Pour, Spray, Splat, Roller, RollerBack, Tape, Unroll, Halftone, Shutter, Slap, Leaf, Tube }

/** Each place's arrival. */
fun arrivalFor(route: Route): PageArrival = when (route) {
    Route.Home -> PageArrival.Pour
    Route.Search -> PageArrival.Spray
    Route.Mix -> PageArrival.Splat
    Route.Playlists -> PageArrival.Roller
    Route.Queue -> PageArrival.RollerBack
    Route.Import -> PageArrival.Tape
    Route.Artist -> PageArrival.Unroll
    Route.About -> PageArrival.Halftone
    Route.Settings -> PageArrival.Shutter
    Route.NowPlaying -> PageArrival.Slap
    Route.Sound -> PageArrival.Leaf
    Route.Profile -> PageArrival.Tube
}

/**
 * Where the pointer last went down in the window, and when. A page sprayed, burst or printed on
 * starts there, so it comes out of what you reached for (a row of the side panel, a tab, a button);
 * one opened from the keyboard finds nothing that recent and starts from its own place.
 */
internal object Reach {
    private var at = Offset.Unspecified
    private var time = 0L

    fun press(position: Offset) {
        at = position
        time = System.currentTimeMillis()
    }

    /** The last press, in the window, if it was a moment ago. */
    fun recent(): Offset? = at.takeIf { it.isSpecified && System.currentTimeMillis() - time < FreshMs }

    private const val FreshMs = 1_000L
}

/** Notes every press for [Reach]. Put on the window's root, so a press is measured from its corner. */
fun Modifier.noteReach(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press) event.changes.firstOrNull()?.let { Reach.press(it.position) }
        }
    }
}

/**
 * Draws a page arriving by [arrival] at [progress] (0 nothing yet, 1 settled). The new page is
 * painted solid inside what has been revealed, and the tool's own mark (wet paint, spatter, a
 * roller's band, tape, the roll, ink dots, the shutter's slats, the tube's flash) leads it. [origin]
 * is the page's own spot (about where its title is); [reach] is the press that opened it, in the
 * page's coordinates, when there was one.
 */
internal fun ContentDrawScope.drawArrival(
    arrival: PageArrival,
    progress: Float,
    seed: Int,
    paint: Color,
    origin: Offset,
    reach: Offset? = null,
) {
    if (progress >= 1f) {
        drawContent()
        return
    }
    if (progress <= 0f) return
    val w = size.width
    val h = size.height
    // Drawn on the page's own scope, inside whichever clip the arrival sets.
    val page: () -> Unit = {
        drawRect(ValenColors.Background)
        drawContent()
    }
    when (arrival) {
        PageArrival.Pour -> {
            drawPath(pourPath(w, h, (progress + Motion.PourLead).coerceAtMost(1f), seed, density), paint)
            clipPath(pourPath(w, h, progress, seed, density, drips = false)) { page() }
        }

        PageArrival.Spray -> {
            val at = reach ?: origin
            val r = farthest(at, w, h) * 1.08f * progress
            // Spatter flies ahead of the can's edge: dots that land further out the faster it sprays.
            val random = Random(seed)
            repeat(140) {
                val angle = random.nextFloat() * TwoPi
                val out = r + (random.nextFloat() * random.nextFloat()) * 90.dp.toPx()
                val dot = (1.2f + random.nextFloat() * 4.5f) * density
                drawCircle(paint, radius = dot, center = Offset(at.x + cos(angle) * out, at.y + sin(angle) * out))
            }
            drawCircle(paint, radius = r + 10.dp.toPx(), center = at)
            clipPath(Path().apply { addOval(Rect(at, r)) }) { page() }
        }

        PageArrival.Splat -> drawSplat(reach ?: Offset((origin.x + w) / 2f, h * 0.4f), progress, seed, paint, page)

        PageArrival.Roller, PageArrival.RollerBack -> drawRoller(arrival == PageArrival.RollerBack, progress, seed, paint, page)

        PageArrival.Tape -> drawTape(progress, seed, page)

        PageArrival.Unroll -> drawUnroll(progress, seed, page)

        PageArrival.Halftone -> drawHalftone(reach, progress, seed, paint, page)

        PageArrival.Shutter -> {
            // A shop shutter rolling up: the new page shows from the bottom, the slats lead.
            val slats = 40.dp.toPx()
            val top = h - (h + slats) * progress
            clipPath(Path().apply { addRect(Rect(0f, top + slats, w, h)) }) { page() }
            val pitch = 7.dp.toPx()
            var y = top
            while (y < top + slats) {
                drawRect(ValenColors.Surface2, topLeft = Offset(0f, y), size = Size(w, pitch * 0.62f))
                drawRect(ValenColors.Surface, topLeft = Offset(0f, y + pitch * 0.62f), size = Size(w, pitch * 0.38f))
                y += pitch
            }
            drawRect(paint, topLeft = Offset(0f, top + slats - 3.dp.toPx()), size = Size(w, 3.dp.toPx()))
        }

        PageArrival.Tube -> {
            // A tube lighting: a line across the middle, then the picture opening from it.
            val line = (progress / 0.25f).coerceIn(0f, 1f)
            val open = ((progress - 0.25f) / 0.75f).coerceIn(0f, 1f)
            val lineW = w * Motion.Enter.transform(line)
            val openH = maxOf(2.dp.toPx(), h * Motion.Enter.transform(open))
            val shown = Rect(w / 2f - lineW / 2f, h / 2f - openH / 2f, w / 2f + lineW / 2f, h / 2f + openH / 2f)
            clipPath(Path().apply { addRect(shown) }) {
                page()
                drawRect(Color.White.copy(alpha = (1f - open) * 0.85f))
            }
        }

        PageArrival.Slap -> {
            // Handled as a transform by [arriving]; here the page simply draws.
            page()
        }

        PageArrival.Leaf -> {
            // Turned by [arriving]; the side still turned away from the light is in shadow.
            page()
            drawRect(Color.Black.copy(alpha = 0.55f * (1f - progress)))
        }
    }
}

/**
 * A balloon of paint burst against the wall: a blot with fat lobes, thrown drops with tails pointing
 * back at it, and the page inside the blot's wet edge.
 */
private fun ContentDrawScope.drawSplat(at: Offset, progress: Float, seed: Int, paint: Color, page: () -> Unit) {
    val random = Random(seed)
    val lobes = 6 + random.nextInt(4)
    val phase = random.nextFloat() * TwoPi
    val phase2 = random.nextFloat() * TwoPi
    // The lobes dip to 0.78 of the radius, so it reaches that much past the farthest corner.
    val r = farthest(at, size.width, size.height) * 1.34f * progress
    fun blot(radius: Float): Path = Path().apply {
        val steps = 120
        for (i in 0..steps) {
            val a = i * TwoPi / steps
            val k = 1f + 0.12f * sin(a * lobes + phase) + 0.06f * sin(a * (lobes * 2 + 1) + phase2) + 0.04f * sin(a * 3f + phase2)
            val x = at.x + cos(a) * radius * k
            val y = at.y + sin(a) * radius * k
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    repeat(22) {
        val a = random.nextFloat() * TwoPi
        val dir = Offset(cos(a), sin(a))
        val out = r * (1.06f + random.nextFloat() * 0.5f) + 10.dp.toPx()
        val drop = (2.5f + random.nextFloat() * random.nextFloat() * 11f) * density
        val c = at + dir * out
        drawLine(paint, c - dir * (drop * 3.2f), c, strokeWidth = drop * 1.2f, cap = StrokeCap.Round)
        drawCircle(paint, drop, c)
    }
    drawPath(blot(r + 12.dp.toPx()), paint)
    clipPath(blot(r)) { page() }
}

/**
 * A paint roller drawn over the wall, its band of wet paint with the dry streaks a roller leaves:
 * across (Playlists left to right, the queue right to left) or, every other visit, down the wall
 * (the queue up it).
 */
private fun ContentDrawScope.drawRoller(back: Boolean, progress: Float, seed: Int, paint: Color, page: () -> Unit) {
    val down = seed % 2 == 1
    val w = size.width
    val h = size.height
    // Worked out along the roller's way ([along]) and across it, then laid on the wall.
    val along = if (down) h else w
    val across = if (down) w else h
    fun at(a: Float, c: Float) = if (down) Offset(c, a) else Offset(a, c)
    val band = 34.dp.toPx()
    val edge = -band + (along + band * 2f) * progress
    val front = if (back) along - edge else edge
    val wave = { c: Float -> sin(c / across * 11f + seed) * 4.dp.toPx() }
    val start = if (back) along else 0f
    val covered = Path().apply {
        val steps = 24
        at(start, 0f).let { moveTo(it.x, it.y) }
        for (i in 0..steps) {
            val c = across * i / steps
            at(front + wave(c), c).let { lineTo(it.x, it.y) }
        }
        at(start, across).let { lineTo(it.x, it.y) }
        close()
    }
    val lead = if (back) front - band * 0.1f else front - band * 0.9f
    drawRect(paint, topLeft = at(lead, 0f), size = if (down) Size(w, band) else Size(band, h))
    val random = Random(seed)
    repeat(26) {
        val c = random.nextFloat() * across
        val thick = (1 + random.nextInt(3)) * density
        drawRect(ValenColors.Background.copy(alpha = 0.18f), topLeft = at(lead, c), size = if (down) Size(thick, band) else Size(band, thick))
    }
    clipPath(covered) { page() }
}

/**
 * Strips of packing tape laid across the wall one under the other, each torn off at its end, the
 * page showing through them; once they are all down the tape clears off the page it holds. The
 * strips start from alternate sides, the first from either.
 */
private fun ContentDrawScope.drawTape(progress: Float, seed: Int, page: () -> Unit) {
    val w = size.width
    val h = size.height
    val random = Random(seed)
    val strip = 58.dp.toPx()
    val overlap = 12.dp.toPx()
    val tooth = 6.dp.toPx()
    val n = ceil(h / strip).toInt().coerceAtLeast(1)
    // Each strip takes [each] of the time to lay, the next starting a little after; all are down by
    // [laid]. A strip's tape clears off the page over [clearing] once it is down, so the tape is a
    // band running down the page just behind the strips being laid.
    val laid = 0.62f
    val each = 0.3f
    val clearing = 0.3f
    val step = if (n > 1) (laid - each) / (n - 1) else 0f
    val leftFirst = seed % 2 == 0
    val shown = Path()
    val strips = ArrayList<Pair<Path, Float>>(n)
    val edges = ArrayList<Triple<Offset, Offset, Float>>(n * 2)
    for (i in 0 until n) {
        // Drawn before anything is skipped, so each strip keeps its own slope from frame to frame.
        val slope = (random.nextFloat() - 0.5f) * 0.016f
        val laidOut = Motion.Enter.transform(((progress - i * step) / each).coerceIn(0f, 1f))
        if (laidOut <= 0f) continue
        val clear = 1f - ((progress - i * step - each) / clearing).coerceIn(0f, 1f)
        val fromLeft = (i % 2 == 0) == leftFirst
        val length = -tooth + (w + tooth * 3f) * laidOut
        val head = if (fromLeft) length else w - length
        val tail = if (fromLeft) -tooth * 2f else w + tooth * 2f
        val top = i * strip - overlap
        val bottom = (i + 1) * strip + overlap
        fun y(base: Float, x: Float) = base + slope * (x - w / 2f)
        val left = min(head, tail)
        val right = max(head, tail)
        // Clockwise, every strip the same way round, so together they fill as one.
        val path = Path().apply {
            moveTo(left, y(top, left))
            lineTo(right, y(top, right))
            if (fromLeft) torn(right, y(top, right), y(bottom, right), -tooth) else lineTo(right, y(bottom, right))
            lineTo(left, y(bottom, left))
            if (!fromLeft) torn(left, y(bottom, left), y(top, left), tooth) else lineTo(left, y(top, left))
            close()
        }
        shown.addPath(path)
        if (clear <= 0f) continue
        strips += path to clear
        edges += Triple(Offset(left, y(top + 1.dp.toPx(), left)), Offset(right, y(top + 1.dp.toPx(), right)), clear)
        edges += Triple(Offset(left, y(bottom - 1.dp.toPx(), left)), Offset(right, y(bottom - 1.dp.toPx(), right)), clear)
    }
    clipPath(shown) { page() }
    val tape = ValenColors.Tape
    strips.forEach { (path, clear) -> drawPath(path, tape.copy(alpha = tape.alpha * 0.75f * clear)) }
    edges.forEach { (a, b, clear) -> drawLine(tape.copy(alpha = min(1f, tape.alpha * 1.4f) * clear), a, b, strokeWidth = 1.dp.toPx()) }
}

/** The torn end of a strip of tape, from [fromY] to [toY] at [x]: teeth [bite] deep. */
private fun Path.torn(x: Float, fromY: Float, toY: Float, bite: Float) {
    val teeth = 9
    for (k in 1..teeth) {
        val y = fromY + (toY - fromY) * k / teeth
        lineTo(if (k % 2 == 1) x + bite else x, y)
    }
    lineTo(x, toY)
}

/**
 * A poster unrolled down the wall from the top: the roll gets thinner as it goes and leaves the
 * page flat above it, with the curl's shade just over the roll and the roll's shadow under it. It
 * hangs a little off level, one way or the other.
 */
private fun ContentDrawScope.drawUnroll(progress: Float, seed: Int, page: () -> Unit) {
    val w = size.width
    val h = size.height
    val random = Random(seed)
    val slope = (if (seed % 2 == 0) 1f else -1f) * (0.01f + random.nextFloat() * 0.012f)
    val roll = (30f - 14f * progress).dp.toPx()
    val tilt = abs(slope) * w / 2f
    val edge = -roll - tilt + (h + roll * 2f + tilt * 2f) * progress
    fun y(x: Float) = edge + slope * (x - w / 2f)
    clipPath(Path().apply { moveTo(0f, -1f); lineTo(w, -1f); lineTo(w, y(w)); lineTo(0f, y(0f)); close() }) { page() }
    // Across the roll, square to its slant.
    val n = Offset(-slope, 1f) / sqrt(1f + slope * slope)
    val mid = Offset(w / 2f, edge)
    fun band(from: Float, to: Float): Path = Path().apply {
        moveTo(0f, y(0f) + from)
        lineTo(w, y(w) + from)
        lineTo(w, y(w) + to)
        lineTo(0f, y(0f) + to)
        close()
    }
    val curl = 26.dp.toPx()
    drawPath(band(-curl, 0f), Brush.linearGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.32f)), mid - n * curl, mid))
    val shadow = 22.dp.toPx()
    drawPath(
        band(roll, roll + shadow),
        Brush.linearGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent), mid + n * roll, mid + n * (roll + shadow)),
    )
    drawPath(
        band(0f, roll),
        Brush.linearGradient(
            0f to ValenColors.PaperShade,
            0.32f to ValenColors.PaperLight,
            0.62f to ValenColors.Paper,
            1f to ValenColors.PaperShade,
            start = mid,
            end = mid + n * roll,
        ),
    )
}

/**
 * A screen print: the page comes through a grid of dots that swell until they meet, a wave of them
 * from where you reached for it (or a corner), each dot's ink a little ahead of the print.
 */
private fun ContentDrawScope.drawHalftone(reach: Offset?, progress: Float, seed: Int, paint: Color, page: () -> Unit) {
    val w = size.width
    val h = size.height
    val pitch = 18.dp.toPx()
    // With every other row set half a dot over, a dot this big meets the three around it.
    val full = pitch * 0.66f
    val from = reach ?: when (seed % 4) {
        0 -> Offset(0f, 0f)
        1 -> Offset(w, 0f)
        2 -> Offset(w, h)
        else -> Offset(0f, h)
    }
    val far = farthest(from, w, h)
    val spread = 0.6f
    val random = Random(seed)
    val cols = (w / pitch).toInt() + 2
    val rows = (h / pitch).toInt() + 2
    val centres = ArrayList<Offset>(cols * rows)
    val grown = ArrayList<Float>(cols * rows)
    for (row in 0 until rows) {
        for (col in 0 until cols) {
            val c = Offset(col * pitch + (if (row % 2 == 1) pitch / 2f else 0f), row * pitch)
            val d = (hypot(c.x - from.x, c.y - from.y) / far + (random.nextFloat() - 0.5f) * 0.08f).coerceIn(0f, 1f)
            centres += c
            grown += ((progress - d * spread) / (1f - spread)).coerceIn(0f, 1f)
        }
    }
    centres.forEachIndexed { i, c ->
        val ink = (grown[i] * 1.35f).coerceAtMost(1f)
        if (ink > 0f) drawCircle(paint, full * ink, c)
    }
    drawIntoCanvas { canvas ->
        val bounds = Rect(0f, 0f, w, h)
        canvas.saveLayer(bounds, Paint())
        centres.forEachIndexed { i, c -> if (grown[i] > 0f) drawCircle(Color.Black, full * grown[i], c) }
        canvas.saveLayer(bounds, Paint().apply { blendMode = BlendMode.SrcIn })
        page()
        canvas.restore()
        canvas.restore()
    }
}

/**
 * The arrivals that move the page itself rather than reveal it. A poster slapped on: big and turned,
 * then down flat with a small bounce, turned one way or the other and held off centre as a hand
 * holds it. A notebook's page turned over onto the wall: from its spine on the left, or (every other
 * visit) over the top like a pad.
 */
internal fun Modifier.arriving(arrival: PageArrival, active: Boolean, seed: Int, progress: () -> Float): Modifier = when {
    !active -> this
    arrival == PageArrival.Slap -> graphicsLayer {
        val raw = progress().coerceIn(0f, 1f)
        val p = Motion.Kinetic.transform(raw)
        val s = 1.08f - 0.08f * p
        scaleX = s
        scaleY = s
        val way = if (seed % 2 == 0) -1f else 1f
        rotationZ = way * 2.5f * (1f - p)
        alpha = (raw * 3f).coerceAtMost(1f)
        transformOrigin = TransformOrigin(if (way < 0f) 0.42f else 0.58f, 0.35f)
    }
    arrival == PageArrival.Leaf -> graphicsLayer {
        val raw = progress().coerceIn(0f, 1f)
        val turn = 1f - raw
        cameraDistance = 14f * density
        if (seed % 2 == 0) {
            transformOrigin = TransformOrigin(0f, 0.5f)
            rotationY = Motion.LeafDegrees * turn
        } else {
            transformOrigin = TransformOrigin(0.5f, 0f)
            rotationX = -Motion.LeafDegrees * turn
        }
        alpha = (raw * 4f).coerceAtMost(1f)
    }
    else -> this
}

/** How far from [at] the farthest corner of a [w] × [h] page is: what a round reveal must reach. */
private fun farthest(at: Offset, w: Float, h: Float): Float =
    maxOf(hypot(at.x, at.y), hypot(w - at.x, at.y), hypot(at.x, h - at.y), hypot(w - at.x, h - at.y))

private const val TwoPi = 6.2832f

/** How an item arrives on a page that has just appeared. */
enum class ItemArrival {
    /** Fades up from below: rows. */
    Rise,

    /** Comes down big and turned, and lands flat with a bounce: covers, like stickers. */
    Slap,

    /** Swings in from the left on its own corner: titles of sections. */
    Swing,
}

/**
 * Arrives with its page by [style], [index] steps after the first item. Nothing moves under
 * reduced motion or once the page has settled, so a scroll never replays an entrance.
 */
@Composable
fun Modifier.arrive(index: Int = 0, style: ItemArrival = ItemArrival.Rise): Modifier {
    val reduced = LocalReducedMotion.current
    val entrance = LocalPageEntrance.current
    val animate = remember { !entrance.settled && !reduced }
    if (!animate) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            1f,
            tween(
                durationMillis = if (style == ItemArrival.Rise) Motion.EnterMs + 120 else Motion.KineticMs,
                delayMillis = index.coerceIn(0, Motion.StaggerCap) * Motion.StaggerMs,
                easing = if (style == ItemArrival.Rise) Motion.Enter else Motion.Kinetic,
            ),
        )
    }
    val turn = remember(index) { (Random(index * 7919).nextFloat() * 2f - 1f) * 6f }
    return graphicsLayer {
        val p = progress.value
        when (style) {
            ItemArrival.Rise -> {
                alpha = p.coerceIn(0f, 1f)
                translationY = (1f - p) * Motion.RiseDistance.toPx()
            }
            ItemArrival.Slap -> {
                alpha = (p * 2.5f).coerceIn(0f, 1f)
                val s = 1.25f - 0.25f * p
                scaleX = s
                scaleY = s
                rotationZ = turn * (1f - p)
            }
            ItemArrival.Swing -> {
                alpha = p.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0f, 1f)
                rotationZ = -8f * (1f - p)
                translationX = -(1f - p) * Motion.RiseDistance.toPx() * 1.5f
            }
        }
    }
}
