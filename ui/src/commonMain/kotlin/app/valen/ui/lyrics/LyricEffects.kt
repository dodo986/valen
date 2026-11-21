/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import app.valen.core.LyricWord
import app.valen.ui.theme.Motion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The stage's decorative effects, as arithmetic over song time and the words' own intervals. Every
 * one of them is a function of the time it is drawn at, so nothing is carried between frames: a
 * pause freezes them where they are (the clock stops), a seek lands them where the song now is,
 * a seek back unwinds them, and the same recording always draws the same way. None of them touch
 * the lyrics' timing, which stays the source's.
 *
 * Nothing here takes a settings mode. Settings → Lyrics → Moving words off and the system's
 * reduced motion hold the same stage, softer: the effects are always drawn, and only the pane's
 * own transitions take the shorter tween ([Motion.soft]). Motion is never stripped to a still
 * picture — the words keep moving under every setting.
 */
internal object LyricEffects {
    /** A line that is not on show (upcoming or long past) is read at one of these. */
    private const val FAR = Long.MAX_VALUE / 8

    /**
     * The share of the light field's full strength behind the line of [startMs]–[endMs] at [t]:
     * it blooms in as the line arrives, breathes gently while it is sung, and trails off once the
     * line is over ([Motion.LyricTrailMs]).
     */
    fun field(t: Long, startMs: Long, endMs: Long): Float {
        if (t <= -FAR || t >= FAR) return 0f
        if (t < startMs) return 0f
        val arrive = Motion.Enter.transform(((t - startMs).toFloat() / Motion.LyricFieldInMs).coerceIn(0f, 1f))
        val out = if (t <= endMs) 1f else (1f - (t - endMs).toFloat() / Motion.LyricTrailMs.toFloat()).coerceIn(0f, 1f)
        val leave = Motion.Exit.transform(out)
        val phase = (t % Motion.LyricFieldBreatheMs.toLong()).toDouble() / Motion.LyricFieldBreatheMs
        val breathe = 1.0 - Motion.LyricFieldBreathe * (0.5 - 0.5 * cos(2.0 * PI * phase))
        return (arrive * leave * breathe).toFloat().coerceIn(0f, 1f)
    }

    /**
     * How bright the glint at the sung edge is while the edge is inside a word at [share] of it:
     * quiet as the word begins and ends, fullest halfway through, so the light reads as passing
     * through the letters rather than a lamp sitting on them.
     */
    fun glint(share: Float): Float =
        (0.4f + 0.6f * sin(share.coerceIn(0f, 1f) * PI.toFloat())).coerceIn(0f, 1f)

    /**
     * The dab of ink a short word takes as it begins, 0–1: full the moment it starts and gone
     * within [Motion.LyricInkMs]. Long words get the lift and the crest instead.
     */
    fun ink(t: Long, word: LyricWord): Float {
        if (word.endMs - word.startMs > Motion.LyricInkShortMs) return 0f
        val since = t - word.startMs
        if (since < 0L || since > Motion.LyricInkMs) return 0f
        val through = (since.toFloat() / Motion.LyricInkMs).coerceIn(0f, 1f)
        return (1f - Motion.Enter.transform(through)).coerceIn(0f, 1f)
    }

    /**
     * How strong the glow riding a held note's crest is at [local], its own 0–1 moment through the
     * note: strongest where the crest is highest, so the light travels the word with the letters.
     */
    fun crestGlow(local: Float): Float = sin(local.coerceIn(0f, 1f) * PI.toFloat()).coerceIn(0f, 1f)

    /** How far a pause's faint halo has spread at [g] of the pause, gathering as it leaves. */
    fun haloSpread(g: Float, leaving: Float): Float {
        val grown = 0.35f + 0.65f * Motion.Enter.transform(g.coerceIn(0f, 1f))
        return (grown * (1f - Motion.LyricHaloGrow * leaving.coerceIn(0f, 1f))).coerceIn(0f, 1.5f)
    }

    /** How strong a pause's halo is at [g] of it, fading as [leaving] takes the next line in. */
    fun haloAlpha(g: Float, leaving: Float): Float {
        val grown = 0.3f + 0.7f * Motion.Enter.transform(g.coerceIn(0f, 1f))
        return (Motion.LyricHaloAlpha * grown * (1f - leaving.coerceIn(0f, 1f))).coerceIn(0f, 1f)
    }

    /**
     * The share of its tone a mark keeps once its line is over: full to the end of the line, then
     * settling to [Motion.LyricMarkTrailTo] over [Motion.LyricTrailMs]. Drawn from the song's time,
     * so a seek back brings the mark back whole.
     */
    fun markTrail(t: Long, lineEndMs: Long): Float {
        if (t <= lineEndMs) return 1f
        val through = ((t - lineEndMs).toFloat() / Motion.LyricTrailMs.toFloat()).coerceIn(0f, 1f)
        return 1f - (1f - Motion.LyricMarkTrailTo) * Motion.Exit.transform(through)
    }
}