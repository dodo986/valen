/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.native

import app.valen.audio.SoundcloudAudio
import app.valen.core.PlaylistLink
import app.valen.core.ProviderId
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SoundCloud's set search as the search screen sees it: playlists and albums, each one openable. */
class SoundcloudPlaylistCatalogTest {

    private val clientId = "QhVEWrrJKvW4uSU5u5EoI0FTXYUGzy5k"

    private fun catalog(answer: String): SoundcloudPlaylistCatalog {
        val home = """<script>window.__sc_hydration = [{"hydratable":"apiClient","data":{"id":"$clientId","isExpiring":true}}];</script>"""
        val audio = SoundcloudAudio(
            http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val body = when (request.url.host) {
                    "soundcloud.com" -> home
                    else -> answer
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                    .body(body.toResponseBody()).build()
            }.build(),
        )
        return SoundcloudPlaylistCatalog(audio)
    }

    private val answer = """
        {"collection":[
          {"kind":"playlist","id":2317140903,"title":"ghostland forever (deluxe)","track_count":23,
           "permalink_url":"https://soundcloud.com/overtonightofficial/sets/ghostland-forever-deluxe",
           "artwork_url":"https://i1.sndcdn.com/artworks-x-large.jpg","is_album":true,
           "user":{"username":"overtonight"}},
          {"kind":"playlist","id":2317140911,"title":"ghostland forever","track_count":18,
           "permalink_url":"https://soundcloud.com/ratthew-520289455/sets/ghostland-forever",
           "artwork_url":null,"is_album":false,"user":{"username":"ratthew"}},
          {"kind":"playlist","id":5,"title":"no address","track_count":3,"user":{"username":"nobody"}},
          {"kind":"track","id":9,"title":"not a set"}
        ]}
    """.trimIndent()

    @Test
    fun `found sets are rows the search screen can open, albums included`() = runBlocking {
        val found = catalog(answer).findPlaylists("ghostland forever", 5)

        // Albums are sets too: SoundCloud keeps both as one kind, and both import the same way.
        assertEquals(listOf("ghostland forever (deluxe)", "ghostland forever"), found.map { it.title })
        assertEquals(listOf(ProviderId.SOUNDCLOUD, ProviderId.SOUNDCLOUD), found.map { it.provider })
        assertEquals(listOf("overtonight", "ratthew"), found.map { it.owner })
        assertEquals("https://i1.sndcdn.com/artworks-x-t500x500.jpg", found.first().artworkUrl)
        assertEquals(
            PlaylistLink(
                ProviderId.SOUNDCLOUD,
                "2317140903",
                "https://soundcloud.com/overtonightofficial/sets/ghostland-forever-deluxe",
            ),
            found.first().link,
        )
        // A set without a page address could not be opened, so it is not offered.
        assertTrue(found.none { it.title == "no address" })
    }
}