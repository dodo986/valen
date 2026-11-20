/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.desktop

import app.valen.core.Lyrics
import app.valen.core.LyricsCatalog
import app.valen.core.LyricsQuery
import app.valen.core.LyricsSkipped
import app.valen.core.ArtistCatalog
import app.valen.core.ServicePlaylist
import app.valen.core.Release
import app.valen.core.ArtistProfile
import app.valen.core.AudioQuality
import app.valen.core.ReleaseTracks
import app.valen.core.ServiceArtist
import app.valen.core.MusicProvider
import app.valen.core.downloadableCopy
import app.valen.core.PlaylistCatalog
import app.valen.core.PlaylistLink
import app.valen.core.PlaylistRef
import app.valen.core.PlayerSnapshot
import app.valen.core.ProviderEvent
import app.valen.core.ProviderId
import app.valen.core.ReadPlaylist
import app.valen.core.SessionState
import app.valen.audio.DiskAudioCache
import app.valen.audio.FfplayPlayer
import app.valen.audio.NativePlayback
import app.valen.audio.SoundcloudAudio
import app.valen.audio.YoutubeAudio
import app.valen.core.Track
import app.valen.core.PlaylistLibrary
import app.valen.audio.DiskShelf
import app.valen.host.desktop.DesktopHosts
import app.valen.providers.native.FfprobeTags
import app.valen.providers.native.LocalProvider
import java.util.concurrent.atomic.AtomicReference
import app.valen.host.desktop.SoundCloudSignIn
import app.valen.host.desktop.YtMusicSignIn
import app.valen.providers.native.SoundcloudNativeProvider
import app.valen.providers.native.YtmusicNativeProvider
import app.valen.providers.spotify.SpotifyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** The adapters this machine could build, and why the others could not. */
class ProviderSet(
    val providers: List<MusicProvider>,
    val failures: Map<ProviderId, String>,
    /** Playlists downloaded to `~/Music/Valen`; null when the shelf could not be set up. */
    val shelf: DiskShelf? = null,
    private val localSongs: AtomicReference<() -> List<Track>>? = null,
) {
    fun reason(id: ProviderId): String? = failures[id]

    /**
     * Hands the library to what follows it: the device's search looks through its local songs, and
     * the shelf fetches songs added to a kept list.
     */
    fun attach(library: PlaylistLibrary) {
        localSongs?.set { library.playlists.value.flatMap { it.tracks }.filter { it.provider == ProviderId.LOCAL } }
        shelf?.watch(library.playlists)
    }
}

/**
 * The three adapters. Spotify plays through the Web Player in Valen's private Chrome; YouTube
 * Music and SoundCloud play natively, resolved by [YoutubeAudio] and [SoundcloudAudio] and played
 * by FFmpeg, with no browser involved. Chrome is therefore started by Spotify alone (and by a
 * sign-in button), never at boot.
 *
 * A provider that cannot be built (no Chrome for Spotify, a page that will not open) leaves the
 * app running with the others and a sentence explaining it. A failed provider is kept as
 * [RetryingProvider]: pressing Retry starts Chrome again and, when that works, builds the real
 * adapter in place, so the button is not decorative.
 */
suspend fun buildProviders(hosts: DesktopHosts, scope: CoroutineScope): ProviderSet {
    val providers = ArrayList<MusicProvider>(3)
    val failures = LinkedHashMap<ProviderId, String>()
    val youtube = YoutubeAudio()
    val soundcloud = SoundcloudAudio()
    // Songs kept on disk for both native services: a song heard before, or the one up next,
    // plays from the file with no lookup and no network.
    val cacheDir = java.io.File(
        System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.cache"),
        "valen/audio",
    )
    val songs = DiskAudioCache(cacheDir, DiskAudioCache.budget(cacheDir, SONG_CACHE_BYTES))
    val builders = listOf(
        Builder(
            id = ProviderId.SPOTIFY,
            // Chrome is only ever started here, and only when Spotify is actually used.
            ensureHost = { hosts.start(); hosts.startError },
            browser = hosts.generation,
            alive = { hosts.browserAlive() },
            unavailable = "Spotify did not start",
            // Its browser is headless and invisible, so there is nothing alarming to show while
            // it comes up; the tile reads "Not connected" for the moment it takes.
            quiet = true,
            build = { buildSpotify(hosts, scope) },
        ),
        Builder(
            id = ProviderId.YOUTUBE_MUSIC,
            ensureHost = { null },
            unavailable = "YouTube Music did not start",
            build = {
                val playback = NativePlayback(FfplayPlayer(scope), scope, cache = songs) { id, quality -> youtube.resolve(id, quality) }
                YtmusicNativeProvider(
                    hosts.ytMusicSignIn,
                    playback,
                    scope,
                    // The account's playlists live on the page's session; searching and playing stay
                    // signed out, as before. Reading a cookie never launches Chrome.
                    cookies = { hosts.cookiesFor(YtMusicSignIn.ORIGIN) },
                )
            },
        ),
        Builder(
            id = ProviderId.SOUNDCLOUD,
            ensureHost = { null },
            unavailable = "SoundCloud did not start",
            build = {
                val playback = NativePlayback(FfplayPlayer(scope), scope, cache = songs) { id, quality -> soundcloud.resolve(id, quality) }
                SoundcloudNativeProvider(
                    hosts.soundCloudSignIn,
                    playback,
                    soundcloud,
                    oauth = { soundCloudToken(hosts) },
                )
            },
        ),
    )

    for (builder in builders) {
        val id = builder.id
        val chosen: MusicProvider
        if (builder.browser != null) {
            // A browser-backed adapter (Spotify) is always behind the retry wrapper, even when it
            // would build right now: the browser can be replaced under it — a sign-in turns the
            // headless engine into a window — and the wrapper is what notices, drops the page that
            // no longer exists and builds the next adapter in place.
            chosen = RetryingProvider(
                id = id,
                scope = scope,
                ensureHost = builder.ensureHost,
                build = builder.build,
                reason = builder.unavailable,
                browser = builder.browser,
                alive = builder.alive,
                quiet = builder.quiet,
                // The browser-backed service is Spotify, which plays for a signed-in account only.
                playsSignedOut = false,
            )
            providers += chosen
        } else {
            val result = runCatching { builder.build() }
            val provider = result.getOrNull()
            if (provider != null) {
                providers += provider
                chosen = provider
            } else {
                val reason = result.exceptionOrNull()?.message ?: builder.unavailable
                failures[id] = reason
                chosen = RetryingProvider(
                    id = id,
                    scope = scope,
                    ensureHost = builder.ensureHost,
                    build = builder.build,
                    reason = reason,
                    browser = builder.browser,
                    alive = builder.alive,
                    quiet = builder.quiet,
                )
                providers += chosen
            }
        }
        // A song that will not play says why on stderr too, so a run from a terminal is
        // diagnosable without the window (a placeholder that retries included).
        scope.launch {
            chosen.events.collect { event ->
                if (event is ProviderEvent.Failed) System.err.println("valen: ${id.label}: ${event.message}")
            }
        }
    }

    // The files on this device, and the songs the shelf keeps there for any service: both play
    // through one more native player, so a downloaded Spotify song plays offline and signed out.
    val dataDir = java.io.File(
        System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.local/share"),
        "valen",
    )
    val shelf = runCatching {
        DiskShelf(
            root = DiskShelf.defaultRoot(),
            indexFile = java.io.File(dataDir, "downloads.json"),
            scope = scope,
            sourceFor = { track ->
                downloadableCopy(track) { id, query -> providers.firstOrNull { it.id == id }?.search(query, 10).orEmpty() }
            },
            resolve = { copy ->
                val id = copy.uri?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: copy.id
                when (copy.provider) {
                    ProviderId.YOUTUBE_MUSIC -> youtube.resolve(id, AudioQuality.HIGH)
                    ProviderId.SOUNDCLOUD -> soundcloud.resolve(id, AudioQuality.HIGH)
                    else -> throw IllegalStateException("${copy.provider.label} songs are not downloaded")
                }
            },
        )
    }.onFailure { System.err.println("valen: downloads unavailable: ${it.message}") }.getOrNull()
    val localSongs = AtomicReference<() -> List<Track>> { emptyList() }
    providers += LocalProvider(
        playback = LocalProvider.playback(FfplayPlayer(scope), scope) { shelf?.fileFor(it) },
        tags = FfprobeTags(java.io.File(cacheDir.parentFile, "covers")),
        known = { localSongs.get()() },
        musicFolder = DiskShelf.defaultRoot().parentFile,
    )

    // Spotify's engine is headless, so warming it costs nothing on screen: the service is ready
    // (and its account shown) by the time the window has drawn. A machine without a browser just
    // leaves the tile saying so.
    scope.launch { (providers.firstOrNull { it.id == ProviderId.SPOTIFY } as? RetryingProvider)?.warm() }

    return ProviderSet(providers, failures, shelf, localSongs)
}

/** One adapter to build, and how to get its host back if the first attempt failed. */
private class Builder(
    val id: ProviderId,
    val ensureHost: suspend () -> String?,
    val unavailable: String,
    val build: suspend () -> MusicProvider,
    /** Set when the adapter holds the browser: a replacement makes it stale. */
    val browser: kotlinx.coroutines.flow.StateFlow<Int>? = null,
    /** Whether the host behind the adapter still answers; false makes the next use rebuild it. */
    val alive: suspend () -> Boolean = { true },
    /** Start from "not connected" rather than an error, for a host that is started silently. */
    val quiet: Boolean = false,
)

/**
 * The SoundCloud account's OAuth token, taken from the browser's cookie jar when one is signed in.
 * Never stored; null when signed out or no browser is running.
 */
private suspend fun soundCloudToken(hosts: DesktopHosts): String? =
    hosts.cookiesFor(SoundCloudSignIn.ORIGIN)
        ?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("oauth_token=") }
        ?.substringAfter('=')
        ?.takeIf { it.isNotBlank() }

/** The Spotify Web Player tab and the bridge the adapter expects after every page load. */
private suspend fun buildSpotify(hosts: DesktopHosts, scope: CoroutineScope): MusicProvider {
    val host = hosts.spotify()
    val provider = SpotifyProvider(host, hosts.spotifySignIn, scope)
    // `VALEN_SPOTIFY_LOG=1` prints the Web Player bridge's own lines: a refusal, a device that
    // never joined, a call that timed out. Off in a normal run.
    if (System.getenv("VALEN_SPOTIFY_LOG") != null) {
        provider.api.bridge.logger = { line -> System.err.println("spotify: $line") }
    }
    scope.launch {
        // A browser that was just started (or restarted after a crash) may still be loading the
        // Web Player when the first injection runs, and a script injected into a page that then
        // navigates is wiped. Keep injecting until the page announces itself; a page that never
        // does (sign-in refused, no network) stops after a minute and waits for load events.
        var tries = 0
        while (isActive && !provider.api.ready.value && tries < 40) {
            if (tries > 0) delay(1_500)
            tries++
            runCatching { provider.api.onPageLoaded() }
            withTimeoutOrNull(3_000) { provider.api.ready.first { it } }
        }
        while (isActive) {
            runCatching { host.awaitLoad() }.onFailure { break }
            runCatching { provider.api.onPageLoaded() }
        }
    }
    return provider
}

/**
 * A provider whose adapter could not be built yet. Its session says why; a search or a play asks it
 * to try again, and a success replaces the placeholder with the real adapter (which then drives the
 * same flows the coordinator already collects).
 */
internal class RetryingProvider(
    override val id: ProviderId,
    private val scope: CoroutineScope,
    private val ensureHost: suspend () -> String?,
    private val build: suspend () -> MusicProvider,
    reason: String,
    private val browser: StateFlow<Int>? = null,
    private val alive: suspend () -> Boolean = { true },
    private val quiet: Boolean = false,
    /** How often the browser behind the adapter is checked once it is attached. */
    private val pollMs: Long = 3_000,
    /** Whether the service behind it plays with no account (Spotify does not). */
    override val playsSignedOut: Boolean = true,
) : MusicProvider, PlaylistCatalog, ArtistCatalog, LyricsCatalog {

    override val provider: ProviderId get() = id

    private val _session = MutableStateFlow<SessionState>(
        if (quiet) SessionState.SignedOut else SessionState.Unavailable(reason),
    )
    override val session: StateFlow<SessionState> = _session.asStateFlow()

    private val _player = MutableStateFlow<PlayerSnapshot?>(null)
    override val player: StateFlow<PlayerSnapshot?> = _player.asStateFlow()

    private val _events = MutableSharedFlow<ProviderEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<ProviderEvent> = _events

    /** Builds the real adapter now; used to warm a silent host at start-up. */
    suspend fun warm() {
        ready()
    }

    @Volatile private var delegate: MusicProvider? = null

    /** The browser generation [delegate] was built against; null when nothing is attached. */
    @Volatile private var attachedTo: Int? = null

    /** What to restart if the browser dies under a song: pressing play brings it back. */
    @Volatile private var lastTrack: Track? = null
    @Volatile private var lastPosition: Long = 0L

    /** The last volume and quality asked for, so a rebuilt adapter starts where the old one was. */
    @Volatile private var volume: Float? = null
    @Volatile private var quality: AudioQuality? = null

    /** The collectors of the attached adapter; cancelled when it is dropped (see [detach]). */
    private var collectors: List<Job> = emptyList()

    private val lock = Mutex()

    /**
     * Serializes the volume and quality the wrapper remembers with the restore that a rebuilt
     * adapter is given. Without it a restore launched for an old page could land after the user
     * had already moved the slider, putting the older value back on the new page.
     */
    private val settingsLock = Mutex()

    init {
        // A browser can also die without a replacement: the window closed, the engine crashed.
        // Without this the app would keep showing a song that stopped minutes ago. A browser that
        // was *replaced* (a sign-in turns the headless engine into a window) is noticed by
        // [ready], which keeps the account the sign-in already knows and only rebuilds the page.
        if (browser != null) {
            val generations = browser
            scope.launch {
                var misses = 0
                while (isActive) {
                    delay(pollMs)
                    val current = delegate
                    if (current == null) {
                        misses = 0
                        continue
                    }
                    // The adapter was built against a browser that no longer exists (a sign-in
                    // replaced the headless engine with a window). That is not a crash: [ready]
                    // rebuilds on the new page, and the sign-in's poller keeps the account. Closing
                    // it here published "the browser was closed" in the middle of a login.
                    if (attachedTo != generations.value) {
                        misses = 0
                        continue
                    }
                    if (alive()) {
                        misses = 0
                        continue
                    }
                    // One failed probe can be a hiccup; two in a row is a browser that is gone.
                    if (++misses < 2) continue
                    misses = 0
                    lock.withLock {
                        if (delegate !== current) return@withLock
                        // The browser can be replaced between the probe and this lock (a sign-in
                        // opening its window bumps the generation while the port is briefly free).
                        // That is not a death: leave the adapter for [ready] to rebuild on the new
                        // page instead of closing it and reporting the browser as gone.
                        if (attachedTo != generations.value) return@withLock
                        runCatching { current.close() }
                        detach()
                        delegate = null
                        attachedTo = null
                        _player.value = _player.value?.copy(isPlaying = false, isBuffering = false)
                        _session.value = SessionState.Unavailable("${id.label}'s browser was closed")
                        _events.tryEmit(
                            ProviderEvent.Failed("${id.label}'s browser was closed. Press play to reopen it."),
                        )
                    }
                }
            }
        }
    }

    private fun reasonNow(): String =
        (_session.value as? SessionState.Unavailable)?.reason ?: "${id.label} is not available"

    /** The adapter, built on first use; null (with [session] saying why) when it still cannot be. */
    private suspend fun ready(): MusicProvider? {
        val current = delegate
        if (current != null) {
            // A browser replaced under the adapter — a sign-in turned the headless engine into a
            // window — leaves it talking to a page that no longer exists, while Chrome answers
            // fine. `alive()` cannot see that; the generation can. Either way the adapter is built
            // again, but a replacement keeps the account: only the page is gone.
            // The health check may silently restart Chrome and advance its generation.
            val hostAlive = alive()
            val replaced = browser != null && attachedTo != browser.value
            if (!replaced && hostAlive) return current
            // The browser behind Spotify was closed, crashed or replaced: forget the adapter and
            // start it again rather than failing every later play.
            lock.withLock {
                if (delegate === current) {
                    runCatching { current.close() }
                    detach()
                    delegate = null
                    attachedTo = null
                    _player.value = null
                    // The sign-in flow this adapter holds keeps polling, and [session] was copied
                    // from it: a replacement must not turn a signed-in service into "Not connected".
                    if (!replaced) _session.value = SessionState.Unavailable("${id.label}'s browser was closed")
                }
            }
        }
        return lock.withLock {
            delegate?.let { return it }
            ensureHost()?.let { hostError ->
                _session.value = SessionState.Unavailable(hostError)
                return null
            }
            val built = runCatching { build() }.getOrElse {
                _session.value = SessionState.Unavailable(it.message ?: "${id.label} did not start")
                return null
            }
            attach(built)
            built
        }
    }

    /** Stops the flows of the adapter being dropped, so a closed page cannot publish over a new one. */
    private fun detach() {
        collectors.forEach { it.cancel() }
        collectors = emptyList()
    }

    private fun attach(built: MusicProvider) {
        // A rebuilt adapter inherits nothing from the old one: its collectors would otherwise keep
        // running (a StateFlow never completes) and go on writing the *closed* adapter's session
        // and song into state that now belongs to the new page.
        detach()
        delegate = built
        attachedTo = browser?.value
        _session.value = built.session.value
        collectors = listOf(
            scope.launch { built.session.collect { _session.value = it } },
            scope.launch {
                built.player.collect { snapshot ->
                    _player.value = snapshot
                    val track = snapshot?.track
                    if (track != null) {
                        lastTrack = track
                        lastPosition = snapshot.positionMs
                    }
                }
            },
            scope.launch { built.events.collect { _events.emit(it) } },
        )
        // A rebuilt page starts at its own defaults. The user's choices were made before it
        // existed, so they are re-applied here rather than waiting for the next slider move. The
        // value is re-read under [settingsLock], the same lock a later slider move takes: whichever
        // of the two starts first, the page ends at the value the user has chosen, never at an
        // older one applied on top of it.
        scope.launch {
            settingsLock.withLock {
                val wanted = volume ?: return@withLock
                runCatching { built.setVolume(wanted) }
            }
        }
        scope.launch {
            settingsLock.withLock {
                val wanted = quality ?: return@withLock
                runCatching { built.setQuality(wanted) }
            }
        }
    }

    /**
     * The attached adapter, but only while it is still the one on the live browser. Commands that
     * cannot build a page (pause, seek, a volume move) use this: talking to the page of a replaced
     * browser throws or waits out a timeout, and there is nothing there to pause anyway.
     */
    private fun current(): MusicProvider? {
        val provider = delegate ?: return null
        if (browser != null && attachedTo != browser.value) return null
        return provider
    }

    override suspend fun connect() {
        ready()?.connect()
    }

    override suspend fun disconnect() {
        delegate?.disconnect()
    }

    override suspend fun search(query: String, limit: Int): List<Track> =
        ready()?.search(query, limit) ?: throw IllegalStateException(reasonNow())

    /**
     * Playlist reads go through the same [ready] as everything else: for Spotify that builds the
     * adapter on the page that exists now (a replacement under it is noticed here, not by a stale
     * API object). An adapter that cannot read playlists says so instead of an empty list.
     */
    override suspend fun playlists(): List<PlaylistRef> = catalog().playlists()

    override suspend fun read(link: PlaylistLink): ReadPlaylist = catalog().read(link)

    override suspend fun findArtists(query: String, limit: Int): List<ServiceArtist> = artists().findArtists(query, limit)

    override suspend fun artist(id: String): ArtistProfile = artists().artist(id)

    override suspend fun release(id: String): ReleaseTracks = artists().release(id)

    // Every catalogue question is passed on, defaults included: one left to the interface's default
    // answered "none" for Spotify while its adapter could answer (albums and playlists in search).
    override suspend fun findReleases(query: String, limit: Int): List<Release> = artists().findReleases(query, limit)

    override suspend fun findPlaylists(query: String, limit: Int): List<ServicePlaylist> = artists().findPlaylists(query, limit)

    /**
     * The service's own lyrics, from the adapter already attached and never from one built for
     * it: a lyrics lookup must not start a browser. Signed out is "none"; not up yet is skipped.
     */
    override suspend fun serviceLyrics(query: LyricsQuery): Lyrics? {
        if (session.value !is SessionState.Connected) return null
        val current = delegate ?: throw LyricsSkipped("${id.label} is not up")
        return (current as? LyricsCatalog)?.serviceLyrics(query)
    }

    private suspend fun artists(): ArtistCatalog {
        val provider = ready() ?: throw IllegalStateException(reasonNow())
        return provider as? ArtistCatalog
            ?: throw IllegalStateException("${id.label} artist pages are not available here")
    }

    private suspend fun catalog(): PlaylistCatalog {
        val provider = ready() ?: throw IllegalStateException(reasonNow())
        return provider as? PlaylistCatalog
            ?: throw IllegalStateException("Reading ${id.label} playlists is not available here")
    }

    override suspend fun play(track: Track, startMs: Long) {
        val provider = ready() ?: throw IllegalStateException(reasonNow())
        provider.play(track, startMs)
    }

    override suspend fun pause() {
        current()?.pause()
    }

    override suspend fun resume() {
        val before = delegate
        val provider = ready() ?: run {
            // Silence here used to be the whole bug: MPRIS said "play" and nothing happened,
            // anywhere. The reason the adapter could not be built is the answer.
            _events.tryEmit(ProviderEvent.Failed(reasonNow()))
            return
        }
        val track = lastTrack
        // `ready()` had to build a new adapter (its browser died): the new page has no song, so
        // "resume" means starting the last one again where it stopped. Its own state still names
        // the old song, which is why this is decided by identity and not by what it reports.
        if (track != null && provider !== before) {
            runCatching { provider.play(track, lastPosition) }
                .onFailure {
                    _events.tryEmit(ProviderEvent.Failed(it.message ?: "${id.label} could not resume"))
                }
            return
        }
        provider.resume()
    }

    override suspend fun seek(positionMs: Long) {
        current()?.seek(positionMs)
    }

    override suspend fun setVolume(volume: Float) {
        settingsLock.withLock {
            this.volume = volume
            current()?.setVolume(volume)
        }
    }

    override suspend fun setQuality(quality: AudioQuality) {
        settingsLock.withLock {
            this.quality = quality
            current()?.setQuality(quality)
        }
    }

    override suspend fun close() {
        delegate?.close()
        detach()
    }
}

/** The most the desktop keeps of songs on disk (about 750 songs). */
private const val SONG_CACHE_BYTES = 3L * 1024 * 1024 * 1024
