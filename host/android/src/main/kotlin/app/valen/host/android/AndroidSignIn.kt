/* SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapted from Orpheon (https://github.com/kota-zo/Orpheon), base/host-android: the sign-in views of
 * `WebPlayerHost.openLogin`/`HiddenPageHost.signInView` and the session probe of
 * `MyspotiRuntime.isSignedIn`. See NOTICE.
 */
package app.valen.host.android

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import app.myspoti.web.parseCookieHeader
import app.valen.core.ProviderId
import app.valen.core.ProviderSignIn
import app.valen.core.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A sign-in the app must show: the service's own page, full screen, with a close action. Built by
 * [AndroidSignIn]'s `connect`, observed by the activity through [AndroidSignInHub].
 */
class SignInRequest internal constructor(
    val provider: ProviderId,
    val title: String,
    private val attachView: (ViewGroup) -> Unit,
    private val releaseView: (ViewGroup) -> Unit,
    private val cancel: () -> Unit,
) {
    private var container: ViewGroup? = null

    /** Puts the sign-in page in [container] (called from the activity's Compose content). */
    fun attach(container: ViewGroup) {
        this.container = container
        attachView(container)
    }

    /** The user closed the sign-in (or it finished): the page goes away. */
    fun release() {
        container?.let { runCatching { releaseView(it) } }
        container = null
    }

    /** The user backed out: no session was made. */
    fun dismiss() = cancelConnect()

    /** The page goes and the connect() that opened it ends without a session. */
    internal fun cancelConnect() {
        release()
        cancel()
    }
}

/**
 * The one sign-in on screen. The activity observes [request] and shows its WebView; everything else
 * in the app keeps running underneath.
 */
object AndroidSignInHub {
    private val _request = MutableStateFlow<SignInRequest?>(null)
    val request: StateFlow<SignInRequest?> = _request.asStateFlow()

    fun show(request: SignInRequest) {
        val previous = _request.value
        _request.value = request
        // Replaced before it was finished: the page goes and its connect() must not stay pending.
        previous?.cancelConnect()
    }

    /** The user closed the sign-in: the page goes, and the session stays out. */
    fun close() {
        val current = _request.value ?: return
        _request.value = null
        current.cancelConnect()
    }

    /** The sign-in finished on the service's own page: only the page goes, the session stays. */
    fun close(provider: ProviderId) {
        val current = _request.value ?: return
        if (current.provider != provider) return
        _request.value = null
        current.release()
    }
}

/**
 * One [ProviderSignIn] per service: Spotify from its Web Player page, YouTube Music and SoundCloud
 * from the cookies of the hidden page of their site. [connect] shows the service's own sign-in page
 * ([AndroidSignInHub]); a poll of the page or its cookies turns the session [SessionState.Connected].
 * [disconnect] clears that service's cookies.
 */
class AndroidSignIn private constructor(
    val provider: ProviderId,
    scope: CoroutineScope,
    private val open: () -> Unit,
    private val probe: suspend () -> SessionState,
    private val signOut: suspend () -> Unit,
) : ProviderSignIn {
    private val _state = MutableStateFlow<SessionState>(SessionState.SignedOut)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    init {
        // A session made before this run (the cookies are on disk) shows from the first second, and
        // a session lost elsewhere (a sign-out on the site) is not claimed.
        scope.launch {
            while (isActive) {
                delay(if (_state.value is SessionState.Connecting) CONNECTING_POLL_MS else IDLE_POLL_MS)
                val now = runCatching { probe() }.getOrNull() ?: continue
                when {
                    now is SessionState.Connected -> {
                        if (_state.value != now) _state.value = now
                        AndroidSignInHub.close(provider)
                    }
                    _state.value is SessionState.Connected -> _state.value = now
                    _state.value is SessionState.Connecting && now is SessionState.Unavailable -> _state.value = now
                }
            }
        }
    }

    override suspend fun connect() {
        if (_state.value is SessionState.Connected) return
        _state.value = SessionState.Connecting
        open()
    }

    override suspend fun disconnect() {
        AndroidSignInHub.close(provider)
        _state.value = SessionState.SignedOut
        runCatching { signOut() }
        _state.value = SessionState.SignedOut
    }

    /** The sign-in page finished with an account: no need to wait for the next poll. */
    internal fun markConnected() {
        _state.value = SessionState.Connected(null)
    }

    /** The sign-in page was closed without an account. */
    internal fun cancelConnect() {
        if (_state.value is SessionState.Connecting) _state.value = SessionState.SignedOut
    }

    companion object {
        private const val CONNECTING_POLL_MS = 700L
        private const val IDLE_POLL_MS = 4_000L

        /**
         * Spotify: the sp_dc cookie of open.spotify.com is the account, as on the desktop. Reading
         * the page instead ("is the player up?") reported a signed-in account as signed out every
         * time the Web Player reloaded, its renderer restarted, or it parked itself on the light
         * /preferences route, and the UI offered a sign-in that was not needed.
         */
        fun spotify(host: WebPlayerHost, scope: CoroutineScope): AndroidSignIn {
            lateinit var signIn: AndroidSignIn
            signIn = AndroidSignIn(
                provider = ProviderId.SPOTIFY,
                scope = scope,
                open = { AndroidSignInHub.show(spotifyRequest(host, signIn)) },
                probe = {
                    val header = withContext(Dispatchers.Main.immediate) { host.cookies().getCookie(SPOTIFY_ORIGIN) }
                    if (spotifyAccount(header)) SessionState.Connected(null) else SessionState.SignedOut
                },
                signOut = { host.logout() },
            )
            return signIn
        }

        /**
         * YouTube Music: the SAPISID cookie of music.youtube.com is the account. Sign-in is Google's
         * own page, back on music.youtube.com when done.
         */
        fun ytmusic(host: HiddenPageHost, scope: CoroutineScope): AndroidSignIn {
            lateinit var signIn: AndroidSignIn
            signIn = AndroidSignIn(
                provider = ProviderId.YOUTUBE_MUSIC,
                scope = scope,
                open = {
                    AndroidSignInHub.show(pageRequest(
                        host = host,
                        provider = ProviderId.YOUTUBE_MUSIC,
                        title = "Sign in to YouTube Music",
                        url = YTM_SIGN_IN_URL,
                        finished = { url -> url.startsWith(YTM_ORIGIN) },
                        signedIn = { header -> sapisid(header) != null },
                        done = { ok ->
                            if (ok) signIn.markConnected() else signIn.cancelConnect()
                            AndroidSignInHub.close(ProviderId.YOUTUBE_MUSIC)
                        },
                    ))
                },
                probe = { if (sapisid(host.header(YTM_ORIGIN)) != null) SessionState.Connected(null) else SessionState.SignedOut },
                signOut = { host.clear() },
            )
            return signIn
        }

        /** SoundCloud: the oauth_token cookie of soundcloud.com is the account. */
        fun soundcloud(host: HiddenPageHost, scope: CoroutineScope): AndroidSignIn {
            lateinit var signIn: AndroidSignIn
            signIn = AndroidSignIn(
                provider = ProviderId.SOUNDCLOUD,
                scope = scope,
                open = {
                    AndroidSignInHub.show(pageRequest(
                        host = host,
                        provider = ProviderId.SOUNDCLOUD,
                        title = "Sign in to SoundCloud",
                        url = SC_SIGN_IN_URL,
                        finished = { url -> url.startsWith(SC_ORIGIN) && !url.contains("/signin") },
                        signedIn = { header -> oauthToken(header) != null },
                        done = { ok ->
                            if (ok) signIn.markConnected() else signIn.cancelConnect()
                            AndroidSignInHub.close(ProviderId.SOUNDCLOUD)
                        },
                    ))
                },
                probe = { if (oauthToken(host.header(SC_ORIGIN)) != null) SessionState.Connected(null) else SessionState.SignedOut },
                signOut = { host.clear() },
            )
            return signIn
        }

        private const val SPOTIFY_ORIGIN = "https://open.spotify.com"

        /** Whether a cookie header for open.spotify.com carries Spotify's account cookie. */
        fun spotifyAccount(cookieHeader: String?): Boolean = !parseCookieHeader(cookieHeader)["sp_dc"].isNullOrBlank()

        /** The SAPISID cookie the YouTube Music auth header is made from, when the session has one. */
        fun sapisid(cookieHeader: String?): String? {
            val c = parseCookieHeader(cookieHeader)
            return (c["SAPISID"] ?: c["__Secure-3PAPISID"])?.takeIf { it.isNotBlank() }
        }

        /** SoundCloud's session cookie. */
        fun oauthToken(cookieHeader: String?): String? =
            parseCookieHeader(cookieHeader)["oauth_token"]?.takeIf { it.isNotBlank() }

        /** The refresh token that renews SoundCloud's access token, when the browser keeps one. */
        fun oauthRefreshToken(cookieHeader: String?): String? =
            parseCookieHeader(cookieHeader)["oauth_refresh_token"]?.takeIf { it.isNotBlank() }

        const val YTM_ORIGIN = "https://music.youtube.com"
        const val YTM_SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
            "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26next%3Dhttps%253A%252F%252Fmusic.youtube.com%252F"
        val YTM_SESSION_URLS = listOf(
            YTM_ORIGIN, "https://www.youtube.com", "https://youtube.com",
            "https://accounts.google.com", "https://www.google.com", "https://google.com",
        )

        const val SC_ORIGIN = "https://soundcloud.com"
        const val SC_SIGN_IN_URL = "https://soundcloud.com/signin"
        val SC_SESSION_URLS = listOf(SC_ORIGIN, "https://api-v2.soundcloud.com", "https://api.soundcloud.com", "https://secure.soundcloud.com")

        /** The Web Player's own view, shown full screen; Spotify's page is the sign-in page then. */
        private fun spotifyRequest(host: WebPlayerHost, signIn: AndroidSignIn) = SignInRequest(
            provider = ProviderId.SPOTIFY,
            title = "Sign in to Spotify",
            attachView = { container ->
                host.openLogin()
                host.attach(container, visible = true)
            },
            releaseView = { container -> host.detach(container) },
            cancel = {
                signIn.cancelConnect()
                // The page was left on Spotify's sign-in: back to the player for the next play.
                host.reload()
            },
        )

        /** A page of the service's own site in a WebView of its own, on the hidden page's profile. */
        private fun pageRequest(
            host: HiddenPageHost,
            provider: ProviderId,
            title: String,
            url: String,
            finished: (String) -> Boolean,
            signedIn: (String?) -> Boolean,
            done: (Boolean) -> Unit,
        ): SignInRequest {
            var view: WebView? = null
            return SignInRequest(
                provider = provider,
                title = title,
                attachView = { container ->
                    val v = view ?: host.signInView(container.context, url, finished, signedIn, done).also { view = it }
                    (v.parent as? ViewGroup)?.removeView(v)
                    container.addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                },
                releaseView = { container ->
                    view?.let { v ->
                        (v.parent as? ViewGroup)?.removeView(v)
                        runCatching { v.destroy() }
                    }
                    view = null
                },
                cancel = { done(false) },
            )
        }
    }
}
