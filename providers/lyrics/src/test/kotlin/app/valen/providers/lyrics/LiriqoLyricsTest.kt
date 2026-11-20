/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.lyrics

import app.valen.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** The public API is replaced by an in-memory HTTP interceptor; all words are invented. */
@Timeout(10)
class LiriqoLyricsTest {
    private data class Reply(val code: Int = 200, val body: String, val headers: Map<String, String> = emptyMap())
    private val requests = CopyOnWriteArrayList<Request>()
    private var reply: (Request) -> Reply = { Reply(body = envelope(rich)) }
    private var now = 0L
    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val answer = reply(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(answer.code).message("fixture")
            .apply { answer.headers.forEach { (k, v) -> header(k, v) } }
            .body(answer.body.toResponseBody()).build()
    }.build()
    private val query = LyricsQuery("Paper Moons", "The Walls", album = "Chalk", durationMs = 181_234)
    private val song = Track(ProviderId.SOUNDCLOUD, "fixture", query.title, query.artist, durationMs = 181_234)
    private val rich = """{"provider":"netease","upstreamProvider":"NetEase Cloud Music","syncLevel":"word","nativeWordTiming":true,"timed":[
        {"id":0,"text":"chalk lines","start":1000,"end":5000,"agent":"v1","words":[
            {"text":"chalk ","start":1234,"end":1765},{"text":"lines","start":2200,"end":3456}]},
        {"id":1,"text":"on the wall","start":8000,"end":10000}]}"""
    private fun envelope(primary: String = "null", tracks: String = "[]", metadata: String? = null) =
        """{"primary":$primary,"tracks":$tracks${metadata?.let { ",\"metadata\":$it" }.orEmpty()}}"""
    private fun source() = LiriqoLyrics(http, "https://lyrics.example/v1/".toHttpUrl(), clock = { now })
    private fun quote(text: String) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(text))

    @AfterEach
    fun stop() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    @Test
    fun `metadata duration uses seconds but native windows and gaps stay in milliseconds`() = runBlocking {
        val lyrics = source().find(query.copy(videoId = "abcdefghijk"))!!
        val url = requests.single().url
        assertEquals("/v1/lyrics", url.encodedPath)
        assertEquals("Paper Moons", url.queryParameter("title"))
        assertEquals("The Walls", url.queryParameter("artist"))
        assertEquals("Chalk", url.queryParameter("album"))
        assertEquals("181.234", url.queryParameter("duration"))
        assertEquals("abcdefghijk", url.queryParameter("v"))
        assertNull(url.queryParameter("fast"), "the default quality-aware mode keeps the chance of richer timing")
        assertNotNull(requests.single().header("User-Agent"))
        assertNull(requests.single().header("Authorization"))
        assertEquals(LyricsTiming.Words, lyrics.timing)
        assertEquals(listOf(LyricWord("chalk", 1234, 1765, true), LyricWord("lines", 2200, 3456, false)), lyrics.lines[0].words)
        assertEquals(5000, lyrics.lines[0].endMs)
        assertEquals(8000, lyrics.lines[1].startMs)
        assertTrue(lyrics.lines[1].words.isEmpty(), "a line without native units stays line-timed")
        assertEquals("v1", lyrics.lines[0].voice)
        assertEquals("LiriQo · NetEase Cloud Music", lyrics.source)
    }

    @Test
    fun `character and syllable units keep adjacency punctuation and overlapping windows`() = runBlocking {
        reply = { Reply(body = envelope("""{"provider":"petitlyrics","syncLevel":"character","timed":[
            {"text":"你好！","start":0,"end":3000,"words":[{"text":"你","start":0,"end":1800},{"text":"好","start":1500,"end":3000}]},
            {"text":"sunlight","start":4000,"end":7000,"words":[{"text":"sun","start":4000,"end":5000},{"text":"light","start":5000,"end":7000}]}]}""")) }
        val lyrics = source().find(query)!!
        assertEquals(listOf(LyricWord("你", 0, 1800, false), LyricWord("好！", 1500, 3000, false)), lyrics.lines[0].words)
        assertEquals(listOf("sun", "light"), lyrics.lines[1].words.map { it.text })
        assertTrue(lyrics.lines[1].words.none { it.space })
    }

    @Test
    fun `legacy word maps use line id and embedded words win`() = runBlocking {
        for (embedded in listOf(false, true)) {
            reply = { Reply(body = envelope("""{"provider":"netease","syncLevel":"word",
                "words":{"7":[{"text":"chalk","start":1200,"end":1600}]},
                "timed":[{"id":7,"text":"chalk","start":1000,"end":2000${if (embedded) ",\"words\":[{\"text\":\"chalk\",\"start\":1300,\"end\":1900}]" else ""}}]}""")) }
            assertEquals(if (embedded) 1300 else 1200, source().find(query)!!.lines.single().words.single().startMs)
        }
    }

    @Test
    fun `top level word arrays and null embedded words are supported`() = runBlocking {
        reply = { Reply(body = envelope("""{"provider":"netease","words":[[{"text":"chalk","start":1000,"end":2000}]],
            "timed":[{"id":0,"text":"chalk","start":1000,"end":2000,"words":null}]}""")) }
        assertEquals(LyricsTiming.Words, source().find(query)!!.timing)
    }

    @Test
    fun `line timing at zero and plain lyrics do not invent words`() = runBlocking {
        reply = { Reply(body = envelope("""{"provider":"lrclib","syncLevel":"line","timed":[{"text":"chalk lines","start":0,"end":3500}]}""")) }
        val line = source().find(query)!!
        assertEquals(LyricsTiming.Lines, line.timing)
        assertEquals(3500, line.lines.single().endMs)
        assertTrue(line.lines.single().words.isEmpty())
        reply = { Reply(body = envelope("""{"provider":"ytm_line","plain":"chalk lines\non the wall","upstreamProvider":"YouTube Music","lyricsSource":"LyricFind"}""")) }
        val plain = source().find(query)!!
        assertEquals(LyricsTiming.None, plain.timing)
        assertEquals("LiriQo · YouTube Music · LyricFind", plain.source)
    }

    @Test
    fun `invalid native unit sets retain the line without karaoke timing`() = runBlocking {
        val units = listOf(
            """{"text":"chalk","start":-1,"end":2000}""",
            """{"text":"chalk","start":1000,"end":1000}""",
            """{"text":"chalk","start":"NaN","end":2000}""",
            """{"text":"chalk","start":1000,"end":5000}""",
            """{"text":"chalk","start":1000,"end":2000},{"text":"lines","start":900,"end":2500}""",
            """{"text":"chalk","start":1000,"end":2000}""",
        )
        for (words in units) {
            reply = { Reply(body = envelope("""{"provider":"netease","syncLevel":"word","timed":[{"text":"chalk lines","start":1000,"end":3000,"words":[$words]}]}""")) }
            val lyrics = source().find(query)!!
            assertEquals(LyricsTiming.Lines, lyrics.timing)
            assertEquals("chalk lines", lyrics.lines.single().text)
            assertTrue(lyrics.lines.single().words.isEmpty())
        }
    }

    @Test
    fun `derived and phrase timing never masquerade as native words`() = runBlocking {
        for (modifier in listOf("\"provider\":\"lrclib_wordsync\"", "\"nativeWordTiming\":false", "\"granularity\":\"phrase\"")) {
            val track = when {
                modifier.contains("provider") -> rich.replace("\"provider\":\"netease\"", modifier)
                modifier.contains("nativeWordTiming") -> rich.replace("\"nativeWordTiming\":true", modifier)
                else -> rich.replace("\"id\":0", "\"id\":0,$modifier")
            }
            reply = { Reply(body = envelope(track)) }
            assertEquals(LyricsTiming.Lines, source().find(query)!!.timing)
        }
    }

    @Test
    fun `the best usable timing wins even when primary only contains metadata`() = runBlocking {
        reply = { Reply(body = envelope("""{"provider":"lrclib"}""", "[$rich]")) }
        assertEquals(LyricsTiming.Words, source().find(query)!!.timing)
        reply = { Reply(body = envelope("""{"provider":"lrclib","plain":"chalk"}""", "[$rich]")) }
        assertEquals(LyricsTiming.Words, source().find(query)!!.timing)
    }

    @Test
    fun `TTML retains singer and backing vocals while normalized words survive malformed TTML`() = runBlocking {
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>
            <p begin="00:01.000" end="00:04.000" ttm:agent="v1"><span begin="00:01.000" end="00:02.000">chalk </span><span begin="00:02.100" end="00:04.000">lines</span><span ttm:role="x-bg"><span begin="00:02.000" end="00:03.000">oh</span></span></p>
            </div></body></tt>"""
        reply = { Reply(body = envelope("""{"provider":"apple_music_liriqo","ttml":${quote(ttml)}}""")) }
        val lyrics = source().find(query)!!
        assertEquals(LyricsTiming.Words, lyrics.timing)
        assertEquals("v1", lyrics.lines.first().voice)
        assertEquals("oh", lyrics.lines.first().backingText)
        reply = { Reply(body = envelope(rich.dropLast(1) + ",\"ttml\":\"not a TTML document\"}")) }
        assertEquals(LyricsTiming.Words, source().find(query)!!.timing)
    }

    @Test
    fun `instrumental cues are not sung lines and line ends are not stretched across gaps`() = runBlocking {
        reply = { Reply(body = envelope("""{"provider":"netease","timed":[
            {"text":"chalk","start":1000,"end":2000},
            {"text":"music","start":2000,"end":8000,"isInstrumental":true},
            {"text":"lines","start":8000,"end":9000}]}""")) }
        val lyrics = source().find(query)!!
        assertEquals(2, lyrics.lines.size)
        assertEquals(2000, lyrics.lines.first().endMs)
        assertEquals(8000, lyrics.lines.last().startMs)
    }

    @Test
    fun `normalized backing vocals feed the existing backing renderer with native windows`() = runBlocking {
        reply = { Reply(body = envelope("""{"provider":"unison_community","timed":[
            {"text":"chalk","start":1000,"end":5000,"agent":"v1","words":[{"text":"chalk","start":1234,"end":4567}]},
            {"text":"oh","start":2000,"end":3000,"isBackground":true,"words":[{"text":"oh","start":2222,"end":2777}]}]}""")) }
        val lyrics = source().find(query)!!
        assertEquals(1, lyrics.lines.size)
        assertEquals("oh", lyrics.lines.single().backingText)
        assertEquals(listOf(LyricWord("oh", 2222, 2777, false)), lyrics.lines.single().backing)
        assertEquals(4567, lyrics.lines.single().words.single().endMs)
    }

    @Test
    fun `a different artist version or duration is not used for this recording`() = runBlocking {
        for (metadata in listOf("""{"artist":"Someone Else"}""", """{"title":"Paper Moons (Live)"}""", """{"duration":220}""")) {
            reply = { Reply(body = envelope(rich, metadata = metadata)) }
            assertNull(source().find(query))
        }
        reply = { Reply(body = envelope(rich, metadata = """{"duration":"3:01"}""")) }
        assertNotNull(source().find(query))
    }

    @Test
    fun `misses differ from transport errors malformed JSON and JSON error envelopes`() = runBlocking {
        reply = { Reply(404, "missing") }
        assertNull(source().find(query))
        reply = { Reply(body = envelope()) }
        assertNull(source().find(query))
        for (answer in listOf(Reply(500, "busy"), Reply(body = "not json"), Reply(body = "{}"), Reply(body = """{"error":"temporary lookup failure"}"""))) {
            reply = { answer }
            assertThrows<LyricsUnavailable> { source().find(query) }
        }
    }

    @Test
    fun `rate limits respect Retry-After without polling or blocking fallback sources`() = runBlocking {
        reply = { Reply(429, "busy", mapOf("Retry-After" to "120")) }
        val source = source()
        assertThrows<LyricsUnavailable> { source.find(query) }
        assertThrows<LyricsUnavailable> { source.find(query.copy(title = "Another")) }
        assertEquals(1, requests.size)
        now = 120_000
        reply = { Reply(body = envelope(rich)) }
        assertNotNull(source.find(query))
        assertEquals(2, requests.size)
    }

    @Test
    fun `an HTTP date Retry-After is respected and forbidden requests are not retried`() = runBlocking {
        now = 1_700_000_000_000
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now + 90_000).atOffset(ZoneOffset.UTC))
        reply = { Reply(503, "busy", mapOf("Retry-After" to date)) }
        val source = source()
        assertThrows<LyricsUnavailable> { source.find(query) }
        now += 89_999
        assertThrows<LyricsUnavailable> { source.find(query) }
        assertEquals(1, requests.size)
        now++
        reply = { Reply(403, "forbidden") }
        assertThrows<LyricsUnavailable> { source.find(query) }
        assertEquals(2, requests.size)
    }

    @Test
    fun `cancellation is propagated rather than converted into a failed source`() = runBlocking {
        reply = { Thread.sleep(100); Reply(body = envelope(rich)) }
        assertThrows<CancellationException> { withTimeout(10) { source().find(query) } }
    }

    private fun lyprio() = LyprioEndpoint.of("https://lyprio.example", Base64.getEncoder().encodeToString(ByteArray(32) { 1 }))!!

    @Test
    fun `both platforms get LiriQo before all existing fallbacks`() {
        val spotify = object : LyricsSource { override val name = "Spotify"; override suspend fun find(query: LyricsQuery): Lyrics? = null }
        assertEquals(listOf("LiriQo", "Lyprio", "Spotify", "LRCLIB"), LyricsSetup.sources(http, lyprio(), listOf(spotify)).map { it.name })
    }

    @Test
    fun `a primary hit bypasses fallbacks and a miss falls back to configured Lyprio`() = runBlocking {
        val book = LyricsBook(LyricsSetup.sources(http, lyprio()))
        assertEquals(LyricsTiming.Words, (book.lyricsFor(song) as LyricsState.Found).lyrics.timing)
        assertEquals(1, requests.size)
        requests.clear()
        reply = { request ->
            if (request.url.host.startsWith("api.liriqo")) Reply(404, "missing") else Reply(body = """{"complete":true,"results":[{"subprovider":"MUSIXMATCH","format":"lrc","lyrics":"[00:01.00]chalk lines"}]}""",
                headers = mapOf("X-Lyprio-Protocol" to "2"))
        }
        val fallback = (LyricsBook(LyricsSetup.sources(http, lyprio())).lyricsFor(song) as LyricsState.Found).lyrics
        assertEquals("Lyprio · Musixmatch", fallback.source)
        assertEquals(2, requests.size)
    }

    @Test
    fun `a failed primary still reaches service lyrics and LRCLIB`() = runBlocking {
        var serviceCalls = 0
        val service = object : LyricsSource {
            override val name = "Spotify"
            override suspend fun find(query: LyricsQuery): Lyrics { serviceCalls++; return Lyrics(listOf(LyricLine(1000, 2000, "chalk")), LyricsTiming.Lines, name) }
        }
        reply = { Reply(503, "busy") }
        val fromService = LyricsBook(LyricsSetup.sources(http, null, listOf(service))).lyricsFor(song) as LyricsState.Found
        assertEquals("Spotify", fromService.lyrics.source)
        assertEquals(1, serviceCalls)
        requests.clear()
        reply = { request -> if (request.url.host.startsWith("api.liriqo")) Reply(503, "busy")
            else Reply(body = """{"trackName":"Paper Moons","artistName":"The Walls","syncedLyrics":"[00:01.00]chalk lines"}""") }
        val fromLrclib = LyricsBook(LyricsSetup.sources(http, null)).lyricsFor(song) as LyricsState.Found
        assertEquals("LRCLIB", fromLrclib.lyrics.source)
        assertEquals(2, requests.size)
    }

    @Test
    fun `old cached lines and misses cannot bypass LiriQo and are not deleted`(@TempDir dir: Path) = runBlocking {
        val key = LyricsBook.keyOf(song)
        val previous = Lyrics(listOf(LyricLine(1000, 2000, "chalk")), LyricsTiming.Lines, "LRCLIB")
        for ((i, saved) in listOf(previous, null, previous, null, previous, null).withIndex()) {
            val folder = dir.resolve("case-$i")
            val old = DiskLyricsStore(when (i / 2) { 0 -> folder; 1 -> folder.resolve("liriqo-v1"); else -> folder.resolve("liriqo-v2") })
            old.write(key, saved)
            val current = LyricsSetup.store(folder)
            assertNull(current.read(key))
            val book = LyricsBook(LyricsSetup.sources(http, null), current)
            assertEquals(LyricsTiming.Words, (book.lyricsFor(song) as LyricsState.Found).lyrics.timing)
            assertNotNull(old.read(key))
            assertEquals(saved, old.read(key)?.lyrics)
            assertEquals(LyricsTiming.Words, current.read(key)?.lyrics?.timing)
            LyricsBook(LyricsSetup.sources(http, null), current).lyricsFor(song)
            assertEquals(i + 1, requests.size, "the new cache is reused")
        }
    }
}
