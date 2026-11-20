/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.unit.lerp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import app.valen.ui.theme.LocalReducedMotion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * The player on wide layouts: a capsule floating over the bottom of the content, warmed by the
 * cover of what is playing. One line: the song, the transport, the position, then queue and
 * volume, with shuffle and repeat either side of the transport. As the window narrows it gives up
 * volume first, then the times, then shuffle and repeat, then the rail.
 *
 * Cover and title open the stage.
 */
@Composable
fun PlayerBar(
    track: Track,
    isPlaying: Boolean,
    isBuffering: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    positionMs: Long,
    durationMs: Long,
    volume: Float,
    tones: ArtworkTones,
    onOpen: () -> Unit,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
    modes: PlayModes? = null,
    /** How many songs the queue holds, read where it is drawn: a song dropped in is swallowed. */
    queueSize: () -> Int = { 0 },
) {
    val tint by animateColorAsState(tones.chrome, Motion.tint(), label = "dockTint")
    val reduced = LocalReducedMotion.current
    // The player is born as a drop the size of its own height and spreads into the capsule.
    val bloom = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (!reduced) bloom.animateTo(1f, Motion.bloom()) }
    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .widthIn(max = Layout.dockMaxWidth)
            .fillMaxWidth()
            .height(Layout.dockHeight),
    ) {
    val full = maxWidth
    val spread = bloom.value
    GlassSurface(
        shape = Radii.pillShape,
        tint = tint,
        sweepKey = track.key,
        orbit = isPlaying,
        modifier = Modifier
            .width(lerp(Layout.dockHeight, full, spread))
            .fillMaxHeight(),
    ) {
    BoxWithConstraints(
        modifier = Modifier
            .requiredWidth(full)
            .fillMaxHeight()
            .graphicsLayer { alpha = ((bloom.value - 0.45f) / 0.55f).coerceIn(0f, 1f) },
    ) {
        val showVolume = maxWidth >= 860.dp
        val showTimes = maxWidth >= 640.dp
        val showRail = maxWidth >= 520.dp
        val showModes = maxWidth >= 860.dp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(start = Space.step3, end = Space.step4),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .tap(onClick = onOpen, shape = Radii.pillShape)
                    .padding(end = Space.step3),
            ) {
                PlayerCover(url = track.artworkUrl, size = 48.dp, playing = isPlaying)
                SongLines(
                    track = track,
                    detail = "${track.artist}  ·  ${track.provider.short}",
                    modifier = Modifier.padding(start = Space.step3),
                )
            }
            TransportBar(
                isPlaying = isPlaying,
                isBuffering = isBuffering,
                canSkipPrevious = canSkipPrevious,
                canSkipNext = canSkipNext,
                onPrevious = onPrevious,
                onToggle = onToggle,
                onNext = onNext,
                playSize = 34.dp,
                skipSize = 26.dp,
                gap = 0.dp,
                modes = modes.takeIf { showModes },
            )
            if (showRail) {
                Spacer(Modifier.width(Space.step3))
                InlineSeek(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    onSeek = onSeek,
                    showTimes = showTimes,
                    modifier = Modifier.weight(1.25f),
                )
            }
            Spacer(Modifier.width(Space.step2))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.step1),
            ) {
                QueueGlyph(count = queueSize(), song = track.key, onClick = onQueue)
                MoreButton(track = track, origin = MenuOrigin.Player)
                if (showVolume) {
                    VolumeSlider(volume = volume, onVolume = onVolume, railWidth = 88.dp)
                }
            }
        }
    }
    }
    }
}

/**
 * A song's two lines in the player. When the song changes, the new lines rise into place as the
 * old ones lift away, so the player says "next" rather than flickering.
 */
@Composable
internal fun SongLines(track: Track, detail: String, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = track,
        contentKey = { it.key },
        transitionSpec = {
            if (reduced) {
                fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
            } else {
                (slideInVertically(Motion.enter()) { it / 2 } + fadeIn(Motion.enter())) togetherWith
                    (slideOutVertically(Motion.exit()) { -it / 2 } + fadeOut(Motion.exit()))
            }
        },
        modifier = modifier,
        label = "songLines",
    ) { shown ->
        Column {
            Text(
                text = shown.title,
                style = MaterialTheme.typography.titleMedium,
                color = ValenColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (shown.key == track.key) detail else shown.artist,
                style = MaterialTheme.typography.bodySmall,
                color = ValenColors.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The player's cover. A new song's cover drops in with a little spring, and while the song plays
 * the cover sits full size; paused, it sinks back a step, so the player shows its state at a
 * glance even with the transport out of view.
 */
@Composable
internal fun PlayerCover(url: String?, size: Dp, playing: Boolean, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val sink by animateFloatAsState(
        targetValue = if (playing || reduced) 1f else 0.86f,
        animationSpec = Motion.lean(),
        label = "coverSink",
    )
    val arrive = remember(url) { Animatable(if (reduced) 1f else 0.6f) }
    LaunchedEffect(url) { if (!reduced) arrive.animateTo(1f, Motion.squish()) }
    ArtworkSquare(
        url = url,
        size = size,
        measure = true,
        modifier = modifier.graphicsLayer {
            val s = sink * arrive.value
            scaleX = s
            scaleY = s
        },
    )
}

/**
 * The way to the queue, which swallows what is dropped into it: when a song is added (the queue
 * grows while the same song plays), the glyph swells and rings out on the jelly spring, taking
 * the one colour for a moment, so it is plain where "Add to queue" put it.
 */
@Composable
private fun QueueGlyph(count: Int, song: String, onClick: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val flights = LocalFlights.current
    val swallow = remember { Animatable(0f) }
    val seen = remember { arrayOf<Any>(count, song, flights.landed) }
    // A drop landing, or (with none in the air, as from the song menu) the queue growing.
    LaunchedEffect(count, song, flights.landed) {
        val grew = count > seen[0] as Int && song == seen[1] && flights.inAir == 0
        val landed = flights.landed != seen[2]
        seen[0] = count
        seen[1] = song
        seen[2] = flights.landed
        if ((grew || landed) && !reduced) {
            swallow.animateTo(1f, androidx.compose.animation.core.tween(Motion.KickMs, easing = Motion.Exit))
            swallow.animateTo(0f, Motion.jelly())
        }
    }
    val inked = (swallow.value).coerceIn(0f, 1f)
    IconTap(
        icon = Icons.AutoMirrored.Rounded.QueueMusic,
        label = "Queue",
        onClick = onClick,
        tint = androidx.compose.ui.graphics.lerp(ValenColors.Text2, ValenColors.Primary, inked),
        modifier = Modifier.flightDoor(flights).graphicsLayer {
            val s = 1f + (Motion.SwallowScale - 1f) * swallow.value
            scaleX = s
            scaleY = s
        },
    )
}
