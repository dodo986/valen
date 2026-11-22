/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SoundCloud's public set search against a fake site: the request it makes, and the sets it reads
 * out of the answer (playlists and albums, which SoundCloud keeps as one kind). All words invented.
 */
class SoundcloudPlaylistSearchTest {

    private val clientId = "QhVEWrrJKvW4uSU5u5EoI0FTXYUGzy5k"
    private val home = """<script>window.__sc_hydration = [{"hydratable":"apiClient","data":{"id":"$clientId","isExpiring":true}}];</script>"""
    private val requests = CopyOnWriteArrayList<Request>()

    private val answer = """
        {"collection":[
          {"kind":"playlist","id":2317140903,"title":"ghostland forever (deluxe)","track_count":23,
           "permalink_url":"https://soundcloud.com/overtonightofficial/sets/ghostland-forever-deluxe",
           "artwork_url":"https://i1.sndcdn.com/artworks-x-large.jpg","is_album":true,
           "user":{"username":"overtonight"}},
          {"kind":"playlist","id":2317140911,"title":"ghostland forever","track_count":18,
           "permalink_url":"https://soundcloud.com/ratthew-520289455/sets/ghostland-forever",
           "artwork_url":null,"is_album":false,
           "user":{"username":"ratthew"}},
          {"kind":"track","id":9,"title":"not a set","user":{"username":"someone"}}
        ],"next_href":"https://api-v2.soundcloud.com/search/playlists?query_urn=abc&offset=5"}
    """.trimIndent()

    private fun audio() = SoundcloudAudio(
        http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = when (request.url.host) {
                "soundcloud.com" -> home
                "api-v2.soundcloud.com" -> answer
                else -> ""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody()).build()
        }.build(),
    )

    @Test
    fun `sets by name are read with their owner and the address that opens them`() = runBlocking {
        val found = audio().searchPlaylists("ghostland forever", 5)

        // The track row in the same answer is skipped: a search of sets answers sets.
        assertEquals(listOf("ghostland forever (deluxe)", "ghostland forever"), found.map { it.name })
        assertEquals(listOf("overtonight", "ratthew"), found.map { it.owner })
        assertEquals(listOf(23, 18), found.map { it.trackCount })
        assertEquals(
            "https://soundcloud.com/overtonightofficial/sets/ghostland-forever-deluxe",
            found.first().permalink,
        )
        // A cover is asked for in a size a card can use.
        assertEquals("https://i1.sndcdn.com/artworks-x-t500x500.jpg", found.first().artworkUrl)
        assertEquals(null, found.last().artworkUrl)

        // Public data: one request for the search, the runtime client id on it, the words and the limit.
        val search = requests.single { it.url.encodedPath == "/search/playlists" }
        assertEquals("ghostland forever", search.url.queryParameter("q"))
        assertEquals("5", search.url.queryParameter("limit"))
        assertEquals(clientId, search.url.queryParameter("client_id"))
        assertEquals(null, search.header("Authorization"), "a set search needs no session")
        assertTrue(requests.any { it.url.host == "soundcloud.com" }, "the client id is read from the site")
    }

    @Test
    fun `an answer with no sets is an empty search, not a failure`() = runBlocking {
        val empty = SoundcloudAudio(
            http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val body = if (request.url.host == "soundcloud.com") home else """{"collection":[]}"""
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(body.toResponseBody()).build()
            }.build(),
        )
        assertEquals(emptyList<SoundcloudPlaylist>(), empty.searchPlaylists("nothing at all", 8))
    }
}