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
        var anchorPos = coordinator.snapshot.value?.positionMs ?: clock.longValue
        var anchorAt = since.elapsedNow().inWholeMilliseconds
        fun carried(now: Long) = if (frames) anchorPos + ((now - anchorAt) * rate).toLong() else anchorPos
        if (!frames) clock.longValue = anchorPos
        launch {
            coordinator.snapshot.collect { snapshot ->
                val reported = snapshot?.positionMs ?: 0L
                val now = since.elapsedNow().inWholeMilliseconds
                val off = reported - carried(now)
                if (!frames || abs(off) > JUMP_MS) {
                    anchorPos = reported
                } else {
                    // A small difference is taken a third at a time, so the words never stutter.
                    anchorPos = carried(now) + off / 3
                }
                anchorAt = now
                if (!frames) clock.longValue = reported
            }
        }
        if (!frames) return@LaunchedEffect
        while (true) {
            withFrameMillis {
                val next = carried(since.elapsedNow().inWholeMilliseconds)
                val shown = clock.longValue
                // Small steps back are held (the next report brings the time forward again);
                // a seek back is a real jump and is followed.
                if (next >= shown || shown - next > HOLD_BACK_MS) clock.longValue = next
            }
        }
    }
    return clock
}

/** A report this far from the carried time is a seek, not drift. */
private const val JUMP_MS = 400L

/** A step back smaller than this is drift and is not shown. */
private const val HOLD_BACK_MS = 300L
