/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.lyrics

import app.valen.core.Lyrics
import app.valen.core.LyricsQuery
import app.valen.core.LyricsSource
import app.valen.core.LyricsTiming
import kotlinx.coroutines.CancellationException

/**
 * Lyprio first, then LiriQo when Lyprio's words are not timed word by word. Lyprio's sources
 * sometimes answer with plain or line-timed words where LiriQo still has the singing tracked
 * word by word — and word timing is what the stage draws — so on a word-for-word miss the second
 * source is asked and the better-timed answer wins. On a tie the first source's words stand, so
 * a line-timed answer is not swapped for another line-timed one.
 *
 * Misses and failures answer like one source: null when neither has the song, the first source's
 * failure when both could not be asked, and a fallback failure is reported only when the first
 * source missed (a failure must never be remembered as a miss).
 */
internal class WordTimedFallback(
    private val first: LyricsSource,
    private val fallback: LyricsSource,
) : LyricsSource {
    override val name: String = first.name

    override suspend fun find(query: LyricsQuery): Lyrics? {
        val primary = try {
            first.find(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The first source could not answer; the fallback may still have the song.
            return fallbackOrRethrow(e, query)
        }
        if (primary == null) return fallbackOrRethrow(null, query)
        // An instrumental is a whole answer (there are no words to time), and word timing is as
        // good as it gets: neither leaves the fallback anything better to offer.
        if (primary.instrumental || primary.timing == LyricsTiming.Words) return primary
        val second = try {
            fallback.find(query)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // The first source's words, though untimed, are still words to show.
            return primary
        }
        if (second == null || second.instrumental) return primary
        return if (second.quality() > primary.quality()) second else primary
    }

    private suspend fun fallbackOrRethrow(firstFailure: Throwable?, query: LyricsQuery): Lyrics? {
        val second = try {
            fallback.find(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (firstFailure != null) throw firstFailure
            throw e
        }
        if (second != null) return second
        if (firstFailure != null) throw firstFailure
        return null
    }
}