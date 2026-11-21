/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import app.valen.core.LyricWord
import app.valen.ui.theme.Motion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The stage's decorative effects. Each is a function of the song time alone, so pausing freezes it,
 * a seek lands it where the song now is and a seek back rewinds it exactly — nothing is held
 * between frames. No setting can take them away: Moving words off and reduced motion keep the same
 * stage, softer (only the pane's own transitions take a shorter tween, which is not arithmetic).
 */
class LyricEffectsTest {
    private val short = LyricWord("on", 1_000, 1_300)
    private val long = LyricWord("lantern", 1_000, 2_000)

    private fun times(from: Long, to: Long, step: Long = 50L) = generateSequence(from) { it + step }.takeWhile { it <= to }.toList()

    @Test
    fun `a short word's ink rises the moment it begins and is gone in a breath, however it is reached`() {
        assertEquals(0f, LyricEffects.ink(900, short), "nothing before it starts")
        assertTrue(LyricEffects.ink(1_000, short) > 0.99f, "full the moment it begins")
        assertEquals(0f, LyricEffects.ink(1_000 + Motion.LyricInkMs, short), "spent by then")
        assertEquals(0f, LyricEffects.ink(1_500, short))
        // A long word gets the lift and the crest instead of the dab.
        assertEquals(0f, LyricEffects.ink(1_050, long))
        val forward = times(1_000, 1_300).map { LyricEffects.ink(it, short) }
        assertTrue(forward.zipWithNext().all { (a, b) -> b <= a }, "it only dries")
        val backward = times(1_000, 1_300).reversed().map { LyricEffects.ink(it, short) }.reversed()
        assertEquals(forward, backward, "a seek back reads exactly what was read on the way in")
        assertEquals(LyricEffects.ink(1_100, short), LyricEffects.ink(1_100, short))
    }

    @Test
    fun `the light blooms, breathes and trails, and a seek back reads the same light`() {
        val start = 0L
        val end = 10_000L
        assertEquals(0f, LyricEffects.field(-100, start, end))
        assertTrue(LyricEffects.field(1_000, start, end) > 0.5f)
        assertEquals(0f, LyricEffects.field(end + Motion.LyricTrailMs + 50, start, end))
        // Breathing: the light rises and falls gently while the line is held, under every setting.
        val t1 = 1_000L
        val t2 = t1 + Motion.LyricFieldBreatheMs.toLong() / 2
        val one = LyricEffects.field(t1, start, end)
        val two = LyricEffects.field(t2, start, end)
        assertTrue(kotlin.math.abs(one - two) > 0.001f, "a held line's light breathes")
        // And it unwinds: a seek back reads what was read before.
        val forward = times(0, end).map { LyricEffects.field(it, start, end) }
        val backward = times(0, end).reversed().map { LyricEffects.field(it, start, end) }.reversed()
        assertEquals(forward, backward)
    }

    @Test
    fun `a mark settles back once its line is over, and a seek back brings it whole`() {
        val end = 4_000L
        assertEquals(1f, LyricEffects.markTrail(4_000, end))
        assertEquals(1f, LyricEffects.markTrail(1_000, end))
        assertEquals(Motion.LyricMarkTrailTo, LyricEffects.markTrail(end + Motion.LyricTrailMs, end), 0.001f)
        assertEquals(Motion.LyricMarkTrailTo, LyricEffects.markTrail(end + Motion.LyricTrailMs * 4, end), 0.001f)
        val forward = times(4_000, 5_200).map { LyricEffects.markTrail(it, end) }
        assertTrue(forward.zipWithNext().all { (a, b) -> b <= a }, "it only settles back")
        val backward = times(4_000, 5_200).reversed().map { LyricEffects.markTrail(it, end) }.reversed()
        assertEquals(forward, backward)
    }

    @Test
    fun `the glint and the held crest travel with the words at every setting`() {
        assertEquals(1f, LyricEffects.glint(0.5f), 0.001f)
        assertEquals(1f, LyricEffects.crestGlow(0.5f), 0.001f)
        assertEquals(0f, LyricEffects.crestGlow(0f), 0.001f)
        assertEquals(LyricEffects.glint(0.25f), LyricEffects.glint(0.75f), 0.001f)
        assertTrue(LyricEffects.glint(0.05f) > 0f, "quiet, but never off, inside a word")
        // The retune: the light following the edge is a hint on the letters, far under the field.
        assertTrue(Motion.LyricGlintAlpha <= Motion.LyricFieldAlpha * 0.7f, "the glint is dimmer than the field it rides")
    }

    @Test
    fun `a held pause's halo gathers over it and is drawn in as the next line comes`() {
        val early = LyricEffects.haloAlpha(0.1f, 0f)
        val late = LyricEffects.haloAlpha(0.9f, 0f)
        assertTrue(early > 0f)
        assertTrue(late > early, "it grows over the pause")
        assertTrue(LyricEffects.haloSpread(0.9f, 0f) > LyricEffects.haloSpread(0.1f, 0f))
        assertEquals(0f, LyricEffects.haloAlpha(0.9f, 1f), 0.001f)
        assertTrue(LyricEffects.haloSpread(0.9f, 1f) < LyricEffects.haloSpread(0.9f, 0f), "it gathers in")
    }
}