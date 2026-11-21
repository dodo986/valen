/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale

/**
 * A soft, feathered pool of one colour, [width] by [height] across: a radial fade squeezed into a
 * shape by scaling the canvas, so the light is round in both directions and fades to nothing at
 * every edge. The stage's light field, its glints and a pause's halo are all this one thing, and
 * none of them has an edge a person could point at.
 */
internal fun DrawScope.pool(center: Offset, width: Float, height: Float, color: Color, strength: Float) {
    if (strength <= 0.004f || width <= 0.5f || height <= 0.5f) return
    val a = color.alpha * strength.coerceIn(0f, 1f)
    if (a <= 0.004f) return
    val radius = height / 2f
    val brush = Brush.radialGradient(
        colors = listOf(
            color.copy(alpha = a),
            color.copy(alpha = a * 0.62f),
            color.copy(alpha = a * 0.22f),
            color.copy(alpha = 0f),
        ),
        center = center,
        radius = radius,
    )
    scale(width / height, 1f, center) { drawCircle(brush = brush, radius = radius, center = center) }
}
