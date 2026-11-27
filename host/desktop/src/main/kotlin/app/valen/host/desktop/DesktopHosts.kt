/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import app.valen.core.SignInPrompt
import app.valen.host.cdp.CdpBrowser
import app.valen.host.cdp.CdpHost
import app.valen.host.cdp.CdpPageHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

/**
 * The desktop host:
 * - [sessions] + [cookieReader] + [externalSignIn] handle external-browser login and session
 *   persistence for all three services (zero Chromium dependency for YTM & SoundCloud, and no
 *   in-app Spotify sign-in).
 * - [chrome] is Valen's headless bundled playback engine for Spotify's Web Player alone.
 */
class DesktopHosts(
    val scope: CoroutineScope,
    val chrome: ChromeLauncher = ChromeLauncher(),
    val sessions: DesktopSessionStore = DesktopSessionStore(),
    val cookieReader: BrowserCookieReader = BrowserCookieReader(),
) : SignInTabs, AutoCloseable {

    val cdpUrl: String get() = chrome.cdpUrl

    /**
     * Bumped whenever the browser is replaced. An adapter built against the old browser is stale
     * from that moment.
     */
    val generation = kotlinx.coroutines.flow.MutableStateFlow(0)

    private val externalSignIn = ExternalSignIn(sessions, cookieReader, scope)

    /** Active external-browser sign-in prompt, observed by the UI shell. */
    val signInPrompt: StateFlow<SignInPrompt?> get() = externalSignIn.prompt

    val spotifySignIn = SpotifySignIn(this, scope, external = externalSignIn)
    val ytMusicSignIn = YtMusicSignIn(this, scope, external = externalSignIn)
    val soundCloudSignIn = SoundCloudSignIn(this, scope, external = externalSignIn)

    private val lock = Mutex()
    private val stateGuard = Any()
    @Volatile private var spotifyHost: CdpHost? = null
    @Volatile private var ytMusicHost: CdpPageHost? = null
    @Volatile private var soundCloudHost: CdpPageHost? = null

    private val browserSession = BrowserSession(
        start = { visible ->
            val before = chrome.launchCount
            val ok = chrome.start(visible)
            if (ok && (chrome.launchCount != before || spotifyHost?.isOpen == false)) {
                dropHosts()
                generation.value += 1
            }
            ok
        },
        stop = { closeHosts(); chrome.close() },
        reportFailure = {
            System.err.println("valen: browser recovery failed three times; press Retry: ${chrome.lastError}")
        },
    )

    /**
     * Launches (or reuses) the headless Spotify Chrome and starts observing the three sign-ins.
     */
    suspend fun start(visible: Boolean = false): Boolean {
        // Native services read DesktopSessionStore regardless of whether Chrome starts.
        ytMusicSignIn.startPolling()
        soundCloudSignIn.startPolling()
        if (!browserSession.ensure(visible)) {
            spotifySignIn.startPolling()
            return false
        }
        spotifySignIn.startPolling()
        return true
    }

    /** Restores native sign-in states at boot without launching Chrome. */
    suspend fun refreshNativeSessions() {
        ytMusicSignIn.refresh()
        soundCloudSignIn.refresh()
        spotifySignIn.refresh()
    }

    /** Bundled Chrome stays headless for playback; sign-in happens in the user's real browser. */
    override suspend fun ensureVisible(): Boolean = ensureBackground()

    override suspend fun ensureBackground(): Boolean = browserSession.ensure(visible = false)

    override suspend fun cookieNames(url: String): Set<String> =
        CdpBrowser.cookieNames(cdpUrl, URI(url).host)

    override val windowError: String? get() = chrome.lastError

    /** True while a browser is coming up; a probe then has no page to ask. */
    override val browserStarting: Boolean get() = browserSession.starting || chrome.starting

    private suspend fun dropHosts() = lock.withLock { closeHosts() }

    private fun closeHosts() = synchronized(stateGuard) {
        spotifyHost?.close()
        ytMusicHost?.close()
        soundCloudHost?.close()
        spotifyHost = null
        ytMusicHost = null
        soundCloudHost = null
    }

    override suspend fun spotify(): CdpHost = lock.withLock {
        check(!browserSession.closed) { "Valen is closing" }
        val existing = spotifyHost?.takeIf { it.isOpen }
        if (existing != null) return@withLock existing
        val host = CdpHost.connect(cdpUrl = cdpUrl)
        synchronized(stateGuard) {
            if (browserSession.closed) { host.close(); error("Valen is closing") }
            if (spotifyHost != null) { spotifyHost?.close(); generation.value += 1 }
            spotifyHost = host
        }
        // Inject the imported `sp_dc` cookie right as the Web Player attaches.
        sessions.spotifySpDc()?.let { spDc ->
            runCatching {
                val has = "sp_dc" in host.cookieNames("https://open.spotify.com/")
                if (!has) {
                    host.setCookies(listOf("sp_dc" to spDc), ".spotify.com", httpOnly = true)
                    host.navigate("https://open.spotify.com/")
                }
            }
        }
        host
    }

    override suspend fun youtubeMusic(): CdpPageHost = lock.withLock {
        check(!browserSession.closed) { "Valen is closing" }
        ytMusicHost?.takeIf { it.isOpen } ?: CdpPageHost.connect(
            origin = YtMusicSignIn.ORIGIN,
            startUrl = YtMusicSignIn.HOME,
            sessionUrls = YOUTUBE_SESSION_URLS,
            cdpUrl = cdpUrl,
        ).also { host ->
            synchronized(stateGuard) {
                if (browserSession.closed) { host.close(); error("Valen is closing") }
                ytMusicHost?.close()
                ytMusicHost = host
            }
        }
    }

    override suspend fun soundCloud(): CdpPageHost = lock.withLock {
        check(!browserSession.closed) { "Valen is closing" }
        soundCloudHost?.takeIf { it.isOpen } ?: CdpPageHost.connect(
            origin = SoundCloudSignIn.ORIGIN,
            startUrl = SoundCloudSignIn.HOME,
            sessionUrls = SOUNDCLOUD_SESSION_URLS,
            cdpUrl = cdpUrl,
        ).also { host ->
            synchronized(stateGuard) {
                if (browserSession.closed) { host.close(); error("Valen is closing") }
                soundCloudHost?.close()
                soundCloudHost = host
            }
        }
    }

    /** Why Chrome is not there, when [start] returned false. */
    val startError: String? get() = chrome.lastError

    /**
     * Reads cookies from an already-attached Web Player tab without launching Chrome.
     */
    suspend fun cookiesFor(url: String): String? =
        spotifyHost?.takeIf { it.isOpen }?.let { host -> runCatching { host.cookieHeader(url) }.getOrNull() }

    /**
     * Writes a renewed SoundCloud session back into Chrome's cookie jar if Chrome is already open.
     * Primary persistence lives in [sessions].
     */
    suspend fun setSoundCloudSession(accessToken: String, refreshToken: String?) {
        val host = spotifyHost?.takeIf { it.isOpen } ?: return
        runCatching {
            val cookies = buildList {
                add("oauth_token" to accessToken)
                refreshToken?.takeIf { it.isNotBlank() }?.let { add("oauth_refresh_token" to it) }
            }
            host.setCookies(cookies, "soundcloud.com", httpOnly = true)
        }
    }

    /**
     * Recovers the browser before the adapter treats a lost connection as a failure.
     */
    suspend fun browserAlive(): Boolean = browserStarting || ensureBackground()

    override fun close() {
        externalSignIn.close()
        browserSession.close()
        spotifySignIn.close()
        ytMusicSignIn.close()
        soundCloudSignIn.close()
    }

    companion object {
        /** The sites whose cookies hold a YouTube account (all deleted at sign-out). */
        val YOUTUBE_SESSION_URLS = listOf(
            YtMusicSignIn.ORIGIN, "https://www.youtube.com", "https://youtube.com",
            "https://accounts.google.com", "https://www.google.com", "https://google.com",
        )

        /** The sites whose cookies hold a SoundCloud session (all deleted at sign-out). */
        val SOUNDCLOUD_SESSION_URLS = listOf(
            SoundCloudSignIn.ORIGIN, "https://www.soundcloud.com", "https://api.soundcloud.com",
            "https://secure.soundcloud.com",
        )
    }
}
