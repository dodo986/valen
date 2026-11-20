/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleOut
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.LocalFocusManager
import app.valen.core.RepeatMode
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.valen.core.PlaybackCoordinator
import kotlinx.coroutines.flow.map
import app.valen.core.PlaylistManager
import app.valen.ui.artwork.ArtworkPalette
import app.valen.ui.artwork.LocalPlayingTones
import app.valen.ui.components.rememberPosition
import app.valen.ui.util.systemReducedMotion
import app.valen.ui.components.Backdrop
import app.valen.ui.components.LocalBackdrop
import app.valen.ui.components.LocalPageEntrance
import app.valen.ui.components.backdropSource
import app.valen.ui.components.arrivalFor
import app.valen.ui.components.drawArrival
import app.valen.ui.components.arriving
import app.valen.ui.components.Reach
import app.valen.ui.components.noteReach
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Offset
import app.valen.ui.components.rememberPageEntrance
import app.valen.ui.components.wallGrain
import app.valen.ui.components.BottomNav
import app.valen.ui.components.FullScreenSheet
import app.valen.ui.components.MiniPlayer
import app.valen.ui.components.NoticeToast
import app.valen.ui.components.PlayerBar
import app.valen.ui.components.PlayModes
import app.valen.ui.components.PageMemory
import app.valen.ui.menu.LocalSongMenu
import app.valen.ui.artist.Browser
import app.valen.ui.artist.BrowsePage
import app.valen.ui.artist.BrowseScreen
import app.valen.ui.artist.LocalBrowser
import app.valen.ui.menu.SongMenuHost
import app.valen.ui.sound.SoundScreen
import app.valen.ui.menu.SongMenuState
import app.valen.ui.components.LocalPageMemory
import app.valen.ui.components.Sidebar
import app.valen.ui.screens.HomeScreen
import app.valen.ui.screens.NowPlayingScreen
import app.valen.ui.screens.PlaylistImportScreen
import app.valen.ui.screens.PlaylistsScreen
import app.valen.ui.screens.QueueScreen
import app.valen.ui.screens.SearchScreen
import app.valen.ui.screens.SearchState
import app.valen.ui.screens.SettingsScreen
import app.valen.ui.profile.ProfileScreen
import app.valen.ui.welcome.WelcomeFlow
import app.valen.ui.recap.LocalRecap
import app.valen.ui.recap.RecapState
import app.valen.ui.recap.WeekStoryHost
import app.valen.ui.smart.MixScreen
import app.valen.ui.screens.HomeFeed
import app.valen.ui.screens.AboutScreen
import app.valen.ui.screens.LocalHomeFeed
import app.valen.ui.theme.Chrome
import app.valen.ui.theme.Layout
import app.valen.ui.theme.LocalChrome
import app.valen.ui.theme.LocalReducedMotion
import app.valen.ui.theme.Motion
import app.valen.ui.theme.Space
import app.valen.ui.theme.ValenColors
import app.valen.ui.theme.ValenTheme
import app.valen.ui.theme.Tonal
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.SideEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The scope every screen sends its playback commands on. It is remembered once, at the root, which
 * never leaves the composition, so a command started from a screen that is then navigated away
 * from still completes. A scope remembered inside a screen would be cancelled with it, and one
 * used after it left throws inside the composition and freezes the window.
 */
val LocalAppScope = staticCompositionLocalOf<CoroutineScope> { error("ValenApp provides the app scope") }

/**
 * Where the app is. A place ([base]) is one of the screens the navigation lists; the stage and the
 * queue open over a place and close back to it. The same state serves every width: on a phone the
 * things in [over] are sheets, on a wide window the top one is simply the screen shown.
 *
 * It lives above the width switch, so resizing a window across a breakpoint keeps you where you were.
 */
@Stable
private class Nav(start: Route) {
    var base by mutableStateOf(if (start in Places) start else Route.Home)
        private set
    var over by mutableStateOf(if (start in Places) emptyList() else listOf(start))
        private set

    val top: Route get() = over.lastOrNull() ?: base

    fun open(route: Route) {
        if (route in Places) {
            base = route
            over = emptyList()
        } else if (top != route) {
            over = (over - route) + route
        }
    }

    fun close(route: Route) {
        over = over - route
    }

    companion object {
        val Places = setOf(Route.Home, Route.Search, Route.Playlists, Route.Settings, Route.Profile, Route.About)
    }
}

/**
 * The app. Responsive by width, not by platform: a floating side panel and player dock on wide
 * windows, an icon rail in the middle, and a floating tab bar with two sheets on a phone.
 */
@Composable
fun ValenApp(
    coordinator: PlaybackCoordinator,
    /** The playlists Valen keeps; the one library both platforms hand in. */
    playlists: PlaylistManager,
    startRoute: String? = null,
    /** Text the search field starts with; the development `--query` flag. */
    startQuery: String? = null,
    /** The song menu's state; the screenshot run passes its own to open the menu. */
    songMenu: SongMenuState = remember { SongMenuState() },
    /** Where each page was left; the screenshot run passes its own to open a playlist. */
    pageMemory: PageMemory = remember { PageMemory() },
    /** "Your week"; the screenshot run passes its own to open it. */
    recap: RecapState = remember { RecapState() },
    /** The window's keyboard, where there is one to speak of: the desktop hands its window's keys in. */
    keys: KeyShortcuts? = null,
) {
    ValenTheme(reducedMotion = systemReducedMotion()) {
        val scope = rememberCoroutineScope()
        val nav = remember(startRoute) { Nav(Route.from(startRoute)) }
        val search = remember(startQuery) { SearchState(startQuery.orEmpty()) }
        val playing by coordinator.nowPlaying.collectAsState()
        val settings by coordinator.settings.collectAsState()
        val followCover = settings.coverColour
        // Set before anything reads a tone this frame, so the first frame is already right.
        ArtworkPalette.followCover = followCover
        val tones = app.valen.ui.artwork.rememberSettledTones(playing?.track?.artworkUrl)
        // The whole app is one colour: pink on black by default, or the playing cover's. It eases
        // to a new one rather than jumping.
        val seed by animateColorAsState(tones.accent, Motion.tint(), label = "seed")
        val scheme = remember(seed, followCover) { Tonal.scheme(seed, black = !followCover) }
        SideEffect { ValenColors.scheme = scheme }
        val homeFeed = remember { HomeFeed() }
        // The catalogue's pages (artists, releases, playlists of a service), opened from anywhere.
        val browser = remember(nav) {
            Browser().apply {
                onShow = { nav.open(Route.Artist) }
                onHide = { nav.close(Route.Artist) }
            }
        }
        // Opened on an artist (the development `--route artist --query NAME`): their page.
        LaunchedEffect(Unit) {
            if (Route.from(startRoute) == Route.Artist && !startQuery.isNullOrBlank()) browser.open(BrowsePage.Artist(startQuery.trim()))
        }
        // Another place opened over them takes the pages away: their stack goes with them.
        LaunchedEffect(nav.over) { if (Route.Artist !in nav.over) browser.forget() }
        // The keys page (`?`), and what every key does.
        val keysShown = remember { mutableStateOf(false) }
        // Before the app scope is provided below: it is handed in rather than read.
        if (keys != null) KeyCommands(keys, coordinator, playlists, nav, search, browser, keysShown, scope)
        val focusManager = LocalFocusManager.current
        // Where every page was left (above the width switch, so a resize keeps it too): [pageMemory].
        CompositionLocalProvider(
            LocalAppScope provides scope,
            LocalPlayingTones provides tones,
            LocalHomeFeed provides homeFeed,
            LocalPageMemory provides pageMemory,
            LocalSongMenu provides songMenu,
            LocalBrowser provides browser,
            LocalRecap provides recap,
            app.valen.ui.update.LocalUpdateSheet provides remember { app.valen.ui.update.UpdateSheetState() },
            app.valen.ui.components.LocalWarm provides remember(coordinator) { { t: app.valen.core.Track -> coordinator.warm(t) } },
            app.valen.ui.components.LocalFlights provides remember { app.valen.ui.components.Flights() },
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ValenColors.Background)
                    .blurOnOutsideClick(focusManager)
                    .noteReach(),
            ) {
                when {
                    maxWidth >= Layout.wideFrom -> WideApp(coordinator, playlists, nav, search, rail = false)
                    maxWidth >= Layout.railFrom -> WideApp(coordinator, playlists, nav, search, rail = true)
                    else -> PhoneApp(coordinator, playlists, nav, search)
                }
                KeysPage(visible = keysShown.value, onDismiss = { keysShown.value = false })
                // "Your week", over everything (chrome too) while it is told.
                WeekStoryHost(coordinator = coordinator, playlists = playlists)
                // The welcome, once, over everything until it is done; it fades up and away.
                AnimatedVisibility(
                    visible = !settings.onboarded,
                    enter = fadeIn(Motion.fade()),
                    exit = fadeOut(Motion.pour()) + scaleOut(Motion.pour(), targetScale = 1.04f),
                ) {
                    WelcomeFlow(coordinator)
                }
            }
        }
    }
}

/**
 * What each key does here ([KeyShortcuts], [Shortcut]): the transport and the volume on the
 * keyboard, and every place a key away. A shortcut that changes something you might not be looking
 * at (shuffle, repeat, mute, a like) says so in a notice; the ones you can see happen (seeking, the
 * volume, a place opening) need no word.
 */
@Composable
private fun KeyCommands(
    keys: KeyShortcuts,
    coordinator: PlaybackCoordinator,
    playlists: PlaylistManager,
    nav: Nav,
    search: SearchState,
    browser: Browser,
    keysShown: MutableState<Boolean>,
    scope: CoroutineScope,
) {
    val focus = LocalFocusManager.current
    val steps = remember(coordinator, scope) { KeySteps(coordinator, scope) }
    DisposableEffect(keys, coordinator, nav, search, browser, steps, focus) {
        keys.perform = perform@{ shortcut ->
            val settings = coordinator.settings.value
            // The welcome has the keyboard to itself: it asks for a name.
            if (!settings.onboarded) return@perform false
            val track = coordinator.nowPlaying.value?.track
            when (shortcut) {
                Shortcut.Back -> when {
                    // Esc in a field lets go of it, so the next key plays rather than types.
                    Typing.active -> focus.clearFocus()
                    keysShown.value -> keysShown.value = false
                    else -> when (val top = nav.over.lastOrNull()) {
                        null -> return@perform false
                        // The catalogue's pages go back one page at a time before they close.
                        Route.Artist -> browser.back()
                        else -> nav.close(top)
                    }
                }
                Shortcut.Help -> keysShown.value = !keysShown.value
                Shortcut.PlayPause -> scope.launch { coordinator.toggle() }
                Shortcut.Next -> scope.launch { coordinator.next() }
                Shortcut.Previous -> scope.launch { coordinator.previous() }
                Shortcut.SeekForward -> return@perform steps.seekBy(KeySteps.SeekStepMs)
                Shortcut.SeekBack -> return@perform steps.seekBy(-KeySteps.SeekStepMs)
                Shortcut.VolumeUp -> steps.volumeBy(KeySteps.VolumeStep)
                Shortcut.VolumeDown -> steps.volumeBy(-KeySteps.VolumeStep)
                Shortcut.Mute -> coordinator.confirm(if (steps.mute()) "Muted" else "Unmuted")
                Shortcut.Like -> {
                    val song = track ?: return@perform false
                    scope.launch {
                        val liked = playlists.library.toggleLiked(song)
                        coordinator.confirm(if (liked) "Added to Liked songs" else "Removed from Liked songs")
                    }
                }
                Shortcut.Shuffle -> {
                    val on = !settings.shuffle
                    coordinator.setShuffle(on)
                    coordinator.confirm(if (on) "Shuffle on" else "Shuffle off")
                }
                Shortcut.Repeat -> {
                    coordinator.cycleRepeat()
                    coordinator.confirm(
                        when (coordinator.settings.value.repeat) {
                            RepeatMode.OFF -> "Repeat off"
                            RepeatMode.ALL -> "Repeat all"
                            RepeatMode.ONE -> "Repeat one"
                        },
                    )
                }
                Shortcut.Search -> {
                    nav.open(Route.Search)
                    search.focusAsk++
                }
                Shortcut.Stage -> when {
                    nav.top == Route.NowPlaying -> nav.close(Route.NowPlaying)
                    track != null -> nav.open(Route.NowPlaying)
                    else -> return@perform false
                }
                Shortcut.Queue -> if (nav.top == Route.Queue) nav.close(Route.Queue) else nav.open(Route.Queue)
            }
            true
        }
        onDispose { keys.perform = null }
    }
}

@Composable
private fun WideApp(
    coordinator: PlaybackCoordinator,
    playlists: PlaylistManager,
    nav: Nav,
    search: SearchState,
    rail: Boolean,
) {
    val scope = LocalAppScope.current
    val snapshot by coordinator.nowPlaying.collectAsState()
    val position = rememberPosition(coordinator)
    val settings by coordinator.settings.collectAsState()
    val notice by coordinator.notice.collectAsState()
    val confirmation by coordinator.noticeIsConfirmation.collectAsState()
    // Read only by the dock's queue glyph, so a queue change recomposes that glyph alone.
    val queueSize = remember(coordinator) { coordinator.queue.map { it.size } }.collectAsState(coordinator.queue.value.size)
    val track = snapshot?.track
    val route = nav.top

    // The stage carries its own transport; a dock under it would only repeat it.
    val docked = track != null && route != Route.NowPlaying
    val gutter = if (rail) Layout.railGutter else Layout.desktopGutter
    val chrome = Chrome(
        start = Layout.chromeInset + (if (rail) Layout.railWidth else Layout.sidebarWidth),
        bottom = if (docked) Layout.dockHeight + Space.step4 else 0.dp,
        gutter = gutter,
        compact = false,
    )

    val backdrop = remember { Backdrop() }
    Box(modifier = Modifier.fillMaxSize()) {
        Wall(backdrop) {
        CompositionLocalProvider(LocalChrome provides chrome) {
            Pages(route) { shown ->
                Screen(
                    route = shown,
                    coordinator = coordinator,
                    playlists = playlists,
                    search = search,
                    onRoute = nav::open,
                    // The stage and the import close back to where you were. The queue has no close
                    // here: the side panel is right beside it.
                    onClose = if (shown == Route.NowPlaying || shown == Route.Import || shown == Route.Mix || shown == Route.Sound) {
                        ({ nav.close(shown) })
                    } else {
                        null
                    },
                    onBack = null,
                )
            }
        }

        Lowlight(visible = docked, height = Layout.dockHeight + Space.step24)
        }

        CompositionLocalProvider(LocalBackdrop provides backdrop) {
        Sidebar(
            current = route,
            onNavigate = nav::open,
            profileName = settings.profileName,
            avatar = settings.avatar,
            privateMode = settings.privateMode,
            onPrivateMode = coordinator::setPrivateMode,
            condensed = rail,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = Layout.chromeInset, top = Layout.chromeInset, bottom = Layout.chromeInset),
        )

        Rising(
            visible = docked,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = chrome.start + gutter, end = gutter, bottom = Space.step4),
        ) {
            val shown = rememberLast(track) ?: return@Rising
            PlayerBar(
                track = shown,
                isPlaying = snapshot?.isPlaying == true,
                isBuffering = snapshot?.isBuffering == true,
                canSkipPrevious = snapshot?.canSkip != false,
                canSkipNext = snapshot?.canSkip != false,
                positionMs = position.value,
                durationMs = snapshot?.durationMs ?: shown.durationMs,
                volume = settings.volume,
                tones = ArtworkPalette.tones(shown.artworkUrl),
                onOpen = { nav.open(Route.NowPlaying) },
                onPrevious = { scope.launch { coordinator.previous() } },
                onToggle = { scope.launch { coordinator.toggle() } },
                onNext = { scope.launch { coordinator.next() } },
                onSeek = { position -> scope.launch { coordinator.seek(position) } },
                onVolume = { value -> scope.launch { coordinator.setVolume(value) } },
                onQueue = { nav.open(Route.Queue) },
                queueSize = { queueSize.value },
                modes = PlayModes(
                    shuffle = settings.shuffle,
                    repeat = settings.repeat,
                    onShuffle = { coordinator.setShuffle(!settings.shuffle) },
                    onRepeat = { coordinator.cycleRepeat() },
                ),
            )
        }

        SongMenuHost(
            coordinator = coordinator,
            playlists = playlists,
            onSound = { nav.open(Route.Sound) },
            compact = false,
            start = chrome.start,
            bottom = chrome.bottom,
            modifier = Modifier.fillMaxSize(),
        )

        // A new release: by itself once it is ready, or from Settings → About.
        app.valen.ui.update.UpdateSheetHost(
            coordinator = coordinator,
            compact = false,
            start = chrome.start,
            bottom = chrome.bottom,
            modifier = Modifier.fillMaxSize(),
        )

        Notice(
            message = notice,
            confirmation = confirmation,
            onDismiss = { coordinator.clearNotice() },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(start = chrome.start + gutter, end = gutter, top = Space.step4),
        )
        // Songs added to the queue, flying into the dock.
        app.valen.ui.components.FlightHost(app.valen.ui.components.LocalFlights.current)
        }
    }
}

@Composable
private fun PhoneApp(
    coordinator: PlaybackCoordinator,
    playlists: PlaylistManager,
    nav: Nav,
    search: SearchState,
) {
    val scope = LocalAppScope.current
    val snapshot by coordinator.nowPlaying.collectAsState()
    val position = rememberPosition(coordinator)
    val notice by coordinator.notice.collectAsState()
    val confirmation by coordinator.noticeIsConfirmation.collectAsState()
    val track = snapshot?.track

    val bars = Layout.bottomNavHeight + Layout.chromeInset +
        (if (track != null) Layout.miniPlayerHeight + Space.step2 else 0.dp)
    val page = Chrome(start = 0.dp, bottom = bars, gutter = Layout.phoneGutter, compact = true)
    val backdrop = remember { Backdrop() }
    val sheet = Chrome(start = 0.dp, bottom = 0.dp, gutter = Layout.phoneGutter, compact = true)

    // The catalogue's pages (an artist, a release, a service's playlist) are a page of the wall on
    // a phone, like a place: the mini player and the tab bar float over them as over Home, so the
    // song stays in reach while you browse. A sheet opened before them (the stage an artist was
    // opened from) steps aside while they show, and comes back when Back closes them; one opened
    // after them (the stage, from the mini player) covers them.
    val browsingAt = nav.over.indexOf(Route.Artist)
    val wallRoute = if (browsingAt >= 0) Route.Artist else nav.base
    fun sheetShown(route: Route): Boolean = nav.over.indexOf(route) > browsingAt

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Wall(backdrop) {
        CompositionLocalProvider(LocalChrome provides page) {
            Pages(wallRoute) { shown ->
                Screen(
                    route = shown,
                    coordinator = coordinator,
                    playlists = playlists,
                    search = search,
                    onRoute = nav::open,
                    onClose = null,
                    onBack = { nav.open(Route.Home) },
                )
            }
        }

        Lowlight(visible = true, height = bars + Space.step16)
        }

        CompositionLocalProvider(LocalBackdrop provides backdrop) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.step2),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = Layout.chromeInset)
                .padding(bottom = Layout.chromeInset),
        ) {
            Rising(visible = track != null) {
                val shown = rememberLast(track) ?: return@Rising
                MiniPlayer(
                    track = shown,
                    isPlaying = snapshot?.isPlaying == true,
                    canSkipNext = snapshot?.canSkip != false,
                    positionMs = position.value,
                    durationMs = snapshot?.durationMs ?: shown.durationMs,
                    tones = ArtworkPalette.tones(shown.artworkUrl),
                    onOpen = { nav.open(Route.NowPlaying) },
                    onToggle = { scope.launch { coordinator.toggle() } },
                    onNext = { scope.launch { coordinator.next() } },
                    onPrevious = if (snapshot?.canSkip != false) ({ scope.launch { coordinator.previous() } }) else null,
                )
            }
            BottomNav(current = nav.top, onNavigate = nav::open)
        }

        Notice(
            message = notice,
            confirmation = confirmation,
            onDismiss = { coordinator.clearNotice() },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = Layout.chromeInset, vertical = Space.step2),
        )
        // Songs added to the queue, flying into the mini player.
        app.valen.ui.components.FlightHost(app.valen.ui.components.LocalFlights.current)
        }

        CompositionLocalProvider(LocalChrome provides sheet) {
            FullScreenSheet(
                visible = sheetShown(Route.NowPlaying),
                onDismiss = { nav.close(Route.NowPlaying) },
            ) {
                NowPlayingScreen(
                    coordinator = coordinator,
                    onOpenQueue = { nav.open(Route.Queue) },
                    onBrowse = { nav.open(Route.Search) },
                    onClose = { nav.close(Route.NowPlaying) },
                    playlists = playlists,
                    onSound = { nav.open(Route.Sound) },
                )
            }
            FullScreenSheet(
                visible = sheetShown(Route.Queue),
                onDismiss = { nav.close(Route.Queue) },
            ) {
                QueueScreen(
                    coordinator = coordinator,
                    onBrowse = { nav.open(Route.Search) },
                    onClose = { nav.close(Route.Queue) },
                )
            }
            FullScreenSheet(
                visible = sheetShown(Route.Mix),
                onDismiss = { nav.close(Route.Mix) },
            ) {
                MixScreen(
                    coordinator = coordinator,
                    manager = playlists,
                    onClose = { nav.close(Route.Mix) },
                    onSaved = { nav.close(Route.Mix); nav.open(Route.Playlists) },
                )
            }
            FullScreenSheet(
                visible = sheetShown(Route.Sound),
                onDismiss = { nav.close(Route.Sound) },
            ) {
                SoundScreen(coordinator = coordinator, playlists = playlists, onClose = { nav.close(Route.Sound) })
            }
            FullScreenSheet(
                visible = sheetShown(Route.Import),
                onDismiss = { nav.close(Route.Import) },
            ) {
                PlaylistImportScreen(
                    coordinator = coordinator,
                    manager = playlists,
                    onClose = { nav.close(Route.Import) },
                )
            }
        }

        // Back closes the sheet on top, or returns a place to Home, before it leaves the app.
        val pages = LocalBrowser.current
        SystemBack(enabled = nav.over.isNotEmpty()) {
            // The catalogue's pages go back one page at a time before they close.
            when (val top = nav.over.lastOrNull()) {
                Route.Artist -> pages.back()
                null -> Unit
                else -> nav.close(top)
            }
        }
        SystemBack(enabled = nav.over.isEmpty() && nav.base != Route.Home) { nav.open(Route.Home) }

        // The song menu over everything, sheets included.
        SongMenuHost(coordinator = coordinator, playlists = playlists, onSound = { nav.open(Route.Sound) }, compact = true, modifier = Modifier.fillMaxSize())
        app.valen.ui.update.UpdateSheetHost(coordinator = coordinator, compact = true, modifier = Modifier.fillMaxSize())
    }
}

/**
 * One screen replacing another in the same place. Each place arrives its own way ([arrivalFor]),
 * a little differently each visit: poured, sprayed, burst, rolled, taped up, unrolled, printed,
 * a shutter rolled up, slapped on, a page turned over, a tube switched on. The old page runs its own
 * arrival backwards and sinks under it; then the new page's rows and cards rise in one after
 * another ([arrive]). Under reduced motion it is a short cross-fade.
 */
@Composable
private fun Pages(route: Route, content: @Composable (Route) -> Unit) {
    val reduced = LocalReducedMotion.current
    var pours by remember { mutableIntStateOf(0) }
    AnimatedContent(
        targetState = route,
        transitionSpec = {
            if (reduced) {
                fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade())
            } else {
                // The new page shows at once and is revealed by the pour below; the old one waits
                // underneath until the paint has covered it.
                fadeIn(tween(1)) togetherWith
                    (scaleOut(Motion.pour(), targetScale = Motion.PouredOverScale) +
                        fadeOut(Motion.pour(), targetAlpha = Motion.PouredOverAlpha))
            }
        },
        modifier = Modifier.fillMaxSize(),
        label = "pages",
    ) { shown ->
        val first = remember { pours == 0 }
        val seed = remember { ++pours }
        // A page that was still arriving when another took its place rewinds the same arrival as it
        // leaves (the same tool, the same curve, the same 620 ms), so two half-arrived pages never
        // sit painted over one another; one that had settled stays painted under the new paint.
        val arrived = remember { mutableStateOf(false) }
        val pour by transition.animateFloat(
            transitionSpec = { if (reduced) Motion.fade() else Motion.pour() },
            label = "pour",
        ) { state ->
            when {
                reduced || first -> 1f
                state == EnterExitState.PreEnter -> 0f
                state == EnterExitState.PostExit && !arrived.value -> 0f
                else -> 1f
            }
        }
        LaunchedEffect(pour) { if (pour >= 1f) arrived.value = true }
        val entrance = rememberPageEntrance(shown)
        val paint = LocalPlayingTones.current.bright
        // The press that opened this page, if one did (a click or a tap, not a key): in the window.
        val reached = remember { Reach.recent() }
        var at by remember { mutableStateOf(Offset.Zero) }
        CompositionLocalProvider(LocalPageEntrance provides entrance) {
            val arrival = arrivalFor(shown)
            val chrome = LocalChrome.current
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { at = it.positionInRoot() }
                    .arriving(arrival, active = !first && !reduced, seed = seed) { pour }
                    .drawWithContent {
                        // The page's own spot is about where its title is, at the top of its column.
                        val origin = Offset(chrome.start.toPx() + chrome.gutter.toPx() + 60.dp.toPx(), 90.dp.toPx())
                        drawArrival(arrival, pour, seed, paint, origin, reach = reached?.let { it - at })
                    },
            ) {
                content(shown)
            }
        }
    }
}

/**
 * The wall the pages are painted on: black, with the grain of sprayed concrete over them, and
 * recorded for the glass floating above it.
 */
@Composable
private fun Wall(backdrop: Backdrop, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .backdropSource(backdrop)
            .background(ValenColors.Background)
            .wallGrain(),
        content = content,
    )
}

@Composable
private fun Screen(
    route: Route,
    coordinator: PlaybackCoordinator,
    playlists: PlaylistManager,
    search: SearchState,
    onRoute: (Route) -> Unit,
    onClose: (() -> Unit)?,
    onBack: (() -> Unit)?,
) {
    when (route) {
        Route.Home -> HomeScreen(coordinator = coordinator, playlists = playlists, onRoute = onRoute)
        Route.Playlists -> PlaylistsScreen(
            coordinator = coordinator,
            manager = playlists,
            onImport = { onRoute(Route.Import) },
            onMix = { onRoute(Route.Mix) },
        )
        Route.Import -> PlaylistImportScreen(
            coordinator = coordinator,
            manager = playlists,
            onClose = onClose ?: { onRoute(Route.Playlists) },
        )
        Route.Search -> SearchScreen(
            coordinator = coordinator,
            state = search,
            onOpenSettings = { onRoute(Route.Settings) },
        )
        Route.NowPlaying -> NowPlayingScreen(
            coordinator = coordinator,
            onOpenQueue = { onRoute(Route.Queue) },
            onBrowse = { onRoute(Route.Search) },
            onClose = onClose,
            playlists = playlists,
            onSound = { onRoute(Route.Sound) },
        )
        Route.Queue -> QueueScreen(
            coordinator = coordinator,
            onBrowse = { onRoute(Route.Search) },
            onClose = onClose,
        )
        Route.Settings -> SettingsScreen(
            coordinator = coordinator,
            onBack = onBack,
            playlists = playlists,
            onAbout = { onRoute(Route.About) },
            onCleared = { onRoute(Route.Home) },
        )
        Route.About -> AboutScreen(version = coordinator.version)
        Route.Artist -> BrowseScreen(coordinator = coordinator, playlists = playlists)
        Route.Sound -> SoundScreen(coordinator = coordinator, playlists = playlists, onClose = onClose ?: { onRoute(Route.NowPlaying) })
        Route.Profile -> ProfileScreen(coordinator = coordinator)
        Route.Mix -> MixScreen(
            coordinator = coordinator,
            manager = playlists,
            onClose = onClose ?: { onRoute(Route.Playlists) },
            onSaved = { onRoute(Route.Playlists) },
        )
    }
}

/**
 * The page dimming to black under floating chrome, so a list scrolling beneath the player fades
 * out instead of colliding with it.
 */
@Composable
private fun BoxScope.Lowlight(visible: Boolean, height: Dp) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(Motion.standard()),
        exit = fadeOut(Motion.exit()),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(height)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to ValenColors.Background.copy(alpha = 0.78f),
                        1f to ValenColors.Background.copy(alpha = 0.96f),
                    ),
                ),
        )
    }
}

/** Chrome that arrives from below when there is something to play, and leaves the same way. */
@Composable
private fun Rising(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = visible,
        enter = if (reduced) fadeIn(Motion.fade()) else slideInVertically(Motion.enter()) { it / 2 } + fadeIn(Motion.enter()),
        exit = if (reduced) fadeOut(Motion.fade()) else slideOutVertically(Motion.exit()) { it / 2 } + fadeOut(Motion.exit()),
        modifier = modifier,
    ) {
        content()
    }
}

@Composable
private fun Notice(message: String?, confirmation: Boolean, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    // Glass poured out of a drop, like the song menu; see PouredNotice.
    app.valen.ui.components.PouredNotice(message = message, confirmation = confirmation, onDismiss = onDismiss, modifier = modifier)
}

/** The last non-null [value]: what something keeps showing while it animates away. */
@Composable
private fun <T : Any> rememberLast(value: T?): T? {
    var last by remember { mutableStateOf(value) }
    if (value != null && value != last) last = value
    return value ?: last
}
