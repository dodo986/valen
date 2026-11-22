/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/*
 * A song's words, and where they come from. Lyrics are looked up by song, never by service: a
 * song playing from SoundCloud is asked for by its title and artist like any other. Sources are
 * tried in order (the configured Lyprio/LiriQo pair, service lyrics, then LRCLIB); the first
 * source with words wins.
 * Everything here is pure: the sources that go to the network live in `providers/lyrics`.
 */

/** One word (or syllable) as it is sung: [space] says whether a space follows it. */
@Serializable
data class LyricWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val space: Boolean = true,
)

/** One line of the song. [words] is empty when the lyrics are timed by line only. */
@Serializable
data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    /** Backing vocals sung under this line ("oh-oh"), with their own timing when known. */
    val backing: List<LyricWord> = emptyList(),
    val backingText: String? = null,
    /** Who sings it, when the lyrics name more than one voice ("v1", "v2"). */
    val voice: String? = null,
    /** A line that is all backing vocals, with no main line to sit under. */
    val aside: Boolean = false,
) {
    val timedByWord: Boolean get() = words.isNotEmpty()
}

/** How closely the words follow the song. */
@Serializable
enum class LyricsTiming { Words, Lines, None }

@Serializable
data class Lyrics(
    val lines: List<LyricLine>,
    val timing: LyricsTiming,
    /** Where they came from, said plainly: "Lyprio · Musixmatch", "LRCLIB". */
    val source: String,
    /** The source knows the song and says it has no words. */
    val instrumental: Boolean = false,
) {
    /** The voices the lyrics name, in the order they first sing. */
    val voices: List<String> get() = lines.mapNotNull { it.voice }.distinct()

    companion object {
        fun instrumental(source: String) = Lyrics(emptyList(), LyricsTiming.None, source, instrumental = true)
    }
}

/** What a lyrics source is asked: the song as a person would name it. */
data class LyricsQuery(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    /** The YouTube video id, when the song plays from YouTube Music (some sources key on it). */
    val videoId: String? = null,
    /** The Spotify track id, when the song plays from Spotify (its own lyrics need no search). */
    val spotifyId: String? = null,
)

/**
 * The song's names, cleaned of what only advertises the upload: "(Official Video)", "[HD]",
 * " - Topic" after a channel, "feat. …" in a title. A different take ("(Live)", "(Acoustic)") keeps
 * its words, since its lyrics differ too.
 */
fun Track.lyricsQuery(): LyricsQuery {
    var name = title
    name = LYRIC_NOISE_BRACKETS.replace(name) { m ->
        val inner = m.groupValues[1].lowercase()
        if (LYRIC_NOISE_WORDS.any { inner.contains(it) } || inner.startsWith("feat") || inner.startsWith("ft")) "" else m.value
    }
    name = LYRIC_FEATURING.replace(name, "").trim().trimEnd('-', '–', '—', ' ')
    var by = artist.removeSuffix(" - Topic").trim()
    // A YouTube upload titled "Artist - Song" under the artist's own channel.
    val dash = name.indexOf(" - ")
    if (dash > 0 && name.substring(0, dash).trim().equals(by, ignoreCase = true)) {
        name = name.substring(dash + 3).trim()
    } else if (dash > 0 && provider == ProviderId.YOUTUBE_MUSIC && by.isBlank()) {
        by = name.substring(0, dash).trim()
        name = name.substring(dash + 3).trim()
    }
    return LyricsQuery(
        title = name.ifBlank { title },
        artist = by,
        album = album?.takeIf { it.isNotBlank() && it != title },
        durationMs = durationMs.takeIf { it > 0 },
        videoId = id.takeIf { provider == ProviderId.YOUTUBE_MUSIC && it.isNotBlank() },
        spotifyId = id.takeIf { provider == ProviderId.SPOTIFY && it.isNotBlank() },
    )
}

private val LYRIC_NOISE_BRACKETS = Regex("""\s*[\(\[\{]([^\)\]\}]*)[\)\]\}]""")
private val LYRIC_NOISE_WORDS = listOf(
    "official", "video", "audio", "lyric", "visuali", "hd", "hq", "4k", "remaster", "explicit", "clean", "mv", "m/v",
)
private val LYRIC_FEATURING = Regex("""\s+(feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)

/** A place that can find a song's words. */
interface LyricsSource {
    /** Its name, as the credit under the words says it. */
    val name: String

    /**
     * The song's words, or null when this source does not have them. Throws [LyricsUnavailable]
     * (or any other exception) when the source could not be asked; that is not a miss.
     */
    suspend fun find(query: LyricsQuery): Lyrics?
}

/** A source that could not answer, in words a person can read. */
class LyricsUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A source that was not asked this time and is not a failure either: Spotify signed in but its
 * player not up (a lyrics lookup never starts it). Nothing it might have had is remembered as a
 * miss, so the song is asked about again later.
 */
class LyricsSkipped(message: String) : Exception(message)

/**
 * A music service that keeps lyrics of its own (Spotify's lyrics card). Adapters implement it the
 * way they implement [ArtistCatalog]; [ServiceLyrics] makes one a [LyricsSource].
 */
interface LyricsCatalog {
    /**
     * The song's words from the service, or null when it has none or is signed out. Throws
     * [LyricsSkipped] when it could answer but nothing should be started to ask it.
     */
    suspend fun serviceLyrics(query: LyricsQuery): Lyrics?
}

/** A service's own lyrics as one of the sources [LyricsBook] asks. */
class ServiceLyrics(override val name: String, private val catalog: LyricsCatalog) : LyricsSource {
    override suspend fun find(query: LyricsQuery): Lyrics? = catalog.serviceLyrics(query)
}

/** Where a song's words stand. */
sealed interface LyricsState {
    data object Looking : LyricsState
    data class Found(val lyrics: Lyrics) : LyricsState

    /** Every source was asked and none has them. */
    data object Missing : LyricsState

    /** No source could be asked; [reason] says why. Not remembered: the next look tries again. */
    data class Failed(val reason: String) : LyricsState
}

/**
 * Lyrics kept between starts: what was found, and which songs no source had (for a few days, so a
 * song without words is not asked about at every play). The desktop keeps them under
 * `$XDG_CACHE_HOME/valen/lyrics`, Android in its cache folder.
 */
interface LyricsStore {
    /** What was kept for [key]: lyrics, a remembered miss ([Lyrics] null), or nothing. */
    fun read(key: String): Kept?
    fun write(key: String, lyrics: Lyrics?)

    data class Kept(val lyrics: Lyrics?, val savedAtMs: Long)

    object None : LyricsStore {
        override fun read(key: String): Kept? = null
        override fun write(key: String, lyrics: Lyrics?) {}
    }
}

/**
 * The words of the songs that play: looked up once per recording, from the first source that has
 * them, and kept (in memory for the songs of this run, in [store] across runs). Two screens asking
 * for the same song at once share one lookup.
 */
class LyricsBook(
    private val sources: List<LyricsSource>,
    private val store: LyricsStore = LyricsStore.None,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Mutex()
    private val kept = object : LinkedHashMap<String, LyricsState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsState>?) = size > KEPT_SONGS
    }
    private val looking = mutableMapOf<String, CompletableDeferred<LyricsState>>()

    /** Whether any source is set up at all. */
    val hasSources: Boolean get() = sources.isNotEmpty()

    /** What is already known about [track] without asking anyone: for a screen's first frame. */
    fun known(track: Track): LyricsState? = synchronized(kept) { kept[keyOf(track)] }

    /** [track]'s words: from memory, from the store, or from the sources in order. */
    suspend fun lyricsFor(track: Track, again: Boolean = false): LyricsState {
        val key = keyOf(track)
        if (!again) synchronized(kept) { kept[key] }?.let { return it }
        val (mine, deferred) = lock.withLock {
            val running = looking[key]
            if (running != null) false to running else true to CompletableDeferred<LyricsState>().also { looking[key] = it }
        }
        if (!mine) return deferred.await()
        try {
            // The store's file, the parse and the sources' answers are read away from the UI
            // thread: the pane asks from a composition, and on a phone that is the main thread.
            val state = withContext(Dispatchers.Default) { look(key, track, again) }
            if (state !is LyricsState.Failed) synchronized(kept) { kept[key] = state }
            deferred.complete(state)
            return state
        } catch (e: CancellationException) {
            deferred.cancel(e)
            throw e
        } finally {
            lock.withLock { if (looking[key] === deferred) looking.remove(key) }
        }
    }

    private suspend fun look(key: String, track: Track, again: Boolean): LyricsState {
        if (!again) {
            store.read(key)?.let { saved ->
                val lyrics = saved.lyrics
                if (lyrics != null) return LyricsState.Found(lyrics)
                if (clock() - saved.savedAtMs < MISS_KEPT_MS) return LyricsState.Missing
            }
        }
        if (sources.isEmpty()) return LyricsState.Missing
        val query = track.lyricsQuery()
        var failure: String? = null
        var skipped = false
        for (source in sources) {
            val found = try {
                source.find(query)
            } catch (e: CancellationException) {
                throw e
            } catch (_: LyricsSkipped) {
                skipped = true
                null
            } catch (e: Throwable) {
                // Not only exceptions: a reader that cannot load on this platform (an
                // ExceptionInInitializerError) is a source that failed, never a crash.
                failure = failure ?: "${source.name}: ${e.message ?: "it did not answer"}"
                null
            }
            if (found != null && (found.instrumental || found.lines.any { it.text.isNotBlank() })) {
                runCatching { store.write(key, found) }
                return LyricsState.Found(found)
            }
        }
        if (failure != null) return LyricsState.Failed(failure)
        // A source that was not asked may still have them: the miss is not kept across runs.
        if (!skipped) runCatching { store.write(key, null) }
        return LyricsState.Missing
    }

    companion object {
        /** No sources: every song is "missing". For tests and builds that ask for none. */
        val None = LyricsBook(emptyList())

        /**
         * One key per recording and length, so copies of the same audio on two services share their
         * words, and a version of another length does not. The lyrics' times are the length's: a
         * music video with a 30 s intro ("(Official Video)" is dropped from the name, so it is the
         * same recording) took the album version's lyrics and showed every line 30 s early, on
         * both platforms, again at every play from the store. Lengths are taken in
         * [LENGTH_STEP_MS] steps: copies seconds apart share a step or, across a step's edge, are
         * simply looked up twice; a longer intro never shares.
         */
        fun keyOf(track: Track): String {
            val recording = recordingKey(track.title, track.artist)
            if (track.durationMs <= 0) return recording
            return "$recording@${(track.durationMs + LENGTH_STEP_MS / 2) / LENGTH_STEP_MS}"
        }

        /** The step lengths are compared in (LRCLIB allows 4 s either way for one recording too). */
        const val LENGTH_STEP_MS = 4_000L

        private const val KEPT_SONGS = 48

        /** A song no source had is not asked about again for this long. */
        const val MISS_KEPT_MS = 3L * 24 * 60 * 60 * 1000
    }
}
