/* SPDX-License-Identifier: GPL-3.0-only */
// Valen, GPL-3.0. Playback goes through the Orpheon Web Player implementation vendored in
// :libs:spotify (https://github.com/kota-zo/Orpheon); see NOTICE.
package app.valen.providers.spotify

import app.myspoti.api.LibrarySort
import app.myspoti.api.LibraryFilter
import app.valen.core.Lyrics
import app.valen.core.LyricsCatalog
import app.valen.core.LyricsQuery
import app.valen.core.LyricsSkipped
import app.valen.core.ArtistFollows
import app.valen.core.ArtistCatalog
import app.myspoti.api.ItemKind
import app.myspoti.api.NowPlaying
import app.myspoti.api.PlayerState
import app.myspoti.spotify.SpotifyApi
import app.myspoti.spotify.bridge.BridgeHost
import app.valen.core.AudioQuality
import app.valen.core.MusicProvider
import app.valen.core.PlaylistCatalog
import app.valen.core.PlaylistLink
import app.valen.core.PlaylistRef
import app.valen.core.PlayerSnapshot
import app.valen.core.ProviderEvent
import app.valen.core.ProviderId
import app.valen.core.ProviderSignIn
import app.valen.core.ReadPlaylist
import app.valen.core.SessionState
import app.valen.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Spotify behind [MusicProvider], on the vendored Web Player implementation in `:libs:spotify`.
 *
 * [host] runs the official Web Player page (Android WebView or desktop Chrome); sign-in belongs to
 * the platform's [ProviderSignIn]. The adapter plays one track at a time by using its uri as the
 * play context: Valen owns the queue, Spotify never gets one.
 */
class SpotifyProvider(
    private val host: BridgeHost,
    private val signIn: ProviderSignIn,
    scope: CoroutineScope,
    private val http: OkHttpClient = OkHttpClient(),
    /**
     * Asks the host to load the Web Player when it is not up: Android loads it late, on first
     * need, instead of at every start. A no-op where the page is always there.
     */
    private val wake: () -> Unit = {},
) : MusicProvider, PlaylistCatalog, ArtistFollows, ArtistCatalog, LyricsCatalog {

    override val id: ProviderId = ProviderId.SPOTIFY

    /** The vendored API. Hosts call [SpotifyApi.onPageLoaded] after each page load and [SpotifyApi.onPageStarting] when a load starts. */
    val api: SpotifyApi = SpotifyApi(host, scope, http)

    private val catalog = SpotifyPlaylistCatalog(api, http) { awaitReady(BOOT_WAIT_MS) }

    /**
     * Waits for the Web Player to be ready, first waking it when it is not up. A page that has to
     * boot first (late loading, a reopened browser) gets [ms]; a page that is up answers at once.
     */
    private suspend fun awaitReady(ms: Long): Boolean {
        if (api.ready.value) return true
        wake()
        withTimeoutOrNull(ms) { api.ready.first { it } }
        return api.ready.value
    }

    override val provider: ProviderId get() = id

    private val artists = SpotifyArtists({ api }) {
        if (!awaitReady(BOOT_WAIT_MS)) throw IllegalStateException("Spotify is still starting; try again in a moment")
    }

    override suspend fun findArtists(query: String, limit: Int) = artists.find(query, limit)

    override suspend fun artist(id: String) = artists.artist(id)

    override suspend fun release(id: String) = artists.release(id)

    override suspend fun findReleases(query: String, limit: Int) = artists.findReleases(query, limit)

    override suspend fun findPlaylists(query: String, limit: Int) = artists.findPlaylists(query, limit)

    /**
     * The song's words from Spotify's lyrics card, for a signed-in account. Never wakes the Web
     * Player: a page that is not up yet is skipped, and the song is asked about again later.
     */
    override suspend fun serviceLyrics(query: LyricsQuery): Lyrics? {
        if (session.value !is SessionState.Connected) return null
        if (!api.ready.value) throw LyricsSkipped("Spotify's player is not up")
        val id = api.copyOf(query) ?: return null
        return api.extras.lyrics("spotify:track:$id")?.toValen(query.durationMs)
    }

    override suspend fun playlists(): List<PlaylistRef> = catalog.playlists()

    override suspend fun read(link: PlaylistLink): ReadPlaylist = catalog.read(link)

    override val session: StateFlow<SessionState> = signIn.state

    /** The Web Player plays for a signed-in account only. */
    override val playsSignedOut: Boolean get() = false

    /** The artists the account follows, from its library, most recent first. */
    override suspend fun followedArtists(): List<String> {
        awaitReady(BOOT_WAIT_MS)
        return api.library.items(LibraryFilter.ARTISTS, LibrarySort.RECENTS, null, 0, 50).items.map { it.name }
    }

    /** Child of the caller's scope, so [close] stops this adapter's watchers and snapshot flow. */
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + job)

    private val _events = MutableSharedFlow<ProviderEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<ProviderEvent> = _events

    @Volatile private var volume: Float = 1f

    /** When the page's own volume was last set: a play right after it does not ask again. */
    @Volatile private var volumeSetAt = 0L

    /** Set by [pause] and cleared by [play]/[resume]: a pause the user asked for is never a track end. */
    @Volatile private var pausedByUser = false

    override val player: StateFlow<PlayerSnapshot?> = api.player.state
        .map { it?.toSnapshot() }
        .stateIn(ownScope, SharingStarted.Eagerly, null)

    init {
        ownScope.launch { watchPlayback() }
        ownScope.launch { watchSession() }
        // Spotify's own answer when it will not play: Orpheon's wording, shown as it is.
        ownScope.launch {
            api.player.refusedAll.collect { _events.emit(ProviderEvent.Failed("Spotify refused to play this")) }
        }
        ownScope.launch {
            api.player.licencesRefused.collect { _events.emit(ProviderEvent.Failed("Spotify refused to play this")) }
        }
    }

    override suspend fun connect() = signIn.connect()

    override suspend fun disconnect() = signIn.disconnect()

    override suspend fun search(query: String, limit: Int): List<Track> {
        // A search at startup often beat the Web Player's own start: give it a moment instead of
        // refusing with "not ready".
        if (!awaitReady(BOOT_WAIT_MS)) throw IllegalStateException("Spotify is still starting; try again in a moment")
        return api.search.tracks(query, 0, limit).items.mapNotNull { it.toValenTrack() }
    }

    override suspend fun play(track: Track, startMs: Long) {
        val uri = track.uri ?: "spotify:track:${track.id}"
        // A page that was just reopened (its browser was closed and is being restarted) is not
        // ready for a play yet. Waiting here is better than losing the song the user asked for.
        awaitReady(PLAY_WAIT_MS)
        // A hidden Web Player tab never loads the media the play asks for.
        runCatching { host.activate() }
        pausedByUser = false
        // Before the play: the page starts every song at its own level otherwise. Its own answer is
        // slow on an idle page (30 s, the first play of a session), its level is not: never wait,
        // and not twice in a row (the queue sets the volume just before it plays).
        if (System.currentTimeMillis() - volumeSetAt > VOLUME_FRESH_MS) {
            api.player.setVolumeQuickly(volume.toDouble())
            volumeSetAt = System.currentTimeMillis()
        }
        // A track uri is a valid one-song context for the Web Player, which reuses its play call.
        api.player.play(uri, positionMs = startMs.takeIf { it > 0 })
    }

    override suspend fun pause() {
        pausedByUser = true
        api.player.pause()
    }

    override suspend fun resume() {
        pausedByUser = false
        api.player.resume()
    }

    override suspend fun seek(positionMs: Long) = api.player.seek(positionMs)

    override suspend fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        this.volume = v
        // A drag sends these one after another: the page's slow answer is not worth waiting for.
        api.player.setVolumeQuickly(v.toDouble())
        volumeSetAt = System.currentTimeMillis()
    }

    /** Spotify picks the stream's quality itself; the Web Player keeps its own setting. */
    override suspend fun setQuality(quality: AudioQuality) {}

    override suspend fun close() {
        // The vendored API has no close of its own: its work runs in the caller's scope. Stopping this
        // adapter's child scope ends the snapshot flow and both watchers.
        ownScope.cancel()
    }

    // ---- mapping -------------------------------------------------------------------------

    private fun NowPlaying.toCore(): Track = Track(
        provider = ProviderId.SPOTIFY,
        id = uri.substringAfterLast(':'),
        title = name,
        artist = artists.joinToString(", ") { it.name },
        album = album?.name?.takeIf { it.isNotBlank() },
        artworkUrl = imageUrl,
        durationMs = durationMs,
        explicit = explicit,
        uri = uri,
    )

    private fun PlayerState.toSnapshot(): PlayerSnapshot {
        val item = item?.takeIf { it.kind != ItemKind.AD }
        return PlayerSnapshot(
            track = item?.toCore(),
            isPlaying = item != null && !isPaused,
            isBuffering = isBuffering,
            positionMs = item?.let { positionAt(System.currentTimeMillis()) } ?: 0L,
            durationMs = item?.durationMs ?: 0L,
            canSeek = restrictions.canSeek,
            // The adapter plays a one-song context; the queue is Valen's, so a skip is always
            // possible: the coordinator plays the next entry, whatever the page allows itself.
            canSkip = true,
            volume = volume,
        )
    }

    // ---- events --------------------------------------------------------------------------

    /**
     * [ProviderEvent.TrackEnded] when a song that was playing stops at (or past) its end, or gives way
     * to the page's next song. A pause the user asked for is not an end.
     */
    private suspend fun watchPlayback() {
        var previous: PlayerState? = null
        api.player.state.collect { state ->
            val before = previous
            previous = state
            if (before == null || before.isPaused) return@collect
            val item = before.item?.takeIf { it.kind != ItemKind.AD } ?: return@collect
            val stopped = state == null || state.isPaused || state.item?.uri != item.uri
            if (!stopped || pausedByUser) return@collect
            val at = before.positionAt(System.currentTimeMillis())
            val nearEnd = item.durationMs <= 0L || at >= item.durationMs - END_TOLERANCE_MS
            // The song that ended, not the one the page shows now: it may already be on another.
            if (nearEnd) _events.emit(ProviderEvent.TrackEnded(item.toCore().key))
        }
    }

    /**
     * [ProviderEvent.SessionLost] when the page stops being ready for good. A quick page reload also
     * drops readiness, so the loss is only reported once it lasts [LOST_AFTER_MS].
     */
    private suspend fun watchSession() {
        var wasReady = false
        api.ready.collect { ready ->
            if (ready) {
                wasReady = true
                return@collect
            }
            if (!wasReady) return@collect
            delay(LOST_AFTER_MS)
            if (!api.ready.value) {
                wasReady = false
                _events.emit(ProviderEvent.SessionLost)
            }
        }
    }

    private companion object {
        /** How long a search or a library read waits for a Web Player that has to boot first. */
        const val BOOT_WAIT_MS = 25_000L
        /** How long a play waits for it (the song asked for is not dropped for a slow start). */
        const val PLAY_WAIT_MS = 30_000L
        /** A song that stops this close to its end (or past it) is over, not paused. */
        const val END_TOLERANCE_MS = 1_500L
        /** How long the page may stay not-ready (a reload) before the session counts as lost. */
        const val LOST_AFTER_MS = 10_000L
        /** A volume set this recent is not asked for again before a play. */
        const val VOLUME_FRESH_MS = 1_000L
    }
}
