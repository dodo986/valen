/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import app.valen.core.OfflineShelf
import app.valen.core.OfflineState
import app.valen.core.Playlist
import app.valen.core.PlaylistDownload
import app.valen.core.ProviderId
import app.valen.core.Track
import app.valen.core.largestArtworkUrl
import app.valen.core.DownloadableServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import kotlin.random.Random

/**
 * Playlists kept as ordinary music files (`~/Music/Valen/Artist/Album/Title.opus`, a phone's `Music/Valen`),
 * tagged and with the cover inside, plus `Playlists/<name>.m3u8` for each kept list (which any
 * player — and Valen's own import from this device — can open).
 *
 * **Quality.** Each song is the best stream its service offers ([resolve] asks for
 * `AudioQuality.HIGH`), kept exactly as it came: it is only re-wrapped ([SongFinisher]: FFmpeg on a
 * desktop, Valen's own Ogg, MP4 and ID3 writers on a phone) to add the tags and cover, so nothing
 * is encoded twice.
 *
 * **Spotify is never asked.** Its streams are copy-protected and its servers watch for bulk
 * fetching, so a Spotify song is kept as the same recording from YouTube Music or SoundCloud
 * ([sourceFor], matched as strictly as an import). The list's names and covers are already in
 * Valen's library, so a Spotify playlist downloads with no Spotify request at all.
 *
 * **Quickly, gently.** Two songs at once ([lanes]), so one is looked up while the other's bytes
 * come in; lookups (a search, a stream address) are spaced 0.7–1.4 s apart across both, never in a
 * burst; ranges of 8 MB; a cut download continued rather than restarted; a song whose copy is
 * already here is not fetched again. A service that answers 429/403 is rested (30 s,
 * doubling, at most 10 min, or as long as it asks) — every lane waits, and the run goes on one
 * song at a time — and the address looked up again; the state says so in [OfflineState.resting].
 * Three ordinary failures and the song is reported missing.
 */
class DiskShelf(
    private val root: File,
    private val indexFile: File,
    private val scope: CoroutineScope,
    /** A downloadable copy of the song (YouTube Music or SoundCloud), or null when none matches. */
    private val sourceFor: suspend (Track) -> Track?,
    /** The best stream of a downloadable copy. */
    private val resolve: suspend (Track) -> ResolvedStream,
    private val fetcher: StreamFetcher = StreamFetcher(rangeBytes = RANGE_BYTES),
    private val http: OkHttpClient = DiskAudioCache.defaultHttp(),
    /** Says what a fetched stream is and re-wraps it with its tags: FFmpeg, or Valen's own writers. */
    private val finisher: SongFinisher = SongFinisher.default(),
    /** Where unfinished songs wait (a phone's cache: its music folder takes music files only). */
    private val work: File = File(root, ".partial"),
    /** Told of each music or playlist file written, for a phone's media index. */
    private val onWritten: (File) -> Unit = {},
    /**
     * The least time between two lookups (a search or a stream address) across all lanes, ms; a
     * new one is drawn each time, so they never tick like a robot. Tests pass 0..0.
     */
    private val gapMs: LongRange = 700L..1_400L,
    /** Songs fetched at once. One lookup and one transfer overlap; a service that pushes back gets one. */
    private val lanes: Int = LANES,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : OfflineShelf {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val lock = Any()

    @Serializable
    data class KeptSong(
        val path: String,
        /** The copy that was downloaded ([Track.key]); the song itself for YouTube Music and SoundCloud. */
        val from: String,
        val codec: String? = null,
        val kbps: Int? = null,
        val bytes: Long = 0L,
        val at: Long = 0L,
    )

    @Serializable
    data class Index(
        val version: Int = 1,
        val songs: Map<String, KeptSong> = emptyMap(),
        val playlists: List<String> = emptyList(),
        /** Each kept list's `.m3u8`, so a rename or a forget finds the old one. */
        val listFiles: Map<String, String> = emptyMap(),
        /** Songs kept by themselves, from the song menu. */
        val singles: List<Track> = emptyList(),
    )

    private var index: Index = load()

    /** The library as last seen, for the lists' songs and names. */
    private var library: List<Playlist> = emptyList()

    /** Songs waiting, oldest first, by key. */
    private val pending = LinkedHashMap<String, Track>()

    /** Songs no copy could be kept of this run, with why. */
    private val missing = HashMap<String, String>()

    /** A song being fetched, and how far along it is. */
    private class Active(val track: Track) {
        @Volatile var fraction = 0f
        @Volatile var job: Job? = null
    }

    /** Songs being fetched now, oldest first, by key. */
    private val active = LinkedHashMap<String, Active>()

    /** Paths handed to songs still being finished, so two lanes never pick one name. */
    private val reserved = HashSet<String>()

    private var resting: OfflineState.Rest? = null

    /** Set once a service asked Valen to slow down: one lane for the rest of this run. */
    @Volatile private var calm = false

    private val pacer = Mutex()
    private var lastLookup = 0L
    private var nextGap = 0L
    private var restUntil = 0L

    private val _state = MutableStateFlow(OfflineState())
    override val state: StateFlow<OfflineState> = _state.asStateFlow()

    override val folder: String get() = root.absolutePath

    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) { work() }
        publish()
    }

    override fun fileFor(track: Track): String? = index.songs[track.key]?.path

    /** Follows the library: songs added to a kept list are fetched, a deleted kept list is forgotten. */
    fun watch(playlists: StateFlow<List<Playlist>>) {
        scope.launch {
            playlists.collect { lists ->
                val gone = synchronized(lock) {
                    library = lists
                    val ids = lists.mapTo(HashSet()) { it.id }
                    index.playlists.filter { it !in ids }
                }
                gone.forEach { forget(it) }
                synchronized(lock) {
                    index.playlists.forEach { id -> lists.firstOrNull { it.id == id }?.let(::enqueue) }
                    index.singles.forEach(::enqueueSong)
                }
                wake.trySend(Unit)
                publish()
                writeLists()
            }
        }
    }

    override fun keepSong(track: Track) {
        if (track.provider == ProviderId.LOCAL) return
        synchronized(lock) {
            if (index.singles.none { it.key == track.key }) index = index.copy(singles = index.singles + track)
            missing.remove(track.key)
            enqueueSong(track)
            save()
        }
        publish()
        wake.trySend(Unit)
    }

    override suspend fun forgetSong(track: Track) = withContext(Dispatchers.IO) {
        val doomed = synchronized(lock) {
            if (index.singles.none { it.key == track.key }) return@withContext
            index = index.copy(singles = index.singles.filter { it.key != track.key })
            val files = release(setOf(track.key))
            save()
            files
        }
        delete(doomed)
        publish()
    }

    override fun singleTracks(): List<Track> = synchronized(lock) { index.singles }

    override fun keep(playlist: Playlist) {
        synchronized(lock) {
            if (playlist.id !in index.playlists) index = index.copy(playlists = index.playlists + playlist.id)
            if (library.none { it.id == playlist.id }) library = library + playlist
            // A second press tries the songs that could not be kept again.
            playlist.tracks.forEach { missing.remove(it.key) }
            enqueue(playlist)
            save()
        }
        publish()
        wake.trySend(Unit)
    }

    override suspend fun forget(playlistId: String) = withContext(Dispatchers.IO) {
        val doomed = synchronized(lock) {
            if (playlistId !in index.playlists) return@withContext
            val mine = library.firstOrNull { it.id == playlistId }?.tracks.orEmpty().mapTo(HashSet()) { it.key }
            val list = index.listFiles[playlistId]
            index = index.copy(playlists = index.playlists - playlistId, listFiles = index.listFiles - playlistId)
            val files = release(mine)
            save()
            files + listOfNotNull(list)
        }
        delete(doomed)
        publish()
    }

    /**
     * Lets go of [keys] no kept list or single still needs, with the copies they were downloaded
     * as (kept under their own key too); stops their fetches. Returns the files no key names now.
     * Called under [lock].
     */
    private fun release(keys: Set<String>): List<String> {
        val needed = library.filter { it.id in index.playlists }.flatMap { it.tracks }.mapTo(HashSet()) { it.key }
        index.singles.mapTo(needed) { it.key }
        val direct = keys.filter { it !in needed }.toSet()
        val copies = direct.mapNotNullTo(HashSet()) { index.songs[it]?.from }
        val dropped = direct + index.songs.filter { (k, v) -> v.from in copies && k !in needed }.keys
        dropped.forEach { pending.remove(it) }
        active.filterKeys { it in dropped }.values.forEach { it.job?.cancel() }
        val kept = index.songs.filterKeys { it !in dropped }
        val stillUsed = kept.values.mapTo(HashSet()) { it.path }
        val files = index.songs.filterKeys { it in dropped }.values.map { it.path }.filter { it !in stillUsed }.distinct()
        index = index.copy(songs = kept)
        return files
    }

    private fun delete(paths: List<String>) {
        paths.forEach { path ->
            val f = File(path)
            if (f.isFile && f.canonicalPath.startsWith(root.canonicalPath)) f.delete()
            pruneUp(f.parentFile)
        }
    }

    // ---- the worker ----

    private fun enqueue(playlist: Playlist) {
        playlist.tracks.forEach(::enqueueSong)
        wake.trySend(Unit)
    }

    /** Called under [lock]. */
    private fun enqueueSong(t: Track) {
        if (t.provider == ProviderId.LOCAL) return
        if (index.songs.containsKey(t.key) || t.key in missing || t.key in active) return
        pending.putIfAbsent(t.key, t)
    }

    /**
     * Hands waiting songs to free lanes. Each runs on its own; a lane that ends writes the lists'
     * files before the state says the song is in ("downloaded" on screen means the playlist file
     * on disk has it too), then wakes this loop for the next song.
     */
    private suspend fun work() {
        while (scope.isActive) {
            val next = synchronized(lock) {
                if (active.size >= (if (calm) 1 else lanes.coerceAtLeast(1))) return@synchronized null
                val entry = pending.entries.firstOrNull() ?: return@synchronized null
                pending.remove(entry.key)
                entry.value.also { active[it.key] = Active(it) }
            }
            if (next == null) {
                wake.receive()
                continue
            }
            publish()
            val job = scope.launch(Dispatchers.IO) {
                try {
                    fetchSong(next)
                } finally {
                    withContext(NonCancellable) {
                        writeLists()
                        synchronized(lock) { active.remove(next.key) }
                        publish()
                        wake.trySend(Unit)
                    }
                }
            }
            synchronized(lock) { active[next.key]?.job = job }
        }
    }

    /**
     * A lookup (a search for a copy, or a stream's address), spaced from every other lookup on
     * any lane by [gapMs] and held while a service is being rested. The bytes themselves come from
     * the services' file servers and are not paced: those are what downloads are for.
     */
    private suspend fun <T> paced(block: suspend () -> T): T {
        pacer.withLock {
            val now = System.currentTimeMillis()
            val wait = maxOf(restUntil - now, lastLookup + nextGap - now)
            if (wait > 0) delay(wait)
            lastLookup = System.currentTimeMillis()
            nextGap = if (gapMs.last > 0) Random.nextLong(gapMs.first, gapMs.last + 1) else 0L
        }
        return block()
    }

    /** One song, with rests for a service that asks for them and a few tries for anything else. */
    private suspend fun fetchSong(track: Track) {
        var failures = 0
        var rests = 0
        val copy = try {
            if (track.provider in DownloadableServices) track else paced { sourceFor(track) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (copy == null) {
            miss(track, "Not found on YouTube Music or SoundCloud")
            return
        }
        // The same recording is already here (another list's song, or the song in another
        // service's name): this song is that file too, with nothing fetched.
        synchronized(lock) {
            index.songs[copy.key]?.takeIf { File(it.path).isFile }?.let { kept ->
                index = index.copy(songs = index.songs + (track.key to kept))
                save()
                return
            }
        }
        val me = synchronized(lock) { active[track.key] }
        val part = File(work, DiskAudioCache.safe(track.key) + ".part")
        while (true) {
            try {
                val stream = paced { resolve(copy) }
                runInterruptible {
                    fetcher.fetch(stream, part) { f ->
                        // A step a person can see, not every 256 KB.
                        val moved = me != null && f - me.fraction >= 0.02f
                        if (moved) {
                            me!!.fraction = f
                            publish()
                        }
                    }
                }
                val kept = finish(track, copy, part)
                synchronized(lock) {
                    reserved.remove(kept.path)
                    val songs = index.songs + (track.key to kept)
                    // The copy itself is kept too: the same video in another list is not fetched twice.
                    index = index.copy(songs = if (copy.key in songs) songs else songs + (copy.key to kept))
                    resting = null
                    save()
                }
                return
            } catch (e: CancellationException) {
                part.delete()
                throw e
            } catch (e: Throttled) {
                rests++
                if (rests > MAX_RESTS) {
                    miss(track, "${copy.provider.label} kept refusing; try again later")
                    return
                }
                val wait = (e.retryAfterMs ?: (REST_MS shl (rests - 1))).coerceAtMost(MAX_REST_MS)
                // Every lane waits out the rest, and the run goes on one song at a time.
                calm = true
                pacer.withLock { restUntil = maxOf(restUntil, System.currentTimeMillis() + wait) }
                synchronized(lock) {
                    resting = OfflineState.Rest("${copy.provider.label} asked Valen to slow down", clock() + wait)
                }
                publish()
                delay(wait)
                synchronized(lock) { resting = null }
                publish()
            } catch (e: Exception) {
                failures++
                if (failures >= MAX_FAILURES) {
                    part.delete()
                    miss(track, e.message ?: "Could not be downloaded")
                    return
                }
                delay(RETRY_MS * failures)
            }
        }
    }

    private fun miss(track: Track, reason: String) {
        synchronized(lock) { missing[track.key] = reason }
        publish()
    }

    /**
     * The fetched stream as a tagged music file in its place: re-wrapped with the cover (never
     * encoded again), else moved as it came in its own container.
     */
    private fun finish(track: Track, copy: Track, part: File): KeptSong {
        val probe = finisher.probe(part, track.durationMs.takeIf { it > 0 } ?: copy.durationMs)
        val cover = runCatching { cover(track.artworkUrl ?: copy.artworkUrl) }.getOrNull()
        val tags = SongTags(track.title, track.artist, track.album, "Kept by Valen from ${copy.provider.label}")
        val wrapped = SongFinisher.extFor(probe.codec)?.let { ext ->
            val out = File(work, part.nameWithoutExtension + ".wrapped")
            val made = runCatching { finisher.wrap(part, out, ext, tags, cover) }.getOrDefault(false) &&
                out.isFile && out.length() >= DiskAudioCache.MIN_BYTES
            if (made) out to ext else { out.delete(); null }
        }
        val (source, ext) = wrapped ?: (part to SongFinisher.rawExt(probe))
        val dest = place(track, ext)
        try {
            SongFinisher.move(source, dest)
        } catch (e: Exception) {
            synchronized(lock) { reserved.remove(dest.absolutePath) }
            throw e
        }
        part.delete()
        onWritten(dest)
        return KeptSong(
            path = dest.absolutePath,
            from = copy.key,
            codec = probe.codec,
            kbps = probe.kbps,
            bytes = dest.length(),
            at = clock(),
        )
    }

    /** The cover at its largest. */
    private fun cover(url: String?): ByteArray? {
        val address = largest(url ?: return null) ?: return null
        val request = Request.Builder().url(address).build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            val bytes = r.body?.bytes() ?: return null
            return bytes.takeIf { it.size >= 512 }
        }
    }

    /** Where a song lives: Artist/Album/Title.ext, a number added only when two songs share it. */
    private fun place(track: Track, ext: String): File {
        val artist = clean(track.artist.split(", ", " & ", " feat. ", " ft. ").firstOrNull().orEmpty()).ifBlank { "Unknown artist" }
        val album = clean(track.album.orEmpty()).ifBlank { "Singles" }
        val title = clean(track.title).ifBlank { "Untitled" }
        val folder = File(File(root, artist), album)
        var f = File(folder, "$title.$ext")
        var n = 2
        synchronized(lock) {
            val taken = index.songs.values.mapTo(HashSet()) { it.path }
            while (f.exists() || f.absolutePath in taken || f.absolutePath in reserved) {
                f = File(folder, "$title ($n).$ext")
                n++
            }
            reserved += f.absolutePath
        }
        return f
    }

    // ---- the lists' files and the index ----

    /** Each kept list as `Playlists/<name>.m3u8`, its songs as paths from there. */
    private fun writeLists() {
        val (lists, songs, files) = synchronized(lock) { Triple(library.filter { it.id in index.playlists }, index.songs, index.listFiles) }
        val dir = File(root, "Playlists")
        val written = HashMap<String, String>()
        for (list in lists) {
            val lines = list.tracks.mapNotNull { t ->
                val path = if (t.provider == ProviderId.LOCAL) t.id else songs[t.key]?.path ?: return@mapNotNull null
                val f = File(path)
                val rel = runCatching { f.relativeTo(dir).path }.getOrDefault(f.absolutePath)
                "#EXTINF:${t.durationMs / 1000},${t.artist} - ${t.title}\n$rel"
            }
            if (lines.isEmpty()) continue
            val target = File(dir, clean(list.name).ifBlank { list.id } + ".m3u8")
            val text = "#EXTM3U\n#PLAYLIST:${list.name}\n" + lines.joinToString("\n") + "\n"
            runCatching {
                dir.mkdirs()
                if (!target.isFile || target.readText() != text) {
                    target.writeText(text)
                    onWritten(target)
                }
                // A renamed list leaves its old file behind otherwise.
                files[list.id]?.takeIf { it != target.absolutePath }?.let { File(it).delete() }
                written[list.id] = target.absolutePath
            }
        }
        if (written.isNotEmpty()) synchronized(lock) {
            if (written.any { (id, p) -> index.listFiles[id] != p }) {
                index = index.copy(listFiles = index.listFiles + written)
                save()
            }
        }
    }

    private fun publish() {
        val next = synchronized(lock) {
            val keptKeys = index.songs.keys
            OfflineState(
                playlists = index.playlists.associateWith { id ->
                    val list = library.firstOrNull { it.id == id }
                    val tracks = list?.tracks.orEmpty()
                    val keys = tracks.mapTo(HashSet()) { it.key }
                    // With two lanes, the list shows the first of its songs being fetched.
                    val playing = active.values.firstOrNull { it.track.key in keys }
                    PlaylistDownload(
                        total = tracks.size,
                        done = tracks.count { it.provider == ProviderId.LOCAL || it.key in keptKeys },
                        missing = tracks.filter { it.key in missing }.associate { "${it.title} — ${it.artist}" to missing.getValue(it.key) },
                        current = playing?.track?.let { "${it.title} — ${it.artist}" }
                            ?: if (tracks.any { it.key in pending }) "Waiting" else null,
                        currentFraction = playing?.fraction ?: 0f,
                    )
                },
                kept = keptKeys.toSet(),
                files = index.songs.values.mapTo(HashSet()) { it.path }.size,
                singles = index.singles.mapTo(HashSet()) { it.key },
                fetching = active.mapValues { it.value.fraction },
                waiting = pending.keys.toSet(),
                unavailable = HashMap(missing),
                resting = resting,
            )
        }
        _state.value = next
    }

    private fun load(): Index {
        val read = runCatching { if (indexFile.isFile) json.decodeFromString<Index>(indexFile.readText()) else Index() }.getOrDefault(Index())
        // A file deleted by hand is simply not kept any more (it is fetched again if its list is kept).
        return read.copy(songs = read.songs.filterValues { File(it.path).isFile })
    }

    private fun save() {
        runCatching {
            indexFile.parentFile?.mkdirs()
            val tmp = File(indexFile.parentFile, indexFile.name + ".tmp")
            tmp.writeText(json.encodeToString(Index.serializer(), index))
            Files.move(tmp.toPath(), indexFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun pruneUp(dir: File?) {
        var d = dir
        val top = root.canonicalFile
        while (d != null && d.canonicalFile != top && d.canonicalPath.startsWith(top.path)) {
            if (d.listFiles()?.isEmpty() != true) return
            d.delete()
            d = d.parentFile
        }
    }

    companion object {
        const val MAX_FAILURES = 3
        const val MAX_RESTS = 6
        const val RETRY_MS = 2_000L
        const val REST_MS = 30_000L
        const val MAX_REST_MS = 10 * 60_000L

        /** Two songs at once: one being looked up while the other's bytes come in. */
        const val LANES = 2

        /** 8 MB ranges: most songs in one request, under the size YouTube slows to playing speed. */
        const val RANGE_BYTES = 8L * 1024 * 1024

        /** A name any file system takes: no separators or reserved marks, not hidden, not too long. */
        fun clean(name: String): String = name
            .replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]"), "_")
            .trim()
            .trimStart('.')
            .take(120)
            .trim()

        /** The biggest picture a cover address can give: Google's sizes and SoundCloud's names. */
        fun largest(url: String): String? {
            if (!url.startsWith("https://") && !url.startsWith("http://")) return null
            return when {
                "googleusercontent.com" in url || "ggpht.com" in url -> largestArtworkUrl(url)
                "sndcdn.com" in url -> url.replace(Regex("-(large|t\\d+x\\d+|small|badge|tiny|mini|crop)\\."), "-t500x500.")
                else -> url
            }
        }

        /** A FLAC picture block, base64: how Ogg and FLAC files carry a cover. */
        fun pictureBlock(image: ByteArray): String {
            val mime = (if (image.size > 3 && image[0] == 0x89.toByte() && image[1] == 'P'.code.toByte()) "image/png" else "image/jpeg").toByteArray()
            val out = java.io.ByteArrayOutputStream()
            fun int(v: Int) { out.write(v ushr 24 and 0xff); out.write(v ushr 16 and 0xff); out.write(v ushr 8 and 0xff); out.write(v and 0xff) }
            int(3) // front cover
            int(mime.size); out.write(mime)
            int(0) // no description
            int(0); int(0); int(0); int(0)
            int(image.size); out.write(image)
            return Base64.getEncoder().encodeToString(out.toByteArray())
        }

        fun tool(name: String, env: String): String? {
            System.getenv(env)?.takeIf { it.isNotBlank() && File(it).canExecute() }?.let { return it }
            return System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
                .map { File(it, name) }.firstOrNull { it.canExecute() }?.absolutePath
        }

        /** `$XDG_MUSIC_DIR/Valen` (from `user-dirs.dirs`), else `~/Music/Valen`. */
        fun defaultRoot(home: String = System.getProperty("user.home")): File {
            System.getenv("VALEN_DOWNLOADS_DIR")?.takeIf { it.isNotBlank() }?.let { return File(it) }
            val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config"
            val music = runCatching {
                File(config, "user-dirs.dirs").readLines()
                    .firstOrNull { it.startsWith("XDG_MUSIC_DIR=") }
                    ?.substringAfter('=')?.trim('"')
                    ?.replace("\$HOME", home)
            }.getOrNull()?.takeIf { it.isNotBlank() && it != home }
            return File(music ?: "$home/Music", "Valen")
        }
    }
}
