/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import app.valen.core.LyricWord

/** One row's highlight, in singing order. Adjacent cues share a spatial boundary: extending
 * every syllable by the gradient's feather made the highlight jump backwards at each join. */
internal class LyricSweep(private val spans: List<Span>, private val rtl: Boolean) {
    data class Span(
        val left: Float,
        val right: Float,
        val startMs: Long,
        val endMs: Long,
        val from: Int,
        val count: Int,
        val length: Int,
    ) {
        fun share(t: Long): Float {
            val p = when {
                t <= startMs -> 0f
                t >= endMs -> 1f
                else -> (t - startMs).toFloat() / (endMs - startMs)
            }
            return ((p * length - from) / count).coerceIn(0f, 1f)
        }
    }

    fun edge(t: Long, feather: Float): Float {
        if (spans.isEmpty()) return 0f
        val direction = if (rtl) -1f else 1f
        fun leading(span: Span) = if (rtl) span.right else span.left
        fun trailing(span: Span) = if (rtl) span.left else span.right
        var edge = leading(spans.first()) - direction * feather / 2f
        for ((i, span) in spans.withIndex()) {
            val share = span.share(t)
            if (share <= 0f) break
            val start = spans.getOrNull(i - 1)?.let { (trailing(it) + leading(span)) / 2f }
                ?: (leading(span) - direction * feather / 2f)
            val end = spans.getOrNull(i + 1)?.let { (trailing(span) + leading(it)) / 2f }
                ?: (trailing(span) + direction * feather / 2f)
            edge = start + (end - start) * share
            if (share < 1f) break
        }
        return edge
    }
}

/** Syllables of a space-delimited word lift together; their native highlight times stay separate.
 * Unspaced ideographs retain their own motion rather than lifting an entire sentence at once. */
internal fun lyricMotionStarts(words: List<LyricWord>): LongArray = LongArray(words.size).also { starts ->
    for (i in words.indices) {
        val previous = words.getOrNull(i - 1)
        val joins = previous != null && !previous.space &&
            previous.text.lastOrNull()?.let { !it.isWhitespace() && it.code < 0x2E80 } == true &&
            words[i].text.firstOrNull()?.let { !it.isWhitespace() && it.code < 0x2E80 } == true
        starts[i] = if (joins) starts[i - 1] else words[i].startMs
    }
}
