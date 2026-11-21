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
