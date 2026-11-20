/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Dark only, by design. The page is black and stays black; the only colour on a screen comes from
 * the artwork that is playing, as a wash behind the content and a tint in the floating player.
 *
 * Secondary and tertiary text are white at an alpha rather than fixed greys, so the same three
 * text steps read correctly on the black page, on a raised surface and on an artwork wash.
 */
object ValenColors {
    /**
     * The scheme in force: every surface and mark below is a tone of the one seed it was made
     * from (see [Tonal]). It is snapshot state, so a new song re-tints whatever reads it.
     */
    var scheme by mutableStateOf(Tonal.scheme(Tonal.DefaultSeed, black = true))
        internal set

    val Background: Color get() = scheme.background

    /** One step up from the page: grouped rows, tiles, the frame an image loads into. */
    val Surface: Color get() = scheme.surface

    /** Two steps up: inputs, a selected or pressed row. */
    val Surface2: Color get() = scheme.surface2
    val Skeleton: Color get() = scheme.skeleton

    /** The one colour, soft: marks, the filled button, a rail's active part, the tag. */
    val Primary: Color get() = scheme.primary
    val OnPrimary: Color get() = scheme.onPrimary

    /** What is selected: the droplet, a chosen chip, the swipe behind the playing song. */
    val Container: Color get() = scheme.container
    val OnContainer: Color get() = scheme.onContainer

    val Text = Color(0xFFFFFFFF)
    val Text2 = Color(0x9EFFFFFF)
    val Text3 = Color(0x61FFFFFF)

    /** Ink on a white control (the primary button, a selected chip). */
    val OnLight = Color(0xFF000000)

    /** A separator between rows of one group: 1dp at 10% white. */
    val Hairline = Color(0x1AFFFFFF)

    /**
     * Floating chrome (side panel, player dock, phone bars, toasts) is liquid glass: the page
     * behind it blurred, made a little more vivid, then veiled by [GlassVeil] so white text holds
     * its contrast over any cover that scrolls under it. Chrome never sits on other chrome.
     */
    val GlassVeil: Color get() = scheme.glassVeil

    /** The same chrome where the platform cannot blur what is behind it: nearly opaque instead. */
    val GlassFill: Color get() = scheme.glassFill

    /** The hairline every glass shape carries, under its highlight. */
    val GlassEdge = Color(0x1FFFFFFF)

    /** Light caught on a glass rim, strongest where the light comes from (top left, or the pointer). */
    val GlassSpecular = Color(0x5CFFFFFF)

    /** The faint sheen across the top of a glass shape, and the sweep that crosses it on a new song. */
    val GlassSheen = Color(0x17FFFFFF)

    /** The droplet that marks where you are inside a glass panel: a brighter lens, not a fill. */
    val GlassLens: Color get() = scheme.container.copy(alpha = 0.78f)

    /**
     * The wall under everything: a fine grain of light and dark specks, like sprayed concrete or a
     * photocopied flyer. Glass blurs it away, which is what makes the glass read as smooth.
     */
    /** Tape holding a cover to the wall: warm paper, mostly see-through. */
    val Tape = Color(0x70EDE4D3)

    /** The back of a piece of paper, warm off-white: the unroll's roll, lit from above. */
    val Paper = Color(0xFFE2D9C7)
    val PaperLight = Color(0xFFF4EEE2)
    val PaperShade = Color(0xFF8C8475)

    val GrainLight = Color(0x09FFFFFF)
    val GrainDark = Color(0x12000000)

    /** White fills for controls that sit on glass or on an artwork wash. */
    val Fill = Color(0x1AFFFFFF)
    val FillStrong = Color(0x29FFFFFF)
    val Hover = Color(0x0FFFFFFF)

    /** Laid over a cover so a white mark on it reads whatever the cover looks like. */
    val CoverScrim = Color(0x85000000)

    val Danger = Color(0xFFFF5A5F)
    val Success = Color(0xFF3DD68C)

    /** Used until an artwork accent has been extracted, and whenever one cannot be. */
    val AccentFallback = Color(0xFFD9D9DE)
}
