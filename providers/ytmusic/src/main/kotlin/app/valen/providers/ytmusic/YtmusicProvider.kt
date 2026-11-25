/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.ytmusic

import app.myspoti.api.Image
import app.myspoti.api.Track as CatalogTrack
import app.myspoti.web.PageBridge
import app.myspoti.web.PageHost
import app.myspoti.web.PageState
import app.myspoti.web.SessionCookies
import app.myspoti.ytmusic.InnerTube
import app.myspoti.ytmusic.Ytm
import app.myspoti.ytmusic.YtmPage
import app.myspoti.ytmusic.YtmParse
import app.valen.core.AudioQuality
import app.valen.core.MusicProvider
import app.valen.core.PlayerSnapshot
import app.valen.core.ProviderEvent
import app.valen.core.ProviderId
import app.valen.core.SoundWebAudio
import app.valen.core.SoundEffects
import app.valen.core.EffectSupport
import app.valen.core.ProviderSignIn
import app.valen.core.SessionState
import app.valen.core.Track
import app.valen.core.largestArtworkUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap

/**
 * YouTube Music as a Valen provider: the official web app in a hidden page ([host], the page script
 * `ytm-page.js` injected by [PageBridge]) plays and reports, the web app's own API ([InnerTube])
 * searches. The page is audible (Valen's own player) and takes commands signed out as well as in;
 * the API's reads are made on the user's session when it has one, signed out otherwise.
 *
 * The queue is the app's: [play] replaces the page's song. Titles and artwork come from the search
 * answer that produced the song ([known]) or, for a song first met on the page, from the watch-next
 * list ([InnerTube.next]); until that answers, the id with the `i.ytimg.com` cover stands in.
 *
 * [cookies]: the `Cookie` header of the user's YouTube Music sign-in, read when asked (never stored
 * here); the constructor with a [SessionCookies] reads it from the host's browser.
 */
class YtmusicProvider(
    private val host: PageHost,
    private val signIn: ProviderSignIn,
    private val scope: CoroutineScope,
    private val cookies: suspend () -> String?,
    http: OkHttpClient = OkHttpClient(),
    private val now: () -> Long = System::currentTimeMillis,
) : MusicProvider {

    /**
     * Valen's page: it is ready once its script answers, signed in or not (the app plays YouTube
     * Music signed out; the account only adds the library and the history).
     */
    constructor(
        host: PageHost,
        signIn: ProviderSignIn,
        scope: CoroutineScope,
        session: SessionCookies,
        http: OkHttpClient = OkHttpClient(),
    ) : this(host, signIn, scope, { session.header(Ytm.ORIGIN) }, http)

    override val id: ProviderId = ProviderId.YOUTUBE_MUSIC
    override val session: StateFlow<SessionState> get() = signIn.state

    /** The page's script, injected after every load; its messages come back here. */
    // The page's script, and Valen's sound engine beside it (injected with it after every load).
    val bridge: PageBridge = PageBridge(host, YtmPage.SCRIPT + "\n;" + SoundWebAudio.SCRIPT, scope)

    /** The effects last asked for, put on again whenever a page comes up. */
    @Volatile private var effects: SoundEffects = SoundEffects.FLAT
    val page: YtmPage = YtmPage(host, bridge, scope, wallClock = now, requireSignIn = false)

    private val api: InnerTube = InnerTube(
        http,
        cookies = cookies,
        clientVersion = { page.pageInfo("clientVersion") ?: InnerTube.DEFAULT_CLIENT_VERSION },
        language = { page.pageInfo("hl") ?: "en" },
        country = { page.pageInfo("gl") ?: "US" },
    )

    private val _player = MutableStateFlow<PlayerSnapshot?>(null)
    override val player: StateFlow<PlayerSnapshot?> = _player

    private val _events = MutableSharedFlow<ProviderEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: SharedFlow<ProviderEvent> = _events

    /** Songs by video id: the search answers' rows, the watch-next rows, and the tracks [play] was given. */
    private val known = ConcurrentHashMap<String, Track>()
    private val resolving = ConcurrentHashMap.newKeySet<String>()

    private var currentId: String? = null
    private var volume = 1f
    private var last: PageState? = null
    /** The song whose end was already reported ([ProviderEvent.TrackEnded] is emitted once). */
    private var endedFor: String? = null
    private var lastError: String? = null

    init {
        scope.launch {
            page.state.collect { s ->
                val prev = last
                last = s
                if (s == null) {
                    _player.value = null
                    return@collect
                }
                // A new song on the page starts at the page's own volume: put the app's on it again.
                if (prev?.trackId != s.trackId) scope.launch { applyVolume() }
                ended(prev, s)
                _player.value = snapshot(s)
            }
        }
        // The volume is the app's, not the page's: re-applied when a page comes up.
        scope.launch { bridge.ready.collect { if (it) { applyVolume(); applyEffects() } } }
    }

    // ── Session ──

    override suspend fun connect() = signIn.connect()

    override suspend fun disconnect() = signIn.disconnect()

    // ── Search (signed out too) ──

    override suspend fun search(query: String, limit: Int): List<Track> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val found = YtmParse.search(api.search(query.trim()))
            .filterIsInstance<YtmParse.Item.Song>()
            .mapNotNull { it.track.toCore() }
            .distinctBy { it.id }
            .take(limit)
        found.forEach { known[it.id] = it }
        return found
    }

    // ── Playback ──

    override suspend fun play(track: Track, startMs: Long) {
        val videoId = videoIdOf(track) ?: throw IllegalArgumentException("not a YouTube Music song: ${track.id}")
        known[videoId] = track.copy(provider = ProviderId.YOUTUBE_MUSIC, id = videoId)
        currentId = videoId
        endedFor = null
        lastError = null
        try {
            // A hidden tab defers media loading; the play would never start.
            runCatching { host.activate() }
            page.play(videoId) { startMs }
            applyVolume()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(e.message ?: "the page did not play ${track.title}")
        }
    }

    override suspend fun pause() = command("pause") { page.pause() }

    override suspend fun resume() = command("resume") { page.resume() }

    override suspend fun seek(positionMs: Long) = command("seek") { page.seek(positionMs.coerceAtLeast(0)) }

    /**
     * The web app's player has no remote volume: the video element's own volume is set, never its
     * mute flag. It is put back on each new song the page shows (see [init]).
     */
    override suspend fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        applyVolume()
        refresh()
    }

    /** YouTube Music chooses the stream itself. */
    override suspend fun setQuality(quality: AudioQuality) {}

    /** The web app's video element is routed through Web Audio ([SoundWebAudio]). */
    override val effectSupport: EffectSupport get() = EffectSupport.Full

    override suspend fun setEffects(effects: SoundEffects) {
        this.effects = effects
        applyEffects()
    }

    private suspend fun applyEffects() {
        if (!bridge.ready.value) return
        runCatching { host.evaluate(SoundWebAudio.call(effects)) }
    }

    override suspend fun close() {
        runCatching { page.release() }
        _player.value = null
    }

    /** A page command; a page that refused it is reported, not thrown (the queue keeps its place). */
    private suspend fun command(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(e.message ?: "$name failed")
        }
    }

    private suspend fun applyVolume() {
        val v = volume.coerceIn(0f, 1f)
        try {
            host.evaluate(
                "(() => { const v = $v; document.querySelectorAll('video, audio').forEach((el) => { el.muted = false; el.volume = v; }); return true; })()",
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // No page yet, or one that refused: the next page load puts the volume on again.
        }
    }

    // ── The page's state ──

    /** The page's state as Valen's snapshot; a song it does not know is looked up in the background. */
    private fun snapshot(s: PageState): PlayerSnapshot {
        // An ad is not a song: while one plays, the snapshot keeps the song it interrupts.
        val id = if (s.ad) currentId else s.trackId
        val track = id?.let { known[it] }
        if (!s.ad && id != null && track == null) resolve(id)
        return PlayerSnapshot(
            track = track,
            isPlaying = !s.paused && !s.ad,
            isBuffering = s.buffering,
            positionMs = s.positionMs,
            durationMs = s.durationMs,
            canSeek = !s.ad,
            canSkip = true,
            volume = volume,
            error = lastError,
        )
    }

    private fun refresh() {
        val s = last ?: return
        _player.value = snapshot(s)
    }

    /**
     * The page finished the song: it went from playing to paused at (or past) its length, never a
     * pause of the user's (which lands anywhere else). Reported once per song; a play, a resume or
     * another song arms it again.
     */
    private fun ended(prev: PageState?, s: PageState) {
        val id = s.trackId ?: return
        if (!s.paused || s.ad) {
            endedFor = null
            return
        }
        if (prev == null || prev.paused || prev.trackId != id) return
        if (s.durationMs <= 0) return
        val end = maxOf(prev.positionMs, s.positionMs) >= s.durationMs - END_SLACK_MS
        if (!end) return
        if (currentId != null && id != currentId) return
        if (endedFor == id) return
        endedFor = id
        _events.tryEmit(ProviderEvent.TrackEnded())
    }

    /** Fills [known] from the song's watch-next row (title, artists, album, length) when it is missing. */
    private fun resolve(videoId: String) {
        if (!resolving.add(videoId)) return
        scope.launch {
            try {
                val row = YtmParse.watchNextTrack(videoId, api.next(videoId))
                val track = row?.toCore()
                if (track != null) {
                    known[videoId] = track
                    refresh()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not fatal: the snapshot keeps the id and the standing cover; search will fill it in.
            } finally {
                resolving.remove(videoId)
            }
        }
    }

    private fun failed(message: String) {
        lastError = message
        _events.tryEmit(ProviderEvent.Failed(message))
        refresh()
    }

    // ── Mapping ──

    /** The video id of one of the app's tracks: its `uri` when it has one, else its id. */
    private fun videoIdOf(track: Track): String? =
        listOfNotNull(track.uri?.substringAfterLast(':'), track.id.substringAfterLast(':'))
            .firstOrNull { Ytm.isVideoId(it) }

    /** One of the web app's rows as Valen's track, with the YouTube Music cover as its artwork. */
    private fun CatalogTrack.toCore(): Track? {
        val videoId = uri.substringAfterLast(':').takeIf { Ytm.isVideoId(it) }
            ?: return null
        val picked = (album?.images?.takeIf { it.isNotEmpty() } ?: images).fold<Image, Image?>(null) { best, img ->
            if (best == null || (img.width ?: 0) >= (best.width ?: 0)) img else best
        }
        val artwork = largestArtworkUrl(picked?.url) ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        return Track(
            provider = ProviderId.YOUTUBE_MUSIC,
            id = videoId,
            title = name,
            artist = artists.joinToString(", ") { it.name }.ifBlank { "YouTube Music" },
            album = album?.name?.takeIf { it.isNotBlank() },
            artworkUrl = artwork,
            durationMs = durationMs,
            explicit = explicit,
            uri = "${Ytm.PROVIDER}:track:$videoId",
        )
    }

    private companion object {
        /** How close to its length a playing song must be to count its pause as the song's end. */
        const val END_SLACK_MS = 1_000L
    }
}
