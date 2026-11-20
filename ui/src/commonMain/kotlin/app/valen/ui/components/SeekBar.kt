/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.util.lerp
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.core.tween
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import app.valen.ui.theme.timeStyle
import app.valen.ui.util.formatTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Position on the stage: a thick white rail that grows under a pointer or a finger, the time
 * elapsed on the left and the time left, counting down, on the right. There is no thumb; the end
 * of the fill is the handle. A pointer resting on the rail turns the times into where a click
 * would land, in full white, and they ease back when it leaves.
 */
@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val seek = rememberSeekState(positionMs, durationMs)
    Column(modifier = modifier.fillMaxWidth()) {
        ProgressRail(
            value = seek.fraction,
            enabled = enabled && durationMs > 0L,
            onScrub = { seek.scrub = it },
            onCommit = { seek.commit(it, onSeek) },
            onHover = { seek.hover = it },
            rest = 6.dp,
            active = 10.dp,
            // Scrubbing raises a drop of the paint with the time in it.
            drop = { fraction -> formatTime((fraction * durationMs).toLong()) },
        )
        val tone by animateColorAsState(if (seek.previewing) ValenColors.Text else ValenColors.Text2, Motion.press(), label = "seekTimes")
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatTime(seek.shownMs),
                style = timeStyle(large = true),
                color = tone,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (durationMs > 0L) "-" + formatTime((durationMs - seek.shownMs).coerceAtLeast(0L)) else formatTime(0L),
                style = timeStyle(large = true),
                color = tone,
            )
        }
    }
}

/**
 * Position inside the floating player: elapsed, a thin rail, total. All on one line, because the
 * dock has one line to give.
 */
@Composable
fun InlineSeek(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    showTimes: Boolean = true,
) {
    val seek = rememberSeekState(positionMs, durationMs)
    val tone by animateColorAsState(if (seek.previewing) ValenColors.Text else ValenColors.Text2, Motion.press(), label = "dockTimes")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.step3),
        modifier = modifier,
    ) {
        if (showTimes) {
            Text(
                text = formatTime(seek.shownMs),
                style = timeStyle(),
                color = tone,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 30.dp),
            )
        }
        ProgressRail(
            value = seek.fraction,
            enabled = durationMs > 0L,
            onScrub = { seek.scrub = it },
            onCommit = { seek.commit(it, onSeek) },
            onHover = if (showTimes) ({ seek.hover = it }) else null,
            modifier = Modifier.weight(1f),
            rest = 4.dp,
            active = 6.dp,
        )
        if (showTimes) {
            Text(
                text = formatTime(durationMs),
                style = timeStyle(),
                color = ValenColors.Text2,
                modifier = Modifier.widthIn(min = 30.dp),
            )
        }
    }
}

/**
 * What a seek rail shows: the song's place, a drag's preview, a resting pointer's preview, and —
 * between letting go and the player reporting the new place — the place asked for, so the rail
 * never flicks back to where it was first.
 */
private class SeekState(
    private val position: () -> Long,
    private val duration: () -> Long,
) {
    var scrub by mutableStateOf<Float?>(null)

    /** Where the pointer is over the rail, and whether it has rested there long enough to preview. */
    var hover by mutableStateOf<Float?>(null)
    var rested by mutableStateOf(false)

    /** The place asked for by the last seek, until the player reports it. */
    var held by mutableStateOf<Long?>(null)

    private val placeMs: Long
        get() = held?.takeIf { abs(position() - it) > Motion.SeekNearMs } ?: position()

    private val preview: Float?
        get() = scrub ?: hover?.takeIf { rested }

    val fraction: Float
        get() {
            val d = duration()
            return scrub ?: if (d > 0L) (placeMs.toFloat() / d).coerceIn(0f, 1f) else 0f
        }

    /** A drag, or a pointer resting on the rail: the times say where that would be. */
    val previewing: Boolean
        get() = preview != null

    val shownMs: Long
        get() {
            val d = duration()
            val at = preview
            return if (at != null && d > 0L) (at * d).toLong() else placeMs
        }

    fun commit(fraction: Float, onSeek: (Long) -> Unit) {
        scrub = null
        val d = duration()
        if (d > 0L) {
            val at = (fraction * d).toLong()
            held = at
            onSeek(at)
        }
    }
}

@Composable
private fun rememberSeekState(positionMs: Long, durationMs: Long): SeekState {
    val position by rememberUpdatedState(positionMs)
    val duration by rememberUpdatedState(durationMs)
    val state = remember { SeekState({ position }, { duration }) }
    // The pointer has to rest a moment before the times change: crossing the rail changes nothing.
    val over = state.hover != null
    LaunchedEffect(over) {
        state.rested = false
        if (over) {
            delay(Motion.PreviewHoverMs)
            state.rested = true
        }
    }
    // Let go of the asked-for place once the player is there, or after a while regardless.
    val at = state.held
    LaunchedEffect(at, positionMs) {
        if (at != null && abs(positionMs - at) <= Motion.SeekNearMs) state.held = null
    }
    LaunchedEffect(at) {
        if (at == null) return@LaunchedEffect
        delay(Motion.SeekHoldMs)
        state.held = null
    }
    return state
}

/**
 * The one rail behind seek and volume. It reports every drag frame through [onScrub] and the
 * final value through [onCommit], so a caller can preview without writing anything until release.
 * [rest] thick normally, [active] under a pointer or while dragging.
 *
 * It is painted, not slid: when the value jumps (a tap further on, a new song) the paint runs to
 * its new place on the run spring, a bead gathering at its head while it runs, rather than
 * teleporting; a song's steady ticks and a drag follow exactly. A pointer resting on it lights the
 * track up to where it would land; [onHover] is told where that is.
 */
@Composable
internal fun ProgressRail(
    value: Float,
    enabled: Boolean,
    onScrub: (Float?) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 28.dp,
    rest: Dp = 4.dp,
    active: Dp = 6.dp,
    onHover: ((Float?) -> Unit)? = null,
    /** While dragged, a drop of paint rises above the finger with this label in it (the stage). */
    drop: ((Float) -> String)? = null,
) {
    // Pointer handlers outlive recompositions. In particular, a song change replaces the
    // callback that converts the fraction to milliseconds using that song's duration.
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentOnCommit by rememberUpdatedState(onCommit)
    val currentOnHover by rememberUpdatedState(onHover)
    val reduced = LocalReducedMotion.current
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(value) }
    val source = rememberInteraction()
    val hovered by source.collectIsHoveredAsState()
    val trackHeight by animateDpAsState(
        targetValue = if (enabled && (dragging || hovered)) active else rest,
        animationSpec = Motion.standard(),
        label = "railHeight",
    )
    // Where a resting pointer would put it, and how lit that part of the track is.
    var pointerAt by remember { mutableStateOf<Float?>(null) }
    var lastPointer by remember { mutableStateOf(0f) }
    val ghost by animateFloatAsState(
        targetValue = if (enabled && pointerAt != null && !dragging) 1f else 0f,
        animationSpec = Motion.standard(),
        label = "railGhost",
    )

    // The paint: exact while it follows, a run on the run spring when it jumps. A run lives
    // in its own job, so the song's steady ticks (four a second) never restart it: it lands where
    // it was sent and then takes the latest place, a tick at most away.
    val paint = remember { Animatable(value) }
    val runs = rememberCoroutineScope()
    val run = remember { arrayOfNulls<Job>(1) }
    val latest by rememberUpdatedState(value)
    LaunchedEffect(value, dragging) {
        val running = run[0]?.isActive == true
        when {
            dragging || reduced -> {
                run[0]?.cancel()
                paint.snapTo(value)
            }
            running && abs(value - paint.targetValue) <= Motion.RunFrom -> Unit
            abs(value - paint.value) > Motion.RunFrom || running -> run[0] = runs.launch {
                paint.animateTo(value, Motion.run())
                paint.snapTo(latest)
            }
            else -> paint.snapTo(value)
        }
    }

    fun fractionOf(x: Float, width: Int): Float =
        if (width <= 0) 0f else (x / width).coerceIn(0f, 1f)

    // The drop: up while dragged (blooming out of the rail), its body following the finger on
    // the droplet's slow spring while its neck stays on the paint's head.
    val measurer = rememberTextMeasurer()
    val dropStyle = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum", color = ValenColors.OnPrimary)
    val dropUp = remember { Animatable(0f) }
    val dropAt = remember { androidx.compose.runtime.mutableFloatStateOf(value) }
    val showDrop = drop != null && dragging && !reduced
    val target by rememberUpdatedState(dragValue)
    LaunchedEffect(showDrop) {
        if (showDrop) {
            dropAt.floatValue = dragValue
            dropUp.animateTo(1f, Motion.bloom())
        } else {
            dropUp.animateTo(0f, tween(Motion.ExitMs, easing = Motion.Exit))
        }
    }
    // The body follows on the slow droplet spring, stepped on the display's frames while dragged
    // (an animation restarted on every pointer move never got going).
    LaunchedEffect(showDrop) {
        if (!showDrop) return@LaunchedEffect
        val trail = Motion.liquidTrail<Float>()
        var velocity = 0f
        var last = -1L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (last < 0L) last = now
                var left = ((now - last) / 1_000_000_000f).coerceAtMost(0.25f)
                last = now
                var x = dropAt.floatValue
                while (left > 0f) {
                    val dt = minOf(left, 0.004f)
                    left -= dt
                    val pull = -trail.stiffness * (x - target) - 2f * trail.dampingRatio * kotlin.math.sqrt(trail.stiffness) * velocity
                    velocity += pull * dt
                    x += velocity * dt
                }
                dropAt.floatValue = x
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .hoverable(source, enabled)
            .then(
                if (!enabled) Modifier else Modifier
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull() ?: continue
                                if (change.type != PointerType.Mouse) continue
                                val at = if (event.type == PointerEventType.Exit) null else fractionOf(change.position.x, size.width)
                                if (at != null) lastPointer = at
                                if (at != pointerAt) {
                                    pointerAt = at
                                    currentOnHover?.invoke(at)
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val fraction = fractionOf(offset.x, size.width)
                            currentOnScrub(fraction)
                            currentOnCommit(fraction)
                            currentOnScrub(null)
                        }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset ->
                                dragging = true
                                dragValue = fractionOf(offset.x, size.width)
                                currentOnScrub(dragValue)
                            },
                            onDragEnd = {
                                dragging = false
                                currentOnCommit(dragValue)
                                currentOnScrub(null)
                            },
                            onDragCancel = {
                                dragging = false
                                currentOnScrub(null)
                            },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                dragValue = fractionOf(change.position.x, size.width)
                                currentOnScrub(dragValue)
                            },
                        )
                    },
            ),
    ) {
        val cy = size.height / 2f
        val track = trackHeight.toPx()
        val radius = CornerRadius(track / 2f, track / 2f)
        drawRoundRect(
            color = ValenColors.FillStrong,
            topLeft = Offset(0f, cy - track / 2f),
            size = Size(size.width, track),
            cornerRadius = radius,
        )
        if (ghost > 0f) {
            // The track lit up to where a click would land, under the paint.
            drawRoundRect(
                color = ValenColors.Fill.copy(alpha = ValenColors.Fill.alpha * ghost),
                topLeft = Offset(0f, cy - track / 2f),
                size = Size((size.width * lastPointer).coerceAtLeast(track), track),
                cornerRadius = radius,
            )
        }
        val shown = paint.value.coerceIn(0f, 1f)
        val filled = size.width * shown
        val ink: Color = if (enabled) ValenColors.Primary else ValenColors.Text3
        if (filled > 0f) {
            // Never narrower than it is tall, so the first seconds of a song show as a dot, not a sliver.
            val reach = filled.coerceAtLeast(track)
            drawRoundRect(
                color = ink,
                topLeft = Offset(0f, cy - track / 2f),
                size = Size(reach, track),
                cornerRadius = radius,
            )
            // Running paint gathers at its front in a bead, which flattens out as it stops.
            val bead = (abs(paint.velocity) / Motion.BeadSpeed).coerceIn(0f, 1f)
            if (bead > 0.02f) {
                drawCircle(ink, radius = track / 2f * (1f + Motion.BeadSwell * bead), center = Offset(reach - track / 2f, cy))
            }
        }
        val up = dropUp.value
        if (drop != null && up > 0.01f) {
            val label = measurer.measure(drop(if (dragging) dragValue else shown), dropStyle)
            val padX = 12.dp.toPx()
            val padY = 6.dp.toPx()
            val w = label.size.width + padX * 2f
            val h = label.size.height + padY * 2f
            val base = Offset((size.width * shown).coerceAtLeast(track / 2f), cy)
            val rest = Offset(size.width * dropAt.floatValue, cy - track / 2f - Motion.DropRise.toPx() - h / 2f)
            // Rising out of the rail as it blooms, and falling back into it when let go.
            val body = Offset(lerp(base.x, rest.x, up.coerceIn(0f, 1f)), lerp(base.y, rest.y, up))
            val s = up.coerceAtLeast(0f)
            val bw = w * s
            val bh = h * s
            // The neck: wide where it leaves the bubble, thinning to the paint's head.
            val neck = Path().apply {
                val top = body.y + bh / 2f - bh * 0.2f
                val half = bh * 0.32f
                moveTo(body.x - half, top)
                quadraticTo(body.x - half * 0.3f, (top + base.y) / 2f, base.x - track * 0.22f, base.y)
                lineTo(base.x + track * 0.22f, base.y)
                quadraticTo(body.x + half * 0.3f, (top + base.y) / 2f, body.x + half, top)
                close()
            }
            drawPath(neck, ink)
            drawRoundRect(
                color = ink,
                topLeft = Offset(body.x - bw / 2f, body.y - bh / 2f),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(bh / 2f, bh / 2f),
            )
            if (s > 0.6f) {
                drawText(
                    label,
                    topLeft = Offset(body.x - label.size.width / 2f, body.y - label.size.height / 2f),
                    alpha = ((s - 0.6f) / 0.4f).coerceIn(0f, 1f),
                )
            }
        }
    }
}
