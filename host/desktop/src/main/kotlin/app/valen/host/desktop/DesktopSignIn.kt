/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import app.valen.core.ProviderId
import app.valen.core.ProviderSignIn
import app.valen.core.SessionState
import app.valen.core.SignInPrompt
import app.valen.host.cdp.CdpHost
import app.valen.host.cdp.CdpPageHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Paths

/**
 * The tabs the desktop sign-ins talk to. [DesktopHosts] owns the Chrome and the CDP hosts; the
 * sign-ins ask for a provider's tab and get the same one every time (attached lazily, so an app
 * started without Chrome still opens).
 */
interface SignInTabs {
    /**
     * Puts a *window* on screen when the browser runs headless. Legacy path kept for tests;
     * production sign-ins open the user's real external browser instead.
     */
    suspend fun ensureVisible(): Boolean

    /** Recovers a closed browser using its existing profile, without opening a login window. */
    suspend fun ensureBackground(): Boolean

    /** Browser-wide account cookies: checking a session must not depend on a login tab existing. */
    suspend fun cookieNames(url: String): Set<String>

    /** Why the last [ensureVisible] could not open a window, when it could not. */
    val windowError: String?

    /**
     * True while the browser is being launched or replaced. A probe during that is meaningless
     * (there is no page yet) and must not be published: it would flash "Offline" over a sign-in
     * that is working.
     */
    val browserStarting: Boolean

    /** The Spotify Web Player tab: the tab the adapter plays through. */
    suspend fun spotify(): CdpHost

    suspend fun youtubeMusic(): CdpPageHost

    suspend fun soundCloud(): CdpPageHost
}

/**
 * Coordinates external-browser sign-in (`xdg-open`) and cookie import (`BrowserCookieReader`,
 * `cookies.txt`, or pasted cookie string) into [DesktopSessionStore].
 *
 * Sign-in never opens an embedded or remote-debugged window; cookies are stored securely in
 * [sessions] and never logged.
 */
class ExternalSignIn(
    val sessions: DesktopSessionStore,
    val cookies: BrowserCookieReader,
    private val scope: CoroutineScope,
    private val openBrowser: suspend (String) -> Boolean = ::openExternalBrowser,
    private val pollMs: Long = 1_500L,
) {
    private val _prompt = MutableStateFlow<SignInPrompt?>(null)
    val prompt: StateFlow<SignInPrompt?> = _prompt.asStateFlow()

    private var activeProvider: ProviderId? = null
    private var pollJob: Job? = null

    /**
     * Starts external sign-in for [provider]. If a valid session is already present in an
     * installed browser profile, it is imported immediately without interrupting the user;
     * otherwise [loginUrl] is opened in the default browser and [SignInPrompt] stays open while
     * polling for cookies or waiting for a file/paste import.
     */
    suspend fun begin(
        provider: ProviderId,
        loginUrl: String,
        cookieHint: String,
        onImported: suspend (BrowserCookieReader.ImportedSession) -> Unit,
        onCancelled: () -> Unit,
    ) {
        cancel(activeProvider)
        activeProvider = provider

        val existing = cookies.readFromBrowsers(provider)
        if (existing != null) {
            onImported(existing)
            activeProvider = null
            _prompt.value = null
            return
        }

        val hasProfiles = withContext(Dispatchers.IO) { cookies.hasReadableBrowser() }
        val opened = openBrowser(loginUrl)
        val initialStatus = when {
            !opened -> "Could not open your browser automatically. Open $loginUrl or import cookies below."
            hasProfiles -> "Finish signing in in your browser. Valen is checking your session…"
            else -> "Finish signing in in your browser, then import cookies.txt or paste your cookie below."
        }

        fun updatePrompt(status: String = initialStatus, error: String? = null) {
            if (activeProvider != provider) return
            _prompt.value = SignInPrompt(
                provider = provider,
                loginUrl = loginUrl,
                status = status,
                hasBrowserProfile = hasProfiles,
                cookieHint = cookieHint,
                error = error,
                onOpenBrowser = { scope.launch { openBrowser(loginUrl) } },
                onCheckNow = {
                    scope.launch {
                        val scan = cookies.scanBrowsers(provider)
                        if (scan.session != null) {
                            finish(provider, scan.session, onImported)
                        } else {
                            val detail = when {
                                scan.decryptionFailedProfiles.isNotEmpty() ->
                                    "Could not decrypt cookies from ${scan.decryptionFailedProfiles.joinToString(", ")} (keyring locked or unsupported store). Import cookies.txt or paste $cookieHint below."
                                scan.checkedProfiles.isNotEmpty() ->
                                    "Checked ${scan.checkedProfiles.joinToString(", ")} — no ${provider.label} session saved on disk yet. Wait a few seconds after signing in, or import cookies.txt / paste below."
                                else ->
                                    "No readable browser cookie stores found. Import cookies.txt or paste $cookieHint below."
                            }
                            updatePrompt(status = initialStatus, error = detail)
                        }
                    }
                },
                onImportFile = { filePath ->
                    scope.launch {
                        val found = cookies.readFromFile(provider, Paths.get(filePath))
                        if (found != null) {
                            finish(provider, found, onImported)
                        } else {
                            updatePrompt(
                                status = initialStatus,
                                error = "That file does not contain a valid ${provider.label} session ($cookieHint).",
                            )
                        }
                    }
                },
                onPasteCookie = { pasted ->
                    scope.launch {
                        val found = cookies.parseInput(provider, pasted)
                        if (found != null) {
                            finish(provider, found, onImported)
                        } else {
                            updatePrompt(
                                status = initialStatus,
                                error = "Could not find $cookieHint in the pasted text.",
                            )
                        }
                    }
                },
                onCancel = {
                    cancel(provider)
                    onCancelled()
                },
            )
        }

        updatePrompt()
        if (hasProfiles) {
            pollJob = scope.launch {
                while (isActive && activeProvider == provider) {
                    delay(pollMs)
                    val found = runCatching { cookies.readFromBrowsers(provider) }.getOrNull()
                    if (found != null) {
                        finish(provider, found, onImported)
                        break
                    }
                }
            }
        }
    }

    private suspend fun finish(
        provider: ProviderId,
        imported: BrowserCookieReader.ImportedSession,
        onImported: suspend (BrowserCookieReader.ImportedSession) -> Unit,
    ) {
        if (activeProvider != provider) return
        pollJob?.cancel()
        pollJob = null
        activeProvider = null
        _prompt.value = null
        onImported(imported)
    }

    fun cancel(provider: ProviderId?) {
        if (provider != null && activeProvider != provider) return
        pollJob?.cancel()
        pollJob = null
        activeProvider = null
        _prompt.value = null
    }

    fun close() {
        cancel(null)
    }
}

/** Opens [url] in the user's default Linux desktop browser (`xdg-open` / `gio open`). */
internal suspend fun openExternalBrowser(url: String): Boolean = withContext(Dispatchers.IO) {
    val pathEnv = System.getenv("PATH").orEmpty()
    val candidates = listOf(
        listOf("xdg-open", url),
        listOf("gio", "open", url),
        listOf("sensible-browser", url),
    )
    for (cmd in candidates) {
        val exe = BrowserCookieReader.findOnPath(cmd[0], pathEnv) ?: continue
        val started = runCatching {
            ProcessBuilder(listOf(exe.toString()) + cmd.drop(1))
                .also { BrowserCookieReader.cleanAppImageEnv(it.environment()) }
                .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.isSuccess
        if (started) return@withContext true
    }
    false
}

/**
 * Sign-in on the desktop.
 *
 * When [external] is present (normal app execution), all three services authenticate in the user's
 * real external browser and store sessions in [DesktopSessionStore] — no in-app sign-in window, and
 * YouTube Music / SoundCloud never touch Chromium at all. The legacy [tabs] path remains intact
 * when [external] is null for existing unit tests.
 */
abstract class DesktopSignIn(
    protected val tabs: SignInTabs,
    protected val scope: CoroutineScope,
    private val pollMs: Long = 4_000,
) : ProviderSignIn {

    protected val mutableState = MutableStateFlow<SessionState>(SessionState.SignedOut)
    override val state: StateFlow<SessionState> = mutableState.asStateFlow()

    private var poller: Job? = null
    private val sessionLock = Mutex()
    @Volatile private var closed = false

    /** Whether [refresh] requires Chrome's background browser to be running. */
    protected open val needsBrowserForProbe: Boolean get() = true

    /** One probe of the service, run while the sign-in polls. */
    protected abstract suspend fun probe(): SessionState

    /** Runs [probe] now and publishes the answer. */
    suspend fun refresh(): SessionState = sessionLock.withLock {
        if (closed) return mutableState.value
        if (needsBrowserForProbe && tabs.browserStarting) return mutableState.value
        val next = try {
            if (needsBrowserForProbe && !tabs.ensureBackground()) {
                throw IOException(tabs.windowError ?: "Browser recovery failed")
            }
            probe()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            SessionState.Unavailable("The browser is not running: press Connect to open it again")
        } catch (e: Exception) {
            SessionState.Unavailable(e.message ?: "The sign-in page is not reachable")
        }
        if (!closed) mutableState.value = next
        mutableState.value
    }

    /** Serialise cookie deletion with probes, so an older probe cannot undo a sign-out. */
    protected suspend fun signOut(clear: suspend () -> Unit) = sessionLock.withLock {
        if (closed) return@withLock
        val next = try {
            if (needsBrowserForProbe) {
                check(tabs.ensureBackground()) { tabs.windowError ?: "Could not reopen the browser to sign out" }
            }
            clear()
            SessionState.SignedOut
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SessionState.Unavailable(e.message ?: "Could not clear the account's cookies; press Retry")
        }
        if (!closed) mutableState.value = next
    }

    /** Polls until [close]; safe to call more than once. */
    fun startPolling() {
        if (closed || poller?.isActive == true) return
        poller = scope.launch {
            while (isActive) {
                refresh()
                delay(pollMs)
            }
        }
    }

    fun stopPolling() {
        poller?.cancel()
        poller = null
    }

    /** Stops observing at app shutdown; the saved session is left intact. */
    fun close() {
        closed = true
        stopPolling()
        mutableState.value = SessionState.SignedOut
    }
}

/**
 * Spotify: external-browser sign-in (`sp_dc` imported into [DesktopSessionStore]) combined with
 * Valen's headless bundled Chrome playback engine.
 */
class SpotifySignIn(
    tabs: SignInTabs,
    scope: CoroutineScope,
    pollMs: Long = 4_000,
    private val external: ExternalSignIn? = null,
) : DesktopSignIn(tabs, scope, pollMs) {

    override val needsBrowserForProbe: Boolean get() = external == null

    override suspend fun connect() {
        if (external != null) {
            mutableState.value = SessionState.Connecting
            external.begin(
                provider = ProviderId.SPOTIFY,
                loginUrl = LOGIN_URL,
                cookieHint = "sp_dc",
                onImported = { imported ->
                    val spDc = (imported as BrowserCookieReader.ImportedSession.Spotify).spDc
                    external.sessions.saveSpotifySpDc(spDc)
                    wasConnected = false
                    injectIntoBrowserIfReady(spDc)
                    refresh()
                },
                onCancelled = {
                    if (mutableState.value is SessionState.Connecting) {
                        scope.launch { refresh() }
                    }
                },
            )
            return
        }
        mutableState.value = SessionState.Connecting
        if (!tabs.ensureVisible()) {
            mutableState.value = SessionState.Unavailable(tabs.windowError ?: "Could not open a browser window to sign in to Spotify")
            return
        }
        val host = runCatching { tabs.spotify() }.getOrElse {
            mutableState.value = SessionState.Unavailable(it.message ?: "Spotify did not open")
            return
        }
        runCatching { host.activate() }
        refresh()
        startPolling()
    }

    override suspend fun disconnect() {
        external?.cancel(ProviderId.SPOTIFY)
        signOut {
            external?.sessions?.clear(ProviderId.SPOTIFY)
            wasConnected = false
            // Best-effort clear in Chrome if Chrome is available (or in legacy mode).
            if (external == null || runCatching { tabs.ensureBackground() }.getOrDefault(false)) {
                runCatching {
                    val host = tabs.spotify()
                    host.clearCookies(SESSION_URLS)
                    host.navigate(HOME)
                }
            }
        }
    }

    override suspend fun probe(): SessionState {
        if (external != null) {
            val storedSpDc = external.sessions.spotifySpDc()
            val browserHasCookie = !tabs.browserStarting &&
                runCatching { ACCOUNT_COOKIE in tabs.cookieNames(HOME) }.getOrDefault(false)
            if (storedSpDc == null && !browserHasCookie) {
                wasConnected = false
                return SessionState.SignedOut
            }
            if (storedSpDc != null && !tabs.browserStarting) {
                injectIntoBrowserIfReady(storedSpDc)
            }
            val name = if (!tabs.browserStarting) {
                runCatching {
                    val host = tabs.spotify()
                    if (!wasConnected) {
                        wasConnected = reloadDone(host.pageKind()) { runCatching { host.reload() }.isSuccess }
                    }
                    readAccountName(host)
                }.getOrNull()
            } else null
            if (name != null) lastKnownName = name
            return SessionState.Connected(name ?: lastKnownName)
        }

        if (ACCOUNT_COOKIE !in tabs.cookieNames(HOME)) {
            wasConnected = false
            return SessionState.SignedOut
        }
        val host = tabs.spotify()
        if (!wasConnected) {
            wasConnected = reloadDone(host.pageKind()) { runCatching { host.reload() }.isSuccess }
        }
        val name = readAccountName(host)
        return SessionState.Connected(name)
    }

    private suspend fun injectIntoBrowserIfReady(spDc: String) {
        if (tabs.browserStarting) return
        runCatching {
            val has = ACCOUNT_COOKIE in tabs.cookieNames(HOME)
            val host = tabs.spotify()
            if (!has) {
                host.setCookies(listOf(ACCOUNT_COOKIE to spDc), ".spotify.com", httpOnly = true)
                host.navigate(HOME)
                wasConnected = true
            }
        }
    }

    private suspend fun readAccountName(host: CdpHost): String? = runCatching {
        host.evaluateValue("document.querySelector('[data-testid=\"user-widget-link\"]')?.getAttribute('aria-label') ?? 'null'")
            ?.let { kotlinx.serialization.json.Json.parseToJsonElement(it).toString() }
            ?.removeSurrounding("\"")
            ?.takeIf { it.isNotBlank() && it != "null" }
    }.getOrNull()

    /** Whether an account was already seen: that transition is what earns the one reload. */
    @Volatile private var wasConnected = false
    @Volatile private var lastKnownName: String? = null

    private companion object {
        const val HOME = "https://open.spotify.com/"
        const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
        const val ACCOUNT_COOKIE = "sp_dc"
        val SESSION_URLS = listOf(
            "https://open.spotify.com", "https://accounts.spotify.com",
            "https://www.spotify.com", "https://spotify.com", "https://api.spotify.com",
        )
    }
}

internal enum class PageKind { LOGIN, PLAYER, UNKNOWN }

internal fun pageKindOf(value: String?): PageKind = when (value) {
    "\"login\"" -> PageKind.LOGIN
    "\"player\"" -> PageKind.PLAYER
    else -> PageKind.UNKNOWN
}

private suspend fun CdpHost.pageKind(): PageKind = pageKindOf(
    runCatching {
        evaluateValue(
            "(function(){try{return document.readyState !== 'complete' ? 'unknown' : (window.Myspoti ? 'player' :" +
                " (location.pathname.indexOf('/login') >= 0" +
                " || location.pathname.indexOf('/preferences') >= 0" +
                " || document.querySelector('[data-testid=\"login-button\"]') !== null ? 'login' : 'player'))}catch(e){return 'unknown'}})()",
        )
    }.getOrNull(),
)

internal suspend fun reloadDone(kind: PageKind, reload: suspend () -> Boolean): Boolean = when (kind) {
    PageKind.LOGIN -> reload()
    PageKind.PLAYER -> true
    PageKind.UNKNOWN -> false
}

/**
 * YouTube Music: external-browser sign-in stored in [DesktopSessionStore] (zero Chromium
 * dependency). Falls back to [tabs] only when [external] is null (unit tests).
 */
class YtMusicSignIn(
    tabs: SignInTabs,
    scope: CoroutineScope,
    pollMs: Long = 4_000,
    private val external: ExternalSignIn? = null,
) : DesktopSignIn(tabs, scope, pollMs) {

    override val needsBrowserForProbe: Boolean get() = external == null

    override suspend fun connect() {
        if (external != null) {
            mutableState.value = SessionState.Connecting
            external.begin(
                provider = ProviderId.YOUTUBE_MUSIC,
                loginUrl = LOGIN_URL,
                cookieHint = "SAPISID or Cookie header",
                onImported = { imported ->
                    val header = (imported as BrowserCookieReader.ImportedSession.YoutubeMusic).cookieHeader
                    external.sessions.saveYoutubeCookies(header)
                    refresh()
                },
                onCancelled = {
                    if (mutableState.value is SessionState.Connecting) {
                        scope.launch { refresh() }
                    }
                },
            )
            return
        }
        mutableState.value = SessionState.Connecting
        if (!tabs.ensureVisible()) {
            mutableState.value = SessionState.Unavailable(tabs.windowError ?: "Could not open a browser window to sign in to YouTube Music")
            return
        }
        val page = runCatching { tabs.youtubeMusic() }.getOrElse {
            mutableState.value = SessionState.Unavailable(it.message ?: "YouTube Music did not open")
            return
        }
        try {
            page.navigate(HOME)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.value = SessionState.Unavailable(e.message ?: "YouTube Music did not open")
            return
        }
        runCatching { page.activate() }
        refresh()
        startPolling()
    }

    override suspend fun disconnect() {
        external?.cancel(ProviderId.YOUTUBE_MUSIC)
        signOut {
            if (external != null) {
                external.sessions.clear(ProviderId.YOUTUBE_MUSIC)
            } else {
                val page = tabs.youtubeMusic()
                page.clear()
                page.navigate(HOME)
            }
        }
    }

    override suspend fun probe(): SessionState {
        if (external != null) {
            val header = external.sessions.youtubeCookies()
            if (!header.isNullOrBlank()) return SessionState.Connected()
            return SessionState.SignedOut
        }
        val names = tabs.cookieNames(ORIGIN)
        return if (names.any { it in SIGN_IN_COOKIES }) SessionState.Connected() else SessionState.SignedOut
    }

    companion object {
        const val ORIGIN = "https://music.youtube.com"
        const val HOME = "$ORIGIN/"
        const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
            "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"
        private val SIGN_IN_COOKIES = setOf("SAPISID", "__Secure-3PAPISID")
    }
}

/**
 * SoundCloud: external-browser sign-in stored in [DesktopSessionStore] (zero Chromium dependency).
 */
class SoundCloudSignIn(
    tabs: SignInTabs,
    scope: CoroutineScope,
    pollMs: Long = 4_000,
    private val external: ExternalSignIn? = null,
) : DesktopSignIn(tabs, scope, pollMs) {

    override val needsBrowserForProbe: Boolean get() = external == null

    override suspend fun connect() {
        if (external != null) {
            mutableState.value = SessionState.Connecting
            external.begin(
                provider = ProviderId.SOUNDCLOUD,
                loginUrl = LOGIN_URL,
                cookieHint = "oauth_token",
                onImported = { imported ->
                    val sc = imported as BrowserCookieReader.ImportedSession.SoundCloud
                    external.sessions.saveSoundCloudTokens(sc.accessToken, sc.refreshToken)
                    refresh()
                },
                onCancelled = {
                    if (mutableState.value is SessionState.Connecting) {
                        scope.launch { refresh() }
                    }
                },
            )
            return
        }
        mutableState.value = SessionState.Connecting
        if (!tabs.ensureVisible()) {
            mutableState.value = SessionState.Unavailable(tabs.windowError ?: "Could not open a browser window to sign in to SoundCloud")
            return
        }
        val page = runCatching { tabs.soundCloud() }.getOrElse {
            mutableState.value = SessionState.Unavailable(it.message ?: "SoundCloud did not open")
            return
        }
        try {
            page.navigate(HOME)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.value = SessionState.Unavailable(e.message ?: "SoundCloud did not open")
            return
        }
        runCatching { page.activate() }
        refresh()
        startPolling()
    }

    override suspend fun disconnect() {
        external?.cancel(ProviderId.SOUNDCLOUD)
        signOut {
            if (external != null) {
                external.sessions.clear(ProviderId.SOUNDCLOUD)
            } else {
                val page = tabs.soundCloud()
                page.clear()
                page.navigate(HOME)
            }
        }
    }

    override suspend fun probe(): SessionState {
        if (external != null) {
            return if (external.sessions.soundCloudTokens() != null) SessionState.Connected() else SessionState.SignedOut
        }
        val names = tabs.cookieNames(ORIGIN)
        return if (SIGN_IN_COOKIE in names) SessionState.Connected() else SessionState.SignedOut
    }

    companion object {
        const val ORIGIN = "https://soundcloud.com"
        const val HOME = "$ORIGIN/"
        const val LOGIN_URL = "https://soundcloud.com/signin"
        private const val SIGN_IN_COOKIE = "oauth_token"
    }
}
