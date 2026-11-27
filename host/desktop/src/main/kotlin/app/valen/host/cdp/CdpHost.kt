/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.cdp

import app.myspoti.spotify.bridge.AdBlock
import app.myspoti.spotify.bridge.BridgeHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [BridgeHost] on a desktop Chrome started with `--remote-debugging-port`.
 *
 * Attaches to the open.spotify.com tab (or opens one), relays bridge messages through a CDP
 * binding, and fails the requests [AdBlock] refuses with `BlockedByClient`, like the Android host
 * does in `shouldInterceptRequest`. Copied from Orpheon's `base/host-cdp`.
 */
class CdpHost private constructor(private val socket: WebSocket, private val listener: Listener) : BridgeHost {

    /**
     * Kept in step with [Listener]'s own flow. Per the interface it is a plain [Flow]; the concrete
     * shared flow keeps the bridge's previous broadcast behaviour.
     */
    override val messages: SharedFlow<String> = listener.messages

    /** False as soon as this tab disconnects, even if the browser still answers HTTP. */
    val isOpen: Boolean get() = listener.isOpen

    /** Suspends until the next page load; the API re-injects the bridge then. */
    suspend fun awaitLoad() { listener.pageLoads.first() }

    /**
     * The Web Player only fetches and starts a song's media while its page has focus: with the
     * Chrome window in the background `document.hasFocus()` is false and the element stays at
     * `readyState 0` with no audio request at all, whatever the autoplay policy says. Focus
     * emulation makes the page see focus without the user's window being taken away, and
     * [Page.bringToFront] does the rest when nothing else matters.
     */
    override suspend fun activate() {
        runCatching { send("Emulation.setFocusEmulationEnabled", buildJsonObject { put("enabled", true) }) }
        runCatching { send("Page.bringToFront", JsonObject(emptyMap())) }
    }

    override suspend fun evaluate(script: String) {
        send("Runtime.evaluate", buildJsonObject { put("expression", script); put("awaitPromise", false) })
    }

    /**
     * Runs [script] and returns the JSON of its value once the page ran it (`"true"`, `"\"x\""`,
     * `"null"`, …), like the Android host's `evaluateJavascript` callback. Throws when the page
     * cannot be reached, so a caller fails at once instead of waiting for an answer that cannot come.
     */
    override suspend fun evaluateValue(script: String): String? {
        val result = send("Runtime.evaluate", buildJsonObject {
            put("expression", script)
            put("awaitPromise", true)
            put("returnByValue", true)
        })
        val exception = result["exceptionDetails"]?.jsonObject
        if (exception != null) {
            val text = exception["exception"]?.jsonObject?.get("description")?.jsonPrimitive?.content
                ?: exception["text"]?.jsonPrimitive?.content
                ?: "the page threw"
            throw IllegalStateException(text)
        }
        val value = result["result"]?.jsonObject?.get("value") ?: return null
        return value.toString()
    }

    /** Sets each cookie ([name] to [value]) on [domain] for a year, secure, on the whole site. */
    suspend fun setCookies(cookies: List<Pair<String, String>>, domain: String, httpOnly: Boolean = false) {
        val expires = System.currentTimeMillis() / 1000.0 + 365 * 24 * 3600
        for ((name, value) in cookies) send("Network.setCookie", buildJsonObject {
            put("name", name); put("value", value); put("domain", domain); put("path", "/")
            put("secure", true); put("httpOnly", httpOnly); put("expires", expires)
        })
    }

    suspend fun reload() {
        send("Page.reload", buildJsonObject {})
    }

    /** Navigates the tab to [url] (the Web Player's own page, or its logout, after a sign-out). */
    suspend fun navigate(url: String) {
        send("Page.navigate", buildJsonObject { put("url", url) })
    }

    /**
     * The names of the cookies the browser holds for [url] (never their values). Spotify's account
     * lives in `sp_dc`: reading that cookie is more truthful than guessing from the page's markup,
     * which reported "connected" on the "Unsupported browser" page too.
     */
    suspend fun cookieNames(url: String): Set<String> =
        cookies(listOf(url)).mapNotNull { it["name"]?.jsonPrimitive?.content }.toSet()

    /**
     * The `Cookie` header the browser would send to [url], or null when it holds none. Cookies are
     * browser-wide, so the Web Player tab answers for another service's site too: reading an
     * account's playlists must not need a tab (let alone a browser) of its own.
     */
    suspend fun cookieHeader(url: String): String? =
        cookies(listOf(url)).joinToString("; ") {
            "${it["name"]!!.jsonPrimitive.content}=${it["value"]!!.jsonPrimitive.content}"
        }.ifEmpty { null }

    /** Signs out: every cookie the browser holds for [urls] is deleted. */
    suspend fun clearCookies(urls: List<String>) {
        for (c in cookies(urls)) send("Network.deleteCookies", buildJsonObject {
            put("name", c["name"]!!.jsonPrimitive.content)
            put("domain", c["domain"]!!.jsonPrimitive.content)
            put("path", c["path"]?.jsonPrimitive?.content ?: "/")
        })
    }

    private suspend fun cookies(urls: List<String>): List<JsonObject> =
        send("Network.getCookies", buildJsonObject {
            put("urls", buildJsonArray { urls.forEach { add(JsonPrimitive(it)) } })
        })["cookies"]?.jsonArray?.map { it.jsonObject }.orEmpty()

    /** The url of the page now, or null when the host cannot tell. */
    suspend fun url(): String? = runCatching { evaluateValue("location.href")?.let { Json.parseToJsonElement(it).jsonPrimitive.content } }.getOrNull()

    /**
     * Detaches: the page connection is closed and anything still waiting on it fails at once
     * instead of sitting out the send timeout.
     */
    fun close() {
        listener.shutdown("the page connection was closed")
        socket.close(1000, null)
    }

    private suspend fun send(method: String, params: JsonObject): JsonObject = listener.send(socket, method, params)

    internal class Listener(private val binding: String) : WebSocketListener() {
        private val incoming = Channel<String>(Channel.UNLIMITED)
        private val pump = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * Every bridge message, in order and none dropped. The WebSocket thread cannot suspend, and
         * a bounded flow with `tryEmit` silently discarded a message whenever the collector was
         * busy — a bridge reply lost that way leaves the call that wanted it waiting for the full
         * timeout. The channel has no bound; the shared flow in front of it keeps the broadcast
         * behaviour the bridge expects.
         */
        val messages: SharedFlow<String> =
            incoming.receiveAsFlow().shareIn(pump, SharingStarted.Eagerly, replay = 0)

        private val ids = AtomicInteger(0)

        /**
         * The closed state and the commands waiting for an answer. Both live under [guard], so
         * shutdown cannot fall between "is it closed" and "register the waiter": a command that
         * starts as the host closes is refused at once instead of waiting out the send timeout on
         * a socket that will never answer, and every command that was already waiting is failed
         * with the shutdown reason rather than silently cleared.
         */
        private val guard = Any()
        private val pending = HashMap<Int, CompletableDeferred<JsonObject>>()
        private var closed: IllegalStateException? = null

        val isOpen: Boolean get() = synchronized(guard) { closed == null }

        val opened = CompletableDeferred<Unit>()
        private val loads = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val pageLoads: SharedFlow<Unit> = loads

        suspend fun send(ws: WebSocket, method: String, params: JsonObject): JsonObject {
            val id = ids.incrementAndGet()
            val waiter = CompletableDeferred<JsonObject>()
            synchronized(guard) {
                closed?.let { throw IllegalStateException(it.message ?: "the page connection was closed") }
                pending[id] = waiter
            }
            val sent = ws.send(buildJsonObject { put("id", id); put("method", method); put("params", params) }.toString())
            if (!sent) {
                // The socket is already closing or closed: waiting 30 s for an answer that cannot
                // come would stall every caller behind it (a Connect that appears to hang). A
                // successful send only says the frame was queued, not that it was answered.
                synchronized(guard) { pending.remove(id) }
                throw IllegalStateException("the browser is not accepting commands ($method)")
            }
            return try {
                // Only this call's own timeout becomes a browser failure. A caller's own timeout
                // or a cancelled screen must see its cancellation, not an invented browser error.
                withTimeoutOrNull(SEND_TIMEOUT_MS) { waiter.await() }
                    ?: throw IllegalStateException("the browser did not answer $method within ${SEND_TIMEOUT_MS / 1000} s")
            } finally {
                synchronized(guard) { pending.remove(id) }
            }
        }

        /** Ends [messages] and fails anything still waiting: a closed host answers nothing. */
        fun shutdown(reason: String) {
            val failure = IllegalStateException(reason)
            val waiting = synchronized(guard) {
                if (closed == null) closed = failure
                pending.values.toList().also { pending.clear() }
            }
            opened.completeExceptionally(failure)
            waiting.forEach { it.completeExceptionally(failure) }
            incoming.close()
        }

        override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // A frame the host cannot parse must not take the whole connection down with it (it
            // would look like the browser died, and the page would be dropped for no reason).
            runCatching { handle(text, webSocket) }
        }

        private fun handle(text: String, webSocket: WebSocket) {
            val msg = json.parseToJsonElement(text).jsonObject
            msg["id"]?.let { id ->
                val waiter = synchronized(guard) { pending.remove(id.jsonPrimitive.int) } ?: return
                msg["error"]?.let { waiter.completeExceptionally(IllegalStateException(it.toString())) }
                    ?: waiter.complete(msg["result"]?.jsonObject ?: JsonObject(emptyMap()))
                return
            }
            val params = msg["params"]?.jsonObject ?: return
            when (msg["method"]?.jsonPrimitive?.content) {
                "Runtime.bindingCalled" ->
                    if (params["name"]?.jsonPrimitive?.content == binding) incoming.trySend(params["payload"]!!.jsonPrimitive.content)
                "Fetch.requestPaused" -> {
                    val requestId = params["requestId"]!!.jsonPrimitive.content
                    val request = params["request"]!!.jsonObject
                    val url = request["url"]!!.jsonPrimitive.content
                    // The device registration goes through as the Web Player sends it (see
                    // DeviceRegistration.withDeviceName): the desktop host keeps the browser's own name.
                    val reply = if (AdBlock.isBlocked(url)) {
                        buildJsonObject { put("id", ids.incrementAndGet()); put("method", "Fetch.failRequest"); putJsonObject("params") { put("requestId", requestId); put("errorReason", "BlockedByClient") } }
                    } else {
                        buildJsonObject { put("id", ids.incrementAndGet()); put("method", "Fetch.continueRequest"); putJsonObject("params") { put("requestId", requestId) } }
                    }
                    webSocket.send(reply.toString())
                }
                "Page.loadEventFired" -> loads.tryEmit(Unit)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            shutdown("the browser closed the page connection ($code${if (reason.isNotBlank()) ": $reason" else ""})")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            shutdown("the browser closed the page connection ($code${if (reason.isNotBlank()) ": $reason" else ""})")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            shutdown(t.message ?: "the page connection failed")
        }

        private companion object {
            const val SEND_TIMEOUT_MS = 30_000L
        }
    }

    companion object {
        const val BINDING = "__mspHostPost"
        private val json = Json { ignoreUnknownKeys = true }

        /** Attaches to the Web Player tab of the Chrome listening on [cdpUrl]. */
        suspend fun connect(
            cdpUrl: String = "http://127.0.0.1:9333",
            http: OkHttpClient = defaultHttp,
            startUrl: String = "https://open.spotify.com/",
            binding: String = BINDING,
        ): CdpHost {
            val targets = withContext(Dispatchers.IO) {
                http.newCall(Request.Builder().url("$cdpUrl/json/list").build()).execute().use { it.body.string() }
            }
            val page = json.parseToJsonElement(targets).jsonArray.map { it.jsonObject }.firstOrNull {
                it["type"]?.jsonPrimitive?.content == "page" && it["url"]!!.jsonPrimitive.content.startsWith("https://open.spotify.com")
            } ?: withContext(Dispatchers.IO) {
                val created = http.newCall(Request.Builder().url("$cdpUrl/json/new?$startUrl").put(okhttp3.RequestBody.EMPTY).build())
                    .execute().use { it.body.string() }
                json.parseToJsonElement(created).jsonObject
            }
            val wsUrl = page["webSocketDebuggerUrl"]!!.jsonPrimitive.content
            val listener = Listener(binding)
            val socket = http.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
            val host = CdpHost(socket, listener)
            try {
                withTimeout(10_000) { listener.opened.await() }
                host.send("Runtime.enable", buildJsonObject {})
                host.send("Page.enable", buildJsonObject {})
                // Focus emulation from the start: the Web Player refuses to load media on an unfocused page.
                host.send("Emulation.setFocusEmulationEnabled", buildJsonObject { put("enabled", true) })
                host.send("Runtime.addBinding", buildJsonObject { put("name", binding) })
                host.send("Fetch.enable", buildJsonObject {
                    put("patterns", buildJsonArray {
                        AdBlock.hosts.forEach { h -> add(buildJsonObject { put("urlPattern", "*://$h/*") }) }
                        add(buildJsonObject { put("urlPattern", "*/track-playback/v1/devices") })
                    })
                })
                return host
            } catch (e: Throwable) {
                host.close()
                throw e
            }
        }

        private val defaultHttp = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    }
}

/**
 * The browser as a whole (not one tab): what is asked of it whichever tab or window the user is in,
 * as a sign-in that went through another page or a popup.
 */
object CdpBrowser {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()

    /** Ask Chrome to flush its profile and exit before a visible/headless replacement. */
    suspend fun closeBrowser(cdpUrl: String): Boolean {
        val version = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url("$cdpUrl/json/version").build()).execute().use { it.body.string() }
        }
        val url = json.parseToJsonElement(version).jsonObject["webSocketDebuggerUrl"]!!.jsonPrimitive.content
        val answer = CompletableDeferred<Boolean>()
        val socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            private var sent = false
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sent = webSocket.send("""{"id":1,"method":"Browser.close"}""")
                if (!sent) answer.complete(false)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val reply = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                if (reply["id"]?.jsonPrimitive?.int == 1) answer.complete(reply["error"] == null)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { answer.complete(sent) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { answer.complete(sent) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                answer.completeExceptionally(t)
            }
        })
        return try { withTimeout(2_000) { answer.await() } } finally { socket.cancel() }
    }

    /** Account cookie names for [host], including parent-domain cookies; never their values. */
    suspend fun cookieNames(cdpUrl: String, host: String): Set<String> {
        val version = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url("$cdpUrl/json/version").build()).execute().use { it.body.string() }
        }
        val ws = json.parseToJsonElement(version).jsonObject["webSocketDebuggerUrl"]!!.jsonPrimitive.content
        val answer = CompletableDeferred<String>()
        val socket = http.newWebSocket(Request.Builder().url(ws).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"id":1,"method":"Storage.getCookies"}""") }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (runCatching { json.parseToJsonElement(text).jsonObject["id"]?.jsonPrimitive?.int == 1 }.getOrDefault(false)) {
                    answer.complete(text)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { answer.completeExceptionally(t) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                answer.completeExceptionally(IOException("The browser closed the cookie connection"))
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                answer.completeExceptionally(IOException("The browser closed the cookie connection"))
            }
        })
        try {
            val text = withTimeout(10_000) { answer.await() }
            val reply = json.parseToJsonElement(text).jsonObject
            check(reply["error"] == null) { "The browser could not read the account cookies" }
            val cookies = reply["result"]?.jsonObject?.get("cookies")?.jsonArray
                ?: error("The browser did not return account cookies")
            return cookies.mapNotNull { c ->
                val o = c.jsonObject
                val domain = o["domain"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (cookieDomainMatches(domain, host)) o["name"]?.jsonPrimitive?.content else null
            }.toSet()
        } finally { socket.close(1000, null) }
    }

    /** Opens [url] in a new tab of the Chrome listening on [cdpUrl]; returns its target id, or null. */
    suspend fun openTab(cdpUrl: String, url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$cdpUrl/json/new?$url").put(okhttp3.RequestBody.EMPTY).build())
                .execute().use { json.parseToJsonElement(it.body.string()).jsonObject["id"]?.jsonPrimitive?.content }
        }.getOrNull()
    }
}

/** A cookie for .youtube.com applies to music.youtube.com; a host-only cookie does not. */
internal fun cookieDomainMatches(domain: String, host: String): Boolean {
    val cookieHost = domain.removePrefix(".").lowercase(Locale.ROOT)
    val requestHost = host.lowercase(Locale.ROOT)
    return cookieHost.isNotEmpty() && (requestHost == cookieHost ||
        (domain.startsWith('.') && requestHost.endsWith(".$cookieHost")))
}
