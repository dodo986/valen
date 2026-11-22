/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.lyrics

import app.valen.core.LyricLine
import app.valen.core.LyricWord
import app.valen.core.Lyrics
import app.valen.core.LyricsQuery
import app.valen.core.LyricsSource
import app.valen.core.LyricsTiming
import app.valen.core.LyricsUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The word-timing rule between Lyprio and LiriQo, against fake sources; the words are invented. */
class WordTimedFallbackTest {
    private val query = LyricsQuery("Paper Moons", "The Walls", durationMs = 181_000)

    private fun words(source: String) = Lyrics(
        listOf(LyricLine(1_000, 2_000, "chalk lines", listOf(LyricWord("chalk", 1_000, 1_400), LyricWord("lines", 1_400, 1_900)))),
        LyricsTiming.Words,
        source,
    )

    private fun lines(source: String) = Lyrics(listOf(LyricLine(1_000, 2_000, "chalk lines")), LyricsTiming.Lines, source)

    private fun plain(source: String) = Lyrics(listOf(LyricLine(1_000, 2_000, "chalk lines")), LyricsTiming.None, source)

    private class Fake(override val name: String, private val answer: suspend (LyricsQuery) -> Lyrics?) : LyricsSource {
        var asked = 0
        override suspend fun find(query: LyricsQuery): Lyrics? {
            asked++
            return answer(query)
        }
    }

    private fun pair(first: LyricsSource, fallback: LyricsSource) = WordTimedFallback(first, fallback)

    @Test
    fun `word timed first words are not asked of the fallback`() = runBlocking {
        val second = Fake("LiriQo") { words("LiriQo · X") }
        val found = pair(Fake("Lyprio") { words("Lyprio · X") }, second).find(query)!!
        assertEquals("Lyprio · X", found.source)
        assertEquals(0, second.asked)
    }

    @Test
    fun `line timed first words are replaced by the fallback's word timing`() = runBlocking {
        val found = pair(Fake("Lyprio") { lines("Lyprio · X") }, Fake("LiriQo") { words("LiriQo · Y") }).find(query)!!
        assertEquals("LiriQo · Y", found.source)
        assertEquals(LyricsTiming.Words, found.timing)
    }

    @Test
    fun `plain first words are replaced by line timing from the fallback`() = runBlocking {
        val found = pair(Fake("Lyprio") { plain("Lyprio · X") }, Fake("LiriQo") { lines("LiriQo · Y") }).find(query)!!
        assertEquals("LiriQo · Y", found.source)
        assertEquals(LyricsTiming.Lines, found.timing)
    }

    @Test
    fun `a tie keeps the first source's answer`() = runBlocking {
        val found = pair(Fake("Lyprio") { lines("Lyprio · X") }, Fake("LiriQo") { lines("LiriQo · Y") }).find(query)!!
        assertEquals("Lyprio · X", found.source)
    }

    @Test
    fun `line timing is not traded for plain words from the fallback`() = runBlocking {
        val found = pair(Fake("Lyprio") { lines("Lyprio · X") }, Fake("LiriQo") { plain("LiriQo · Y") }).find(query)!!
        assertEquals("Lyprio · X", found.source)
    }

    @Test
    fun `an instrumental from the first source is a whole answer`() = runBlocking {
        val second = Fake("LiriQo") { words("LiriQo · Y") }
        val found = pair(Fake("Lyprio") { Lyrics.instrumental("Lyprio") }, second).find(query)!!
        assertEquals("Lyprio", found.source)
        assertEquals(0, second.asked)
    }

    @Test
    fun `an instrumental fallback does not replace words`() = runBlocking {
        val found = pair(Fake("Lyprio") { lines("Lyprio · X") }, Fake("LiriQo") { Lyrics.instrumental("LiriQo") }).find(query)!!
        assertEquals("Lyprio · X", found.source)
    }

    @Test
    fun `the fallback is asked when the first source misses`() = runBlocking {
        val found = pair(Fake("Lyprio") { null }, Fake("LiriQo") { lines("LiriQo · Y") }).find(query)!!
        assertEquals("LiriQo · Y", found.source)
    }

    @Test
    fun `the fallback is asked when the first source fails`() = runBlocking {
        val found = pair(Fake("Lyprio") { throw LyricsUnavailable("it could not be reached") }, Fake("LiriQo") { words("LiriQo · Y") })
            .find(query)!!
        assertEquals("LiriQo · Y", found.source)
    }

    @Test
    fun `a fallback failure keeps the first source's untimed words`() = runBlocking {
        val found = pair(Fake("Lyprio") { lines("Lyprio · X") }, Fake("LiriQo") { throw LyricsUnavailable("busy") }).find(query)!!
        assertEquals("Lyprio · X", found.source)
    }

    @Test
    fun `both missing is a miss`() = runBlocking {
        assertNull(pair(Fake("Lyprio") { null }, Fake("LiriQo") { null }).find(query))
    }

    @Test
    fun `both failing reports the first source's failure`() {
        val first = LyricsUnavailable("Lyprio: it could not be reached")
        val error = assertThrows<LyricsUnavailable> {
            runBlocking {
                pair(Fake("Lyprio") { throw first }, Fake("LiriQo") { throw LyricsUnavailable("LiriQo: busy") }).find(query)
            }
        }
        assertSame(first, error)
    }

    @Test
    fun `a first-source miss and a fallback failure is a failure, not a miss`() {
        assertThrows<LyricsUnavailable> {
            runBlocking { pair(Fake("Lyprio") { null }, Fake("LiriQo") { throw LyricsUnavailable("busy") }).find(query) }
        }
    }

    @Test
    fun `the pair answers under the first source's name`() {
        assertEquals("Lyprio", pair(Fake("Lyprio") { null }, Fake("LiriQo") { null }).name)
    }

    @Test
    fun `cancellation is never swallowed as a failure`() {
        assertThrows<CancellationException> {
            runBlocking { pair(Fake("Lyprio") { throw CancellationException("stopped") }, Fake("LiriQo") { null }).find(query) }
        }
    }
}