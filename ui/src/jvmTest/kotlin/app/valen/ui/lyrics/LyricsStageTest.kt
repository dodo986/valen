/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import androidx.compose.ui.unit.Density
import app.valen.ui.theme.Motion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The stage's place in the song, and the depth of field that follows from it. Both are pure, so a
 * pause holds them, a seek lands them and a seek back unwinds them — and a line's plane never
 * depends on how it was reached.
 */
class LyricsStageTest {
    private companion object {
        /** The credit row's own time: always the last of a pane's rows. */
        const val CREDIT = Long.MAX_VALUE / 4
    }

    /**
     * Four lines at 1s, 5s, 9s and 13s, one second each, and the credit that always ends a pane's
     * rows: the song can stand on a line, never on the credit.
     */
    private val starts = longArrayOf(1_000, 5_000, 9_000, 13_000, CREDIT)
    private val ends = longArrayOf(2_000, 6_000, 10_000, 14_000, CREDIT)

    /** The same song built fresh, each line living until its light has trailed off. */
    private fun song(live: LongArray = LongArray(5) { if (it < 4) ends[it] + Motion.LyricTrailMs else CREDIT }) =
        LyricStage(starts, ends, live)

    @Test
    fun `the same song time always stands on the same row, so a pause holds the words still`() {
        val stage = song()
        assertEquals(-1, stage.active(0))
        assertEquals(0, stage.active(1_000))
        assertEquals(0, stage.active(4_999), "a row is left only when the next one begins")
        assertEquals(1, stage.active(5_000))
        assertEquals(3, stage.active(900_000), "past the last line the words stay on it")
        // A pause reads the same time again and again: nothing has moved.
        assertEquals(2, stage.active(9_500))
        assertEquals(2, stage.active(9_500))
        assertEquals(stage.sung(9_500, 2), stage.sung(9_500, 2))
        assertEquals(stage.trailing(9_500, 2, stage.sung(9_500, 2)), stage.trailing(9_500, 2, stage.sung(9_500, 2)))
    }

    @Test
    fun `a seek forward reads later rows, and a seek back unwinds to what was there before`() {
        val stage = song()
        val times = listOf(500L, 1_500L, 5_500L, 9_500L, 13_500L, 20_000L)
        val forward = times.map { stage.active(it) }
        assertEquals(listOf(-1, 0, 1, 2, 3, 3), forward)
        // Walking the song backwards gives exactly the answers that were read on the way up.
        val backward = times.asReversed().map { stage.active(it) }.asReversed()
        assertEquals(forward, backward)
    }

    @Test
    fun `a line is sung until its own end and then is left with its ink and its light`() {
        val stage = song()
        assertEquals(1, stage.sung(5_400, 1), "the row on show is sung before its end")
        assertEquals(-1, stage.sung(6_000, 1), "at its end it is no longer being sung")
        assertEquals(-1, stage.sung(6_500, 1))
        // Its marks and its light still need the clock for a moment after it: it is left trailing.
        assertTrue(1 in stage.trailing(6_500, 1, -1))
        assertEquals(1, stage.trailing(6_500, 1, -1).first, "only the rows still alight come along")
        // Once that is over, it is simply past.
        assertTrue(stage.trailing(8_200, 1, -1).isEmpty())
    }

    @Test
    fun `one song's stage cannot answer for another`() {
        val a = song()
        val b = LyricStage(longArrayOf(0, 30_000, CREDIT), longArrayOf(20_000, 60_000, CREDIT), longArrayOf(20_900, 60_900, CREDIT))
        assertEquals(0, b.active(1_000))
        assertEquals(0, a.active(1_000), "the other song's stage is its own")
        assertEquals(1, a.active(5_000))
        assertEquals(0, b.active(5_000), "and it never learns from it")
    }

    @Test
    fun `the depth of field is fullest at the song and eases away both ways`() {
        assertEquals(1f, LyricsDepth.focus(0))
        assertTrue(LyricsDepth.focus(1) < 1f)
        assertTrue(LyricsDepth.focus(2) < LyricsDepth.focus(1))
        assertEquals(Motion.LyricDepthFloor, LyricsDepth.focus(12))
        assertEquals(Motion.LyricDepthFloor, LyricsDepth.focus(-12))
        // A line behind the song settles back a touch quicker than one to come.
        assertTrue(LyricsDepth.focus(-2) < LyricsDepth.focus(2))
    }

    @Test
    fun `the active line keeps its own tone and is never blurred`() {
        for (density in listOf(Density(1f), Density(2f), Density(2.75f))) {
            assertEquals(0f, LyricsDepth.blurPx(LyricsDepth.focus(0), density), "the sung line stays crisp")
            assertTrue(LyricsDepth.blurPx(LyricsDepth.focus(3), density) > 0f)
            val far = density.run { Motion.LyricBlurFarDp.toPx() }
            assertTrue(LyricsDepth.blurPx(LyricsDepth.focus(20), density) <= far + 0.25f, "blur stays close to the words")
            // The depth reads: the line beside the song is already visibly soft, the far ones softer.
            assertTrue(LyricsDepth.blurPx(LyricsDepth.focus(1), density) >= 1.5f * density.density, "the line beside the song softens")
            assertTrue(LyricsDepth.blurPx(LyricsDepth.focus(20), density) >= far * 0.7f, "and the far ones really recede")
        }
        assertEquals(1f, LyricsDepth.alpha(1f, blur = true))
        assertEquals(1f, LyricsDepth.alpha(1f, blur = false))
        assertEquals(0f, LyricsDepth.tint(1f, blur = false), "the active line takes no tint")
    }

    @Test
    fun `without a blur the recede leans on opacity and tone, and blur is only spent near the song`() {
        val focus = LyricsDepth.focus(4)
        assertTrue(LyricsDepth.alpha(focus, blur = false) < LyricsDepth.alpha(focus, blur = true), "no blur, deeper recede")
        assertTrue(LyricsDepth.tint(focus, blur = false) > 0f)
        assertEquals(0f, LyricsDepth.tint(focus, blur = true), "with a blur the tone is left alone")

        assertTrue(LyricsDepth.blurs(0, enabled = true))
        assertTrue(LyricsDepth.blurs(Motion.LyricBlurLines, enabled = true))
        assertFalse(LyricsDepth.blurs(Motion.LyricBlurLines + 1, enabled = true), "no layer on words out of sight")
        assertFalse(LyricsDepth.blurs(-(Motion.LyricBlurLines + 1), enabled = true))
        assertFalse(LyricsDepth.blurs(1, enabled = false), "no blur where the platform cannot make one")
        assertFalse(LyricsDepth.blurs(0, enabled = false))
    }
}
