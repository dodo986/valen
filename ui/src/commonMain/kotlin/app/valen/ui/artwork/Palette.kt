/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.artwork

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import app.valen.ui.theme.Tonal
import app.valen.ui.theme.ValenScheme

/** A tiny copy of an artwork: enough pixels to find its colour, small enough to forget. */
internal class PixelMap(val width: Int, val height: Int, val argb: IntArray) {
    /** The columns from [from] until [until], as their own map. */
    fun columns(from: Int, until: Int): PixelMap {
        val start = from.coerceIn(0, width)
        val end = until.coerceIn(start, width)
        val w = end - start
        if (w <= 0 || height <= 0) return PixelMap(0, 0, IntArray(0))
        val out = IntArray(w * height)
        for (y in 0 until height) {
            argb.copyInto(out, y * w, y * width + start, y * width + end)
        }
        return PixelMap(w, height, out)
    }
}

/**
 * What one artwork gives a screen: its accent, and the colour of each half of the picture, which
 * the wash uses so the left of the screen leans the way the left of the cover does.
 *
 * Every derived step is a fixed luminance, so text on a wash keeps its contrast whatever the
 * cover looks like.
 */
@Immutable
class ArtworkTones(val accent: Color, val left: Color, val right: Color, black: Boolean = false) {
    /**
     * The scheme this artwork seeds: one hue, in tones. Everything below is taken from it, so a
     * cover's colour reaches the screen as one soft family, never as its raw, saturated self.
     */
    val scheme: ValenScheme = Tonal.scheme(accent, black)

    /** Small marks on the page: the tag, the marker lines, the playing bars, the paint of a pour. */
    val bright: Color = scheme.primary

    /** The floor of a wash. */
    val deep: Color = scheme.washFloor

    /** The tint of the floating player, mixed into its glass. */
    val chrome: Color = scheme.chrome

    /** The three pools of a wash: one hue at three depths, dark enough for white text anywhere. */
    val poolLeft: Color = scheme.washHigh
    val poolRight: Color = scheme.washMid
    val poolAccent: Color = scheme.washLow

    override fun equals(other: Any?): Boolean =
        other is ArtworkTones && other.accent == accent && other.left == left && other.right == right

    override fun hashCode(): Int = (accent.hashCode() * 31 + left.hashCode()) * 31 + right.hashCode()
}

/** The whole picture's accent plus each half's; null when the artwork has no usable colour. */
internal fun tonesFrom(map: PixelMap): ArtworkTones? {
    val accent = accentFrom(map) ?: return null
    val half = map.width / 2
    val left = if (half > 0) accentFrom(map.columns(0, half)) else null
    val right = if (half > 0) accentFrom(map.columns(half, map.width)) else null
    return ArtworkTones(accent, left ?: accent, right ?: accent)
}

/**
 * The artwork accent: the hue that covers the most of the picture, counted by how strongly each
 * pixel carries it, then pulled back so it can sit behind text (15% less saturated, clamped to
 * 30–55% luminance).
 *
 * Pixels are grouped by hue, not by exact colour, so a gradient counts as the one colour it reads
 * as instead of being split into a hundred near-misses that lose to any flat patch of grey. A
 * picture with no real colour in it gives its average grey. Returns null when nothing usable is
 * left (an empty or fully transparent image).
 */
internal fun accentFrom(map: PixelMap): Color? {
    if (map.argb.isEmpty()) return null
    val weights = FloatArray(HueBins)
    val reds = FloatArray(HueBins)
    val greens = FloatArray(HueBins)
    val blues = FloatArray(HueBins)
    var coloured = 0
    var counted = 0
    var greyR = 0f
    var greyG = 0f
    var greyB = 0f

    for (pixel in map.argb) {
        val alpha = (pixel ushr 24) and 0xFF
        if (alpha < 128) continue
        val r = ((pixel ushr 16) and 0xFF) / 255f
        val g = ((pixel ushr 8) and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        counted++
        greyR += r
        greyG += g
        greyB += b
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val chroma = max - min
        // Near-black, near-white and near-grey pixels carry no hue worth naming.
        if (max < 0.10f || chroma < 0.08f || chroma / max < MinSaturation) continue
        val hue = when (max) {
            r -> ((g - b) / chroma).mod(6f)
            g -> (b - r) / chroma + 2f
            else -> (r - g) / chroma + 4f
        }
        val bin = (hue / 6f * HueBins).toInt().coerceIn(0, HueBins - 1)
        // Saturated, reasonably bright pixels speak loudest; a dark muddy corner barely counts.
        val weight = (chroma / max) * (0.35f + max)
        weights[bin] += weight
        reds[bin] += r * weight
        greens[bin] += g * weight
        blues[bin] += b * weight
        coloured++
    }
    if (counted == 0) return null

    if (coloured < counted * MinColouredShare) {
        return clampForAccent(greyR / counted, greyG / counted, greyB / counted)
    }
    var best = 0
    for (i in 1 until HueBins) if (weights[i] > weights[best]) best = i
    val weight = weights[best]
    if (weight <= 0f) return clampForAccent(greyR / counted, greyG / counted, greyB / counted)
    return clampForAccent(reds[best] / weight, greens[best] / weight, blues[best] / weight)
}

/** Eighteen 20° slices of the colour wheel: fine enough to tell teal from blue, coarse enough to hold a gradient. */
private const val HueBins = 18
private const val MinSaturation = 0.16f

/** Below this share of coloured pixels the picture is black and white with a stray tint, and stays grey. */
private const val MinColouredShare = 0.06f

private fun saturationOf(r: Float, g: Float, b: Float): Float {
    val max = maxOf(r, g, b)
    if (max <= 0f) return 0f
    return (max - minOf(r, g, b)) / max
}

private fun lumaOf(r: Float, g: Float, b: Float): Float = 0.299f * r + 0.587f * g + 0.114f * b

/** Desaturate 15%, then scale luminance into 30–55% so the accent stays legible on the dark stage. */
private fun clampForAccent(r: Float, g: Float, b: Float): Color {
    val saturation = saturationOf(r, g, b)
    val grey = lumaOf(r, g, b)
    val targetSaturation = saturation * 0.85f
    val keep = if (saturation <= 0.0001f) 0f else targetSaturation / saturation
    var rr = grey + (r - grey) * keep
    var gg = grey + (g - grey) * keep
    var bb = grey + (b - grey) * keep

    val luma = lumaOf(rr, gg, bb).coerceAtLeast(0.0001f)
    val target = luma.coerceIn(0.30f, 0.55f)
    val scale = target / luma
    rr = (rr * scale).coerceIn(0f, 1f)
    gg = (gg * scale).coerceIn(0f, 1f)
    bb = (bb * scale).coerceIn(0f, 1f)
    return Color(rr, gg, bb, 1f)
}

/**
 * The same hue at a chosen luminance. Darkening scales the colour; lightening scales it as far as
 * its brightest channel allows and then walks the rest of the way toward white, so a saturated
 * colour gets lighter without clipping into a different hue.
 */
internal fun Color.atLuma(target: Float): Color {
    val r = red
    val g = green
    val b = blue
    val luma = lumaOf(r, g, b)
    if (luma <= 0.0001f) return Color(target, target, target, 1f)
    val max = maxOf(r, g, b)
    val scale = minOf(target / luma, 1f / max)
    val sr = r * scale
    val sg = g * scale
    val sb = b * scale
    val scaled = lumaOf(sr, sg, sb)
    if (scaled >= target - 0.0005f) return Color(sr.coerceIn(0f, 1f), sg.coerceIn(0f, 1f), sb.coerceIn(0f, 1f), 1f)
    val toWhite = ((target - scaled) / (1f - scaled)).coerceIn(0f, 1f)
    return Color(
        (sr + (1f - sr) * toWhite).coerceIn(0f, 1f),
        (sg + (1f - sg) * toWhite).coerceIn(0f, 1f),
        (sb + (1f - sb) * toWhite).coerceIn(0f, 1f),
        1f,
    )
}

/**
 * Tones by artwork URL. A state map, so whatever is showing a wash re-tints the moment the colour
 * is known, and a track played again does not decode twice. Only artwork that can become the
 * screen's accent is measured (the dock, the mini player, the hero, the stage, an opened page),
 * so the map holds played or opened covers, not every search result or shelf tile.
 */
object ArtworkPalette {
    val Fallback = Tonal.DefaultSeed

    /** No artwork: the default seed's family. */
    val Neutral = ArtworkTones(Fallback, Fallback, Fallback, black = true)

    private val cache = mutableStateMapOf<String, ArtworkTones>()

    /**
     * Whether a cover's own colour reaches the screen. Off (the default black and pink), every
     * cover gives the same pink tones; on, each cover seeds its own.
     */
    var followCover by androidx.compose.runtime.mutableStateOf(false)

    fun tones(url: String?): ArtworkTones = if (!followCover || url == null) Neutral else cache[url] ?: Neutral

    fun accent(url: String?): Color = tones(url).accent

    /** The measured tones, whatever the colour choice: what is known about a cover. */
    internal fun measured(url: String?): ArtworkTones? = url?.let { cache[it] }

    /** True once this artwork has been measured, including when it turned out to have no colour. */
    fun known(url: String): Boolean = cache.containsKey(url)

    /** The value was computed elsewhere (the artwork composable) and is being handed over. */
    fun put(url: String, tones: ArtworkTones) {
        cache[url] = tones
    }

    fun clear() {
        cache.clear()
    }
}

/**
 * The tones of the song that is playing, for the few marks outside a screen's own wash that take
 * its colour: the wordmark's overspray and the marker under a page title. Neutral when nothing is.
 */
val LocalPlayingTones = androidx.compose.runtime.compositionLocalOf { ArtworkPalette.Neutral }

/**
 * The tones for [url], held at the last known ones until the new cover has been measured (a
 * moment after its picture arrives), so a song change goes from one cover's colour straight to
 * the next and never dips through the default family on the way. Past [Motion.ToneWaitMs]
 * without a measure it takes what there is.
 */
@androidx.compose.runtime.Composable
fun rememberSettledTones(url: String?): ArtworkTones {
    val now = ArtworkPalette.tones(url)
    val known = url == null || !ArtworkPalette.followCover || ArtworkPalette.known(url)
    var last by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(now) }
    var waited by androidx.compose.runtime.remember(url) { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(url) {
        kotlinx.coroutines.delay(app.valen.ui.theme.Motion.ToneWaitMs)
        waited = true
    }
    if (known || waited) last = now
    return last
}
