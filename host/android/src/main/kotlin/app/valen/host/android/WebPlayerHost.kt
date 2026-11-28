/* SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapted from Orpheon (https://github.com/kota-zo/Orpheon), base/host-android, `WebPlayerHost.kt`.
 * See NOTICE.
 */
package app.valen.host.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.myspoti.spotify.bridge.BridgeHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * [BridgeHost] on an Android WebView that loads the official Spotify Web Player.
 *
 * - The WebView is built on the application context, so playback outlives activities, and it is an
 *   [AwakeWebView] so Chromium keeps the page running at full speed with the app in the background.
 * - Desktop user agent: on a phone UA the Web Player only offers to open the app.
 * - Protected media (Widevine) is allowed; the page asks for it on every load and a page that never
 *   does never registers its playback device.
 * - [loginRequired] turns true on Spotify's login pages: the UI then shows [view] so the user signs
 *   in on Spotify's own page, and [loginLoad] says how that page is opening.
 * - The sign-in page is the phone's own mobile Chrome ([MobileIdentity]); the player page is a
 *   desktop Chrome ([BrowserIdentity]), switched back as soon as Spotify hands the page to the
 *   player. Spotify patched in-app sign-ins, so the sign-in's e-mail-code requests leave from the
 *   app instead of the WebView ([LoginTransport], Revera's fix at c0b705c).
 * - Trimmed against Orpheon's host: no native engine, no ad blocking, no account profiles, no media
 *   proxy. The page here is the player.
 */
class WebPlayerHost(private val context: Context) : BridgeHost {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val messages: SharedFlow<String> = _messages

    private val _url = MutableStateFlow("")
    /** The page's current URL. */
    val url: StateFlow<String> = _url

    private val _loginRequired = MutableStateFlow(false)
    val loginRequired: StateFlow<Boolean> = _loginRequired

    private val _loginLoad = MutableStateFlow(LoginLoad.NONE)
    /** How the sign-in page is opening: the UI covers it while it loads and shows an error if it fails. */
    val loginLoad: StateFlow<LoginLoad> = _loginLoad

    private val _pageLoaded = MutableStateFlow(false)

    /**
     * The player's own document finished loading and it is not a sign-in page: the Web Player is up
     * and signed in (Spotify sends a signed-out visitor to the login page). What a sign-in waits for.
     */
    val playerReady: Boolean get() = _pageLoaded.value && !_loginRequired.value && _url.value.startsWith(PLAYER_URL)

    /** Fires after each Web Player page load; the API injects the bridge then. */
    var onPageLoaded: (() -> Unit)? = null

    /** Fires when a Web Player page load starts: the old page and its bridge are gone. */
    var onPageStarting: (() -> Unit)? = null

    /**
     * WebView profiles (androidx.webkit MULTI_PROFILE): Spotify keeps its own cookies and Web storage
     * next to the other services' hidden pages. Without them the default profile is used.
     */
    val multiProfile: Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    }.getOrDefault(false)

    val profile: String = PROFILE

    /** The desktop Chrome this page presents ([BrowserIdentity]). */
    val identity: BrowserIdentity = BrowserIdentity.of(appContext)

    private val debuggable = appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    init {
        if (debuggable) runCatching { WebView.setWebContentsDebuggingEnabled(true) }
        if (Widevine.tooOld(Widevine.version)) {
            android.util.Log.w(LOG, "Widevine ${Widevine.version}: Spotify may refuse audio licences on this device")
        }
    }

    /** The guarded fallback channel of the bridge's messages, when the WebView has no web message listener. */
    private var hostChannel: PlayerChannel? = null

    /** Hands the page that starts its secret for the fallback channel. */
    private fun armChannel(view: WebView, renew: Boolean) {
        val channel = hostChannel ?: return
        if (renew) channel.newPage()
        view.evaluateJavascript(channel.shim(), null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    var view: WebView = createView()

    /**
     * Builds the page view. Called once at startup, and again after a renderer crash: a WebView whose
     * renderer died can never be reused, and Android kills the process if one is used after
     * [onRenderProcessGone] returns false (see [rebuild]).
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun createView(): WebView = AwakeWebView(android.content.MutableContextWrapper(appContext)).apply {
        // The profile, before anything else is done with the view (androidx.webkit's rule).
        useProfile(this)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        // A desktop Chrome on Linux (on a phone UA the Web Player only offers to open the app), its
        // client hints agreeing with its user agent, and no X-Requested-With where that can be dropped.
        identity.applyTo(settings)
        // The page plays the audio: its renderer keeps its priority when the screen is off.
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        val webView = this
        cookies().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(webView, true) }
        hostChannel = exposeChannel("MyspotiHost", PLAYER_ORIGIN) { json -> _messages.tryEmit(json) }
        // Spotify Connect's commands leave from the app, without the WebView's X-Requested-With:
        // carrying it they are answered 200 and never delivered, and nothing plays (SpotifyRelay).
        SpotifyRelay(this) { identity.userAgent }.install()
        // Spotify patched in-app sign-ins the same way: the sign-in page's e-mail-code requests are
        // answered 200 and never delivered while the mark is on them. They leave from the app too
        // (LoginTransport, Revera's fix at c0b705c); the page and its session cookies stay the WebView's.
        LoginTransport(this) { cookies() }.install()
        webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                if (allowed.isNotEmpty()) request.grant(allowed.toTypedArray()) else request.deny()
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                // Spotify handed the sign-in back to the player: from here the page must speak as the
                // player again (the desktop identity), or the Web Player refuses to run. Revera's
                // sign-in fix: the sign-in page was the phone's mobile Chrome, the player is not.
                if (mobileLoginBrowser && android.net.Uri.parse(url).host == PLAYER_HOST) {
                    configurePlayerBrowser()
                    view.stopLoading()
                    view.loadUrl(PLAYER_URL)
                    return
                }
                // The load watchdog's evidence that something started. It was never counted, so
                // the watchdog reloaded the Web Player at about 4, 12, 24 and 40 s after every
                // load, each time wiping the song just asked for back to the account's last one.
                pageStarts++
                mainFrameFailed = false
                _pageLoaded.value = false
                main.removeCallbacks(loadTimeout)
                if (android.net.Uri.parse(url).host == PLAYER_HOST) main.postDelayed(loadTimeout, PAGE_LOAD_TIMEOUT_MS)
                // The sign-in page was asked for and Spotify sent us elsewhere: there is no sign-in
                // page left to wait for. Before the address is published, so whoever hides the page
                // for the player does it after this.
                if (!isLoginPage(url) && _loginLoad.value != LoginLoad.NONE && _loginLoad.value != LoginLoad.FAILED) setLoginLoad(LoginLoad.NONE)
                _url.value = url
                _loginRequired.value = isLoginPage(url)
                if (url.startsWith(PLAYER_URL)) {
                    onPageStarting?.invoke()
                    armChannel(view, renew = true)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // ERR_ABORTED: a load this app stopped itself (a timeout, a newer load) — not a failure.
                val url = request.url.toString()
                val aborted = "ERR_ABORTED" in error.description.toString()
                if (request.isForMainFrame && request.url.host == PLAYER_HOST && !aborted)
                    playerPageFailed("load error ${error.errorCode}")
                if ((LoginLoad.isSpotifyAccounts(url) || (request.isForMainFrame && isLoginPage(url))) && !aborted)
                    LoginDiagnostics.record("network", "err-${error.errorCode}")
                if (request.isForMainFrame && _loginLoad.value != LoginLoad.NONE && LoginLoad.isSpotifyAccounts(url) && !aborted)
                    loginFailed("load error ${error.errorCode}")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                val url = request.url.toString()
                if (request.isForMainFrame && request.url.host == PLAYER_HOST && (response.statusCode >= 500 || response.statusCode == 429))
                    playerPageFailed("http ${response.statusCode}")
                if (LoginLoad.isSpotifyAccounts(url) || (request.isForMainFrame && isLoginPage(url)))
                    LoginDiagnostics.record("http", "${request.url.host} ${response.statusCode}")
                if (request.isForMainFrame && _loginLoad.value != LoginLoad.NONE && LoginLoad.isSpotifyAccounts(url) && response.statusCode >= 500)
                    loginFailed("http ${response.statusCode}")
            }

            /**
             * The sign-in page is on Spotify's account host, whose certificate failure is the
             * sign-in's own. Only hosts and failure types are kept: never the failing address.
             */
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                val url = error.url.orEmpty()
                val signIn = _loginLoad.value != LoginLoad.NONE || isLoginPage(_url.value) || isLoginPage(url)
                android.util.Log.w(LOG, "ssl error ${error.primaryError} on ${hostOf(url)}${if (signIn) " during a sign-in" else ""}")
                if (signIn) {
                    LoginDiagnostics.record("ssl", "primary-${error.primaryError}")
                    if (url.isEmpty() || url == _url.value || LoginLoad.isSpotifyAccounts(url) || isLoginPage(url)) {
                        handler.cancel()
                        loginFailed("ssl error ${error.primaryError}")
                        return
                    }
                }
                handler.cancel()
            }

            /**
             * The sign-in's trail: its own page's requests, by method, host and path only. Never the
             * query (a flow id can ride there), never a header or a body, never a posted field: the
             * e-mail address and the code the person types are never logged.
             */
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if ("https".equals(url.scheme, true) && url.host == LoginTransport.ACCOUNTS_HOST) {
                    val step = signInSteps.incrementAndGet()
                    android.util.Log.i(LOG, "sign-in step #$step ${request.method} ${url.host}${url.path ?: "/"}")
                }
                return null
            }

            override fun onPageFinished(view: WebView, url: String) {
                main.removeCallbacks(loadTimeout)
                if (url.startsWith(PLAYER_URL) && !mainFrameFailed) { pageFailures = 0; main.removeCallbacks(retryPlayerPage) }
                _url.value = url
                _loginRequired.value = isLoginPage(url)
                // Finished is not painted: Spotify's sign-in is a script-built page. Looked at until it has content.
                if (_loginLoad.value == LoginLoad.LOADING && isLoginPage(url)) {
                    loginProbes = 0
                    main.removeCallbacks(loginProbe)
                    main.postDelayed(loginProbe, LoginLoad.FIRST_PROBE_MS)
                }
                if (url.startsWith(PLAYER_URL)) {
                    // Again, same secret: the page start's call may have run before the new document existed.
                    armChannel(view, renew = false)
                    _pageLoaded.value = true
                    onPageLoaded?.invoke()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val host = request.url.host.orEmpty()
                // Stay on Spotify's own pages (player, login, consent); open nothing else.
                val allowed = listOf("spotify.com", "spotify.net", "scdn.co", "google.com", "facebook.com", "apple.com")
                    .any { host == it || host.endsWith(".$it") }
                val secure = request.url.scheme == "https"
                return !(allowed && secure)
            }

            /**
             * The renderer died: a WebView whose renderer is gone can never be reused, and Android
             * kills the whole process if one is still used after this returns false.
             */
            override fun onRenderProcessGone(gone: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (gone !== this@WebPlayerHost.view) return true
                android.util.Log.w(LOG, "renderer gone (crashed=${detail.didCrash()}): rebuilding the page")
                onPageStarting?.invoke()
                rebuild()
                return true
            }
        }
    }

    /** The sign-in page presents the phone's own Chrome, not the player's desktop identity. */
    private var mobileLoginBrowser = false

    /** Sign-in requests seen on Spotify's account host: a count for the log, never a payload. */
    private val signInSteps = AtomicInteger()

    /** The player's identity (a desktop Chrome on Linux): what the Web Player needs to play on a phone. */
    private fun configurePlayerBrowser() {
        mobileLoginBrowser = false
        identity.applyTo(view.settings)
    }

    /**
     * The sign-in page's identity: the phone's own mobile Chrome, hints agreeing with it. Spotify
     * builds its account pages for the phone's browser; the player's desktop identity is switched
     * back in as soon as the page heads for open.spotify.com (see [onPageStarted]).
     */
    private fun configureLoginBrowser() {
        mobileLoginBrowser = true
        runCatching {
            MobileIdentity.of(WebSettings.getDefaultUserAgent(context), android.os.Build.VERSION.RELEASE, android.os.Build.MODEL)
        }.onFailure { android.util.Log.w(LOG, "sign-in: no mobile browser identity: $it") }
            .getOrNull()?.applyTo(view.settings)
    }

    /** A host for the log, never an address: a sign-in failure must not carry a query or a code. */
    private fun hostOf(url: String): String = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: "unknown host"

    /** The container the page is attached to, so a rebuilt page goes back to the same place. */
    private var container: ViewGroup? = null

    /** Whether the page was shown full screen at the last [attach]. */
    private var lastVisible = false

    /** Replaces the page after its renderer died; the page loads again. */
    private fun rebuild() = main.post {
        val wasLoaded = loadAsked
        val wasLogin = _loginRequired.value || _loginLoad.value != LoginLoad.NONE
        val box = container
        box?.let { runCatching { it.removeView(view) } }
        (view as? AwakeWebView)?.releaseFromWindow()
        runCatching { view.stopLoading() }
        runCatching { view.destroy() }
        view = createView()
        pageStarts = 0
        _url.value = ""
        _pageLoaded.value = false
        loadAsked = false
        box?.let { attach(it, lastVisible) }
        if (wasLogin) { loadAsked = wasLoaded; beginLoginLoad(); view.loadUrl(LOGIN_URL) }
        else if (wasLoaded) load(firstWatchMs = REBUILD_WATCH_MS) else view.loadUrl("about:blank")
    }

    private fun isLoginPage(url: String?) = LoginLoad.isLoginUrl(url)

    fun load() = load(firstWatchMs = LOAD_WATCH_MS)

    private fun load(firstWatchMs: Long) = main.post { loadNow(firstWatchMs) }

    /** Main thread. */
    private fun loadNow(firstWatchMs: Long) {
        pageStarts = 0
        loadAsked = true
        view.loadUrl(PLAYER_URL)
        watchLoad(PLAYER_URL, attempt = 1, firstWatchMs)
    }

    /**
     * Loads the player unless it is loaded, loading or showing a sign-in already. The Web Player
     * is a whole web app (half a gigabyte and two cores while it boots on a phone), so it is not
     * loaded at start any more: a signed-in account loads it shortly after, and anything else
     * that needs it (a Spotify search or play) asks for it here.
     */
    fun ensureLoaded() = main.post { if (wantsPlayer()) loadNow(LOAD_WATCH_MS) }

    /** No player asked for yet, and no sign-in page on show (a failed one does not count). */
    private fun wantsPlayer(): Boolean =
        !loadAsked && _loginLoad.value != LoginLoad.LOADING && _loginLoad.value != LoginLoad.SHOWN

    /** Page starts since the last [load]; the load watchdog looks at it. */
    private var pageStarts = 0

    /** [load] was called and no [unload] since. */
    private var loadAsked = false

    /**
     * An exec-spawned process (GrapheneOS "secure app spawning") can lose the first navigation of a
     * WebView made in Application.onCreate: no page start, ever. When nothing started after a while,
     * the page is asked for again (a few times, further apart).
     */
    private fun watchLoad(url: String, attempt: Int, firstWatchMs: Long = LOAD_WATCH_MS) {
        main.postDelayed({
            if (pageStarts > 0 || !loadAsked || attempt > LOAD_RETRIES) return@postDelayed
            if (attempt > 1) view.stopLoading()
            view.loadUrl(url)
            watchLoad(url, attempt + 1)
        }, if (attempt == 1) firstWatchMs else LOAD_WATCH_MS * attempt)
    }

    /** The player's own URL, not the page's current one. */
    fun reload() = main.post { view.loadUrl(PLAYER_URL) }

    private var mainFrameFailed = false
    private var pageFailures = 0
    private val retryPlayerPage = Runnable {
        if (!loadAsked || _loginRequired.value) return@Runnable
        view.stopLoading()
        view.loadUrl(PLAYER_URL)
    }
    private val loadTimeout = Runnable {
        if (!loadAsked || _loginRequired.value) return@Runnable
        view.stopLoading()
        playerPageFailed("still loading after ${PAGE_LOAD_TIMEOUT_MS / 1000} s")
    }

    private fun playerPageFailed(what: String) {
        mainFrameFailed = true
        val wait = RETRY_PAGE_MS[pageFailures.coerceAtMost(RETRY_PAGE_MS.lastIndex)]
        pageFailures++
        android.util.Log.w(LOG, "player page failed to load ($what): again in ${wait / 1000} s")
        main.removeCallbacks(retryPlayerPage)
        main.postDelayed(retryPlayerPage, wait)
    }

    /** Shows Spotify's own sign-in page; the view must then be visible ([attach]). */
    fun openLogin() = main.post {
        main.removeCallbacks(retryPlayerPage)
        main.removeCallbacks(loadTimeout)
        beginLoginLoad()
        // Signed out, a sign-in starts from nothing: a failed attempt leaves half a flow behind (its
        // cookies and storage), and Spotify answers "Oops! Something went wrong" to a flow it cannot
        // match with its own state (Orpheon's finding). Only on Spotify's own profile: without
        // profiles the cookie store is shared with the other services' sign-ins.
        val signedOut = runCatching { !AndroidSignIn.spotifyAccount(cookies().getCookie(PLAYER_ORIGIN)) }.getOrDefault(false)
        if (signedOut && multiProfile) {
            android.util.Log.i(LOG, "sign-in: starting from a clean slate")
            cookies().removeAllCookies {
                main.post {
                    webStorage().deleteAllData()
                    view.clearCache(true)
                    view.loadUrl(LOGIN_URL)
                }
            }
        } else {
            view.loadUrl(LOGIN_URL)
        }
    }

    /** Signs out of Spotify in this WebView: its cookies and storage go, then the login page opens. */
    fun logout() = main.post {
        beginLoginLoad()
        cookies().removeAllCookies {
            main.post {
                webStorage().deleteAllData()
                view.clearCache(true)
                _pageLoaded.value = false
                view.loadUrl(LOGIN_URL)
            }
        }
    }

    /** "Try again" after the sign-in page failed: from nothing. */
    fun retryLogin() = main.post { beginLoginLoad(); logout() }

    /** Writes the session cookies to disk now. */
    fun flushCookies() = cookies().flush()

    /** The active profile's cookies (the default ones without profiles). Main thread. */
    fun cookies(): CookieManager =
        if (multiProfile) ProfileStore.getInstance().getOrCreateProfile(profile).cookieManager
        else CookieManager.getInstance()

    private fun webStorage(): android.webkit.WebStorage =
        if (multiProfile) ProfileStore.getInstance().getOrCreateProfile(profile).webStorage
        else android.webkit.WebStorage.getInstance()

    /** Puts [webView] on the page's profile. Call it on a new view, before anything else. Main thread. */
    fun useProfile(webView: WebView) {
        if (!multiProfile) return
        ProfileStore.getInstance().getOrCreateProfile(profile)
        WebViewCompat.setProfile(webView, profile)
    }

    private var loginProbes = 0
    private var loginReloaded = false

    /** The sign-in page was asked for: kept out of sight, with its clocks running, until it has content. */
    private fun beginLoginLoad() {
        configureLoginBrowser()
        signInSteps.set(0)
        LoginDiagnostics.record("opening")
        loginProbes = 0
        loginReloaded = false
        main.removeCallbacks(loginProbe)
        main.removeCallbacks(loginTimeout)
        _loginLoad.value = LoginLoad.LOADING
        view.alpha = 0f
        main.postDelayed(loginTimeout, LoginLoad.TIMEOUT_MS)
    }

    private fun setLoginLoad(state: LoginLoad) {
        if (_loginLoad.value == state) return
        _loginLoad.value = state
        if (state != LoginLoad.LOADING) { main.removeCallbacks(loginProbe); main.removeCallbacks(loginTimeout) }
        if (state == LoginLoad.SHOWN || state == LoginLoad.NONE) view.alpha = 1f
    }

    private fun loginFailed(what: String) {
        if (_loginLoad.value == LoginLoad.FAILED) return
        android.util.Log.w(LOG, "sign-in page failed ($what)")
        LoginDiagnostics.record(stageOfSignInFailure(what), what)
        android.util.Log.w(LOG, LoginDiagnostics.summary())
        runCatching { view.stopLoading() }
        view.alpha = 0f
        setLoginLoad(LoginLoad.FAILED)
    }

    /** The stage a sign-in's own failure belongs to: its type only, never the page's content. */
    private fun stageOfSignInFailure(what: String): String = when {
        what.startsWith("ssl") -> "ssl"
        what.startsWith("http") -> "http"
        what.startsWith("load error") -> "network"
        what.startsWith("blank") -> "blank"
        else -> "timeout"
    }

    private val loginTimeout = Runnable {
        if (_loginLoad.value != LoginLoad.LOADING) return@Runnable
        // A page can show everything and still be "loading" (a stuck script or tracker): content decides.
        view.evaluateJavascript(LoginLoad.PAINT_JS) { answer ->
            if (_loginLoad.value != LoginLoad.LOADING) return@evaluateJavascript
            if (LoginLoad.painted(answer) && isLoginPage(view.url)) setLoginLoad(LoginLoad.SHOWN)
            else loginFailed("no content after ${LoginLoad.TIMEOUT_MS / 1000} s")
        }
    }

    /** Asks the page how much it shows; empty after its load finished means a page that did not run. */
    private val loginProbe: Runnable = Runnable {
        if (_loginLoad.value != LoginLoad.LOADING) return@Runnable
        view.evaluateJavascript(LoginLoad.PAINT_JS) { answer ->
            if (_loginLoad.value != LoginLoad.LOADING) return@evaluateJavascript
            if (LoginLoad.painted(answer)) { setLoginLoad(LoginLoad.SHOWN); return@evaluateJavascript }
            when (LoginLoad.blank(++loginProbes, loginReloaded)) {
                LoginLoad.Blank.WAIT -> main.postDelayed(loginProbe, LoginLoad.PROBE_EVERY_MS)
                LoginLoad.Blank.RELOAD -> {
                    loginReloaded = true
                    loginProbes = 0
                    view.loadUrl(LOGIN_URL)
                }
                LoginLoad.Blank.FAIL -> loginFailed("blank after a second load")
            }
        }
    }

    override suspend fun evaluate(script: String) {
        val done = CompletableDeferred<Unit>()
        main.post {
            runCatching { view.evaluateJavascript(script, null) }
            done.complete(Unit)
        }
        done.await()
    }

    /** The page's own answer: a call that never reached the bridge fails now, not after its timeout. */
    override suspend fun evaluateValue(script: String): String? {
        val done = CompletableDeferred<String?>()
        main.post {
            runCatching { view.evaluateJavascript(script) { value -> done.complete(value) } }
                .onFailure { done.completeExceptionally(IllegalStateException("page not reachable: ${it.message}")) }
        }
        return done.await()
    }

    override fun commandComing() {
        main.post {
            // A command for a page that was never loaded: load it (the command waits for it to answer).
            if (wantsPlayer()) loadNow(LOAD_WATCH_MS)
            view.postInvalidateOnAnimation()
        }
    }

    /**
     * Keeps the page attached to a window: a 1 px INVISIBLE view while the player is hidden, full
     * screen while the sign-in is shown.
     */
    fun attach(container: ViewGroup, visible: Boolean) = main.post {
        this.container = container
        lastVisible = visible
        // A <select> (the birth month on Spotify's sign-up page) opens its list as a dialog, which
        // needs an Activity: shown, the page works on the activity's context; hidden, on the
        // application's again, so no activity is kept by a page that outlives them. Same page.
        (view.context as? android.content.MutableContextWrapper)?.baseContext =
            if (visible) container.context else appContext
        (view.parent as? ViewGroup)?.removeView(view)
        val size = if (visible) ViewGroup.LayoutParams.MATCH_PARENT else 1
        container.addView(view, ViewGroup.LayoutParams(size, size))
        keepClearOfSystemBars(visible)
        // Still opening or failed, the sign-in page stays out of sight (the app's own screen covers it).
        view.alpha = if (_loginLoad.value == LoginLoad.LOADING || _loginLoad.value == LoginLoad.FAILED) 0f else 1f
        view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        view.importantForAccessibility = if (visible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        // Typing in the login form needs touch focus, or the keyboard never opens.
        view.isFocusable = visible
        view.isFocusableInTouchMode = visible
        view.descendantFocusability = if (visible) ViewGroup.FOCUS_AFTER_DESCENDANTS else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (visible) view.requestFocus()
        view.requestLayout()
        // Shown again after being hidden at 1 px: a page that was not told to redraw can stay white.
        if (visible) view.postInvalidateOnAnimation()
        if (loadAsked && _url.value.isEmpty() && pageStarts == 0 && _loginLoad.value == LoginLoad.NONE) view.loadUrl(PLAYER_URL)
    }

    /**
     * The app draws edge to edge: the sign-in page shown full screen would go under the status bar
     * and its form under the keyboard. Shown, the page keeps clear of the system bars and the IME.
     */
    private fun keepClearOfSystemBars(visible: Boolean) {
        val v = view
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(v) { _, insets ->
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null) {
                val bars = if (lastVisible) insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                        androidx.core.view.WindowInsetsCompat.Type.displayCutout() or
                        androidx.core.view.WindowInsetsCompat.Type.ime()
                ) else androidx.core.graphics.Insets.NONE
                if (lp.topMargin != bars.top || lp.bottomMargin != bars.bottom || lp.leftMargin != bars.left || lp.rightMargin != bars.right) {
                    lp.setMargins(bars.left, bars.top, bars.right, bars.bottom)
                    v.layoutParams = lp
                }
            }
            insets
        }
        if (visible) androidx.core.view.ViewCompat.requestApplyInsets(v)
        else (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.setMargins(0, 0, 0, 0); v.layoutParams = it }
    }

    /** Only the Activity that owns the current attachment may release it. */
    fun detach(from: ViewGroup) = main.post {
        if (container !== from) return@post
        container = null
        lastVisible = false
        (view.parent as? ViewGroup)?.removeView(view)
        (view.context as? android.content.MutableContextWrapper)?.baseContext = appContext
    }

    /** Frees the page (data saver, low memory). [load] brings it back. */
    fun unload() = main.post { loadAsked = false; view.loadUrl("about:blank") }

    companion object {
        const val LOG = "Valen"

        const val PLAYER_URL = "https://open.spotify.com/"
        const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
        private const val PLAYER_HOST = "open.spotify.com"
        private const val PLAYER_ORIGIN = "https://open.spotify.com"
        private const val PROFILE = "valen-spotify"
        private const val PAGE_LOAD_TIMEOUT_MS = 25_000L
        /** A page that did not load is loaded again after these waits, the last one repeated. */
        private val RETRY_PAGE_MS = longArrayOf(2_000, 5_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000)
        private const val LOAD_WATCH_MS = 4_000L
        private const val REBUILD_WATCH_MS = 1_500L
        private const val LOAD_RETRIES = 4
    }
}
