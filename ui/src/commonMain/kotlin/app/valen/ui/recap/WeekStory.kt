/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.recap

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.valen.core.PlaybackCoordinator
import app.valen.core.PlaylistManager
import app.valen.core.Track
import app.valen.core.WeekRecap
import app.valen.core.localEpochDay
import app.valen.core.week
import app.valen.ui.LocalAppScope
import app.valen.ui.SystemBack
import app.valen.ui.artwork.ArtworkPalette
import app.valen.ui.components.AmbientWash
import app.valen.ui.components.IconTap
import app.valen.ui.components.PageArrival
import app.valen.ui.components.PlayingBars
import app.valen.ui.components.drawArrival
import app.valen.ui.components.wallGrain
import app.valen.ui.theme.Layout
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Radii
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Your week", over everything while it is told. It is poured onto the screen like Home's page,
 * and leaves with a short fade.
 */
@Composable
fun WeekStoryHost(coordinator: PlaybackCoordinator, playlists: PlaylistManager) {
    val recap = LocalRecap.current
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = recap.shown,
        enter = fadeIn(tween(1)),
        exit = if (reduced) fadeOut(Motion.fade()) else fadeOut(Motion.exit()) + scaleOut(Motion.exit(), targetScale = 1.03f),
    ) {
        val pour by transition.animateFloat(
            transitionSpec = { if (reduced) Motion.fade() else Motion.pour() },
            label = "storyPour",
        ) { if (it == EnterExitState.PreEnter && !reduced) 0f else 1f }
        val paint = ValenColors.Primary
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent { drawArrival(PageArrival.Pour, pour, seed = 986, paint = paint, origin = Offset.Zero) },
        ) {
            WeekStory(coordinator, playlists, startAt = recap.startAt, turns = recap.turns, onClose = recap::close)
        }
    }
}

/** The pages of the story, in their order. */
internal enum class StoryKind { Opening, Time, Artist, Artists, Songs, New, Hours, Song, Outro }

/** A page: what it tells, the song that plays under it, and how long it stays (null: until left). */
internal class StoryPage(val kind: StoryKind, val song: Track?, val ms: Int?)

/**
 * The week as pages. A page with nothing to say is left out (one artist makes no list; a week
 * that is all there is has no "new to you"). Each page plays a song it is about, and no song is
 * used twice while another is left: the song of the week is kept for its own page at the end.
 */
internal fun storyOf(week: WeekRecap): List<StoryPage> {
    val used = HashSet<String>()
    fun take(options: List<Track>): Track? {
        val pick = options.firstOrNull { it.key !in used } ?: options.firstOrNull() ?: return null
        used += pick.key
        return pick
    }
    val top = week.songs.map { it.track }
    val best = take(top.take(1))
    val artist = week.artists.firstOrNull()
    val artistSong = take(artist?.songs?.map { it.track }.orEmpty())
    val songsSong = take(top.drop(1))
    val newSong = week.newSongs?.takeIf { it.isNotEmpty() }?.let { list -> take(list.map { it.track }) }
    val artistsSong = take(week.artists.drop(1).mapNotNull { it.songs.firstOrNull()?.track })
    val hoursSong = week.hours?.let { take(top) }
    val openSong = take(top.drop(2) + top)
    return buildList {
        add(StoryPage(StoryKind.Opening, openSong, Motion.StoryShortMs))
        add(StoryPage(StoryKind.Time, openSong, Motion.StoryPageMs))
        if (artist != null) add(StoryPage(StoryKind.Artist, artistSong, Motion.StoryPageMs))
        if (week.artists.size >= 3) add(StoryPage(StoryKind.Artists, artistsSong ?: artistSong, Motion.StoryLongMs))
        if (week.songs.size >= 3) add(StoryPage(StoryKind.Songs, songsSong, Motion.StoryLongMs))
        if (newSong != null) add(StoryPage(StoryKind.New, newSong, Motion.StoryPageMs))
        if (week.hours != null) add(StoryPage(StoryKind.Hours, hoursSong, Motion.StoryPageMs))
        add(StoryPage(StoryKind.Song, best, Motion.StoryLongMs))
        add(StoryPage(StoryKind.Outro, best, null))
    }
}

/**
 * Where a page's song starts: about a third of the way in, where a song has usually got going (the
 * first chorus, more often than not), and never so late that the page outlasts it.
 */
internal fun startFor(track: Track): Long {
    val d = track.durationMs
    return when {
        d >= 90_000 -> (d * 3 / 10).coerceIn(30_000, d - 45_000)
        d > 0 -> 0L
        else -> 40_000L
    }
}

/**
 * The story: pages that turn by themselves, a tap on the right for the next and on the left for
 * the one before, a hold to stop, and Escape, the arrows and Space on a keyboard. The player is
 * lent to it while it is told and given back as it was ([PlaybackCoordinator.lend]): nothing it
 * plays counts as listening, and what was playing plays on afterwards.
 */
@Composable
private fun WeekStory(coordinator: PlaybackCoordinator, playlists: PlaylistManager, startAt: Int, turns: Int, onClose: () -> Unit) {
    val scope = LocalAppScope.current
    val week = remember { coordinator.stats.log.value.week(localEpochDay()) }
    if (week == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val pages = remember(week) { storyOf(week) }
    var at by remember { mutableIntStateOf(startAt.coerceIn(0, pages.lastIndex)) }
    var forward by remember { mutableStateOf(true) }
    var paused by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var sounding by remember { mutableStateOf<String?>(null) }

    fun go(to: Int) {
        if (to !in pages.indices || to == at) return
        forward = to > at
        at = to
        paused = false
    }

    // A turn asked for from outside (the screenshot run): the next page, as a tap on the right.
    val firstTurns = remember { turns }
    LaunchedEffect(turns) { if (turns != firstTurns) go(at + 1) }

    // Lent while it is told; given back as it was when it closes, whichever way it closes.
    DisposableEffect(coordinator) {
        coordinator.lend()
        onDispose { scope.launch { coordinator.giveBack() } }
    }

    // Each page's song, a moment after the page lands; the same song as the page before goes on.
    // The first waits for the story's pour to cover the app, which shows the song as it starts.
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(at) {
        val song = pages[at].song ?: return@LaunchedEffect
        pages.getOrNull(at + 1)?.song?.let { if (it.key != song.key) coordinator.warm(it) }
        val playing = coordinator.nowPlaying.value?.isPlaying == true
        if (song.key == sounding) {
            if (!playing) scope.launch { coordinator.toggle() }
            return@LaunchedEffect
        }
        delay(if (first) Motion.PourMs.toLong() else Motion.StorySongDelayMs)
        first = false
        sounding = song.key
        scope.launch { coordinator.playLent(song, startFor(song)) }
    }

    // The page's time: a page held, or the story paused, stops it where it is.
    val progress = remember(at) { Animatable(if (pages[at].ms == null) 1f else 0f) }
    val length = pages[at].ms
    LaunchedEffect(at, paused, holding) {
        if (length == null || paused || holding) return@LaunchedEffect
        val left = ((1f - progress.value) * length).toInt()
        progress.animateTo(1f, tween(left.coerceAtLeast(1), easing = LinearEasing))
        if (at < pages.lastIndex) go(at + 1)
    }
    // A hold hides the chrome once it is clearly a hold, not a tap on the way to one.
    var bare by remember { mutableStateOf(false) }
    LaunchedEffect(holding) {
        if (!holding) {
            bare = false
            return@LaunchedEffect
        }
        delay(Motion.StoryHoldMs)
        bare = true
    }

    fun togglePause() {
        paused = !paused
        val playing = coordinator.nowPlaying.value?.isPlaying == true
        if (sounding != null && paused == playing) scope.launch { coordinator.toggle() }
    }

    val playAll: () -> Unit = {
        val songs = week.songs.map { it.track }
        val first = songs.firstOrNull()
        if (first != null) {
            coordinator.keepLent()
            scope.launch { coordinator.play(first, songs) }
        }
        onClose()
    }

    SystemBack(enabled = true, onBack = onClose)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ValenColors.Background)
            .wallGrain()
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> go(at + 1)
                    Key.DirectionLeft -> go(at - 1)
                    Key.Spacebar -> togglePause()
                    Key.Escape -> onClose()
                    else -> return@onKeyEvent false
                }
                true
            },
    ) {
        val wide = maxWidth >= Layout.railFrom
        val chrome = StoryChrome(
            pages = pages.size,
            at = at,
            progress = { progress.value },
            paused = paused,
            bare = bare,
            week = week,
            onPause = ::togglePause,
            onClose = onClose,
        )
        if (wide) {
            // A poster on the wall, as tall as the window allows, with a way back and on beside it.
            val height = maxHeight - Space.step6 * 2
            val width = minOf(height * Layout.storyAspect, maxWidth - Space.step24 * 2)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                IconTap(
                    icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                    label = "Previous page",
                    onClick = { go(at - 1) },
                    enabled = at > 0,
                    tint = ValenColors.Text,
                    iconSize = 32.dp,
                    size = 56.dp,
                )
                Spacer(Modifier.width(Space.step6))
                Poster(
                    pages = pages,
                    at = at,
                    forward = forward,
                    week = week,
                    playlists = playlists,
                    coordinator = coordinator,
                    chrome = chrome,
                    holding = holding,
                    onHold = { holding = it },
                    onStep = { if (it) go(at + 1) else go(at - 1) },
                    onPlayAll = playAll,
                    onClose = onClose,
                    width = width,
                    modifier = Modifier
                        .size(width, width / Layout.storyAspect)
                        .clip(Radii.artworkShape),
                )
                Spacer(Modifier.width(Space.step6))
                IconTap(
                    icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    label = "Next page",
                    onClick = { go(at + 1) },
                    enabled = at < pages.lastIndex,
                    tint = ValenColors.Text,
                    iconSize = 32.dp,
                    size = 56.dp,
                )
            }
        } else {
            Poster(
                pages = pages,
                at = at,
                forward = forward,
                week = week,
                playlists = playlists,
                coordinator = coordinator,
                chrome = chrome,
                holding = holding,
                onHold = { holding = it },
                onStep = { if (it) go(at + 1) else go(at - 1) },
                onPlayAll = playAll,
                onClose = onClose,
                width = maxWidth,
                insets = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** What the story's chrome shows and does. */
private class StoryChrome(
    val pages: Int,
    val at: Int,
    val progress: () -> Float,
    val paused: Boolean,
    val bare: Boolean,
    val week: WeekRecap,
    val onPause: () -> Unit,
    val onClose: () -> Unit,
)

/**
 * The poster: designed at [Layout.storyDesignWidth] and scaled as a whole to [width], so a page
 * is laid out once and reads the same on a phone and a wide window. A page that comes next is
 * slapped on over the one before, which sinks and darkens under it; going back peels the top page
 * away.
 */
@Composable
private fun Poster(
    pages: List<StoryPage>,
    at: Int,
    forward: Boolean,
    week: WeekRecap,
    playlists: PlaylistManager,
    coordinator: PlaybackCoordinator,
    chrome: StoryChrome,
    holding: Boolean,
    onHold: (Boolean) -> Unit,
    onStep: (Boolean) -> Unit,
    onPlayAll: () -> Unit,
    onClose: () -> Unit,
    width: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    insets: Boolean = false,
) {
    val reduced = LocalReducedMotion.current
    val base = LocalDensity.current
    val scale = width / Layout.storyDesignWidth
    val held by animateFloatAsState(if (holding && !reduced) Motion.StoryHoldScale else 1f, Motion.squish(), label = "held")
    val step by rememberUpdatedState(onStep)
    val hold by rememberUpdatedState(onHold)
    Box(
        modifier = modifier
            .background(ValenColors.Background)
            .graphicsLayer {
                scaleX = held
                scaleY = held
            },
    ) {
        CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
            Box(Modifier.fillMaxSize().then(if (insets) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier)) {
                // Under everything that can be pressed: a tap on the right turns the page, on the
                // left goes back; a hold stops the page where it is.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    hold(true)
                                    tryAwaitRelease()
                                    hold(false)
                                },
                                onLongPress = {},
                                onTap = { at -> step(at.x > size.width / 3f) },
                            )
                        },
                )
                AnimatedContent(
                    targetState = at,
                    transitionSpec = {
                        when {
                            reduced -> fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
                            forward -> fadeIn(tween(1)) togetherWith
                                (scaleOut(Motion.pour(), targetScale = Motion.PouredOverScale) + fadeOut(Motion.pour(), targetAlpha = Motion.PouredOverAlpha))
                            // Back: the top page lifts away and the one before is there underneath.
                            else -> (fadeIn(tween(1)) togetherWith (fadeOut(Motion.exit()) + scaleOut(Motion.exit(), targetScale = Motion.LiftScale)))
                                .apply { targetContentZIndex = -1f }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "storyPage",
                ) { index ->
                    val slap by transition.animateFloat(
                        transitionSpec = { tween(Motion.SlapMs, easing = LinearEasing) },
                        label = "slap",
                    ) { if (it == EnterExitState.PreEnter) 0f else 1f }
                    // Paper, not a fade: the new page is solid almost at once, big and turned
                    // enough to cover the one under it, and slapped flat with a small bounce.
                    val turn = if (index % 2 == 0) -3.2f else 2.8f
                    val slapping = forward && !reduced && index > 0
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                if (!slapping) return@graphicsLayer
                                val p = Motion.Kinetic.transform(slap.coerceIn(0f, 1f))
                                val s = Motion.SlapScale - (Motion.SlapScale - 1f) * p
                                scaleX = s
                                scaleY = s
                                rotationZ = turn * (1f - p)
                                alpha = (slap * 9f).coerceIn(0f, 1f)
                                transformOrigin = TransformOrigin(0.5f, 0.38f)
                            },
                    ) {
                        StoryGround(pages[index].song)
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(top = 96.dp, bottom = 76.dp, start = 24.dp, end = 24.dp),
                        ) {
                            StoryPageContent(
                                page = pages[index],
                                week = week,
                                coordinator = coordinator,
                                sounding = pages[index].song,
                                onPlayAll = onPlayAll,
                                onDone = onClose,
                            )
                        }
                    }
                }
                Top(chrome, Modifier.align(Alignment.TopCenter))
                NowSounding(pages[at].song, playing = !chrome.paused, hidden = chrome.bare, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/**
 * A page's ground: the wall, with the light of its song's cover across it (the one wash on the
 * screen), drifting slowly while it is shown.
 */
@Composable
private fun StoryGround(song: Track?) {
    val reduced = LocalReducedMotion.current
    Box(Modifier.fillMaxSize().background(ValenColors.Background)) {
        AmbientWash(
            tones = ArtworkPalette.tones(song?.artworkUrl),
            drift = !reduced,
            fade = 0.55f,
            modifier = Modifier.fillMaxSize(),
        )
        Spacer(Modifier.fillMaxSize().wallGrain())
    }
}

/**
 * The top of the poster: a segment for each page (those read full, the one shown filling), the
 * story's name and its days, and pause and close.
 */
@Composable
private fun Top(chrome: StoryChrome, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val shown by animateFloatAsState(if (chrome.bare) 0f else 1f, if (reduced) Motion.fade() else Motion.standard(), label = "chrome")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = shown }
            .padding(top = 12.dp, start = 14.dp, end = 6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(end = 8.dp)) {
            for (i in 0 until chrome.pages) {
                Spacer(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .drawBehind {
                            val r = CornerRadius(size.height / 2f, size.height / 2f)
                            drawRoundRect(ValenColors.Fill.copy(alpha = 0.28f), cornerRadius = r)
                            val p = when {
                                i < chrome.at -> 1f
                                i > chrome.at -> 0f
                                else -> chrome.progress()
                            }
                            if (p > 0f) drawRoundRect(ValenColors.Text, size = Size(size.width * p, size.height), cornerRadius = r)
                        },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            // The first page says all this big; from the second on it stays here, small.
            val named by animateFloatAsState(if (chrome.at == 0) 0f else 1f, if (reduced) Motion.fade() else Motion.standard(), label = "named")
            Column(Modifier.weight(1f).padding(start = 2.dp).graphicsLayer { alpha = named }) {
                Text("Your week", style = MaterialTheme.typography.titleSmall, color = ValenColors.Text, maxLines = 1)
                Text(daysOf(chrome.week), style = MaterialTheme.typography.bodySmall, color = ValenColors.Text2, maxLines = 1)
            }
            IconTap(
                icon = if (chrome.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                label = if (chrome.paused) "Go on with your week" else "Pause your week",
                onClick = chrome.onPause,
                tint = ValenColors.Text,
            )
            IconTap(icon = Icons.Rounded.Close, label = "Close your week", onClick = chrome.onClose, tint = ValenColors.Text)
        }
    }
}

/** What plays under the page, at the foot of the poster: the bars and the song's name. */
@Composable
private fun NowSounding(song: Track?, playing: Boolean, hidden: Boolean, modifier: Modifier = Modifier) {
    val reduced = LocalReducedMotion.current
    val shown by animateFloatAsState(if (hidden || song == null) 0f else 1f, if (reduced) Motion.fade() else Motion.standard(), label = "sounding")
    Box(modifier = modifier.padding(bottom = 18.dp).graphicsLayer { alpha = shown }) {
        AnimatedContent(
            targetState = song,
            contentKey = { it?.key },
            transitionSpec = { fadeIn(Motion.enter()) togetherWith fadeOut(Motion.exit()) },
            label = "soundingSong",
        ) { shownSong ->
            if (shownSong == null) {
                Spacer(Modifier.height(36.dp))
                return@AnimatedContent
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .widthIn(max = 320.dp)
                    .clip(Radii.pillShape)
                    .background(ValenColors.Background.copy(alpha = 0.42f))
                    .padding(horizontal = 14.dp),
            ) {
                PlayingBars(playing = playing, tint = ValenColors.Primary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(Space.step2))
                Text(
                    text = shownSong.title + "  ·  " + shownSong.artist,
                    style = MaterialTheme.typography.labelMedium,
                    color = ValenColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
