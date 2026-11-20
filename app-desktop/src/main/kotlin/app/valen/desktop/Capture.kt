/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import app.valen.ui.menu.MenuOrigin
import app.valen.ui.menu.MenuPage
import app.valen.ui.menu.SongMenuState
import app.valen.ui.components.PageMemory
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.Density
import app.valen.core.PlaybackCoordinator
import app.valen.core.PlaylistManager
import app.valen.ui.Route
import app.valen.ui.ValenApp
import app.valen.ui.recap.RecapState
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `--screenshot` pass: `ValenApp` drawn at exactly the requested size, in one offscreen Skia
 * scene, and written as PNG. It is what the docs screenshots are made of.
 *
 * Not a screen capture on purpose: on Wayland a compositor does not hand a window's pixels to an
 * X11 client, so `java.awt.Robot` returns a black frame; the scene draws the same composition the
 * window draws, at the same pixel size, with no window manager in between.
 */
object Capture {

    /**
     * VALEN_CAPTURE_UPDATE=uptodate|checking|available|downloading|ready|installed|failed|told|off:
     * Settings → About shows that state of an update to an invented 9.9.9-beta.1, with nothing asked
     * of GitHub. Unset: no updater at all, so a screenshot never looks.
     */
    fun updates(): app.valen.core.Updates? {
        val which = System.getenv("VALEN_CAPTURE_UPDATE")?.lowercase() ?: return null
        val release = app.valen.core.AppRelease(
            version = "9.9.9-beta.1",
            code = app.valen.core.ValenVersion.parse("9.9.9-beta.1")!!.code,
            test = true,
            asset = app.valen.core.ReleaseAsset("linux-appimage", "Valen-9.9.9-beta.1-x86_64.AppImage", 121_000_000, "0".repeat(64)),
            page = "https://example.invalid/release",
            url = "https://example.invalid/file",
            // Invented notes in GitHub's generated shape, so the sheet shows a real-looking list.
            notes = """
                ## What's Changed
                * Lyrics keep their place after a seek by @someone in https://github.com/dodo986/valen/pull/41
                * The equaliser's boost is balanced, and its limiter can be turned off by @someone in https://github.com/dodo986/valen/pull/42
                * Downloads resume after a dropped connection by @someone in https://github.com/dodo986/valen/pull/43

                **Full Changelog**: https://github.com/dodo986/valen/compare/v9.9.8...v9.9.9-beta.1
            """.trimIndent(),
        )
        val now = System.currentTimeMillis()
        val state: app.valen.core.UpdateState = when (which) {
            "checking" -> app.valen.core.UpdateState.Checking
            "available", "told", "pour" -> app.valen.core.UpdateState.Available(release)
            "downloading" -> app.valen.core.UpdateState.Downloading(release, 46_000_000, release.asset.size)
            "ready" -> app.valen.core.UpdateState.Ready(release)
            "installed" -> app.valen.core.UpdateState.Installed(release)
            "failed" -> app.valen.core.UpdateState.Failed("Could not reach GitHub", release)
            "off" -> app.valen.core.UpdateState.Off
            else -> app.valen.core.UpdateState.UpToDate(now - 25 * 60_000)
        }
        val route = when (which) {
            "told" -> app.valen.core.UpdateRoute.Tell
            "ready" -> app.valen.core.UpdateRoute.System
            else -> app.valen.core.UpdateRoute.Itself
        }
        return object : app.valen.core.Updates {
            override val current = ValenBuild.VERSION
            override val route = route
            override val elsewhere = if (route == app.valen.core.UpdateRoute.Tell) "This copy comes from Nix, so it is updated with the flake." else null
            override val state = kotlinx.coroutines.flow.MutableStateFlow(state).also { flow ->
                // "pour": a download that runs while the sheet is open, then the release is ready.
                if (which == "pour") {
                    Thread {
                        Thread.sleep(2_600)
                        val total = release.asset.size
                        for (i in 0..24) {
                            flow.value = app.valen.core.UpdateState.Downloading(release, total * i / 24, total)
                            Thread.sleep(110)
                        }
                        flow.value = app.valen.core.UpdateState.Ready(release)
                    }.apply { isDaemon = true }.start()
                }
            }
            override fun check() {}
            override fun update() {}
            override fun restart() {}
            override fun openPage(release: app.valen.core.AppRelease) {}
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    fun render(
        coordinator: PlaybackCoordinator,
        playlists: PlaylistManager,
        route: Route,
        width: Int,
        height: Int,
        path: Path,
        query: String? = null,
    ): Boolean =
        runCatching {
            path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            // Development only: VALEN_CAPTURE_GO switches to another route halfway through the
            // settle (so a page change can be seen), or to each of a comma-separated list in turn
            // 900 ms of animation time apart (a second visit's arrival, or coming back); the old
            // page's exit is seen too. VALEN_CAPTURE_GO_MS sets that gap in animation time, for a
            // shorter or longer one (a slow frame clock needs a longer settle). VALEN_CAPTURE_FRAMES
            // saves every frame drawn.
            val shownRoute = mutableStateOf(route.name)
            val goes = System.getenv("VALEN_CAPTURE_GO")?.split(',')?.mapNotNull { name ->
                name.trim().takeIf(String::isNotEmpty)?.let { Route.from(it).name }
            }.orEmpty()
            val goGapNanos = (System.getenv("VALEN_CAPTURE_GO_MS")?.toLongOrNull() ?: 900L) * 1_000_000L
            var goIndex = 0
            var nextGoAtNanos = 0L
            // VALEN_CAPTURE_MENU=main|playlist|sleep opens the playing song's menu on that page,
            // grown from about where the stage's ⋯ sits, at the same halfway point.
            val menuPage = when (System.getenv("VALEN_CAPTURE_MENU")) {
                "main" -> MenuPage.Main
                "playlist" -> MenuPage.AddToPlaylist
                "sleep" -> MenuPage.Sleep
                else -> null
            }
            val songMenu = SongMenuState()
            var menuOpened = false
            // VALEN_CAPTURE_PLAYLIST=<id> opens that playlist on the Playlists page; "new" puts a
            // blank sleeve on the shelf to be named.
            val pageMemory = PageMemory()
            when (val wanted = System.getenv("VALEN_CAPTURE_PLAYLIST")) {
                null -> Unit
                "new" -> pageMemory.value("playlists.creating", false).value = true
                else -> pageMemory.value<String?>("playlists.open", null).value = wanted
            }
            // VALEN_CAPTURE_SETTINGS=<part> opens that part of Settings (services, playback, week…).
            System.getenv("VALEN_CAPTURE_SETTINGS")?.let { pageMemory.value<String?>("settings:part", null).value = it }
            // VALEN_CAPTURE_SCROLL=<page key>:<px> starts that page scrolled (below-the-fold sections).
            System.getenv("VALEN_CAPTURE_SCROLL")?.split(':')?.takeIf { it.size == 2 }?.let { (key, px) ->
                px.toIntOrNull()?.let { pageMemory.startScrolled(key, it) }
            }
            // The sound notebook: VALEN_CAPTURE_TAB opens a tab of its settings page (0–4),
            // VALEN_CAPTURE_TURN turns to another halfway, VALEN_CAPTURE_SOUND picks a preset halfway.
            val soundTab = pageMemory.value("sound.tab", 0)
            System.getenv("VALEN_CAPTURE_TAB")?.toIntOrNull()?.let { soundTab.value = it }
            val turnTo = System.getenv("VALEN_CAPTURE_TURN")?.toIntOrNull()
            val presetTo = System.getenv("VALEN_CAPTURE_SOUND")?.let { app.valen.core.SoundPresets.byId(it) }
            var soundDone = false
            // VALEN_CAPTURE_SEEK=<ms> moves the song there once it plays (to see lyrics mid-song);
            // VALEN_CAPTURE_WORDS=1 turns the stage's lyrics on halfway (to see the swap).
            val seekTo = System.getenv("VALEN_CAPTURE_SEEK")?.toLongOrNull()
            var sought = seekTo == null
            val wordsHalfway = System.getenv("VALEN_CAPTURE_WORDS") != null
            // VALEN_CAPTURE_RECAP=<page> opens "Your week" on that page (0 is the first) after the
            // first few frames, so it pours in; VALEN_CAPTURE_RECAP_TURN=1 turns to the next page halfway.
            val recap = RecapState()
            val recapAt = System.getenv("VALEN_CAPTURE_RECAP")?.toIntOrNull()
            var recapOpened = recapAt == null
            var recapTurned = System.getenv("VALEN_CAPTURE_RECAP_TURN") == null
            // VALEN_CAPTURE_SKIP=next|prev[,next|prev…] moves the queue on (or back) halfway, then
            // once more every 1.5 s, to see a song change: the poster slapped on (or lifted off),
            // the rail's paint running, the dock's gulp.
            val skips = System.getenv("VALEN_CAPTURE_SKIP")?.lowercase()?.split(',')?.map { it.trim() }.orEmpty().toMutableList()
            var nextSkipAt = 0L
            // VALEN_CAPTURE_ENQUEUE=1 adds a copy of the playing song to the queue halfway: the poured
            // notice with its drawn tick, and the dock's queue glyph swallowing it.
            var enqueued = System.getenv("VALEN_CAPTURE_ENQUEUE") == null
            // VALEN_CAPTURE_PAUSE=1 pauses the song halfway (the lights going down).
            var paused = System.getenv("VALEN_CAPTURE_PAUSE") == null
            // VALEN_CAPTURE_POINTER=X,Y rests a mouse pointer there from a third of the way in
            // (hover states: a rail's ghost and its times); VALEN_CAPTURE_DRAG=X,Y,X2,Y2 presses
            // at X,Y halfway, drags to X2,Y2 over a second, rests and lets go at 1.6 s.
            val pointer = System.getenv("VALEN_CAPTURE_POINTER")?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }
                ?.takeIf { it.size == 2 }?.let { Offset(it[0], it[1]) }
            var pointed = pointer == null
            val drag = System.getenv("VALEN_CAPTURE_DRAG")?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }
                ?.takeIf { it.size == 4 }
            var dragFrom = -1L
            // VALEN_CAPTURE_CLICK=X,Y clicks there halfway (a search row's +: the drop's flight).
            val click = System.getenv("VALEN_CAPTURE_CLICK")?.split(',')?.mapNotNull { it.trim().toFloatOrNull() }
                ?.takeIf { it.size == 2 }?.let { Offset(it[0], it[1]) }
            var clicked = click == null
            var clickedAt = -1L
            var unclicked = click == null
            // VALEN_CAPTURE_KEYS=help[,stage,…] presses those shortcuts (`Shortcut` names) halfway,
            // 1.2 s apart: the keys page slapped up, a place opened from the keyboard.
            val keys = app.valen.ui.KeyShortcuts()
            val keyPresses = System.getenv("VALEN_CAPTURE_KEYS")?.split(',')?.mapNotNull { name ->
                app.valen.ui.Shortcut.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
            }.orEmpty().toMutableList()
            var nextKeyAt = 0L
            val frames = System.getenv("VALEN_CAPTURE_FRAMES")?.let { Path.of(it) }?.also { Files.createDirectories(it) }
            var frame = 0
            val step = System.getenv("VALEN_CAPTURE_STEP_MS")?.toLongOrNull() ?: STEP_MS
            val scene = ImageComposeScene(
                width = width,
                height = height,
                density = Density(1f),
            ) {
                ValenApp(coordinator, playlists, shownRoute.value, query, songMenu = songMenu, pageMemory = pageMemory, recap = recap, keys = keys)
            }
            try {
                // One frame composes the tree, then the scene is drawn repeatedly while the real
                // clock moves: searches, artwork and their colours arrive after the first frame.
                var nanos = 0L
                scene.render(nanos).close()
                // VALEN_CAPTURE_SETTLE_MS: a longer settle, to see what still moves once the start of a
                // song's motion is over (the glass orbit rests after two laps).
                val settle = System.getenv("VALEN_CAPTURE_SETTLE_MS")?.toLongOrNull() ?: SETTLE_MS
                val settleUntil = System.nanoTime() + settle * 1_000_000L
                val halfway = System.nanoTime() + settle * 500_000L
                while (System.nanoTime() < settleUntil) {
                    Thread.sleep(step)
                    nanos += step * 1_000_000L
                    if (goIndex < goes.size && System.nanoTime() > halfway && (goIndex == 0 || nanos >= nextGoAtNanos)) {
                        shownRoute.value = goes[goIndex++]
                        nextGoAtNanos = nanos + goGapNanos
                    }
                    if (!recapOpened && nanos >= 240_000_000L) {
                        recapOpened = true
                        recap.open(recapAt!!)
                    }
                    if (!recapTurned && System.nanoTime() > halfway) {
                        recapTurned = true
                        recap.turn()
                    }
                    if (!sought && coordinator.snapshot.value?.isPlaying == true) {
                        sought = true
                        kotlinx.coroutines.runBlocking { coordinator.seek(seekTo!!) }
                    }
                    if (!soundDone && System.nanoTime() > halfway) {
                        soundDone = true
                        if (wordsHalfway) coordinator.setLyricsShown(true)
                        turnTo?.let { soundTab.value = it }
                        presetTo?.let { coordinator.usePreset(it) }
                    }
                    if (skips.isNotEmpty() && System.nanoTime() > maxOf(halfway, nextSkipAt)) {
                        val skip = skips.removeAt(0)
                        nextSkipAt = System.nanoTime() + 1_500_000_000L
                        kotlinx.coroutines.runBlocking { if (skip == "prev") coordinator.previous() else coordinator.next() }
                    }
                    if (keyPresses.isNotEmpty() && System.nanoTime() > maxOf(halfway, nextKeyAt)) {
                        nextKeyAt = System.nanoTime() + 1_200_000_000L
                        keys.run(keyPresses.removeAt(0))
                    }
                    if (!paused && System.nanoTime() > halfway && coordinator.snapshot.value?.isPlaying == true) {
                        paused = true
                        kotlinx.coroutines.runBlocking { coordinator.toggle() }
                    }
                    if (!enqueued && System.nanoTime() > halfway) {
                        coordinator.snapshot.value?.track?.let { song ->
                            // A song the queue does not hold yet (it refuses one it has): the
                            // playing one under a key of its own, never played in the capture.
                            coordinator.enqueue(song.copy(id = song.id + "#capture"))
                            enqueued = true
                        }
                    }
                    if (!pointed && System.nanoTime() > settleUntil - settle * 666_000L) {
                        pointed = true
                        scene.sendPointerEvent(PointerEventType.Enter, pointer!!, type = PointerType.Mouse)
                        scene.sendPointerEvent(PointerEventType.Move, pointer, type = PointerType.Mouse)
                    }
                    // Press, then let go a few frames later: `clickable` wants to see the down and
                    // the up as separate frames, as a real mouse gives them (both in one frame never
                    // reached it, and a click on a row did nothing).
                    if (!clicked && System.nanoTime() > halfway) {
                        clicked = true
                        clickedAt = nanos
                        scene.sendPointerEvent(PointerEventType.Enter, click!!, type = PointerType.Mouse)
                        scene.sendPointerEvent(PointerEventType.Move, click, type = PointerType.Mouse)
                        scene.sendPointerEvent(PointerEventType.Press, click, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true))
                    } else if (clicked && !unclicked && nanos - clickedAt >= 90_000_000L) {
                        unclicked = true
                        scene.sendPointerEvent(PointerEventType.Release, click!!, type = PointerType.Mouse)
                    }
                    if (drag != null && System.nanoTime() > halfway) {
                        val from = Offset(drag[0], drag[1])
                        val to = Offset(drag[2], drag[3])
                        if (dragFrom < 0L) {
                            dragFrom = nanos
                            scene.sendPointerEvent(PointerEventType.Move, from, type = PointerType.Mouse)
                            scene.sendPointerEvent(PointerEventType.Press, from, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true))
                        } else if (nanos - dragFrom <= DRAG_HOLD_NANOS) {
                            val t = ((nanos - dragFrom) / 1_000_000_000f).coerceIn(0f, 1f)
                            val at = Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
                            scene.sendPointerEvent(PointerEventType.Move, at, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true))
                        } else if (dragFrom > 0L) {
                            // Held a moment at the end, then let go (a held row is slapped back,
                            // a pulled mini player skips).
                            scene.sendPointerEvent(PointerEventType.Release, to, type = PointerType.Mouse)
                            dragFrom = 0L
                        }
                    }
                    if (menuPage != null && !menuOpened && System.nanoTime() > halfway) {
                        coordinator.snapshot.value?.track?.let { song ->
                            if (menuPage == MenuPage.Sleep) coordinator.sleepIn(30)
                            songMenu.open(song, MenuOrigin.Player, Rect(Offset(width - 76f, 8f), Size(44f, 44f)), alignEnd = true, page = menuPage)
                            menuOpened = true
                        }
                    }
                    // Each frame is a fresh native image: closed at once, or the settle holds
                    // gigabytes of them until the collector happens to finalise them.
                    scene.render(nanos).use { drawn ->
                        if (frames != null) {
                            drawn.encodeToData(EncodedImageFormat.PNG)?.let {
                                Files.write(frames.resolve("frame-%03d.png".format(frame++)), it.bytes)
                            }
                        }
                    }
                }
                if (System.getenv("VALEN_CAPTURE_IDLE") != null) {
                    // Development: after the settle nothing should still be asking for frames
                    // unless something on screen is really animating.
                    val busy = (1..20).count {
                        nanos += step * 1_000_000L
                        scene.render(nanos).close()
                        scene.hasInvalidations()
                    }
                    println("frames still invalidated after settle: $busy of 20")
                }
                val image = scene.render(nanos + 1_000_000_000L)
                val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("the image could not be encoded")
                Files.write(path.toAbsolutePath(), data.bytes)
                println("saved ${path.toAbsolutePath()} (${image.width}x${image.height})")
                true
            } finally {
                // A failure while drawing must not be hidden by one while tearing down.
                runCatching { scene.close() }.onFailure { System.err.println("valen: tearing the window down failed: ${it.message}") }
            }
        }.getOrElse {
            System.err.println("valen: could not render the window: ${it.message}")
            if (System.getenv("VALEN_CAPTURE_TRACE") != null) it.printStackTrace()
            false
        }

    /** How long the scene keeps drawing so real network answers can reach a frame. */
    private const val SETTLE_MS = 5_000L
    private const val STEP_MS = 80L

    /** A capture's drag moves for a second, rests, and lets go at this much animation time. */
    private const val DRAG_HOLD_NANOS = 1_600_000_000L
}
