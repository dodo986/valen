/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import app.valen.core.LyricWord
import app.valen.core.Lyrics
import app.valen.core.LyricsTiming
import app.valen.ui.theme.Motion
import kotlin.random.Random

/**
 * The marks the pen leaves on sung words, in the one marker the tag was written with. Most are
 * made once a word has been sung (it is gone over, struck, crossed out); a held note is marked
 * while it is held (circled, or underlined with paint running from it). [behind] marks go under
 * the letters (a swipe of the container tone), the rest over them.
 */
internal enum class Scribble(val behind: Boolean = false, val whileHeld: Boolean = false) {
    /** One line through the middle of the word, a little uphill. */
    Strike,

    /** Two lines through it, the second drawn back the other way. */
    Double,

    /** An X from corner to corner. */
    Cross,

    /** Scratched out: the pen zigzags back and forth over the word. */
    Scratch,

    /** Short slashes across it, one after another. */
    Hatch,

    /** A zigzag under it, like a lightning line. */
    Zigzag,

    /** A rough box drawn round it in one go, its ends not quite meeting. */
    Box,

    /** A loop round it whose ends cross. */
    Loop(whileHeld = true),

    /** A marker line under it with a lift at the end and paint running from it. */
    Underline(whileHeld = true),

    /** The marker gone over it flat, behind the letters. */
    Swipe(behind = true),

    /** Three dashes thrown off its top corner, for a word that is shouted. */
    Burst,
}

/** One mark on one word: what, how it is jittered, and when the pen draws it. */
internal class Mark(
    val kind: Scribble,
    val seed: Int,
    val startMs: Long,
    val durationMs: Int,
    /** How far paint runs from an underline, 0–1: further the longer the note was held. */
    val drip: Float = 0f,
) {
    /** How much of the mark is drawn at [t] (song time). */
    fun progress(t: Long): Float = when {
        t <= startMs -> 0f
        t >= startMs + durationMs -> 1f
        else -> (t - startMs).toFloat() / durationMs
    }

    val endMs: Long get() = startMs + durationMs
}

/**
 * Which words of a song are marked, and how: decided once per song from its key, so the same
 * song is always marked the same way. Word intervals can be native or estimated for display
 * within a timed line; lyrics with no timing have no marks.
 *
 * Not every word: about one in six, never two side by side, at most two to a line. A word sung
 * again in the same line is crossed out; a held note is circled or underlined while it is held;
 * a shouted word gets a burst; long words get one of the rest. The same kind is never used twice
 * in a row.
 */
internal fun markWords(lyrics: Lyrics, seed: Int): List<List<Mark?>> {
    if (lyrics.timing == LyricsTiming.None) return lyrics.lines.map { emptyList() }
    val recent = ArrayDeque<Scribble>()
    var sinceLast = 3
    return lyrics.lines.mapIndexed { li, line ->
        val seen = mutableSetOf<String>()
        var inLine = 0
        line.words.mapIndexed { wi, word ->
            val norm = word.text.lowercase().filter { it.isLetterOrDigit() }
            val r = Random(seed * 31 + li * 7919 + wi * 104_729)
            val pick = if (eligible(word, norm) && inLine < MAX_PER_LINE && sinceLast >= 2) choose(word, norm, seen, r, recent) else null
            seen += norm
            if (pick == null) {
                sinceLast++
                null
            } else {
                sinceLast = 0
                inLine++
                recent.addLast(pick)
                if (recent.size > 2) recent.removeFirst()
                timed(pick, word, r.nextInt())
            }
        }
    }
}

private fun eligible(word: LyricWord, norm: String): Boolean {
    if (norm.length >= 2) return true
    // A single ideograph or kana is a whole word in many songs.
    return norm.length == 1 && norm[0].code >= 0x2E80
}

private fun choose(word: LyricWord, norm: String, seen: Set<String>, r: Random, recent: ArrayDeque<Scribble>): Scribble? {
    val held = word.endMs - word.startMs >= HELD_MS
    val repeated = norm.length >= 2 && norm in seen
    val shouted = word.text.endsWith('!') || word.text.endsWith('?')
    val pool = when {
        held && r.nextFloat() < 0.8f -> HELD
        repeated && r.nextFloat() < 0.75f -> CROSSED
        shouted && r.nextFloat() < 0.6f -> SHOUTED
        norm.length >= 4 && r.nextFloat() < 0.2f -> ANY
        norm.length in 2..3 && r.nextFloat() < 0.07f -> SHORT
        else -> return null
    }
    val fresh = pool.filter { it !in recent }.ifEmpty { pool }
    return fresh[r.nextInt(fresh.size)]
}

private fun timed(kind: Scribble, word: LyricWord, seed: Int): Mark {
    val sung = (word.endMs - word.startMs).coerceAtLeast(1)
    if (kind.whileHeld && sung >= HELD_MS) {
        // Drawn along with the held note, from just after it starts to just before it ends.
        val start = word.startMs + (sung * 0.12f).toLong()
        val length = (sung * 0.8f).toInt().coerceIn(Motion.ScribbleMs, Motion.ScribbleHeldMaxMs)
        val drip = ((sung - HELD_MS).toFloat() / DRIP_FULL_MS).coerceIn(0.25f, 1f)
        return Mark(kind, seed, start, length, drip)
    }
    val length = when (kind) {
        Scribble.Strike, Scribble.Swipe, Scribble.Burst -> Motion.ScribbleQuickMs
        Scribble.Scratch, Scribble.Loop, Scribble.Box -> Motion.ScribbleLongMs
        else -> Motion.ScribbleMs
    }
    return Mark(kind, seed, word.endMs + Motion.ScribbleAfterMs, length, drip = if (kind == Scribble.Underline) 0.3f else 0f)
}

private const val MAX_PER_LINE = 2

/** A note held this long is marked while it is held. */
private const val HELD_MS = 1_100L

/** Held this much longer than [HELD_MS], an underline's paint runs its full length. */
private const val DRIP_FULL_MS = 1_800f

private val HELD = listOf(Scribble.Loop, Scribble.Underline)
private val CROSSED = listOf(Scribble.Strike, Scribble.Cross, Scribble.Scratch, Scribble.Double, Scribble.Hatch)
private val SHOUTED = listOf(Scribble.Burst, Scribble.Loop, Scribble.Box)
private val SHORT = listOf(Scribble.Strike, Scribble.Cross, Scribble.Swipe, Scribble.Box)
private val ANY = listOf(
    Scribble.Strike, Scribble.Double, Scribble.Cross, Scribble.Scratch, Scribble.Hatch,
    Scribble.Zigzag, Scribble.Box, Scribble.Loop, Scribble.Underline, Scribble.Swipe,
)
