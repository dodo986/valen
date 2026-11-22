/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The signed-in side of SoundCloud's API against a fake browser: an access token that expires, the
 * refresh token that renews it, the account listing that moved to `/users/{id}/playlists`, and a
 * stale session that must never block importing a public set. All words and tokens are invented.
 */
class SoundcloudAccountTest {

    private val nowMs = 1_800_000_000_000L
    private val clientId = "QhVEWrrJKvW4uSU5u5EoI0FTXYUGzy5k"
    private val home = """<script>window.__sc_hydration = [{"hydratable":"apiClient","data":{"id":"$clientId","isExpiring":true}}];</script>"""
    private val requests = CopyOnWriteArrayList<Request>()
    private var refreshed: SoundcloudSession? = null
    private var refreshCount = 0

    private fun jwt(expSeconds: Long): String {
        fun enc(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
        return enc("""{"alg":"none"}""") + "." + enc("""{"exp":$expSeconds}""") + "."
    }

    private fun audio(answer: (Request) -> Pair<Int, String>, persist: (suspend (SoundcloudSession) -> Unit)? = { refreshed = it }) =
        SoundcloudAudio(
            http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                requests += request
                val (code, body) = answer(request)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                    .body(body.toResponseBody()).build()
            }.build(),
            now = { nowMs },
            persist = persist,
        )

    private fun answerApi(request: Request, playlists: String, unauthorized: (Request) -> Boolean = { false }): Pair<Int, String> = when {
        request.url.host == "soundcloud.com" -> 200 to home
        request.url.host == "secure.soundcloud.com" -> {
            refreshCount++
            200 to """{"access_token":"${jwt(nowMs / 1000 + 86_400)}","refresh_token":"refresh-2","expires_in":86400,"token_type":"bearer"}"""
        }

        request.url.encodedPath == "/me" -> if (unauthorized(request)) 401 to "" else 200 to """{"id":1379887933,"username":"dodo"}"""
        request.url.encodedPath.startsWith("/users/1379887933/playlists") ->
            if (unauthorized(request)) 401 to "" else 200 to """{"collection":[$playlists],"next_href":null}"""

        else -> 404 to ""
    }

    private val onePlaylist = """{"kind":"playlist","id":"2305468899","title":"yungmusicplayer","track_count":8,"permalink_url":"https://soundcloud.com/dodo/sets/yungmusicplayer","artwork_url":null,"sharing":"public"}"""

    private fun token(request: Request): String? = request.header("Authorization")?.removePrefix("OAuth ")

    @Test
    fun `a jwt states when it expires and an opaque token states nothing`() {
        assertEquals(1_800_000_123_000L, tokenExpiresAtMs(jwt(1_800_000_123)))
        assertNull(tokenExpiresAtMs("2-338174-1234567890"))
        assertNull(tokenExpiresAtMs("not a token"))
    }

    @Test
    fun `an expired access token is renewed and the renewed pair is written back`() = runBlocking {
        val expired = jwt(nowMs / 1000 - 10)
        val source = audio({ answerApi(it, onePlaylist) })

        val playlists = source.accountPlaylists(SoundcloudSession(expired, "refresh-1"))

        assertEquals(listOf("yungmusicplayer"), playlists.map { it.name })
        assertEquals(8, playlists.single().trackCount)
        assertEquals(1, refreshCount)
        assertEquals("refresh-2", refreshed?.refreshToken)
        assertNotNull(refreshed?.accessToken)
        assertTrue(tokenExpiresAtMs(refreshed!!.accessToken)!! > nowMs)
        val refreshRequest = requests.single { it.url.host == "secure.soundcloud.com" }
        assertTrue(refreshRequest.url.encodedPath.endsWith("/oauth/token"))
        val form = refreshRequest.body?.let { body -> okio.Buffer().also { buffer -> body.writeTo(buffer) }.readUtf8() }.orEmpty()
        assertTrue(form.contains("grant_type=refresh_token"))
        assertTrue(form.contains("refresh_token=refresh-1"))
        assertTrue(form.contains("client_id=$clientId"))
    }

    @Test
    fun `a renewed session is kept for the next call instead of refreshing again`() = runBlocking {
        val expired = jwt(nowMs / 1000 - 10)
        val source = audio({ answerApi(it, onePlaylist) })
        source.accountPlaylists(SoundcloudSession(expired, "refresh-1"))
        source.accountPlaylists(SoundcloudSession(expired, "refresh-1"))
        assertEquals(1, refreshCount)
    }

    @Test
    fun `a token refused by the server is renewed once and the call retried`() = runBlocking {
        val stale = jwt(nowMs / 1000 + 3_600)
        fun isStale(request: Request) = token(request) == stale
        val source = audio({ answerApi(it, onePlaylist, unauthorized = ::isStale) })

        val playlists = source.accountPlaylists(SoundcloudSession(stale, "refresh-1"))

        assertEquals(listOf("yungmusicplayer"), playlists.map { it.name })
        assertEquals(1, refreshCount)
        assertEquals(2, requests.count { it.url.encodedPath == "/me" })
    }

    @Test
    fun `an expired session with no refresh token asks for a sign-in, not a 401`() {
        val source = audio({ answerApi(it, onePlaylist) })
        val error = assertThrows(SoundcloudSessionExpired::class.java) {
            runBlocking { source.accountPlaylists(SoundcloudSession(jwt(nowMs / 1000 - 10), null)) }
        }
        assertTrue(error.message!!.contains("sign in again"))
        assertEquals(0, requests.count { it.url.encodedPath == "/me" })
    }

    @Test
    fun `a refused session still imports a public set by asking without it`() = runBlocking {
        val set = """{"kind":"playlist","id":"1","title":"Ghost land forever","track_count":1,"permalink_url":"https://soundcloud.com/x/sets/ghost","tracks":[{"kind":"track","id":"9","title":"Ghost land forever","duration":1000,"permalink_url":"https://soundcloud.com/x/ghost","user":{"username":"x"}}]}"""
        val source = audio({ request ->
            when {
                request.url.host == "secure.soundcloud.com" -> {
                    refreshCount++
                    200 to """{"access_token":"${jwt(nowMs / 1000 + 86_400)}","refresh_token":"refresh-2","token_type":"bearer"}"""
                }

                request.url.encodedPath == "/resolve" && request.header("Authorization") != null -> 401 to ""
                request.url.encodedPath == "/resolve" -> 200 to set
                request.url.host == "soundcloud.com" -> 200 to home
                else -> 404 to ""
            }
        })

        val read = source.playlist("https://soundcloud.com/x/sets/ghost", SoundcloudSession("2-338174-1", "refresh-1"))

        assertEquals("Ghost land forever", read.name)
        assertEquals(listOf("Ghost land forever"), read.tracks.map { it.title })
        val resolveCalls = requests.filter { it.url.encodedPath == "/resolve" }
        assertEquals(3, resolveCalls.size)
        assertNotNull(resolveCalls[0].header("Authorization"))
        assertNotNull(resolveCalls[1].header("Authorization"))
        assertNull(resolveCalls[2].header("Authorization"))
        assertEquals(1, refreshCount)
    }
}