/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import app.valen.ui.theme.LocalReducedMotion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.valen.ui.theme.Layout
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import kotlinx.coroutines.delay

/**
 * What the app has to say right now, floating at the top of the content.
 *
 * Two kinds, and they behave differently on purpose. A [confirmation] ("Added to queue") is a nod:
 * a tick, and it leaves by itself. Anything else is a provider's own message, in its own words,
 * and it stays until it is dismissed, because a failure that fades away has been hidden.
 */
@Composable
fun NoticeToast(
    message: String,
    confirmation: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (confirmation) {
        LaunchedEffect(message) {
            delay(Motion.ToastMs)
            onDismiss()
        }
    }
    GlassSurface(
        shape = Radii.cardShape,
        corner = Radii.card * 0.62f,
        modifier = modifier
            .widthIn(max = 560.dp)
            .heightIn(min = 48.dp),
    ) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = Space.step4, end = if (confirmation) Space.step5 else Space.step1),
    ) {
        if (confirmation) {
            DrawnTick(key = message, color = ValenColors.Success, modifier = Modifier.size(20.dp))
        } else {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = ValenColors.Danger,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = ValenColors.Text,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(horizontal = Space.step3, vertical = Space.step3),
        )
        if (!confirmation) {
            IconTap(icon = Icons.Rounded.Close, label = "Dismiss", onClick = onDismiss, iconSize = 18.dp)
        }
    }
    }
}

/** One service's failure, inside the results it failed to add to: compact, and named. */
@Composable
fun InlineError(message: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.step2),
        modifier = modifier.heightIn(min = Layout.hitTarget - Space.step2),
    ) {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = ValenColors.Danger,
            modifier = Modifier.size(16.dp),
        )
        Text(text = message, style = MaterialTheme.typography.bodySmall, color = ValenColors.Text2)
    }
}

/**
 * A tick drawn on, once the toast has poured out: one stroke, short leg then long, at a pen's pace,
 * each time [key] (the message) changes.
 */
@Composable
private fun DrawnTick(key: Any, color: Color, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val drawn = remember(key) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(key) {
        if (!reduced) drawn.animateTo(1f, tween(Motion.TickMs, delayMillis = Motion.TickDelayMs, easing = Motion.Standard))
    }
    Spacer(
        modifier.drawWithCache {
            val u = size.minDimension / 24f
            val path = Path().apply {
                moveTo(4.5f * u, 12.5f * u)
                lineTo(9.5f * u, 17.5f * u)
                lineTo(19.5f * u, 7f * u)
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val piece = Path()
            val pen = Stroke(2.6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            onDrawBehind {
                if (drawn.value <= 0f) return@onDrawBehind
                piece.reset()
                measure.getSegment(0f, measure.length * drawn.value, piece, true)
                drawPath(piece, color, style = pen)
            }
        },
    )
}

/**
 * The notice as chrome arrives: born as a drop its own height across in the middle of where it
 * will stand, falling into place on the bloom spring while it spreads to both ends on the quick
 * droplet spring — so it pours out into a bar of glass rather than sliding down — and its words
 * come in once it is mostly there. It leaves by pulling back into the drop. Only its reach is
 * animated (a layer clip), never its size, so the glass keeps seeing what is behind it.
 */
@Composable
fun PouredNotice(
    message: String?,
    confirmation: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = message != null,
        enter = if (reduced) fadeIn(Motion.fade()) else EnterTransition.None,
        exit = if (reduced) fadeOut(Motion.fade()) else ExitTransition.None,
        modifier = modifier,
    ) {
        // What it says and what kind it is stay as they were while it leaves: the flag is cleared
        // with the message, and a confirmation used to turn into an error for its last frames.
        var last by remember { mutableStateOf(message) }
        var lastConfirmation by remember { mutableStateOf(confirmation) }
        if (message != null) {
            last = message
            lastConfirmation = confirmation
        }
        val shown = last ?: return@AnimatedVisibility
        val across by transition.animateFloat(
            transitionSpec = { if (targetState == EnterExitState.Visible) Motion.liquidLead() else Motion.exit() },
            label = "noticeAcross",
        ) { if (it == EnterExitState.Visible || reduced) 1f else 0f }
        val fall by transition.animateFloat(
            transitionSpec = { if (targetState == EnterExitState.Visible) Motion.bloom() else Motion.exit() },
            label = "noticeFall",
        ) { if (it == EnterExitState.Visible || reduced) 1f else 0f }
        Box(
            Modifier
                .graphicsLayer {
                    translationY = -(1f - fall) * (size.height + Space.step4.toPx())
                    if (across < 0.999f) {
                        clip = true
                        shape = DropReveal(across)
                    } else {
                        clip = false
                    }
                },
        ) {
            Box(Modifier.graphicsLayer { alpha = ((across - 0.35f) / 0.65f).coerceIn(0f, 1f) }) {
                NoticeToast(message = shown, confirmation = lastConfirmation, onDismiss = onDismiss)
            }
        }
    }
}

/** A drop the shape's height across in its middle, spread to [across] of the way to both ends. */
private class DropReveal(private val across: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val drop = size.height / 2f
        val middle = size.width / 2f
        val half = drop + (middle - drop) * across.coerceAtLeast(0f)
        val left = (middle - half).coerceAtLeast(0f)
        val right = (middle + half).coerceAtMost(size.width)
        return Outline.Rounded(RoundRect(left, 0f, right, size.height, CornerRadius(drop, drop)))
    }
}
