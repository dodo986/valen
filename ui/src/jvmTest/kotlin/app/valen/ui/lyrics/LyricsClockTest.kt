/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LyricsClockTest {
    @Test
    fun `quarter second player jitter never jumps the highlight or freezes frames`() {
        val clock = LyricsTimebase(0, 0)
        val shown = mutableListOf<Long>()
        for (at in 0L..10_000L step 10L) {
            if (at > 0 && at % 250L == 0L) {
                val before = clock.positionAt(at)
                clock.report(at + if ((at / 250) % 2 == 1L) 120 else -120, at)
                assertEquals(before, clock.positionAt(at), "a report must not move the display at $at")
            }
            shown += clock.positionAt(at)
        }
        val steps = shown.zipWithNext().map { (a, b) -> b - a }
        assertTrue(steps.all { it in 7L..13L }, "10 ms frames must not jump 58 ms or freeze: ${steps.minOrNull()}..${steps.maxOrNull()}")
        assertTrue(kotlin.math.abs(shown.last() - 10_000) < 120)
    }

    @Test
    fun `an early or late report converges without moving at the moment it arrives`() {
        for (error in listOf(-300L, -120L, 120L, 300L)) {
            val clock = LyricsTimebase(1_000, 0)
            clock.report(1_250 + error, 250)
            assertEquals(1_250, clock.positionAt(250))
            assertEquals(6_000 + error, clock.positionAt(5_000))
            val steps = (250L..5_000L step 10L).map(clock::positionAt).zipWithNext().map { (a, b) -> b - a }
            assertTrue(steps.all { it in 7L..13L })
        }
    }

    @Test
    fun `a real seek lands immediately forward or backward and forgets earlier drift`() {
        val clock = LyricsTimebase(5_000, 0)
        clock.report(5_370, 250)
        clock.report(25_000, 300)
        assertEquals(25_000, clock.positionAt(300))
        assertEquals(25_100, clock.positionAt(400))
        clock.report(1_000, 450)
        assertEquals(1_000, clock.positionAt(450))
        assertEquals(1_100, clock.positionAt(550))
    }

    @Test
    fun `corrections remain monotonic at slower and faster playback speeds`() {
        for (rate in listOf(0.5f, 1f, 1.5f, 2f)) {
            val clock = LyricsTimebase(1_000, 0, rate)
            val at = 500L
            val predicted = 1_000 + (at * rate).toLong()
            clock.report(predicted - 300, at)
            assertEquals(predicted, clock.positionAt(at))
            val steps = (at..6_000L step 10L).map(clock::positionAt).zipWithNext().map { (a, b) -> b - a }
            assertTrue(steps.all { it >= (10 * rate * 0.8).toLong() - 1 && it <= (10 * rate * 1.2).toLong() + 1 })
            assertEquals(1_000 + (6_000 * rate).toLong() - 300, clock.positionAt(6_000))
        }
    }

    @Test
    fun `repeated reads at the same time hold the drawing and normal reports accumulate no drift`() {
        val clock = LyricsTimebase(0, 0)
        for (at in 0L..60_000L step 250L) {
            clock.report(at, at)
            assertEquals(at, clock.positionAt(at))
            assertEquals(clock.positionAt(at), clock.positionAt(at))
            assertEquals(at + 100, clock.positionAt(at + 100))
        }
    }
}
