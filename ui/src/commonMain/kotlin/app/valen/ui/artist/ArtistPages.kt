/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.artist

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.valen.core.ArtistView
import app.valen.core.MergedRelease
import app.valen.core.PlaybackCoordinator
import app.valen.core.PlaylistCatalog
import app.valen.core.PlaylistManager
import app.valen.core.ProviderId
import app.valen.core.ReadPlaylist
import app.valen.core.ReleaseKind
import app.valen.core.ReleaseTracks
import app.valen.core.ServicePlaylist
import app.valen.core.SmartSource
import app.valen.core.SmartSpec
import app.valen.core.Track
import app.valen.core.ImportResult
import app.valen.ui.LocalAppScope
import app.valen.ui.artwork.ArtworkPalette
import app.valen.ui.artwork.ArtworkTones
import app.valen.ui.components.AmbientWash
import app.valen.ui.components.ArtistBubble
import app.valen.ui.components.Artwork
import app.valen.ui.components.ContentColumn
import app.valen.ui.components.EmptyState
import app.valen.ui.components.FilterChips
import app.valen.ui.components.IconTap
import app.valen.ui.components.InlineError
import app.valen.ui.components.ItemArrival
import app.valen.ui.theme.LocalChrome
import app.valen.ui.components.PrimaryButton
import app.valen.ui.components.QuietButton
import app.valen.ui.components.ResultRow
import app.valen.ui.components.SectionHeader
import app.valen.ui.components.SkeletonRows
import app.valen.ui.components.arrive
import app.valen.ui.components.bleed
import app.valen.ui.components.hoverLift
import app.valen.ui.components.pasted
import app.valen.ui.components.pressScale
import app.valen.ui.components.rememberInteraction
import app.valen.ui.components.rememberPageList
import app.valen.ui.components.rise
import app.valen.ui.components.tap
import app.valen.ui.menu.MenuOrigin
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.TrackType
import app.valen.ui.theme.ValenColors
import app.valen.ui.theme.timeStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The catalogue's pages, the top of the [Browser]'s stack: an artist, a release, a playlist. A page
 * opened slides in from the side the way it was reached and the one under it eases back; going back
 * runs the same the other way.
 */
@Composable
fun BrowseScreen(
    coordinator: PlaybackCoordinator,
    playlists: PlaylistManager,
    modifier: Modifier = Modifier,
) {
    val browser = LocalBrowser.current
    val top = browser.top ?: return
    val reduced = LocalReducedMotion.current
    AnimatedContent(
        targetState = top,
        contentKey = { it.key },
        transitionSpec = {
            val way = if (browser.forward) 1 else -1
            if (reduced) {
                fadeIn(tween(Motion.StandardMs)) togetherWith fadeOut(tween(Motion.StandardMs))
            } else {
                (slideInHorizontally(tween(PageMs, easing = Motion.Standard)) { it * way / 6 } + fadeIn(tween(PageMs)))
                    .togetherWith(
                        slideOutHorizontally(tween(PageMs, easing = Motion.Standard)) { -it * way / 10 } +
                            fadeOut(tween(PageMs / 2)) + scaleOut(tween(PageMs), targetScale = 0.97f),
                    )
            }
        },
        label = "browsePage",
        modifier = modifier.fillMaxSize().background(ValenColors.Background),
    ) { page ->
        when (page) {
            is BrowsePage.Artist -> ArtistPage(coordinator, page)
            is BrowsePage.Album -> AlbumPage(coordinator, page.release)
            is BrowsePage.Listing -> ListPage(coordinator, playlists, page.playlist)
        }
    }
}

private const val PageMs = 420

/** Something still being read, read, or that could not be. */
private sealed interface Loaded<out T> {
    data object Waiting : Loaded<Nothing>
    data class Ready<T>(val value: T) : Loaded<T>
    data class Failed(val why: String) : Loaded<Nothing>
}

@Composable
private fun <T> load(key: Any, attempt: Int, kept: () -> T? = { null }, read: suspend () -> T): Loaded<T> {
    // A page coming back (Back from an album) starts from what is kept: a frame of "waiting"
    // would shorten its list and lose where it was scrolled to.
    val start = remember(key, attempt) { if (attempt == 0) kept() else null }
    val state by produceState<Loaded<T>>(start?.let { Loaded.Ready(it) } ?: Loaded.Waiting, key, attempt) {
        if (start != null) return@produceState
        value = Loaded.Waiting
        value = try {
            Loaded.Ready(read())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Loaded.Failed(e.message ?: "It could not be read")
        }
    }
    return state
}

// ── The artist ──

@Composable
private fun ArtistPage(coordinator: PlaybackCoordinator, page: BrowsePage.Artist) {
    val chrome = LocalChrome.current
    val scope = LocalAppScope.current
    val browser = LocalBrowser.current
    var attempt by remember(page.key) { mutableIntStateOf(0) }
    val state = load(page.key, attempt, kept = { coordinator.artists.kept(page.name) }) { coordinator.artists.open(page.name, page.known, fresh = attempt > 0) }
    val view = (state as? Loaded.Ready)?.value
    val listState = rememberPageList("artist:" + page.key)
    val tones = ArtworkPalette.tones(view?.imageUrl)

    fun play(from: Track?, list: List<Track>, shuffle: Boolean = false) {
        val songs = list.filter { coordinator.canPlayNow(it) }.ifEmpty { list }
        val first = (if (shuffle) songs.randomOrNull() else from ?: songs.firstOrNull()) ?: return
        if (shuffle) coordinator.setShuffle(true)
        scope.launch { coordinator.play(first, songs, from = view?.name ?: page.name) }
    }

    fun mix() {
        scope.launch {
            val songs = coordinator.mixer().build(
                SmartSpec(sources = setOf(SmartSource.Artists), artists = listOf(view?.name ?: page.name), size = 25, discovery = 0.5f),
                coordinator.taste(),
            )
            if (songs.isNotEmpty()) coordinator.play(songs.first(), songs, from = null, generated = true)
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = chrome.bottom + Space.step12),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "hero") {
            ArtistHero(
                name = view?.name ?: page.name,
                view = view,
                tones = tones,
                listState = listState,
                onBack = { browser.back() },
                onPlay = { view?.let { play(null, it.popular) } },
                onShuffle = { view?.let { play(null, it.popular, shuffle = true) } },
                onMix = { mix() },
            )
        }
        when (state) {
            is Loaded.Waiting -> item(key = "waiting") {
                ContentColumn { SkeletonRows(count = 6) }
            }
            is Loaded.Failed -> item(key = "failed") {
                ContentColumn {
                    Spacer(Modifier.height(Space.step6))
                    EmptyState(
                        title = "Could not open ${page.name}",
                        body = state.why,
                        actionLabel = "Try again",
                        onAction = { attempt++ },
                    )
                }
            }
            is Loaded.Ready -> artistSections(coordinator, state.value, onPlay = { t -> play(t, state.value.popular) })
        }
    }
}

/**
 * The top of an artist's page: their wide picture (or their portrait) bleeding under the page,
 * drifting slower than the page as it scrolls; the portrait, their name, what the services count,
 * and the ways to start listening.
 */
@Composable
private fun ArtistHero(
    name: String,
    view: ArtistView?,
    tones: ArtworkTones,
    listState: LazyListState,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onMix: () -> Unit,
) {
    val chrome = LocalChrome.current
    val compact = chrome.compact
    val reduced = LocalReducedMotion.current
    val band = if (compact) 300.dp else 380.dp
    val ready = view != null
    val shown by animateFloatAsState(if (ready) 1f else 0f, tween(Motion.StandardMs * 2, easing = Motion.Standard), label = "heroShown")
    Box(modifier = Modifier.fillMaxWidth()) {
        AmbientWash(tones = tones, strength = 0.8f, fade = 1f, modifier = Modifier.matchParentSize())
        // The band picture: the wide header where a service has one, else the portrait itself.
        val picture = view?.headerUrl ?: view?.imageUrl
        if (picture != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(band)
                    .graphicsLayer {
                        // Read here, in the draw phase: the scroll never recomposes the page.
                        val first = listState.firstVisibleItemIndex == 0
                        val offset = if (first) listState.firstVisibleItemScrollOffset.toFloat() else size.height
                        translationY = if (reduced) 0f else offset * 0.45f
                        alpha = shown * (1f - (offset / size.height).coerceIn(0f, 1f) * 0.9f)
                        val grow = if (reduced) 1f else 1.06f - 0.06f * shown
                        scaleX = grow
                        scaleY = grow
                    },
            ) {
                Artwork(
                    url = picture,
                    shape = RectangleShape,
                    animate = true,
                    measure = view?.headerUrl == null,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (view?.headerUrl == null) 0.55f else 0.85f },
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0f to ValenColors.Background.copy(alpha = 0.35f),
                                0.55f to ValenColors.Background.copy(alpha = 0.25f),
                                1f to ValenColors.Background,
                            ),
                        ),
                )
            }
        }
        ContentColumn {
            Spacer(Modifier.height(if (compact) Space.step4 else Space.step6))
            IconTap(icon = Icons.AutoMirrored.Rounded.ArrowBack, label = "Back", onClick = onBack, tint = ValenColors.Text)
            // Room for the band picture to show above the portrait; without one, none.
            val room = when {
                picture == null -> Space.step6
                compact -> band * 0.32f
                else -> band * 0.42f
            }
            Spacer(Modifier.height(room))
            val portrait = if (compact) 120.dp else 168.dp
            if (compact) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Portrait(view?.imageUrl, portrait, ready)
                    Spacer(Modifier.height(Space.step4))
                    HeroWords(name = name, view = view, centered = true)
                }
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Portrait(view?.imageUrl, portrait, ready)
                    Spacer(Modifier.width(Space.step8))
                    Column(modifier = Modifier.weight(1f)) { HeroWords(name = name, view = view, centered = false) }
                }
            }
            Spacer(Modifier.height(Space.step6))
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.step2, if (compact) Alignment.CenterHorizontally else Alignment.Start),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = 0.4f + 0.6f * shown },
            ) {
                PrimaryButton(label = "Play", icon = Icons.Rounded.PlayArrow, enabled = view?.popular?.isNotEmpty() == true, onClick = onPlay)
                QuietButton(label = "Shuffle", icon = Icons.Rounded.Shuffle, enabled = (view?.popular?.size ?: 0) > 1, onClick = onShuffle)
                QuietButton(label = "Mix", icon = Icons.Rounded.AutoAwesome, onClick = onMix)
            }
            Spacer(Modifier.height(Space.step8))
        }
    }
}

/** The artist's round portrait; it lands like a sticker once the page is read. */
@Composable
private fun Portrait(url: String?, size: Dp, ready: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .then(if (ready) Modifier.arrive(0, ItemArrival.Slap) else Modifier)
            .clip(CircleShape)
            .background(ValenColors.Container),
    ) {
        if (url != null) Artwork(url = url, shape = CircleShape, measure = true, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun HeroWords(name: String, view: ArtistView?, centered: Boolean) {
    val compact = LocalChrome.current.compact
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        if (view?.verified == true) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.arrive(1, ItemArrival.Rise)) {
                Icon(Icons.Rounded.Verified, contentDescription = null, tint = ValenColors.Primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Space.step1))
                Text("Verified artist", style = MaterialTheme.typography.labelLarge, color = ValenColors.Text)
            }
            Spacer(Modifier.height(Space.step1))
        }
        Text(
            text = name,
            style = if (compact) TrackType.title else TrackType.display,
            color = ValenColors.Text,
            textAlign = align,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val counts = listOfNotNull(
            view?.monthlyListeners?.let { "${compactCount(it)} monthly listeners" },
            view?.followers?.let { "${compactCount(it)} followers" },
        )
        if (counts.isNotEmpty() || view != null) {
            Spacer(Modifier.height(Space.step2))
            Text(
                text = (counts + listOfNotNull(view?.let { "on " + servicesIn(it.sources.map { s -> s.provider }) })).joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = ValenColors.Text2,
                textAlign = align,
                modifier = Modifier.arrive(2, ItemArrival.Rise),
            )
        }
    }
}

/** What an artist's page holds under the top, section by section; a section with nothing is left out. */
private fun androidx.compose.foundation.lazy.LazyListScope.artistSections(
    coordinator: PlaybackCoordinator,
    view: ArtistView,
    onPlay: (Track) -> Unit,
) {
    if (view.missing.isNotEmpty()) {
        item(key = "missing") {
            ContentColumn {
                view.missing.forEach { (provider, why) -> InlineError(message = "${provider.label}: $why") }
                Spacer(Modifier.height(Space.step4))
            }
        }
    }
    if (view.popular.isNotEmpty()) {
        item(key = "popular") { PopularSection(coordinator, view, onPlay) }
    }
    if (view.releases.isNotEmpty()) {
        item(key = "discography") { DiscographySection(view.releases) }
    }
    if (view.appearsOn.isNotEmpty()) {
        item(key = "appears") { ReleaseShelf(title = "Appears on", releases = view.appearsOn, index = 8) }
    }
    if (view.playlists.isNotEmpty()) {
        item(key = "playlists") { PlaylistShelf(view.playlists) }
    }
    if (view.related.isNotEmpty()) {
        item(key = "related") { RelatedShelf(view) }
    }
    if (view.about != null || view.monthlyListeners != null || view.followers != null) {
        item(key = "about") { AboutCard(view) }
    }
}

@Composable
private fun PopularSection(coordinator: PlaybackCoordinator, view: ArtistView, onPlay: (Track) -> Unit) {
    val snapshot by coordinator.nowPlaying.collectAsState()
    val queue by coordinator.queue.collectAsState()
    val queued = remember(queue) { queue.mapTo(HashSet()) { it.key } }
    val playingKey = snapshot?.track?.key
    val accent = ArtworkPalette.tones(snapshot?.track?.artworkUrl).bright
    var more by remember(view.name) { mutableStateOf(false) }
    val compact = LocalChrome.current.compact
    ContentColumn {
        SectionHeader(title = "Popular", modifier = Modifier.arrive(3, ItemArrival.Swing))
        Spacer(Modifier.height(Space.step2))
        Column(modifier = Modifier.fillMaxWidth().bleed(Space.step2)) {
            view.popular.forEachIndexed { i, track ->
                AnimatedVisibility(
                    visible = i < PopularShown || more,
                    enter = expandVertically(tween(Motion.StandardMs, easing = Motion.Standard)) + fadeIn(),
                    exit = shrinkVertically(tween(Motion.StandardMs, easing = Motion.Standard)) + fadeOut(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.rise(i)) {
                        Text(
                            text = "${i + 1}",
                            style = timeStyle(large = true),
                            color = if (track.key == playingKey) accent else ValenColors.Text3,
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(28.dp),
                        )
                        Spacer(Modifier.width(Space.step2))
                        ResultRow(
                            track = track,
                            onPlay = { onPlay(track) },
                            onEnqueue = { coordinator.enqueue(track) },
                            current = track.key == playingKey,
                            playing = track.key == playingKey && snapshot?.isPlaying == true,
                            queued = track.key in queued,
                            accent = accent,
                            menu = MenuOrigin.Song,
                            modifier = Modifier.weight(1f),
                        )
                        val plays = view.plays[track.key]
                        if (!compact && plays != null) {
                            Text(
                                text = "${compactCount(plays)} plays",
                                style = timeStyle(),
                                color = ValenColors.Text3,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(96.dp).padding(end = Space.step2),
                            )
                        }
                    }
                }
            }
        }
        if (view.popular.size > PopularShown) {
            Spacer(Modifier.height(Space.step2))
            QuietButton(label = if (more) "Show less" else "Show more", small = true, onClick = { more = !more })
        }
        Spacer(Modifier.height(Space.step8))
    }
}

private const val PopularShown = 5

/** Which of the releases the discography shows. */
private enum class Shelf(val label: String, val kinds: Set<ReleaseKind>) {
    Albums("Albums", setOf(ReleaseKind.ALBUM)),
    Singles("Singles and EPs", setOf(ReleaseKind.SINGLE, ReleaseKind.EP)),
    Compilations("Compilations", setOf(ReleaseKind.COMPILATION)),
}

@Composable
private fun DiscographySection(releases: List<MergedRelease>) {
    val present = remember(releases) { Shelf.entries.filter { s -> releases.any { it.kind in s.kinds } } }
    var only by remember(releases) { mutableStateOf<Shelf?>(null) }
    val shown = remember(releases, only) { only?.let { s -> releases.filter { it.kind in s.kinds } } ?: releases }
    ContentColumn {
        SectionHeader(title = "Discography", modifier = Modifier.arrive(5, ItemArrival.Swing))
        if (present.size > 1) {
            Spacer(Modifier.height(Space.step2))
            FilterChips(
                options = present,
                selected = setOfNotNull(only),
                onToggle = { only = if (only == it) null else it },
                label = { it.label },
                allLabel = "All",
                allSelected = only == null,
                onAll = { only = null },
            )
        }
        Spacer(Modifier.height(Space.step3))
    }
    // The shelf turns over when the filter changes: the cards of the new choice land in turn.
    AnimatedContent(
        targetState = shown,
        contentKey = { only },
        transitionSpec = { fadeIn(tween(Motion.StandardMs)) togetherWith fadeOut(tween(Motion.StandardMs / 2)) },
        label = "discography",
    ) { list -> ReleaseRow(list, index = 6) }
    Spacer(Modifier.height(Space.step8))
}

@Composable
private fun ReleaseShelf(title: String, releases: List<MergedRelease>, index: Int) {
    ContentColumn {
        SectionHeader(title = title, modifier = Modifier.arrive(index, ItemArrival.Swing))
        Spacer(Modifier.height(Space.step3))
    }
    ReleaseRow(releases, index = index + 1)
    Spacer(Modifier.height(Space.step8))
}

@Composable
private fun ReleaseRow(releases: List<MergedRelease>, index: Int) {
    val chrome = LocalChrome.current
    val browser = LocalBrowser.current
    val size = if (chrome.compact) 148.dp else 176.dp
    // Inside the page's column (clear of the floating panel), bled back out to its edges.
    ContentColumn {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Space.step4),
        contentPadding = PaddingValues(horizontal = chrome.gutter),
        modifier = Modifier.fillMaxWidth().bleed(chrome.gutter),
    ) {
        itemsIndexed(releases, key = { _, it -> it.key + it.kind }) { i, release ->
            CoverCard(
                seed = release.key.hashCode(),
                artwork = release.artworkUrl,
                title = release.title,
                detail = listOfNotNull(release.year?.toString(), release.kind.label).joinToString("  ·  "),
                size = size,
                onClick = { browser.open(BrowsePage.Album(release)) },
                modifier = Modifier.arrive(index + i, ItemArrival.Slap),
            )
        }
    }
    }
}

@Composable
private fun PlaylistShelf(playlists: List<ServicePlaylist>) {
    val chrome = LocalChrome.current
    val browser = LocalBrowser.current
    ContentColumn {
        SectionHeader(title = "Playlists", modifier = Modifier.arrive(10, ItemArrival.Swing))
        Spacer(Modifier.height(Space.step3))
    }
    // Inside the page's column (clear of the floating panel), bled back out to its edges.
    ContentColumn {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Space.step4),
        contentPadding = PaddingValues(horizontal = chrome.gutter),
        modifier = Modifier.fillMaxWidth().bleed(chrome.gutter),
    ) {
        itemsIndexed(playlists, key = { _, it -> it.provider.name + it.id }) { i, list ->
            CoverCard(
                seed = list.id.hashCode(),
                artwork = list.artworkUrl,
                title = list.title,
                // "This is …" lists are the service's own: its name once, not as owner and service.
                detail = listOfNotNull(list.owner?.takeUnless { it.equals(list.provider.label, true) || it.equals(list.provider.short, true) }, list.provider.short).joinToString("  ·  "),
                size = if (chrome.compact) 148.dp else 176.dp,
                onClick = { if (list.link != null) browser.open(BrowsePage.Listing(list)) },
                modifier = Modifier.arrive(11 + i, ItemArrival.Slap),
            )
        }
    }
    }
    Spacer(Modifier.height(Space.step8))
}

@Composable
private fun RelatedShelf(view: ArtistView) {
    val chrome = LocalChrome.current
    val browser = LocalBrowser.current
    ContentColumn {
        SectionHeader(title = "Fans also like", modifier = Modifier.arrive(12, ItemArrival.Swing))
        Spacer(Modifier.height(Space.step3))
    }
    ContentColumn {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Space.step5),
        contentPadding = PaddingValues(horizontal = chrome.gutter),
        modifier = Modifier.fillMaxWidth().bleed(chrome.gutter),
    ) {
        itemsIndexed(view.related, key = { _, it -> it.key }) { i, artist ->
            ArtistBubble(
                name = artist.name,
                artwork = artist.imageUrl,
                size = if (chrome.compact) 104.dp else 128.dp,
                onClick = { browser.open(BrowsePage.Artist(artist.name, artist.copies)) },
                modifier = Modifier.arrive(13 + i, ItemArrival.Slap),
            )
        }
    }
    }
    Spacer(Modifier.height(Space.step8))
}

/** Who they are, in the services' words, with what the services count. */
@Composable
private fun AboutCard(view: ArtistView) {
    var open by remember(view.name) { mutableStateOf(false) }
    ContentColumn {
        SectionHeader(title = "About", modifier = Modifier.arrive(14, ItemArrival.Swing))
        Spacer(Modifier.height(Space.step3))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .arrive(15, ItemArrival.Rise)
                .clip(Radii.cardShape)
                .background(ValenColors.Surface)
                .tap(onClick = { open = !open }, shape = Radii.cardShape)
                .padding(Space.step6),
        ) {
            val numbers = listOfNotNull(
                view.monthlyListeners?.let { compactCount(it) to "monthly listeners" },
                view.followers?.let { compactCount(it) to "followers" },
            )
            if (numbers.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.step8)) {
                    numbers.forEach { (n, what) ->
                        Column {
                            Text(n, style = TrackType.headline, color = ValenColors.Text)
                            Text(what, style = MaterialTheme.typography.bodySmall, color = ValenColors.Text2)
                        }
                    }
                }
            }
            view.about?.let { about ->
                if (numbers.isNotEmpty()) Spacer(Modifier.height(Space.step4))
                Text(
                    text = about,
                    style = MaterialTheme.typography.bodyLarge,
                    color = ValenColors.Text,
                    maxLines = if (open) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.animateContentSize(tween(Motion.StandardMs, easing = Motion.Standard)),
                )
                if (!open && about.length > 220) {
                    Spacer(Modifier.height(Space.step2))
                    Text("Read more", style = MaterialTheme.typography.labelLarge, color = ValenColors.Primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(Space.step8))
    }
}

/**
 * A release or playlist as a cover on a shelf, its title and one line under it. The cover is
 * pasted at a slight angle like the app's other covers and lifts under a pointer, where a play mark
 * comes up in its corner.
 */
@Composable
internal fun CoverCard(
    seed: Int,
    artwork: String?,
    title: String,
    detail: String,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = rememberInteraction()
    val hovered by source.collectIsHoveredAsState()
    val mark by animateFloatAsState(if (hovered) 1f else 0f, Motion.press(), label = "coverOpenMark")
    Column(
        modifier = modifier
            .width(size)
            .pasted(seed = seed, source = source)
            .hoverLift(source)
            .pressScale(source)
            .tap(onClick = onClick, shape = Radii.cardShape, source = source, rest = ValenColors.Background),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Artwork(url = artwork, shape = Radii.cardShape, iconSize = 32.dp, modifier = Modifier.size(size))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(Space.step3)
                    .size(40.dp)
                    .graphicsLayer {
                        alpha = mark
                        translationY = (1f - mark) * 6.dp.toPx()
                    }
                    .clip(CircleShape)
                    .background(ValenColors.Primary),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = ValenColors.OnPrimary, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.height(Space.step3))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = ValenColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = Space.step1),
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = ValenColors.Text2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Space.step1, end = Space.step1, bottom = Space.step2),
        )
    }
}

// ── A release ──

@Composable
private fun AlbumPage(coordinator: PlaybackCoordinator, release: MergedRelease) {
    var attempt by remember(release.key) { mutableIntStateOf(0) }
    val state = load(release.key + release.copies.firstOrNull()?.id, attempt, kept = { coordinator.artists.keptTracks(release) }) { coordinator.artists.tracks(release) }
    val tracks = (state as? Loaded.Ready<ReleaseTracks>)?.value
    val songs = tracks?.tracks.orEmpty()
    val browser = LocalBrowser.current
    TrackListPage(
        coordinator = coordinator,
        key = "album:" + release.key,
        artwork = release.artworkUrl,
        kicker = listOfNotNull(release.kind.label, release.year?.toString()).joinToString("  ·  "),
        title = release.title,
        byline = release.artist,
        onByline = { browser.openArtistOf(release.artist) },
        detail = if (tracks == null) servicesIn(release.services) else listOf(
            songCount(songs.size),
            duration(songs.sumOf { it.durationMs }),
            "on " + servicesIn(release.services),
        ).filter { it.isNotEmpty() }.joinToString("  ·  "),
        footer = tracks?.label,
        state = state,
        songs = songs,
        from = release.title,
        onRetry = { attempt++ },
    )
}

// ── A playlist of a service ──

@Composable
private fun ListPage(coordinator: PlaybackCoordinator, playlists: PlaylistManager, list: ServicePlaylist) {
    val scope = LocalAppScope.current
    var attempt by remember(list.id) { mutableIntStateOf(0) }
    val state = load(list.provider.name + list.id, attempt) {
        val link = list.link ?: throw IllegalStateException("This playlist has no address to read")
        val catalog = coordinator.provider(list.provider) as? PlaylistCatalog
            ?: throw IllegalStateException("${list.provider.label} playlists cannot be read here")
        catalog.read(link)
    }
    val read = (state as? Loaded.Ready<ReadPlaylist>)?.value
    val songs = read?.tracks.orEmpty()
    var saving by remember(list.id) { mutableStateOf<String?>(null) }
    TrackListPage(
        coordinator = coordinator,
        key = "list:" + list.provider.name + list.id,
        artwork = list.artworkUrl ?: read?.artworkUrl,
        kicker = "Playlist  ·  ${list.provider.short}",
        title = read?.name ?: list.title,
        byline = list.owner,
        onByline = null,
        detail = if (read == null) "" else songCount(songs.size) + (duration(songs.sumOf { it.durationMs }).takeIf { it.isNotEmpty() }?.let { "  ·  $it" } ?: ""),
        footer = read?.note,
        state = state,
        songs = songs,
        from = read?.name ?: list.title,
        onRetry = { attempt++ },
        extra = {
            val url = list.link?.url
            if (url != null) {
                QuietButton(
                    label = saving ?: "Save",
                    icon = Icons.Rounded.LibraryAdd,
                    enabled = saving == null,
                    onClick = {
                        saving = "Saving…"
                        scope.launch {
                            saving = when (val r = playlists.importLink(url)) {
                                is ImportResult.Imported -> "Saved"
                                is ImportResult.Failed -> { coordinator.confirm(r.reason); null }
                            }
                        }
                    },
                )
            }
        },
    )
}

/**
 * A release or a playlist: its cover over its own colours, what it is, its title, who made it (the
 * way to their page), how long it is, and its songs, numbered.
 */
@Composable
private fun TrackListPage(
    coordinator: PlaybackCoordinator,
    key: String,
    artwork: String?,
    kicker: String,
    title: String,
    byline: String?,
    onByline: (() -> Unit)?,
    detail: String,
    footer: String?,
    state: Loaded<*>,
    songs: List<Track>,
    from: String,
    onRetry: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    val chrome = LocalChrome.current
    val compact = chrome.compact
    val scope = LocalAppScope.current
    val browser = LocalBrowser.current
    val snapshot by coordinator.nowPlaying.collectAsState()
    val queue by coordinator.queue.collectAsState()
    val queued = remember(queue) { queue.mapTo(HashSet()) { it.key } }
    val playingKey = snapshot?.track?.key
    val accent = ArtworkPalette.tones(snapshot?.track?.artworkUrl).bright
    val tones = ArtworkPalette.tones(artwork)
    val listState = rememberPageList(key)

    fun play(from0: Track?, shuffle: Boolean = false) {
        val playable = songs.filter { coordinator.canPlayNow(it) }.ifEmpty { songs }
        val first = (if (shuffle) playable.randomOrNull() else from0 ?: playable.firstOrNull()) ?: return
        if (shuffle) coordinator.setShuffle(true)
        scope.launch { coordinator.play(first, playable, from = from) }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = chrome.bottom + Space.step12),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") {
            Box(modifier = Modifier.fillMaxWidth()) {
                AmbientWash(tones = tones, strength = 0.7f, modifier = Modifier.matchParentSize())
                ContentColumn {
                    Spacer(Modifier.height(if (compact) Space.step4 else Space.step6))
                    IconTap(icon = Icons.AutoMirrored.Rounded.ArrowBack, label = "Back", onClick = { browser.back() }, tint = ValenColors.Text)
                    Spacer(Modifier.height(Space.step4))
                    val cover = if (compact) 132.dp else 220.dp
                    val words: @Composable () -> Unit = {
                        Text(kicker, style = MaterialTheme.typography.labelLarge, color = ValenColors.Text2, modifier = Modifier.arrive(1, ItemArrival.Rise))
                        Spacer(Modifier.height(Space.step1))
                        Text(
                            text = title,
                            style = if (compact) TrackType.headline else TrackType.title,
                            color = ValenColors.Text,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!byline.isNullOrBlank()) {
                            Spacer(Modifier.height(Space.step2))
                            Text(
                                text = byline,
                                style = MaterialTheme.typography.titleMedium,
                                color = ValenColors.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = if (onByline != null) Modifier.tap(onClick = onByline, shape = Radii.rowShape) else Modifier,
                            )
                        }
                        if (detail.isNotEmpty()) {
                            Spacer(Modifier.height(Space.step1))
                            Text(detail, style = MaterialTheme.typography.bodyMedium, color = ValenColors.Text2)
                        }
                    }
                    Row(verticalAlignment = Alignment.Bottom) {
                        Artwork(
                            url = artwork,
                            shape = Radii.artworkShape,
                            measure = true,
                            iconSize = 40.dp,
                            modifier = Modifier.size(cover).arrive(0, ItemArrival.Slap),
                        )
                        Spacer(Modifier.width(if (compact) Space.step4 else Space.step8))
                        Column(modifier = Modifier.weight(1f)) { words() }
                    }
                    Spacer(Modifier.height(Space.step6))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.step2), verticalAlignment = Alignment.CenterVertically) {
                        PrimaryButton(label = "Play", icon = Icons.Rounded.PlayArrow, enabled = songs.isNotEmpty(), onClick = { play(null) })
                        QuietButton(label = "Shuffle", icon = Icons.Rounded.Shuffle, enabled = songs.size > 1, onClick = { play(null, shuffle = true) })
                        extra()
                    }
                    Spacer(Modifier.height(Space.step6))
                }
            }
        }
        when (state) {
            is Loaded.Waiting -> item(key = "waiting") { ContentColumn { SkeletonRows(count = 8) } }
            is Loaded.Failed -> item(key = "failed") {
                ContentColumn {
                    EmptyState(title = "Could not open $title", body = state.why, actionLabel = "Try again", onAction = onRetry)
                }
            }
            is Loaded.Ready<*> -> {
                itemsIndexed(songs, key = { i, t -> "${t.key}#$i" }) { i, track ->
                    ContentColumn {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.bleed(Space.step2).rise(i)) {
                            Text(
                                text = "${i + 1}",
                                style = timeStyle(large = true),
                                color = if (track.key == playingKey) accent else ValenColors.Text3,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(28.dp),
                            )
                            Spacer(Modifier.width(Space.step2))
                            ResultRow(
                                track = track,
                                onPlay = { play(track) },
                                onEnqueue = { coordinator.enqueue(track) },
                                current = track.key == playingKey,
                                playing = track.key == playingKey && snapshot?.isPlaying == true,
                                queued = track.key in queued,
                                accent = accent,
                                menu = MenuOrigin.Song,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (!footer.isNullOrBlank()) {
                    item(key = "footer") {
                        ContentColumn {
                            Spacer(Modifier.height(Space.step6))
                            Text(footer, style = MaterialTheme.typography.bodySmall, color = ValenColors.Text3)
                        }
                    }
                }
            }
        }
    }
}

// ── Words ──

/** 1234 → "1,234"; 25_400_000 → "25.4M"; the way music apps count. */
internal fun compactCount(n: Long): String {
    fun one(v: Double, unit: String): String {
        val tenths = Math.round(v * 10)
        return if (tenths % 10 == 0L) "${tenths / 10}$unit" else "${tenths / 10}.${tenths % 10}$unit"
    }
    return when {
        n >= 1_000_000_000L -> one(n / 1e9, "B")
        n >= 1_000_000L -> one(n / 1e6, "M")
        n >= 10_000L -> one(n / 1e3, "K")
        else -> n.toString().reversed().chunked(3).joinToString(",").reversed()
    }
}

private fun servicesIn(providers: List<ProviderId>): String {
    val names = providers.distinct().map { it.label }
    return when (names.size) {
        0 -> ""
        1 -> names.first()
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}

private fun songCount(n: Int) = if (n == 1) "1 song" else "$n songs"

private fun duration(ms: Long): String {
    if (ms <= 0) return ""
    val minutes = (ms / 60_000).toInt()
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}
