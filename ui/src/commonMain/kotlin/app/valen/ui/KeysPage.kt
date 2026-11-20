/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.valen.ui.components.rememberInteraction
import app.valen.ui.components.taped
import app.valen.ui.sound.RuleGap
import app.valen.ui.sound.ruledPage
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/** One line of the keys page: the keys, and what they do. */
private class KeyLine(val keys: List<String>, val does: String)

/** "or" between two keys that each do it, rather than two held together. */
private const val Or = "or"

private val Lines = listOf(
    KeyLine(listOf("Space"), "Play or pause"),
    KeyLine(listOf("←", "→"), "Back or on 5 seconds"),
    KeyLine(listOf("Ctrl", "←", "→"), "Previous or next song"),
    KeyLine(listOf("Ctrl", "↑", "↓"), "Louder or quieter"),
    KeyLine(listOf("M"), "Mute"),
    KeyLine(listOf("L"), "Like the song"),
    KeyLine(listOf("S"), "Shuffle"),
    KeyLine(listOf("R"), "Repeat: all, one, off"),
    KeyLine(listOf("Ctrl", "F", Or, "/"), "Search"),
    KeyLine(listOf("N"), "Now playing"),
    KeyLine(listOf("Q"), "The queue"),
    KeyLine(listOf("Esc"), "Close what is on top"),
    KeyLine(listOf("?"), "This page"),
)

/**
 * The keys, written up on a page of the notebook and slapped on the wall with two strips of tape,
 * over a dimmed room: `?` puts it up, and `?`, Esc or a click anywhere takes it down. One line of
 * the page is one key, so it reads like a list someone wrote by the speakers.
 */
@Composable
fun KeysPage(visible: Boolean, onDismiss: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val shown = remember { Animatable(0f) }
    // Each time it goes up, the tape is slapped on again.
    var putUp by remember { mutableStateOf(0) }
    LaunchedEffect(visible) {
        if (visible) {
            putUp++
            shown.animateTo(1f, if (reduced) Motion.fade() else tween(Motion.SlapMs, easing = Motion.Kinetic))
        } else {
            shown.animateTo(0f, Motion.exit())
        }
    }
    if (!visible && shown.value == 0f) return

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = shown.value.coerceIn(0f, 1f) }
            .background(ValenColors.Background.copy(alpha = 0.6f))
            .semantics { contentDescription = "Close the keys" }
            .clickable(interactionSource = rememberInteraction(), indication = null, onClick = onDismiss),
    ) {
        val p = shown.value
        Column(
            modifier = Modifier
                .padding(Space.step6)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    if (!reduced) {
                        // Slapped on: in bigger and turned, flat against the wall by the end, a
                        // degree off straight as a hand puts a page up. Taken down, it lifts away.
                        val grow = if (visible) Motion.SlapScale else Motion.LiftScale
                        val s = grow + (1f - grow) * p
                        scaleX = s
                        scaleY = s
                        rotationZ = -1.2f + (if (visible) Motion.SlapDegrees else 0f) * (1f - p)
                    }
                }
                .taped(putUp)
                .clickable(interactionSource = rememberInteraction(), indication = null, onClick = {})
                .ruledPage(top = RuleGap * 2),
        ) {
            Box(Modifier.fillMaxWidth().height(RuleGap * 2)) {
                Text(
                    "Keys",
                    style = MaterialTheme.typography.titleLarge,
                    color = ValenColors.Text,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = KeysAt, bottom = 4.dp),
                )
            }
            Lines.forEach { line ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().height(RuleGap).padding(start = KeysAt, end = Space.step5),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.width(KeysWidth),
                    ) {
                        line.keys.forEach { key ->
                            if (key == Or) {
                                Text("or", style = MaterialTheme.typography.labelMedium, color = ValenColors.Text3)
                            } else {
                                KeyCap(key)
                            }
                        }
                    }
                    Text(line.does, style = MaterialTheme.typography.bodyMedium, color = ValenColors.Text2, maxLines = 1)
                }
            }
            Box(Modifier.height(RuleGap))
        }
    }
}

/** A key as it sits on the keyboard: a small cap, its lower edge a step deeper than the rest. */
@Composable
private fun KeyCap(label: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(20.dp)
            .widthIn(min = 20.dp)
            .drawBehind {
                // The cap's side, showing under its face.
                drawLine(
                    ValenColors.Text3,
                    Offset(CapCorner.toPx(), size.height + 0.5.dp.toPx()),
                    Offset(size.width - CapCorner.toPx(), size.height + 0.5.dp.toPx()),
                    1.5.dp.toPx(),
                )
            }
            .background(ValenColors.Fill, CapShape)
            .border(1.dp, ValenColors.Text3.copy(alpha = 0.7f), CapShape)
            .padding(horizontal = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = ValenColors.Text, maxLines = 1)
    }
}

private val CapCorner = 5.dp
private val CapShape = RoundedCornerShape(CapCorner)

/** The keys start a little past the margin line, as writing does. */
private val KeysAt = 60.dp

/** The column the keys fill: the widest line (Ctrl F or /) and a gap after it. */
private val KeysWidth = 150.dp
