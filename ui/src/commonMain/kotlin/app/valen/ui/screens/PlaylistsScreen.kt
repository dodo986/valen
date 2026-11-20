/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.screens

import app.valen.ui.components.bleed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import app.valen.ui.components.FilterPill
import app.valen.core.arrangeSongs
import app.valen.core.ListOrder
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.ui.unit.Dp
import app.valen.ui.components.NameField
import app.valen.ui.components.PlaylistCover
import app.valen.ui.components.SearchField
import app.valen.ui.components.SkeletonRows
import app.valen.ui.components.coverOf
import app.valen.ui.components.taped
import app.valen.ui.components.tap
import app.valen.ui.menu.MenuOrigin
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import kotlinx.coroutines.delay
import androidx.compose.material.icons.rounded.AutoAwesome
import app.valen.ui.components.ScrollPage
import app.valen.ui.components.PageScrollbar
import app.valen.ui.components.pageScrollbar
import app.valen.ui.components.rememberPageList
import app.valen.ui.components.rememberPageValue
import app.valen.ui.components.rise
import app.valen.ui.components.arrive
import app.valen.ui.components.ItemArrival
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.valen.core.ImportProgress
import app.valen.core.ImportResult
import app.valen.core.PlaybackCoordinator
import app.valen.core.Playlist
import app.valen.core.PlaylistManager
import app.valen.core.Track
import app.valen.ui.LocalAppScope
import app.valen.ui.artwork.ArtworkPalette
import app.valen.ui.components.AmbientWash
import app.valen.ui.components.Artwork
import app.valen.ui.components.ArtworkSquare
import app.valen.ui.components.ContentColumn
import app.valen.ui.components.EmptyState
import app.valen.ui.components.IconTap
import app.valen.ui.components.InlineError
import app.valen.ui.components.PageBottom
import app.valen.ui.components.PageTitle
import app.valen.ui.components.PageTop
import app.valen.ui.components.PlaylistCard
import app.valen.ui.components.PrimaryButton
import app.valen.ui.components.ProgressRail
import app.valen.ui.components.QuietButton
import app.valen.ui.components.ResultRow
import app.valen.ui.components.SectionHeader
import app.valen.ui.components.playlistDetail
import app.valen.ui.theme.Layout
import app.valen.ui.theme.LocalChrome
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.TrackType
import app.valen.ui.theme.ValenColors
import kotlinx.coroutines.launch

/**
 * The playlists Valen keeps: lists read from the services and lists it made itself. A list opens
 * in place; importing is the one thing that leaves this screen, because a pasted address and an
 * account's own lists are a job of their own.
 *
 * A list's songs are the service copies Valen can play. The rows that no service could offer are
 * listed under the songs, not dropped: a playlist that quietly lost a third of itself is worse
 * than one that says so.
 */
@Composable
fun PlaylistsScreen(
    coordinator: PlaybackCoordinator,
    manager: PlaylistManager,
    onImport: () -> Unit,
    onMix: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val all by manager.library.playlists.collectAsState()
    // Which list is open survives a trip to the player and back, like the scroll inside it.
    var openId by rememberPageValue<String?>("playlists.open", null)
    val open = all.firstOrNull { it.id == openId }
    val scope = LocalAppScope.current
    if (open == null) {
        PlaylistList(
            playlists = all,
            onImport = onImport,
            onMix = onMix,
            onOpen = { openId = it.id },
            onCreate = { name ->
                scope.launch {
                    val made = manager.library.create(name)
                    openId = made.id
                }
            },
            modifier = modifier,
        )
    } else {
        PlaylistDetail(
            coordinator = coordinator,
            manager = manager,
            playlist = open,
            onBack = { openId = null },
            modifier = modifier,
        )
    }
}

@Composable
private fun PlaylistList(
    playlists: List<Playlist>,
    onImport: () -> Unit,
    onMix: () -> Unit = {},
    onOpen: (Playlist) -> Unit,
    onCreate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val compact = LocalChrome.current.compact
    // A sleeve being named stays on the shelf across a trip elsewhere, like the scroll.
    var creating by rememberPageValue("playlists.creating", false)
    val actions: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.step2)) {
            PrimaryButton(label = "New playlist", icon = Icons.Rounded.Add, onClick = { creating = true }, enabled = !creating, small = true)
            QuietButton(label = "New mix", icon = Icons.Rounded.AutoAwesome, onClick = onMix, small = true)
            QuietButton(label = "Import", onClick = onImport, small = true)
        }
    }
    ScrollPage(key = "playlists", modifier = modifier
            .fillMaxSize()) {
        ContentColumn {
            PageTop()
            PageTitle(title = "Playlists", trailing = if (compact) null else actions)
            Spacer(Modifier.height(Space.step2))
            Text(
                text = "Lists you make, lists from your services, and the ones you paste in. Their songs play from whichever service has them.",
                style = MaterialTheme.typography.bodyMedium,
                color = ValenColors.Text2,
                modifier = Modifier.widthIn(max = 560.dp),
            )
            if (compact) {
                Spacer(Modifier.height(Space.step4))
                actions()
            }
            Spacer(Modifier.height(Space.step8))
            if (playlists.isEmpty() && !creating) {
                EmptyState(
                    title = "No playlists yet",
                    body = "Make one and add songs to it from anywhere, or bring one in from Spotify, YouTube Music or SoundCloud.",
                    actionLabel = "New playlist",
                    onAction = { creating = true },
                )
            } else {
                PlaylistGrid(
                    playlists = playlists,
                    creating = creating,
                    onCreate = { name ->
                        creating = false
                        onCreate(name)
                    },
                    onCancel = { creating = false },
                    onOpen = onOpen,
                )
            }
        }
        PageBottom()
    }
}

/** Covers on shelves: the grid earns a column for every 280dp the window gives it. */
@Composable
private fun PlaylistGrid(
    playlists: List<Playlist>,
    creating: Boolean,
    onCreate: (String) -> Unit,
    onCancel: () -> Unit,
    onOpen: (Playlist) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val gap = Space.step4
        val columns = (maxWidth / 280.dp).toInt().coerceIn(2, 7)
        val size = (maxWidth - gap * (columns - 1)) / columns
        // The new sleeve takes the first place on the shelf while it is being named.
        val cells: List<Playlist?> = (if (creating) listOf(null) else emptyList<Playlist?>()) + playlists
        Column(verticalArrangement = Arrangement.spacedBy(Space.step8)) {
            cells.chunked(columns).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEachIndexed { c, playlist ->
                        if (playlist == null) {
                            NewPlaylistCard(size = size, onCreate = onCreate, onCancel = onCancel)
                        } else {
                            key(playlist.id) {
                                PlaylistCard(
                                    modifier = Modifier.arrive(r * columns + c, ItemArrival.Slap),
                                    playlist = playlist,
                                    size = size,
                                    onClick = { onOpen(playlist) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A new list, made on the shelf itself: a blank sleeve in the container tone is slapped down and
 * taped up where the first cover was, the name is typed under it and shows on the sleeve as it is
 * typed, and Enter (or Create) keeps it. Escape, or Cancel, takes the sleeve away again.
 */
@Composable
private fun NewPlaylistCard(size: Dp, onCreate: (String) -> Unit, onCancel: () -> Unit) {
    val reduced = LocalReducedMotion.current
    var name by remember { mutableStateOf("") }
    val slap = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) slap.animateTo(1f, tween(Motion.KineticMs, easing = Motion.Kinetic))
    }
    Column(
        modifier = Modifier
            .width(size)
            .graphicsLayer {
                val p = slap.value
                alpha = (p * 2.5f).coerceIn(0f, 1f)
                val s = 1.25f - 0.25f * p
                scaleX = s
                scaleY = s
                rotationZ = -5f * (1f - p)
            },
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .taped("new-playlist")
                .clip(Radii.cardShape)
                .background(ValenColors.Container),
        ) {
            Text(
                text = name.ifBlank { "New playlist" },
                style = MaterialTheme.typography.headlineMedium,
                color = if (name.isBlank()) ValenColors.OnContainer.copy(alpha = 0.5f) else ValenColors.OnContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(Space.step5),
            )
        }
        Spacer(Modifier.height(Space.step3))
        NameField(
            value = name,
            onValue = { name = it },
            onDone = { onCreate(name) },
            onCancel = onCancel,
            style = MaterialTheme.typography.titleMedium,
            label = "Playlist name",
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.step1),
        )
        Spacer(Modifier.height(Space.step2))
        Row(verticalAlignment = Alignment.CenterVertically) {
            QuietButton(label = "Create", icon = Icons.Rounded.Check, small = true, onClick = { onCreate(name) })
            Spacer(Modifier.width(Space.step1))
            IconTap(icon = Icons.Rounded.Close, label = "Cancel", onClick = onCancel)
        }
    }
}

/**
 * One playlist: its cover and name over its songs, with refresh and removal beside Play. A list
 * read from a service can be read again in place; a list made here has nothing to refresh, so the
 * action is not offered rather than shown disabled.
 */
@Composable
private fun PlaylistDetail(
    coordinator: PlaybackCoordinator,
    manager: PlaylistManager,
    playlist: Playlist,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chrome = LocalChrome.current
    val scope = LocalAppScope.current
    val snapshot by coordinator.nowPlaying.collectAsState()
    val playingKey = snapshot?.track?.key
    val accent = ArtworkPalette.tones(snapshot?.track?.artworkUrl).bright
    // The playlist's backdrop belongs to its cover, even when the app uses black and pink.
    val coverTones = ArtworkPalette.measured(coverOf(playlist)) ?: ArtworkPalette.Neutral

    var progress by remember(playlist.id) { mutableStateOf<ImportProgress?>(null) }
    var failure by remember(playlist.id) { mutableStateOf<String?>(null) }
    var confirming by remember(playlist.id) { mutableStateOf(false) }
    var renaming by remember(playlist.id) { mutableStateOf(false) }
    var newName by remember(playlist.id) { mutableStateOf(playlist.name) }
    // A new list arrives empty, ready for its first songs.
    val adding = remember(playlist.id) { SongFinder() }

    // How the songs are shown: found by name, sorted. The sort is kept for each list while Valen
    // runs, and it is the order the list plays in; finding only narrows what is shown.
    var order by rememberPageValue("playlist-order:${playlist.id}", ListOrder.Own)
    var reversed by rememberPageValue("playlist-reversed:${playlist.id}", false)
    var find by remember(playlist.id) { mutableStateOf("") }
    val sorted = remember(playlist.tracks, order, reversed) { arrangeSongs(playlist.tracks, order, reversed) }
    val shown = remember(sorted, find) { if (find.isBlank()) sorted else arrangeSongs(sorted.map { it.value }, ListOrder.Own, find = find).map { sorted[it.index] } }

    val playable = sorted.map { it.value }.filter { manager.canPlay(it) }
    val source = playlist.source?.link()

    // Kept on this device: the shelf's state for this list, when the platform keeps songs at all.
    val shelf = coordinator.offline
    val offline by (shelf?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(app.valen.core.OfflineState()) }).collectAsState()
    val download = offline.playlists[playlist.id]
    val downloadable = shelf != null && playlist.tracks.any { it.provider != app.valen.core.ProviderId.LOCAL }
    var unkeeping by remember(playlist.id) { mutableStateOf(false) }

    // The song Shuffle will start with is picked when the page opens, so it can be looked up ahead
    // like Play's first song: both buttons then start at once.
    val shuffleFirst = remember(playlist.id, playable.size) { playable.randomOrNull() }
    LaunchedEffect(playlist.id) {
        playable.firstOrNull()?.let(coordinator::warm)
        shuffleFirst?.let(coordinator::warm)
    }

    fun play() {
        val first = playable.firstOrNull() ?: return
        // A saved mix is a list Valen made: its listening counts as offered, not chosen.
        scope.launch { coordinator.play(first, playable, from = playlist.name, generated = playlist.smart != null) }
    }

    /** Shuffle on, from a song picked at random. */
    fun shuffle() {
        val first = shuffleFirst ?: playable.randomOrNull() ?: return
        coordinator.setShuffle(true)
        scope.launch { coordinator.play(first, playable, from = playlist.name, generated = playlist.smart != null) }
    }

    fun rename() {
        renaming = false
        val wanted = newName.trim()
        if (wanted.isNotEmpty() && wanted != playlist.name) scope.launch { manager.library.rename(playlist.id, wanted) }
        newName = wanted.ifEmpty { playlist.name }
    }

    fun refresh() {
        if (progress != null) return
        failure = null
        scope.launch {
            progress = ImportProgress(label = "Reading the playlist…")
            when (val result = manager.refresh(playlist.id) { progress = it }) {
                is ImportResult.Failed -> failure = result.reason
                is ImportResult.Imported -> Unit
            }
            progress = null
        }
    }

    fun remove() {
        scope.launch {
            manager.library.remove(playlist.id)
            onBack()
        }
    }

    if (playlist.takesSongs) {
        LaunchedEffect(adding.query) {
            val q = adding.query.trim()
            if (q.isEmpty()) {
                adding.results = emptyList()
                adding.failure = null
                adding.searching = false
                return@LaunchedEffect
            }
            adding.searching = true
            delay(FindDebounceMs)
            val found = runCatching { coordinator.unifiedSearch(q) }
            adding.results = found.getOrNull()?.tracks?.take(20).orEmpty()
            adding.failure = found.exceptionOrNull()?.message
                ?: found.getOrNull()?.takeIf { it.hits.isEmpty() && it.errors.isNotEmpty() }?.errors?.values?.firstOrNull()
            adding.searching = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ValenColors.Background),
    ) {
        val listState = rememberPageList("playlist:${playlist.id}")
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = chrome.bottom + Space.step12),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                Box(modifier = Modifier.fillMaxWidth()) {
                    AmbientWash(tones = coverTones, strength = 0.62f, modifier = Modifier.matchParentSize())
                    ContentColumn {
                        Spacer(Modifier.height(if (chrome.compact) Space.step4 else Space.step6))
                        // Back on the left, the one destructive action in the corner it belongs
                        // in: away from Play, and away from the songs.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconTap(
                                icon = Icons.AutoMirrored.Rounded.ArrowBack,
                                label = "Back to playlists",
                                onClick = onBack,
                                tint = ValenColors.Text,
                            )
                            Spacer(Modifier.weight(1f))
                            if (playlist.takesSongs && !confirming) {
                                IconTap(
                                    icon = Icons.Rounded.Edit,
                                    label = "Rename",
                                    onClick = { newName = playlist.name; renaming = true },
                                    tint = ValenColors.Text2,
                                )
                            }
                            if (confirming) {
                                QuietButton(
                                    label = "Remove",
                                    small = true,
                                    contentColor = ValenColors.Danger,
                                    onClick = { remove() },
                                )
                                Spacer(Modifier.width(Space.step2))
                                QuietButton(label = "Keep", small = true, onClick = { confirming = false })
                            } else if (!playlist.isOffline) {
                                // Offline is Valen's to fill; Settings turns it off.
                                IconTap(
                                    icon = Icons.Rounded.DeleteOutline,
                                    label = "Remove this playlist",
                                    onClick = { confirming = true },
                                    tint = ValenColors.Text2,
                                )
                            }
                        }
                        Spacer(Modifier.height(Space.step4))
                        Row(verticalAlignment = Alignment.Bottom) {
                            PlaylistCover(
                                playlist = playlist,
                                size = if (chrome.compact) 112.dp else 200.dp,
                                shape = Radii.artworkShape,
                                iconSize = 40.dp,
                                measure = true,
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = Space.step6),
                            ) {
                                // The name is renamed where it stands: typed over in its own size.
                                if (renaming) {
                                    NameField(
                                        value = newName,
                                        onValue = { newName = it },
                                        onDone = { rename() },
                                        onCancel = { renaming = false; newName = playlist.name },
                                        style = if (chrome.compact) TrackType.headline else TrackType.title,
                                        label = "Playlist name",
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    Text(
                                        text = playlist.name,
                                        style = if (chrome.compact) TrackType.headline else TrackType.title,
                                        color = ValenColors.Text,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.height(Space.step2))
                                Text(
                                    text = playlistDetail(playlist),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = ValenColors.Text2,
                                )
                                playlist.note?.let { note ->
                                    Text(
                                        text = note,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ValenColors.Text3,
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(Space.step6))
                        // Four buttons do not fit a phone's width: the row wraps rather than
                        // pushing the last one off the page.
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Space.step2),
                            verticalArrangement = Arrangement.spacedBy(Space.step2),
                        ) {
                            PrimaryButton(
                                label = "Play",
                                icon = Icons.Rounded.PlayArrow,
                                enabled = playable.isNotEmpty(),
                                onClick = { play() },
                            )
                            if (playable.size > 1) {
                                QuietButton(label = "Shuffle", icon = Icons.Rounded.Shuffle, onClick = { shuffle() })
                            }
                            val spec = playlist.smart
                            if (spec != null) {
                                var remaking by remember(playlist.id) { mutableStateOf(false) }
                                QuietButton(
                                    label = if (remaking) "Making…" else "Make again",
                                    icon = Icons.Rounded.AutoAwesome,
                                    enabled = !remaking,
                                    onClick = {
                                        remaking = true
                                        scope.launch {
                                            val songs = runCatching { coordinator.mixer().build(spec, coordinator.taste()) }.getOrDefault(emptyList())
                                            if (songs.isNotEmpty()) {
                                                manager.library.add(playlist.name, songs, artworkUrl = playlist.artworkUrl, smart = spec, replacing = playlist.id)
                                            }
                                            remaking = false
                                        }
                                    },
                                )
                            }
                            if (source != null) {
                                QuietButton(
                                    label = if (progress != null) "Refreshing" else "Refresh",
                                    icon = Icons.Rounded.Refresh,
                                    enabled = progress == null,
                                    onClick = { refresh() },
                                )
                            }
                            if (downloadable && shelf != null && !unkeeping) {
                                QuietButton(
                                    label = when {
                                        download == null -> "Download"
                                        download.complete -> "Downloaded"
                                        else -> "Downloading"
                                    },
                                    icon = when {
                                        download == null -> Icons.Rounded.Download
                                        download.complete -> Icons.Rounded.DownloadDone
                                        else -> Icons.Rounded.Downloading
                                    },
                                    onClick = { if (download == null) shelf.keep(playlist) else unkeeping = true },
                                )
                            }
                            if (unkeeping && shelf != null) {
                                QuietButton(
                                    label = "Remove download",
                                    small = true,
                                    contentColor = ValenColors.Danger,
                                    onClick = {
                                        unkeeping = false
                                        scope.launch { shelf.forget(playlist.id) }
                                    },
                                )
                                QuietButton(label = "Keep", small = true, onClick = { unkeeping = false })
                            }
                        }
                        if (download != null && shelf != null) {
                            DownloadProgress(download, offline.resting, shelf.folder, onRetry = { shelf.keep(playlist) })
                        }
                        progress?.let { running ->
                            Spacer(Modifier.height(Space.step4))
                            Text(
                                text = if (running.total > 0) {
                                    "Matching ${running.processed} of ${running.total} songs"
                                } else {
                                    "Reading the playlist…"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = ValenColors.Text2,
                            )
                            Spacer(Modifier.height(Space.step2))
                            ProgressRail(
                                fraction = if (running.total > 0) {
                                    running.processed.toFloat() / running.total
                                } else {
                                    0f
                                },
                            )
                            if (running.label.isNotEmpty()) {
                                Spacer(Modifier.height(Space.step2))
                                Text(
                                    text = running.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ValenColors.Text3,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        failure?.let { reason ->
                            Spacer(Modifier.height(Space.step3))
                            InlineError(reason)
                        }
                        Spacer(Modifier.height(Space.step6))
                    }
                }
            }

            // An empty list of your own is not a dead end: its empty state leads into the search below.
            if (playlist.tracks.isEmpty() && playlist.isSourced) {
                item(key = "empty") {
                    PageRow {
                        EmptyState(
                            title = "This playlist has no songs Valen can play",
                            body = "Sign in to the service that has them, then refresh the list.",
                        )
                    }
                }
            }

            // Long enough to look through: a field to find a song by name, and the ways to sort.
            if (playlist.tracks.size >= ToolsFrom) {
                item(key = "tools") {
                    PageRow {
                        Column {
                            SearchField(
                                value = find,
                                onValueChange = { find = it },
                                placeholder = "Find in this playlist",
                                onClear = { find = "" },
                                modifier = Modifier.widthIn(max = 520.dp),
                            )
                            Spacer(Modifier.height(Space.step1))
                            // Run off the page's edges when they do not fit, as the shelves do.
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(Space.step2),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .bleed(chrome.gutter)
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = chrome.gutter),
                            ) {
                                // The chosen order pressed again runs the other way.
                                ListOrder.entries.forEach { way ->
                                    val chosen = way == order
                                    FilterPill(
                                        label = way.label(chosen, reversed),
                                        selected = chosen,
                                        onClick = {
                                            if (chosen) {
                                                reversed = !reversed
                                            } else {
                                                order = way
                                                reversed = false
                                            }
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(Space.step2))
                        }
                    }
                }
                if (shown.isEmpty() && find.isNotBlank()) {
                    item(key = "no-match") {
                        PageRow {
                            Text(
                                text = "No song here matches “${find.trim()}”.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ValenColors.Text2,
                                modifier = Modifier.padding(vertical = Space.step4),
                            )
                        }
                    }
                }
            }

            // Keyed by each song's place in the list, so a new sort slides the rows to theirs.
            itemsIndexed(shown, key = { _, entry -> "${entry.index}:${entry.value.key}" }) { i, entry ->
                val track = entry.value
                PageRow(Modifier.animateItem(placementSpec = Motion.QueueReorder)) {
                    if (manager.canPlay(track)) {
                        ResultRow(
                            modifier = Modifier.rise(i + 2),
                            menu = MenuOrigin.InPlaylist(playlist.id),
                            track = track,
                            current = track.key == playingKey,
                            playing = track.key == playingKey && snapshot?.isPlaying == true,
                            accent = accent,
                            onPlay = {
                                val start = playable.firstOrNull { it.key == track.key } ?: track
                                scope.launch { coordinator.play(start, playable.ifEmpty { listOf(track) }, from = playlist.name, generated = playlist.smart != null) }
                            },
                            onEnqueue = { coordinator.enqueue(track) },
                        )
                    } else {
                        UnavailableRow(
                            track = track,
                            onSignIn = { scope.launch { coordinator.connect(track.provider) } },
                        )
                    }
                }
            }

            // A list of your own can be filled from here: search, then + drops the song into it.
            if (playlist.takesSongs) {
                item(key = "add-title") {
                    PageRow {
                        Column {
                            if (playlist.tracks.isEmpty() && playlist.isOwn) {
                                SectionHeader("No songs yet")
                                Text(
                                    text = "Find some here, or add a song from its menu anywhere in Valen.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ValenColors.Text2,
                                    modifier = Modifier.padding(top = Space.step1),
                                )
                            } else {
                                Spacer(Modifier.height(Space.step10))
                                SectionHeader("Add songs")
                                if (playlist.isSourced) {
                                    // Nothing is written to the service: say where the songs live.
                                    Text(
                                        text = if (playlist.source?.provider == app.valen.core.ProviderId.LOCAL) {
                                            "Songs added here stay when the list is read from its file again. They live in Valen, not in the file."
                                        } else if (playlist.source != null) {
                                            "Songs added here stay when the list is refreshed. They live in Valen, not on ${playlist.source?.provider?.label ?: "the service"}."
                                        } else {
                                            "Songs added here stay when the mix is made again."
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ValenColors.Text2,
                                        modifier = Modifier.padding(top = Space.step1).widthIn(max = 560.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(Space.step4))
                            SearchField(
                                value = adding.query,
                                onValueChange = { adding.query = it },
                                placeholder = "Search for songs to add",
                                onClear = { adding.query = "" },
                            )
                            Spacer(Modifier.height(Space.step2))
                        }
                    }
                }
                if (adding.searching && adding.results.isEmpty()) {
                    item(key = "add-wait") { PageRow { SkeletonRows(3) } }
                }
                adding.failure?.let { reason ->
                    item(key = "add-failure") { PageRow { InlineError(reason) } }
                }
                items(adding.results, key = { "add:${it.key}" }) { found ->
                    val inside = playlist.tracks.any { it.key == found.key }
                    PageRow(Modifier.animateItem()) {
                        AddSongRow(
                            track = found,
                            inside = inside,
                            listName = playlist.name,
                            onPlay = { scope.launch { coordinator.play(found) } },
                            onAdd = { scope.launch { manager.library.addTracks(playlist.id, listOf(found)) } },
                        )
                    }
                }
            }

            if (playlist.unmatched.isNotEmpty()) {
                item(key = "unmatched-title") {
                    PageRow {
                        Spacer(Modifier.height(Space.step8))
                        SectionHeader("Not on your services")
                    }
                }
                item(key = "unmatched-note") {
                    PageRow {
                        Text(
                            text = "These songs were in the source list, but no service Valen can play had them. They stay listed so the playlist does not look shorter than it is.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ValenColors.Text2,
                            modifier = Modifier
                                .widthIn(max = 560.dp)
                                .padding(bottom = Space.step2),
                        )
                    }
                }
                items(playlist.unmatched) { entry ->
                    PageRow {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = Layout.hitTarget)
                                .padding(vertical = Space.step2),
                        ) {
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = ValenColors.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = entry.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = ValenColors.Text2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        PageScrollbar(listState, Modifier.align(Alignment.CenterEnd).pageScrollbar())
    }
}

/**
 * A kept list's downloads, said plainly under its buttons: how many songs are on this device, the
 * one on its way, a service's request to slow down, and the songs no copy could be found for.
 */
@Composable
private fun DownloadProgress(
    download: app.valen.core.PlaylistDownload,
    resting: app.valen.core.OfflineState.Rest?,
    folder: String,
    onRetry: () -> Unit,
) {
    val missing = download.missing.size
    if (download.complete && missing == 0) {
        Spacer(Modifier.height(Space.step3))
        Text(
            text = "On this device, in ${app.valen.ui.shortFolder(folder)}",
            style = MaterialTheme.typography.bodySmall,
            color = ValenColors.Text3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    Spacer(Modifier.height(Space.step4))
    Text(
        text = when {
            download.complete -> "${download.done} of ${download.total} songs on this device"
            resting != null -> "${resting.reason}. Waiting a moment…"
            else -> "Downloading ${download.done + 1} of ${download.total}"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = ValenColors.Text2,
    )
    if (!download.complete) {
        Spacer(Modifier.height(Space.step2))
        ProgressRail(
            fraction = if (download.total > 0) {
                ((download.done + download.missing.size + download.currentFraction) / download.total).coerceIn(0f, 1f)
            } else {
                0f
            },
        )
        download.current?.takeIf { it != "Waiting" }?.let { label ->
            Spacer(Modifier.height(Space.step2))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = ValenColors.Text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (missing > 0 && download.complete) {
        Spacer(Modifier.height(Space.step2))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (missing == 1) "1 song could not be downloaded: ${download.missing.values.first()}." else "$missing songs could not be downloaded.",
                style = MaterialTheme.typography.bodySmall,
                color = ValenColors.Text3,
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(Space.step2))
            QuietButton(label = "Try again", small = true, onClick = onRetry)
        }
    }
}

/** A song its own service cannot play right now: dimmed, with the one action that changes that. */
@Composable
private fun UnavailableRow(track: Track, onSignIn: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Layout.rowHeight)
            .padding(start = Space.step2),
    ) {
        ArtworkSquare(
            url = track.artworkUrl,
            size = 48.dp,
            modifier = Modifier.graphicsLayer { alpha = 0.45f },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Space.step3),
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                color = ValenColors.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${track.artist}  ·  ${track.provider.short} is signed out",
                style = MaterialTheme.typography.bodySmall,
                color = ValenColors.Text3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        QuietButton(
            label = "Sign in",
            small = true,
            onClick = onSignIn,
            modifier = Modifier.padding(end = Space.step2),
        )
    }
}

/** One row of a full-width list, clear of the side panel and inside the gutters. */
/** A list this long gets the field to find a song in it, and the sorts. */
private const val ToolsFrom = 10

@Composable
private fun PageRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val chrome = LocalChrome.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = chrome.start + chrome.gutter, end = chrome.gutter),
    ) {
        content()
    }
}

/** How long typing must pause before the playlist's search runs. */
private const val FindDebounceMs = 350L

/** The playlist page's own search for songs to add: what was typed, and what came back. */
@Stable
private class SongFinder {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Track>>(emptyList())
    var searching by mutableStateOf(false)
    var failure by mutableStateOf<String?>(null)
}

/**
 * A song the search found, for a list of your own: a press plays it, + drops it into the list.
 * Once it is in, the plus turns over into a tick.
 */
@Composable
private fun AddSongRow(track: Track, inside: Boolean, listName: String, onPlay: () -> Unit, onAdd: () -> Unit) {
    val reduced = LocalReducedMotion.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(Layout.rowHeight)
            .tap(onClick = onPlay, shape = Radii.rowShape)
            .padding(start = Space.step2),
    ) {
        Artwork(url = track.artworkUrl, shape = Radii.coverShape, contentDescription = null, iconSize = 20.dp, modifier = Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(horizontal = Space.step3)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium, color = ValenColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${track.artist}  ·  ${track.provider.short}",
                style = MaterialTheme.typography.bodySmall,
                color = ValenColors.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AnimatedContent(
            targetState = inside,
            transitionSpec = {
                if (reduced) {
                    fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
                } else {
                    (scaleIn(Motion.morph(), initialScale = 0.4f) + fadeIn(Motion.morph())) togetherWith
                        (scaleOut(Motion.exit(), targetScale = 0.4f) + fadeOut(Motion.exit()))
                }
            },
            label = "addMorph",
        ) { added ->
            IconTap(
                icon = if (added) Icons.Rounded.Check else Icons.Rounded.Add,
                label = if (added) "In $listName" else "Add to $listName",
                onClick = onAdd,
                enabled = !added,
                tint = if (added) ValenColors.Primary else ValenColors.Text,
                modifier = Modifier.padding(end = Space.step1),
            )
        }
    }
}
