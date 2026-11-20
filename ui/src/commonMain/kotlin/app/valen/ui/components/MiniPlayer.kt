/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import app.valen.ui.theme.LocalReducedMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.snapshotFlow
import kotlin.math.abs
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.valen.core.Track
import app.valen.ui.menu.MenuOrigin
import app.valen.ui.menu.MoreButton
import app.valen.ui.menu.rememberSongMenuOpener
import app.valen.ui.menu.songMenuOnRightClick
import app.valen.ui.artwork.ArtworkTones
import app.valen.ui.theme.Layout
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors

/**
 * The phone's player: a capsule floating above the tab bar, warmed by the cover. Cover, one line
 * each, play and next. Position is a hairline along the bottom, inside the curve. Tapping
 * anywhere else opens the stage.
 */
@Composable
fun MiniPlayer(
    track: Track,
    isPlaying: Boolean,
    canSkipNext: Boolean,
    positionMs: Long,
    durationMs: Long,
    tones: ArtworkTones,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    /** Swiped right: the song before. Without it only a swipe left (next) is taken. */
    onPrevious: (() -> Unit)? = null,
) {
    val swipe = rememberSwipe(
        canNext = canSkipNext,
        canPrevious = onPrevious != null,
        onNext = onNext,
        onPrevious = { onPrevious?.invoke() },
        song = track.key,
    )
    val progress = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val tint by animateColorAsState(tones.chrome, Motion.tint(), label = "miniTint")
    val bar by animateColorAsState(tones.bright, Motion.tint(), label = "miniBar")
    val flights = LocalFlights.current
    GlassSurface(
        shape = Radii.pillShape,
        tint = tint,
        sweepKey = track.key,
        orbit = isPlaying,
        // A song added to the queue flies in and the capsule gulps it.
        gulpKey = flights.landed,
        modifier = modifier
            .fillMaxWidth()
            .height(Layout.miniPlayerHeight)
            .flightDoor(flights)
            .graphicsLayer {
                // Pulled, the capsule is drawn out toward the finger from its other end.
                val pulled = swipe.reach(size.width)
                scaleX = 1f + Motion.SwipeStretch * pulled
                scaleY = 1f - Motion.SwipeStretch * 0.5f * pulled
                transformOrigin = TransformOrigin(if (swipe.offset.value < 0f) 1f else 0f, 0.5f)
            },
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { swipe.width = it.width.toFloat() }
            .then(swipe.gesture)
            .drawWithContent {
                drawContent()
                // Inset by the capsule's own radius, so the line never touches the curve.
                val inset = size.height / 2f
                val thickness = 2.dp.toPx()
                val y = size.height - thickness - 5.dp.toPx()
                val full = size.width - inset * 2f
                val corner = CornerRadius(thickness / 2f, thickness / 2f)
                drawRoundRect(ValenColors.Fill, Offset(inset, y), Size(full, thickness), corner)
                if (progress > 0f) {
                    drawRoundRect(bar, Offset(inset, y), Size(full * progress, thickness), corner)
                }
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .graphicsLayer {
                    // The song follows the finger, thinning as it goes.
                    translationX = swipe.offset.value
                    alpha = 1f - 0.85f * swipe.reach(size.width * 0.6f)
                }
                .tap(onClick = onOpen, onLongClick = rememberSongMenuOpener(track, MenuOrigin.Player))
                .padding(start = Space.step2, end = Space.step2),
        ) {
            PlayerCover(url = track.artworkUrl, size = 44.dp, playing = isPlaying)
            SongLines(
                track = track,
                detail = track.artist,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Space.step3),
            )
            PlayToggle(isPlaying = isPlaying, onToggle = onToggle, iconSize = 30.dp)
            SkipTap(
                icon = Icons.Rounded.SkipNext,
                label = "Next",
                onClick = onNext,
                forward = true,
                enabled = canSkipNext,
                iconSize = 26.dp,
            )
        }
    }
    }
}

/**
 * The mini player's sideways pull: where the song is, how far the glass is drawn out, and what a
 * let-go does. A pull the queue cannot follow (no next song, no way back) barely gives.
 */
private class MiniSwipe(
    private val scope: CoroutineScope,
    private val reduced: Boolean,
    private val canNext: () -> Boolean,
    private val canPrevious: () -> Boolean,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val flingPx: Float,
    private val song: () -> String,
) {
    val offset = Animatable(0f)
    var width = 1f

    /** How far the finger has really gone; [offset] is that with the pull's resistance. */
    private var travel = 0f

    /** How far it is pulled, 0 at rest to 1 at half its width. */
    fun reach(full: Float): Float = (abs(offset.value) / (full / 2f).coerceAtLeast(1f)).coerceIn(0f, 1f)

    fun start() {
        travel = offset.value
    }

    fun drag(by: Float) {
        travel += by
        val next = travel
        val allowed = if (next < 0f) canNext() else canPrevious()
        // Past where it would skip, it pulls harder; a way it cannot go, it hardly moves at all.
        val commit = width * Motion.SwipeCommit
        val eased = when {
            !allowed -> next * 0.12f
            abs(next) > commit -> (commit + (abs(next) - commit) * 0.45f) * if (next < 0f) -1f else 1f
            else -> next
        }
        scope.launch { offset.snapTo(eased) }
    }

    fun release(velocity: Float) {
        val at = offset.value
        val way = if (at < 0f) -1f else 1f
        val allowed = if (at < 0f) canNext() else canPrevious()
        val far = abs(at) >= width * Motion.SwipeCommit * 0.92f
        val flung = abs(velocity) >= flingPx && velocity * way > 0f
        scope.launch {
            if (allowed && at != 0f && (far || flung)) {
                val before = song()
                if (!reduced) offset.animateTo(way * width * 0.6f, tween(Motion.ExitMs, easing = Motion.Exit))
                if (way < 0f) onNext() else onPrevious()
                // Gone, the capsule waits for the next song (never slides the old one back in),
                // which then comes in from the other side as the glass gulps it.
                withTimeoutOrNull(Motion.SwipeWaitMs) { snapshotFlow { song() }.first { it != before } }
                offset.snapTo(if (reduced) 0f else -way * width * 0.3f)
                offset.animateTo(0f, if (reduced) Motion.fade() else Motion.liquid())
            } else {
                offset.animateTo(0f, if (reduced) Motion.fade() else Motion.jelly())
            }
        }
    }

    val gesture: Modifier = Modifier.pointerInput(Unit) {
        val tracker = VelocityTracker()
        detectHorizontalDragGestures(
            onDragStart = {
                tracker.resetTracking()
                start()
            },
            onDragEnd = { release(tracker.calculateVelocity().x) },
            onDragCancel = { release(0f) },
            onHorizontalDrag = { change, amount ->
                change.consume()
                tracker.addPosition(change.uptimeMillis, change.position)
                drag(amount)
            },
        )
    }
}

@Composable
private fun rememberSwipe(
    canNext: Boolean,
    canPrevious: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    song: String,
): MiniSwipe {
    val scope = rememberCoroutineScope()
    val reduced = LocalReducedMotion.current
    val next by rememberUpdatedState(onNext)
    val previous by rememberUpdatedState(onPrevious)
    val mayNext by rememberUpdatedState(canNext)
    val mayPrevious by rememberUpdatedState(canPrevious)
    val shown by rememberUpdatedState(song)
    val fling = with(LocalDensity.current) { Motion.SwipeFlingDp.dp.toPx() }
    return remember(scope, reduced) {
        MiniSwipe(scope, reduced, { mayNext }, { mayPrevious }, { next() }, { previous() }, fling, { shown })
    }
}
