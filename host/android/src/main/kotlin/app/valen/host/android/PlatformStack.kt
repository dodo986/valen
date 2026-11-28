/* SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapted from Revera (https://github.com/caruno-git/Revera), base/host-android, `PlatformStack.kt`
 * at c0b705c, which forks Myspoti/Orpheon (https://github.com/kota-zo/myspoti). See NOTICE.
 */
package app.valen.host.android

import android.content.Context
import android.net.http.HttpEngine
import android.net.http.HttpException
import android.net.http.UploadDataProvider
import android.net.http.UploadDataSink
import android.net.http.UrlRequest
import android.net.http.UrlResponseInfo
import androidx.annotation.RequiresApi
import okhttp3.Headers
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * The platform's own HTTP stack (`android.net.http`, Cronet in the system since Android 14).
 *
 * [LoginTransport] uses it on API 34+ so a relayed sign-in request leaves on Chromium's network
 * stack, like the WebView's own `fetch` would, instead of OkHttp's: the TLS and HTTP/2 fingerprint
 * of the request is then the browser's too. Below 34 there is no platform stack and the transport
 * falls back to OkHttp.
 *
 * The request carries the headers the transport built, less `Accept-Encoding`: this stack writes
 * its own, as Chrome does, and decodes what it asked for. It adds no `X-Requested-With` (that header
 * is the WebView's own) and no cookie: the caller passes the page's cookie itself.
 */

/** A body from memory (the platform has no ready-made one). */
@RequiresApi(34)
private class BytesUpload(private val bytes: ByteArray) : UploadDataProvider() {
    private var at = 0

    override fun getLength(): Long = bytes.size.toLong()

    override fun read(sink: UploadDataSink, buffer: ByteBuffer) {
        val n = minOf(buffer.remaining(), bytes.size - at)
        buffer.put(bytes, at, n)
        at += n
        sink.onReadSucceeded(false)
    }

    override fun rewind(sink: UploadDataSink) {
        at = 0
        sink.onRewindSucceeded()
    }
}

@RequiresApi(34)
internal object PlatformStack {
    @Volatile private var engine: HttpEngine? = null
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "valen-platform-http").apply { isDaemon = true } }

    private fun engine(context: Context): HttpEngine =
        engine ?: synchronized(this) {
            engine ?: HttpEngine.Builder(context).setEnableBrotli(true).build().also { engine = it }
        }

    /**
     * Sends [method] to [url] with [headers] and [sent] as a JSON body; [done] is called once with
     * the status (0 when the request never got an answer), the response headers (all of them, in
     * order, `Set-Cookie`s included), the decoded text, the protocol and a failure type.
     */
    fun fetch(
        context: Context,
        url: String,
        method: String,
        headers: Headers,
        sent: String?,
        done: (code: Int, headers: List<Pair<String, String>>, text: String, protocol: String, failure: String?) -> Unit,
    ) {
        val out = ByteArrayOutputStream()
        val callback = object : UrlRequest.Callback {
            override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) = request.followRedirect()

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) = request.read(ByteBuffer.allocateDirect(32 * 1024))

            override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, buffer: ByteBuffer) {
                buffer.flip()
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                out.write(bytes)
                buffer.clear()
                request.read(buffer)
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) =
                done(info.httpStatusCode, info.headers.asList.map { it.key to it.value }, out.toString(Charsets.UTF_8.name()), info.negotiatedProtocol, null)

            override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: HttpException) =
                done(0, emptyList(), "", "", LoginDiagnostics.failureType(error.message))

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) = done(0, emptyList(), "", "", "canceled")
        }
        runCatching {
            val builder = engine(context).newUrlRequestBuilder(url, executor, callback).setHttpMethod(method)
            headers.forEach { (name, value) -> if (!name.equals("Accept-Encoding", true)) builder.addHeader(name, value) }
            if (method != "GET" && method != "HEAD") {
                // This stack refuses a body without a type: the one a browser's fetch gives a string body.
                if (headers["Content-Type"] == null) builder.addHeader("Content-Type", "text/plain;charset=UTF-8")
                builder.setUploadDataProvider(BytesUpload((sent ?: "").toByteArray()), executor)
            }
            builder.build().start()
        }.onFailure { done(0, emptyList(), "", "", LoginDiagnostics.failureType(it)) }
    }
}