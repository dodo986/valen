/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import app.valen.core.LyricWord
import app.valen.core.Lyrics
import app.valen.core.LyricsTiming

/**
 * Display-only word intervals for lines that have no native word timing. Longer words get more
 * of the line's interval, ending by the next phrase from that singer. Some line-only providers
 * give every phrase a five-second placeholder even when the next starts much sooner; using that
 * whole window made the estimated words lag and cut off half-lit at every line change.
 * This is a visual estimate, not native timing. The source model and disk cache stay untouched.
 */
internal fun Lyrics.withAnimationTiming(): Lyrics {
    if (timing == LyricsTiming.None || instrumental) return this
    val nextByVoice = lines.filter { !it.aside && it.text.isNotBlank() }.groupBy { it.voice }.mapValues { (_, phrases) ->
        phrases.map { it.startMs }.distinct().sorted().zipWithNext().toMap()
    }
    val animated = lines.map { line ->
        val next = if (line.aside) null else nextByVoice[line.voice]?.get(line.startMs)
        val estimatedEnd = if (line.words.isEmpty() && next != null) minOf(line.endMs, next) else line.endMs
        val words = line.words.ifEmpty { animationWords(line.text, line.startMs, estimatedEnd) }
        // Real backing cues may outlive the next lead phrase: never crop or rescale those.
        val backing = line.backing.ifEmpty { animationWords(line.backingText.orEmpty(), line.startMs, estimatedEnd) }
        val end = if (line.words.isEmpty() && line.backing.isEmpty()) estimatedEnd else line.endMs
        if (words == line.words && backing == line.backing && end == line.endMs) line
        else line.copy(endMs = end, words = words, backing = backing)
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
