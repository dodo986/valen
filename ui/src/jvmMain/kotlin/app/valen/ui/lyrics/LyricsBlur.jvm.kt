/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter

/** Skia blurs a layer on every desktop; the stage's depth of field always has its blur here. */
internal actual object LyricsBlur {
    actual val supported: Boolean = true
    private val blurred = HashMap<Float, RenderEffect>()

    actual fun effect(radiusPx: Float): RenderEffect? {
        // A line settles through the same few radii again and again, and an ImageFilter is not free:
        // make one per radius and hand it back.
        blurred[radiusPx]?.let { return it }
        val made = ImageFilter.makeBlur(radiusPx / 2f, radiusPx / 2f, FilterTileMode.CLAMP).asComposeRenderEffect()
        if (blurred.size < RADII) blurred[radiusPx] = made
        return made
    }

    private const val RADII = 64
}
