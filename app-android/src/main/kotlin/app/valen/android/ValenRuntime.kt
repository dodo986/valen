/* SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapted from Orpheon (https://github.com/kota-zo/Orpheon), base/host-android, `MyspotiRuntime.kt`.
 * See NOTICE.
 */
package app.valen.android

import android.content.Context
import android.view.ViewGroup
import app.valen.core.PlaybackCoordinator
import app.valen.core.PlaylistCatalog
import app.valen.core.PlaylistLibrary
import app.valen.core.PlaylistManager
import app.valen.core.PlaylistStore
import app.valen.core.ProviderId
import app.valen.core.SessionState
import app.valen.core.ListeningStats
import app.valen.core.ListeningStore
import app.valen.core.SettingsStore
import app.valen.host.android.AndroidPlayback
import app.valen.host.android.AndroidSignIn
import app.valen.host.android.HiddenPageHost
import app.valen.host.android.WebPlayerHost
import app.valen.audio.NativePlayback
import app.valen.audio.SoundcloudAudio
import app.valen.audio.SoundcloudSession
import app.valen.audio.YoutubeAudio
import app.valen.audio.DiskAudioCache
import app.valen.host.android.ExoAudioPlayer
import app.valen.host.android.ExoStreamCache
import app.valen.audio.DiskShelf
import app.valen.core.AudioQuality
import app.valen.core.Track
import app.valen.core.downloadableCopy
import app.valen.core.OfflineList
import app.valen.host.android.DownloadService
import app.valen.providers.native.LocalProvider
import app.valen.providers.native.SoundcloudNativeProvider
import app.valen.providers.native.YtmusicNativeProvider
import app.valen.providers.spotify.SpotifyProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import app.valen.host.android.BrowserIdentity
import app.valen.update.GithubUpdater
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import app.valen.core.LyricsBook
import app.valen.core.ServiceLyrics
import app.valen.providers.lyrics.LyricsSetup
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The app's single runtime: the hidden WebViews, one sign-in per service, the providers (the three
 * services and this phone's own files), the downloads shelf, the one coordinator and the media
 * session behind them. Built on the main thread (a WebView
 * requirement), usually from the application's `onCreate`, and kept for the life of the process so
 * music outlives activities.
 */
object ValenRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var started = false

    lateinit var coordinator: PlaybackCoordinator
        private set

    /** The playlists kept on this device; the Spotify adapter is the only catalogue here. */
    lateinit var playlists: PlaylistManager
        private set

    /**
     * Spotify's Web Player page, when this build has Spotify. Frozen on Android for now
     * (`valen.android.spotify` in gradle.properties): no page, no sign-in, no adapter, so the app
     * lists YouTube Music and SoundCloud alone and a Spotify song plays from another service.
     */
    var webPlayer: WebPlayerHost? = null
        private set

    /** Updates from Valen's GitHub releases; the install receiver reports back to it. */
    var updater: GithubUpdater? = null
        private set

    /** Playlists downloaded to the phone (`Music/Valen`); null when that could not be set up. */
    var shelf: DiskShelf? = null
        private set

    private val hidden = LinkedHashMap<ProviderId, HiddenPageHost>()
    private val signIns = LinkedHashMap<ProviderId, AndroidSignIn>()

    val isStarted: Boolean get() = started

    /** The hidden page of a service (YouTube Music, SoundCloud), when it was built. */
    fun hiddenHost(id: ProviderId): HiddenPageHost? = hidden[id]

    /** The sign-in of a service: its state is the session the UI shows. */
    fun signIn(id: ProviderId): AndroidSignIn? = signIns[id]

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext

        val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        val settings = SettingsStore(
            read = { File(app.filesDir, "settings.json").takeIf { it.isFile }?.readText() },
            write = { text -> runCatching { File(app.filesDir, "settings.json").writeText(text) } },
        )

        val identity = BrowserIdentity.of(app)
        val ytmHost = HiddenPageHost(
            app, "valen-ytmusic",
            AndroidSignIn.YTM_ORIGIN, AndroidSignIn.YTM_SESSION_URLS, identity,
        )
        val scHost = HiddenPageHost(
            app, "valen-soundcloud",
            AndroidSignIn.SC_ORIGIN, AndroidSignIn.SC_SESSION_URLS, identity,
        )
        hidden[ProviderId.YOUTUBE_MUSIC] = ytmHost
        hidden[ProviderId.SOUNDCLOUD] = scHost

        val ytmSignIn = AndroidSignIn.ytmusic(ytmHost, scope)
        val scSignIn = AndroidSignIn.soundcloud(scHost, scope)
        signIns[ProviderId.YOUTUBE_MUSIC] = ytmSignIn
        signIns[ProviderId.SOUNDCLOUD] = scSignIn

        val spotify = if (BuildConfig.SPOTIFY) spotify(app, http) else null

        // YouTube Music and SoundCloud play natively, as on the desktop: the stream is resolved by
        // the same code (NewPipe's extractor, SoundCloud's API) and played by ExoPlayer, with
        // Valen's effects in its audio chain. Their hidden pages are kept for signing in only; the
        // account's cookies come from them (reading a cookie loads no page).
        val youtube = YoutubeAudio(http)
        val soundcloudAudio = SoundcloudAudio(
            http,
            // A renewed session goes back into the hidden page's cookie jar: SoundCloud's access
            // token now lasts a day, and the refresh token that renews it must survive a restart.
            persist = { session ->
                scHost.setSessionCookies(
                    AndroidSignIn.SC_ORIGIN,
                    buildList {
                        add("oauth_token" to session.accessToken)
                        session.refreshToken?.takeIf { it.isNotBlank() }?.let { add("oauth_refresh_token" to it) }
                    },
                )
            },
        )
        // Songs kept on disk for both, one cache (ExoPlayer allows one per folder).
        val songs = ExoStreamCache(app, DiskAudioCache.budget(File(app.cacheDir, "songs"), SONG_CACHE_BYTES), http, scope)
        val ytmusic = YtmusicNativeProvider(
            ytmSignIn,
            NativePlayback(ExoAudioPlayer(app, scope, http, songs), scope, cache = songs) { id, quality -> youtube.resolve(id, quality) },
            scope,
            cookies = { ytmHost.header(AndroidSignIn.YTM_ORIGIN) },
        )
        val soundcloud = SoundcloudNativeProvider(
            scSignIn,
            NativePlayback(ExoAudioPlayer(app, scope, http, songs), scope, cache = songs) { id, quality -> soundcloudAudio.resolve(id, quality) },
            soundcloudAudio,
            session = {
                val header = scHost.header(AndroidSignIn.SC_ORIGIN)
                AndroidSignIn.oauthToken(header)?.let { access ->
                    SoundcloudSession(access, AndroidSignIn.oauthRefreshToken(header))
                }
            },
        )
        // Downloads: one song at a time from YouTube Music or SoundCloud (a Spotify song as its copy
        // there), tagged by Valen's own writers, kept in Music/Valen where every player sees them.
        // The shelf works off the main thread: its files are written as the library changes.
        val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val shelf = runCatching {
            DiskShelf(
                root = AndroidStorage.downloads(app),
                indexFile = File(app.filesDir, "downloads.json"),
                scope = io,
                sourceFor = { track ->
                    downloadableCopy(track) { id, query ->
                        when (id) {
                            ProviderId.YOUTUBE_MUSIC -> ytmusic.search(query, 10)
                            ProviderId.SOUNDCLOUD -> soundcloud.search(query, 10)
                            else -> emptyList()
                        }
                    }
                },
                resolve = { copy ->
                    val id = copy.uri?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: copy.id
                    when (copy.provider) {
                        ProviderId.YOUTUBE_MUSIC -> youtube.resolve(id, AudioQuality.HIGH)
                        ProviderId.SOUNDCLOUD -> soundcloudAudio.resolve(id, AudioQuality.HIGH)
                        else -> throw IllegalStateException("${copy.provider.label} songs are not downloaded")
                    }
                },
                finisher = AndroidFinisher(),
                work = File(app.cacheDir, "downloads"),
                onWritten = { AndroidStorage.announce(app, it) },
            )
        }.onFailure { android.util.Log.w("Valen", "downloads unavailable: ${it.message}") }.getOrNull()
        this.shelf = shelf

        // The phone's own songs (and every song the shelf keeps, under its own track) play through
        // one more ExoPlayer, effects included.
        val localSongs = AtomicReference<() -> List<Track>> { emptyList() }
        val local = LocalProvider(
            playback = LocalProvider.playback(ExoAudioPlayer(app, scope, http), scope) { shelf?.fileFor(it) },
            tags = AndroidTags(File(app.cacheDir, "covers")),
            known = { localSongs.get()() },
            musicFolder = AndroidStorage.music(),
            home = AndroidStorage.music().parentFile?.absolutePath ?: "/storage/emulated/0",
        )
        val providers = listOfNotNull(spotify, ytmusic, soundcloud, local)

        // A debug build's name carries "-debug"; the version is the same.
        val version = BuildConfig.VERSION_NAME.removeSuffix("-debug")
        val updater = GithubUpdater(
            current = version,
            repo = BuildConfig.UPDATE_REPO,
            installer = ApkInstaller(app),
            http = http,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            folder = File(app.filesDir, "updates"),
        )
        this.updater = updater

        coordinator = PlaybackCoordinator(
            providers = providers,
            scope = scope,
            settingsStore = settings,
            stats = ListeningStats(
                ListeningStore(
                    read = { File(app.filesDir, "listening.json").takeIf { it.isFile }?.readText() },
                    write = { text -> runCatching { File(app.filesDir, "listening.json").writeText(text) } },
                ),
            ),
            // Lyprio (with LiriQo behind an untimed answer), Spotify and LRCLIB; kept in the cache.
            lyrics = LyricsBook(
                LyricsSetup.sources(
                    http,
                    LyricsSetup.lyprio(env = emptyMap(), builtUrl = BuildConfig.LYPRIO_BASE_URL, builtKey = BuildConfig.LYPRIO_HMAC_KEY),
                    // Spotify's own, for a signed-in account whose Web Player is already up.
                    services = listOfNotNull(spotify?.let { ServiceLyrics("Spotify", it) }),
                ),
                LyricsSetup.store(File(app.cacheDir, "lyrics").toPath()),
            ),
            offline = shelf,
            version = version,
            updates = updater,
        )
        updater.start(coordinator.settings.map { it.updates })
        val library = PlaylistLibrary(
            PlaylistStore(
                read = { File(app.filesDir, "playlists.json").takeIf { it.isFile }?.readText() },
                write = { text -> runCatching { File(app.filesDir, "playlists.json").writeText(text) } },
            ),
            clock = { System.currentTimeMillis() },
        )
        localSongs.set { library.playlists.value.flatMap { it.tracks }.filter { it.provider == ProviderId.LOCAL } }
        // "Offline": every song downloaded or kept whole by the song cache, while there is one.
        OfflineList(coordinator, library, io).start()
        shelf?.let { kept ->
            kept.watch(library.playlists)
            DownloadService.follow(app, scope, kept.state) { id -> library.playlists.value.firstOrNull { it.id == id }?.name }
        }
        playlists = PlaylistManager(
            library = library,
            // Every adapter lists and reads its account's playlists (YouTube Music and SoundCloud
            // on the session their sign-in pages hold), as on the desktop.
            catalogs = providers.filterIsInstance<PlaylistCatalog>(),
            search = { provider, query, limit ->
                coordinator.provider(provider)?.search(query, limit) ?: emptyList()
            },
            // A downloaded song plays from its file whatever its service says.
            playable = { track ->
                coordinator.isKept(track) || (coordinator.provider(track.provider) != null &&
                    (track.provider != ProviderId.SPOTIFY ||
                        coordinator.sessions.value[track.provider] is SessionState.Connected))
            },
            priority = { coordinator.priorityOrder() },
        )

        AndroidPlayback.controls = object : AndroidPlayback.Controls {
            override fun play() {
                scope.launch { if (coordinator.snapshot.value?.isPlaying != true) coordinator.toggle() }
            }

            override fun pause() {
                scope.launch { if (coordinator.snapshot.value?.isPlaying == true) coordinator.toggle() }
            }

            override fun next() {
                scope.launch { coordinator.next() }
            }

            override fun previous() {
                scope.launch { coordinator.previous() }
            }

            override fun seek(positionMs: Long) {
                scope.launch { coordinator.seek(positionMs) }
            }
        }

        // A debug build writes what the app says to the user (failures included) to logcat.
        if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // The Web Player bridge's own lines: each call, its answer or failure, refusals.
            spotify?.api?.bridge?.logger = { line -> android.util.Log.d("ValenSpotify", line) }
            scope.launch { coordinator.notice.collect { if (it != null) android.util.Log.i("ValenNotice", it) } }
        }
        scope.launch {
            // Nothing on show ends the session only when the queue is empty too: a song that is
            // between two states never takes the notification (and the service) away.
            combine(coordinator.snapshot, coordinator.queue) { shown, queue -> shown to queue.isEmpty() }
                .collect { (shown, empty) ->
                    AndroidPlayback.rate = coordinator.currentRate()
                    if (shown != null || empty) AndroidPlayback.setSnapshot(app, shown)
                }
        }
        scope.launch {
            combine(coordinator.queue, coordinator.index) { queue, index -> queue to index }
                .collect { (queue, index) -> AndroidPlayback.setQueue(app, queue, index) }
        }

        // The Web Player loads once an account is signed in (its sign-in check reads cookies, it
        // needs no page), a little after start so the app's first screen comes first. Signed out,
        // it loads only if a Spotify search or play asks for it.
        val page = webPlayer
        val spotifySignIn = signIns[ProviderId.SPOTIFY]
        if (page != null && spotifySignIn != null) {
            scope.launch {
                kotlinx.coroutines.delay(WEB_PLAYER_DELAY_MS)
                spotifySignIn.state.collect { if (it is SessionState.Connected) page.ensureLoaded() }
            }
        }
    }

    /** Spotify's page, its sign-in and its adapter, for a build that has Spotify. */
    private fun spotify(app: Context, http: OkHttpClient): SpotifyProvider {
        val page = WebPlayerHost(app)
        webPlayer = page
        val signIn = AndroidSignIn.spotify(page, scope)
        signIns[ProviderId.SPOTIFY] = signIn
        val spotify = SpotifyProvider(page, signIn, scope, http, wake = { page.ensureLoaded() })
        // The vendored API injects the page script after each load; the host says when.
        page.onPageLoaded = { scope.launch { runCatching { spotify.api.onPageLoaded() } } }
        page.onPageStarting = { runCatching { spotify.api.onPageStarting() } }
        return spotify
    }

    /**
     * Keeps every hidden WebView attached to a window (a WebView that never was runs no media). The
     * activity passes a 1 px container; a page that outlives the activity keeps playing through the
     * foreground service.
     */
    fun attachHidden(container: ViewGroup) {
        if (!started) return
        webPlayer?.let { page -> runCatching { page.attach(container, visible = false) } }
        hidden.values.forEach { runCatching { it.attach(container) } }
    }

    /**
     * The activity's window is going: the pages leave it (the view tree would keep the activity
     * alive), but [AwakeWebView] holds Chromium's detach back, so the music keeps playing.
     */
    fun detachHidden(container: ViewGroup) {
        if (!started) return
        webPlayer?.let { page -> runCatching { page.detach(container) } }
        hidden.values.forEach { runCatching { it.detach(container) } }
    }

    /** The most a phone keeps of songs on disk (about 400 songs). */
    private const val SONG_CACHE_BYTES = 1_536L * 1024 * 1024

    /** How long after start a signed-in account's Web Player is loaded. */
    private const val WEB_PLAYER_DELAY_MS = 4_000L
}
