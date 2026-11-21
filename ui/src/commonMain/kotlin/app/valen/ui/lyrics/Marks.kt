/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import app.valen.core.LyricWord
import app.valen.core.Lyrics
import app.valen.core.LyricsTiming
import app.valen.core.PenFrequency
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

    /** Open corner brackets round it, `[` at the left and `]` at the right, drawn one after the other. */
    Brackets,

    /** Two marker lines under it, the lower one running past the word and flicking up at its end. */
    DoubleUnderline(whileHeld = true),

    /** A dotted trail under it: small round dabs that thin out toward the end. */
    DotTrail,

    /** A loose loop round it, and a smaller, offset echo of that loop beside the word. */
    EchoLoop,

    /** A wide caret over it, like a hat thrown on the word. */
    Caret,

    /** Small round arcs under it, a scalloped line rather than the zigzag's sharp angles. */
    Scallop,
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
 * Not every word, and how few depends on [frequency]: [PenFrequency.SPARSE] marks roughly one
 * eligible word in nine or ten, [PenFrequency.STANDARD] about one in five, and
 * [PenFrequency.FREQUENT] nearly every eligible word the spacing rule allows, about one in three.
 * Never two closer than three words apart, at most two to a line. A word sung again in the same
 * line is crossed out; a held note is circled or underlined while it is held; a shouted word gets
 * a burst, brackets or a caret; short words, long words and the rest get one of the others. The
 * same kind is never used twice in a row.
 */
internal fun markWords(
    lyrics: Lyrics,
    seed: Int,
    frequency: PenFrequency = PenFrequency.STANDARD,
): List<List<Mark?>> {
    if (lyrics.timing == LyricsTiming.None) return lyrics.lines.map { emptyList() }
    val recent = ArrayDeque<Scribble>()
    var sinceLast = 3
    return lyrics.lines.mapIndexed { li, line ->
        val seen = mutableSetOf<String>()
        var inLine = 0
        line.words.mapIndexed { wi, word ->
            val norm = word.text.lowercase().filter { it.isLetterOrDigit() }
            val r = Random(seed * 31 + li * 7919 + wi * 104_729)
            val pick = if (eligible(word, norm) && inLine < MAX_PER_LINE && sinceLast >= 2) {
                choose(word, norm, seen, r, recent, frequency)
            } else {
                null
            }
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

private fun choose(
    word: LyricWord,
    norm: String,
    seen: Set<String>,
    r: Random,
    recent: ArrayDeque<Scribble>,
    frequency: PenFrequency,
): Scribble? {
    val eager = frequency.eagerness
    val held = word.endMs - word.startMs >= HELD_MS
    val repeated = norm.length >= 2 && norm in seen
    val shouted = word.text.endsWith('!') || word.text.endsWith('?')
    val pool = when {
        // A held note takes only a mark drawn while it is held, the way the note itself is held.
        held -> if (r.nextFloat() < 0.8f * eager) HELD else return null
        repeated && r.nextFloat() < 0.75f * eager -> CROSSED
        shouted && r.nextFloat() < 0.6f * eager -> SHOUTED
        norm.length >= 4 && r.nextFloat() < 0.2f * eager -> ANY
        norm.length in 2..3 && r.nextFloat() < 0.07f * eager -> SHORT
        else -> return null
    }
    // Never the same kind twice running; when a small pool leaves nothing else, at least the last.
    val fresh = pool.filter { it !in recent }
        .ifEmpty { pool.filter { it != recent.lastOrNull() } }
        .ifEmpty { pool }
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
        Scribble.Strike, Scribble.Swipe, Scribble.Burst, Scribble.DotTrail, Scribble.Caret ->
            Motion.ScribbleQuickMs
        Scribble.Scratch, Scribble.Loop, Scribble.Box, Scribble.EchoLoop, Scribble.DoubleUnderline ->
            Motion.ScribbleLongMs
        else -> Motion.ScribbleMs
    }
    val drip = if (kind == Scribble.Underline || kind == Scribble.DoubleUnderline) 0.3f else 0f
    return Mark(kind, seed, word.endMs + Motion.ScribbleAfterMs, length, drip)
}

private const val MAX_PER_LINE = 2

/** A note held this long is marked while it is held. */
private const val HELD_MS = 1_100L

/** Held this much longer than [HELD_MS], an underline's paint runs its full length. */
private const val DRIP_FULL_MS = 1_800f

/**
 * How much more freely the pen goes at each level, against the standard chances above. [SPARSE]
 * holds it back, [FREQUENT] is eager enough that nearly every eligible word takes the pen, and the
 * spacing rule (never two marks closer than three words) is what thins those out.
 */
private val PenFrequency.eagerness: Float get() = when (this) {
    PenFrequency.SPARSE -> 0.45f
    PenFrequency.STANDARD -> 1.2f
    PenFrequency.FREQUENT -> 12f
}

private val HELD = listOf(Scribble.Loop, Scribble.Underline, Scribble.DoubleUnderline)
private val CROSSED = listOf(Scribble.Strike, Scribble.Cross, Scribble.Scratch, Scribble.Double, Scribble.Hatch)
private val SHOUTED = listOf(Scribble.Burst, Scribble.Loop, Scribble.Box, Scribble.Brackets, Scribble.Caret)
private val SHORT = listOf(Scribble.Strike, Scribble.Cross, Scribble.Swipe, Scribble.Box, Scribble.DotTrail)
private val ANY = listOf(
    Scribble.Strike, Scribble.Double, Scribble.Cross, Scribble.Scratch, Scribble.Hatch,
    Scribble.Zigzag, Scribble.Box, Scribble.Loop, Scribble.Underline, Scribble.Swipe,
    Scribble.EchoLoop, Scribble.Scallop,
)
