/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * A playlist in Valen's own library. Its songs are ordinary [Track]s, each on the service that
 * can play it: the playlist is the app's, exactly like the queue, so one list can mix services
 * and nothing has to be copied into an account.
 *
 * A playlist read from a service keeps [source], which is what lets a later import of the same
 * list refresh this one instead of adding a second copy.
 */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val tracks: List<Track> = emptyList(),
    /** When it was added or last refreshed; epoch millis, 0 when the platform did not say. */
    val addedAt: Long = 0L,
    val source: PlaylistSource? = null,
    val artworkUrl: String? = null,
    /**
     * The source's entries that no service could offer. Kept so an import never looks cleaner
     * than it was: the playlist screen lists them rather than silently shortening the list.
     */
    val unmatched: List<UnmatchedEntry> = emptyList(),
    /** What the reader said about the list itself: a shared sample, a capped page. */
    val note: String? = null,
    /** How a smart playlist was made, so it can be made again; null for any other list. */
    val smart: SmartSpec? = null,
    /**
     * Songs added here to a list read from a service or made by the mixer. A refresh or a remake
     * brings the source's songs back as they are; these are put back after them, so a song added
     * to a saved Spotify list stays in it — in Valen; nothing is written to the account.
     */
    val added: List<Track> = emptyList(),
    /** Songs taken out here (their keys), kept out when the list is read or made again. */
    val removed: List<String> = emptyList(),
    /** Renamed here: a refresh keeps this name instead of the source's. */
    val renamed: Boolean = false,
) {
    val isImportable: Boolean get() = source?.key != null

    /** The one list the heart fills. */
    val isLiked: Boolean get() = id == LIKED_PLAYLIST_ID

    /**
     * A list the person makes by hand: songs can be added and removed and it keeps them. A list read
     * from a service or made by the mixer is not one — a refresh or a remake would replace its songs.
     */
    val isOwn: Boolean get() = source == null && smart == null && !isLiked && !isOffline

    /** "Offline": Valen fills it with every song that plays with no network, and empties it. */
    val isOffline: Boolean get() = id == OFFLINE_PLAYLIST_ID

    /** Every list takes songs but Liked songs, which the heart fills, and Offline, which Valen fills. */
    val takesSongs: Boolean get() = !isLiked && !isOffline

    /** A list whose songs come from elsewhere (a service, the mixer), so additions are kept apart. */
    val isSourced: Boolean get() = source != null || smart != null
}

/** The id of "Liked songs": fixed, so every like lands in the same list on every start. */
const val LIKED_PLAYLIST_ID = "liked"
const val LIKED_PLAYLIST_NAME = "Liked songs"

/** The id of "Offline", the list of every song that plays with no network. */
const val OFFLINE_PLAYLIST_ID = "offline"
const val OFFLINE_PLAYLIST_NAME = "Offline"

/** Where a playlist was read from, and enough of it to read the same one again. */
@Serializable
data class PlaylistSource(
    /** The service it lives on; null for a list Valen keeps only locally. */
    val provider: ProviderId? = null,
    val playlistId: String? = null,
    val url: String? = null,
) {
    /**
     * Identity of the source list. Two imports of the same service playlist share it whatever
     * link they were read from (`…/playlist/X?si=…` is the same list as `…/playlist/X`).
     */
    val key: String?
        get() = when {
            provider == null -> null
            !playlistId.isNullOrBlank() -> "${provider.name}:$playlistId"
            !url.isNullOrBlank() -> "${provider.name}:$url"
            else -> null
        }

    /**
     * The address that reads this list again, for a refresh. A playlist the person made inside
     * Valen has no source and no address; one read from a service always has at least its id.
     */
    fun link(): PlaylistLink? {
        val provider = provider ?: return null
        val id = playlistId?.takeIf { it.isNotBlank() }
        val address = url?.takeIf { it.isNotBlank() } ?: id ?: return null
        return PlaylistLink(provider, id, address)
    }
}

/** One source entry that could not be found on any of the user's services. */
@Serializable
data class UnmatchedEntry(val title: String, val artist: String)

/** The on-disk shape. A version is written so a later change can migrate rather than guess. */
@Serializable
data class PlaylistFile(val version: Int = 1, val playlists: List<Playlist> = emptyList())

/**
 * Persistence is the platform's (a file on both), as with [SettingsStore]: core stays free of
 * java.io. A file that cannot be parsed is an empty library, not a crash at start.
 */
class PlaylistStore(
    private val read: () -> String?,
    private val write: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun load(): List<Playlist> = runCatching {
        read()?.let { json.decodeFromString<PlaylistFile>(it).playlists }?.map { p ->
            p.copy(
                artworkUrl = largestArtworkUrl(p.artworkUrl),
                tracks = p.tracks.map(Track::withLargestArtwork),
                added = p.added.map(Track::withLargestArtwork),
            )
        } ?: emptyList()
    }.getOrDefault(emptyList())

    fun save(playlists: List<Playlist>) {
        runCatching { write(json.encodeToString(PlaylistFile(playlists = playlists))) }
    }
}

/**
 * The playlists Valen keeps, as one observable list the UI reads and the importer writes.
 *
 * Every mutation runs under one [mutex] and re-reads the list inside it: an import can finish
 * while the user renames a playlist, and neither may write the other's stale copy away. A
 * re-import of the same source playlist replaces that entry (same id, same place in the list) —
 * importing twice must not leave two copies of the same list.
 */
class PlaylistLibrary(
    private val store: PlaylistStore,
    private val clock: () -> Long = { 0L },
) {
    private val mutex = Mutex()

    private val _playlists = MutableStateFlow(store.load())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    fun get(id: String): Playlist? = _playlists.value.firstOrNull { it.id == id }

    suspend fun add(
        name: String,
        tracks: List<Track>,
        source: PlaylistSource? = null,
        artworkUrl: String? = null,
        unmatched: List<UnmatchedEntry> = emptyList(),
        note: String? = null,
        smart: SmartSpec? = null,
        /** Replaces this playlist in place (a smart list made again) instead of adding one. */
        replacing: String? = null,
    ): Playlist = mutex.withLock {
        val current = _playlists.value
        val existing = replacing?.let { id -> current.firstOrNull { it.id == id } }
            ?: source?.key?.let { key -> current.firstOrNull { it.source?.key == key } }
        // What was added and taken out here survives the source's songs being read again.
        val removed = existing?.removed.orEmpty()
        val added = existing?.added.orEmpty()
        val kept = tracks.filterNot { it.key in removed }
        val have = kept.mapTo(HashSet()) { it.key }
        val playlist = Playlist(
            id = existing?.id ?: newId(current),
            name = if (existing?.renamed == true) existing.name else name.ifBlank { existing?.name ?: "Playlist" },
            tracks = kept + added.filter { have.add(it.key) },
            addedAt = clock(),
            source = source ?: existing?.source,
            artworkUrl = artworkUrl ?: existing?.artworkUrl,
            unmatched = unmatched,
            note = note,
            smart = smart ?: existing?.smart,
            added = added,
            removed = removed,
            renamed = existing?.renamed == true,
        )
        val next = if (existing == null) current + playlist else current.map { if (it.id == existing.id) playlist else it }
        _playlists.value = next
        store.save(next)
        playlist
    }

    /** Removes every playlist, on disk too. */
    suspend fun clear() = mutex.withLock {
        _playlists.value = emptyList()
        store.save(emptyList())
    }

    suspend fun remove(id: String): Boolean = mutex.withLock {
        val next = _playlists.value.filterNot { it.id == id }
        if (next.size == _playlists.value.size) {
            false
        } else {
            _playlists.value = next
            store.save(next)
            true
        }
    }

    suspend fun rename(id: String, name: String): Boolean = mutex.withLock {
        val clean = name.trim()
        if (clean.isEmpty()) return@withLock false
        var changed = false
        val next = _playlists.value.map {
            if (it.id == id && it.name != clean) {
                changed = true
                it.copy(name = clean, renamed = it.isSourced)
            } else {
                it
            }
        }
        if (changed) {
            _playlists.value = next
            store.save(next)
        }
        changed
    }

    /** A new empty list of the person's own, at the end of the library. */
    suspend fun create(name: String): Playlist = add(name.trim().ifBlank { "New playlist" }.take(80), emptyList())

    /**
     * Adds [tracks] to the end of the list, skipping songs it already has; the number added. A list
     * that is not there adds nothing.
     */
    suspend fun addTracks(id: String, tracks: List<Track>): Int = mutex.withLock {
        val current = _playlists.value
        val list = current.firstOrNull { it.id == id } ?: return@withLock 0
        val have = list.tracks.mapTo(HashSet()) { it.key }
        val fresh = tracks.filter { have.add(it.key) }
        if (fresh.isEmpty()) return@withLock 0
        val freshKeys = fresh.mapTo(HashSet()) { it.key }
        val next = current.map {
            when {
                it.id != id -> it
                // A sourced list remembers what was added here, and forgets a removal it undoes.
                it.isSourced -> it.copy(
                    tracks = it.tracks + fresh,
                    added = it.added + fresh,
                    removed = it.removed.filterNot { key -> key in freshKeys },
                )
                else -> it.copy(tracks = it.tracks + fresh)
            }
        }
        _playlists.value = next
        store.save(next)
        fresh.size
    }

    /** Takes the song out of the list; the liked list goes away with its last song. */
    suspend fun removeTrack(id: String, key: String): Boolean = mutex.withLock {
        val current = _playlists.value
        val list = current.firstOrNull { it.id == id } ?: return@withLock false
        val kept = list.tracks.filterNot { it.key == key }
        if (kept.size == list.tracks.size) return@withLock false
        val next = when {
            list.isLiked && kept.isEmpty() -> current.filterNot { it.id == id }
            // A song added here simply goes; one from the source is kept out of the next refresh.
            list.isSourced -> current.map {
                if (it.id != id) {
                    it
                } else if (it.added.any { t -> t.key == key }) {
                    it.copy(tracks = kept, added = it.added.filterNot { t -> t.key == key })
                } else {
                    it.copy(tracks = kept, removed = (it.removed + key).distinct())
                }
            }
            else -> current.map { if (it.id == id) it.copy(tracks = kept) else it }
        }
        _playlists.value = next
        store.save(next)
        true
    }

    fun contains(id: String, track: Track): Boolean = get(id)?.tracks?.any { it.key == track.key } == true

    fun isLiked(track: Track): Boolean = contains(LIKED_PLAYLIST_ID, track)

    /**
     * Likes the song, or takes the like back; whether it is liked now. "Liked songs" is made on the
     * first like, at the head of the library, newest first, and leaves with its last song.
     */
    suspend fun toggleLiked(track: Track): Boolean = mutex.withLock {
        val current = _playlists.value
        val liked = current.firstOrNull { it.isLiked }
        val next: List<Playlist>
        val nowLiked: Boolean
        when {
            liked == null -> {
                next = listOf(Playlist(id = LIKED_PLAYLIST_ID, name = LIKED_PLAYLIST_NAME, tracks = listOf(track), addedAt = clock())) + current
                nowLiked = true
            }
            liked.tracks.any { it.key == track.key } -> {
                val kept = liked.tracks.filterNot { it.key == track.key }
                next = if (kept.isEmpty()) current.filterNot { it.isLiked } else current.map { if (it.isLiked) it.copy(tracks = kept) else it }
                nowLiked = false
            }
            else -> {
                next = current.map { if (it.isLiked) it.copy(tracks = listOf(track) + it.tracks) else it }
                nowLiked = true
            }
        }
        _playlists.value = next
        store.save(next)
        nowLiked
    }

    /**
     * Sets "Offline" to [tracks]: made after Liked songs when it first has a song, left alone when
     * nothing changed (no write), gone when [tracks] is empty.
     */
    suspend fun setOffline(tracks: List<Track>) = mutex.withLock {
        val current = _playlists.value
        val list = current.firstOrNull { it.isOffline }
        val next = when {
            tracks.isEmpty() -> if (list == null) return@withLock else current.filterNot { it.isOffline }
            list == null -> {
                val made = Playlist(
                    id = OFFLINE_PLAYLIST_ID,
                    name = OFFLINE_PLAYLIST_NAME,
                    tracks = tracks,
                    addedAt = clock(),
                    note = "Songs that play with no network: downloaded, or kept whole after a listen. Settings can hide this list.",
                )
                val at = current.indexOfFirst { it.isLiked } + 1
                current.take(at) + made + current.drop(at)
            }
            list.tracks.map { it.key } == tracks.map { it.key } -> return@withLock
            else -> current.map { if (it.isOffline) it.copy(tracks = tracks) else it }
        }
        _playlists.value = next
        store.save(next)
    }

    /** A local id that no entry uses, even if the clock did not move between two imports. */
    private fun newId(current: List<Playlist>): String {
        while (true) {
            val id = "pl-${clock()}-${Random.nextInt(0x1000, 0x10000).toString(16)}"
            if (current.none { it.id == id }) return id
        }
    }
}
