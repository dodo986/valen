/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.desktop

import app.valen.core.AudioQuality
import app.valen.core.MusicProvider
import app.valen.core.PlayerSnapshot
import app.valen.core.ProviderEvent
import app.valen.core.ProviderId
import app.valen.core.SessionState
import app.valen.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The adapter handoff when the browser under Spotify is replaced (a sign-in turns the headless
 * engine into a window). Keeping the old adapter means every bridge call times out while the page
 * itself looks fine: signing in and then pressing play did nothing, and said nothing.
 */
class ProviderRecoveryTest {

    /**
     * The wrapper answers for the adapter it wraps, so it passes on every question, those with a
     * default answer included: a default left in place answers "none" for the wrapper instead
     * (Spotify's albums and playlists in search were empty that way while its adapter had them).
     */
    @Test
    fun `the wrapper passes on the catalogue's albums and playlists`() = harness { h ->
        runBlocking {
            h.provider.connect()
            assertEquals(listOf("alb-1"), h.provider.findReleases("night", 5).map { it.id })
            assertEquals(listOf("pl-1"), h.provider.findPlaylists("night", 5).map { it.id })
        }
    }

    @Test
    fun `a health check that recovers Chrome rebuilds the adapter in the same command`() {
        var onProbe: () -> Unit = {}
        harness(alive = { onProbe(); true }, pollMs = 60_000) { h ->
            runBlocking { h.provider.connect() }
            onProbe = { h.browser.value = 1 }
            runBlocking { h.provider.search("test", 1) }
            assertEquals(2, h.builds.size, "used the old page after the health check replaced Chrome")
            assertTrue(h.builds.first().closed)
            assertEquals(SessionState.Connected("someone"), h.provider.session.value)
        }
    }

    @Test
    fun `exhausted browser recovery does not pretend the account signed out`() {
        var alive = true
        harness(alive = { alive }, pollMs = 50, quiet = true) { h ->
            runBlocking { h.provider.connect() }
            alive = false
            runBlocking {
                withTimeout(2_000) { h.provider.session.first { it is SessionState.Unavailable } }
            }
            assertTrue(h.builds.first().closed)
        }
    }

    @Test
    fun `the same browser keeps one adapter`() = harness { h ->
        runBlocking { h.provider.connect() }
        runBlocking { h.provider.connect() }

        assertEquals(1, h.builds.size, "rebuilt without a reason")
        assertFalse(h.builds.single().closed, "the attached adapter was closed")
    }

    @Test
    fun `a replaced browser builds a new adapter and keeps the account`() = harness { h ->
        runBlocking { h.provider.connect() }
        assertEquals(SessionState.Connected("someone"), h.provider.session.value, "signed in")

        // The sign-in replaced the browser: the page the adapter holds no longer exists.
        h.browser.value = 1
        runBlocking { h.provider.connect() }

        assertEquals(2, h.builds.size, "the adapter was not rebuilt")
        assertTrue(h.builds.first().closed, "the stale adapter was kept")
        assertEquals(
            SessionState.Connected("someone"),
            h.provider.session.value,
            "a replaced browser must not turn a signed-in service into Not connected",
        )
    }

    @Test
    fun `a browser that died is rebuilt on the next use`() {
        var alive = true
        harness(alive = { alive }) { h ->
            runBlocking { h.provider.connect() }
            alive = false
            runBlocking { h.provider.connect() }

            assertEquals(2, h.builds.size, "the adapter was not rebuilt after the browser died")
            assertTrue(h.builds.first().closed, "the dead adapter was kept")
        }
    }

    /**
     * A sign-in replaces the headless engine with a windowed one: the port is briefly free and the
     * generation has moved. That is a replacement, not a crash — the poller used to close the good
     * adapter and publish "Spotify's browser was closed" in the middle of the login.
     */
    @Test
    fun `a browser being replaced is not mistaken for a closed one`() {
        var alive = true
        harness(alive = { alive }, pollMs = 50) { h ->
            runBlocking { h.provider.connect() }

            alive = false
            h.browser.value = 1
            runBlocking { delay(500) }

            assertEquals(1, h.builds.size, "the replacement rebuilt the adapter behind the user")
            assertFalse(h.builds.single().closed, "the adapter was closed during the replacement")
            assertEquals(
                SessionState.Connected("someone"),
                h.provider.session.value,
                "a replacement must not look like a lost account",
            )
        }
    }

    /**
     * The same replacement, one step later: the probe itself is what crosses it. A sign-in opens
     * its window while a probe is answering — the port is briefly free (the probe says "gone") and
     * the generation has already moved when the watchdog takes the lock. That used to close the
     * good adapter and publish "Spotify's browser was closed" in the middle of the login.
     */
    @Test
    fun `a replacement that lands during a failed probe is not a dead browser`() {
        val browser = MutableStateFlow(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val builds = mutableListOf<FakeProvider>()
        var probes = 0
        try {
            val provider = RetryingProvider(
                id = ProviderId.SPOTIFY,
                scope = scope,
                ensureHost = { null },
                build = { FakeProvider().also { builds += it } },
                reason = "Spotify did not start",
                browser = browser,
                // The first miss is an ordinary hiccup; the second is the replacement beginning,
                // which moves the generation before the watchdog reaches its lock.
                alive = {
                    probes++
                    if (probes >= 2) browser.value = browser.value + 1
                    false
                },
                quiet = true,
                pollMs = 50,
            )
            runBlocking {
                provider.connect()
                delay(500)
            }

            assertEquals(1, builds.size, "the adapter was rebuilt behind the user")
            assertFalse(builds.single().closed, "a replacement during a probe was taken for a death")
            assertEquals(
                SessionState.Connected("someone"),
                provider.session.value,
                "a replacement must not look like a lost account",
            )
        } finally {
            scope.cancel()
        }
    }

    /** The other half: a browser that is gone for good is noticed, and the song is not left playing. */
    @Test
    fun `a browser that dies without a replacement is noticed`() {
        var alive = true
        harness(alive = { alive }, pollMs = 50, quiet = false) { h ->
            runBlocking { h.provider.connect() }

            alive = false
            runBlocking { delay(500) }

            assertTrue(h.builds.single().closed, "the adapter of a dead browser was kept")
            assertEquals(
                SessionState.Unavailable("Spotify's browser was closed"),
                h.provider.session.value,
                "the session did not say the browser was gone",
            )
        }
    }

    /**
     * A rebuilt page starts at its own defaults, and the volume had already been set by the user
     * before the browser was replaced. It is re-applied on attachment instead of waiting for the
     * next slider move (which may never come).
     */
    @Test
    fun `a rebuilt adapter gets the volume that was already chosen`() {
        harness { h ->
            runBlocking { h.provider.connect() }
            runBlocking { h.provider.setVolume(0.35f) }

            h.browser.value = 1
            runBlocking { h.provider.connect() }
            assertEquals(2, h.builds.size, "the adapter was not rebuilt")
            runBlocking {
                withTimeout(2_000) { h.builds[1].appliedVolume.first { it == 0.35f } }
            }
        }
    }

    /** A volume set before an adapter exists is not lost either: it is applied when one is built. */
    @Test
    fun `a volume set before the adapter is applied on the first build`() {
        harness { h ->
            runBlocking { h.provider.setVolume(0.2f) }
            runBlocking { h.provider.connect() }
            runBlocking {
                withTimeout(2_000) { h.builds.single().appliedVolume.first { it == 0.2f } }
            }
        }
    }

    /**
     * The restore a rebuilt page gets is serialized with a later slider move. It used to run
     * detached: one that started first could finish last and put the *old* volume back on top of
     * the value the user had just chosen. Whichever starts first, the page ends at the newer one.
     */
    @Test
    fun `a slow restore cannot overwrite a newer volume`() {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        harness(prepare = {
            volumeGate = gate
            volumeStarted = started
        }) { h ->
            runBlocking {
                withTimeout(5_000) {
                    h.provider.setVolume(0.1f)
                    h.provider.connect()
                    // The restore now sits inside the adapter's setVolume, holding the lock.
                    started.await()
                    val newer = launch { h.provider.setVolume(0.5f) }
                    delay(100)
                    gate.complete(Unit)
                    newer.join()
                }
            }
            assertEquals(
                0.5f,
                h.builds.single().lastVolume,
                "an older restore overwrote the volume the user chose after it",
            )
        }
    }

    /**
     * A replaced adapter is closed, and nothing of it may reach the wrapper again. Its flows used
     * to keep collecting — a StateFlow never completes — so a closed page could still write its
     * session and its old song over the state the new page owns.
     */
    @Test
    fun `a replaced adapter can no longer publish over the new one`() {
        harness { h ->
            runBlocking { h.provider.connect() }
            val stale = h.builds.single()

            h.browser.value = 1
            runBlocking { h.provider.connect() }
            assertEquals(2, h.builds.size, "the adapter was not rebuilt")

            stale.publish(PlayerSnapshot(track = Track(ProviderId.SPOTIFY, "old", "Old song", "Someone"), isPlaying = true))
            stale.publish(SessionState.Unavailable("the closed page said so"))
            runBlocking { delay(200) }

            assertEquals(null, h.provider.player.value, "the closed adapter's song was shown")
            assertEquals(
                SessionState.Connected("someone"),
                h.provider.session.value,
                "the closed adapter's session was published",
            )
        }
    }

    /** A retry wrapper over fake adapters, with the browser it holds as a flow the test moves. */
    private class Harness(
        alive: suspend () -> Boolean,
        pollMs: Long = 3_000,
        quiet: Boolean = true,
        prepare: FakeProvider.() -> Unit = {},
    ) : AutoCloseable {
        val browser = MutableStateFlow(0)
        val builds = mutableListOf<FakeProvider>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = RetryingProvider(
            id = ProviderId.SPOTIFY,
            scope = scope,
            ensureHost = { null },
            build = { FakeProvider().also { it.prepare(); builds += it } },
            reason = "Spotify did not start",
            browser = browser,
            alive = alive,
            quiet = quiet,
            pollMs = pollMs,
        )

        override fun close() {
            scope.cancel()
        }
    }

    private fun harness(
        alive: suspend () -> Boolean = { true },
        pollMs: Long = 3_000,
        quiet: Boolean = true,
        prepare: FakeProvider.() -> Unit = {},
        body: (Harness) -> Unit,
    ) {
        Harness(alive, pollMs, quiet, prepare).use(body)
    }

    /** One adapter, as the wrapper sees it: a session it reports and a close it can be given. */
    private class FakeProvider : MusicProvider, app.valen.core.ArtistCatalog {
        override val id = ProviderId.SPOTIFY
        override val provider: ProviderId get() = id
        override suspend fun findArtists(query: String, limit: Int): List<app.valen.core.ServiceArtist> = emptyList()
        override suspend fun artist(id: String): app.valen.core.ArtistProfile = error("no artist pages here")
        override suspend fun release(id: String): app.valen.core.ReleaseTracks = error("no releases here")
        override suspend fun findReleases(query: String, limit: Int) =
            listOf(app.valen.core.Release(ProviderId.SPOTIFY, "alb-1", "Headlights", "Night Drive"))
        override suspend fun findPlaylists(query: String, limit: Int) =
            listOf(app.valen.core.ServicePlaylist(ProviderId.SPOTIFY, "pl-1", "Night roads"))
        private val mutableSession = MutableStateFlow<SessionState>(SessionState.Connected("someone"))
        override val session: StateFlow<SessionState> = mutableSession.asStateFlow()
        private val mutablePlayer = MutableStateFlow<PlayerSnapshot?>(null)
        override val player: StateFlow<PlayerSnapshot?> = mutablePlayer.asStateFlow()
        override val events: SharedFlow<ProviderEvent> = MutableSharedFlow()

        /** What a page that is no longer attached would report; a test publishes it to prove it is ignored. */
        fun publish(snapshot: PlayerSnapshot) { mutablePlayer.value = snapshot }

        fun publish(state: SessionState) { mutableSession.value = state }

        @Volatile var closed = false
            private set

        /** The last volume this adapter was given, for the re-apply-on-attach check. */
        val appliedVolume = MutableStateFlow<Float?>(null)

        /** Held open by a test that wants the first volume call to sit in the adapter. */
        @Volatile var volumeGate: CompletableDeferred<Unit>? = null

        /** Completes when the first volume call arrived, before it is applied. */
        @Volatile var volumeStarted: CompletableDeferred<Unit>? = null

        private val volumeLog = mutableListOf<Float>()

        /** The volume of the last call this adapter completed, in completion order. */
        val lastVolume: Float get() = synchronized(volumeLog) { volumeLog.last() }

        override suspend fun connect() {}
        override suspend fun disconnect() {}
        override suspend fun search(query: String, limit: Int): List<Track> = emptyList()
        override suspend fun play(track: Track, startMs: Long) {}
        override suspend fun pause() {}
        override suspend fun resume() {}
        override suspend fun seek(positionMs: Long) {}
        override suspend fun setVolume(volume: Float) {
            volumeStarted?.let { started -> volumeStarted = null; started.complete(Unit) }
            volumeGate?.let { gate -> volumeGate = null; gate.await() }
            // Recorded when the call *completes*, as a real sink applies it: the order in which
            // the wrapper lets calls finish is the behaviour under test. Recording on arrival made
            // the test pass even when a stale restore finished after a newer setting.
            synchronized(volumeLog) { volumeLog += volume }
            appliedVolume.value = volume
        }
        override suspend fun setQuality(quality: AudioQuality) {}
        override suspend fun close() {
            closed = true
        }
    }
}
