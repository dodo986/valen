/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.getValue
import app.valen.core.RepeatMode
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import app.valen.ui.theme.Layout
import app.valen.ui.theme.ValenColors

/**
 * Previous, play/pause, next, as bare glyphs: no disc behind the play button. Size is the
 * hierarchy; on the stage the play glyph is the largest thing on screen after the cover.
 */
@Composable
fun TransportBar(
    isPlaying: Boolean,
    isBuffering: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    playSize: Dp = 36.dp,
    skipSize: Dp = 28.dp,
    gap: Dp = 4.dp,
    /** Shuffle and repeat, either side of the three; left out when [modes] is null. */
    modes: PlayModes? = null,
) {
    // With the modes, they stand at the two ends and the three keep their gap in the middle.
    Row(
        horizontalArrangement = if (modes != null) Arrangement.SpaceBetween else Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        if (modes != null) {
            ModeToggle(
                icon = Icons.Rounded.Shuffle,
                label = if (modes.shuffle) "Shuffle on" else "Shuffle off",
                on = modes.shuffle,
                onClick = modes.onShuffle,
                iconSize = skipSize * 0.78f,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        SkipTap(
            icon = Icons.Rounded.SkipPrevious,
            label = "Previous",
            onClick = onPrevious,
            forward = false,
            enabled = canSkipPrevious,
            iconSize = skipSize,
            size = max(Layout.hitTarget, skipSize + 16.dp),
        )
        val target = max(Layout.hitTarget, playSize + 16.dp)
        if (isBuffering) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(target)) {
                LiquidWait(modifier = Modifier.size(playSize * 0.66f))
            }
        } else {
            PlayToggle(isPlaying = isPlaying, onToggle = onToggle, iconSize = playSize, size = target)
        }
        SkipTap(
            icon = Icons.Rounded.SkipNext,
            label = "Next",
            onClick = onNext,
            forward = true,
            enabled = canSkipNext,
            iconSize = skipSize,
            size = max(Layout.hitTarget, skipSize + 16.dp),
        )
        }
        if (modes != null) {
            ModeToggle(
                icon = if (modes.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                label = when (modes.repeat) {
                    RepeatMode.OFF -> "Repeat off"
                    RepeatMode.ALL -> "Repeat all"
                    RepeatMode.ONE -> "Repeat one"
                },
                on = modes.repeat != RepeatMode.OFF,
                onClick = modes.onRepeat,
                iconSize = skipSize * 0.78f,
            )
        }
    }
}

/** Shuffle and repeat as the transport shows them: what they are now, and how to change them. */
class PlayModes(
    val shuffle: Boolean,
    val repeat: RepeatMode,
    val onShuffle: () -> Unit,
    val onRepeat: () -> Unit,
)

/**
 * A mode that is on or off: on is the primary tone with a mark under it (so it does not rest on
 * colour alone), off is quiet.
 */
@Composable
private fun ModeToggle(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit, iconSize: Dp) {
    val tint by animateColorAsState(if (on) ValenColors.Primary else ValenColors.Text2, Motion.tint(), label = "mode")
    Box(contentAlignment = Alignment.Center) {
        IconTap(icon = icon, label = label, onClick = onClick, tint = tint, iconSize = iconSize)
        if (on) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-4).dp)
                    .size(4.dp)
                    .background(ValenColors.Primary, CircleShape),
            )
        }
    }
}

/**
 * Play and pause as one control that turns into the other: the old glyph shrinks and turns away
 * as the new one grows in from a quarter turn, with the same squeeze-and-spring as every press.
 */
@Composable
fun PlayToggle(
    isPlaying: Boolean,
    onToggle: () -> Unit,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    size: Dp = max(Layout.hitTarget, iconSize + 16.dp),
) {
    val reduced = LocalReducedMotion.current
    val source = rememberInteraction()
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .pressScale(source)
            .tap(onClick = onToggle, shape = CircleShape, source = source),
    ) {
        AnimatedContent(
            targetState = isPlaying,
            transitionSpec = {
                if (reduced) {
                    fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
                } else {
                    (scaleIn(Motion.morph(), initialScale = 0.4f) + fadeIn(Motion.morph())) togetherWith
                        (scaleOut(Motion.exit(), targetScale = 0.4f) + fadeOut(Motion.exit()))
                }
            },
            label = "playToggle",
        ) { playing ->
            val turn = remember { Animatable(if (reduced) 0f else -90f) }
            LaunchedEffect(Unit) { if (!reduced) turn.animateTo(0f, Motion.squish()) }
            Icon(
                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = ValenColors.Text,
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer { rotationZ = turn.value },
            )
        }
    }
}

/**
 * Next and previous. A press kicks the glyph the way it leads — a few dp along its arrow, pushed
 * a little narrow — and it springs back with the press's overshoot, so a skip feels like a shove
 * rather than a switch. Pressed again quickly, the kick starts over from where it is.
 */
@Composable
internal fun SkipTap(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    forward: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = ValenColors.Text,
    iconSize: Dp = 26.dp,
    size: Dp = Layout.hitTarget,
) {
    val reduced = LocalReducedMotion.current
    val source = rememberInteraction()
    val kick = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val way = if (forward) 1f else -1f
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .pressScale(source)
            .tap(
                onClick = {
                    onClick()
                    if (!reduced) {
                        scope.launch {
                            kick.animateTo(1f, tween(Motion.KickMs, easing = Motion.Exit))
                            kick.animateTo(0f, Motion.squish())
                        }
                    }
                },
                shape = CircleShape,
                enabled = enabled,
                source = source,
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) tint else ValenColors.Text3,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    val k = kick.value
                    translationX = way * k * Motion.KickDistance.toPx()
                    scaleX = 1f - 0.1f * k
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (forward) 1f else 0f, 0.5f)
                },
        )
    }
}
