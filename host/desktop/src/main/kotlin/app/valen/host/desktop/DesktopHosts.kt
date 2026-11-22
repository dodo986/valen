/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import app.valen.host.cdp.CdpBrowser
import app.valen.host.cdp.CdpHost
import app.valen.host.cdp.CdpPageHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI

/**
 * The desktop host: one Chrome with Valen's private profile, one CDP host on the Spotify Web Player
 * tab, one page host per page-based service (YouTube Music, SoundCloud), and the three sign-ins
 * built on them.
 *
 * Everything is attached lazily: an app that starts with Chrome missing still runs, and a provider
 * whose tab is never opened never opens one. [close] stops what this object started.
 */
class DesktopHosts(
    val scope: CoroutineScope,
    val chrome: ChromeLauncher = ChromeLauncher(),
) : SignInTabs, AutoCloseable {

    val cdpUrl: String get() = chrome.cdpUrl

    /**
     * Bumped whenever the browser is replaced (a headless engine restarted as a window for a
     * sign-in). An adapter built against the old browser is stale from that moment.
     */
    val generation = kotlinx.coroutines.flow.MutableStateFlow(0)

    val spotifySignIn = SpotifySignIn(this, scope)
    val ytMusicSignIn = YtMusicSignIn(this, scope)
    val soundCloudSignIn = SoundCloudSignIn(this, scope)

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
                // A live browser can have a dead tab. Invalidate its adapter in both cases.
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
     * Launches (or reuses) Chrome and restores the three account states without service pages.
     * [visible] is for a sign-in: the user has to see the page they log in on.
     */
    suspend fun start(visible: Boolean = false): Boolean {
        if (!browserSession.ensure(visible)) return false
        spotifySignIn.startPolling()
        // Restore account state without loading YouTube Music or SoundCloud pages.
        ytMusicSignIn.startPolling()
        soundCloudSignIn.startPolling()
        return true
    }

    /**
     * Restarts the browser with a window, for a sign-in that a person has to complete. False when
     * no window could be opened; [windowError] then says why.
     */
    override suspend fun ensureVisible(): Boolean = start(visible = true)

    override suspend fun ensureBackground(): Boolean = browserSession.ensure()

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
        spotifyHost?.takeIf { it.isOpen } ?: CdpHost.connect(cdpUrl = cdpUrl).also { host ->
            synchronized(stateGuard) {
                if (browserSession.closed) { host.close(); error("Valen is closing") }
                if (spotifyHost != null) { spotifyHost?.close(); generation.value += 1 }
                spotifyHost = host
            }
        }
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
     * The browser's cookies for [url], read through the Web Player tab that already exists. Only a
     * reachable browser answers: this never launches Chrome, so an account read cannot start one.
     * Null also means no browser is running, which is the same thing to a caller (no session).
     */
    suspend fun cookiesFor(url: String): String? =
        runCatching { spotify().cookieHeader(url) }.getOrNull()

    /**
     * Writes a renewed SoundCloud session back into the browser's cookie jar, through the Web
     * Player tab [cookiesFor] reads it from (cookies are browser-wide). Never launches Chrome: with
     * no browser running there is nothing to write, and the renewed session lives for this run only.
     */
    suspend fun setSoundCloudSession(accessToken: String, refreshToken: String?) {
        runCatching {
            val cookies = buildList {
                add("oauth_token" to accessToken)
                refreshToken?.takeIf { it.isNotBlank() }?.let { add("oauth_refresh_token" to it) }
            }
            spotify().setCookies(cookies, "soundcloud.com")
        }
    }

    /**
     * Recovers the browser before the adapter treats a lost connection as a failure. A start
     * already in progress counts as alive; its generation invalidates the old adapter afterwards.
     */
    suspend fun browserAlive(): Boolean = browserStarting || ensureBackground()

    override fun close() {
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
