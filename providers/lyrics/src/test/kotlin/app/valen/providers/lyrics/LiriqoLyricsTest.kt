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
    fun `a line label that lags its first word never shows the line late`() = runBlocking {
        reply = { Reply(body = envelope("""{"provider":"netease","syncLevel":"word","timed":[
            {"text":"chalk lines","start":5000,"end":9000,"words":[
                {"text":"chalk ","start":4200,"end":5000},{"text":"lines","start":5000,"end":9000}]}]}""")) }
        val lyrics = source().find(query)!!
        val line = lyrics.lines.single()
        assertEquals(4200, line.startMs, "the line stands from its earliest cue, not from a label that lags it")
        assertEquals(LyricsTiming.Words, lyrics.timing)
        assertEquals(listOf(4200L to 5000L, 5000L to 9000L), line.words.map { it.startMs to it.endMs })
    }

    @Test
    fun `backing word cues sit under the line and never break its own words`() = runBlocking {
        reply = { Reply(body = envelope("""{"provider":"apple_music_liriqo","syncLevel":"syllable","timed":[
            {"text":"paper stars (oh oh)","start":1000,"end":4000,"words":[
                {"text":"paper ","start":1000,"end":1500},
                {"text":"stars","start":1500,"end":2500},
                {"text":"oh ","start":1200,"end":1800,"isBackground":true},
                {"text":"oh","start":1800,"end":2400,"isBackground":true}]}]}""")) }
        val lyrics = source().find(query)!!
        val line = lyrics.lines.single()
        assertEquals(LyricsTiming.Words, lyrics.timing)
        assertEquals(listOf("paper", "stars"), line.words.map { it.text })
        assertEquals(listOf(1000L to 1500L, 1500L to 2500L), line.words.map { it.startMs to it.endMs })
        assertEquals("(oh oh)", line.backingText)
        assertEquals(listOf(1200L to 1800L, 1800L to 2400L), line.backing.map { it.startMs to it.endMs })
    }

    @Test
    fun `a backing cue that cannot be placed does not take the line's words with it`() = runBlocking {
        // The echo starts before the line's own first word, so it is not a cue for the line; the
        // lead words beside it must still be sung word by word.
        reply = { Reply(body = envelope("""{"provider":"apple_music_liriqo","syncLevel":"syllable","timed":[
            {"text":"chalk (oh)","start":1000,"end":3000,"words":[
                {"text":"chalk ","start":1000,"end":2000},
                {"text":"oh","start":900,"end":1400,"isBackground":true}]}]}""")) }
        val line = source().find(query)!!.lines.single()
        assertEquals(listOf("chalk"), line.words.map { it.text })
        assertEquals(listOf(1000L to 2000L), line.words.map { it.startMs to it.endMs })
        assertTrue(line.backing.isEmpty(), "the cue that starts before the line is not used")
        assertEquals(1000, line.startMs)
        assertEquals(3000, line.endMs)
    }

    @Test
    fun `overlapping lines and cues are kept while a broken cue is refused`() = runBlocking {
        reply = { Reply(body = envelope("""{"provider":"unison_community","syncLevel":"syllable","timed":[
            {"text":"chalk","start":1000,"end":5000,"words":[{"text":"cha","start":1000,"end":3000},{"text":"lk","start":2500,"end":5000}]},
            {"text":"lines","start":4000,"end":7000,"words":[{"text":"lines","start":4000,"end":7000}]}]}""")) }
        val lyrics = source().find(query)!!
        assertEquals(2, lyrics.lines.size, "two lines that overlap are still two lines")
        assertEquals(5000, lyrics.lines[0].endMs)
        assertEquals(4000, lyrics.lines[1].startMs)
        assertEquals(listOf(1000L to 3000L, 2500L to 5000L), lyrics.lines[0].words.map { it.startMs to it.endMs })

        reply = { Reply(body = envelope("""{"provider":"unison_community","syncLevel":"syllable","timed":[
            {"text":"chalk","start":1000,"end":5000,"words":[{"text":"chalk","start":3000,"end":3000}]}]}""")) }
        val broken = source().find(query)!!
        assertEquals(LyricsTiming.Lines, broken.timing)
        assertTrue(broken.lines.single().words.isEmpty())
        assertEquals(1000, broken.lines.single().startMs, "a cue that is over before it starts does not move the line")
    }

    @Test
    fun `a zero length punctuation cue does not discard the line's own words`() = runBlocking {
        // LiriQo states punctuation as its own cue, sometimes with no time at all; the line's text
        // still has the comma, so the words around it stay sung word by word.
        reply = { Reply(body = envelope("""{"provider":"netease","syncLevel":"word","timed":[
            {"text":"chalk, lines","start":1000,"end":4000,"words":[
                {"text":"chalk ","start":1000,"end":2000},
                {"text":", ","start":2000,"end":2000},
                {"text":"lines","start":2000,"end":4000}]}]}""")) }
        val lyrics = source().find(query)!!
        val line = lyrics.lines.single()
        assertEquals(LyricsTiming.Words, lyrics.timing)
        assertEquals(listOf("chalk,", "lines"), line.words.map { it.text })
        assertEquals(listOf(1000L to 2000L, 2000L to 4000L), line.words.map { it.startMs to it.endMs })
        assertEquals(4000, line.endMs)
    }

    @Test
    fun `drifting cues are refused at the source and no offset is ever added`() = runBlocking {
        // A word that drifts past its own line's end is not a cue for that line.
        reply = { Reply(body = envelope("""{"provider":"netease","syncLevel":"word","timed":[
            {"text":"chalk lines","start":1000,"end":3000,"words":[
                {"text":"chalk ","start":1000,"end":2000},{"text":"lines","start":2000,"end":9000}]}]}""")) }
        val drifted = source().find(query)!!
        assertEquals(3000, drifted.lines.single().endMs, "a drifting word does not stretch the line")
        assertEquals(LyricsTiming.Lines, drifted.timing)
        assertTrue(drifted.lines.single().words.isEmpty())

        // The times are the ones the source states: a payload that is all late is not shifted back.
        reply = { Reply(body = envelope("""{"provider":"netease","syncLevel":"word","timed":[
            {"text":"chalk","start":3000,"end":5000,"words":[{"text":"chalk","start":3000,"end":5000}]},
            {"text":"lines","start":7000,"end":9000,"words":[{"text":"lines","start":7000,"end":9000}]}]}""")) }
        val stated = source().find(query)!!
        assertEquals(listOf(3000L, 7000L), stated.lines.map { it.startMs })
        assertEquals(listOf(5000L, 9000L), stated.lines.map { it.endMs })
        assertEquals(listOf(3000L, 7000L), stated.lines.map { it.words.single().startMs })

        // A line that begins after the song has ended belongs to a longer version, not to this one.
        reply = { Reply(body = envelope("""{"provider":"netease","syncLevel":"word","timed":[
            {"text":"chalk","start":190000,"end":192000,"words":[{"text":"chalk","start":190000,"end":192000}]}]}""")) }
        val beyond = source().find(query)!!
        assertEquals(LyricsTiming.None, beyond.timing)
        assertEquals("chalk", beyond.lines.single().text)
    }

    @Test
    fun `times that are not whole milliseconds and broken line windows are refused`() = runBlocking {
        reply = { Reply(body = envelope("""{"provider":"netease","syncLevel":"word","timed":[
            {"text":"chalk","start":1000.5,"end":2000,"words":[{"text":"chalk","start":1000,"end":2000}]},
            {"text":"lines","start":3000,"end":4000,"words":[{"text":"lines","start":3000,"end":4000}]}]}""")) }
        val lyrics = source().find(query)!!
        assertEquals(listOf(3000L), lyrics.lines.map { it.startMs })

        reply = { Reply(body = envelope("""{"provider":"netease","syncLevel":"word","timed":[
            {"text":"chalk","start":1000,"end":6000,"words":[
                {"text":"chalk ","start":-4,"end":2000},{"text":"lines","start":2000,"end":6000}]}]}""")) }
        val negative = source().find(query)!!
        assertEquals(LyricsTiming.Lines, negative.timing)
        assertEquals(1000, negative.lines.single().startMs)

        // A whole millisecond written as a float is the same millisecond, not a malformed time.
        reply = { Reply(body = envelope("""{"provider":"netease","syncLevel":"word","timed":[
            {"text":"chalk","start":1000.0,"end":2000.0,"words":[{"text":"chalk","start":1000.0,"end":2000.0}]}]}""")) }
        val whole = source().find(query)!!
        assertEquals(1000, whole.lines.single().startMs)
        assertEquals(LyricsTiming.Words, whole.timing)
    }

    @Test
    fun `a cue in another timebase does not drag its line earlier`() = runBlocking {
        // Word times that look like seconds beside millisecond line labels: the line keeps its own
        // start and the cues are refused, rather than being treated as an offset for the line.
        reply = { Reply(body = envelope("""{"provider":"netease","syncLevel":"word","timed":[
            {"text":"chalk lines","start":60000,"end":64000,"words":[
                {"text":"chalk ","start":0,"end":1000},{"text":"lines","start":1000,"end":2000}]}]}""")) }
        val lyrics = source().find(query)!!
        val line = lyrics.lines.single()
        assertEquals(60000, line.startMs, "a cue far earlier than the label is another timebase, not this line's start")
        assertEquals(64000, line.endMs)
        assertEquals(LyricsTiming.Lines, lyrics.timing)
        assertTrue(line.words.isEmpty())
    }

    @Test
    fun `a candidate of another length is not this song's version`() = runBlocking {
        reply = { Reply(body = envelope(
            """{"provider":"netease","duration":300,"syncLevel":"word","nativeWordTiming":true,"timed":[{"text":"chalk","start":1000,"end":2000}]}""",
            """[{"provider":"lrclib","duration":181,"syncLevel":"line","timed":[{"text":"chalk lines","start":9000,"end":12000}]}]""")) }
        val lyrics = source().find(query)!!
        assertEquals(9000, lyrics.lines.single().startMs, "the five-minute copy's timing is not used for this song")
        assertEquals(LyricsTiming.Lines, lyrics.timing)
    }

    @Test
    fun `a candidate of another length does not outrank one that fits the song`() = runBlocking {
        reply = { Reply(body = envelope(
            """{"provider":"netease","duration":300,"syncLevel":"word","nativeWordTiming":true,"timed":[{"text":"chalk","start":1000,"end":2000}]}""",
            """[{"provider":"lrclib","syncLevel":"line","timed":[{"text":"chalk lines","start":9000,"end":12000}]}]""")) }
        val lyrics = source().find(query)!!
        assertEquals(9000, lyrics.lines.single().startMs, "the copy that fits the song is the one used")
        assertEquals(LyricsTiming.Lines, lyrics.timing)
    }

    @Test
    fun `a line whose own end is before its start keeps its words`() = runBlocking {
        reply = { Reply(body = envelope("""{"provider":"netease","syncLevel":"word","timed":[
            {"text":"chalk","start":1000,"end":500,"words":[{"text":"chalk","start":1000,"end":2000}]},
            {"text":"lines","start":3000,"end":4000,"words":[{"text":"lines","start":3000,"end":4000}]}]}""")) }
        val lyrics = source().find(query)!!
        assertEquals(2, lyrics.lines.size, "a backwards end does not cost the line its words")
        assertEquals(1000, lyrics.lines[0].startMs)
        assertEquals(2000, lyrics.lines[0].endMs, "the end comes from the line's words, not a backwards label")
        assertEquals(LyricsTiming.Words, lyrics.timing)
    }

    @Test
    fun `a line end past the song is refused rather than stretched over later lines`() = runBlocking {
        reply = { Reply(body = envelope("""{"provider":"netease","syncLevel":"word","timed":[
            {"text":"chalk","start":1000,"end":3000000,"words":[{"text":"chalk","start":1000,"end":2000}]},
            {"text":"lines","start":20000,"end":24000,"words":[{"text":"lines","start":20000,"end":24000}]}]}""")) }
        val lyrics = source().find(query)!!
        assertEquals(2, lyrics.lines.size)
        assertTrue(lyrics.lines[0].endMs <= 20000, "a declared end past the song is not used at all")
        assertEquals(20000, lyrics.lines[1].startMs)
    }

    @Test
    fun `cues written inside the line's own span stay word-timed as one run`() = runBlocking {
        // The echo sits inside the line (a shape some providers write): the split cannot spell the
        // text, so the cues are read as one run in the order they are written.
        reply = { Reply(body = envelope("""{"provider":"unison_community","syncLevel":"syllable","timed":[
            {"text":"Oh (oh) yeah","start":1000,"end":3000,"words":[
                {"text":"Oh ","start":1000,"end":1500},
                {"text":"oh","start":1200,"end":1400,"isBackground":true},
                {"text":"yeah","start":1600,"end":3000}]}]}""")) }
        val lyrics = source().find(query)!!
        val line = lyrics.lines.single()
        assertEquals(LyricsTiming.Words, lyrics.timing)
        assertEquals(listOf(1000L, 1200L, 1600L), line.words.map { it.startMs })
        assertEquals("Oh( oh) yeah", line.words.joinToString("") { it.text + if (it.space) " " else "" }.trim())
        assertEquals(3000, line.endMs)
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
    fun `a metadata duration stated in milliseconds is not read as hours`() = runBlocking {
        reply = { Reply(body = envelope(rich, metadata = """{"duration":181234}""")) }
        assertNotNull(source().find(query), "181234 ms is this three-minute song, not fifty hours")
        reply = { Reply(body = envelope(rich, metadata = """{"duration":220}""")) }
        assertNull(source().find(query), "220 seconds is still another version")
        reply = { Reply(body = envelope(rich, metadata = """{"duration":-1}""")) }
        assertNull(source().find(query), "a negative length is not a length")
        reply = { Reply(body = envelope(rich, metadata = """{"duration":"3:"}""")) }
        assertNull(source().find(query), "a length that cannot be read is not the same as no length")
    }

    @Test
    fun `a reply holding only other versions of the song is a miss and not a failure`() = runBlocking {
        reply = { Reply(body = envelope("""{"provider":"netease","duration":300,"syncLevel":"word","nativeWordTiming":true,"timed":[{"text":"chalk","start":1000,"end":2000}]}""")) }
        assertNull(source().find(query), "a five-minute copy of a three-minute song is not a failure to report")
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
    fun `cancellation is propagated rather than converted into a failed source`() = runBlocking<Unit> {
        reply = { Thread.sleep(100); Reply(body = envelope(rich)) }
        assertThrows<CancellationException> { withTimeout(10) { source().find(query) } }
    }

    private fun lyprio() = LyprioEndpoint.of("https://lyprio.example", Base64.getEncoder().encodeToString(ByteArray(32) { 1 }))!!

    @Test
    fun `both platforms get configured Lyprio then LiriQo before the remaining fallbacks`() {
        val spotify = object : LyricsSource { override val name = "Spotify"; override suspend fun find(query: LyricsQuery): Lyrics? = null }
        assertEquals(listOf("Lyprio", "LiriQo", "Spotify", "LRCLIB"), LyricsSetup.sources(http, lyprio(), listOf(spotify)).map { it.name })
        assertEquals(listOf("LiriQo", "Spotify", "LRCLIB"), LyricsSetup.sources(http, null, listOf(spotify)).map { it.name })
    }

    @Test
    fun `a Lyprio hit bypasses LiriQo even when only line timed lyrics are found`() = runBlocking {
        reply = { Reply(body = """{"complete":true,"results":[{"subprovider":"MUSIXMATCH","format":"lrc","lyrics":"[00:01.00]chalk lines"}]}""",
            headers = mapOf("X-Lyprio-Protocol" to "2")) }
        val book = LyricsBook(LyricsSetup.sources(http, lyprio()))
        val found = (book.lyricsFor(song) as LyricsState.Found).lyrics
        assertEquals("Lyprio · Musixmatch", found.source)
        assertEquals(LyricsTiming.Lines, found.timing)
        assertEquals(1, requests.size)
        assertEquals("lyprio.example", requests.single().url.host)
    }

    @Test
    fun `a Lyprio miss or failure reaches LiriQo before the remaining sources`() = runBlocking {
        for (code in listOf(200, 503)) {
            requests.clear()
            reply = { request ->
                if (request.url.host == "lyprio.example") Reply(code, """{"complete":true,"results":[]}""",
                    headers = mapOf("X-Lyprio-Protocol" to "2")) else Reply(body = envelope(rich))
            }
            val fallback = (LyricsBook(LyricsSetup.sources(http, lyprio())).lyricsFor(song) as LyricsState.Found).lyrics
            assertEquals("LiriQo · NetEase Cloud Music", fallback.source)
            // A completed miss expands Lyprio's search to all of its sources before falling back.
            assertEquals(if (code == 200) 3 else 2, requests.size)
            assertTrue(requests.dropLast(1).all { it.url.host == "lyprio.example" })
            assertEquals("lyprio.example", requests.first().url.host)
            assertTrue(requests.last().url.host.startsWith("api.liriqo"))
        }
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
    fun `old cached lines and misses cannot bypass the new source order and are not deleted`(@TempDir dir: Path) = runBlocking {
        val key = LyricsBook.keyOf(song)
        val previous = Lyrics(listOf(LyricLine(1000, 2000, "chalk")), LyricsTiming.Lines, "LRCLIB")
        for ((i, saved) in listOf(previous, null, previous, null, previous, null, previous, null, previous, null).withIndex()) {
            val folder = dir.resolve("case-$i")
            val old = DiskLyricsStore(when (i / 2) {
                0 -> folder; 1 -> folder.resolve("liriqo-v1"); 2 -> folder.resolve("liriqo-v2")
                3 -> folder.resolve("liriqo-original-v1"); else -> folder.resolve("liriqo-original-v2")
            })
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
