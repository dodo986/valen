/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import app.valen.core.LyricLine
import app.valen.core.LyricWord
import app.valen.core.Lyrics
import app.valen.core.LyricsTiming
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AnimationTimingTest {
    @Test
    fun `five second placeholder windows finish their estimated words before the next phrase`() {
        // Reproduces the overlapping line-only windows in the reported recording, with invented text.
        val source = Lyrics(listOf(
            LyricLine(0, 5_000, "lanterns shimmer softly"),
            LyricLine(2_550, 7_550, "paper moons drift"),
            LyricLine(3_920, 8_920, "chalk stars glow"),
        ), LyricsTiming.Lines, "LiriQo")
        val animated = source.withAnimationTiming()
        assertEquals(listOf(2_550L, 3_920L, 8_920L), animated.lines.map { it.endMs })
        assertEquals(listOf(2_550L, 3_920L, 8_920L), animated.lines.map { it.words.last().endMs })
        assertTrue(animated.lines[0].words.all { it.endMs <= source.lines[1].startMs })
        assertTrue(animated.lines[1].words.all { it.endMs <= source.lines[2].startMs })
        assertEquals(listOf(5_000L, 7_550L, 8_920L), source.lines.map { it.endMs })
        assertTrue(source.lines.all { it.words.isEmpty() })
        assertEquals(LyricsTiming.Lines, animated.timing)
    }

    @Test
    fun `an explicit short phrase keeps the instrumental gap before the next phrase`() {
        val source = Lyrics(listOf(LyricLine(0, 1_500, "chalk moons"), LyricLine(5_000, 6_000, "paper stars")), LyricsTiming.Lines, "test")
        val animated = source.withAnimationTiming()
        assertEquals(1_500L, animated.lines.first().endMs)
        assertEquals(1_500L, animated.lines.first().words.last().endMs)
    }

    @Test
    fun `another singer or a backing phrase does not shorten the lead estimate`() {
        val source = Lyrics(listOf(
            LyricLine(1_000, 6_000, "lanterns glow", voice = "v1"),
            LyricLine(1_500, 5_000, "paper stars", voice = "v2"),
            LyricLine(2_000, 3_000, "oh again", voice = "v1", aside = true),
            LyricLine(4_000, 9_000, "chalk moons", voice = "v1"),
        ), LyricsTiming.Lines, "test")
        val animated = source.withAnimationTiming()
        assertEquals(4_000L, animated.lines[0].words.last().endMs)
        assertEquals(5_000L, animated.lines[1].words.last().endMs)
        assertEquals(3_000L, animated.lines[2].words.last().endMs)
    }

    @Test
    fun `same start labels and unsorted input never make zero length estimates`() {
        val source = Lyrics(listOf(
            LyricLine(4_000, 9_000, "chalk moons"),
            LyricLine(1_000, 6_000, "lanterns glow"),
            LyricLine(1_000, 6_000, "paper stars"),
        ), LyricsTiming.Lines, "test")
        val lines = source.withAnimationTiming().lines
        assertEquals(listOf(9_000L, 4_000L, 4_000L), lines.map { it.endMs })
        assertTrue(lines.flatMap { it.words }.all { it.endMs > it.startMs })
    }

    @Test
    fun `native overlapping lead and backing times survive the next phrase intact`() {
        val native = LyricLine(1_000, 7_000, "lantern", words = listOf(LyricWord("lantern", 1_234, 6_876, false)))
        val backing = listOf(LyricWord("oh", 2_000, 6_500, false))
        val under = LyricLine(1_000, 7_000, "paper stars", backing = backing, backingText = "oh")
        val next = LyricLine(3_000, 8_000, "chalk moons")
        val sung = Lyrics(listOf(native, next), LyricsTiming.Words, "test").withAnimationTiming()
        assertSame(native, sung.lines.first())
        val withBacking = Lyrics(listOf(under, next), LyricsTiming.Words, "test").withAnimationTiming().lines.first()
        assertEquals(3_000L, withBacking.words.last().endMs)
        assertSame(backing, withBacking.backing)
        assertEquals(7_000L, withBacking.endMs)
    }

    @Test
    fun `line animation spans the phrase with longer intervals for longer words`() {
        val source = Lyrics(listOf(LyricLine(1_000, 9_000, "a lantern glows")), LyricsTiming.Lines, "LiriQo")
        val animated = source.withAnimationTiming()
        val words = animated.lines.single().words
        assertEquals(listOf("a", "lantern", "glows"), words.map { it.text })
        assertEquals(1_000, words.first().startMs)
        assertEquals(9_000, words.last().endMs)
        assertTrue(words.all { it.endMs > it.startMs })
        assertTrue(words.zipWithNext().all { (a, b) -> a.endMs == b.startMs })
        assertTrue(words[1].endMs - words[1].startMs > words[0].endMs - words[0].startMs)
        assertTrue(source.lines.single().words.isEmpty(), "source and cached lyrics stay untouched")
        assertEquals(LyricsTiming.Lines, animated.timing, "estimates must not be labelled native word timing")
        assertEquals(source.source, animated.source)
        assertEquals(source.lines.single().text, animated.lines.single().text)
    }

    @Test
    fun `Unicode punctuation and spacing survive animation tokenization`() {
        val text = "  Olá,\t  coração! 🌙  "
        val animated = Lyrics(listOf(LyricLine(0, 5_000, text)), LyricsTiming.Lines, "test").withAnimationTiming()
        val words = animated.lines.single().words
        assertEquals(text, words.joinToString("") { it.text + if (it.space) " " else "" })
        assertEquals(3, words.size)
        assertTrue(words.last().text.contains("🌙"))
    }

    @Test
    fun `native timing including backing vocals and overlaps is preserved exactly`() {
        val line = LyricLine(1_000, 5_000, "paper stars",
            words = listOf(LyricWord("paper", 1_234, 2_600), LyricWord("stars", 2_400, 4_876, false)),
            backing = listOf(LyricWord("oh", 1_555, 4_555, false)), backingText = "oh", voice = "v2")
        val source = Lyrics(listOf(line), LyricsTiming.Words, "LiriQo")
        assertSame(source, source.withAnimationTiming())
        val mixed = source.copy(lines = listOf(line, LyricLine(6_000, 9_000, "chalk moons")))
        val animated = mixed.withAnimationTiming()
        assertSame(line, animated.lines.first())
        assertEquals(2, animated.lines.last().words.size)
    }

    @Test
    fun `backing phrases get display timing while voices and line bounds stay intact`() {
        val line = LyricLine(5_000, 9_000, "lantern light", backingText = "glow again", voice = "v1", aside = true)
        val animated = Lyrics(listOf(line), LyricsTiming.Lines, "test").withAnimationTiming().lines.single()
        assertEquals(2, animated.backing.size)
        assertEquals(line, animated.copy(words = emptyList(), backing = emptyList()))
        assertEquals(line.startMs, animated.backing.first().startMs)
        assertEquals(line.endMs, animated.backing.last().endMs)
    }

    @Test
    fun `untimed instrumental and invalid intervals never acquire invented animation bounds`() {
        val untimed = Lyrics(listOf(LyricLine(0, 0, "paper stars")), LyricsTiming.None, "test")
        assertSame(untimed, untimed.withAnimationTiming())
        val instrumental = Lyrics.instrumental("test")
        assertSame(instrumental, instrumental.withAnimationTiming())
        for (line in listOf(LyricLine(2_000, 1_000, "backwards"), LyricLine(-1, 1_000, "negative"),
            LyricLine(0, 1, "too many words"), LyricLine(0, 5_000, "   "))) {
            assertSame(line, Lyrics(listOf(line), LyricsTiming.Lines, "test").withAnimationTiming().lines.single())
        }
    }

    @Test
    fun `phrase marks use song time and unwind after a backwards seek`() {
        val source = Lyrics(listOf(LyricLine(1_000, 25_000, "lanterns shimmer softly")), LyricsTiming.Lines, "test")
        val animated = source.withAnimationTiming()
        val seed = (0..100).first { markWords(animated, it).flatten().any { mark -> mark != null } }
        val marks = markWords(animated, seed).flatten().filterNotNull()
        assertTrue(marks.isNotEmpty())
        assertTrue(marks.size <= 2)
        assertTrue(marks.all { it.progress(30_000) == 1f && it.progress(0) == 0f })
        assertEquals(marks.map { it.kind to it.startMs }, markWords(animated, seed).flatten().filterNotNull().map { it.kind to it.startMs })
        assertTrue(markWords(source, seed).flatten().isEmpty())
    }
}
