/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.lyrics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import app.valen.core.PlaybackCoordinator
import app.valen.ui.theme.LocalReducedMotion
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.TimeSource

/**
 * Where the song is, for its words. The coordinator reports the position four times a second;
 * words are sung faster than that, so while [smooth] is asked for and the song plays, the time is
 * carried forward on the display's frames from the last report, at the speed the song plays at,
 * and eased onto each new report rather than jumping to it. A report far from the carried time
 * (a seek, a stall) is taken as it is.
 *
 * This is the one place outside the seek bars that asks for frames while a song plays, and only
 * while words with native or estimated timing are on screen: otherwise (and under reduced
 * motion) it follows the reports as they come.
 */
@Composable
internal fun rememberLyricsClock(coordinator: PlaybackCoordinator, playing: Boolean, smooth: Boolean): LongState {
    val reduced = LocalReducedMotion.current
    val sound by coordinator.sound.collectAsState()
    val active by coordinator.active.collectAsState()
    val snapshot by coordinator.snapshot.collectAsState()
    // The speed the song really plays at: the effects' only on a service that applies them (Spotify
    // plays at 1× whatever the notebook says, and carrying its words at 1.25× ran them ahead).
    val rate = remember(sound, active) { coordinator.currentRate() }
    val clock = remember(coordinator) { mutableLongStateOf(coordinator.snapshot.value?.positionMs ?: 0L) }
    // A buffering player does not advance audio, so its lyric clock must not run ahead.
    val frames = smooth && playing && snapshot?.isBuffering != true && !reduced
    LaunchedEffect(coordinator, playing, frames, rate) {
        val since = TimeSource.Monotonic.markNow()
        val initial = coordinator.snapshot.value?.positionMs ?: clock.longValue
        val timebase = LyricsTimebase(initial, 0, rate)
        if (!frames) clock.longValue = initial
        launch {
            coordinator.snapshot.collect { snapshot ->
                val reported = snapshot?.positionMs ?: 0L
                val now = since.elapsedNow().inWholeMilliseconds
                if (!frames) clock.longValue = reported
                else timebase.report(reported, now)
            }
        }
        if (!frames) return@LaunchedEffect
        while (true) {
            withFrameMillis {
                clock.longValue = timebase.positionAt(since.elapsedNow().inWholeMilliseconds)
            }
        }
    }
    return clock
}

/** A continuous display clock between player reports. Adding even a third of an error at the
 * report's arrival is still an instantaneous jump (or a freeze when backwards steps are held).
 * Spread drift corrections over future frames instead, with at most 20% speed adjustment. Large
 * discontinuities still land immediately, so a real seek never waits for the clock to catch up. */
internal class LyricsTimebase(positionMs: Long, atMs: Long, private val rate: Float = 1f) {
    private var anchorPos = positionMs.toDouble()
    private var anchorAt = atMs
    private var correction = 0.0
    private var correctionMs = CORRECTION_MS

    private fun carried(now: Long): Double {
        val elapsed = (now - anchorAt).coerceAtLeast(0).toDouble()
        val p = (elapsed / correctionMs).coerceIn(0.0, 1.0)
        val eased = p * p * (3.0 - 2.0 * p)
        return anchorPos + elapsed * rate + correction * eased
    }

    fun positionAt(now: Long): Long = carried(now).toLong().coerceAtLeast(0)

    fun report(positionMs: Long, atMs: Long) {
        val predicted = carried(atMs)
        val error = positionMs - predicted
        anchorAt = atMs
        if (abs(error) > JUMP_MS) {
            anchorPos = positionMs.toDouble()
            correction = 0.0
        } else {
            anchorPos = predicted
            correction = error
            // Smoothstep's peak slope is 1.5. Keep even a negative correction monotonic at
            // slower playback rates, without holding frames or changing the song's own cues.
            correctionMs = maxOf(CORRECTION_MS, abs(error) * 1.5 / (rate * MAX_RATE_CORRECTION))
        }
    }

    private companion object {
        const val JUMP_MS = 400L
        const val CORRECTION_MS = 500.0
        const val MAX_RATE_CORRECTION = 0.2
    }
}
