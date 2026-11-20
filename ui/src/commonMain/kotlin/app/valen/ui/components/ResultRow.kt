/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import androidx.compose.foundation.background
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onPlaced
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.valen.core.ProviderId
import app.valen.core.Track
import app.valen.ui.menu.MenuOrigin
import app.valen.ui.menu.MoreButton
import app.valen.ui.menu.rememberSongMenuOpener
import app.valen.ui.menu.songMenuOnRightClick
import app.valen.ui.theme.Layout
import app.valen.ui.theme.LocalChrome
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import app.valen.ui.theme.timeStyle
import app.valen.ui.util.formatTime

/**
 * A search result: the song, who it is by, and which service answered. Tapping the row plays it;
 * the trailing button only adds it to the queue, and turns into a tick once it is there.
 *
 * The row that is playing takes the screen's accent for its title and shows the playing bars over
 * its cover, so the list always says where the music is.
 */
/**
 * Looks a song up ahead because the person is about to pick it ([PlaybackCoordinator.warm]); the
 * shell provides it, nothing does by default.
 */
val LocalWarm = androidx.compose.runtime.staticCompositionLocalOf<(Track) -> Unit> { {} }

@Composable
fun ResultRow(
    track: Track,
    onPlay: () -> Unit,
    onEnqueue: () -> Unit,
    modifier: Modifier = Modifier,
    /** Other services that list the same recording; the row is played from [track]'s service. */
    alsoOn: List<ProviderId> = emptyList(),
    current: Boolean = false,
    playing: Boolean = false,
    queued: Boolean = false,
    accent: Color = ValenColors.Text,
    /** Where the song's menu (⋯, right click, long press) says it was opened; none without it. */
    menu: MenuOrigin? = null,
) {
    val compact = LocalChrome.current.compact
    val flights = LocalFlights.current
    val reducedMotion = LocalReducedMotion.current
    // Where the row is, for the drop that flies from its cover into the queue. Taken on the row
    // itself (the item's root), as the song menu's right click does: a layout callback on the
    // cover, nested inside a lazy list's item, upset Compose's own index of node rectangles and
    // it threw when the page was left. The cover's place in the row is fixed (below).
    val rowAt = androidx.compose.runtime.remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(Layout.rowHeight)
            .onPlaced { rowAt[0] = it }
            .then(if (menu != null) Modifier.songMenuOnRightClick(track, menu) else Modifier)
            .tap(
                onClick = onPlay,
                shape = Radii.rowShape,
                onLongClick = if (menu != null) rememberSongMenuOpener(track, menu) else null,
                onIntent = LocalWarm.current.let { warm -> { warm(track) } },
            )
            .padding(start = Space.step2),
    ) {
        StatefulCover(
            url = track.artworkUrl,
            size = CoverSize,
            current = current,
            playing = playing,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Space.step3),
        ) {
            // The song that is playing is gone over with a marker in the cover's colour.
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                color = ValenColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.markerSwipe(active = current, color = ValenColors.Container, seed = track.key.hashCode()),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Measured first, so a long list of services can never squeeze the artist out.
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = ValenColors.Text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = if (compact) 150.dp else 420.dp),
                )
                Text(
                    text = buildString {
                        append("  ·  ").append(track.provider.short)
                        if (alsoOn.isNotEmpty()) {
                            // A phone has room for the count; a wide row names them.
                            append("  +  ")
                            append(if (compact) alsoOn.size.toString() else alsoOn.joinToString(", ") { it.short })
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ValenColors.Text3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        if (!compact && track.durationMs > 0L) {
            Text(
                text = formatTime(track.durationMs),
                style = timeStyle(),
                color = ValenColors.Text3,
                modifier = Modifier.padding(horizontal = Space.step3),
            )
        }
        // Once it is in, the plus turns into a tick: the new glyph pops in from small on the
        // press's spring. One glyph that changes, not an AnimatedContent: a subcomposition in
        // every row of a lazy list threw inside Compose ("LayoutNode not found in RectList")
        // when the list's rows were taken down.
        val pop = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
        val seenQueued = androidx.compose.runtime.remember { booleanArrayOf(queued) }
        androidx.compose.runtime.LaunchedEffect(queued) {
            if (seenQueued[0] == queued) return@LaunchedEffect
            seenQueued[0] = queued
            if (!reducedMotion) {
                pop.snapTo(0.4f)
                pop.animateTo(1f, Motion.squish())
            }
        }
        IconTap(
            icon = if (queued) Icons.Rounded.Check else Icons.Rounded.Add,
            label = if (queued) "In the queue" else "Add to queue",
            onClick = {
                rowAt[0]?.takeIf { it.isAttached }?.let { row ->
                    val at = row.positionInRoot()
                    with(density) {
                        val side = CoverSize.toPx()
                        val left = at.x + Space.step2.toPx()
                        val top = at.y + (row.size.height - side) / 2f
                        flights.launch(androidx.compose.ui.geometry.Rect(left, top, left + side, top + side), track.artworkUrl, reducedMotion)
                    }
                }
                onEnqueue()
            },
            enabled = !queued,
            modifier = Modifier
                .padding(end = if (menu != null) 0.dp else Space.step1)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                },
        )
        if (menu != null) {
            MoreButton(track = track, origin = menu, modifier = Modifier.padding(end = Space.step1))
        }
    }
}

/** A result row's cover. */
private val CoverSize = 48.dp

/** A row's cover; while its song is the current one, dimmed with the playing bars over it. */
@Composable
internal fun StatefulCover(
    url: String?,
    size: Dp,
    current: Boolean,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(size)) {
        ArtworkSquare(url = url, size = size)
        if (current) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(size)
                    .clip(Radii.coverShape)
                    .background(ValenColors.CoverScrim),
            ) {
                PlayingBars(playing = playing, tint = ValenColors.Text, modifier = Modifier.size(16.dp))
            }
        }
    }
}
