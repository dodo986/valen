/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.valen.ui.theme.Glass
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.ValenColors
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What liquid glass sees through: the pages, recorded once a frame into a layer that every glass
 * shape draws a piece of, blurred and bent at its rim. The chrome is never inside the source, so
 * glass can never see glass — the "never stacked" rule is structural, not a convention.
 *
 * The approach (record the content once, let each glass shape draw the part under itself) is the
 * one Mochy Music's `GlassKit` takes with Android render nodes; this is Valen's own, on Compose's
 * multiplatform graphics layers, so the same glass draws on the desktop. See NOTICE.
 */
@Stable
class Backdrop {
    internal var layer: GraphicsLayer? = null

    /** Where the recorded content sits in the window. */
    internal var origin by mutableStateOf(Offset.Zero)

    /** Bumped each time the content is recorded again, so the glass over it redraws too. */
    internal var frame by mutableIntStateOf(0)
}

/** The backdrop the glass in this part of the tree sees through; null where there is none. */
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Records what this draws into [backdrop], and draws it as usual. */
@Composable
fun Modifier.backdropSource(backdrop: Backdrop): Modifier {
    val layer = rememberGraphicsLayer()
    backdrop.layer = layer
    return this
        .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
            // Written without being read here, or the source would invalidate itself forever.
            Snapshot.withoutReadObservation { backdrop.frame++ }
        }
}

/**
 * A piece of floating chrome in liquid glass: the page behind it blurred, a little more vivid,
 * bent at the rim like the edge of a lens, and veiled just enough for white text. Light catches
 * the rim from the top and, on a desktop, wherever the pointer rests. With [sweepKey] a band of
 * light crosses the glass once each time the key changes (the player uses the song).
 *
 * [corner] is the rounding the rim's refraction follows; null for a pill (half the height).
 * Where the platform cannot blur, the glass falls back to the near-opaque [ValenColors.GlassFill].
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = Radii.pillShape,
    corner: Dp? = null,
    tint: Color? = null,
    sweepKey: Any? = null,
    orbit: Boolean = false,
    /** A change of it makes the glass gulp (without the band of light): something dropped in. */
    gulpKey: Any? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val backdrop = LocalBackdrop.current
    val reduced = LocalReducedMotion.current
    // Under Battery Saver (or with animations off) the glass is the solid fill: a live blur is
    // re-rendered for every frame of the page beneath it, the dearest thing a phone draws here.
    val clear = backdrop != null && GlassBlur.supported && !reduced
    var position by remember { mutableStateOf(Offset.Zero) }
    var pointer by remember { mutableStateOf<Offset?>(null) }
    var lastPointer by remember { mutableStateOf(Offset.Zero) }
    val glint by animateFloatAsState(
        targetValue = if (pointer != null && !reduced) 1f else 0f,
        animationSpec = Motion.standard(),
        label = "glassGlint",
    )

    val sweep = remember { Animatable(1f) }
    val gulp = remember { Animatable(0f) }
    var sweepSeen by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(sweepKey) {
        val first = sweepSeen == null
        sweepSeen = sweepKey ?: Unit
        if (first || sweepKey == null || reduced) return@LaunchedEffect
        // A new song: the glass swallows it (a jelly squash that rings out) as light crosses it.
        launch {
            gulp.snapTo(1f)
            gulp.animateTo(0f, Motion.jelly())
        }
        sweep.snapTo(0f)
        sweep.animateTo(1f, tween(Motion.SweepMs, easing = Motion.Standard))
    }
    var gulpSeen by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(gulpKey) {
        val first = gulpSeen == null
        gulpSeen = gulpKey ?: Unit
        if (first || gulpKey == null || reduced) return@LaunchedEffect
        gulp.snapTo(0.75f)
        gulp.animateTo(0f, Motion.jelly())
    }
    // The light goes round [OrbitLaps] times when a song starts (or playing resumes), then rests:
    // following the screen's own frames for those laps only, drawn in its own layer (below). It used to run as an infinite
    // transition for the whole song, a frame at the screen's full rate on every screen with a
    // player, each one redrawing this surface over its blurred backdrop — most of what a phone
    // spent drawing while music played.
    val turn = remember { mutableFloatStateOf(0f) }
    var circling by remember { mutableStateOf(false) }
    LaunchedEffect(orbit && !reduced, sweepKey) {
        circling = orbit && !reduced
        if (!circling) return@LaunchedEffect
        // At the screen's own rate (smooth at 120 Hz) for the laps only, then no frames at all.
        val lapsNanos = OrbitLaps * Motion.OrbitMs * 1_000_000L
        var start = -1L
        var last = -1L
        while (true) {
            val done = withFrameNanos { now ->
                if (start < 0L) { start = now; last = now }
                turn.floatValue = (turn.floatValue + (now - last) / (Motion.OrbitMs * 1_000_000f)) % 1f
                last = now
                now - start >= lapsNanos
            }
            if (done) break
        }
        circling = false
    }
    val orbitStrength by animateFloatAsState(if (circling) 1f else 0f, Motion.tint(), label = "orbitStrength")

    val veil = if (tint == null) ValenColors.GlassVeil else tint.copy(alpha = 0.5f).compositeOver(ValenColors.GlassVeil)
    val solid = if (tint == null) ValenColors.GlassFill else tint.copy(alpha = 0.62f).compositeOver(ValenColors.GlassFill)

    Box(
        modifier = modifier
            .graphicsLayer {
                val g = gulp.value
                scaleX = 1f + 0.03f * g
                scaleY = 1f - 0.1f * g
            }
            .onGloballyPositioned { position = it.positionInRoot() }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.type != PointerType.Mouse) continue
                        if (event.type == PointerEventType.Exit) {
                            pointer = null
                        } else {
                            pointer = change.position
                            lastPointer = change.position
                        }
                    }
                }
            }
            .clip(shape)
            .then(if (clear) Modifier else Modifier.background(solid))
            .drawWithContent {
                drawContent()
                val outline = shape.createOutline(size, layoutDirection, this)
                drawGlassLight(outline, glint = if (glint > 0f) lastPointer else null, glintStrength = glint, sweep = sweep.value)
            },
    ) {
        if (clear) {
            GlassBackdrop(
                backdrop = backdrop!!,
                position = { position },
                corner = corner,
                veil = veil,
            )
        }
        content()
        if (orbitStrength > 0f) {
            Spacer(
                Modifier
                    .matchParentSize()
                    // Its own layer: a step of the orbit redraws this light, nothing under it.
                    .graphicsLayer()
                    .drawWithCache {
                        val outline = shape.createOutline(size, layoutDirection, this)
                        val measure = PathMeasure().apply { setPath(Path().apply { addOutline(outline) }, true) }
                        onDrawBehind { drawOrbit(outline, measure, turn.floatValue, orbitStrength) }
                    },
            )
        }
    }
}


/** How many times the light goes round when a song starts before it rests. */
private const val OrbitLaps = 2

/** The blurred, bent page under one glass shape, laid out past the shape so the blur has room. */
@Composable
private fun BoxScope.GlassBackdrop(
    backdrop: Backdrop,
    position: () -> Offset,
    corner: Dp?,
    veil: Color,
) {
    val density = LocalDensity.current
    val blur = with(density) { Glass.blur.toPx() }
    val bend = with(density) { Glass.refraction.toPx() }
    val depth = with(density) { Glass.rimDepth.toPx() }
    val cornerPx = corner?.let { with(density) { it.toPx() } }
    // The blur reaches about two radii, and the rim bends in from up to [bend] outside the shape.
    val bleed = (blur * 2f + bend).roundToInt()
    val cache = remember { EffectCache() }
    val vivid = remember {
        Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(Glass.vibrancy) }) }
    }
    Spacer(
        Modifier
            .matchParentSize()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints.fixed(constraints.maxWidth + bleed * 2, constraints.maxHeight + bleed * 2),
                )
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(-bleed, -bleed) }
            }
            .graphicsLayer {
                val inner = Size(size.width - bleed * 2f, size.height - bleed * 2f)
                val radius = (cornerPx ?: (inner.height / 2f)).coerceAtMost(inner.minDimension / 2f)
                renderEffect = cache.get(size, bleed.toFloat(), radius, blur, depth, bend)
            }
            .drawBehind {
                val layer = backdrop.layer ?: return@drawBehind
                @Suppress("UNUSED_VARIABLE")
                val frame = backdrop.frame
                val shift = backdrop.origin - (position() - Offset(bleed.toFloat(), bleed.toFloat()))
                drawIntoCanvas { it.saveLayer(Rect(Offset.Zero, size), vivid) }
                translate(shift.x, shift.y) { drawLayer(layer) }
                drawIntoCanvas { it.restore() }
                drawRect(veil)
            },
    )
}

/** The last glass effect, kept while the shape's size and rounding stay the same. */
private class EffectCache {
    private var key: List<Float>? = null
    private var effect: RenderEffect? = null

    fun get(size: Size, inset: Float, radius: Float, blur: Float, depth: Float, bend: Float): RenderEffect? {
        val next = listOf(size.width, size.height, inset, radius, blur, depth, bend)
        if (next != key) {
            key = next
            effect = GlassBlur.effect(
                width = size.width,
                height = size.height,
                inset = inset,
                corner = radius,
                blur = blur,
                depth = depth,
                bend = bend,
                dispersion = Glass.dispersion,
            )
        }
        return effect
    }
}

/**
 * Light on glass: the hairline, a highlight along the rim that is brightest at the top (and a
 * faint one at the bottom, where light inside the glass comes back out), a soft sheen across the
 * top, the pointer's glint and the sweep of a new song.
 */
internal fun DrawScope.drawGlassLight(
    outline: Outline,
    glint: Offset? = null,
    glintStrength: Float = 0f,
    sweep: Float = 1f,
) {
    val hair = 1.dp.toPx()
    drawOutline(outline, ValenColors.GlassEdge, style = Stroke(hair * 2f))

    val h = size.height
    val reach = (64.dp.toPx() / h).coerceIn(0.08f, 0.5f)
    val rim = Brush.verticalGradient(
        0f to ValenColors.GlassSpecular,
        reach to Color.Transparent,
        1f - reach to Color.Transparent,
        1f to ValenColors.GlassSpecular.copy(alpha = 0.32f),
    )
    drawOutline(outline, rim, style = Stroke(hair * 2.4f))

    val sheenDepth = minOf(h * 0.55f, 120.dp.toPx())
    drawRect(
        Brush.verticalGradient(
            0f to ValenColors.GlassSheen,
            1f to Color.Transparent,
            startY = 0f,
            endY = sheenDepth,
        ),
    )

    if (glint != null && glintStrength > 0f) {
        val radius = Glass.glintReach.toPx()
        val light = Brush.radialGradient(
            0f to ValenColors.GlassSpecular.copy(alpha = glintStrength),
            1f to Color.Transparent,
            center = glint,
            radius = radius,
        )
        drawOutline(outline, light, style = Stroke(hair * 3f))
        drawRect(
            Brush.radialGradient(
                0f to ValenColors.GlassSheen.copy(alpha = ValenColors.GlassSheen.alpha * glintStrength),
                1f to Color.Transparent,
                center = glint,
                radius = radius * 0.8f,
            ),
        )
    }

    if (sweep < 1f) {
        val band = max(size.width * 0.22f, 120.dp.toPx())
        val x = -band + (size.width + band * 2f) * sweep
        val fade = if (sweep < 0.15f) sweep / 0.15f else if (sweep > 0.85f) (1f - sweep) / 0.15f else 1f
        val light = Brush.linearGradient(
            0f to Color.Transparent,
            0.5f to ValenColors.GlassSheen.copy(alpha = 0.16f * fade),
            1f to Color.Transparent,
            start = Offset(x - band, 0f),
            end = Offset(x + band * 0.6f, size.height),
        )
        drawRect(light)
        val edge = Brush.linearGradient(
            0f to Color.Transparent,
            0.5f to ValenColors.GlassSpecular.copy(alpha = 0.9f * fade),
            1f to Color.Transparent,
            start = Offset(x - band * 0.5f, 0f),
            end = Offset(x + band * 0.5f, 0f),
        )
        drawOutline(outline, edge, style = Stroke(hair * 2.4f))
    }
}

/**
 * A point of light travelling round the rim, the way light runs round the edge of a glass that
 * is turned slowly in the hand: two of them, half a turn apart, the second fainter.
 */
private fun DrawScope.drawOrbit(outline: Outline, measure: PathMeasure, phase: Float, strength: Float) {
    val length = measure.length
    if (length <= 0f) return
    val reach = minOf(size.height * 1.6f, 140.dp.toPx())
    for ((offset, power) in listOf(0f to 1f, 0.5f to 0.45f)) {
        val at = measure.getPosition(((phase + offset) % 1f) * length)
        val light = Brush.radialGradient(
            0f to ValenColors.GlassSpecular.copy(alpha = strength * power),
            0.5f to ValenColors.GlassSpecular.copy(alpha = 0.25f * strength * power),
            1f to Color.Transparent,
            center = at,
            radius = reach,
        )
        drawOutline(outline, light, style = Stroke(1.dp.toPx() * 3f))
    }
}

/**
 * The droplet that marks where you are inside a glass panel: a smaller, brighter lens with its
 * own rim light. [stretch] is how far it is drawn out along its path while moving (0 at rest).
 */
internal fun DrawScope.drawDroplet(
    rect: Rect,
    stretch: Float = 0f,
    vertical: Boolean = true,
    alpha: Float = 1f,
) {
    if (alpha <= 0f || rect.width <= 0f || rect.height <= 0f) return
    val grow = 1f + stretch
    val thin = 1f - stretch * 0.22f
    val w = if (vertical) rect.width * thin else rect.width * grow
    val h = if (vertical) rect.height * grow else rect.height * thin
    val c = rect.center
    val r = Rect(c.x - w / 2f, c.y - h / 2f, c.x + w / 2f, c.y + h / 2f)
    val radius = minOf(r.width, r.height) / 2f
    val round = RoundRect(r, CornerRadius(radius, radius))
    val path = Path().apply { addRoundRect(round) }
    drawPath(path, ValenColors.GlassLens.copy(alpha = ValenColors.GlassLens.alpha * alpha))
    clipPath(path) {
        drawRect(
            Brush.verticalGradient(
                0f to ValenColors.GlassSheen.copy(alpha = ValenColors.GlassSheen.alpha * alpha * 1.4f),
                0.6f to Color.Transparent,
                startY = r.top,
                endY = r.bottom,
            ),
            topLeft = r.topLeft,
            size = r.size,
        )
    }
    val hair = 1.dp.toPx()
    drawPath(
        path,
        Brush.verticalGradient(
            0f to ValenColors.GlassSpecular.copy(alpha = 0.75f * alpha),
            0.45f to Color.Transparent,
            0.8f to Color.Transparent,
            1f to ValenColors.GlassSpecular.copy(alpha = 0.3f * alpha),
            startY = r.top,
            endY = r.bottom,
        ),
        style = Stroke(hair),
    )
}

/**
 * The platform half of liquid glass: whether this device can blur what is behind a layer, and
 * the blur-then-bend effect itself (null where only part of it, or none, is available).
 */
internal expect object GlassBlur {
    val supported: Boolean

    fun effect(
        width: Float,
        height: Float,
        inset: Float,
        corner: Float,
        blur: Float,
        depth: Float,
        bend: Float,
        dispersion: Float,
    ): RenderEffect?
}

/**
 * The rim of the lens. Inside a band [depth] deep along the edge of the rounded rectangle
 * [rect], what is drawn is taken from further out along the edge's normal — up to [bend] at the
 * very edge, easing off toward the middle — so the page appears to curve over the rim the way it
 * does through the edge of a thick lens. Red and blue are taken a little further and a little
 * nearer than green, for a hint of prism. The middle of the glass is left untouched.
 *
 * One source for both platforms: Skia's SkSL on the desktop, AGSL on Android 13 and later.
 */
internal const val LensShader = """
uniform shader content;
uniform float4 rect;
uniform float radius;
uniform float depth;
uniform float bend;
uniform float disperse;

float box(float2 p, float2 c, float2 h, float r) {
    float2 q = abs(p - c) - h + float2(r, r);
    return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 p) {
    float2 c = (rect.xy + rect.zw) * 0.5;
    float2 h = (rect.zw - rect.xy) * 0.5;
    float d = box(p, c, h, radius);
    if (d >= 0.0 || d < -depth) {
        return content.eval(p);
    }
    float t = 1.0 + d / depth;
    float k = t * t * t;
    float2 g = float2(
        box(p + float2(1.0, 0.0), c, h, radius) - box(p - float2(1.0, 0.0), c, h, radius),
        box(p + float2(0.0, 1.0), c, h, radius) - box(p - float2(0.0, 1.0), c, h, radius)
    );
    float len = length(g);
    if (len < 0.0001) {
        return content.eval(p);
    }
    float2 o = (g / len) * bend * k;
    half4 base = content.eval(p + o);
    half red = content.eval(p + o * (1.0 + disperse)).r;
    half blue = content.eval(p + o * (1.0 - disperse)).b;
    return half4(red, base.g, blue, base.a);
}
"""
