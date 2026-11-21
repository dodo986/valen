/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import androidx.compose.ui.graphics.RenderEffect

/**
 * The platform half of the stage's depth of field: whether this device can blur one line's layer,
 * and the plain blur itself (null where it cannot). Android blurs a layer from 12 (API 31) and
 * every desktop can. Where it cannot, the recede is carried by opacity and tone alone
 * ([LyricsDepth.alpha] with `blur = false`), so a distant line never simply looks out of focus by
 * accident. A blur is only ever put on the few lines around the one being sung, never on the
 * active line and never on the whole pane.
 */
internal expect object LyricsBlur {
    val supported: Boolean

    /** A plain blur of [radiusPx], or null where the platform cannot make one. */
    fun effect(radiusPx: Float): RenderEffect?
}
