/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LyricsFollowTest {
    @Test
    fun `selecting another phrase cannot displace resting rows before scrolling starts`() {
        // The previous remaining-distance formula displaced this row by 31.5 px immediately
        // when the target advanced by one 120 px wrapped line, with no scroll movement at all.
        for (distance in listOf(-2f, -1f, 0f, 1f, 3.5f, 7f)) {
            assertEquals(0f, LyricsFollow.cascadeOffset(distance, 0f, 140f), 0.0001f)
        }
    }

    @Test
    fun `passing a row boundary does not switch the cascade by a whole row`() {
        val before = LyricsFollow.cascadeOffset(4f - 0.9999f, 300f, 140f)
        val after = LyricsFollow.cascadeOffset(4f - 1.0001f, 300f, 140f)
        assertEquals(before, after, 0.001f)
        assertEquals(0f, LyricsFollow.cascadeOffset(0f, 300f, 140f))
    }

    @Test
    fun `wrapped lines interpolate the pixel velocity without a boundary jump`() {
        val tops = intArrayOf(0, 60, 180, 240)
        assertEquals(60f, LyricsFollow.rowHeight(tops, 0f))
        assertEquals(90f, LyricsFollow.rowHeight(tops, 0.5f))
        assertEquals(120f, LyricsFollow.rowHeight(tops, 1f))
        val before = LyricsFollow.rowHeight(tops, 0.9999f)
        val after = LyricsFollow.rowHeight(tops, 1.0001f)
        assertEquals(before, after, 0.02f)
        assertEquals(60f, LyricsFollow.rowHeight(tops, 2f))
        assertEquals(60f, LyricsFollow.rowHeight(tops, -1f))
        assertEquals(60f, LyricsFollow.rowHeight(tops, 99f))
        assertEquals(0f, LyricsFollow.rowHeight(intArrayOf(), 0f))
    }

    @Test
    fun `forward and backward travel mirror each other and settle without residual offsets`() {
        for (distance in listOf(-2f, -0.1f, 0f, 0.1f, 1f, 10f)) {
            val forward = LyricsFollow.cascadeOffset(distance, 250f, 140f)
            assertEquals(-forward, LyricsFollow.cascadeOffset(distance, -250f, 140f), 0.0001f)
            assertEquals(0f, LyricsFollow.cascadeOffset(distance, 0f, 140f), 0.0001f)
            assertTrue(kotlin.math.abs(LyricsFollow.cascadeOffset(distance, 100_000f, 140f)) <= 140f)
        }
    }
}
