/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * Android blurs a layer from 12 (API 31). Below that the stage's depth of field keeps every word
 * crisp and carries the recede with opacity and tone ([LyricsDepth.alpha] with `blur = false`).
 */
internal actual object LyricsBlur {
    actual val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private val blurred = HashMap<Float, RenderEffect>()

    actual fun effect(radiusPx: Float): RenderEffect? {
        if (!supported) return null
        blurred[radiusPx]?.let { return it }
        val made = android.graphics.RenderEffect
            .createBlurEffect(radiusPx / 2f, radiusPx / 2f, Shader.TileMode.CLAMP)
            .asComposeRenderEffect()
        if (blurred.size < RADII) blurred[radiusPx] = made
        return made
    }

    private const val RADII = 64
}
