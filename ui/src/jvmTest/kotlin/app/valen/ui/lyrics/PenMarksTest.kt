/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import app.valen.core.LyricLine
import app.valen.core.LyricWord
import app.valen.core.Lyrics
import app.valen.core.LyricsTiming
import app.valen.core.PenFrequency
import app.valen.ui.theme.Motion
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The pen's marks, as pure logic: the new strokes build, the choice is deterministic and thinned or
 * thickened by [PenFrequency], the spacing and per-line limits hold, and every mark is drawn from
 * song time so a seek back unwrites it. No Compose or Skiko UI: construction and arithmetic only.
 */
class PenMarksTest {
    private val newMarks = listOf(
        Scribble.Brackets,
        Scribble.DoubleUnderline,
        Scribble.DotTrail,
        Scribble.EchoLoop,
        Scribble.Caret,
        Scribble.Scallop,
    )

    @Test
    fun `every new pen mark builds its strokes for a word box`() {
        val box = WordBox(0f, 48f, 40f, 20f)
        // Skiko unpacks its native library beside the user's home, which the test harness here
        // holds read-only; point it at a writable folder first. Where even that cannot load, this
        // one drawing check steps aside and the rest of the class still runs.
        val ready = runCatching {
            if (System.getProperty("skiko.data.path") == null) {
                val scratch = File(System.getProperty("java.io.tmpdir"), "valen-pen-test")
                if (scratch.mkdirs() || scratch.isDirectory) {
                    System.setProperty("skiko.data.path", scratch.absolutePath)
                }
            }
            scribbleInk(Scribble.Caret, box, 0, density = 2f)
        }
        assumeTrue(ready.isSuccess, "Skiko is not loadable here: ${ready.exceptionOrNull()}")
        val leastStrokes = mapOf(
            Scribble.Brackets to 4,
            Scribble.DoubleUnderline to 2,
            Scribble.DotTrail to 5,
            Scribble.EchoLoop to 2,
            Scribble.Caret to 1,
            Scribble.Scallop to 1,
        )
        for ((kind, atLeast) in leastStrokes) {
            for (seed in 0 until 24) {
                val ink = scribbleInk(kind, box, seed, density = 2f)
                assertTrue(ink.strokeCount >= atLeast, "$kind should make at least $atLeast strokes")
                assertTrue(ink.width > 0f, "$kind should carry the marker's width")
            }
        }
    }

    @Test
    fun `the same song seed and level always mark the same way`() {
        val lyrics = corpus()
        for (frequency in PenFrequency.entries) {
            assertEquals(
                fingerprint(markWords(lyrics, 4242, frequency)),
                fingerprint(markWords(lyrics, 4242, frequency)),
                "$frequency should mark the same way every time",
            )
        }
        assertEquals(
            fingerprint(markWords(lyrics, 4242)),
            fingerprint(markWords(lyrics, 4242, PenFrequency.STANDARD)),
            "the two-argument call is still the standard pen",
        )
        assertNotEquals(
            fingerprint(markWords(lyrics, 4242, PenFrequency.SPARSE)),
            fingerprint(markWords(lyrics, 4242, PenFrequency.STANDARD)),
            "a level change should change the marks",
        )
        assertNotEquals(
            fingerprint(markWords(lyrics, 4242, PenFrequency.STANDARD)),
            fingerprint(markWords(lyrics, 4242, PenFrequency.FREQUENT)),
            "a level change should change the marks",
        )
    }

    @Test
    fun `the levels thin or thicken the pen around the promised rates`() {
        val lyrics = corpus()
        val seeds = 0 until 40
        val eligible = eligibleWords(lyrics) * seeds.count()
        val sparse = marksOver(lyrics, seeds, PenFrequency.SPARSE).toDouble() / eligible
        val standard = marksOver(lyrics, seeds, PenFrequency.STANDARD).toDouble() / eligible
        val frequent = marksOver(lyrics, seeds, PenFrequency.FREQUENT).toDouble() / eligible
        println(
            "pen frequency over $eligible eligible words: " +
                "sparse one in ${1 / sparse}, standard one in ${1 / standard}, frequent one in ${1 / frequent}",
        )
        assertTrue(sparse < standard, "sparse should be lighter than standard (one in ${1 / sparse})")
        assertTrue(standard < frequent, "standard should be lighter than frequent (one in ${1 / frequent})")
        assertTrue(
            standard in (1.0 / 6.8)..(1.0 / 4.0),
            "standard should be about one in five, was one in ${1 / standard}",
        )
        assertTrue(sparse < standard * 0.7, "sparse should be clearly lighter, was one in ${1 / sparse}")
        assertTrue(
            sparse in (1.0 / 12.0)..(1.0 / 7.0),
            "sparse should be about one in nine or ten, was one in ${1 / sparse}",
        )
        assertTrue(frequent > standard * 1.3, "frequent should be clearly denser, was one in ${1 / frequent}")
        assertTrue(frequent > 1.0 / 3.6, "frequent should hit the spacing rule, was one in ${1 / frequent}")
    }

    @Test
    fun `every new pen mark is reachable`() {
        val lyrics = corpus()
        val reached = mutableSetOf<Scribble>()
        for (seed in 0 until 60) {
            for (frequency in PenFrequency.entries) {
                markWords(lyrics, seed, frequency).forEach { line ->
                    line.forEach { mark -> mark?.let { reached += it.kind } }
                }
            }
        }
        for (kind in newMarks) assertTrue(kind in reached, "$kind should be reachable")
    }

    @Test
    fun `marks keep their spacing count and held rule`() {
        val lyrics = corpus()
        for (seed in 0 until 24) {
            for (frequency in PenFrequency.entries) {
                val marks = markWords(lyrics, seed, frequency)
                marks.forEach { line ->
                    assertTrue(line.count { it != null } <= 2, "at most two marks a line")
                }
                val flat = ArrayList<Pair<Int, Mark>>()
                var base = 0
                marks.forEach { line ->
                    line.forEachIndexed { wi, mark -> mark?.let { flat += (base + wi) to it } }
                    base += line.size
                }
                flat.zipWithNext { a, b ->
                    assertTrue(b.first - a.first >= 3, "marks must never sit side by side")
                    assertNotEquals(a.second.kind, b.second.kind, "the same kind never twice running")
                }
                marks.forEachIndexed { li, line ->
                    line.forEachIndexed { wi, mark ->
                        val word = lyrics.lines[li].words[wi]
                        if (mark != null && word.endMs - word.startMs >= 1_100L) {
                            assertTrue(mark.kind.whileHeld, "a held note takes only a mark held with it")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `every new pen mark is drawn by time and unwinds on a seek back`() {
        for (kind in newMarks) {
            val mark = Mark(kind, seed = 5, startMs = 1_000L, durationMs = Motion.ScribbleMs, drip = 0.6f)
            assertEquals(0f, mark.progress(0L), "$kind is unwritten before its start")
            assertEquals(0f, mark.progress(1_000L), "$kind is unwritten at its start")
            assertTrue(mark.progress(1_400L) in 0f..1f, "$kind is part-drawn in between")
            assertEquals(1f, mark.progress(2_000L), "$kind is whole at its end")
            assertEquals(1f, mark.progress(60_000L), "$kind stays whole after it")
        }
    }

    /** A corpus of ordinary lyric words: short and long, some held, some repeated, some shouted. */
    private fun corpus(lines: Int = 60, perLine: Int = 5): Lyrics {
        val words = listOf(
            "the", "night", "you", "falling", "me", "heart", "we", "together", "and", "waiting",
            "so", "echoes", "on", "silver", "it", "lantern", "of", "morning", "in", "paper",
        )
        var at = 0L
        val built = ArrayList<LyricLine>(lines)
        for (li in 0 until lines) {
            val row = ArrayList<LyricWord>(perLine)
            var previous = ""
            for (wi in 0 until perLine) {
                val index = li * perLine + wi
                var text = words[index % words.size]
                if (index % 9 == 4) text += "!"
                if (wi > 0 && index % 11 == 3) text = previous.trimEnd('!', '?')
                val duration = if (index % 13 == 0) 1_500L else 300L
                row += LyricWord(text, at, at + duration)
                at += duration
                previous = text
            }
            built += LyricLine(row.first().startMs, row.last().endMs, row.joinToString(" ") { it.text }, row)
        }
        return Lyrics(built, LyricsTiming.Words, "test")
    }

    private fun eligibleWords(lyrics: Lyrics): Int = lyrics.lines.sumOf { line ->
        line.words.count { word ->
            val norm = word.text.lowercase().filter { it.isLetterOrDigit() }
            norm.length >= 2 || (norm.length == 1 && norm[0].code >= 0x2E80)
        }
    }

    private fun marksOver(lyrics: Lyrics, seeds: IntRange, frequency: PenFrequency): Int =
        seeds.sumOf { seed -> markWords(lyrics, seed, frequency).sumOf { line -> line.count { it != null } } }

    private fun fingerprint(marks: List<List<Mark?>>): List<String> = marks.flatMap { line ->
        line.map { mark ->
            mark?.let { "${it.kind}|${it.seed}|${it.startMs}|${it.durationMs}|${it.drip}" } ?: "-"
        }
    }
}
