/* SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapted from Revera (https://github.com/caruno-git/Revera), base/host-android,
 * `LoginTransport.kt` at c0b705c ("Fix Spotify email code request transport"), which forks
 * Myspoti/Orpheon (https://github.com/kota-zo/myspoti). See NOTICE.
 */
package app.valen.host.android

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Sends Spotify's sign-in e-mail-code requests (`accounts.spotify.com/login/code/send` and
 * `/login/code/verify`) from the app instead of the WebView, and hands the answer back to the page
 * as the `Response` its `fetch` awaited. The page, its cookies and its session stay the WebView's.
 *
 * Every request a WebView makes carries `X-Requested-With: <package>`, and current WebViews no
 * longer let an app turn that off. Spotify patched its sign-in so those two XHRs are answered with
 * 200 and never delivered when the mark is on them: no code is sent, the page waits, and the
 * sign-in never finishes. Revera found and shipped this relay (its 1.0.9); Orpheon and Valen had the
 * same login code and the same breakage. This is the same mechanism, pinned to Revera c0b705c:
 *
 * - the page's `fetch` is wrapped at document start (only on `accounts.spotify.com`), and only
 *   `POST`s to the two code endpoints are relayed, after [allowed] has checked the exact host and path;
 * - the request leaves with the page's own headers and identity (user agent, client hints) less the
 *   WebView's mark, and with the WebView's cookie; on API 34+ it leaves on the platform's Chromium
 *   HTTP stack ([PlatformStack]) so it looks like the browser's, below it on OkHttp;
 * - every `Set-Cookie` the answer carries is written back into the page's `CookieManager`, attributes
 *   and all, so the session lands in the profile exactly as a browser's would, and the body goes back
 *   to the page's promise.
 *
 * Nothing here is logged but the endpoint name, the host and the status: never a header, a body, a
 * cookie, a `Set-Cookie` value or a sign-in code.
 */
internal class LoginTransport(private val view: WebView, private val cookieJar: () -> CookieManager) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        // A 30x from the code endpoints is the answer the page must see, not something to follow blind.
        .followRedirects(false)
        .build()

    /** False when this WebView cannot run a script before the page's own (then nothing is relayed). */
    var installed = false
        private set

    fun install() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            Log.w(TAG, "sign-in transport not installed: this WebView lacks message listeners or document-start scripts")
            LoginDiagnostics.record("unsupported", "sign-in transport")
            return
        }
        WebViewCompat.addWebMessageListener(view, CHANNEL, setOf(ORIGIN)) { _, message, origin, mainFrame, _ ->
            if (mainFrame && origin.toString() == ORIGIN) message.data?.let(::receive)
        }
        WebViewCompat.addDocumentStartJavaScript(view, SCRIPT, setOf(ORIGIN))
        installed = true
        Log.i(TAG, "sign-in transport installed")
    }

    private fun receive(raw: String) {
        val m = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val url = m.optString("url")
        val method = m.optString("method")
        // Only the two code endpoints on the exact account host, with a body a sign-in could carry.
        if (!allowed(method, url) || m.optString("body").length > MAX_BODY) return
        val id = m.optInt("id", -1)
        val doc = m.optString("doc")
        val step = endpointOf(url)
        val given = m.optJSONObject("headers") ?: JSONObject()
        val builder = Request.Builder().url(url)
            .header("User-Agent", view.settings.userAgentString)
            .header("Origin", ORIGIN)
            .header("Referer", view.url?.takeIf { it.startsWith("$ORIGIN/") } ?: "$ORIGIN/")
        for (key in given.keys()) {
            if (key.lowercase() in DROPPED) continue
            val value = given.optString(key)
            if (value.isNotEmpty()) builder.header(key, value)
        }
        builder.header("Sec-Fetch-Site", "same-origin")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Dest", "empty")
            .header("sec-ch-ua", m.optString("brands"))
            .header("sec-ch-ua-mobile", "?1")
            .header("sec-ch-ua-platform", "\"Android\"")
        cookieJar().getCookie(url)?.let { builder.header("Cookie", it) }
        val request = runCatching { builder.post(m.optString("body").toRequestBody(JSON)).build() }.getOrNull()
        if (request == null) {
            Log.w(TAG, "sign-in transport: $step not sent (bad request address)")
            LoginDiagnostics.record("transport", "$ACCOUNTS_HOST $step failed")
            finish(id, doc, 0, "", emptyList())
            return
        }
        Log.i(TAG, "sign-in transport: $step")
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            PlatformStack.fetch(view.context, url, method, request.headers, m.optString("body")) { code, headers, text, _, failure ->
                report(step, code, failure)
                finish(id, doc, code, text, setCookies(headers))
            }
            return
        }
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                report(step, 0, LoginDiagnostics.failureType(e))
                finish(id, doc, 0, "", emptyList())
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val text = runCatching { r.body.string() }.getOrElse { "" }
                    report(step, r.code, null)
                    // Every Set-Cookie header, in the order the server sent them.
                    finish(id, doc, r.code, text, setCookies(r.headers.values("set-cookie").map { "set-cookie" to it }))
                }
            }
        })
    }

    /** Logs and records the answer: the endpoint, the host and the status only. */
    private fun report(step: String, code: Int, failure: String?) {
        if (code > 0) {
            Log.i(TAG, "sign-in transport: $step -> $code")
            LoginDiagnostics.record("transport", "$ACCOUNTS_HOST $step $code")
        } else {
            val type = failure ?: "failed"
            Log.i(TAG, "sign-in transport: $step -> $type")
            LoginDiagnostics.record("network", "$ACCOUNTS_HOST $step $type")
        }
    }

    private fun finish(id: Int, doc: String, status: Int, body: String, cookies: List<String>) {
        view.post {
            // The session must land where the page's own cookie store keeps it, with every attribute
            // the server wrote (domain, path, expiry, secure, httpOnly, sameSite): setCookie takes the
            // Set-Cookie value as it is, so none of them are rebuilt here.
            val jar = cookieJar()
            for (cookie in cookies) jar.setCookie(ORIGIN, cookie)
            jar.flush()
            val reply = JSONObject().put("id", id).put("doc", doc).put("status", status).put("body", body)
            runCatching { view.evaluateJavascript("window.$REPLY && window.$REPLY($reply)", null) }
        }
    }

    companion object {
        private const val TAG = "ValenSpotify"

        internal const val ACCOUNTS_HOST = "accounts.spotify.com"
        internal const val ORIGIN = "https://$ACCOUNTS_HOST"

        private const val CHANNEL = "ValenLoginNet"
        private const val REPLY = "__valenLoginReply"
        private const val MAX_BODY = 65_536

        private const val CODE_SEND = "/login/code/send"
        private const val CODE_VERIFY = "/login/code/verify"
        private val PATHS = listOf(CODE_SEND, CODE_VERIFY)

        private val JSON = "application/json".toMediaType()

        /** Never sent on: the WebView's mark, and what OkHttp or the platform stack sets itself. */
        private val DROPPED = setOf("cookie", "host", "content-length", "x-requested-with", "user-agent", "origin", "referer")

        /**
         * The sign-in's own requests, and nothing else: exactly `POST`, https, the exact account
         * host (not a lookalike), the default or 443 port, no credentials in the address, and one of
         * the two code paths. Pinned by [LoginTransportTest].
         */
        fun allowed(method: String, url: String): Boolean = runCatching {
            val u = URI(url)
            method == "POST" && u.scheme == "https" && u.host == ACCOUNTS_HOST &&
                u.port in listOf(-1, 443) && u.userInfo == null && u.path in PATHS
        }.getOrDefault(false)

        /** The step's name for the log and the diagnostics: the path, never the query or the body. */
        fun endpointOf(url: String): String = when (runCatching { URI(url).path }.getOrNull()) {
            CODE_SEND -> "code-send"
            CODE_VERIFY -> "code-verify"
            else -> "code-request"
        }

        /**
         * Every `Set-Cookie` value in order, each exactly as the server wrote it (all attributes
         * kept), and nothing else. Pinned by [LoginTransportTest].
         */
        fun setCookies(headers: List<Pair<String, String>>): List<String> =
            headers.filter { it.first.equals("set-cookie", ignoreCase = true) }.map { it.second }

        /**
         * Wraps the page's `fetch` on the sign-in page only, before its own scripts run. A relayed
         * request's answer comes back over the channel as `{id, doc, status, body}`; the document's
         * own mark (`doc`) keeps an answer meant for a previous page from resolving this one's.
         */
        private val SCRIPT = """
            (() => {
              if (window !== window.top || window.$REPLY || !window.$CHANNEL) return;
              const original = window.fetch.bind(window), pending = new Map();
              const doc = Math.random().toString(36).slice(2); let seq = 0;
              window.$REPLY = r => {
                if (r.doc !== doc) return;
                const p = pending.get(r.id); if (!p) return;
                clearTimeout(p.timer); pending.delete(r.id);
                if (!r.status) p.reject(new TypeError('Login network request failed'));
                else p.resolve(new Response(r.body, {status:r.status,headers:{'content-type':'application/json'}}));
              };
              window.fetch = async (input, init) => {
                const req = new Request(input, init), u = new URL(req.url);
                if (u.origin !== '$ORIGIN' || req.method !== 'POST' ||
                    !['$CODE_SEND','$CODE_VERIFY'].includes(u.pathname)) return original(input, init);
                const body = await req.clone().text();
                if (body.length > $MAX_BODY) throw new TypeError('Login request too large');
                return new Promise((resolve,reject) => {
                  const id = ++seq;
                  const timer = setTimeout(() => {pending.delete(id);reject(new TypeError('Login request timed out'));},28000);
                  pending.set(id,{resolve,reject,timer});
                  window.$CHANNEL.postMessage(JSON.stringify({id,doc,url:req.url,method:req.method,headers:Object.fromEntries(req.headers),brands:(navigator.userAgentData?.brands||[]).map(b=>'"'+b.brand+'";v="'+b.version+'"').join(', '),body}));
                });
              };
            })();
        """.trimIndent()
    }
}