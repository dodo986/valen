/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import androidx.compose.ui.unit.Density
import app.valen.ui.theme.Motion
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The stage's depth of field, as arithmetic: how far a line stands from the song, and what that
 * does to it. The line being sung is at 1 — crisp, full size, in the primary's light. Each line
 * away softens a little ([Motion.LyricDepthNearStep]); a line behind the song settles back a
 * touch quicker ([Motion.LyricDepthPastStep]), so the stage reads as moving forward. The floor is
 * [Motion.LyricDepthFloor]: even the words far out keep enough tone to be read as words.
 *
 * Kept out of the composable so it can be checked without a screen, and so the same distance
 * gives the same focus every frame — nothing here is remembered between frames.
 */
internal object LyricsDepth {
    /** How near the song a line [distance] lines away stands: 1 at the song, down to the floor. */
    fun focus(distance: Int): Float = when {
        distance == 0 -> 1f
        distance < 0 -> max(Motion.LyricDepthFloor, 1f + distance * Motion.LyricDepthPastStep)
        else -> max(Motion.LyricDepthFloor, 1f - distance * Motion.LyricDepthNearStep)
    }

    /**
     * The share of its own tone a line keeps at [focus], before the words' own 40% dim. A line
     * whose layer is not blurred ([blur] false: Android below 12, reduced motion) recedes a little
     * further, so the near/far step still reads without the blur.
     */
    fun alpha(focus: Float, blur: Boolean): Float {
        val near = if (blur) Motion.LyricDepthAlphaNear else Motion.LyricDepthAlphaNoBlur
        return (near + (1f - near) * focus).coerceIn(0f, 1f)
    }

    /**
     * How far a line at [focus] leans toward the page's own tone when it cannot be blurred, 0–1
     * ([Motion.LyricDepthTint] at the floor). The gentle tint half of the fallback.
     */
    fun tint(focus: Float, blur: Boolean): Float =
        if (blur) 0f else ((1f - focus) * Motion.LyricDepthTint).coerceIn(0f, 1f)

    /**
     * The blur radius for a line at [focus], in px, snapped to a quarter of a pixel: a settling
     * line passes through the same radii again and again, and a blur made once per radius is a
     * blur made once.
     */
    fun blurPx(focus: Float, density: Density): Float {
        if (focus >= 1f) return 0f
        val far = with(density) { Motion.LyricBlurFarDp.toPx() }
        return ((1f - focus) * far * 4f).roundToInt() / 4f
    }

    /**
     * Whether this line is near enough to the song to spend a blurred layer on: within
     * [Motion.LyricBlurLines] of it. Further out the recede is opacity and tone only, so a long
     * song never keeps a blurred layer for every line it has.
     */
    fun blurs(distance: Int, enabled: Boolean): Boolean =
        enabled && distance >= -Motion.LyricBlurLines && distance <= Motion.LyricBlurLines
}
