/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.screens

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import app.valen.core.artistsIn
import app.valen.ui.artist.BrowsePage
import app.valen.ui.artist.LocalBrowser

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.rounded.Lyrics
import app.valen.ui.lyrics.LyricsPane
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.valen.ui.components.rememberPosition
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import app.valen.core.PlaybackCoordinator
import app.valen.core.PlayerSnapshot
import app.valen.core.Track
import app.valen.ui.LocalAppScope
import app.valen.ui.artwork.ArtworkPalette
import app.valen.ui.components.AmbientWash
import app.valen.ui.components.Artwork
import app.valen.ui.components.ArtworkSquare
import app.valen.ui.components.EmptyState
import app.valen.ui.components.IconTap
import app.valen.ui.components.KineticText
import app.valen.ui.components.breathe
import app.valen.ui.components.taped
import app.valen.ui.components.pasted
import app.valen.ui.components.rememberInteraction
import androidx.compose.foundation.hoverable
import app.valen.ui.components.tilt
import app.valen.ui.components.Posters
import app.valen.ui.components.repaintFrom
import app.valen.ui.components.PosterEntry
import app.valen.ui.components.rememberWentBack
import app.valen.ui.components.QuietButton
import app.valen.ui.components.SeekBar
import app.valen.ui.components.TransportBar
import app.valen.ui.components.PlayModes
import app.valen.ui.KeepScreenOn
import app.valen.ui.menu.LiquidHeart
import app.valen.ui.menu.MenuOrigin
import app.valen.ui.menu.MoreButton
import app.valen.ui.menu.SleepChip
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.foundation.layout.Row
import app.valen.core.PlaylistManager
import app.valen.core.Playlist
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import app.valen.ui.components.VolumeSlider
import app.valen.ui.components.tap
import app.valen.ui.theme.LocalChrome
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.TrackType
import app.valen.ui.theme.ValenColors
import kotlinx.coroutines.launch

/**
 * The stage. The cover's colours fill the room and drift while the song plays; the cover itself
 * is the largest thing in it. Beside it, or under it on a narrow window: the song, one thick rail,
 * three bare glyphs, and what comes next.
 */
@Composable
fun NowPlayingScreen(
    coordinator: PlaybackCoordinator,
    onOpenQueue: () -> Unit,
    onBrowse: () -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    /** The library the heart fills; without it the stage shows no heart. */
    playlists: PlaylistManager? = null,
    /** Opens the sound notebook; without it the stage shows no way there. */
    onSound: (() -> Unit)? = null,
) {
    val scope = LocalAppScope.current
    val chrome = LocalChrome.current
    val snapshot by coordinator.nowPlaying.collectAsState()
    val position = rememberPosition(coordinator)
    val settings by coordinator.settings.collectAsState()
    val queue by coordinator.queue.collectAsState()
    val index by coordinator.index.collectAsState()
    val track = snapshot?.track
    // Held at the last cover's until the new one is measured: the room goes straight from one
    // song's colour to the next, poured out from the cover.
    val tones = app.valen.ui.artwork.rememberSettledTones(track?.artworkUrl)
    val spot = remember { app.valen.ui.components.RepaintSpot() }
    val next = if (index >= 0) queue.getOrNull(index + 1) else null
    // Whether the song on show was reached by going back: the old poster then lifts off it.
    val back = rememberWentBack(track?.key, index, queue)
    val library = playlists?.library
    val kept by remember(library) { library?.playlists ?: MutableStateFlow(emptyList<Playlist>()) }.collectAsState()
    val liked = track != null && kept.firstOrNull { it.isLiked }?.tracks?.any { it.key == track.key } == true
    // The screen stays awake on the stage when asked (on a phone; a desktop has no say here).
    KeepScreenOn(settings.keepScreenOn)

    val controls = StageControls(
        onSeek = { position -> scope.launch { coordinator.seek(position) } },
        onPrevious = { scope.launch { coordinator.previous() } },
        onToggle = { scope.launch { coordinator.toggle() } },
        onNext = { scope.launch { coordinator.next() } },
        onVolume = { value -> scope.launch { coordinator.setVolume(value) } },
        modes = PlayModes(
            shuffle = settings.shuffle,
            repeat = settings.repeat,
            onShuffle = { coordinator.setShuffle(!settings.shuffle) },
            onRepeat = { coordinator.cycleRepeat() },
        ),
        heart = if (library != null && track != null) {
            { key(track.key) { LiquidHeart(liked = liked, onToggle = { scope.launch { library.toggleLiked(track) } }) } }
        } else {
            null
        },
        sleep = { SleepChip(coordinator = coordinator, track = track) },
        lyrics = if (settings.lyrics && track != null) {
            { place, compact ->
                LyricsPane(
                    coordinator = coordinator,
                    track = track,
                    playing = snapshot?.isPlaying == true && snapshot?.isBuffering != true,
                    onSeek = { at -> scope.launch { coordinator.seek(at) } },
                    compact = compact,
                    modifier = place,
                )
            }
        } else {
            null
        },
    )

    Box(modifier = modifier.fillMaxSize().background(ValenColors.Background)) {
        AmbientWash(
            tones = tones,
            drift = snapshot?.isPlaying == true,
            fade = 0.72f,
            spot = spot,
            // The lights go down while the song is paused (not while it is only loading).
            lit = snapshot?.isPlaying == true || snapshot?.isBuffering == true,
            modifier = Modifier.fillMaxSize(),
        )
        androidx.compose.runtime.CompositionLocalProvider(app.valen.ui.components.LocalRepaintSpot provides spot) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = chrome.start),
        ) {
            val side = maxWidth >= 820.dp && maxWidth >= maxHeight
            when {
                track == null -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize().padding(horizontal = chrome.gutter),
                ) {
                    EmptyState(
                        title = "The stage is empty",
                        body = "Nothing is queued. Search for a song and it plays here.",
                        actionLabel = "Search music",
                        onAction = onBrowse,
                        centered = true,
                    )
                }

                side -> SideStage(
                    track = track,
                    snapshot = snapshot,
                    position = position,
                    volume = settings.volume,
                    next = next,
                    controls = controls,
                    onOpenQueue = onOpenQueue,
                    stageWidth = maxWidth,
                    stageHeight = maxHeight,
                    back = back,
                )

                else -> TallStage(
                    track = track,
                    snapshot = snapshot,
                    position = position,
                    volume = settings.volume,
                    next = next,
                    controls = controls,
                    onOpenQueue = onOpenQueue,
                    stageWidth = maxWidth,
                    stageHeight = maxHeight,
                    showVolume = !chrome.compact,
                    topInset = if (onClose != null) Space.step12 else Space.step6,
                    back = back,
                )
            }
            if (track != null) {
                val sound by coordinator.sound.collectAsState()
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = chrome.gutter - Space.step2, top = Space.step2),
                ) {
                    // The song's words, beside the cover or in its place; in the one colour while shown.
                    IconTap(
                        icon = Icons.Rounded.Lyrics,
                        label = if (settings.lyrics) "Hide lyrics" else "Show lyrics",
                        onClick = { coordinator.setLyricsShown(!settings.lyrics) },
                        tint = if (settings.lyrics) ValenColors.Primary else ValenColors.Text,
                        iconSize = 24.dp,
                    )
                    if (onSound != null) {
                        // The notebook's way in, in the one colour while effects are doing something.
                        IconTap(
                            icon = Icons.Rounded.GraphicEq,
                            label = "Sound",
                            onClick = onSound,
                            // Faint while the song's service keeps its own sound; the one colour while effects work.
                            tint = when {
                                coordinator.effectSupport(track) is app.valen.core.EffectSupport.None -> ValenColors.Text3
                                sound.on && !sound.isFlat -> ValenColors.Primary
                                else -> ValenColors.Text
                            },
                            iconSize = 24.dp,
                        )
                    }
                    MoreButton(track = track, origin = MenuOrigin.Player, tint = ValenColors.Text, iconSize = 26.dp)
                }
            }
            if (onClose != null) {
                IconTap(
                    icon = Icons.Rounded.KeyboardArrowDown,
                    label = "Close the player",
                    onClick = onClose,
                    tint = ValenColors.Text,
                    iconSize = 28.dp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = chrome.gutter - Space.step2, top = Space.step2),
                )
            }
        }
        }
    }
}

private class StageControls(
    val onSeek: (Long) -> Unit,
    val onPrevious: () -> Unit,
    val onToggle: () -> Unit,
    val onNext: () -> Unit,
    val onVolume: (Float) -> Unit,
    val modes: PlayModes,
    /** The like, beside the song's words. */
    val heart: (@Composable () -> Unit)?,
    /** The sleep timer's pill, above what comes next. */
    val sleep: @Composable () -> Unit,
    /** The song's words, placed by the stage (compact on a phone); null while they are hidden. */
    val lyrics: (@Composable (Modifier, Boolean) -> Unit)?,
)

/** The smallest a stage cover gets, the share of the height it may take, and where it stops. */
private val StageCoverFloor = 220.dp
private const val StageCoverShare = 0.62f
private val StageCoverMax = 720.dp

/** The words, rail and transport stop here, so the stage keeps its shape on a very wide window. */
private val StageWordsMax = 560.dp

/** With the lyrics shown the column is wider and the cover sits back to this share of its size. */
private val StageLyricsMax = 680.dp
private const val StageCoverWithWords = 0.8f

/** Wide and landscape: the cover on the left, everything about the song beside it. */
@Composable
private fun SideStage(
    track: Track,
    snapshot: PlayerSnapshot?,
    position: State<Long>,
    volume: Float,
    next: Track?,
    controls: StageControls,
    onOpenQueue: () -> Unit,
    stageWidth: Dp,
    stageHeight: Dp,
    back: Boolean,
) {
    val gutter = LocalChrome.current.gutter
    val reduced = LocalReducedMotion.current
    val gap = if (stageWidth >= 1100.dp) Space.step16 else Space.step10
    val words = controls.lyrics != null
    val column = if (words) StageLyricsMax else StageWordsMax
    // The stage is a block, not a page, so it grows with the window instead of hugging one size:
    // a fixed cover on a 2880px-wide screen reads as a postage stamp. The cover may take a share of
    // the stage's height, up to a size that no longer needs to grow. With the words shown it sits
    // back a little to give them the room.
    val ceiling = maxOf(StageCoverFloor, min(stageHeight * StageCoverShare, StageCoverMax))
    val room = if (words) stageWidth - gutter * 2 - gap - min(column, (stageWidth - gutter * 2 - gap) * 0.56f) else (stageWidth - gutter * 2 - gap) * 0.48f
    val full = min(stageHeight - Space.step24 - Space.step8, room).coerceIn(StageCoverFloor, ceiling)
    val cover by animateDpAsState(
        targetValue = if (words) (full * StageCoverWithWords).coerceAtLeast(StageCoverFloor) else full,
        animationSpec = if (reduced) Motion.fade() else Motion.lean(),
        label = "stageCover",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = gutter, vertical = Space.step8),
    ) {
        StagePoster(
            track = track,
            back = back,
            playing = snapshot?.isPlaying == true,
            iconSize = 64.dp,
            modifier = Modifier.size(cover),
        )
        Spacer(Modifier.width(gap))
        AnimatedContent(
            targetState = controls.lyrics,
            contentKey = { it != null },
            transitionSpec = { stageSwap(reduced, toWords = targetState != null) },
            label = "stageColumn",
            // No weight: a weighted slot keeps its full share even when the column is narrower, so
            // the block would sit off-centre on a very wide window with all the slack on one side.
            modifier = Modifier.widthIn(max = column).fillMaxHeight(),
        ) { lyrics ->
            if (lyrics != null) {
                // The words take the column; the song sits above them and the transport below.
                Column(Modifier.fillMaxSize().padding(top = Space.step10, bottom = Space.step2)) {
                    StageWords(track = track, isPlaying = snapshot?.isPlaying == true, large = false, heart = controls.heart)
                    Spacer(Modifier.height(Space.step4))
                    lyrics(Modifier.weight(1f).fillMaxWidth(), false)
                    Spacer(Modifier.height(Space.step3))
                    LiveSeekBar(
                        position = position,
                        durationMs = snapshot?.durationMs ?: track.durationMs,
                        enabled = snapshot?.canSeek ?: true,
                        onSeek = controls.onSeek,
                    )
                    Spacer(Modifier.height(Space.step1))
                    StageTransport(snapshot, controls, playSize = 60.dp, gap = Space.step6)
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    Column(
                        modifier = Modifier.heightIn(min = min(cover, 420.dp)),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        StageWords(track = track, isPlaying = snapshot?.isPlaying == true, large = true, heart = controls.heart)
                        Spacer(Modifier.height(Space.step8))
                        LiveSeekBar(
                            position = position,
                            durationMs = snapshot?.durationMs ?: track.durationMs,
                            enabled = snapshot?.canSeek ?: true,
                            onSeek = controls.onSeek,
                        )
                        Spacer(Modifier.height(Space.step2))
                        StageTransport(snapshot, controls, playSize = 68.dp, gap = Space.step6)
                        Spacer(Modifier.height(Space.step3))
                        VolumeSlider(volume = volume, onVolume = controls.onVolume, railWidth = null, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(Space.step8))
                        controls.sleep()
                        UpNext(next = next, onOpenQueue = onOpenQueue)
                    }
                }
            }
        }
    }
}

/** A phone, or a window taller than it is wide: the cover on top, the song under it. */
@Composable
private fun TallStage(
    track: Track,
    snapshot: PlayerSnapshot?,
    position: State<Long>,
    volume: Float,
    next: Track?,
    controls: StageControls,
    onOpenQueue: () -> Unit,
    stageWidth: Dp,
    stageHeight: Dp,
    showVolume: Boolean,
    topInset: Dp,
    back: Boolean,
) {
    val gutter = LocalChrome.current.gutter
    val reduced = LocalReducedMotion.current
    val column = min(stageWidth - gutter * 2, 520.dp)
    val cover = min(column, stageHeight * 0.44f).coerceAtLeast(160.dp)
    AnimatedContent(
        targetState = controls.lyrics,
        contentKey = { it != null },
        transitionSpec = { stageSwap(reduced, toWords = targetState != null) },
        label = "tallStage",
        modifier = Modifier.fillMaxSize(),
    ) { lyrics ->
        if (lyrics != null) {
            // The words stand where the cover was; the cover comes down to the song's line.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier
                        .width(column)
                        .fillMaxHeight()
                        .padding(top = topInset + Space.step2, bottom = Space.step6),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        ArtworkSquare(url = track.artworkUrl, size = 56.dp, contentDescription = track.title)
                        Spacer(Modifier.width(Space.step3))
                        Column(Modifier.weight(1f)) {
                            KineticText(text = track.title, style = MaterialTheme.typography.titleLarge, color = ValenColors.Text, maxLines = 1)
                            Text(
                                text = track.artist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ValenColors.Text2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        controls.heart?.invoke()
                    }
                    Spacer(Modifier.height(Space.step3))
                    lyrics(Modifier.weight(1f).fillMaxWidth(), true)
                    Spacer(Modifier.height(Space.step2))
                    LiveSeekBar(
                        position = position,
                        durationMs = snapshot?.durationMs ?: track.durationMs,
                        enabled = snapshot?.canSeek ?: true,
                        onSeek = controls.onSeek,
                    )
                    Spacer(Modifier.height(Space.step1))
                    StageTransport(snapshot, controls, playSize = 64.dp, gap = Space.step4)
                }
            }
        } else {
            Box(
                contentAlignment = Alignment.TopCenter,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .width(column)
                        .heightIn(min = stageHeight)
                        .padding(top = topInset, bottom = Space.step6),
                ) {
                    StagePoster(
                        track = track,
                        back = back,
                        playing = snapshot?.isPlaying == true,
                        iconSize = 56.dp,
                        modifier = Modifier
                            .size(cover)
                            .align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.height(Space.step8))
                    StageWords(track = track, isPlaying = snapshot?.isPlaying == true, large = false, heart = controls.heart)
                    Spacer(Modifier.height(Space.step5))
                    LiveSeekBar(
                        position = position,
                        durationMs = snapshot?.durationMs ?: track.durationMs,
                        enabled = snapshot?.canSeek ?: true,
                        onSeek = controls.onSeek,
                    )
                    Spacer(Modifier.height(Space.step2))
                    StageTransport(snapshot, controls, playSize = 72.dp, gap = Space.step4)
                    if (showVolume) {
                        Spacer(Modifier.height(Space.step3))
                        VolumeSlider(volume = volume, onVolume = controls.onVolume, railWidth = null, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(Space.step6))
                    controls.sleep()
                    UpNext(next = next, onOpenQueue = onOpenQueue)
                }
            }
        }
    }
}

/**
 * The stage's cover: pasted to the wall and taped, leaning toward the pointer, sitting back while
 * paused. A new song's cover is slapped on over the last one; going back lifts it off again.
 */
@Composable
private fun StagePoster(track: Track, back: Boolean, playing: Boolean, iconSize: Dp, modifier: Modifier = Modifier) {
    val coverSource = rememberInteraction()
    val spot = app.valen.ui.components.LocalRepaintSpot.current
    Posters(
        item = track,
        key = { it.key },
        // A song with the same cover (an album played through) keeps the poster up and only
        // re-tapes it: an identical sheet is never torn down.
        same = { a, b -> a.artworkUrl != null && a.artworkUrl == b.artworkUrl },
        back = back,
        modifier = modifier
            .then(if (spot != null) Modifier.repaintFrom(spot) else Modifier)
            .hoverable(coverSource)
            .breathe(playing)
            .tilt(),
    ) { shown, entry, onReady ->
        Artwork(
            url = shown.artworkUrl,
            shape = Radii.artworkShape,
            contentDescription = shown.title,
            // Only the song that plays tints the room; the poster under it is on its way out.
            measure = shown.key == track.key,
            animate = entry == PosterEntry.First,
            onReady = onReady,
            iconSize = iconSize,
            modifier = Modifier
                .fillMaxSize()
                .pasted(seed = shown.key.hashCode(), source = coverSource)
                .taped(shown.key, already = entry == PosterEntry.Returned),
        )
    }
}

/** The three bare glyphs with shuffle and repeat at their ends. */
@Composable
private fun StageTransport(snapshot: PlayerSnapshot?, controls: StageControls, playSize: Dp, gap: Dp) {
    TransportBar(
        isPlaying = snapshot?.isPlaying == true,
        isBuffering = snapshot?.isBuffering == true,
        canSkipPrevious = snapshot?.canSkip != false,
        canSkipNext = snapshot?.canSkip != false,
        onPrevious = controls.onPrevious,
        onToggle = controls.onToggle,
        onNext = controls.onNext,
        modes = controls.modes,
        playSize = playSize,
        skipSize = 44.dp,
        gap = gap,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The stage changing between the song and its words. The two layouts put the same things (the
 * title, the rail, the transport) in different places, so they never show at once: what leaves
 * sinks and goes first, and what arrives rises in once it has mostly gone. Nothing is clipped
 * while the column changes width.
 */
private fun AnimatedContentTransitionScope<*>.stageSwap(reduced: Boolean, toWords: Boolean) = if (reduced) {
    (fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())) using SizeTransform(clip = false)
} else {
    val rise = if (toWords) 1 else -1
    val arrive = tween<Float>(Motion.EnterMs, delayMillis = Motion.ExitMs * 3 / 4, easing = Motion.Enter)
    val travel = tween<androidx.compose.ui.unit.IntOffset>(Motion.EnterMs, delayMillis = Motion.ExitMs * 3 / 4, easing = Motion.Enter)
    (
        (fadeIn(arrive) + slideInVertically(travel) { it / 14 * rise }) togetherWith
            (fadeOut(Motion.exit()) + slideOutVertically(Motion.exit()) { -it / 22 * rise })
        ) using SizeTransform(clip = false)
}

/** The song, set left like a headline, cross-fading when the song changes. */
@Composable
private fun StageWords(track: Track, isPlaying: Boolean, large: Boolean, heart: (@Composable () -> Unit)? = null) {
    val reduced = LocalReducedMotion.current
    // The heart stays put while the words change, so its fill is the song's, not the transition's.
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
    AnimatedContent(
        modifier = Modifier.weight(1f),
        targetState = track,
        contentKey = { it.key },
        transitionSpec = {
            if (reduced) {
                fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
            } else {
                fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit())
            }
        },
        label = "stageWords",
    ) { shown ->
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = ValenColors.Text2)) {
                        append(if (isPlaying) "Playing from " else "Paused on ")
                    }
                    append(shown.provider.label)
                },
                style = MaterialTheme.typography.titleSmall,
                color = ValenColors.Text,
                maxLines = 1,
            )
            Spacer(Modifier.height(Space.step2))
            KineticText(
                text = shown.title,
                style = if (large) TrackType.title else TrackType.headline,
                color = ValenColors.Text,
                maxLines = 2,
            )
            Spacer(Modifier.height(Space.step1))
            // Each artist named is the way to their page; the album after them is only words.
            val browser = LocalBrowser.current
            val names = remember(shown.artist) { artistsIn(shown.artist).ifEmpty { listOf(shown.artist) } }
            val link = TextLinkStyles(
                style = SpanStyle(color = ValenColors.Text2),
                hoveredStyle = SpanStyle(color = ValenColors.Text, textDecoration = TextDecoration.Underline),
                pressedStyle = SpanStyle(color = ValenColors.Text),
            )
            Text(
                text = buildAnnotatedString {
                    names.forEachIndexed { i, name ->
                        if (i > 0) append(", ")
                        withLink(LinkAnnotation.Clickable(tag = name, styles = link) { browser.open(BrowsePage.Artist(name)) }) { append(name) }
                    }
                    shown.album?.takeIf { it.isNotBlank() && it != shown.title }?.let { append("  ·  $it") }
                },
                style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                color = ValenColors.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    heart?.invoke()
    }
}

/**
 * The way to the queue. When a song is waiting it is named, with its cover; when none is, the
 * button just says where it leads.
 */
@Composable
private fun UpNext(next: Track?, onOpenQueue: () -> Unit) {
    if (next == null) {
        QuietButton(label = "Queue", icon = Icons.AutoMirrored.Rounded.QueueMusic, onClick = onOpenQueue)
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .tap(onClick = onOpenQueue, shape = Radii.rowShape, rest = ValenColors.Fill)
            .padding(start = Space.step2, end = Space.step3),
    ) {
        ArtworkSquare(url = next.artworkUrl, size = 44.dp)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Space.step3),
        ) {
            Text(
                text = "Up next",
                style = MaterialTheme.typography.bodySmall,
                color = ValenColors.Text2,
                maxLines = 1,
            )
            Text(
                text = "${next.title}  ·  ${next.artist}",
                style = MaterialTheme.typography.titleSmall,
                color = ValenColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = ValenColors.Text2,
            modifier = Modifier.size(24.dp),
        )
    }
}

/** The seek bar, reading the moving position itself: a tick composes it again, not the stage around it. */
@Composable
private fun LiveSeekBar(position: State<Long>, durationMs: Long, enabled: Boolean, onSeek: (Long) -> Unit) =
    SeekBar(positionMs = position.value, durationMs = durationMs, enabled = enabled, onSeek = onSeek)
