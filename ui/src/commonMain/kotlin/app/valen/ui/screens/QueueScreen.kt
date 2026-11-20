/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.screens

import androidx.compose.foundation.lazy.itemsIndexed
import app.valen.ui.components.rise
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import app.valen.ui.components.PageScrollbar
import app.valen.ui.components.pageScrollbar
import app.valen.ui.components.rememberPageList
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.valen.core.PlaybackCoordinator
import app.valen.core.Track
import app.valen.ui.LocalAppScope
import app.valen.ui.artwork.ArtworkPalette
import app.valen.ui.components.Artwork
import app.valen.ui.components.ArtworkSquare
import app.valen.ui.components.EmptyState
import app.valen.ui.components.IconTap
import app.valen.ui.components.PageTitle
import app.valen.ui.components.PlayingBars
import app.valen.ui.components.QueueRow
import app.valen.ui.components.QueueRowHeight
import app.valen.ui.components.bleed
import app.valen.ui.components.tap
import app.valen.ui.theme.LocalChrome
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.TrackType
import app.valen.ui.theme.ValenColors
import app.valen.ui.util.queueSummary
import kotlinx.coroutines.launch

/**
 * The queue is the session's one list: what is playing, what comes next, what already played.
 * Up-next rows can be dragged by their handle; a drag moves a row one place each time it crosses
 * a row height, so the order on screen always matches the coordinator.
 *
 * With room, the song that is playing stands beside the list as a cover; without, it leads it.
 */
@Composable
fun QueueScreen(
    coordinator: PlaybackCoordinator,
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    val chrome = LocalChrome.current
    val scope = LocalAppScope.current
    val queue by coordinator.queue.collectAsState()
    val index by coordinator.index.collectAsState()
    val snapshot by coordinator.nowPlaying.collectAsState()
    val following by coordinator.keepPlaying.collectAsState()

    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragTravel by remember { mutableStateOf(0f) }
    val rowPx = with(LocalDensity.current) { QueueRowHeight.toPx() }

    fun dragDelta(delta: Float) {
        val key = dragKey ?: return
        dragTravel += delta
        var from = coordinator.queue.value.indexOfFirst { it.key == key }
        val current = coordinator.index.value
        while (dragTravel >= rowPx && from > current && from < coordinator.queue.value.lastIndex) {
            coordinator.move(from, from + 1)
            dragTravel -= rowPx
            from += 1
        }
        while (dragTravel <= -rowPx && from > current + 1) {
            coordinator.move(from, from - 1)
            dragTravel += rowPx
            from -= 1
        }
    }

    val currentTrack = queue.getOrNull(index)
    val upNext = if (index >= 0) queue.drop(index + 1) else emptyList()
    val history = if (index > 0) queue.take(index).reversed() else emptyList()
    val playing = snapshot?.isPlaying == true
    val accent = ArtworkPalette.tones(currentTrack?.artworkUrl).bright
    val toggle: () -> Unit = { scope.launch { coordinator.toggle() } }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(ValenColors.Background)
            .padding(start = chrome.start),
    ) {
        val beside = currentTrack != null && maxWidth >= 920.dp
        val header: @Composable () -> Unit = {
            PageTitle(
                title = "Queue",
                leading = if (onClose != null) {
                    {
                        IconTap(
                            icon = Icons.Rounded.KeyboardArrowDown,
                            label = "Close the queue",
                            onClick = onClose,
                            tint = ValenColors.Text,
                            iconSize = 28.dp,
                        )
                    }
                } else {
                    null
                },
            )
            if (queue.isNotEmpty()) {
                Text(
                    text = queueSummary(queue),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ValenColors.Text2,
                    modifier = Modifier.padding(top = Space.step1, bottom = Space.step2),
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = chrome.gutter),
            ) {
                // Beside the cover, the title heads both columns and stays put; in one column it
                // is simply the first thing in the list and scrolls with it.
                if (beside) {
                    Spacer(Modifier.height(Space.step10))
                    header()
                    Spacer(Modifier.height(Space.step4))
                }
                Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (beside && currentTrack != null) {
                    NowBeside(
                        track = currentTrack,
                        playing = playing,
                        accent = accent,
                        onToggle = toggle,
                        modifier = Modifier
                            .width(300.dp)
                            .fillMaxHeight(),
                    )
                    Spacer(Modifier.width(Space.step12))
                }
                // The queue keeps its place too, so a long one is not back at its top each time.
                val queueList = rememberPageList("queue")
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    LazyColumn(
                        state = queueList,
                        modifier = Modifier
                            .fillMaxSize()
                            .bleed(Space.step2),
                        contentPadding = PaddingValues(
                            start = Space.step2,
                            end = Space.step2,
                            top = when {
                                beside -> 0.dp
                                chrome.compact -> Space.step2
                                else -> Space.step10
                            },
                            bottom = chrome.bottom + Space.step12,
                        ),
                    ) {
                        if (!beside) {
                            item(key = "queue_header") { Column { header() } }
                        }

                        if (queue.isEmpty()) {
                            item(key = "queue_empty") {
                                EmptyState(
                                    title = "Nothing is lined up",
                                    body = "Songs you play or add from search line up here, in the order they will play.",
                                    actionLabel = "Find music",
                                    onAction = onBrowse,
                                    modifier = Modifier.padding(top = Space.step8),
                                )
                            }
                            return@LazyColumn
                        }

                        if (currentTrack != null && !beside) {
                            item(key = "queue_now_label") { QueueSectionLabel("Now playing") }
                            item(key = "queue_now") {
                                NowLeading(track = currentTrack, playing = playing, accent = accent, onToggle = toggle)
                            }
                        }

                        if (upNext.isNotEmpty()) {
                            item(key = "queue_next_label") { QueueSectionLabel("Up next", first = beside) }
                            itemsIndexed(upNext, key = { _, it -> "queue_next_${it.key}" }) { i, track ->
                                QueueRow(
                                    track = track,
                                    dimmed = false,
                                    reorderable = true,
                                    held = dragKey == track.key,
                                    modifier = Modifier
                                        .zIndex(if (dragKey == track.key) 1f else 0f)
                                        .animateItem(placementSpec = Motion.QueueReorder)
                                        .rise(i + 1),
                                    onPlay = { scope.launch { coordinator.play(track, coordinator.queue.value) } },
                                    onRemove = {
                                        val at = coordinator.queue.value.indexOfFirst { it.key == track.key }
                                        if (at >= 0) coordinator.removeAt(at)
                                    },
                                    menuIndex = queue.indexOfFirst { it.key == track.key },
                                    onDragStarted = {
                                        dragKey = track.key
                                        dragTravel = 0f
                                    },
                                    onDragStopped = {
                                        dragKey = null
                                        dragTravel = 0f
                                    },
                                    dragDelta = ::dragDelta,
                                )
                            }
                        } else if (following.isEmpty()) {
                            item(key = "queue_end") {
                                Text(
                                    text = "Nothing after this song. Add another from search.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ValenColors.Text2,
                                    modifier = Modifier.padding(top = if (beside) 0.dp else Space.step6, bottom = Space.step2),
                                )
                            }
                        }

                        // What plays on once the queue runs out: Valen's, not the queue, so it is
                        // headed as such and cannot be dragged into it.
                        if (following.isNotEmpty()) {
                            item(key = "queue_following_label") {
                                Column {
                                    QueueSectionLabel("Then, songs like these", first = beside && upNext.isEmpty())
                                    Text(
                                        text = "When the queue runs out, Valen keeps playing. Songs you add go first.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ValenColors.Text2,
                                        modifier = Modifier.padding(bottom = Space.step2),
                                    )
                                }
                            }
                            itemsIndexed(following, key = { _, it -> "queue_following_${it.key}" }) { i, track ->
                                QueueRow(
                                    track = track,
                                    dimmed = false,
                                    modifier = Modifier.animateItem(placementSpec = Motion.QueueReorder).rise(upNext.size + i + 1),
                                    onPlay = { scope.launch { coordinator.playFromKeepPlaying(track) } },
                                    onRemove = { coordinator.dropFromKeepPlaying(track) },
                                )
                            }
                        }

                        if (history.isNotEmpty()) {
                            item(key = "queue_history_label") { QueueSectionLabel("Played") }
                            items(history, key = { "queue_history_${it.key}" }) { track ->
                                QueueRow(
                                    track = track,
                                    dimmed = true,
                                    onPlay = { scope.launch { coordinator.play(track, coordinator.queue.value) } },
                                    onRemove = {
                                        val at = coordinator.queue.value.indexOfFirst { it.key == track.key }
                                        if (at >= 0) coordinator.removeAt(at)
                                    },
                                    menuIndex = queue.indexOfFirst { it.key == track.key },
                                )
                            }
                        }
                    }
                    PageScrollbar(queueList, Modifier.align(Alignment.CenterEnd).pageScrollbar())
                }
                }
            }
        }
    }
}

/** [first] when nothing is above it in the list, so it lines up with the top of the cover. */
@Composable
private fun QueueSectionLabel(text: String, first: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = ValenColors.Text,
        modifier = Modifier.padding(top = if (first) 0.dp else Space.step6, bottom = Space.step2),
    )
}

/** The song that is playing, standing beside the list: its cover, its name, and whether it moves. */
@Composable
private fun NowBeside(
    track: Track,
    playing: Boolean,
    accent: Color,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Artwork(
            url = track.artworkUrl,
            shape = Radii.artworkShape,
            contentDescription = null,
            measure = true,
            iconSize = 48.dp,
            modifier = Modifier
                .size(300.dp)
                .tap(onClick = onToggle, shape = Radii.artworkShape),
        )
        Spacer(Modifier.height(Space.step5))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayingBars(playing = playing, tint = accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Space.step2))
            Text(
                text = if (playing) "Playing from ${track.provider.label}" else "Paused on ${track.provider.label}",
                style = MaterialTheme.typography.titleSmall,
                color = ValenColors.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(Space.step2))
        Text(
            text = track.title,
            style = TrackType.headline,
            color = ValenColors.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(Space.step1))
        Text(
            text = track.artist,
            style = MaterialTheme.typography.bodyLarge,
            color = ValenColors.Text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The song that is playing, leading the list where there is no room beside it. */
@Composable
private fun NowLeading(track: Track, playing: Boolean, accent: Color, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .tap(onClick = onToggle, shape = Radii.cardShape, rest = ValenColors.Surface)
            .padding(start = Space.step2, end = Space.step5),
    ) {
        ArtworkSquare(url = track.artworkUrl, size = 64.dp, shape = Radii.rowShape, measure = true)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Space.step3),
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleLarge,
                color = ValenColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${track.artist}  ·  ${track.provider.short}",
                style = MaterialTheme.typography.bodySmall,
                color = ValenColors.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        PlayingBars(playing = playing, tint = accent, modifier = Modifier.size(18.dp))
    }
}
