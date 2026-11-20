/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui

import androidx.compose.ui.test.ExperimentalTestApi
import app.valen.core.SleepTimer
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.geometry.Size
import app.valen.core.MusicProvider
import app.valen.core.ArtistCatalog
import app.valen.core.ArtistProfile
import app.valen.core.Release
import app.valen.core.ReleaseKind
import app.valen.core.ReleaseTracks
import app.valen.core.ServiceArtist
import app.valen.core.ServicePlaylist
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import app.valen.core.EffectSupport
import app.valen.core.PlaybackCoordinator
import app.valen.core.PlaylistLibrary
import app.valen.core.PlaylistManager
import app.valen.core.PlaylistStore
import app.valen.core.PlayerSnapshot
import app.valen.core.ProviderEvent
import app.valen.core.ProviderId
import app.valen.core.SessionState
import app.valen.core.SettingsStore
import app.valen.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The shell, driven the way a person drives it, against services that answer instantly and need
 * no network: every screen at every width, then a walk through search, play, queue, stage and
 * settings. What it guards is the class of failure a screenshot cannot show: a screen that throws
 * when it is navigated to or away from.
 */
@OptIn(ExperimentalTestApi::class)
class ShellSmokeTest {

    private class FakeProvider(override val id: ProviderId) : MusicProvider, ArtistCatalog {
        override val provider: ProviderId get() = id

        /** Every fake service has the same artist, "Night Drive", with its own songs and releases. */
        private val artist = ServiceArtist(id, "${id.name}-nd", "Night Drive")

        override suspend fun findArtists(query: String, limit: Int): List<ServiceArtist> =
            if (query.contains("night", ignoreCase = true)) listOf(artist) else emptyList()

        override suspend fun artist(id: String): ArtistProfile = ArtistProfile(
            artist = artist,
            about = "Night Drive make music for empty motorways at four in the morning. " +
                "Their records are synths, rain and a voice that sounds like a radio in another car.",
            monthlyListeners = if (this.id == ProviderId.SPOTIFY) 1_234_567L else null,
            followers = 98_000L,
            verified = this.id == ProviderId.SPOTIFY,
            topTracks = List(6) { n -> Track(this.id, "nd-${this.id.short}-$n", "Night song $n", "Night Drive", durationMs = 200_000L) },
            releases = listOf(
                Release(this.id, "alb-1", "Headlights", "Night Drive", ReleaseKind.ALBUM, 2024),
                Release(this.id, "alb-2", "Overpass", "Night Drive", ReleaseKind.ALBUM, 2021),
                Release(this.id, "sgl-1", "Exit 14", "Night Drive", ReleaseKind.SINGLE, 2025),
            ),
            playlists = listOf(ServicePlaylist(this.id, "pl-${this.id.short}", "This is Night Drive (${this.id.short})", owner = this.id.label)),
            related = listOf(ServiceArtist(this.id, "rel-1", "Neon Coast"), ServiceArtist(this.id, "rel-2", "Glass Harbour")),
        )

        override suspend fun release(id: String): ReleaseTracks = ReleaseTracks(
            Release(this.id, id, if (id == "alb-1") "Headlights" else id, "Night Drive"),
            List(5) { n -> Track(this.id, "$id-$n", "Track $n of $id", "Night Drive", durationMs = 210_000L) },
        )

        override val session = MutableStateFlow<SessionState>(SessionState.SignedOut)
        override val player = MutableStateFlow<PlayerSnapshot?>(null)
        override val events = MutableSharedFlow<ProviderEvent>()

        /** As the real services: Spotify keeps its own sound, the other two take effects. */
        override val effectSupport: EffectSupport =
            if (id == ProviderId.SPOTIFY) EffectSupport.None("Spotify keeps its own sound") else EffectSupport.Full

        override suspend fun connect() { session.value = SessionState.Connected("tester") }
        override suspend fun disconnect() { session.value = SessionState.SignedOut }
        override suspend fun search(query: String, limit: Int): List<Track> = List(4) { n ->
            Track(
                provider = id,
                id = "$query-$n",
                title = "${id.short} song $n",
                artist = "Artist $n",
                durationMs = 180_000L + n * 1_000L,
            )
        }

        override suspend fun play(track: Track, startMs: Long) {
            player.value = PlayerSnapshot(track = track, isPlaying = true, durationMs = track.durationMs)
        }

        override suspend fun pause() { player.value = player.value?.copy(isPlaying = false) }
        override suspend fun resume() { player.value = player.value?.copy(isPlaying = true) }
        override suspend fun seek(positionMs: Long) { player.value = player.value?.copy(positionMs = positionMs) }
        override suspend fun setVolume(volume: Float) {}
        override suspend fun close() {}
    }

    private fun coordinator(
        scope: CoroutineScope,
        lyrics: app.valen.core.LyricsBook = app.valen.core.LyricsBook.None,
        offline: app.valen.core.OfflineShelf? = null,
        presence: app.valen.core.PresenceLink? = null,
        updates: app.valen.core.Updates? = null,
    ) = PlaybackCoordinator(
        providers = ProviderId.services.map { FakeProvider(it) },
        scope = scope,
        // Past the welcome: these walk the app itself. The welcome has its own test.
        settingsStore = SettingsStore(read = { """{"onboarded":true}""" }, write = {}),
        lyrics = lyrics,
        offline = offline,
        presence = presence,
        version = "1.0.0-alpha.4",
        updates = updates,
    )

    /**
     * A playlist library with no service catalogues: pasted links are refused for the services
     * that cannot be read here, which is exactly the path a phone or a signed-out desktop takes.
     */
    private fun playlists(coordinator: PlaybackCoordinator): PlaylistManager {
        var clock = 1_000L
        return PlaylistManager(
            library = PlaylistLibrary(PlaylistStore(read = { null }, write = {}), clock = { clock++ }),
            search = { provider, query, _ ->
                listOf(Track(provider = provider, id = query, title = query, artist = "Artist"))
            },
            playable = { coordinator.provider(it.provider) != null },
        )
    }

    /** The app has animations that never end (the playing bars, the drifting wash), so time is stepped by hand. */
    private fun SkikoComposeUiTest.settle(ms: Long = 700L) {
        mainClock.advanceTimeBy(ms)
    }

    /**
     * Waits for what the coordinator publishes on its own 250 ms poll — real time, which the test's
     * virtual clock does not move. Asserting straight after [settle] read `null` on a loaded machine
     * even though the song had started. Steps the clock while it waits, so frames keep drawing.
     */
    private fun SkikoComposeUiTest.settleUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition() && System.nanoTime() < deadline) {
            mainClock.advanceTimeBy(100)
            Thread.sleep(20)
        }
    }

    /** A place in the navigation: written on a wide window (and on the chosen tab), spoken otherwise. */
    private fun SkikoComposeUiTest.goTo(label: String) {
        if (onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()) {
            onNodeWithText(label).performClick()
        } else {
            onNodeWithContentDescription(label).performClick()
        }
    }

    private fun inApp(
        width: Int,
        height: Int,
        lyrics: app.valen.core.LyricsBook = app.valen.core.LyricsBook.None,
        offline: app.valen.core.OfflineShelf? = null,
        presence: app.valen.core.PresenceLink? = null,
        updates: app.valen.core.Updates? = null,
        block: SkikoComposeUiTest.(PlaybackCoordinator, PlaylistManager) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat())) {
                mainClock.autoAdvance = false
                val coordinator = coordinator(scope, lyrics, offline, presence, updates)
                block(coordinator, playlists(coordinator))
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(240)
    fun `an artist's page puts the services together, and its album opens and goes back`() {
        val out = java.io.File("build/screens").also { it.mkdirs() }
        for ((width, height) in listOf(1600 to 1000, 390 to 844)) {
            inApp(width, height) { coordinator, playlists ->
                // A song plays while the pages are browsed: on a phone the mini player stays over them.
                val playing = runBlocking { coordinator.unifiedSearch("warm up").tracks.first().also { coordinator.play(it) } }
                setContent { ValenApp(coordinator, playlists, "artist", startQuery = "Night Drive") }
                settleUntil(5_000) { runCatching { onNodeWithText("Popular").assertExists() }.isSuccess }
                settle(1_500L)
                onAllNodesWithText("Night Drive").onFirst().assertExists()
                if (width < 700) {
                    // The mini player is over the pages, not under them: a real tap on it (a pointer
                    // at its place, which reaches whatever is on top) opens the stage over the artist,
                    // and closing the stage comes back to the artist.
                    onAllNodesWithText(playing.title, useUnmergedTree = true).onFirst().performClick()
                    settle(1_200L)
                    onNodeWithContentDescription("Close the player").performClick()
                    settle(1_200L)
                    onNodeWithText("Popular").assertExists()
                }
                runCatching {
                    javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", java.io.File(out, "artist-${width}x$height.png"))
                }
                // Three services, one artist: the songs of all three, the releases once each.
                onNodeWithText("Night song 0", substring = true, useUnmergedTree = true).assertExists()
                // A phone's page is long: the list is moved to its discography (hero, popular, then it).
                if (height < 900) {
                    onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToIndex(2)
                    settle(800L)
                    runCatching {
                        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", java.io.File(out, "artist-lower-${width}x$height.png"))
                    }
                }
                onNodeWithText("Discography").assertExists()
                // Below the fold: a semantics click, never a scroll (AGENTS §5).
                onNode(hasText("Headlights") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
                settleUntil(5_000) { runCatching { onNodeWithText("Track 0 of alb-1", useUnmergedTree = true).assertExists() }.isSuccess }
                settle(1_500L)
                runCatching {
                    javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", java.io.File(out, "album-${width}x$height.png"))
                }
                onNodeWithText("Track 0 of alb-1", useUnmergedTree = true).assertExists()
                if (width < 700) onAllNodesWithText(playing.title, useUnmergedTree = true).onFirst().assertExists()
                onAllNodesWithContentDescription("Back").onFirst().performClick()
                settle(3_000L)
                settleUntil(5_000) { runCatching { onNodeWithText("Discography").assertExists() }.isSuccess }
                runCatching {
                    javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", java.io.File(out, "back-${width}x$height.png"))
                }
                if (runCatching { onNodeWithText("Discography").assertExists() }.isFailure) {
                    val tree = onRoot(useUnmergedTree = true).printToString(maxDepth = 60)
                    val at = tree.indexOf("Discography")
                    println("TREE AROUND: " + if (at >= 0) tree.substring(maxOf(0, at - 1500), minOf(tree.length, at + 300)) else "no Discography in tree")
                }
                onNodeWithText("Discography", useUnmergedTree = true).assertExists()
            }
        }
    }

    /** Made-up words, timed word by word, for every song. */
    private val wordsForEverySong = app.valen.core.LyricsBook(
        listOf(
            object : app.valen.core.LyricsSource {
                override val name = "test"
                override suspend fun find(query: app.valen.core.LyricsQuery) = app.valen.core.LyricsFormats.parse(
                    "[00:01.00]<00:01.00>chalk <00:01.40>on <00:01.70>the <00:02.00>wall<00:03.00>\n" +
                        "[00:04.00]<00:04.00>rain <00:04.40>rain <00:04.80>again<00:06.00>\n" +
                        "[00:12.00]<00:12.00>paper <00:12.50>stars<00:14.00>",
                    "lrc", "test",
                )
            },
        ),
    )

    @Test
    @org.junit.jupiter.api.Timeout(180)
    fun `the stage shows the song's words, a line plays from where it is pressed, and they hide again`() {
        val out = java.io.File("build/screens").also { it.mkdirs() }
        for ((width, height) in listOf(1600 to 900, 390 to 844)) {
            inApp(width, height, lyrics = wordsForEverySong) { coordinator, playlists ->
                runBlocking {
                    val songs = coordinator.unifiedSearch("warm up").tracks
                    coordinator.play(songs[0], songs)
                }
                setContent { ValenApp(coordinator, playlists, "now-playing") }
                settle()
                onNodeWithContentDescription("Show lyrics").performClick()
                settleUntil(5_000) { runCatching { onNodeWithText("chalk on the wall").assertExists() }.isSuccess }
                settle(1_200L)
                onNodeWithText("rain rain again").assertExists()
                onNodeWithText("Lyrics from test").assertExists()
                runCatching {
                    javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", java.io.File(out, "lyrics-${width}x$height.png"))
                }
                // A line plays from its start.
                onNodeWithText("paper stars").performSemanticsAction(SemanticsActions.OnClick)
                // The song plays on from there, so the time is read as a range.
                settleUntil { (coordinator.snapshot.value?.positionMs ?: 0L) >= 12_000L }
                val at = coordinator.snapshot.value?.positionMs ?: 0L
                assertEquals(true, at in 12_000L..40_000L, "played from $at")
                assertEquals(true, coordinator.settings.value.lyrics)
                onNodeWithContentDescription("Hide lyrics").performClick()
                settle(1_200L)
                onNodeWithText("chalk on the wall").assertDoesNotExist()
                assertEquals(false, coordinator.settings.value.lyrics)
            }
        }
        // No source has the words: one sentence says so.
        inApp(1600, 900) { coordinator, playlists ->
            runBlocking {
                val songs = coordinator.unifiedSearch("warm up").tracks
                coordinator.play(songs[0], songs)
            }
            setContent { ValenApp(coordinator, playlists, "now-playing") }
            settle()
            onNodeWithContentDescription("Show lyrics").performClick()
            settleUntil(5_000) { runCatching { onNodeWithText("No lyrics for this song").assertExists() }.isSuccess }
            onNodeWithText("No lyrics for this song").assertExists()
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(180)
    fun `line timed lyrics animate across the phrase and still seek from its start`() {
        val book = app.valen.core.LyricsBook(listOf(object : app.valen.core.LyricsSource {
            override val name = "line test"
            override suspend fun find(query: app.valen.core.LyricsQuery) = app.valen.core.LyricsFormats.parse(
                "[00:01.00]lanterns shimmer softly\n[00:14.00]paper moons drift home\n[00:23.00]chalk stars glow", "lrc", name)
        }))
        val out = java.io.File("build/screens").also { it.mkdirs() }
        for ((width, height) in listOf(1600 to 900, 2200 to 1200, 390 to 844)) {
            inApp(width, height, lyrics = book) { coordinator, playlists ->
                runBlocking {
                    coordinator.play(coordinator.unifiedSearch("warm up").tracks.first())
                    coordinator.toggle()
                    coordinator.setLyricsShown(true)
                }
                setContent { ValenApp(coordinator, playlists, "now-playing") }
                settleUntil(5_000) { runCatching { onNodeWithText("lanterns shimmer softly").assertExists() }.isSuccess }
                for (at in listOf(3_000L, 10_000L)) {
                    runBlocking { coordinator.seek(at) }
                    settleUntil { coordinator.snapshot.value?.positionMs == at }
                    settle(1_200)
                    javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png",
                        java.io.File(out, "line-lyrics-${width}x$height-$at.png"))
                }
                val source = (book.known(coordinator.snapshot.value!!.track!!) as app.valen.core.LyricsState.Found).lyrics
                assertEquals(app.valen.core.LyricsTiming.Lines, source.timing)
                assertTrue(source.lines.all { it.words.isEmpty() })
                onNodeWithText("paper moons drift home").performSemanticsAction(SemanticsActions.OnClick)
                settleUntil { coordinator.snapshot.value?.positionMs == 14_000L }
                assertEquals(14_000L, coordinator.snapshot.value?.positionMs)
            }
        }
    }

    @Test
    fun `settings turn the lyrics on, and the pen marks and moving words off`() = inApp(1600, 900) { coordinator, playlists ->
        setContent { ValenApp(coordinator, playlists, "settings") }
        settle(1_200L)
        // Settings is an index of parts: the lyrics are under Playback.
        onNode(hasText("Playback") and hasClickAction()).performClick()
        settle(1_200L)
        onNodeWithText("Lyrics").assertExists()
        assertEquals(true, coordinator.settings.value.lyricsMarks)
        assertEquals(true, coordinator.settings.value.lyricsMotion)
        // Below the fold: a semantics click, never a scroll (AGENTS §5).
        onNode(hasText("Pen marks") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        onNode(hasText("Moving words") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        onNode(hasText("Show on the stage") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        settle()
        assertEquals(false, coordinator.settings.value.lyricsMarks)
        assertEquals(false, coordinator.settings.value.lyricsMotion)
        assertEquals(true, coordinator.settings.value.lyrics)
    }

    @Test
    @org.junit.jupiter.api.Timeout(240)
    fun `your week is put on home from settings, told with its songs, and gives the player back`() {
        val out = java.io.File("build/screens").also { it.mkdirs() }
        for ((width, height) in listOf(1600 to 900, 390 to 844)) {
            inApp(width, height) { coordinator, playlists ->
                val songs = runBlocking { coordinator.unifiedSearch("warm up").tracks }
                // A week of listening, and a song of the person's own playing when the story opens.
                songs.forEachIndexed { i, song -> repeat(i + 2) { coordinator.stats.count(song, 120_000, null); coordinator.stats.count(songs.last(), 1, null) } }
                coordinator.stats.flush()
                runBlocking { coordinator.play(songs[1], songs) }
                settleUntil { coordinator.snapshot.value?.isPlaying == true }
                setContent { ValenApp(coordinator, playlists, "settings") }
                settle(1_200L)
                // Settings → Test puts the card on Home whatever the day.
                onNode(hasText("Test") and hasClickAction()).performClick()
                settle(1_200L)
                onNode(hasText("Your week on Home") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
                settle()
                assertEquals(true, coordinator.settings.value.recapAlways)
                // The card waits on Home (and in the profile), not in Settings.
                goTo("Home")
                settle(1_200L)
                onNode(hasText("Your week") and hasClickAction()).performClick()
                settle(1_500L)
                assertTrue(coordinator.isLent)
                onAllNodesWithText("Your week").onFirst().assertExists()
                // Its first song plays once the story has poured in; it is not the person's.
                settleUntil(5_000) { coordinator.queue.value.size == 1 }
                assertEquals(1, coordinator.queue.value.size)
                runCatching {
                    javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", java.io.File(out, "week-${width}x$height.png"))
                }
                // Paused, it waits; closed, it gives the player back.
                onNodeWithContentDescription("Pause your week").performClick()
                settle()
                onNodeWithContentDescription("Go on with your week").assertExists()
                onNodeWithContentDescription("Close your week").performClick()
                settle(1_200L)
                settleUntil(5_000) { !coordinator.isLent && coordinator.queue.value.size == songs.size }
                // Given back as it was: the person's queue, on their song.
                assertEquals(false, coordinator.isLent)
                assertEquals(songs, coordinator.queue.value)
                assertEquals(songs[1].key, coordinator.queue.value[coordinator.index.value].key)
                // (That nothing the story played is counted is RecapTest's, in core.)
            }
        }
    }

    /** A shelf that only remembers what it was asked to keep. */
    private class FakeShelf : app.valen.core.OfflineShelf {
        val keptLists = mutableListOf<String>()
        override val state = MutableStateFlow(app.valen.core.OfflineState())
        override fun fileFor(track: Track): String? = null
        override fun keep(playlist: app.valen.core.Playlist) {
            keptLists += playlist.id
            state.value = app.valen.core.OfflineState(
                playlists = mapOf(playlist.id to app.valen.core.PlaylistDownload(total = playlist.tracks.size, current = "Waiting")),
            )
        }
        override suspend fun forget(playlistId: String) { keptLists -= playlistId; state.value = app.valen.core.OfflineState() }
        val keptSongs = mutableListOf<String>()
        override fun keepSong(track: Track) {
            keptSongs += track.key
            state.value = state.value.copy(singles = state.value.singles + track.key, waiting = state.value.waiting + track.key)
        }
        override val folder: String = "/home/tester/Music/Valen"
    }

    /** An update that is downloaded and checked, waiting for Install; counts the presses. */
    private class FakeUpdates : app.valen.core.Updates {
        val release = app.valen.core.AppRelease(
            version = "1.0.0-alpha.5",
            code = 10_000_105,
            test = true,
            asset = app.valen.core.ReleaseAsset("linux-appimage", "Valen-1.0.0-alpha.5-x86_64.AppImage", 116_000_000, "0".repeat(64)),
            page = "https://example.invalid/release",
            url = "https://example.invalid/file",
            notes = "## What's Changed\n* The sheet pours in by @someone in https://github.com/dodo986/valen/pull/9",
        )
        var updatePresses = 0
        var pagesOpened = 0
        override val current = "1.0.0-alpha.4"
        override val route = app.valen.core.UpdateRoute.Itself
        override val elsewhere: String? = null
        override val state = MutableStateFlow<app.valen.core.UpdateState>(app.valen.core.UpdateState.Ready(release))
        override fun check() {}
        override fun update() {
            updatePresses++
            state.value = app.valen.core.UpdateState.Installed(release)
        }
        override fun restart() {}
        override fun openPage(release: app.valen.core.AppRelease) { pagesOpened++ }
    }

    @Test
    @org.junit.jupiter.api.Timeout(180)
    fun `a ready update opens its sheet by itself, installs, and Later keeps it away a day`() {
        for ((width, height) in listOf(1600 to 900, 390 to 844)) {
            val updates = FakeUpdates()
            inApp(width, height, updates = updates) { coordinator, playlists ->
                setContent { ValenApp(coordinator, playlists, "home") }
                // It waits a moment after the release is ready, so it never lands on a page mid-pour.
                settle(2_400L)
                onNodeWithText("What changed").assertExists()
                onNodeWithText("The sheet pours in by @someone (#9)").assertExists()
                onNode(hasText("Install") and hasClickAction()).performClick()
                settle(1_200L)
                assertEquals(1, updates.updatePresses)
                onNode(hasText("Restart now") and hasClickAction()).assertExists()
                onNode(hasText("Release page") and hasClickAction()).performClick()
                settle()
                assertEquals(1, updates.pagesOpened)

                onNode(hasText("Later") and hasClickAction()).performClick()
                settle(1_200L)
                assertTrue(runCatching { onNodeWithText("What changed").assertExists() }.isFailure)
                val later = coordinator.settings.value.updates
                assertEquals(updates.release.code, later.laterCode)
                assertTrue(later.laterUntil > System.currentTimeMillis() + app.valen.core.UpdatePrefs.LATER_MS - 60_000)

                if (width > 1000) {
                    // Settings → About: the update's row opens the sheet again.
                    onNodeWithText("Settings").performClick()
                    settle(1_200L)
                    onNode(hasText("Valen 1.0.0-alpha.5 opens next time") and hasClickAction()).performClick()
                    settle(1_200L)
                    onNode(hasText("Valen 1.0.0-alpha.5 is ready") and hasClickAction()).performClick()
                    settle(1_200L)
                    onNodeWithText("What changed").assertExists()
                }
            }
        }
    }

    @Test
    fun `settings link discord and a playlist starts downloading`() {
        val shelf = FakeShelf()
        val link = object : app.valen.core.PresenceLink {
            override val status = MutableStateFlow<app.valen.core.PresenceStatus>(app.valen.core.PresenceStatus.NoDiscord)
        }
        inApp(1600, 900, offline = shelf, presence = link) { coordinator, playlists ->
            runBlocking { playlists.library.add("Night drive", coordinator.unifiedSearch("warm up").tracks.take(3)) }
            setContent { ValenApp(coordinator, playlists, "settings") }
            settle(1_200L)
            onNode(hasText("Downloads") and hasClickAction()).performClick()
            settle(1_200L)
            onNodeWithText("~/Music/Valen").assertExists()
            onNode(hasText("Discord") and hasClickAction()).performClick()
            settle(1_200L)
            onNode(hasText("Show on Discord") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
            settle()
            assertEquals(true, coordinator.settings.value.presence.enabled)
            // Nothing to set up: the card is named after Valen's own application, so there is no id
            // field left to type into.
            assertTrue(runCatching { onNodeWithText("Application id").assertExists() }.isFailure)

            onNode(hasText("Second line") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
            settle()
            assertEquals(app.valen.core.PresenceDisplay.SECOND, coordinator.settings.value.presence.display)

            onAllNodes(hasSetTextAction()).onFirst().performTextClearance()
            onAllNodes(hasSetTextAction()).onFirst().performTextInput("{artist} · {album}")
            settle()
            assertEquals("{artist} · {album}", coordinator.settings.value.presence.details)
            onAllNodes(hasText("Artist") and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
            settle()
            assertTrue(coordinator.settings.value.presence.details.contains("{artist}"))

            onAllNodes(hasText("Valen's mark") and hasClickAction()).onFirst().performSemanticsAction(SemanticsActions.OnClick)
            settle()
            assertEquals(false, coordinator.settings.value.presence.mark)
            onNode(hasText("Count down") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
            settle()
            assertEquals(app.valen.core.PresenceTime.REMAINING, coordinator.settings.value.presence.time)
            onNode(hasText("Hide now") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
            settle()
            assertEquals(app.valen.core.PresencePause.AT_ONCE, coordinator.settings.value.presence.pause)
            onNode(hasText("The song's page") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
            settle()
            assertEquals(false, coordinator.settings.value.presence.songButton)
            onNode(hasText("Valen's page") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
            settle()
            assertEquals(false, coordinator.settings.value.presence.homeButton)

            onNodeWithText("Playlists").performClick()
            settle()
            onNodeWithText("Night drive").performClick()
            settle()
            onNode(hasText("Download") and hasClickAction()).performClick()
            settle()
            assertEquals(1, shelf.keptLists.size)
            onNodeWithText("Downloading 1 of 3").assertExists()
            onNode(hasText("Downloading") and hasClickAction()).performClick()
            settle()
            onNode(hasText("Remove download") and hasClickAction()).performClick()
            settle(1_200L)
            assertTrue(shelf.keptLists.isEmpty())

            // One song by itself, from its menu: the row turns into its progress.
            onAllNodesWithContentDescription("More").onFirst().performClick()
            settle()
            onAllNodes(hasText("Download") and hasClickAction()).onLast().performClick()
            settle()
            assertEquals(1, shelf.keptSongs.size)
            onNodeWithText("Waiting").assertExists()
        }
    }

    @Test
    fun `every screen composes at every width, empty and with a queue`() {
        val sizes = listOf(1600 to 900, 2200 to 1200, 860 to 700, 390 to 844)
        for ((width, height) in sizes) {
            for (playing in listOf(false, true)) {
                for (route in Route.entries) {
                    inApp(width, height) { coordinator, playlists ->
                        if (playing) {
                            runBlocking {
                                val songs = coordinator.unifiedSearch("warm up").tracks
                                coordinator.play(songs[1], songs)
                            }
                        }
                        setContent { ValenApp(coordinator, playlists, route.name) }
                        settle()
                        settle()
                    }
                }
            }
        }
    }

    @Test
    fun `search, play, queue, stage and settings on a wide window`() = inApp(1600, 900) { coordinator, playlists ->
        setContent { ValenApp(coordinator, playlists) }
        settle()

        onNodeWithText("Search").performClick()
        settle()
        onNode(hasSetTextAction()).performTextInput("anything")
        settle(1_200L)

        // Three services, four songs each, none shared: one merged list of twelve.
        assertEquals(12, coordinator.unifiedSearchCount("anything"))
        onAllNodesWithText("Spotify song 0")[0].performClick()
        settleUntil { coordinator.snapshot.value?.track?.title == "Spotify song 0" }
        settle()
        assertEquals("Spotify song 0", coordinator.snapshot.value?.track?.title)
        assertEquals(12, coordinator.queue.value.size)

        // The dock arrived with the song; its transport drives the same coordinator.
        onNodeWithContentDescription("Pause").performClick()
        settleUntil { coordinator.snapshot.value?.isPlaying == false }
        settle()
        assertEquals(false, coordinator.snapshot.value?.isPlaying)
        onNodeWithContentDescription("Next").performClick()
        settle()
        assertEquals(1, coordinator.index.value)

        // Shuffle and repeat sit either side of the dock's transport and say what they are.
        val playingNow = coordinator.queue.value[coordinator.index.value].key
        onNodeWithContentDescription("Shuffle off").performClick()
        settle()
        assertEquals(true, coordinator.settings.value.shuffle)
        assertEquals(playingNow, coordinator.queue.value[coordinator.index.value].key)
        onNodeWithContentDescription("Shuffle on").performClick()
        settle()
        assertEquals(1, coordinator.index.value)
        onNodeWithContentDescription("Repeat off").performClick()
        settle()
        onNodeWithContentDescription("Repeat all").performClick()
        settle()
        assertEquals(app.valen.core.RepeatMode.ONE, coordinator.settings.value.repeat)

        onNodeWithText("Queue").performClick()
        settle()
        onNodeWithText("Up next").assertExists()

        onNodeWithText("Now Playing").performClick()
        settle()
        onNodeWithContentDescription("Close the player").performClick()
        settle()
        // Closing the stage returns to the place it was opened over.
        onNodeWithText("Up next").assertExists()

        onNodeWithText("Settings").performClick()
        settle()
        onNode(hasText("Playback") and hasClickAction()).performClick()
        settle()
        onNodeWithText("Audio quality").assertExists()

        // The profile, from its chip at the foot of the panel: the set and its two pickers are
        // there, and a name typed onto the page is kept.
        onNodeWithText("Profile").performClick()
        settle()
        onNodeWithText("Change picture").assertExists()
        onNodeWithText("Change banner").assertExists()
        onNode(hasSetTextAction()).performTextInput("dodo")
        settle()
        assertEquals("dodo", coordinator.settings.value.profileName)

        onNodeWithText("Home").performClick()
        settle()
        assertTrue(coordinator.queue.value.isNotEmpty())
    }

    @Test
    fun `a phone opens the stage and the queue as sheets and closes them again`() = inApp(390, 844) { coordinator, playlists ->
        runBlocking {
            val songs = coordinator.unifiedSearch("warm up").tracks
            coordinator.play(songs[0], songs)
        }
        setContent { ValenApp(coordinator, playlists) }
        settle()

        onNodeWithText("Open player").performClick()
        settle()
        onNodeWithContentDescription("Close the player").assertExists()
        // Home is still composed under the sheet and has an "Up next" of its own; the stage's is the later one.
        onAllNodesWithText("Up next").onLast().performClick()
        settle()
        onNodeWithContentDescription("Close the queue").performClick()
        settle()
        onNodeWithContentDescription("Close the player").performClick()
        settle()
        onNodeWithText("Open player").assertExists()
    }

    @Test
    fun `playlists open, play, and refuse a link no service can read`() = inApp(1600, 900) { coordinator, playlists ->
        runBlocking {
            val found = coordinator.unifiedSearch("warm up").tracks
            playlists.library.add("Night drive", found.take(3), artworkUrl = null)
        }
        setContent { ValenApp(coordinator, playlists) }
        settle()

        // Home leads with what the person keeps, not with setup.
        onNodeWithText("Your playlists").assertExists()
        onNodeWithText("Night drive").performClick()
        settle()
        // The card plays the list: the three songs become the queue, in the order they were kept.
        assertEquals(3, coordinator.queue.value.size)
        assertEquals(0, coordinator.index.value)

        onNodeWithText("Playlists").performClick()
        settle()
        onNodeWithText("Night drive").performClick()
        settle()
        // Leaving the list and coming back finds it still open, not the shelf again.
        onNodeWithText("Home").performClick()
        settle()
        onNodeWithText("Playlists").performClick()
        settle()
        onNodeWithContentDescription("Back to playlists").performClick()
        settle()

        onNodeWithText("Import").performClick()
        settle()
        // A link this app cannot read is refused by name; no library entry is invented.
        onNode(hasSetTextAction()).performTextInput("https://example.com/not-a-playlist")
        onNodeWithText("Import").performClick()
        settle()
        onNodeWithText("That is not a Spotify, YouTube Music or SoundCloud playlist link", substring = true).assertExists()
        assertEquals(1, playlists.library.playlists.value.size)

        // The seeded list is still the three songs the fake services answered with.
        assertEquals(3, playlists.library.playlists.value.single().tracks.size)
    }

    private fun PlaybackCoordinator.unifiedSearchCount(query: String): Int =
        runBlocking { unifiedSearch(query).hits.size }

    @Test
    fun `the welcome walks from hello to home and keeps what was picked`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            runSkikoComposeUiTest(size = Size(1600f, 900f)) {
                mainClock.autoAdvance = false
                val coordinator = PlaybackCoordinator(
                    providers = ProviderId.services.map { FakeProvider(it) },
                    scope = scope,
                    settingsStore = SettingsStore(read = { null }, write = {}),
                )
                setContent { ValenApp(coordinator, playlists(coordinator)) }
                settle()
                onNodeWithText("Start").performClick()
                settle()
                onNode(hasSetTextAction()).performTextInput("dodo")
                settle()
                onNodeWithText("Next").performClick()
                settle()
                onNodeWithText("Daft Punk").performClick()
                settle()
                onNodeWithText("Next").performClick()
                settle()
                onNodeWithText("Skip").performClick() // no songs
                settle()
                onNodeWithText("Next").performClick() // picture
                settle()
                onNodeWithText("Next").performClick() // services and colour
                settle()
                onNodeWithText("Let's go").performClick()
                settle(1_200L)
                assertTrue(coordinator.settings.value.onboarded)
                assertEquals("dodo", coordinator.settings.value.profileName)
                assertEquals(listOf("Daft Punk"), coordinator.settings.value.favouriteArtists)
                onNodeWithText("Search").assertExists()
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a genre mix is made, saved, and keeps its recipe`() = inApp(1600, 900) { coordinator, playlists ->
        setContent { ValenApp(coordinator, playlists, Route.Mix.name) }
        settle()
        onNodeWithText("Genres").performClick()
        settle()
        onNodeWithText("Pop").performClick()
        settle()
        onNodeWithText("Make it").performClick()
        settleUntil { runCatching { onNodeWithText("Save").assertExists() }.isSuccess }
        settle()
        onNodeWithText("Save").performClick()
        settleUntil { playlists.library.playlists.value.isNotEmpty() }
        val saved = playlists.library.playlists.value.single()
        assertTrue(saved.tracks.isNotEmpty())
        assertEquals(listOf("Pop"), saved.smart?.genres)
    }

    @Test
    @org.junit.jupiter.api.Timeout(180)
    fun `about credits both, and clearing data empties everything and opens the welcome`() = inApp(1600, 900) { coordinator, playlists ->
        runBlocking { playlists.library.add("Night drive", coordinator.unifiedSearch("warm up").tracks.take(2)) }
        setContent { ValenApp(coordinator, playlists) }
        settle()
        onNodeWithText("Settings").performClick()
        settle()
        onNode(hasText("your data", substring = true) and hasClickAction()).performClick()
        settle()
        // Below the fold: pressed through its click action, which needs no scrolling.
        onNodeWithText("Who made Valen").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        onNodeWithText("dodo986").assertExists()
        onNodeWithText("Every other shit").assertExists()
        onNodeWithText("Overall best person").assertExists()

        onNodeWithText("Settings").performClick()
        settle()
        // The part chosen last is still open.
        onNodeWithText("Clear data").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        onNodeWithText("Clear everything").performSemanticsAction(SemanticsActions.OnClick)
        settleUntil { !coordinator.settings.value.onboarded && playlists.library.playlists.value.isEmpty() }
        settle()
        assertTrue(playlists.library.playlists.value.isEmpty())
        assertEquals(false, coordinator.settings.value.onboarded)
        onNodeWithText("Start").assertExists()
    }

    @Test
    fun `the song menu plays next, likes, adds to a new list and sets a sleep timer`() = inApp(1600, 900) { coordinator, playlists ->
        setContent { ValenApp(coordinator, playlists) }
        settle()
        onNodeWithText("Search").performClick()
        settle()
        onNode(hasSetTextAction()).performTextInput("anything")
        settle(1_200L)
        onAllNodesWithText("Spotify song 0")[0].performClick()
        settleUntil { coordinator.snapshot.value?.track?.title == "Spotify song 0" }
        settle()
        val playing = coordinator.snapshot.value!!.track!!

        // A row's ⋯ (the rows come before the dock's): "Play next" puts its song straight after.
        val fourth = coordinator.queue.value[3]
        onAllNodesWithContentDescription("More")[3].performClick()
        settle()
        onNodeWithText("Play next").performClick()
        settle()
        assertEquals(fourth.key, coordinator.queue.value[coordinator.index.value + 1].key)

        // The dock's ⋯ opens the player's menu: the heart, a new list with the song in it.
        onAllNodesWithContentDescription("More").onLast().performClick()
        settle()
        onNodeWithText("Start radio").assertExists()
        onNodeWithContentDescription("Like").performClick()
        settle()
        assertTrue(playlists.library.isLiked(playing))
        onNodeWithContentDescription("Liked").assertExists()

        onNodeWithText("Add to playlist").performClick()
        settle()
        onNodeWithText("New playlist").performClick()
        settle()
        onNodeWithContentDescription("Playlist name").performTextInput("Road")
        onNodeWithText("Create").performClick()
        settle()
        val road = playlists.library.playlists.value.single { it.name == "Road" }
        assertEquals(listOf(playing.key), road.tracks.map { it.key })
        onNodeWithContentDescription("In Road").assertExists()

        // Back, then the sleep timer: set, and taken off again.
        onNodeWithContentDescription("Back").performClick()
        settle()
        onNodeWithText("Sleep timer").performClick()
        settle()
        onNodeWithText("30 min").performClick()
        settle()
        assertEquals(30, (coordinator.sleep.value as? SleepTimer.At)?.minutes)
        onNodeWithText("Turn off").performClick()
        settle()
        assertEquals(null, coordinator.sleep.value)

        onNodeWithContentDescription("Close menu").performClick()
        settle()
        onNodeWithText("Sleep timer").assertDoesNotExist()
    }

    @Test
    @org.junit.jupiter.api.Timeout(120)
    fun `an opened playlist measures its own cover without playing a song`() {
        val palette = app.valen.ui.artwork.ArtworkPalette
        val out = java.io.File("build/screens/playlist-palette").also { it.mkdirs() }
        val covers = listOf(0x2468CC, 0x26964C, 0xCE9228, 0x28A6A6).mapIndexed { index, rgb ->
            val file = java.io.File(out, "cover-$index.png")
            val image = java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 64) for (x in 0 until 64) image.setRGB(x, y, rgb)
            javax.imageio.ImageIO.write(image, "png", file)
            file.toURI().toString()
        }
        try {
            for ((index, dimensions) in listOf(1600 to 900, 2200 to 1200, 390 to 844).withIndex()) {
                palette.clear()
                val (width, height) = dimensions
                inApp(width, height) { coordinator, playlists ->
                    coordinator.setCoverColour(index != 2)
                    val songs = (if (index == 2) covers else covers.take(1)).mapIndexed { n, url ->
                        Track(ProviderId.YOUTUBE_MUSIC, "cover-$n", "Song $n", "Artist", artworkUrl = url)
                    }
                    val ownCover = covers[1].takeIf { index == 1 }
                    val playlist = runBlocking { playlists.library.add("Cover colours", songs, artworkUrl = ownCover) }
                    val expected = ownCover ?: covers.first()
                    setContent { ValenApp(coordinator, playlists, "playlists") }
                    settle()
                    assertTrue(!palette.known(expected), "the shelf must not measure every cover")
                    onNodeWithText(playlist.name).performClick()
                    settleUntil(5_000) { palette.known(expected) }
                    assertTrue(palette.known(expected), "opening a playlist must measure its header cover")
                    assertTrue(palette.measured(expected)?.accent != palette.Fallback)
                    assertEquals(null, coordinator.nowPlaying.value, "colour needs no playing song")
                    if (index == 2) {
                        assertTrue(covers.drop(1).none(palette::known), "only the mosaic's representative cover is measured")
                    }
                    settle(1_500)
                    javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png",
                        java.io.File(out, "playlist-${width}x$height.png"))
                    coordinator.setCoverColour(false)
                    settle(1_500)
                    assertEquals(palette.Neutral, palette.tones(expected), "global artwork colours remain off")
                    assertEquals(palette.Neutral.bright, app.valen.ui.theme.ValenColors.Primary)
                    val blackAndPink = onRoot().captureToImage().toAwtImage()
                    val background = java.awt.Color(blackAndPink.getRGB((width * 0.75).toInt(), 60))
                    val coverChannel = if (index == 1) background.green else background.blue
                    assertTrue(coverChannel > background.red,
                        "the playlist background must keep its cover colour in black and pink: $background")
                    javax.imageio.ImageIO.write(blackAndPink, "png",
                        java.io.File(out, "playlist-black-pink-${width}x$height.png"))
                }
            }
        } finally {
            palette.clear()
            palette.followCover = false
        }
    }

    @Test
    fun `a playlist is made on the shelf, filled from its own search, renamed, and its menu opens as a sheet on a phone`() {
        inApp(1600, 900) { coordinator, playlists ->
            setContent { ValenApp(coordinator, playlists) }
            settle()
            onNodeWithText("Playlists").performClick()
            settle()
            onAllNodesWithText("New playlist")[0].performClick()
            settle()
            onNodeWithContentDescription("Playlist name").performTextInput("Morning")
            onNodeWithText("Create").performClick()
            settle()
            val morning = playlists.library.playlists.value.single { it.name == "Morning" }
            assertTrue(morning.isOwn)
            // It opens at once, empty, with its own search for songs to add.
            onNodeWithText("No songs yet").assertExists()
            onNode(hasSetTextAction()).performTextInput("warm")
            settle(1_200L)
            settleUntil { onAllNodesWithContentDescription("Add to Morning").fetchSemanticsNodes().size >= 2 }
            onAllNodesWithContentDescription("Add to Morning")[0].performClick()
            settle()
            onAllNodesWithContentDescription("Add to Morning")[0].performClick()
            settle()
            assertEquals(2, playlists.library.get(morning.id)?.tracks?.size)
            onNodeWithText("Shuffle").assertExists()

            onNodeWithContentDescription("Rename").performClick()
            settle()
            onNodeWithContentDescription("Playlist name").performTextClearance()
            onNodeWithContentDescription("Playlist name").performTextInput("Mornings")
            onNodeWithContentDescription("Playlist name").performImeAction()
            settle()
            assertEquals("Mornings", playlists.library.get(morning.id)?.name)
        }

        inApp(390, 844) { coordinator, playlists ->
            runBlocking {
                val songs = coordinator.unifiedSearch("warm up").tracks
                coordinator.play(songs[0], songs)
            }
            setContent { ValenApp(coordinator, playlists) }
            settle()
            // A long press on the mini player (the last thing that takes one) opens the sheet.
            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)).onLast()
                .performSemanticsAction(SemanticsActions.OnLongClick)
            settle()
            onNodeWithText("Sleep timer").assertExists()
            // Its centre is under the sheet, so the scrim is pressed by its action, not at a point.
            onNodeWithContentDescription("Close menu").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithText("Sleep timer").assertDoesNotExist()

            // Over the stage, which is a sheet of its own: the ⋯ in its corner opens the menu above it.
            onNodeWithText("Open player").performClick()
            settle()
            onAllNodesWithContentDescription("More").onLast().performClick()
            settle()
            onNodeWithText("Start radio").assertExists()
            // Its centre is under the sheet, so the scrim is pressed by its action, not at a point.
            onNodeWithContentDescription("Close menu").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithContentDescription("Close the player").assertExists()
        }
    }

    @Test
    fun `the sound notebook takes a preset, turns its pages, writes its notes and switches off`() {
        inApp(1600, 900) { coordinator, playlists ->
            val songs = runBlocking { coordinator.unifiedSearch("warm up").tracks }
            val spotifySong = songs.first { it.provider == ProviderId.SPOTIFY }
            val ytmSong = songs.first { it.provider == ProviderId.YOUTUBE_MUSIC }
            runBlocking { coordinator.play(spotifySong, songs) }
            setContent { ValenApp(coordinator, playlists) }
            settle()
            onNodeWithText("Now Playing").performClick()
            settle()
            onNodeWithContentDescription("Sound").performClick()
            settle(900L)
            onNodeWithText("Presets").assertExists()

            // A Spotify song: the notebook is shut under a note that says why, and nothing under it moves.
            onNodeWithText("Spotify plays without effects").assertExists()
            onNodeWithText("Nightcore").performClick()
            settle()
            assertEquals(null, coordinator.sound.value.preset)
            onNodeWithText("Play it with effects").assertExists()

            // A YouTube Music song: the note is gone and the notebook works.
            runBlocking { coordinator.play(ytmSong, songs) }
            settle(900L)
            onNodeWithText("Spotify plays without effects").assertDoesNotExist()

            onNodeWithText("Nightcore").performClick()
            settle(900L)
            assertEquals("nightcore", coordinator.sound.value.preset)
            onNodeWithText("Sped up to 1.25×").assertExists()

            // Tone: the bass slider set three quarters along is +6 dB, and the notebook writes it down.
            onNodeWithText("Tone").performClick()
            settle(900L)
            onNodeWithContentDescription("Bass").performSemanticsAction(SemanticsActions.SetProgress) { it(0.75f) }
            settle(900L)
            assertEquals(6f, coordinator.sound.value.bass)
            assertTrue(coordinator.sound.value.edited)
            onNodeWithText("Bass +6 dB").assertExists()

            onNodeWithContentDescription("Effects").performClick()
            settle(900L)
            assertEquals(false, coordinator.sound.value.on)
            onNodeWithText("Effects off").assertExists()
            onNodeWithContentDescription("Close sound").performClick()
            settle()
        }

        inApp(390, 844) { coordinator, playlists ->
            runBlocking {
                val songs = coordinator.unifiedSearch("warm up").tracks
                coordinator.play(songs[0], songs)
            }
            setContent { ValenApp(coordinator, playlists) }
            settle()
            onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)).onLast()
                .performSemanticsAction(SemanticsActions.OnLongClick)
            settle()
            onNodeWithText("Sound").performSemanticsAction(SemanticsActions.OnClick)
            settle(900L)
            onNodeWithText("Presets").assertExists()
            onNodeWithText("As recorded").assertExists()
            onNodeWithContentDescription("Close sound").performClick()
            settle()
            onNodeWithText("Presets").assertDoesNotExist()
        }
    }
}
