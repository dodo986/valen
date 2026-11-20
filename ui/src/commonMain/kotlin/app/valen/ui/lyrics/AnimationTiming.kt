/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import app.valen.core.LyricWord
import app.valen.core.Lyrics
import app.valen.core.LyricsTiming

/**
 * Display-only word intervals for lines that have no native word timing. Longer words get more
 * of the line's interval; this is a visual estimate, not timing supplied by the lyrics source.
 * The source model, its timing classification and the disk cache are left as they were.
 */
internal fun Lyrics.withAnimationTiming(): Lyrics {
    if (timing == LyricsTiming.None || instrumental) return this
    val animated = lines.map { line ->
        val words = line.words.ifEmpty { animationWords(line.text, line.startMs, line.endMs) }
        val backing = line.backing.ifEmpty { animationWords(line.backingText.orEmpty(), line.startMs, line.endMs) }
        if (words == line.words && backing == line.backing) line else line.copy(words = words, backing = backing)
    }
    return if (animated == lines) this else copy(lines = animated)
}

private fun animationWords(text: String, startMs: Long, endMs: Long): List<LyricWord> {
    if (startMs < 0 || endMs <= startMs) return emptyList()
    // Keep punctuation, Unicode and whitespace intact. A token owns the space after it, just
    // like native syllable timing does; no platform-specific regular expression is needed.
    val starts = text.indices.filter { !text[it].isWhitespace() && (it == 0 || text[it - 1].isWhitespace()) }
    if (starts.isEmpty() || endMs - startMs < starts.size) return emptyList()
    val tokens = starts.mapIndexed { i, start -> text.substring(if (i == 0) 0 else start, starts.getOrNull(i + 1) ?: text.length) }
    val weights = tokens.map { token -> token.count { it.isLetterOrDigit() }.coerceAtLeast(1) }
    val total = weights.sum().toDouble()
    // Give every token at least one millisecond, even for a very short line, then distribute
    // the remaining time by length. Cumulative boundaries avoid rounding gaps and overshoots.
    val remaining = endMs - startMs - tokens.size
    var weight = 0
    var at = startMs
    return tokens.mapIndexed { i, token ->
        weight += weights[i]
        val end = if (i == tokens.lastIndex) endMs else startMs + i + 1 + (remaining * (weight / total)).toLong()
        val space = token.endsWith(' ')
        LyricWord(if (space) token.dropLast(1) else token, at, end, space).also { at = end }
    }
}
