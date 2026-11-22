/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import app.valen.core.LyricWord
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LyricSweepTest {
    private fun span(left: Float, right: Float, start: Long, end: Long) =
        LyricSweep.Span(left, right, start, end, 0, 3, 3)

    @Test
    fun `highlight never reverses at rapid syllable joins or overlapping cues`() {
        // A 10 ms prefix followed by a longer syllable, like the native cues in the report.
        val sweep = LyricSweep(listOf(span(0f, 30f, 0, 10), span(30f, 80f, 9, 398),
            span(80f, 110f, 398, 418)), rtl = false)
        val edges = (-1L..450L).map { sweep.edge(it, 20f) }
        assertTrue(edges.zipWithNext().all { (a, b) -> b >= a }, "a new syllable must not unhighlight the previous one")
        assertEquals(-10f, edges.first())
        assertEquals(120f, edges.last())
        // Reading the same times backwards (seeking) gives the exact same drawing.
        assertEquals(edges, (450L downTo -1L).map { sweep.edge(it, 20f) }.asReversed())
    }

    @Test
    fun `adjacent cues share a boundary and a vocal rest holds it still`() {
        val sweep = LyricSweep(listOf(span(0f, 40f, 0, 100), span(60f, 100f, 300, 400)), rtl = false)
        assertEquals(50f, sweep.edge(100, 20f))
        assertEquals(50f, sweep.edge(200, 20f))
        assertEquals(50f, sweep.edge(300, 20f))
        assertTrue(sweep.edge(301, 20f) > 50f)
        assertEquals(110f, sweep.edge(400, 20f))
    }

    @Test
    fun `RTL singing is continuous in the opposite direction`() {
        val sweep = LyricSweep(listOf(span(60f, 100f, 0, 100), span(0f, 60f, 100, 120)), rtl = true)
        val edges = (0L..150L).map { sweep.edge(it, 20f) }
        assertTrue(edges.zipWithNext().all { (a, b) -> b <= a })
        assertEquals(110f, edges.first())
        assertEquals(-10f, edges.last())
        assertEquals(60f, sweep.edge(100, 20f))
    }

    @Test
    fun `a word wrapped across rows keeps the native timing on both pieces`() {
        val first = LyricSweep(listOf(LyricSweep.Span(0f, 30f, 1_000, 2_000, 0, 3, 6)), false)
        val second = LyricSweep(listOf(LyricSweep.Span(0f, 30f, 1_000, 2_000, 3, 3, 6)), false)
        assertEquals(40f, first.edge(1_500, 20f))
        assertEquals(-10f, second.edge(1_500, 20f))
        assertEquals(15f, second.edge(1_750, 20f))
        assertEquals(40f, second.edge(2_000, 20f))
    }

    @Test
    fun `syllables lift as one word without changing their highlight times`() {
        val words = listOf(LyricWord("ev", 1_000, 1_010, false),
            LyricWord("ery", 1_009, 1_398, false), LyricWord("thing", 1_398, 1_582, true),
            LyricWord("glows", 1_600, 1_900, false))
        assertArrayEquals(longArrayOf(1_000, 1_000, 1_000, 1_600), lyricMotionStarts(words))
        assertEquals(1_010, words[0].endMs)
        assertEquals(1_009, words[1].startMs)
    }

    @Test
    fun `unspaced ideographs and embedded whitespace do not lift whole sentences`() {
        val words = listOf(LyricWord("你", 0, 100, false), LyricWord("好", 100, 200, false),
            LyricWord("paper ", 200, 300, false), LyricWord("stars", 300, 400, false))
        assertArrayEquals(longArrayOf(0, 100, 200, 300), lyricMotionStarts(words))
        assertArrayEquals(longArrayOf(), lyricMotionStarts(emptyList()))
    }
}
