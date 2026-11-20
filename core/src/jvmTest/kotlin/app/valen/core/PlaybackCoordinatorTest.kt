/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun track(provider: ProviderId, id: String, title: String = id) = Track(
    provider = provider,
    id = id,
    title = title,
    artist = "Artist",
    durationMs = 180_000,
    uri = "uri:$id",
)

private class FakeProvider(override val id: ProviderId) : MusicProvider {
    override val session = MutableStateFlow<SessionState>(SessionState.SignedOut)
    override val player = MutableStateFlow<PlayerSnapshot?>(null)
    override val events = MutableSharedFlow<ProviderEvent>()

    var played: Track? = null
    var startedAt = 0L
    val prepared = mutableListOf<Track>()
    var volume: Float = 1f
    var playing = false

    override suspend fun connect() { session.value = SessionState.Connected("test") }
    override suspend fun disconnect() { session.value = SessionState.SignedOut }
    override suspend fun search(query: String, limit: Int): List<Track> =
        listOf(track(id, "$id-$query"))

    override suspend fun play(track: Track, startMs: Long) {
        played = track
        startedAt = startMs
        playing = true
        player.value = PlayerSnapshot(track = track, isPlaying = true, durationMs = track.durationMs)
    }

    override suspend fun pause() { playing = false; player.value = player.value?.copy(isPlaying = false) }
    override suspend fun resume() { playing = true; player.value = player.value?.copy(isPlaying = true) }
    override suspend fun seek(positionMs: Long) { player.value = player.value?.copy(positionMs = positionMs) }
    override suspend fun setVolume(volume: Float) { this.volume = volume }
    override suspend fun prepare(track: Track) { prepared += track }
    override suspend fun close() {}
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackCoordinatorTest {

    private fun settings(): SettingsStore = SettingsStore(read = { null }, write = {})

    @Test
    fun `play queues the whole context and starts the chosen track`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val ytm = FakeProvider(ProviderId.YOUTUBE_MUSIC)
        val coordinator = PlaybackCoordinator(listOf(spotify, ytm), backgroundScope, settings())

        val a = track(ProviderId.SPOTIFY, "a")
        val b = track(ProviderId.SPOTIFY, "b")
        val c = track(ProviderId.SPOTIFY, "c")
        coordinator.play(b, listOf(a, b, c))

        assertEquals(listOf(a, b, c), coordinator.queue.value)
        assertEquals(1, coordinator.index.value)
        assertEquals(ProviderId.SPOTIFY, coordinator.active.value)
        assertEquals(b, spotify.played)
        assertTrue(spotify.playing)
    }

    @Test
    fun `next crosses providers and pauses the previous adapter`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val ytm = FakeProvider(ProviderId.YOUTUBE_MUSIC)
        val coordinator = PlaybackCoordinator(listOf(spotify, ytm), backgroundScope, settings())

        val a = track(ProviderId.SPOTIFY, "a")
        val b = track(ProviderId.YOUTUBE_MUSIC, "b")
        coordinator.play(a, listOf(a, b))
        assertEquals(ProviderId.SPOTIFY, coordinator.active.value)

        coordinator.next()
        assertEquals(ProviderId.YOUTUBE_MUSIC, coordinator.active.value)
        assertEquals(1, coordinator.index.value)
        assertEquals(b, ytm.played)
        assertTrue(!spotify.playing)

        coordinator.previous()
        assertEquals(ProviderId.SPOTIFY, coordinator.active.value)
        assertEquals(a, spotify.played)
    }

    @Test
    fun `with keep playing off, the last track pauses instead of wrapping when it ends`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, settings())
        coordinator.setKeepPlaying(false)

        val a = track(ProviderId.SPOTIFY, "a")
        val b = track(ProviderId.SPOTIFY, "b")
        coordinator.play(a, listOf(a, b))
        coordinator.next()
        runCurrent()
        coordinator.next(auto = true)

        assertEquals(1, coordinator.index.value)
        assertEquals(false, spotify.playing)
        assertEquals(listOf(a, b), coordinator.queue.value)
        assertTrue(coordinator.keepPlaying.value.isEmpty(), "nothing is looked for while it is off")
    }

    /** A service whose every search finds five songs not seen before, all by "Artist". */
    private class EndlessProvider : MusicProvider by FakeProvider(ProviderId.SPOTIFY) {
        val inner = FakeProvider(ProviderId.SPOTIFY)
        var batch = 0
        override val player = inner.player
        override suspend fun play(track: Track, startMs: Long) = inner.play(track, startMs)
        override suspend fun search(query: String, limit: Int): List<Track> {
            val b = ++batch
            return (1..5).map { Track(ProviderId.SPOTIFY, "k$b-$it", "Found $b song $it", "Artist", durationMs = 180_000) }
        }
    }

    @Test
    fun `when the queue runs out, songs like it play on, after anything added meanwhile`() = runTest {
        val spotify = EndlessProvider()
        val written = StringBuilder()
        val store = SettingsStore(read = { written.toString().ifEmpty { null } }, write = { written.clear(); written.append(it) })
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, store)
        val a = track(ProviderId.SPOTIFY, "a")
        val b = track(ProviderId.SPOTIFY, "b")
        coordinator.play(a, listOf(a, b))
        runCurrent()
        // One song left after the playing one: what follows is found now, and is not the queue.
        assertTrue(coordinator.keepPlaying.value.isNotEmpty(), "songs found to follow the queue")
        assertEquals(listOf(a, b), coordinator.queue.value)

        // A song added now goes before them.
        val c = track(ProviderId.SPOTIFY, "c")
        coordinator.enqueue(c)
        coordinator.next(); runCurrent()
        coordinator.next(auto = true); runCurrent()
        assertEquals(c, coordinator.queue.value[coordinator.index.value])
        val following = coordinator.keepPlaying.value
        assertTrue(following.isNotEmpty(), "found again for the queue's new end")

        // c ends: the music goes on with them.
        coordinator.next(auto = true); runCurrent()
        assertEquals(listOf(a, b, c), coordinator.queue.value.take(3))
        assertEquals(3, coordinator.index.value)
        assertEquals(following.first(), spotify.inner.played)
        assertTrue(spotify.inner.playing)
        assertEquals(following.map { it.key }, coordinator.queue.value.drop(3).map { it.key })
        assertTrue(coordinator.queue.value.distinctBy { recordingKey(it.title, it.artist) }.size == coordinator.queue.value.size, "no song twice")
        // They are Valen's, not chosen, and a restart still knows it.
        assertEquals(following.map { it.key }, store.load().session?.offered)
    }

    @Test
    fun `a queue that repeats looks for nothing to follow it, and a tapped follower plays at once`() = runTest {
        val spotify = EndlessProvider()
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, settings())
        val a = track(ProviderId.SPOTIFY, "a")
        coordinator.setRepeat(RepeatMode.ALL)
        coordinator.play(a)
        runCurrent()
        assertTrue(coordinator.keepPlaying.value.isEmpty(), "a repeating queue never runs out")

        coordinator.setRepeat(RepeatMode.OFF)
        runCurrent()
        val found = coordinator.keepPlaying.value
        assertTrue(found.size >= 3, "found once it can run out: $found")

        coordinator.playFromKeepPlaying(found[2])
        runCurrent()
        assertEquals(listOf(a) + found, coordinator.queue.value)
        assertEquals(3, coordinator.index.value)
        assertEquals(found[2], spotify.inner.played)
    }

    @Test
    fun `removing the current track keeps a sensible index`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, settings())

        val a = track(ProviderId.SPOTIFY, "a")
        val b = track(ProviderId.SPOTIFY, "b")
        val c = track(ProviderId.SPOTIFY, "c")
        coordinator.play(b, listOf(a, b, c))

        coordinator.removeAt(1)
        assertEquals(listOf(a, c), coordinator.queue.value)
        assertEquals(1, coordinator.index.value)

        coordinator.removeAt(1)
        assertEquals(listOf(a), coordinator.queue.value)

        coordinator.removeAt(0)
        assertEquals(emptyList<Track>(), coordinator.queue.value)
        assertEquals(-1, coordinator.index.value)
        assertNull(coordinator.active.value)
    }

    @Test
    fun `moving rows keeps the playing track selected`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, settings())

        val a = track(ProviderId.SPOTIFY, "a")
        val b = track(ProviderId.SPOTIFY, "b")
        val c = track(ProviderId.SPOTIFY, "c")
        coordinator.play(b, listOf(a, b, c))

        coordinator.move(1, 0)
        assertEquals(listOf(b, a, c), coordinator.queue.value)
        assertEquals(0, coordinator.index.value)

        coordinator.move(2, 0)
        assertEquals(listOf(c, b, a), coordinator.queue.value)
        assertEquals(1, coordinator.index.value)
    }

    @Test
    fun `enqueue does not duplicate a song already queued`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, settings())

        val a = track(ProviderId.SPOTIFY, "a")
        val b = track(ProviderId.SPOTIFY, "b")
        coordinator.play(a, listOf(a))
        coordinator.enqueue(b)
        coordinator.enqueue(b)

        assertEquals(listOf(a, b), coordinator.queue.value)
    }

    @Test
    fun `volume is persisted and applied to the active adapter`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, settings())
        coordinator.play(track(ProviderId.SPOTIFY, "a"))

        coordinator.setVolume(0.4f)
        assertEquals(0.4f, spotify.volume)
        assertEquals(0.4f, coordinator.settings.value.volume)

        coordinator.setVolume(2f)
        assertEquals(1f, spotify.volume)
    }

    @Test
    fun `search fans out and keeps a failure beside the results`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val broken = object : MusicProvider by FakeProvider(ProviderId.SOUNDCLOUD) {
            override suspend fun search(query: String, limit: Int): List<Track> = error("no client id")
        }
        val coordinator = PlaybackCoordinator(listOf(spotify, broken), backgroundScope, settings())

        val results = coordinator.search("daft punk")
        assertEquals(2, results.size)
        assertEquals(1, results.single { it.provider == ProviderId.SPOTIFY }.tracks.size)
        assertEquals("no client id", results.single { it.provider == ProviderId.SOUNDCLOUD }.error)
    }

    @Test
    fun `the same recording listed twice becomes one result`() = runTest {
        val twice = object : MusicProvider by FakeProvider(ProviderId.YOUTUBE_MUSIC) {
            override suspend fun search(query: String, limit: Int): List<Track> = listOf(
                track(ProviderId.YOUTUBE_MUSIC, "a", "One More Time"),
                track(ProviderId.YOUTUBE_MUSIC, "b", "One More Time"),
                track(ProviderId.YOUTUBE_MUSIC, "c", "Aerodynamic"),
            )
        }
        val coordinator = PlaybackCoordinator(listOf(twice), backgroundScope, settings())

        val results = coordinator.search("daft punk").single()
        assertEquals(listOf("a", "c"), results.tracks.map { it.id })
    }

    @Test
    fun `unified search lists a recording once and plays the preferred service's copy`() = runTest {
        val spotify = object : MusicProvider by FakeProvider(ProviderId.SPOTIFY) {
            override suspend fun search(query: String, limit: Int): List<Track> = listOf(
                track(ProviderId.SPOTIFY, "sp-other", "Aerodynamic"),
                track(ProviderId.SPOTIFY, "sp-one", "One More Time"),
            )
        }
        val ytm = object : MusicProvider by FakeProvider(ProviderId.YOUTUBE_MUSIC) {
            override suspend fun search(query: String, limit: Int): List<Track> = listOf(
                track(ProviderId.YOUTUBE_MUSIC, "yt-one", "One More Time (Official Video)"),
            )
        }
        val coordinator = PlaybackCoordinator(
            listOf(spotify, ytm),
            backgroundScope,
            settings(),
            AppSettings(priority = listOf(ProviderId.SPOTIFY, ProviderId.YOUTUBE_MUSIC)),
        )

        val result = coordinator.unifiedSearch("one more time")

        assertEquals(2, result.hits.size)
        val one = result.hits.single { it.track.title.startsWith("One More Time") }
        assertEquals("sp-one", one.track.id)
        assertEquals(listOf(ProviderId.YOUTUBE_MUSIC), one.alsoOn)
        assertEquals(2, result.tracks.distinctBy { it.id }.size)
    }

    @Test
    fun `the source order decides which copy of a shared song is kept`() = runTest {
        val spotify = object : MusicProvider by FakeProvider(ProviderId.SPOTIFY) {
            override suspend fun search(query: String, limit: Int): List<Track> =
                listOf(track(ProviderId.SPOTIFY, "sp", "One More Time"))
        }
        val ytm = object : MusicProvider by FakeProvider(ProviderId.YOUTUBE_MUSIC) {
            override suspend fun search(query: String, limit: Int): List<Track> =
                listOf(track(ProviderId.YOUTUBE_MUSIC, "yt", "One More Time"))
        }
        val coordinator = PlaybackCoordinator(
            listOf(spotify, ytm),
            backgroundScope,
            settings(),
            AppSettings(priority = listOf(ProviderId.YOUTUBE_MUSIC, ProviderId.SPOTIFY)),
        )

        val hit = coordinator.unifiedSearch("one more time").hits.single()

        assertEquals(ProviderId.YOUTUBE_MUSIC, hit.track.provider)
        assertEquals(listOf(ProviderId.SPOTIFY), hit.alsoOn)
    }

    @Test
    fun `a failed service is reported beside the merged results`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val broken = object : MusicProvider by FakeProvider(ProviderId.SOUNDCLOUD) {
            override suspend fun search(query: String, limit: Int): List<Track> = error("no client id")
        }
        val coordinator = PlaybackCoordinator(listOf(spotify, broken), backgroundScope, settings())

        val result = coordinator.unifiedSearch("daft punk")

        assertEquals(1, result.hits.size)
        assertEquals("no client id", result.errors[ProviderId.SOUNDCLOUD])
    }

    @Test
    fun `the source order is cleaned, completed and persisted`() = runTest {
        var stored: String? = null
        val store = SettingsStore(read = { stored }, write = { stored = it })
        val coordinator = PlaybackCoordinator(
            listOf(FakeProvider(ProviderId.SPOTIFY), FakeProvider(ProviderId.YOUTUBE_MUSIC), FakeProvider(ProviderId.SOUNDCLOUD)),
            backgroundScope,
            store,
        )

        coordinator.setPriority(listOf(ProviderId.SOUNDCLOUD, ProviderId.SOUNDCLOUD, ProviderId.SPOTIFY))

        assertEquals(
            listOf(ProviderId.SOUNDCLOUD, ProviderId.SPOTIFY, ProviderId.YOUTUBE_MUSIC),
            coordinator.settings.value.priority,
        )
        assertEquals(
            coordinator.settings.value.priority,
            SettingsStore(read = { stored }, write = {}).load().priority,
        )
    }

    @Test
    fun `settings survive a JSON round trip`() {
        var stored: String? = null
        val store = SettingsStore(read = { stored }, write = { stored = it })
        store.save(AppSettings(quality = AudioQuality.HIGH, volume = 0.3f, lastProvider = ProviderId.YOUTUBE_MUSIC))

        val loaded = SettingsStore(read = { stored }, write = {}).load()
        assertEquals(AudioQuality.HIGH, loaded.quality)
        assertEquals(0.3f, loaded.volume)
        assertEquals(ProviderId.YOUTUBE_MUSIC, loaded.lastProvider)
    }

    @Test
    fun `a broken settings file falls back to defaults`() {
        val loaded = SettingsStore(read = { "{not json" }, write = {}).load()
        assertEquals(AppSettings(), loaded)
    }

    @Test
    fun `the colour choice and the profile persist, and an old settings file reads as black and pink`() = runTest {
        var stored: String? = """{"quality":"AUTO","volume":0.5}"""
        val store = SettingsStore(read = { stored }, write = { stored = it })
        val coordinator = PlaybackCoordinator(listOf(FakeProvider(ProviderId.SPOTIFY)), backgroundScope, store)
        assertEquals(false, coordinator.settings.value.coverColour)
        assertEquals("", coordinator.settings.value.profileName)

        coordinator.setCoverColour(true)
        coordinator.setProfileName("dodo")
        coordinator.setAvatar("file:///tmp/avatar-1.png")
        coordinator.setBanner("file:///tmp/banner-1.jpg")

        val reloaded = SettingsStore(read = { stored }, write = {}).load()
        assertEquals(true, reloaded.coverColour)
        assertEquals("dodo", reloaded.profileName)
        assertEquals("file:///tmp/avatar-1.png", reloaded.avatar)
        assertEquals("file:///tmp/banner-1.jpg", reloaded.banner)

        coordinator.setAvatar(null)
        assertEquals(null, SettingsStore(read = { stored }, write = {}).load().avatar)
    }

    @Test
    fun `listening is counted by song, artist, album, playlist and day, and a play once per start`() {
        var now = 86_400_000L * 20_000 + 1_000
        var stored: String? = null
        val stats = ListeningStats(ListeningStore(read = { stored }, write = { stored = it }), clock = { now })
        val song = Track(ProviderId.SPOTIFY, "a", "Song A", "Artist", album = "Album")
        val other = Track(ProviderId.SOUNDCLOUD, "b", "Song B", "Artist")
        repeat(4) { stats.count(song, 250, "Night drive"); now += 250 }
        stats.count(other, 1_000, null)
        stats.count(song, 500, null)
        stats.flush()

        val log = stats.log.value
        assertEquals(2_500, log.totalMs)
        assertEquals(3, log.plays) // A, then B, then A again
        assertEquals(1_500, log.songs[song.key]?.ms)
        assertEquals(2, log.songs[song.key]?.plays)
        assertEquals(listOf("Artist" to 2_500L), log.topArtists(3))
        assertEquals(listOf("Album" to 1_500L), log.topAlbums(3))
        assertEquals(listOf("Night drive" to 1_000L), log.topPlaylists(3))
        assertEquals(listOf(0L, 2_500L), log.lastDays(20_000, 2))
        // What was written reads back the same.
        assertEquals(log, ListeningStore(read = { stored }, write = {}).load())
    }

    @Test
    fun `clearing data stops playback, empties the queue and resets settings and listening`() = runTest {
        var stored: String? = """{"onboarded":true,"profileName":"dodo","favouriteArtists":["A"],"volume":0.4}"""
        val store = SettingsStore(read = { stored }, write = { stored = it })
        val provider = FakeProvider(ProviderId.SPOTIFY)
        val coordinator = PlaybackCoordinator(listOf(provider), backgroundScope, store)
        val song = Track(ProviderId.SPOTIFY, "1", "Song", "A")
        coordinator.play(song)
        coordinator.stats.count(song, 5_000, null)
        coordinator.stats.flush()

        coordinator.clearData()

        assertEquals(emptyList<Track>(), coordinator.queue.value)
        assertEquals(-1, coordinator.index.value)
        assertEquals(AppSettings(), coordinator.settings.value)
        assertEquals(false, SettingsStore(read = { stored }, write = {}).load().onboarded)
        assertEquals(0L, coordinator.stats.log.value.totalMs)
    }

    @Test
    fun `private mode counts no listening and holds no skip, and the switch is kept`() = runTest {
        var stored: String? = null
        val store = SettingsStore(read = { stored }, write = { stored = it })
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        // The test's virtual clock, so a tick adds exactly the time the scheduler was advanced: on a
        // real clock a fast machine could run every tick inside one millisecond and count nothing.
        val coordinator = PlaybackCoordinator(
            listOf(spotify),
            backgroundScope,
            store,
            clock = { testScheduler.currentTime },
        )
        val a = track(ProviderId.SPOTIFY, "a")
        val b = track(ProviderId.SPOTIFY, "b")

        // What was counted before the switch stays; nothing after it is added.
        coordinator.stats.count(a, 3_000, "Night drive")
        coordinator.setPrivateMode(true)
        assertEquals(true, SettingsStore(read = { stored }, write = {}).load().privateMode)

        coordinator.play(a, listOf(a, b))
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        coordinator.stats.flush()
        assertEquals(3_000L, coordinator.stats.log.value.totalMs, "private mode added no listening")

        // Leaving it in its first seconds is not held against it either.
        coordinator.next()
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals(emptyMap<String, Int>(), coordinator.stats.log.value.skips)

        // Off again, the same listening counts and an early skip is held.
        coordinator.setPrivateMode(false)
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        coordinator.stats.flush()
        assertEquals(5_000L, coordinator.stats.log.value.totalMs, "listening counts again")
        coordinator.next()
        assertEquals(1, coordinator.stats.log.value.skips[b.key])
        assertEquals(false, SettingsStore(read = { stored }, write = {}).load().privateMode)
    }

    @Test
    fun `shuffle plays the chosen song first, keeps every song once, and off puts the order back`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, settings())
        val list = (1..20).map { track(ProviderId.SPOTIFY, "s$it") }

        coordinator.setShuffle(true)
        coordinator.play(list[5], list)
        assertEquals(list[5], coordinator.queue.value.first())
        assertEquals(0, coordinator.index.value)
        assertEquals(list.map { it.key }.toSet(), coordinator.queue.value.map { it.key }.toSet())
        assertEquals(20, coordinator.queue.value.size)

        coordinator.next()
        val playing = coordinator.queue.value[coordinator.index.value]
        coordinator.setShuffle(false)
        assertEquals(list, coordinator.queue.value)
        assertEquals(list.indexOf(playing), coordinator.index.value)
    }

    @Test
    fun `repeat all wraps, repeat one replays on end but next still moves on`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, settings())
        val a = track(ProviderId.SPOTIFY, "a")
        val b = track(ProviderId.SPOTIFY, "b")
        coordinator.play(b, listOf(a, b))

        coordinator.cycleRepeat()
        assertEquals(RepeatMode.ALL, coordinator.settings.value.repeat)
        coordinator.next(auto = true)
        assertEquals(0, coordinator.index.value)
        assertEquals(a, spotify.played)

        coordinator.cycleRepeat()
        assertEquals(RepeatMode.ONE, coordinator.settings.value.repeat)
        spotify.played = null
        coordinator.next(auto = true)
        assertEquals(0, coordinator.index.value)
        assertEquals(a, spotify.played)
        coordinator.next()
        assertEquals(1, coordinator.index.value)

        coordinator.cycleRepeat()
        assertEquals(RepeatMode.OFF, coordinator.settings.value.repeat)
    }

    @Test
    fun `the next start shows the last song paused and plays it from where it was left`() = runTest {
        var saved: String? = null
        val store = SettingsStore(read = { saved }, write = { saved = it })
        val a = track(ProviderId.SPOTIFY, "a")
        val b = track(ProviderId.SPOTIFY, "b")
        val c = track(ProviderId.SPOTIFY, "c")
        run {
            val spotify = FakeProvider(ProviderId.SPOTIFY)
            val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, store)
            coordinator.play(b, listOf(a, b, c), from = "Night drive")
            spotify.player.value = spotify.player.value?.copy(positionMs = 42_000)
            testScheduler.advanceTimeBy(300)
            coordinator.saveSession()
        }

        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val again = PlaybackCoordinator(listOf(spotify), backgroundScope, store)
        assertEquals(listOf(a, b, c), again.queue.value)
        assertEquals(1, again.index.value)
        assertEquals(b, again.snapshot.value?.track)
        assertEquals(false, again.snapshot.value?.isPlaying)
        assertNull(spotify.played)

        again.toggle()
        assertEquals(b, spotify.played)
        assertTrue(spotify.startedAt in 42_000L..43_000L, "started at ${spotify.startedAt}")
    }

    @Test
    fun `the restored song is looked up shortly after start, so Play starts it at once`() = runTest {
        var saved: String? = null
        val store = SettingsStore(read = { saved }, write = { saved = it })
        val a = track(ProviderId.YOUTUBE_MUSIC, "a")
        val b = track(ProviderId.YOUTUBE_MUSIC, "b")
        run {
            val ytm = FakeProvider(ProviderId.YOUTUBE_MUSIC)
            val coordinator = PlaybackCoordinator(listOf(ytm), backgroundScope, store)
            coordinator.play(b, listOf(a, b))
            coordinator.saveSession()
        }
        val ytm = FakeProvider(ProviderId.YOUTUBE_MUSIC)
        PlaybackCoordinator(listOf(ytm), backgroundScope, store)
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()
        assertTrue(ytm.prepared.isEmpty(), "not during the first screen")
        testScheduler.advanceTimeBy(1_500)
        testScheduler.runCurrent()
        assertEquals(listOf(b), ytm.prepared)

        // A restored Spotify song on a signed-out Spotify is not looked up: it could not play.
        run {
            val spotify = FakeProvider(ProviderId.SPOTIFY)
            val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, store)
            spotify.session.value = SessionState.Connected("me")
            coordinator.play(track(ProviderId.SPOTIFY, "s"), listOf(track(ProviderId.SPOTIFY, "s")))
            coordinator.saveSession()
        }
        val signedOut = object : MusicProvider by FakeProvider(ProviderId.SPOTIFY) {
            val calls = mutableListOf<Track>()
            override val playsSignedOut = false
            override suspend fun prepare(track: Track) { calls += track }
        }
        PlaybackCoordinator(listOf(signedOut), backgroundScope, store)
        testScheduler.advanceTimeBy(3_000)
        testScheduler.runCurrent()
        assertTrue(signedOut.calls.isEmpty())
    }

    @Test
    fun `a song that fails moves the music on, also on repeat one, and a run of failures stops`() = runTest {
        val ytm = FakeProvider(ProviderId.YOUTUBE_MUSIC)
        val coordinator = PlaybackCoordinator(listOf(ytm), backgroundScope, settings())
        val list = (1..8).map { track(ProviderId.YOUTUBE_MUSIC, "y$it") }
        coordinator.setRepeat(RepeatMode.ONE)
        coordinator.play(list[0], list)
        ytm.events.emit(ProviderEvent.Failed("the stream would not open", list[0].key))
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        assertEquals(1, coordinator.index.value, "moved on instead of stopping (or replaying a broken song)")
        assertEquals("y2", ytm.played?.id)

        // A failure for a song the app already left does not move the queue.
        ytm.events.emit(ProviderEvent.Failed("late", list[0].key))
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        assertEquals(1, coordinator.index.value)

        // Every song failing is not something skipping fixes: it stops after a few.
        repeat(8) {
            val now = coordinator.queue.value[coordinator.index.value]
            ytm.events.emit(ProviderEvent.Failed("no network", now.key))
            testScheduler.advanceTimeBy(2_000)
            testScheduler.runCurrent()
        }
        assertTrue(coordinator.index.value < 6, "walked the whole queue: ${coordinator.index.value}")
    }

    @Test
    fun `an end reported once the service is on another song still repeats the song that ended`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY).also { it.session.value = SessionState.Connected("me") }
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, settings())
        val a = track(ProviderId.SPOTIFY, "a")
        val b = track(ProviderId.SPOTIFY, "b")
        coordinator.setRepeat(RepeatMode.ONE)
        coordinator.play(a, listOf(a, b))
        // The page has already moved on to a song of its own choosing when it reports the end.
        spotify.player.value = PlayerSnapshot(track = track(ProviderId.SPOTIFY, "autoplay"), isPlaying = true)
        spotify.played = null
        spotify.events.emit(ProviderEvent.TrackEnded(a.key))
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertEquals(a, spotify.played, "repeat one replayed the song that ended")
        assertEquals(0, coordinator.index.value)
    }

    @Test
    fun `play next puts the song straight after the playing one, also when shuffle is turned off`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, settings())
        val list = (1..6).map { track(ProviderId.SPOTIFY, "s$it") }
        coordinator.play(list[1], list)

        coordinator.playNext(list[4])
        assertEquals(listOf("s1", "s2", "s5", "s3", "s4", "s6"), coordinator.queue.value.map { it.id })
        assertEquals(1, coordinator.index.value)
        assertEquals(true, coordinator.noticeIsConfirmation.value)

        coordinator.setShuffle(true)
        val outside = track(ProviderId.SPOTIFY, "new")
        coordinator.playNext(outside)
        assertEquals("new", coordinator.queue.value[coordinator.index.value + 1].id)
        coordinator.setShuffle(false)
        val at = coordinator.queue.value.indexOfFirst { it.id == "new" }
        assertEquals(coordinator.index.value + 1, at)
    }

    @Test
    fun `a timed sleep timer pauses when it runs out`() = runTest {
        var now = 1_000_000L
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, settings(), clock = { now })
        val a = track(ProviderId.SPOTIFY, "a")
        coordinator.play(a, listOf(a))

        coordinator.sleepIn(15)
        assertEquals(15 * 60_000L, coordinator.sleepRemainingMs())
        now += 10 * 60_000L
        testScheduler.advanceTimeBy(600)
        assertTrue(spotify.playing)
        now += 5 * 60_000L
        testScheduler.advanceTimeBy(600)
        assertEquals(false, spotify.playing)
        assertNull(coordinator.sleep.value)
        assertEquals("Paused by the sleep timer", coordinator.notice.value)
    }

    @Test
    fun `the end-of-song timer leaves the next song waiting at its start`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, settings())
        val a = track(ProviderId.SPOTIFY, "a")
        val b = track(ProviderId.SPOTIFY, "b")
        coordinator.play(a, listOf(a, b))
        coordinator.sleepAtEndOfSong()

        spotify.played = null
        coordinator.next(auto = true)
        assertNull(spotify.played)
        assertEquals(1, coordinator.index.value)
        assertEquals(b, coordinator.snapshot.value?.track)
        assertEquals(false, coordinator.snapshot.value?.isPlaying)
        assertNull(coordinator.sleep.value)

        coordinator.toggle()
        assertEquals(b, spotify.played)
        assertEquals(0L, spotify.startedAt)
    }

    @Test
    fun `a radio plays the song now and queues more by its artist after it`() = runTest {
        val radioSongs = (1..5).map { Track(ProviderId.SPOTIFY, "r$it", "Other $it", "Artist", durationMs = 180_000) }
        val spotify = object : MusicProvider by FakeProvider(ProviderId.SPOTIFY) {
            val inner = FakeProvider(ProviderId.SPOTIFY)
            override val player = inner.player
            override suspend fun play(track: Track, startMs: Long) = inner.play(track, startMs)
            override suspend fun search(query: String, limit: Int): List<Track> = radioSongs
        }
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, settings())
        val seed = track(ProviderId.SPOTIFY, "seed", "Seed song")
        coordinator.startRadio(seed)

        assertEquals(seed, coordinator.queue.value.first())
        assertEquals(0, coordinator.index.value)
        assertEquals(6, coordinator.queue.value.size)
        assertEquals(radioSongs.map { it.key }.toSet(), coordinator.queue.value.drop(1).map { it.key }.toSet())
    }

    @Test
    fun `a radio near its end is refilled with new songs, and a new queue is never refilled`() = runTest {
        var batch = 0
        val spotify = object : MusicProvider by FakeProvider(ProviderId.SPOTIFY) {
            val inner = FakeProvider(ProviderId.SPOTIFY)
            override val player = inner.player
            override suspend fun play(track: Track, startMs: Long) = inner.play(track, startMs)
            // Every search finds five songs not seen before, all by the radio's artist.
            override suspend fun search(query: String, limit: Int): List<Track> {
                val b = ++batch
                return (1..5).map { Track(ProviderId.SPOTIFY, "b$b-$it", "Batch $b song $it", "Artist", durationMs = 180_000) }
            }
        }
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, settings())
        val seed = track(ProviderId.SPOTIFY, "seed", "Seed song").copy(artist = "Artist")
        coordinator.startRadio(seed)
        runCurrent()
        val first = coordinator.queue.value.size
        assertEquals(6, first)
        // Two songs on, three are left after the playing one: the radio gets more.
        repeat(2) { coordinator.next(); runCurrent() }
        assertTrue(coordinator.queue.value.size > first, "refilled: ${coordinator.queue.value.size}")
        assertEquals(seed, coordinator.queue.value.first())
        assertEquals(coordinator.queue.value.size, coordinator.queue.value.distinctBy { recordingKey(it.title, it.artist) }.size, "no song twice")

        // Something else is played: that queue is the person's, and stays as it is.
        val mine = listOf(track(ProviderId.SPOTIFY, "x"), track(ProviderId.SPOTIFY, "y"))
        coordinator.play(mine.first(), mine)
        repeat(1) { coordinator.next(); runCurrent() }
        assertEquals(mine, coordinator.queue.value)
    }

    @Test
    fun `effects reach a player that takes them, the last of a quick run only, and survive a restart`() = runTest {
        var saved: String? = null
        val store = SettingsStore(read = { saved }, write = { saved = it })
        val applied = mutableListOf<SoundEffects>()
        val inner = FakeProvider(ProviderId.SOUNDCLOUD)
        val sc = object : MusicProvider by inner {
            override val effectSupport: EffectSupport = EffectSupport.Full
            override suspend fun setEffects(effects: SoundEffects) { applied += effects }
        }
        val coordinator = PlaybackCoordinator(listOf(sc), backgroundScope, store)
        val a = track(ProviderId.SOUNDCLOUD, "a")
        coordinator.play(a, listOf(a))
        assertEquals(1, applied.size)
        assertTrue(applied.last().isFlat)

        coordinator.setEffects(SoundEffects(reverb = 0.1f))
        coordinator.setEffects(SoundEffects(reverb = 0.2f))
        coordinator.setEffects(SoundEffects(reverb = 0.3f))
        testScheduler.advanceTimeBy(200)
        assertEquals(2, applied.size)
        assertEquals(0.3f, applied.last().reverb)

        // Off: the player plays as recorded; the settings are kept.
        coordinator.setEffects(coordinator.sound.value.copy(on = false))
        testScheduler.advanceTimeBy(200)
        assertTrue(applied.last().isFlat)
        assertEquals(0.3f, coordinator.sound.value.reverb)

        coordinator.usePreset(SoundPresets.byId("slowed")!!)
        testScheduler.advanceTimeBy(200)
        assertEquals(0.85f, applied.last().speed)
        val again = PlaybackCoordinator(listOf(sc), backgroundScope, store)
        assertEquals("slowed", again.sound.value.preset)
        assertEquals(EffectSupport.Full, again.effectSupport(a))
    }

    @Test
    fun `parametric presets and output profiles persist without replacing other effects`() = runTest {
        var saved: String? = null
        val store = SettingsStore(read = { saved }, write = { saved = it })
        val coordinator = PlaybackCoordinator(emptyList(), backgroundScope, store)
        coordinator.setEffects(SoundEffects(reverb = .4f, bass = 2f))
        val preset = ParametricPresets.all[1]
        coordinator.useParametricPreset(preset)
        assertEquals(.4f, coordinator.sound.value.reverb)
        assertEquals(2f, coordinator.sound.value.bass)
        coordinator.saveParametricPreset("mine", preset)
        coordinator.saveParametricOutputProfile("headphones")
        coordinator.usePreset(SoundPresets.byId("slowed")!!)
        assertEquals(preset.bands, coordinator.sound.value.parametricEq.bands)
        coordinator.setEffects(coordinator.sound.value.copy(parametricEq = ParametricEq()))
        assertTrue(coordinator.restoreParametricOutputProfile("headphones"))
        assertEquals(preset.bands, coordinator.sound.value.parametricEq.bands)
        testScheduler.advanceTimeBy(200)
        val again = PlaybackCoordinator(emptyList(), backgroundScope, store)
        assertEquals(preset, again.settings.value.parametricEqLibrary.presets["mine"])
        assertEquals(preset.bands, again.sound.value.parametricEq.bands)
    }

    @Test
    fun `a signed-out Spotify is never picked to play, and says why when asked to`() = runTest {
        val needsAccount = object : MusicProvider by FakeProvider(ProviderId.SPOTIFY) {
            val inner = FakeProvider(ProviderId.SPOTIFY)
            override val playsSignedOut = false
            override val session = MutableStateFlow<SessionState>(SessionState.SignedOut)
            override val player = inner.player
            override suspend fun play(track: Track, startMs: Long) = inner.play(track, startMs)
            override suspend fun search(query: String, limit: Int) = listOf(Track(ProviderId.SPOTIFY, "s1", "One More Time", "Daft Punk"))
        }
        val ytm = object : MusicProvider by FakeProvider(ProviderId.YOUTUBE_MUSIC) {
            val inner = FakeProvider(ProviderId.YOUTUBE_MUSIC)
            override val player = inner.player
            override suspend fun play(track: Track, startMs: Long) = inner.play(track, startMs)
            override suspend fun search(query: String, limit: Int) = listOf(Track(ProviderId.YOUTUBE_MUSIC, "y1", "One More Time", "Daft Punk"))
        }
        val coordinator = PlaybackCoordinator(listOf(needsAccount, ytm), backgroundScope, settings())

        // Spotify is first in the order, but signed out: it is not even asked, so only the copy that
        // can play is found; asked on its own, it still answers.
        val hit = coordinator.unifiedSearch("one more time").hits.single()
        assertEquals(ProviderId.YOUTUBE_MUSIC, hit.track.provider)
        assertEquals(emptyList<ProviderId>(), hit.alsoOn)
        assertEquals(ProviderId.SPOTIFY, coordinator.unifiedSearch("one more time", setOf(ProviderId.SPOTIFY)).hits.single().track.provider)

        // Asked for by name, the Spotify copy plays from YouTube Music, which has the same recording.
        val spotifySong = Track(ProviderId.SPOTIFY, "s1", "One More Time", "Daft Punk")
        coordinator.play(spotifySong, listOf(spotifySong))
        assertNull(needsAccount.inner.played)
        assertEquals("y1", ytm.inner.played?.id)
        assertEquals(listOf("y1"), coordinator.queue.value.map { it.id })

        // A song nothing else has: the reason is said, and the queue is left as it was.
        val only = Track(ProviderId.SPOTIFY, "s9", "Rare B-side", "Nobody")
        coordinator.play(only, listOf(only))
        assertEquals("Sign in to Spotify to play this", coordinator.notice.value)
        assertEquals(listOf("y1"), coordinator.queue.value.map { it.id })

        // Moving through a queue passes it by.
        val a = Track(ProviderId.YOUTUBE_MUSIC, "a", "A", "X")
        val c = Track(ProviderId.YOUTUBE_MUSIC, "c", "C", "X")
        coordinator.play(a, listOf(a, spotifySong, c))
        coordinator.next()
        assertEquals(2, coordinator.index.value)
        assertEquals(c, ytm.inner.played)

        // Signed in, it plays.
        needsAccount.session.value = SessionState.Connected("me")
        coordinator.play(spotifySong, listOf(spotifySong))
        assertEquals(spotifySong, needsAccount.inner.played)
    }

    @Test
    fun `taking out the song that plays starts the one after it, and an emptied queue is silence`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, settings())
        val a = track(ProviderId.SPOTIFY, "a")
        val b = track(ProviderId.SPOTIFY, "b")
        coordinator.play(a, listOf(a, b))
        // The start runs on the coordinator's own (background) scope: time is moved on for it.
        coordinator.removeAt(0)
        testScheduler.advanceTimeBy(50)
        testScheduler.runCurrent()
        assertEquals(b, spotify.played)
        assertEquals(0, coordinator.index.value)
        coordinator.removeAt(0)
        testScheduler.advanceTimeBy(50)
        testScheduler.runCurrent()
        assertEquals(-1, coordinator.index.value)
        assertNull(coordinator.active.value)
        assertEquals(false, spotify.playing)
    }

    @Test
    fun `a Spotify song is swapped for the same recording on a service that takes effects, at the same place`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val sc = object : MusicProvider by FakeProvider(ProviderId.SOUNDCLOUD) {
            val inner = FakeProvider(ProviderId.SOUNDCLOUD)
            override val effectSupport: EffectSupport = EffectSupport.Full
            override val player = inner.player
            override suspend fun play(track: Track, startMs: Long) = inner.play(track, startMs)
            override suspend fun search(query: String, limit: Int) = listOf(
                Track(ProviderId.SOUNDCLOUD, "x", "Something else", "Daft Punk"),
                Track(ProviderId.SOUNDCLOUD, "omt", "One More Time", "Daft Punk"),
            )
        }
        val coordinator = PlaybackCoordinator(listOf(spotify, sc), backgroundScope, settings())
        val before = track(ProviderId.SPOTIFY, "a")
        val omt = Track(ProviderId.SPOTIFY, "s1", "One More Time", "Daft Punk", durationMs = 320_000)
        coordinator.play(omt, listOf(before, omt))
        spotify.player.value = spotify.player.value?.copy(positionMs = 64_000)
        testScheduler.advanceTimeBy(300)

        assertTrue(coordinator.playWithEffects(omt))
        assertEquals("omt", sc.inner.played?.id)
        assertEquals(1, coordinator.index.value)
        assertEquals(listOf("a", "omt"), coordinator.queue.value.map { it.id })
        assertEquals(ProviderId.SOUNDCLOUD, coordinator.active.value)

        val nowhere = Track(ProviderId.SPOTIFY, "s2", "Not anywhere", "Nobody")
        assertEquals(false, coordinator.playWithEffects(nowhere))
        assertEquals("Not found on SoundCloud", coordinator.notice.value)
    }

    /** Two stores over one kept JSON string: one writes, one reads the saved settings back. */
    private fun recordingSettings(): Pair<SettingsStore, SettingsStore> {
        var json: String? = null
        fun store() = SettingsStore(read = { json }, write = { json = it })
        return store() to store()
    }

    @Test
    fun `a generated queue is saved as generated, and a chosen song clears it`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val (store, saved) = recordingSettings()
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, store)
        val mix = listOf(track(ProviderId.SPOTIFY, "m1"), track(ProviderId.SPOTIFY, "m2"))

        coordinator.play(mix.first(), mix, generated = true)
        assertEquals(true, saved.load().session?.generated, "a mix's session says it was generated")

        coordinator.play(track(ProviderId.SPOTIFY, "own"))
        assertEquals(false, saved.load().session?.generated, "a song the person chose is their own")
    }

    @Test
    fun `a restored generated session stays generated, and lending puts it back`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val savedQueue = listOf(track(ProviderId.SPOTIFY, "m1"), track(ProviderId.SPOTIFY, "m2"))
        val (store, saved) = recordingSettings()
        val coordinator = PlaybackCoordinator(
            listOf(spotify),
            backgroundScope,
            store,
            initial = AppSettings(session = SavedSession(savedQueue, 0, generated = true)),
        )
        coordinator.saveSession()
        assertEquals(true, saved.load().session?.generated, "a restored mix session is still generated")

        // Borrowed by the recap and given back: the generated flag comes back with the queue.
        coordinator.lend()
        coordinator.giveBack()
        coordinator.saveSession()
        assertEquals(true, saved.load().session?.generated, "giveBack restores the generated flag")
        assertEquals(savedQueue.map { it.key }, coordinator.queue.value.map { it.key })
    }

    @Test
    fun `a radio's queue is generated once its songs are found`() = runTest {
        val spotify = FakeProvider(ProviderId.SPOTIFY)
        val (store, saved) = recordingSettings()
        val coordinator = PlaybackCoordinator(listOf(spotify), backgroundScope, store)
        val seed = track(ProviderId.SPOTIFY, "seed")
        coordinator.startRadio(seed)
        assertTrue(coordinator.queue.value.size >= 1, "the radio starts from its seed")
        assertEquals(true, saved.load().session?.generated, "a radio queue is Valen's, so it is saved as generated")
    }

    @Test
    fun `a service that has not moved yet never puts its last song back on show`() = runTest {
        // A service that answers play() at once but shows the new song only later, like a page or
        // a stream still opening; until then its own state still names what it played before.
        val slow = LaggingProvider(ProviderId.SOUNDCLOUD)
        val coordinator = PlaybackCoordinator(listOf(slow), backgroundScope, settings())
        val a = track(ProviderId.SOUNDCLOUD, "a")
        val b = track(ProviderId.SOUNDCLOUD, "b")
        coordinator.play(a, listOf(a, b))
        slow.show(a)
        testScheduler.advanceTimeBy(300)
        testScheduler.runCurrent()
        assertEquals(a.key, coordinator.snapshot.value?.track?.key)

        coordinator.next()
        // Ticks pass while the service still shows the song before: the new one stays on show.
        repeat(4) {
            testScheduler.advanceTimeBy(260)
            testScheduler.runCurrent()
            assertEquals(b.key, coordinator.snapshot.value?.track?.key, "the old song came back on show")
        }
        // The service moves: its own state is taken again, playing.
        slow.show(b)
        testScheduler.advanceTimeBy(260)
        testScheduler.runCurrent()
        assertEquals(b.key, coordinator.snapshot.value?.track?.key)
        assertTrue(coordinator.snapshot.value?.isPlaying == true)
    }

    @Test
    fun `the service's own description of the song asked for never replaces it on screen`() = runTest {
        val slow = LaggingProvider(ProviderId.SPOTIFY)
        val coordinator = PlaybackCoordinator(listOf(slow), backgroundScope, settings())
        val asked = Track(
            provider = ProviderId.SPOTIFY, id = "a", title = "Song", artist = "Artist",
            artworkUrl = "https://img/640", durationMs = 180_000, uri = "uri:a",
        )
        coordinator.play(asked, listOf(asked))
        // The Web Player describes it with a cover of another size...
        slow.show(asked.copy(artworkUrl = "https://img/300"))
        testScheduler.advanceTimeBy(300)
        testScheduler.runCurrent()
        assertEquals(asked, coordinator.snapshot.value?.track)
        // ...or as a regional copy with an id of its own: still the song that was asked for.
        slow.show(asked.copy(id = "relinked", uri = "uri:relinked", artworkUrl = "https://img/300"))
        testScheduler.advanceTimeBy(300)
        testScheduler.runCurrent()
        assertEquals(asked, coordinator.snapshot.value?.track)
        // A different song is the service's own.
        val other = Track(provider = ProviderId.SPOTIFY, id = "b", title = "Other", artist = "Artist", uri = "uri:b")
        slow.show(other)
        testScheduler.advanceTimeBy(300)
        testScheduler.runCurrent()
        assertEquals(other.key, coordinator.snapshot.value?.track?.key)
    }
}

/** Takes play() but changes what it shows only when told to ([show]). */
private class LaggingProvider(override val id: ProviderId) : MusicProvider {
    override val session = MutableStateFlow<SessionState>(SessionState.Connected("test"))
    override val player = MutableStateFlow<PlayerSnapshot?>(null)
    override val events = MutableSharedFlow<ProviderEvent>()

    fun show(track: Track) {
        player.value = PlayerSnapshot(track = track, isPlaying = true, durationMs = track.durationMs)
    }

    override suspend fun connect() {}
    override suspend fun disconnect() {}
    override suspend fun search(query: String, limit: Int): List<Track> = emptyList()
    override suspend fun play(track: Track, startMs: Long) {}
    override suspend fun pause() { player.value = player.value?.copy(isPlaying = false) }
    override suspend fun resume() { player.value = player.value?.copy(isPlaying = true) }
    override suspend fun seek(positionMs: Long) {}
    override suspend fun setVolume(volume: Float) {}
    override suspend fun prepare(track: Track) {}
    override suspend fun close() {}
}
