/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.lyrics

import app.valen.core.Lyrics
import app.valen.core.LyricsFormats
import app.valen.core.LyricsQuery
import app.valen.core.LyricsSource
import app.valen.core.LyricsUnavailable
import app.valen.core.artistsIn
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * LRCLIB (lrclib.net): an open library of lyrics, timed by line, that needs no key. It is asked
 * as the last fallback: first for the exact song (title, artist,
 * album and length), then by search, keeping only a result by the same artist and of about the
 * same length.
 */
class LrclibLyrics(
    private val http: OkHttpClient,
    private val base: HttpUrl = "https://lrclib.net/api/".toHttpUrl(),
) : LyricsSource {
    override val name = "LRCLIB"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun find(query: LyricsQuery): Lyrics? {
        val artist = artistsIn(query.artist).firstOrNull()?.takeIf { it.isNotBlank() } ?: query.artist
        val seconds = query.durationMs?.takeIf { it > 0 }?.let { (it / 1000.0).roundToLong() }
        if (seconds != null) {
            val exact = get(
                base.newBuilder().addPathSegment("get")
                    .addQueryParameter("track_name", query.title)
                    .addQueryParameter("artist_name", artist)
                    .apply { query.album?.let { addQueryParameter("album_name", it) } }
                    .addQueryParameter("duration", seconds.toString())
                    .build(),
            )
            if (exact != null) {
                val entry = runCatching { json.decodeFromString<Entry>(exact) }.getOrNull()
                entry?.toLyrics(query)?.let { return it }
            }
        }
        val listed = get(
            base.newBuilder().addPathSegment("search")
                .addQueryParameter("track_name", query.title)
                .addQueryParameter("artist_name", artist)
                .build(),
        ) ?: return null
        val entries = runCatching { json.decodeFromString<List<Entry>>(listed) }.getOrNull() ?: return null
        return entries
            .filter { LyricsFormats.artistsMatch(query.artist, it.artistName) }
            .filter { seconds == null || it.duration == null || abs(it.duration - seconds) <= LENGTH_SLACK_S }
            .sortedWith(compareByDescending<Entry> { !it.syncedLyrics.isNullOrBlank() }.thenBy { abs((it.duration ?: 0.0) - (seconds ?: 0L)) })
            .firstNotNullOfOrNull { it.toLyrics(query) }
    }

    /** The body of a 200, null for a 404; anything else is the library not answering. */
    private suspend fun get(url: HttpUrl): String? {
        val answer = try {
            http.answer(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LyricsUnavailable("it could not be reached", e)
        }
        return when (answer.code) {
            200 -> answer.body
            404 -> null
            else -> throw LyricsUnavailable("it answered ${answer.code}")
        }
    }

    private fun Entry.toLyrics(query: LyricsQuery): Lyrics? {
        if (instrumental) return Lyrics.instrumental(name)
        val parsed = syncedLyrics?.takeIf { it.isNotBlank() }?.let { LyricsFormats.parse(it, "lrc", name, query.durationMs) }
            ?: plainLyrics?.takeIf { it.isNotBlank() }?.let { LyricsFormats.parse(it, "plain", name, query.durationMs) }
            ?: return null
        return LyricsFormats.withoutHeader(parsed, query.title, query.artist)
    }

    @Serializable
    private data class Entry(
        val id: Long = 0,
        val trackName: String = "",
        val artistName: String = "",
        val duration: Double? = null,
        val instrumental: Boolean = false,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
    )

    private companion object {
        const val LENGTH_SLACK_S = 4.0
    }
}
