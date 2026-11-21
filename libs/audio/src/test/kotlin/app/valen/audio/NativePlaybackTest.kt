/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import kotlinx.coroutines.test.runCurrent
import app.valen.core.AudioQuality
import app.valen.core.PlayerSnapshot
import app.valen.core.ProviderEvent
import app.valen.core.ProviderId
import app.valen.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The shared half of a native provider: what the app sees when a sink plays, ends or fails. */
@OptIn(ExperimentalCoroutinesApi::class)
class NativePlaybackTest {

    private val track = Track(
        provider = ProviderId.SOUNDCLOUD,
        id = "293",
        title = "Flickermood",
        artist = "Forss",
        durationMs = 213_886,
        uri = "soundcloud:track:293",
    )

    /** A player with no process behind it: the tests drive its state directly. */
    private class FakePlayer : AudioPlayer {
        private val _state = MutableStateFlow(AudioState())
        override val state: StateFlow<AudioState> = _state.asStateFlow()
        override val canSeek: Boolean = true

        var played: ResolvedStream? = null
        var startedAt: Long = -1
        var volume: Float = 1f

        fun emit(state: AudioState) {
            _state.value = state
        }

        override suspend fun play(stream: ResolvedStream, startMs: Long) {
            played = stream
            startedAt = startMs
            _state.value = AudioState(hasStream = true, playing = true, durationMs = stream.durationMs)
        }

        override suspend fun pause() {
            _state.value = _state.value.copy(playing = false)
        }

        override suspend fun resume() {
            _state.value = _state.value.copy(playing = true)
        }

        override suspend fun seek(positionMs: Long) {
            _state.value = _state.value.copy(positionMs = positionMs)
        }

        override suspend fun setVolume(volume: Float) {
            this.volume = volume
            _state.value = _state.value.copy(volume = volume)
        }

        override suspend fun stop() {
            _state.value = AudioState()
        }

        override suspend fun release() = stop()
    }

    @Test
    fun `a stream is resolved from the track's own id and played at the asked position`() = runTest {
        val player = FakePlayer()
        var askedId: String? = null
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val playback = NativePlayback(player, scope) { id, _ ->
            askedId = id
            ResolvedStream("https://example.test/stream", durationMs = 213_886)
        }

        playback.play(track, startMs = 42_000)

        assertEquals("293", askedId)
        assertEquals("https://example.test/stream", player.played?.url)
        assertEquals(42_000, player.startedAt)
        val snapshot = playback.snapshot.value
        assertNotNull(snapshot)
        assertEquals(track, snapshot!!.track)
        scope.cancel()
    }

    @Test
    fun `a cached stream keeps the catalogue duration at the sink without a lookup`() = runTest {
        val player = FakePlayer()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val cache = object : StreamCache {
            override fun offline(key: String) = ResolvedStream("/cache/song", cacheKey = key)
            override fun keep(key: String, stream: ResolvedStream, playing: Boolean) = Unit
            override fun forget(key: String) = Unit
        }
        val playback = NativePlayback(player, scope, cache = cache) { _, _ -> error("must stay offline") }
        playback.play(track, 0)
        assertEquals(track.durationMs, player.played?.durationMs)
        assertEquals(track.durationMs, playback.snapshot.value?.durationMs)
        scope.cancel()
    }

    @Test
    fun `an early end with missing sink duration recovers using the catalogue length`() = runTest {
        val player = FakePlayer()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val playback = NativePlayback(player, scope) { _, _ -> ResolvedStream("https://example.test/song") }
        val events = mutableListOf<ProviderEvent>()
        scope.launch { playback.events.collect { events += it } }
        playback.play(track, 0)
        player.emit(AudioState(hasStream = true, started = true, ended = true, positionMs = 60_000))
        assertTrue(playback.snapshot.value?.isBuffering == true, "the song should reopen, not skip")
        assertTrue(events.none { it is ProviderEvent.TrackEnded })
        scope.cancel()
    }

    @Test
    fun `the end of a stream is reported once, and a new song arms it again`() = runTest {
        val player = FakePlayer()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val playback = NativePlayback(player, scope) { _, _ -> ResolvedStream("https://example.test/a") }
        val events = mutableListOf<ProviderEvent>()
        val listener = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        listener.launch { playback.events.collect { events += it } }

        playback.play(track, 0)
        player.emit(AudioState(hasStream = true, playing = true, durationMs = 213_886))
        player.emit(AudioState(hasStream = true, ended = true, positionMs = 213_886, durationMs = 213_886))
        assertEquals(1, events.count { it is ProviderEvent.TrackEnded })

        // A player that repeats its ended state must not advance the queue twice.
        player.emit(AudioState(hasStream = true, ended = true, positionMs = 213_886, durationMs = 213_886))
        assertEquals(1, events.count { it is ProviderEvent.TrackEnded })

        playback.play(track, 0)
        player.emit(AudioState(hasStream = true, playing = true, durationMs = 213_886))
        player.emit(AudioState(hasStream = true, ended = true, positionMs = 213_886, durationMs = 213_886))
        assertEquals(2, events.count { it is ProviderEvent.TrackEnded })
        scope.cancel()
        listener.cancel()
    }

    @Test
    fun `a stream that cannot be resolved leaves the song in place with the reason`() = runTest {
        val player = FakePlayer()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val playback = NativePlayback(player, scope) { _, _ ->
            throw IllegalStateException("SoundCloud has no playable stream for this track")
        }
        val events = mutableListOf<ProviderEvent>()
        val listener = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        listener.launch { playback.events.collect { events += it } }

        val thrown = runCatching { playback.play(track, 5_000) }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        assertNull(player.played)
        val snapshot: PlayerSnapshot = playback.snapshot.value!!
        assertEquals(track, snapshot.track)
        assertEquals("SoundCloud has no playable stream for this track", snapshot.error)
        assertTrue(events.any { it is ProviderEvent.Failed })
        scope.cancel()
        listener.cancel()
    }

    /**
     * A new song is resolved before its stream starts, and the old ffplay process keeps ticking
     * for those hundreds of milliseconds. Publishing that tick under the new track showed the new
     * song playing at the old song's position — the app asked for a song and the stage jumped.
     */
    @Test
    fun `a tick from the previous stream does not move the new song`() = runTest {
        val player = FakePlayer()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        var resolves = 0
        val playback = NativePlayback(player, scope) { _, _ ->
            // The first song plays at once; the second one is still resolving while the old
            // stream keeps ticking.
            if (++resolves > 1) gate.await()
            ResolvedStream("https://example.test/b")
        }

        playback.play(track, 0)
        player.emit(AudioState(hasStream = true, playing = true, positionMs = 1_000, durationMs = 213_886))
        assertEquals(1_000, playback.snapshot.value?.positionMs)

        val next = track.copy(id = "294", title = "Another", uri = "soundcloud:track:294")
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { playback.play(next, 0) }
        // Still resolving: this is the old stream's clock.
        player.emit(AudioState(hasStream = true, playing = true, positionMs = 2_000, durationMs = 213_886))
        assertEquals(next, playback.snapshot.value?.track, "the new song is the one on show")
        assertEquals(0, playback.snapshot.value?.positionMs, "the old stream's clock moved the new song")

        gate.complete(Unit)
        job.join()
        assertEquals(next, playback.snapshot.value?.track)
        assertEquals(0, playback.snapshot.value?.positionMs)
        assertTrue(playback.snapshot.value?.isPlaying == true, "the resolved stream is playing")
        assertFalse(playback.snapshot.value?.isBuffering == true, "still buffering once the stream started")
        scope.cancel()
    }

    @Test
    fun `a song that cannot be resolved stops the previous stream and keeps its failure`() = runTest {
        val player = FakePlayer()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var resolves = 0
        val playback = NativePlayback(player, scope) { _, _ ->
            if (++resolves == 1) ResolvedStream("https://example.test/a")
            else throw IllegalStateException("no stream for this track")
        }

        playback.play(track, 0)
        player.emit(AudioState(hasStream = true, playing = true, positionMs = 1_000, durationMs = 213_886))

        val next = track.copy(id = "294", title = "Another", uri = "soundcloud:track:294")
        runCatching { playback.play(next, 0) }

        assertFalse(player.state.value.hasStream, "the previous stream was left running")
        val snapshot = playback.snapshot.value!!
        assertEquals(next, snapshot.track)
        assertEquals("no stream for this track", snapshot.error)
        assertFalse(snapshot.isPlaying, "a song that failed is not reported as playing")

        // What the stopped sink reports next must not clear the reason the song failed.
        player.emit(AudioState())
        assertEquals("no stream for this track", playback.snapshot.value?.error)
        assertEquals(next, playback.snapshot.value?.track)
        scope.cancel()
    }

    @Test
    fun `volume reaches the sink and the snapshot`() = runTest {
        val player = FakePlayer()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val playback = NativePlayback(player, scope) { _, _ -> ResolvedStream("https://example.test/a") }

        playback.play(track, 0)
        playback.setVolume(0.35f)
        playback.setQuality(AudioQuality.HIGH)

        assertEquals(0.35f, player.volume)
        assertEquals(0.35f, playback.snapshot.value?.volume)
        scope.cancel()
    }

    @Test
    fun `a warm-up and the tap after it share one look-up`() = runTest {
        var asked = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val playback = NativePlayback(FakePlayer(), backgroundScope) { id, _ ->
            asked++
            gate.await()
            ResolvedStream("https://example.invalid/$id")
        }
        val warm = launch { playback.warm(track) }
        runCurrent()
        val play = launch { playback.play(track, 0) }
        runCurrent()
        gate.complete(Unit)
        warm.join()
        play.join()
        assertEquals(1, asked, "the play waited for the warm-up's answer")
    }

    @Test
    fun `long podcasts stream without being saved to disk`() = runTest {
        val player = FakePlayer()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var kept = 0
        val cache = object : StreamCache {
            override fun offline(key: String): ResolvedStream? = null
            override fun keep(key: String, stream: ResolvedStream, playing: Boolean) { kept++ }
            override fun forget(key: String) = Unit
        }
        val playback = NativePlayback(player, scope, cache = cache) { _, _ ->
            ResolvedStream("https://example.test/podcast", durationMs = 90 * 60_000L)
        }
        val podcast = track.copy(durationMs = 90 * 60_000L)

        playback.prepare(podcast)
        playback.play(podcast, 0)

        assertEquals(0, kept)
        assertNull(player.played?.cacheKey)
        assertEquals("https://example.test/podcast", player.played?.url)
        scope.cancel()
    }

    @Test
    fun `ordinary songs remain cacheable and existing offline files remain playable`() = runTest {
        val player = FakePlayer()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var kept = 0
        var cached: ResolvedStream? = null
        val cache = object : StreamCache {
            override fun offline(key: String): ResolvedStream? = cached
            override fun keep(key: String, stream: ResolvedStream, playing: Boolean) { kept++ }
            override fun forget(key: String) = Unit
        }
        val playback = NativePlayback(player, scope, cache = cache) { _, _ ->
            ResolvedStream("https://example.test/song", durationMs = track.durationMs)
        }

        playback.play(track, 0)
        assertEquals(1, kept)
        assertNotNull(player.played?.cacheKey)

        cached = ResolvedStream("/cache/song", cacheKey = track.provider.name.lowercase() + "-" + track.id)
        playback.play(track.copy(durationMs = 90 * 60_000L), 0)
        assertEquals("/cache/song", player.played?.url)
        assertNotNull(player.played?.cacheKey)
        scope.cancel()
    }

    @Test
    fun `resolved address cache evicts old entries after the limit`() = runTest {
        val requests = mutableMapOf<String, Int>()
        var tick = 0L
        val playback = NativePlayback(FakePlayer(), backgroundScope, clock = { ++tick }) { id, _ ->
            requests[id] = (requests[id] ?: 0) + 1
            ResolvedStream("https://example.test/$id")
        }

        for (id in 0 until 33) {
            playback.warm(track.copy(id = "$id", uri = "soundcloud:track:$id"))
        }
        playback.warm(track.copy(id = "32", uri = "soundcloud:track:32"))
        playback.warm(track.copy(id = "0", uri = "soundcloud:track:0"))

        assertEquals(1, requests["32"])
        assertEquals(2, requests["0"], "oldest resolved address should have been evicted")
    }
}
