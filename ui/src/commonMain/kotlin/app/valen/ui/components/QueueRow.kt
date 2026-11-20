/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.valen.core.Track
import app.valen.ui.menu.MenuOrigin
import app.valen.ui.menu.MoreButton
import app.valen.ui.menu.rememberSongMenuOpener
import app.valen.ui.menu.songMenuOnRightClick
import app.valen.ui.theme.Layout
import app.valen.ui.theme.LocalChrome
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import app.valen.ui.theme.timeStyle
import app.valen.ui.util.formatTime
import kotlin.math.PI
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin

/** Every queue row is this tall; a drag moves a song one place each time it crosses it. */
val QueueRowHeight = Layout.rowHeight

/**
 * A queue row. Songs that already played are dimmed; songs still to come can be dragged by the
 * handle on the right, and removed. The row body stays a tap target: tapping plays that song.
 */
@Composable
fun QueueRow(
    track: Track,
    dimmed: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    reorderable: Boolean = false,
    onDragStarted: () -> Unit = {},
    onDragStopped: () -> Unit = {},
    dragDelta: (Float) -> Unit = {},
    /** This row's place in the queue, for its menu (right click, long press); none without it. */
    menuIndex: Int? = null,
    /** Held by its handle: it comes off the wall like a strip of paper, and is slapped back. */
    held: Boolean = false,
) {
    val compact = LocalChrome.current.compact
    val origin = menuIndex?.let { MenuOrigin.Queue(it) }
    val reduced = LocalReducedMotion.current
    val lift = remember { Animatable(0f) }
    LaunchedEffect(held) {
        when {
            reduced -> lift.snapTo(if (held) 1f else 0f)
            held -> lift.animateTo(1f, Motion.lean())
            // Let go: slapped flat, a touch past and back, like the paper it is.
            lift.value > 0f -> lift.animateTo(0f, tween(Motion.KineticMs, easing = Motion.Kinetic))
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(QueueRowHeight)
            .graphicsLayer {
                val l = lift.value
                val s = 1f + (Motion.HeldScale - 1f) * l
                scaleX = s
                scaleY = s
                rotationZ = -Motion.HeldDegrees * l
            }
            .drawBehind {
                // Lifted, the strip catches the light: the raised fill, in the row's own shape.
                val l = lift.value.coerceIn(0f, 1f)
                if (l > 0f) {
                    drawOutline(
                        Radii.rowShape.createOutline(size, layoutDirection, this),
                        ValenColors.Fill.copy(alpha = ValenColors.Fill.alpha * l),
                    )
                }
            }
            .then(if (origin != null) Modifier.songMenuOnRightClick(track, origin) else Modifier)
            .tap(
                onClick = onPlay,
                shape = Radii.rowShape,
                onLongClick = if (origin != null) rememberSongMenuOpener(track, origin) else null,
            )
            .padding(start = Space.step2),
    ) {
        ArtworkSquare(
            url = track.artworkUrl,
            size = 48.dp,
            modifier = Modifier.graphicsLayer { alpha = if (dimmed) 0.5f else 1f },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Space.step3)
                .graphicsLayer { alpha = if (dimmed) 0.5f else 1f },
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                color = ValenColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${track.artist}  ·  ${track.provider.short}",
                style = MaterialTheme.typography.bodySmall,
                color = ValenColors.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!compact && track.durationMs > 0L) {
            Text(
                text = formatTime(track.durationMs),
                style = timeStyle(),
                color = ValenColors.Text3,
                modifier = Modifier.padding(horizontal = Space.step3),
            )
        }
        IconTap(
            icon = Icons.Rounded.Close,
            label = "Remove from queue",
            onClick = onRemove,
            tint = ValenColors.Text3,
            iconSize = 18.dp,
        )
        if (reorderable) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(Layout.hitTarget)
                    .dragHandle(onDragStarted, onDragStopped, dragDelta),
            ) {
                Icon(
                    imageVector = Icons.Rounded.DragIndicator,
                    contentDescription = "Reorder",
                    tint = ValenColors.Text3,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Drag on the handle only: the row body stays a tap target. */
private fun Modifier.dragHandle(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDelta: (Float) -> Unit,
): Modifier = pointerInput(Unit) {
    detectDragGestures(
        onDragStart = { onStart() },
        onDragEnd = { onStop() },
        onDragCancel = { onStop() },
        onDrag = { change, dragAmount ->
            change.consume()
            onDelta(dragAmount.y)
        },
    )
}

/**
 * Three bars, moving only while the song is actually playing. They step on a timer
 * ([Motion.PlayingBarsStepMs], like a level meter) and are read in the draw alone: an infinite
 * transition here asked for every frame of the display while a song played, on whatever page
 * showed the playing row and under the stage too (60 frames a second on the emulator, the
 * whole window drawn each time).
 */
@Composable
fun PlayingBars(playing: Boolean, tint: Color, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val moving = playing && !reduced
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        val step = (2 * PI).toFloat() * Motion.PlayingBarsStepMs / Motion.PlayingBarsMs
        while (true) {
            delay(Motion.PlayingBarsStepMs.toLong())
            phase.floatValue = (phase.floatValue + step) % (2 * PI).toFloat()
        }
    }
    Canvas(modifier) {
        val barWidth = size.width / 5f
        val gap = barWidth
        val at = phase.floatValue
        Bases.forEachIndexed { index, base ->
            val level = if (moving) base * (0.45f + 0.55f * abs(sin(at + index * 1.7f))) else base * 0.6f
            val height = size.height * level
            drawRoundRect(
                color = tint,
                topLeft = Offset(index * (barWidth + gap), (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

private val Bases = floatArrayOf(0.55f, 1f, 0.75f)
