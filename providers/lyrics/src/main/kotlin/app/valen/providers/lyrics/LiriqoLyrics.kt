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
        // A candidate refused for being another version's length is a miss, not a failure: LiriQo may
        // simply have no words for the version playing (a music video against the album cut). Only a
        // candidate that passed those gates and still yielded nothing is worth reporting as a failure.
        if (found == null && candidates.any { matches(it["metadata"] as? JsonObject, query) && durationFits(it, query) }) {
            throw LyricsUnavailable("its lyrics could not be read")
        }
        return found
    }

    private fun matches(metadata: JsonObject?, query: LyricsQuery): Boolean {
        if (metadata == null) return true
        metadata.string("artist")?.let { if (!LyricsFormats.artistsMatch(query.artist, it)) return false }
        metadata.string("title")?.let {
            if (query.videoId == null && recordingKey(query.title, "") != recordingKey(it, "")) return false
        }
        val durations = metadata.durationsMs()
        val wanted = query.durationMs
        if (durations.any { !it.isFinite() || it < 0 }) return false
        return durations.isEmpty() || wanted == null || durations.any { abs(it - wanted) <= LENGTH_SLACK_MS }
    }

    /**
     * The lengths a stated duration may mean, in milliseconds: a number in seconds ("214") or, when
     * it is far too large to be seconds, also in milliseconds; or a "m:ss" / "h:mm:ss" string. A
     * number that could be either is offered both ways, so the one that fits the song playing decides
     * (a three-hour mix states "duration": 10800, both seconds and a millisecond count).
     */
    private fun JsonObject.durationsMs(): List<Double> {
        val text = string("duration")
        val parts = text?.split(':')
        if (parts != null && parts.size in 2..3) {
            var total = 0.0
            for (part in parts) total = total * 60 + (part.toDoubleOrNull() ?: return MALFORMED_DURATION)
            return listOf(total * 1000)
        }
        val number = (this["duration"] as? JsonPrimitive)?.doubleOrNull
            ?: return if (text == null) emptyList() else MALFORMED_DURATION
        if (!number.isFinite() || number < 0) return listOf(number)
        if (number == 0.0) return emptyList()
        return if (number >= MILLISECOND_DURATION) listOf(number, number * 1000) else listOf(number * 1000)
    }

    /**
     * A track of another length is another version of the song: its times are not this song's. The
     * reply's own metadata is checked by [matches]; this checks each candidate, including the ones
     * under `tracks`, whose own `duration` is otherwise never compared with the song playing.
     */
    private fun durationFits(track: JsonObject, query: LyricsQuery): Boolean {
        val wanted = query.durationMs ?: return true
        val durations = track.durationsMs()
        if (durations.any { !it.isFinite() || it < 0 }) return false
        return durations.isEmpty() || durations.any { it > 0 && abs(it - wanted) <= LENGTH_SLACK_MS }
    }

    private fun readTrack(track: JsonObject, query: LyricsQuery): Lyrics? {
        if (!matches(track["metadata"] as? JsonObject, query)) return null
        if (!durationFits(track, query)) return null
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

    /**
     * The native `timed` track, read into lines. LiriQo's times are absolute milliseconds, and they
     * are taken as they are: nothing here shifts a cue, and a cue that cannot be trusted — not a
     * whole millisecond, negative, an end at or before its start, past the song — is dropped rather
     * than nudged.
     *
     * A line stands from its earliest cue: its own start, or a word cue that begins before it, so a
     * label that lags the singing never shows the line late (the LRC reader has always taken the
     * earlier of the two). Its words are used only when they are whole and spell the line in order;
     * otherwise the line keeps its own window. A line that begins after the song has ended is left
     * out, and an end past the song is held at the song's edge.
     */
    private fun normalized(track: JsonObject, source: String, native: Boolean, duration: Long?): Lyrics? {
        val timed = track["timed"] as? JsonArray ?: return null
        val limit = duration?.coerceIn(0, MAX_TIME_MS)?.plus(LENGTH_SLACK_MS) ?: MAX_TIME_MS
        val raw = timed.mapIndexedNotNull { index, value ->
            val line = value as? JsonObject ?: return@mapIndexedNotNull null
            if (line.flag("isCue") == true || line.flag("isInstrumental") == true) return@mapIndexedNotNull null
            val units = (line["words"]?.takeUnless { it == JsonNull } ?: when (val all = track["words"]) {
                is JsonArray -> all.getOrNull(line.string("id")?.toIntOrNull() ?: index)
                is JsonObject -> all[line.string("id") ?: index.toString()]
                else -> null
            }) as? JsonArray
            val text = line.string("text")?.takeIf { it.isNotBlank() }
                ?: units?.joinToString("") { (it as? JsonObject)?.string("text").orEmpty() }?.trim()?.takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            Triple(line, text, units)
        }
        if (track.string("syncLevel") == "plain") return LyricsFormats.parse(raw.joinToString("\n") { it.second }, "plain", source)
        val lines = raw.mapNotNull { (line, text, units) -> timedLine(line, text, units, limit, native) }
        val starts = lines.map { it.start }.distinct().sorted()
        val nextStarts = starts.zipWithNext().toMap()
        val read = lines.sortedBy { it.start }.mapNotNull { line ->
            val start = line.start
            // A declared end at or before the line's own start is not a window: drop that value and
            // let the line's cues (or the next line) say how long it lasts, rather than lose the line.
            val declared = line.declaredEnd?.takeIf { it > start }
            val end = (declared ?: line.cueEnd?.takeIf { it > start } ?: nextStarts[start]
                ?: duration?.takeIf { it > start } ?: (start + LAST_LINE_MS)).coerceAtMost(limit)
            if (end <= start) return@mapNotNull null
            val lead = place(line.lead, line.text, 0, start, end)
            val backing = place(line.backing, line.text, lead?.second ?: 0, start, end)
            val covered = backing?.second ?: (lead?.second ?: 0)
            val rawTail = line.text.substring(covered)
            val backingText = line.backing?.joinToString("") { it.text }.orEmpty()
            // A backing group that could not be placed is left out on its own, without taking the
            // line's good words with it: the part of the text it stood for is set aside.
            val left = if (backing == null && backingText.isNotEmpty()) rawTail.replace(backingText, "") else rawTail
            var spells = !left.any { it.isLetterOrDigit() }
            var words = if (spells) lead?.first.orEmpty() else emptyList()
            var under = if (spells) backing?.first.orEmpty() else emptyList()
            var trailing = if (!rawTail.any { it.isLetterOrDigit() }) rawTail.trim() else ""
            // A source that writes the backing words inside the lead's span cannot be split by text;
            // its cues are then one run in the order they were written, so the line stays word-timed.
            val leadCues = line.lead
            val backingCues = line.backing
            if (!spells && leadCues != null && backingCues != null) {
                val flat = place((leadCues + backingCues).sortedBy { it.order }, line.text, 0, start, end)
                val rest = flat?.let { line.text.substring(it.second) }
                if (flat != null && rest != null && !rest.any { it.isLetterOrDigit() }) {
                    spells = true
                    words = flat.first
                    under = emptyList()
                    trailing = rest.trim()
                }
            }
            if (spells && trailing.isNotEmpty()) {
                if (under.isNotEmpty()) under = under.dropLast(1) + under.last().copy(text = under.last().text + trailing, space = false)
                else if (words.isNotEmpty()) words = words.dropLast(1) + words.last().copy(text = words.last().text + trailing, space = false)
            }
            LyricLine(start, end, line.text, words, under,
                under.takeIf { it.isNotEmpty() }?.joinToString("") { it.text + if (it.space) " " else "" }?.trim(),
                voice = line.voice, aside = line.aside)
        }
        if (read.isNotEmpty()) {
            val timing = if (read.any { it.timedByWord }) LyricsTiming.Words else LyricsTiming.Lines
            return Lyrics(LyricsFormats.attachBacking(read), timing, source)
        }
        return LyricsFormats.parse(raw.joinToString("\n") { it.second }, "plain", source)
    }

    /** One cue as the source states it, before it is matched to its line's text. */
    private class Cue(val text: String, val start: Long, val end: Long, val order: Int)

    /** One `timed` line with its cues read, before the line's own window is worked out. */
    private class Line(
        val text: String,
        val declaredStart: Long,
        val declaredEnd: Long?,
        val lead: List<Cue>?,
        val backing: List<Cue>?,
        val voice: String?,
        val aside: Boolean,
    ) {
        /**
         * The earliest cue of the line: a label that lags its words must not show the line late.
         * Only a cue close enough to the label to be the same line's does that — a cue far earlier
         * is another timebase (seconds read as milliseconds, say), and the label is kept.
         */
        val start: Long
            get() {
                val cue = lead?.firstOrNull()?.start ?: return declaredStart
                return if (cue >= declaredStart - CUE_LEAD_MS) minOf(declaredStart, cue) else declaredStart
            }

        val cueEnd: Long? get() = (lead.orEmpty() + backing.orEmpty()).maxOfOrNull { it.end }
    }

    private fun timedLine(source: JsonObject, text: String, units: JsonArray?, limit: Long, native: Boolean): Line? {
        val declared = source.time("start")?.takeIf { it <= limit } ?: return null
        val aside = source.flag("isBackground") == true
        val wanted = native && source.string("granularity") != "phrase"
        return Line(
            text = text,
            declaredStart = declared,
            declaredEnd = source.time("end")?.takeIf { it <= limit },
            lead = if (wanted) cues(units, aside = aside, background = false, limit = limit) else null,
            backing = if (wanted && !aside) cues(units, aside = false, background = true, limit = limit) else null,
            voice = source.string("agent"),
            aside = aside,
        )
    }

    /**
     * The cues of one group (the line's own words, or the backing vocals under it) in the order they
     * are written. Null when one of them cannot be trusted — a time that is not a whole millisecond,
     * an end at or before its start, a time past the song — or when a later cue runs backwards: a
     * group with one broken cue is not one to guess at.
     */
    private fun cues(units: JsonArray?, aside: Boolean, background: Boolean, limit: Long): List<Cue>? {
        if (units == null || units.isEmpty()) return null
        val out = ArrayList<Cue>(units.size)
        var previous = 0L
        for ((order, value) in units.withIndex()) {
            val unit = value as? JsonObject ?: return null
            if (!aside && (unit.flag("isBackground") == true) != background) continue
            val word = unit.string("text")?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            // Punctuation is not a cue: LiriQo often states it with no time at all (a comma from
            // 36000 to 36000), and the line's own text still holds it. Skipping it keeps the line
            // word-timed instead of throwing away every cue beside it.
            if (word.none { it.isLetterOrDigit() }) continue
            val from = unit.time("start") ?: return null
            val until = unit.time("end") ?: return null
            if (until <= from || until > limit) return null
            if (out.isNotEmpty() && from < previous) return null
            out += Cue(word, from, until, order)
            previous = from
        }
        return out.ifEmpty { null }
    }

    /**
     * Places [cues] in [text] in the order they are written, giving each the spacing and punctuation
     * the line itself has. Null when a cue falls outside the line's window or when the cues do not
     * spell the line in order: that is a drift or another line, not a cue to shift.
     */
    private fun place(cues: List<Cue>?, text: String, from: Int, start: Long, end: Long): Pair<List<LyricWord>, Int>? {
        if (cues.isNullOrEmpty()) return null
        val words = ArrayList<LyricWord>(cues.size)
        var cursor = from
        for (cue in cues) {
            if (cue.start < start || cue.end > end) return null
            val at = text.indexOf(cue.text, cursor)
            if (at < 0) return null
            val gap = text.substring(cursor, at)
            if (gap.any { it.isLetterOrDigit() }) return null
            val punctuation = gap.filterNot { it.isWhitespace() }
            if (words.isNotEmpty()) words[words.lastIndex] = words.last().let {
                it.copy(text = it.text + punctuation, space = gap.any(Char::isWhitespace))
            }
            words += LyricWord((if (words.isEmpty()) punctuation else "") + cue.text, cue.start, cue.end, space = false)
            cursor = at + cue.text.length
        }
        return words to cursor
    }

    private fun retryTime(header: String?): Long {
        val seconds = header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
        if (seconds != null) return runCatching { Math.addExact(clock(), Math.multiplyExact(seconds, 1000L)) }.getOrDefault(Long.MAX_VALUE)
        return header?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
            ?.coerceAtLeast(clock()) ?: (clock() + RETRY_MS)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    /**
     * A time from the answer, in whole milliseconds. A whole millisecond written as a float
     * ("1000.0") is the same millisecond; a fractional one ("1000.5") is not a cue to guess at.
     */
    private fun JsonObject.time(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        val value = primitive.longOrNull
            ?: primitive.doubleOrNull?.takeIf { it.isFinite() && it >= 0 && it == Math.floor(it) }?.toLong()
            ?: return null
        return value.takeIf { it in 0..MAX_TIME_MS }
    }

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

        /** How far a word cue may pull its line's label back before it is read as another timebase. */
        const val CUE_LEAD_MS = 4_000L

        /** A stated duration at or above this may be milliseconds rather than seconds. */
        const val MILLISECOND_DURATION = 10_000.0

        /** A duration that is stated but cannot be read: not the same as no duration at all. */
        val MALFORMED_DURATION = listOf(Double.NaN)
    }
}
