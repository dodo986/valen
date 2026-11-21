/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.lyrics

import app.valen.core.LyricLine
import app.valen.core.LyricWord
import app.valen.core.Lyrics
import app.valen.core.LyricsFormats
import app.valen.core.LyricsQuery
import app.valen.core.LyricsSource
import app.valen.core.LyricsTiming
import app.valen.core.LyricsUnavailable
import app.valen.core.recordingKey
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** LiriQo's normalized /lyrics response: native unit and line times are absolute milliseconds. */
class LiriqoLyrics(
    private val http: OkHttpClient,
    private val base: HttpUrl = "https://api.liriqo-alfarrizi.my.id/v1/".toHttpUrl(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : LyricsSource {
    override val name = "LiriQo"
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var retryAt = 0L

    override suspend fun find(query: LyricsQuery): Lyrics? {
        if (clock() < retryAt) throw LyricsUnavailable("it is unavailable; try again later")
        if (query.title.isBlank()) return null
        val url = base.newBuilder().addPathSegment("lyrics")
            .apply {
                if (query.artist.isBlank()) addQueryParameter("Q", query.title) else {
                    addQueryParameter("title", query.title)
                    addQueryParameter("artist", query.artist)
                }
                query.videoId?.takeIf { VIDEO_ID.matches(it) }?.let { addQueryParameter("v", it) }
                query.album?.takeIf { it.isNotBlank() }?.let { addQueryParameter("album", it) }
                query.durationMs?.takeIf { it > 0 }?.let { addQueryParameter("duration", (it / 1000.0).toString()) }
            }.build()
        val answer = try {
            http.answer(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LyricsUnavailable("it could not be reached", e)
        }
        when (answer.code) {
            404 -> return null
            429, 503 -> {
                retryAt = retryTime(answer.header("Retry-After"))
                throw LyricsUnavailable("it answered ${answer.code}; try again later")
            }
            200 -> Unit
            else -> throw LyricsUnavailable("it answered ${answer.code}")
        }
        val reply = answer.body?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            ?: throw LyricsUnavailable("its answer could not be read")
        if (reply["error"] != null && reply["error"] != JsonNull) throw LyricsUnavailable("it reported a lookup failure")
        if ("primary" !in reply && reply["tracks"] !is JsonArray) throw LyricsUnavailable("its answer could not be read")
        if (!matches(reply["metadata"] as? JsonObject, query)) return null
        val candidates = listOfNotNull(reply["primary"] as? JsonObject) +
            (reply["tracks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val found = candidates.mapNotNull { readTrack(it, query) }.maxByOrNull { it.quality() }
        if (found == null && candidates.isNotEmpty()) throw LyricsUnavailable("its lyrics could not be read")
        return found
    }

    private fun matches(metadata: JsonObject?, query: LyricsQuery): Boolean {
        if (metadata == null) return true
        metadata.string("artist")?.let { if (!LyricsFormats.artistsMatch(query.artist, it)) return false }
        metadata.string("title")?.let {
            if (query.videoId == null && recordingKey(query.title, "") != recordingKey(it, "")) return false
        }
        val seconds = (metadata["duration"] as? JsonPrimitive)?.doubleOrNull
            ?: metadata.string("duration")?.split(':')?.takeIf { it.size in 2..3 }
                ?.map { it.toDoubleOrNull() ?: return false }?.fold(0.0) { n, part -> n * 60 + part }
        val wanted = query.durationMs
        if (seconds != null && (!seconds.isFinite() || seconds < 0)) return false
        return seconds == null || wanted == null || abs(seconds * 1000 - wanted) <= LENGTH_SLACK_MS
    }

    private fun readTrack(track: JsonObject, query: LyricsQuery): Lyrics? {
        if (!matches(track["metadata"] as? JsonObject, query)) return null
        val provider = track.string("provider").orEmpty()
        val source = (listOf(name) + listOfNotNull(track.string("upstreamProvider") ?: providerName(provider), track.string("lyricsSource")))
            .filter { it.isNotBlank() }.distinctBy { it.lowercase() }.joinToString(" · ")
        if (track.flag("instrumental") == true) return Lyrics.instrumental(source)
        val native = track.flag("nativeWordTiming") != false && provider !in LINE_ONLY
        val alternatives = ArrayList<Lyrics>()
        track.string("ttml")?.let { text ->
            runCatching { LyricsFormats.parse(text, "ttml", source, query.durationMs) }.getOrNull()?.let { parsed ->
                alternatives += if (native) parsed else parsed.copy(
                    timing = if (parsed.timing == LyricsTiming.None) LyricsTiming.None else LyricsTiming.Lines,
                    lines = parsed.lines.map { it.copy(words = emptyList(), backing = emptyList()) },
                )
            }
        }
        normalized(track, source, native, query.durationMs)?.let { alternatives += it }
        track.string("lrc")?.let { text -> runCatching { LyricsFormats.parse(text, "lrc", source, query.durationMs) }.getOrNull()?.let { parsed ->
            // LRC exports can contain estimated unit tags; native words come from JSON or TTML.
            alternatives += parsed.copy(
                timing = if (parsed.timing == LyricsTiming.None) LyricsTiming.None else LyricsTiming.Lines,
                lines = parsed.lines.map { it.copy(words = emptyList(), backing = emptyList()) },
            )
        } }
        track.string("plain")?.let { LyricsFormats.parse(it, "plain", source)?.let { parsed -> alternatives += parsed } }
        return alternatives.maxByOrNull { it.quality() }?.let { LyricsFormats.withoutHeader(it, query.title, query.artist) }
    }

    private fun normalized(track: JsonObject, source: String, native: Boolean, duration: Long?): Lyrics? {
        val timed = track["timed"] as? JsonArray ?: return null
        val raw = timed.mapIndexedNotNull { index, value ->
            val line = value as? JsonObject ?: return@mapIndexedNotNull null
            if (line.flag("isCue") == true || line.flag("isInstrumental") == true) return@mapIndexedNotNull null
            val units = line["words"]?.takeUnless { it == JsonNull } ?: when (val all = track["words"]) {
                is JsonArray -> all.getOrNull(line.string("id")?.toIntOrNull() ?: index)
                is JsonObject -> all[line.string("id") ?: index.toString()]
                else -> null
            }
            val text = line.string("text")?.takeIf { it.isNotBlank() }
                ?: (units as? JsonArray)?.joinToString("") { (it as? JsonObject)?.string("text").orEmpty() }?.trim()
                ?: return@mapIndexedNotNull null
            if (text.isBlank()) return@mapIndexedNotNull null
            Triple(line, text, units as? JsonArray)
        }
        val starts = raw.mapNotNull { it.first.time("start") }.distinct().sorted()
        val nextStarts = starts.zipWithNext().toMap()
        val lines = raw.filter { it.first.time("start") != null }.sortedBy { it.first.time("start") }.mapNotNull { (line, text, units) ->
            if (track.string("syncLevel") == "plain") return@mapNotNull null
            val start = line.time("start")!!
            val explicitEnd = line.time("end")
            if (explicitEnd != null && explicitEnd <= start) return@mapNotNull null
            val end = explicitEnd
                ?: units?.mapNotNull { (it as? JsonObject)?.time("end") }?.maxOrNull()?.takeIf { it > start }
                ?: nextStarts[start]
                ?: duration?.takeIf { it > start }
                ?: (start + LAST_LINE_MS)
            if (duration != null && end > duration + LENGTH_SLACK_MS) return@mapNotNull null
            val words = if (native && line.string("granularity") != "phrase") words(units, text, start, end) else emptyList()
            LyricLine(start, end, text, words, voice = line.string("agent"), aside = line.flag("isBackground") == true)
        }
        if (lines.isNotEmpty()) return Lyrics(LyricsFormats.attachBacking(lines),
            if (lines.any { it.timedByWord }) LyricsTiming.Words else LyricsTiming.Lines, source)
        val plain = raw.joinToString("\n") { it.second }
        return LyricsFormats.parse(plain, "plain", source)
    }

    /** Keep native windows and overlaps, and recover spaces/punctuation from the line itself. */
    private fun words(units: JsonArray?, text: String, start: Long, end: Long): List<LyricWord> {
        if (units.isNullOrEmpty()) return emptyList()
        val words = ArrayList<LyricWord>()
        var cursor = 0
        var previous = start
        for (value in units) {
            val unit = value as? JsonObject ?: return emptyList()
            val word = unit.string("text")?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
            val from = unit.time("start") ?: return emptyList()
            val until = unit.time("end") ?: return emptyList()
            if (from < previous || from < start || until <= from || until > end) return emptyList()
            val at = text.indexOf(word, cursor)
            if (at < 0) return emptyList()
            val gap = text.substring(cursor, at)
            if (gap.any { it.isLetterOrDigit() }) return emptyList()
            val punctuation = gap.filterNot { it.isWhitespace() }
            if (words.isNotEmpty()) words[words.lastIndex] = words.last().let {
                it.copy(text = it.text + punctuation, space = gap.any(Char::isWhitespace))
            }
            words += LyricWord((if (words.isEmpty()) punctuation else "") + word, from, until, space = false)
            cursor = at + word.length
            previous = from
        }
        val tail = text.substring(cursor)
        if (tail.any { it.isLetterOrDigit() }) return emptyList()
        words[words.lastIndex] = words.last().copy(text = words.last().text + tail.trim(), space = false)
        return words
    }

    private fun retryTime(header: String?): Long {
        val seconds = header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        if (seconds != null) return runCatching { Math.addExact(clock(), Math.multiplyExact(seconds, 1000L)) }.getOrDefault(Long.MAX_VALUE)
        return header?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
            ?.coerceAtLeast(clock()) ?: (clock() + RETRY_MS)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.time(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 && it <= MAX_TIME_MS }

    private fun providerName(provider: String): String? = when (provider) {
        "apple_music_liriqo" -> "Apple Music"
        "musixmatch", "musixmatch_richsync" -> "Musixmatch"
        "unison", "unison_community" -> "Unison"
        "netease" -> "NetEase"
        "petitlyrics" -> "PetitLyrics"
        "kugou_legato" -> "KuGou"
        "kuwo" -> "Kuwo"
        "migu" -> "Migu"
        "qq_portato" -> "QQ Music"
        "lrclib", "lrclib_wordsync" -> "LRCLIB"
        "ytm_line" -> "YouTube Music"
        else -> provider.takeIf { it.isNotBlank() }
    }

    private companion object {
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        val LINE_ONLY = setOf("lrclib", "lrclib_wordsync", "ytm_line")
        const val LENGTH_SLACK_MS = 4_000L
        const val LAST_LINE_MS = 5_000L
        const val MAX_TIME_MS = 24L * 60 * 60 * 1000
        const val RETRY_MS = 60_000L
    }
}
