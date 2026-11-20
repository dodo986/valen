/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.screens

import app.valen.ui.theme.Layout
import app.valen.ui.components.ScrollPage
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.valen.core.PlaybackCoordinator
import app.valen.core.Playlist
import app.valen.core.PlaylistManager
import app.valen.core.ProviderId
import app.valen.core.SessionState
import app.valen.core.Track
import app.valen.ui.LocalAppScope
import app.valen.ui.Route
import app.valen.ui.artwork.ArtworkPalette
import app.valen.ui.artwork.ArtworkTones
import app.valen.ui.components.AmbientWash
import app.valen.ui.components.Artwork
import app.valen.ui.components.ContentColumn
import app.valen.ui.components.IconTap
import app.valen.ui.components.PageBottom
import app.valen.ui.components.PlaylistCard
import app.valen.ui.components.PrimaryButton
import app.valen.ui.components.QuietButton
import app.valen.ui.components.SectionHeader
import app.valen.ui.components.ServiceTile
import app.valen.ui.components.ShelfCard
import app.valen.ui.components.KineticText
import app.valen.ui.components.Tag
import app.valen.ui.components.bleed
import app.valen.ui.components.hoverLift
import app.valen.ui.components.pasted
import app.valen.ui.components.rememberInteraction
import app.valen.ui.components.rise
import app.valen.ui.components.arrive
import app.valen.ui.components.ItemArrival
import app.valen.ui.components.taped
import app.valen.ui.components.Posters
import app.valen.ui.components.repaintFrom
import app.valen.ui.components.PosterEntry
import app.valen.ui.components.rememberWentBack
import app.valen.ui.components.tilt
import app.valen.ui.artwork.LocalPlayingTones
import app.valen.ui.components.tap
import app.valen.ui.theme.LocalChrome
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.TrackType
import app.valen.ui.theme.ValenColors
import app.valen.ui.util.currentHour
import app.valen.ui.util.greetingFor
import kotlinx.coroutines.launch

/**
 * Home, in the order a listening app answers: hello, whatever is playing, the playlists you keep
 * here, what you played last, and — only while nothing is connected — the three services.
 *
 * The greeting speaks first because the page belongs to the person, not to the player, and on a
 * wide window it stands in the same washed band as the song, so the page opens with one piece of
 * colour rather than a black strip and a tinted block under it. A section with nothing to show is
 * left out: no empty "Your playlists", no "Recently played" on a first start. Services move off
 * this page as soon as one is connected, because connecting is a setup job and the side panel
 * already reports what each service is doing.
 */
@Composable
fun HomeScreen(
    coordinator: PlaybackCoordinator,
    playlists: PlaylistManager,
    onRoute: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = LocalAppScope.current
    val chrome = LocalChrome.current
    val snapshot by coordinator.nowPlaying.collectAsState()
    val queue by coordinator.queue.collectAsState()
    val index by coordinator.index.collectAsState()
    val sessions by coordinator.sessions.collectAsState()
    val saved by playlists.library.playlists.collectAsState()
    val settings by coordinator.settings.collectAsState()

    val current = snapshot?.track ?: queue.getOrNull(index)
    val back = rememberWentBack(current?.key, index, queue)
    val history = remember(queue, index) {
        if (index <= 0) emptyList() else queue.take(index).reversed().distinctBy { it.key }.take(12)
    }
    val signedOut = coordinator.services.none { sessions[it] is SessionState.Connected }

    fun playPlaylist(playlist: Playlist) {
        val playable = playlist.tracks.filter { playlists.canPlay(it) }
        if (playable.isEmpty()) {
            // Nothing here can play on this machine right now: the lists screen can say why.
            onRoute(Route.Playlists)
        } else {
            scope.launch { coordinator.play(playable.first(), playable, from = playlist.name) }
        }
    }

    ScrollPage(key = "home", modifier = modifier
            .fillMaxSize()) {
        Lead(
            track = current,
            back = back,
            isPlaying = snapshot?.isPlaying == true,
            connected = coordinator.services.count { sessions[it] is SessionState.Connected },
            onToggle = { scope.launch { coordinator.toggle() } },
            onOpenPlayer = { onRoute(Route.NowPlaying) },
            onSearch = { onRoute(Route.Search) },
            onSettings = { onRoute(Route.Settings) },
            onProfile = { onRoute(Route.Profile) },
            avatar = settings.avatar,
        )

        ContentColumn {
            // "Your week", once it is turned on and has something to tell.
            app.valen.ui.recap.WeekCard(
                coordinator = coordinator,
                modifier = Modifier.padding(bottom = Space.step8).arrive(1),
            )
            // What the taste picks first: for you, your artists, on repeat.
            TasteSections(coordinator = coordinator, startIndex = 2)

            if (saved.isNotEmpty()) {
                SectionHeader(title = "Your playlists", onOpen = { onRoute(Route.Playlists) }, modifier = Modifier.arrive(2, ItemArrival.Swing))
                Spacer(Modifier.height(Space.step2))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Space.step4),
                    contentPadding = PaddingValues(horizontal = chrome.gutter),
                    modifier = Modifier
                        .fillMaxWidth()
                        .bleed(chrome.gutter),
                ) {
                    itemsIndexed(saved, key = { _, it -> it.id }) { i, playlist ->
                        PlaylistCard(
                            modifier = Modifier.arrive(3 + i, ItemArrival.Slap),
                            playlist = playlist,
                            size = if (chrome.compact) 140.dp else 168.dp,
                            onClick = { playPlaylist(playlist) },
                        )
                    }
                }
                Spacer(Modifier.height(Space.step8))
            }

            if (history.isNotEmpty()) {
                SectionHeader(title = "Recently played", modifier = Modifier.arrive(4, ItemArrival.Swing))
                Spacer(Modifier.height(Space.step2))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(Space.step4),
                    contentPadding = PaddingValues(horizontal = chrome.gutter),
                    modifier = Modifier
                        .fillMaxWidth()
                        .bleed(chrome.gutter),
                ) {
                    itemsIndexed(history, key = { _, it -> it.key }) { i, track ->
                        ShelfCard(
                            modifier = Modifier.arrive(5 + i, ItemArrival.Slap),
                            track = track,
                            size = if (chrome.compact) 140.dp else 168.dp,
                            onClick = {
                                val context = queue.ifEmpty { listOf(track) }
                                scope.launch { coordinator.play(track, context) }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(Space.step8))
            }

            if (signedOut) {
                SectionHeader(title = "Services", modifier = Modifier.arrive(6, ItemArrival.Swing))
                Spacer(Modifier.height(Space.step2))
                ServiceTiles(
                    services = coordinator.services,
                    sessions = sessions,
                    onConnect = { id -> scope.launch { coordinator.connect(id) } },
                )
            }
        }
        PageBottom()
    }
}

/**
 * The top of the page: the greeting and what is playing (or, on a quiet first start, what can be
 * searched). A wide window holds both in one band washed by the cover's colours; a phone cannot
 * put a greeting on a full-bleed cover and keep it legible, so there the cover is the whole band
 * and the greeting stands on the page above it.
 *
 * With nothing playing there is no cover to take colour from, so there is no wash either: a grey
 * band where no artwork stands is fog, not light.
 */
@Composable
private fun Lead(
    track: Track?,
    back: Boolean,
    isPlaying: Boolean,
    connected: Int,
    onToggle: () -> Unit,
    onOpenPlayer: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onProfile: () -> Unit,
    avatar: String?,
) {
    val chrome = LocalChrome.current
    val greeting = greetingFor(remember { currentHour() })
    if (chrome.compact) {
        ContentColumn {
            Spacer(Modifier.height(Space.step6))
            Greeting(greeting = greeting, compact = true, onSettings = onSettings, onProfile = onProfile, avatar = avatar)
        }
        Spacer(Modifier.height(Space.step6))
        if (track != null) {
            PhoneHero(
                track = track,
                isPlaying = isPlaying,
                onToggle = onToggle,
                onOpen = onOpenPlayer,
            )
        } else {
            ContentColumn {
                StartBody(connected = connected, onSearch = onSearch)
                Spacer(Modifier.height(Space.step10))
            }
        }
    } else {
        val settled = app.valen.ui.artwork.rememberSettledTones(track?.artworkUrl)
        val tones = track?.let { settled }
        if (tones == null) {
            ContentColumn {
                WideLead(greeting = greeting, onSettings = onSettings) {
                    StartBody(connected = connected, onSearch = onSearch)
                }
            }
        } else {
            WashedBand(tones = tones, lit = isPlaying) {
                WideLead(greeting = greeting, onSettings = onSettings) {
                    WideHero(
                        track = track,
                        back = back,
                        isPlaying = isPlaying,
                        onToggle = onToggle,
                        onOpen = onOpenPlayer,
                    )
                }
            }
        }
    }
}

/** A wide window's opening: greeting first, then the song — or the door to search. */
@Composable
private fun WideLead(
    greeting: String,
    onSettings: () -> Unit,
    content: @Composable () -> Unit,
) {
    Spacer(Modifier.height(Space.step10))
    Greeting(greeting = greeting, compact = false, onSettings = onSettings)
    Spacer(Modifier.height(Space.step8))
    content()
    Spacer(Modifier.height(Space.step10))
}

/** The page's opening band: one wash, and content pinned to the page's left edge. */
@Composable
private fun WashedBand(tones: ArtworkTones, lit: Boolean = true, content: @Composable () -> Unit) {
    // The hero's cover pours a new song's colour into the band, and the band dims while paused.
    val spot = remember { app.valen.ui.components.RepaintSpot() }
    Box(modifier = Modifier.fillMaxWidth()) {
        AmbientWash(tones = tones, spot = spot, lit = lit, modifier = Modifier.matchParentSize())
        androidx.compose.runtime.CompositionLocalProvider(app.valen.ui.components.LocalRepaintSpot provides spot) {
            ContentColumn { content() }
        }
    }
}

/** The greeting, in the app's voice. On a phone it carries the way to Settings; the panel does. */
@Composable
private fun Greeting(
    greeting: String,
    compact: Boolean,
    onSettings: () -> Unit,
    onProfile: () -> Unit = {},
    avatar: String? = null,
) {
    Column {
        if (compact) {
            // A phone has no side panel to sign, so the tag signs the top of the page, with the
            // way to Settings beside it, and the greeting gets the full width under them.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Tag(
                    ink = ValenColors.Primary,
                    mist = ValenColors.Primary,
                    modifier = Modifier.width(88.dp),
                )
                Spacer(Modifier.weight(1f))
                // The profile, as its own small round screen.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(Layout.hitTarget)
                        .tap(onClick = onProfile, shape = CircleShape),
                ) {
                    if (avatar != null) {
                        app.valen.ui.profile.PictureImage(uri = avatar, modifier = Modifier.size(32.dp).clip(CircleShape))
                    } else {
                        Icon(
                            imageVector = Route.Profile.icon,
                            contentDescription = Route.Profile.label,
                            tint = ValenColors.Text,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                IconTap(
                    icon = Icons.Rounded.Settings,
                    label = "Settings",
                    onClick = onSettings,
                    tint = ValenColors.Text,
                )
            }
            Spacer(Modifier.height(Space.step2))
        }
        KineticText(
            text = greeting,
            style = if (compact) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium,
            color = ValenColors.Text,
            underline = LocalPlayingTones.current.bright,
        )
    }
}

/**
 * What is playing, as the top of the page. On a wide window the cover sits at the right end of the
 * washed band; on a phone the cover is the band, edge to edge, fading into the page.
 */
@Composable
private fun PhoneHero(
    track: Track,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val chrome = LocalChrome.current
    val tones = ArtworkPalette.tones(track.artworkUrl)
    Box(
        contentAlignment = Alignment.BottomStart,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        Artwork(
            url = track.artworkUrl,
            shape = RectangleShape,
            measure = true,
            iconSize = 48.dp,
            modifier = Modifier.fillMaxSize(),
        )
        Spacer(
            Modifier
                .fillMaxSize()
                .background(
                    // The words sit on the lower half, so that half is firmly under the page's
                    // black: on a pale cover a thin scrim leaves white type unreadable.
                    Brush.verticalGradient(
                        0f to ValenColors.Background.copy(alpha = 0.35f),
                        0.26f to Color.Transparent,
                        0.52f to ValenColors.Background.copy(alpha = 0.62f),
                        0.78f to ValenColors.Background.copy(alpha = 0.90f),
                        1f to ValenColors.Background,
                    ),
                ),
        )
        Column(modifier = Modifier.padding(horizontal = chrome.gutter)) {
            HeroWords(track = track, isPlaying = isPlaying, wide = false)
            Spacer(Modifier.height(Space.step4))
            HeroActions(isPlaying = isPlaying, onToggle = onToggle, onOpen = onOpen)
            Spacer(Modifier.height(Space.step6))
        }
    }
}

@Composable
private fun WideHero(
    track: Track,
    back: Boolean,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // The cover grows a step with the window: at 248dp it reads as a stamp in the corner of a
        // very wide band. It stays the anchor, the words stay the weight beside it.
        val cover = when {
            maxWidth >= 2000.dp -> HeroCoverWidest
            maxWidth >= 1600.dp -> HeroCoverWide
            else -> HeroCover
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = Space.step8),
            ) {
                HeroWords(track = track, isPlaying = isPlaying, wide = true)
                Spacer(Modifier.height(Space.step6))
                HeroActions(isPlaying = isPlaying, onToggle = onToggle, onOpen = onOpen)
            }
            val coverSource = rememberInteraction()
            // A poster on the band: the next song's is slapped on over it, going back lifts it off.
            val spot = app.valen.ui.components.LocalRepaintSpot.current
            Posters(
                item = track,
                key = { it.key },
                // A song with the same cover (an album played through) keeps the poster up and only
                // re-tapes it: an identical sheet is never torn down.
                same = { a, b -> a.artworkUrl != null && a.artworkUrl == b.artworkUrl },
                back = back,
                // No clip on the stack: the tape hangs past the cover's corners. The press is
                // each poster's own, under its tape.
                modifier = Modifier
                    .size(cover)
                    .then(if (spot != null) Modifier.repaintFrom(spot) else Modifier)
                    .tilt()
                    .hoverLift(coverSource),
            ) { shown, entry, onReady ->
                Artwork(
                    url = shown.artworkUrl,
                    shape = Radii.artworkShape,
                    contentDescription = null,
                    measure = shown.key == track.key,
                    animate = entry == PosterEntry.First,
                    onReady = onReady,
                    iconSize = 48.dp,
                    modifier = Modifier
                        .fillMaxSize()
                        .pasted(seed = shown.key.hashCode(), source = coverSource)
                        .taped(shown.key, already = entry == PosterEntry.Returned)
                        .tap(onClick = onOpen, shape = Radii.artworkShape, source = coverSource),
                )
            }
        }
    }
}

private val HeroCover = 248.dp
private val HeroCoverWide = 312.dp
private val HeroCoverWidest = 360.dp

/** "Playing from Spotify": the lead-in dimmed, the service in full white, then the song. */
@Composable
private fun HeroWords(track: Track, isPlaying: Boolean, wide: Boolean) {
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = ValenColors.Text2)) {
                append(if (isPlaying) "Playing from " else "Paused on ")
            }
            append(track.provider.label)
        },
        style = MaterialTheme.typography.titleMedium,
        color = ValenColors.Text,
        maxLines = 1,
    )
    Spacer(Modifier.height(if (wide) Space.step2 else Space.step1))
    KineticText(
        text = track.title,
        style = if (wide) TrackType.display else TrackType.title,
        color = ValenColors.Text,
        maxLines = 2,
    )
    Spacer(Modifier.height(if (wide) Space.step2 else Space.step1))
    Text(
        text = track.artist,
        style = MaterialTheme.typography.titleLarge,
        color = ValenColors.Text2,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun HeroActions(isPlaying: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.step2)) {
        PrimaryButton(
            label = if (isPlaying) "Pause" else "Play",
            icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            onClick = onToggle,
        )
        QuietButton(label = "Open player", onClick = onOpen)
    }
}

/**
 * Nothing has been played: the greeting above is the page's title, so this is one sentence about
 * what the app can do right now and the door to search. The prompt is a door, not a second field.
 */
@Composable
private fun StartBody(connected: Int, onSearch: () -> Unit) {
    Text(
        text = when (connected) {
            0 -> "No service is signed in yet. YouTube Music and SoundCloud play without an account, so you can start with a search."
            1 -> "One of your three services is signed in. One search covers all three."
            2 -> "Two of your three services are signed in. One search covers all three."
            else -> "All three services are signed in. One search covers them all."
        },
        style = MaterialTheme.typography.bodyLarge,
        color = ValenColors.Text2,
        modifier = Modifier.widthIn(max = 520.dp),
    )
    Spacer(Modifier.height(Space.step6))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .height(56.dp)
            .tap(onClick = onSearch, shape = Radii.pillShape, rest = ValenColors.Fill)
            .padding(horizontal = Space.step5),
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = ValenColors.Text,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(Space.step3))
        Text(
            text = "Search every service",
            style = MaterialTheme.typography.bodyLarge,
            color = ValenColors.Text2,
            maxLines = 1,
        )
    }
}

/** The services this build has: side by side when there is room for their buttons, stacked when not. */
@Composable
private fun ServiceTiles(services: List<ProviderId>, sessions: Map<ProviderId, SessionState>, onConnect: (ProviderId) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth >= 880.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.step2)) {
                services.forEach { provider ->
                    ServiceTile(
                        provider = provider,
                        state = sessions[provider] ?: SessionState.SignedOut,
                        onConnect = { onConnect(provider) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Space.step2)) {
                services.forEach { provider ->
                    ServiceTile(
                        provider = provider,
                        state = sessions[provider] ?: SessionState.SignedOut,
                        onConnect = { onConnect(provider) },
                    )
                }
            }
        }
    }
}
