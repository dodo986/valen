/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One song as it has been listened to here: what it is, how long it played, how often it started. */
@Serializable
data class Listened(
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val provider: ProviderId,
    val ms: Long = 0,
    val plays: Int = 0,
    /** Enough of the song to play it again (the weekly recap does): its length, uri and page. */
    val durationMs: Long = 0,
    val uri: String? = null,
    val link: String? = null,
    /**
     * How much of [ms] (and how many of [plays]) came from a generated list ("For you", a radio, a
     * mix) rather than from the person's own choice. Generated listening is still listening, but it
     * must not make an artist look chosen: the taste leaves it out of seeds and of "on repeat".
     */
    val genMs: Long = 0,
    val genPlays: Int = 0,
    /** When it was first heard here, epoch ms; 0 for a song counted before this was kept. */
    val first: Long = 0,
) {
    /** The song again, from its log [key] ("SPOTIFY:id"); null for a key that names no service. */
    fun track(key: String): Track? {
        val provider = ProviderId.entries.firstOrNull { it.name == key.substringBefore(':') } ?: return null
        val id = key.substringAfter(':', "").takeIf { it.isNotBlank() } ?: return null
        return Track(
            provider = provider,
            id = id,
            title = title,
            artist = artist,
            album = album,
            artworkUrl = artworkUrl,
            durationMs = durationMs,
            uri = uri,
            link = link,
        )
    }
}

/**
 * One day of listening in the device's own time, for the weekly recap: the time, each song's
 * share and starts, and when in the day it was heard.
 */
@Serializable
data class DayListening(
    val ms: Long = 0,
    val songs: Map<String, Long> = emptyMap(),
    val plays: Map<String, Int> = emptyMap(),
    /** By hour of the local day, 0–23. */
    val hours: Map<Int, Long> = emptyMap(),
    /** The part of [songs] that came from generated lists, by song key. */
    val genSongs: Map<String, Long> = emptyMap(),
)

/**
 * What Valen has counted of the listening done in it: time actually playing (not time paused or
 * buffering), per song, artist, album, playlist and day. Valen's own, like the queue and the
 * playlists; nothing is read from or sent to a service.
 */
@Serializable
data class ListeningLog(
    /** When counting began, epoch milliseconds; 0 until the first second is counted. */
    val since: Long = 0,
    val totalMs: Long = 0,
    val plays: Int = 0,
    val songs: Map<String, Listened> = emptyMap(),
    val artists: Map<String, Long> = emptyMap(),
    /** Keyed "album\u0000artist", so two albums of the same name stay apart. */
    val albums: Map<String, Long> = emptyMap(),
    /** By playlist name, for songs started from one of Valen's playlists. */
    val playlists: Map<String, Long> = emptyMap(),
    /** By epoch day (UTC), as text so the file stays plain JSON. */
    val days: Map<String, Long> = emptyMap(),
    val services: Map<ProviderId, Long> = emptyMap(),
    /** Songs skipped early (in their first 30 s, or first 40 %), by song key. */
    val skips: Map<String, Int> = emptyMap(),
    /**
     * The last [DAILY_KEEP_DAYS] days one by one, by local epoch day (as text): what the weekly
     * recap is made from. Older days are dropped; the totals above keep them.
     */
    val daily: Map<String, DayListening> = emptyMap(),
    /** When [daily] began to be kept, epoch ms; 0 before the first slice of it. */
    val dailySince: Long = 0,
) {
    fun topSongs(n: Int): List<Listened> = songs.values.sortedByDescending { it.ms }.take(n)
    fun topArtists(n: Int): List<Pair<String, Long>> = artists.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }
    fun topAlbums(n: Int): List<Pair<String, Long>> = albums.entries.sortedByDescending { it.value }.take(n).map { it.key.substringBefore('\u0000') to it.value }
    fun topPlaylists(n: Int): List<Pair<String, Long>> = playlists.entries.sortedByDescending { it.value }.take(n).map { it.key to it.value }

    /** Milliseconds listened on each of the [count] days ending with [today] (epoch day), oldest first. */
    fun lastDays(today: Long, count: Int): List<Long> = (count - 1 downTo 0).map { days[(today - it).toString()] ?: 0L }

    /** The artwork of the most played song by [artist], to stand for them. */
    fun artworkFor(artist: String): String? = songs.values.filter { it.artist == artist && it.artworkUrl != null }.maxByOrNull { it.ms }?.artworkUrl

    /** The artwork of the most played song from [album]. */
    fun artworkForAlbum(album: String): String? = songs.values.filter { it.album == album && it.artworkUrl != null }.maxByOrNull { it.ms }?.artworkUrl
}

/** Persistence for the log, given by the platform like [SettingsStore]. */
class ListeningStore(
    private val read: () -> String?,
    private val write: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun load(): ListeningLog = runCatching {
        read()?.let { json.decodeFromString<ListeningLog>(it) }?.let { log ->
            log.copy(songs = log.songs.mapValues { (_, s) ->
                val upgraded = largestArtworkUrl(s.artworkUrl)
                if (upgraded == s.artworkUrl) s else s.copy(artworkUrl = upgraded)
            })
        }
    }.getOrNull() ?: ListeningLog()

    fun save(log: ListeningLog) {
        runCatching { write(json.encodeToString(log)) }
    }

    companion object {
        /** Counts in memory only (tests, previews). */
        val None = ListeningStore(read = { null }, write = {})
    }
}

/**
 * Counts listening as it happens. [count] is given each slice of time a song really played; a
 * slice is added to the song, its artist, its album, the playlist it was started from, the day,
 * and the service. A song's play is counted once, when its first slice arrives. The log is written
 * at most every [saveEveryMs] and at each new play, so a crash loses seconds, not the evening.
 */
class ListeningStats(
    private val store: ListeningStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val saveEveryMs: Long = 15_000,
    private val publishEveryMs: Long = 2_000,
    /** How far the device's clock is ahead of UTC at a moment, in ms: where its days begin. */
    private val offset: (Long) -> Long = ::localOffsetMs,
) {
    private val _log = MutableStateFlow(store.load())
    val log: StateFlow<ListeningLog> = _log.asStateFlow()
    private var lastSave = 0L
    private var playing: String? = null

    /** Time heard but not yet added to [log]: the coordinator ticks four times a second. */
    private var pendingTrack: Track? = null
    private var pendingPlaylist: String? = null
    private var pendingGenerated = false
    private var pendingMs = 0L
    private var pendingNewPlay = false

    /**
     * Adds a slice of [ms] heard of [track]. Slices of one song are gathered and added to the log
     * every [publishEveryMs] (and when the song changes, and at [flush]): the log's maps are rebuilt
     * on each add, which four times a second is real work with a large library.
     *
     * [generated] is true while the slice played from a list Valen made (a mix, a radio, "For you"):
     * it is counted, and counted apart, so the taste can tell what the person chose from what the
     * algorithm offered.
     */
    @Synchronized
    fun count(track: Track, ms: Long, playlist: String?, generated: Boolean = false) {
        if (ms <= 0) return
        if (pendingTrack != null && (pendingTrack?.key != track.key || pendingGenerated != generated)) publish()
        if (pendingTrack == null) {
            pendingTrack = track
            pendingPlaylist = playlist
            pendingGenerated = generated
            pendingNewPlay = playing != track.key
            playing = track.key
        }
        pendingMs += ms
        if (pendingMs >= publishEveryMs) publish()
    }

    /** Adds what is gathered to the log; writes it when a play began or it has been a while. */
    @Synchronized
    private fun publish() {
        val track = pendingTrack ?: return
        val ms = pendingMs
        val newPlay = pendingNewPlay
        val playlist = pendingPlaylist
        val generated = pendingGenerated
        pendingTrack = null
        pendingMs = 0
        pendingNewPlay = false
        pendingGenerated = false
        if (ms <= 0) return
        val now = clock()
        val log = _log.value
        val known = log.songs[track.key]
        val song = (known ?: Listened(
            title = track.title,
            artist = track.artist,
            album = track.album?.takeIf { it.isNotBlank() },
            artworkUrl = track.artworkUrl,
            provider = track.provider,
            first = now,
        )).let {
            // What it takes to play the song again, filled in for songs counted before it was kept.
            val nextArt = largestArtworkUrl(track.artworkUrl)
            val prevArt = largestArtworkUrl(it.artworkUrl)
            val bestArt = when {
                nextArt == null -> prevArt
                prevArt == null -> nextArt
                "ytimg.com" in prevArt && "ytimg.com" !in nextArt -> nextArt
                "googleusercontent.com" in nextArt || "ggpht.com" in nextArt -> nextArt
                else -> prevArt
            }
            it.copy(
                artworkUrl = bestArt,
                durationMs = track.durationMs.takeIf { d -> d > 0 } ?: it.durationMs,
                uri = it.uri ?: track.uri,
                link = it.link ?: track.link,
            )
        }
        val genMs = if (generated) ms else 0L
        val genPlay = if (newPlay && generated) 1 else 0
        val day = (now / 86_400_000L).toString()
        val album = track.album?.takeIf { it.isNotBlank() }?.let { it + "\u0000" + track.artist }
        _log.value = log.copy(
            since = if (log.since == 0L) now else log.since,
            totalMs = log.totalMs + ms,
            plays = log.plays + if (newPlay) 1 else 0,
            songs = log.songs + (track.key to song.copy(
                ms = song.ms + ms,
                plays = song.plays + if (newPlay) 1 else 0,
                genMs = song.genMs + genMs,
                genPlays = song.genPlays + genPlay,
            )),
            daily = daily(log.daily, track.key, ms, newPlay, now, generated),
            dailySince = if (log.dailySince == 0L) now else log.dailySince,
            artists = log.artists.plus(track.artist, ms),
            albums = if (album == null) log.albums else log.albums.plus(album, ms),
            playlists = if (playlist == null) log.playlists else log.playlists.plus(playlist, ms),
            days = log.days.plus(day, ms),
            services = log.services.plus(track.provider, ms),
        )
        if (newPlay || now - lastSave >= saveEveryMs) {
            lastSave = now
            store.save(_log.value)
        }
    }

    /** Forgets everything counted, on disk too: the start of a clean slate. */
    @Synchronized
    fun clear() {
        pendingTrack = null
        pendingMs = 0
        pendingNewPlay = false
        playing = null
        _log.value = ListeningLog()
        store.save(_log.value)
    }

    /** [track] was skipped early: a quiet "not this one" for the taste. */
    @Synchronized
    fun skip(track: Track) {
        publish()
        val log = _log.value
        _log.value = log.copy(skips = log.skips + (track.key to ((log.skips[track.key] ?: 0) + 1)))
    }

    /** Adds anything gathered and writes the log now: on quit, and when the app leaves the screen. */
    @Synchronized
    fun flush(now: Long = clock()) {
        publish()
        lastSave = now
        store.save(_log.value)
    }

    /** [daily] with [ms] of [key] added to today, in the device's own day and hour. */
    private fun daily(daily: Map<String, DayListening>, key: String, ms: Long, newPlay: Boolean, now: Long, generated: Boolean): Map<String, DayListening> {
        val local = now + offset(now)
        val today = Math.floorDiv(local, DAY_MS)
        val hour = (Math.floorMod(local, DAY_MS) / HOUR_MS).toInt()
        val day = daily[today.toString()] ?: DayListening()
        val next = daily + (today.toString() to day.copy(
            ms = day.ms + ms,
            songs = day.songs.plus(key, ms),
            plays = if (newPlay) day.plays + (key to ((day.plays[key] ?: 0) + 1)) else day.plays,
            hours = day.hours.plus(hour, ms),
            genSongs = if (generated) day.genSongs.plus(key, ms) else day.genSongs,
        ))
        // A new day is the moment to let the oldest go: once a day, not on every slice.
        return if (today.toString() in daily) next else next.filterKeys { (it.toLongOrNull() ?: 0L) > today - DAILY_KEEP_DAYS }
    }

    private fun <K> Map<K, Long>.plus(key: K, ms: Long): Map<K, Long> = this + (key to ((this[key] ?: 0L) + ms))
}

/** Days of [ListeningLog.daily] kept: the week shown, the week before it, and room to spare. */
const val DAILY_KEEP_DAYS = 63L

internal const val DAY_MS = 86_400_000L
internal const val HOUR_MS = 3_600_000L

/** How far this device's time zone is ahead of UTC at [at] (summer time included). */
fun localOffsetMs(at: Long): Long = java.util.TimeZone.getDefault().getOffset(at).toLong()

/** Today's number in the device's own calendar, counted in days from 1970-01-01. */
fun localEpochDay(now: Long = System.currentTimeMillis(), offset: (Long) -> Long = ::localOffsetMs): Long =
    Math.floorDiv(now + offset(now), DAY_MS)
