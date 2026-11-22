/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import app.valen.core.LyricWord
import app.valen.ui.theme.Motion
import app.valen.ui.theme.ValenColors
import kotlin.math.PI
import kotlin.math.sin

/** Where a line stands in the song. */
internal enum class LinePhase {
    /** Still to come. */
    Upcoming,

    /** Being sung now. */
    Sung,

    /** Over, but the pen is still finishing a mark on it and its light is still trailing off. */
    Trailing,

    /** Over. */
    Past,
}

/** A line at the song's own plane, for a text whose depth is not known (a credit, a fallback). */
private val SongPlane: State<Float> = mutableStateOf(1f)

/**
 * Words being sung. The paragraph is drawn once in white and masked: what has been sung is full
 * white, what has not is at [dim], and the edge between them is soft and moves through each word
 * at the pace it is sung. A word lifts a little as its singing starts and stays up until the line
 * is over; a held note has a wave run through its letters, with a soft glow riding that crest.
 *
 * The line being sung stands in a feathered field of the one colour ([field] true for the words,
 * not for the backing under them): it blooms in as the line arrives, breathes while it is held and
 * trails off once it is over. A glint of the same colour follows the sung edge through each word
 * and a short word takes a dab of ink as it begins. The marks the pen makes ([marks], one per word
 * or null) are drawn over the letters, or under them for a swipe, and they settle back as the line
 * passes ([LyricEffects.markTrail]).
 *
 * [depth] is how near the song this line stands, read in the draw so a settling line costs no
 * recomposition; where the platform can blur a layer ([blur] true) a distant line softens, and
 * where it cannot the recede is opacity and a touch of tone.
 *
 * Settings → Lyrics → Moving words off and the system's reduced motion hold the same stage, softer:
 * none of this is stripped to a still picture, the pane's own transitions just take the shorter
 * tween ([soft]).
 *
 * With no [words] (a line timed as a whole) the line brightens and lifts as one.
 */
@Composable
internal fun SungText(
    text: String,
    words: List<LyricWord>,
    marks: List<Mark?>,
    phase: LinePhase,
    clock: LongState,
    style: TextStyle,
    align: TextAlign,
    dim: Float,
    /**
     * Settings → Lyrics → Moving words off, or the system's reduced motion: every effect still
     * plays, the pane only takes its own transitions on the shorter tween.
     */
    soft: Boolean = false,
    /** The line's own interval, for the light field behind it. */
    startMs: Long = 0,
    endMs: Long = 0,
    /** Whether the light field is drawn behind this text: the line's words, never its backing. */
    field: Boolean = false,
    /** How near the song this line stands (see [LyricsDepth]). */
    depth: State<Float> = SongPlane,
    /** Whether this platform blurs the lines around the song (see [LyricsBlur]). */
    blur: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val focus = phase == LinePhase.Sung
    val bright = remember { Animatable(if (focus) 1f else 0f) }
    LaunchedEffect(focus) {
        val target = if (focus) 1f else 0f
        if (soft) {
            bright.animateTo(target, Motion.soft())
        } else {
            bright.animateTo(target, tween(Motion.LyricLineMs, easing = Motion.Standard))
        }
    }
    val shown = remember(text, words) {
        if (words.isEmpty()) text else words.joinToString("") { it.text + if (it.space) " " else "" }.trimEnd()
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current.density
    val primary = ValenColors.Primary
    val page = ValenColors.Surface
    // Worked out once per laid-out paragraph and kept across phases: a line's phase changes
    // with every line sung, and rebuilding the geometry then (a box per letter) cost every
    // line's first frame again at each change.
    val geometries = remember { GeometryKeeper() }
    Text(
        text = shown,
        style = style,
        color = Color.White,
        textAlign = align,
        onTextLayout = { layout = it },
        modifier = modifier
            .fillMaxWidth()
            .drawWithContent {
                val result = layout
                val geometry = if (result != null && result.layoutInput.text.text == shown) {
                    geometries.of(result, words, marks, style.fontSize.toPx(), density)
                } else {
                    null
                }
                // Only a line being sung (or finishing a mark) reads the clock, so only it is
                // drawn again on every frame.
                val t = when (phase) {
                    LinePhase.Sung, LinePhase.Trailing -> clock.longValue
                    LinePhase.Past -> FAR_FUTURE
                    LinePhase.Upcoming -> FAR_PAST
                }
                val b = bright.value
                // The depth of field: opacity, and a touch of the page's own tone where the
                // platform cannot blur the layer itself.
                val level = depth.value.coerceIn(0f, 1f)
                val alpha = LyricsDepth.alpha(level, blur)
                val tint = LyricsDepth.tint(level, blur)
                fun shade(color: Color): Color = if (tint > 0f) lerp(color, page, tint) else color
                if (geometry == null) {
                    // Not laid out yet (its first frame): the Text's own drawing, unmasked.
                    drawContent()
                    return@drawWithContent
                }
                val em = geometry.em
                // The light the line stands in, behind everything else it draws.
                if (field) {
                    val glow = LyricEffects.field(t, startMs, endMs) * alpha
                    if (glow > 0.01f) lightField(geometry, primary, glow)
                }
                for (i in marks.indices) {
                    val ink = geometry.inks[i] ?: continue
                    if (ink.kind.behind) ink.draw(this, marks[i]!!.progress(t), shade(primary).copy(alpha = 0.9f * alpha))
                }
                // The words are drawn from the paragraph with their tone in the paint, never by
                // dimming a layer: an offscreen layer for every line on show was most of what a
                // phone's GPU spent on the lyrics.
                val layoutResult = geometry.layout
                if (words.isNotEmpty() && b <= 0.001f && phase != LinePhase.Sung) {
                    // Resting (to come, or over): all one tone.
                    drawText(layoutResult, color = shade(Color.White).copy(alpha = dim * alpha))
                } else if (words.isEmpty()) {
                    // Timed by line: it comes up as a whole.
                    translate(0f, -LIFT * em * b) {
                        drawText(layoutResult, color = shade(Color.White).copy(alpha = (dim + (1f - dim) * b) * alpha))
                    }
                } else {
                    sung(
                        g = geometry,
                        t = t,
                        bright = b,
                        dim = dim,
                        alpha = alpha,
                        tint = tint,
                        primary = primary,
                        page = page,
                    )
                }
                val markAlpha = (0.72f + 0.28f * b) * LyricEffects.markTrail(t, endMs) * alpha
                for (i in marks.indices) {
                    val ink = geometry.inks[i] ?: continue
                    if (!ink.kind.behind) ink.draw(this, marks[i]!!.progress(t), shade(primary).copy(alpha = markAlpha))
                }
            },
    )
}

/**
 * The words being sung. Each row is painted with its own soft edge (a gradient from sung to
 * unsung in the text's paint, no mask layer), and words that have started rise: a run of words at
 * the same height is drawn in one go, so a row costs a few draws, not one per word. A glint of the
 * one colour follows the edge, a short word takes a dab of ink as it starts, and a held note's
 * crest carries a soft glow.
 */
private fun DrawScope.sung(
    g: Geometry,
    t: Long,
    bright: Float,
    dim: Float,
    alpha: Float,
    tint: Float,
    primary: Color,
    page: Color,
) {
    val em = g.em
    val pad = em
    fun shade(color: Color): Color = if (tint > 0f) lerp(color, page, tint) else color
    val sungAlpha = (dim + (1f - dim) * bright) * alpha
    val sung = shade(Color.White).copy(alpha = sungAlpha)
    val unsung = shade(Color.White).copy(alpha = dim * alpha)
    val feather = FEATHER * em
    val lift = LIFT * em * bright
    for (row in 0 until g.rows) {
        val top = g.bandTop(row, pad)
        val bottom = g.bandBottom(row, pad, size.height)
        val edge = g.edge(row, t, feather)
        // The light at the sung edge, and the ink a short word takes as it begins: both behind the
        // letters, so the words stay whole.
        val at = g.wordAt(row, t)
        if (at >= 0) {
            val word = g.words[at]
            val through = ((t - word.startMs).toFloat() / (word.endMs - word.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
            glint(edge, top, bottom, em, primary, LyricEffects.glint(through) * alpha)
        }
        wordInk(g, row, t, top, bottom, em, primary, alpha)
        val pieces = g.piecesOn(row)
        if (lift <= 0f || pieces.isEmpty()) {
            clipRect(-pad, top, size.width + pad, bottom) { drawText(g.layout, brush(sung, unsung, edge, feather, g.rtl)) }
            continue
        }
        // Pieces in the order they stand on the row, left to right.
        val ordered = g.leftToRight(row)
        var k = 0
        while (k < ordered.size) {
            val piece = ordered[k]
            val word = g.words[piece.word]
            val left = ordered.getOrNull(k - 1)?.let { (it.right + piece.left) / 2f } ?: -pad
            val held = word.endMs - word.startMs >= WAVE_MS && piece.count >= 3 && t in word.startMs..(word.endMs + WAVE_TAIL_MS)
            val up = lift * Motion.Enter.transform(((t - g.motionStarts[piece.word]).toFloat() / Motion.WordLiftMs).coerceIn(0f, 1f))
            if (held) {
                // A held note: a crest runs through its letters as it is held, carrying a soft glow
                // along the word. The glow goes down first, so it never washes the letters out.
                val right = ordered.getOrNull(k + 1)?.let { (piece.right + it.left) / 2f } ?: (size.width + pad)
                val per = (word.endMs - word.startMs).toFloat() / g.lengths[piece.word]
                for (c in 0 until piece.count) {
                    val at = word.startMs + per * (piece.from + c)
                    val local = ((t - at) / (per * 2.6f).coerceIn(160f, 520f)).coerceIn(0f, 1f)
                    val strength = LyricEffects.crestGlow(local)
                    if (strength > 0.04f) {
                        val box = g.layout.getBoundingBox(piece.global + c)
                        crestGlow(box.left, box.right, top, bottom, em, primary, strength * alpha)
                    }
                }
                for (c in 0 until piece.count) {
                    val box = g.layout.getBoundingBox(piece.global + c)
                    val at = word.startMs + per * (piece.from + c)
                    val local = ((t - at) / (per * 2.6f).coerceIn(160f, 520f)).coerceIn(0f, 1f)
                    val crest = lift * 1.25f * sin(local * PI.toFloat())
                    val l = if (c == 0) left else box.left
                    val r = if (c == piece.count - 1) right else box.right
                    clipRect(l, top, r, bottom) { translate(0f, -(up + crest)) { drawText(g.layout, brush(sung, unsung, edge, feather, g.rtl)) } }
                }
                k++
                continue
            }
            // The run of following words at the same height (all still down, or all fully up).
            var end = k
            while (end + 1 < ordered.size) {
                val next = ordered[end + 1]
                val nextWord = g.words[next.word]
                val nextHeld = nextWord.endMs - nextWord.startMs >= WAVE_MS && next.count >= 3 && t in nextWord.startMs..(nextWord.endMs + WAVE_TAIL_MS)
                val nextUp = lift * Motion.Enter.transform(((t - g.motionStarts[next.word]).toFloat() / Motion.WordLiftMs).coerceIn(0f, 1f))
                if (nextHeld || nextUp != up) break
                end++
            }
            val last = ordered[end]
            val right = ordered.getOrNull(end + 1)?.let { (last.right + it.left) / 2f } ?: (size.width + pad)
            clipRect(left, top, right, bottom) { translate(0f, -up) { drawText(g.layout, brush(sung, unsung, edge, feather, g.rtl)) } }
            k = end + 1
        }
    }
}

/** The soft edge between sung and unsung, as a paint for one row. */
private fun brush(sung: Color, unsung: Color, edge: Float, feather: Float, rtl: Boolean): Brush =
    if (rtl) {
        Brush.horizontalGradient(listOf(unsung, sung), startX = edge - feather / 2f, endX = edge + feather / 2f)
    } else {
        Brush.horizontalGradient(listOf(sung, unsung), startX = edge - feather / 2f, endX = edge + feather / 2f)
    }

/**
 * The light field behind the line being sung: one wide pool of the one colour, reaching
 * [Motion.LyricFieldSpreadEm] past the letters and fading to nothing at its edge, so it reads as
 * light through glass and never as a card the words stand on.
 */
private fun DrawScope.lightField(g: Geometry, primary: Color, strength: Float) {
    val em = g.em
    val spread = Motion.LyricFieldSpreadEm * em
    val left = (g.textLeft - spread).coerceAtLeast(0f)
    val right = (g.textRight + spread).coerceAtMost(size.width)
    val top = (g.textTop - spread * 0.85f).coerceAtLeast(0f)
    val bottom = (g.textBottom + spread * 0.85f).coerceAtMost(size.height)
    if (right - left < em || bottom - top < 1f) return
    pool(
        center = Offset((left + right) / 2f, (top + bottom) / 2f),
        width = right - left,
        height = bottom - top,
        color = primary,
        strength = Motion.LyricFieldAlpha * strength,
    )
}

/** The glint at the sung edge: a short shaft of the one colour, behind the letters. */
private fun DrawScope.glint(x: Float, top: Float, bottom: Float, em: Float, primary: Color, strength: Float) {
    val height = bottom - top
    if (height <= 1f || strength <= 0.01f) return
    pool(
        center = Offset(x.coerceIn(-em, size.width + em), (top + bottom) / 2f),
        width = Motion.LyricGlintEm * em,
        height = height,
        color = primary,
        strength = Motion.LyricGlintAlpha * strength,
    )
}

/** The dab of ink a short word takes as it begins: at the word's leading edge, behind the letters. */
private fun DrawScope.wordInk(g: Geometry, row: Int, t: Long, top: Float, bottom: Float, em: Float, primary: Color, alpha: Float) {
    val height = bottom - top
    if (height <= 1f) return
    for (piece in g.leftToRight(row)) {
        if (piece.from != 0) continue
        val word = g.words[piece.word]
        val ink = LyricEffects.ink(t, word) * alpha
        if (ink <= 0.02f) continue
        pool(
            center = Offset(piece.left, bottom - height * 0.28f),
            width = em * 1.15f,
            height = height * 0.8f,
            color = primary,
            strength = Motion.LyricInkAlpha * ink,
        )
    }
}

/** The glow riding a held note's crest: a soft pool on the letters the crest is passing through. */
private fun DrawScope.crestGlow(left: Float, right: Float, top: Float, bottom: Float, em: Float, primary: Color, strength: Float) {
    val height = bottom - top
    if (height <= 1f) return
    pool(
        center = Offset((left + right) / 2f, (top + bottom) / 2f),
        width = Motion.LyricHeldGlowEm * em,
        height = height,
        color = primary,
        strength = Motion.LyricHeldGlowAlpha * strength,
    )
}

/**
 * The last geometry made, kept while its paragraph and marks are the same.
 */
private class GeometryKeeper {
    private var layout: TextLayoutResult? = null
    private var marks: List<Mark?>? = null
    private var words: List<LyricWord>? = null
    private var em = 0f
    private var kept: Geometry? = null

    fun of(result: TextLayoutResult, words: List<LyricWord>, marks: List<Mark?>, em: Float, density: Float): Geometry {
        val k = kept
        if (k != null && layout === result && this.marks === marks && this.words === words && this.em == em) return k
        return Geometry.of(result, words, marks, em, density).also {
            kept = it
            layout = result
            this.marks = marks
            this.words = words
            this.em = em
        }
    }
}

/** A word's run of letters on one row (a word too long for a row is in two pieces). */
private class Piece(val word: Int, val row: Int, val left: Float, val right: Float, val from: Int, val count: Int, val global: Int)

/**
 * What a laid-out line needs to be drawn sung: its rows, the marks' ink and, only once the line is
 * sung, each word's run of letters. Most lines on show are never sung while the pane is open, and
 * reading a box for every letter of every line was the pane's first-frame cost on a phone.
 */
private class Geometry(
    val layout: TextLayoutResult,
    val words: List<LyricWord>,
    val lengths: IntArray,
    /** Where each word starts in the paragraph's text. */
    private val starts: IntArray,
    val rows: Int,
    val rowTop: FloatArray,
    val rowBottom: FloatArray,
    val rowLeft: FloatArray,
    val rowRight: FloatArray,
    val rtl: Boolean,
    val em: Float,
    val inks: Array<ScribbleInk?>,
) {
    val motionStarts = lyricMotionStarts(words)

    /** The band of [row]: from halfway to the row above (or past the top) to halfway to the one below. */
    fun bandTop(row: Int, pad: Float): Float = if (row == 0) -pad else (rowBottom[row - 1] + rowTop[row]) / 2f
    fun bandBottom(row: Int, pad: Float, height: Float): Float = if (row == rows - 1) height + pad else (rowBottom[row] + rowTop[row + 1]) / 2f

    /** The letters' own bounds, for the light field behind them. */
    val textLeft: Float get() = if (rows == 0) 0f else (0 until rows).minOf { rowLeft[it] }
    val textRight: Float get() = if (rows == 0) 0f else (0 until rows).maxOf { rowRight[it] }
    val textTop: Float get() = if (rows == 0) 0f else (0 until rows).minOf { rowTop[it] }
    val textBottom: Float get() = if (rows == 0) em else (0 until rows).maxOf { rowBottom[it] }

    private val pieces: List<Piece> by lazy(LazyThreadSafetyMode.NONE) {
        val all = ArrayList<Piece>(words.size + 2)
        for (i in words.indices) piecesOf(layout, i, starts[i], words[i].text.length, all)
        all
    }

    /** In the order they are sung (right to left on an RTL row). */
    private val byRow: Array<List<Piece>> by lazy(LazyThreadSafetyMode.NONE) {
        Array(rows) { r -> pieces.filter { it.row == r }.let { if (rtl) it.sortedByDescending { p -> p.right } else it } }
    }

    /** In the order they stand, left to right. */
    private val byPlace: Array<List<Piece>> by lazy(LazyThreadSafetyMode.NONE) {
        Array(rows) { r -> pieces.filter { it.row == r }.sortedBy { it.left } }
    }

    private val sweeps: Array<LyricSweep> by lazy(LazyThreadSafetyMode.NONE) {
        Array(rows) { row ->
            LyricSweep(byRow[row].map { piece ->
                val word = words[piece.word]
                LyricSweep.Span(piece.left, piece.right, word.startMs, word.endMs,
                    piece.from, piece.count, lengths[piece.word])
            }, rtl)
        }
    }

    fun piecesOn(row: Int): List<Piece> = byRow[row]
    fun leftToRight(row: Int): List<Piece> = byPlace[row]

    /** How much of a piece is sung at [t]. The edge always moves, whatever the settings. */
    private fun sung(piece: Piece, t: Long): Float {
        val w = words[piece.word]
        val p = when {
            t <= w.startMs -> 0f
            t >= w.endMs -> 1f
            else -> (t - w.startMs).toFloat() / (w.endMs - w.startMs)
        }
        return ((p * lengths[piece.word] - piece.from) / piece.count).coerceIn(0f, 1f)
    }

    /**
     * The word the sung edge is inside on [row] at [t], or -1 (a rest, or the row's end): what the
     * glint follows.
     */
    fun wordAt(row: Int, t: Long): Int {
        for (piece in byRow[row]) {
            val s = sung(piece, t)
            if (s <= 0f) return -1
            if (s < 1f) return piece.word
        }
        return -1
    }

    /** Where the sung part of [row] ends, as an x the soft edge is centred on. */
    fun edge(row: Int, t: Long, feather: Float): Float {
        val list = byRow[row]
        if (list.isEmpty()) return if (rtl) rowLeft[row] - feather else rowRight[row] + feather
        return sweeps[row].edge(t, feather)
    }

    companion object {
        fun of(result: TextLayoutResult, words: List<LyricWord>, marks: List<Mark?>, em: Float, density: Float): Geometry {
            val rows = result.lineCount
            val rowTop = FloatArray(rows) { result.getLineTop(it) }
            val rowBottom = FloatArray(rows) { result.getLineBottom(it) }
            val rowLeft = FloatArray(rows) { result.getLineLeft(it) }
            val rowRight = FloatArray(rows) { result.getLineRight(it) }
            val rtl = rows > 0 && result.getParagraphDirection(0) == ResolvedTextDirection.Rtl
            val lengths = IntArray(words.size)
            val starts = IntArray(words.size)
            var at = 0
            for ((i, w) in words.withIndex()) {
                lengths[i] = w.text.length.coerceAtLeast(1)
                starts[i] = at
                at += w.text.length + if (w.space) 1 else 0
            }
            // Only a marked word needs its box now: its ink is drawn on every line it stands on.
            val one = ArrayList<Piece>(2)
            val inks = Array(words.size) { i ->
                val mark = marks.getOrNull(i) ?: return@Array null
                one.clear()
                piecesOf(result, i, starts[i], words[i].text.length, one, firstOnly = true)
                val first = one.firstOrNull() ?: return@Array null
                val box = WordBox(first.left, first.right, result.getLineBaseline(first.row), em)
                scribbleInk(mark.kind, box, mark.seed, density, mark.drip)
            }
            return Geometry(result, words, lengths, starts, rows, rowTop, rowBottom, rowLeft, rowRight, rtl, em, inks)
        }

        /** Word [i]'s runs of letters, one per row it stands on, added to [into]. */
        private fun piecesOf(result: TextLayoutResult, i: Int, at: Int, n: Int, into: MutableList<Piece>, firstOnly: Boolean = false) {
            val length = result.layoutInput.text.length
            var c = 0
            while (c < n && at + c < length) {
                val row = result.getLineForOffset(at + c)
                val start = c
                var left = Float.MAX_VALUE
                var right = -Float.MAX_VALUE
                while (c < n && at + c < length && result.getLineForOffset(at + c) == row) {
                    val box = result.getBoundingBox(at + c)
                    if (box.width > 0f) {
                        left = minOf(left, box.left)
                        right = maxOf(right, box.right)
                    }
                    c++
                }
                if (left <= right) {
                    into += Piece(i, row, left, right, start, c - start, at + start)
                    if (firstOnly) return
                }
            }
        }
    }
}

/** How far a sung word rises, in ems of its letters. */
private const val LIFT = 0.07f

/** The soft edge between sung and unsung, in ems. */
private const val FEATHER = 0.5f

/** A note held this long gets a wave through its letters. */
private const val WAVE_MS = 1_000L
private const val WAVE_TAIL_MS = 300L

private const val FAR_FUTURE = Long.MAX_VALUE / 4
private const val FAR_PAST = Long.MIN_VALUE / 4
