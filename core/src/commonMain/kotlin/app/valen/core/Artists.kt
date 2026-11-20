/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/** One service's artist: enough to show them and to open their page there. */
data class ServiceArtist(
    val provider: ProviderId,
    val id: String,
    val name: String,
    val imageUrl: String? = null,
)

/** What a release is, as the services sort them. */
enum class ReleaseKind(val label: String, val plural: String) {
    ALBUM("Album", "Albums"),
    SINGLE("Single", "Singles and EPs"),
    EP("EP", "Singles and EPs"),
    COMPILATION("Compilation", "Compilations"),
    ;

    companion object {
        /** The services' own words ("ALBUM", "single", "ep", "compilation"); anything else is an album. */
        fun of(word: String?): ReleaseKind = when (word?.trim()?.lowercase()) {
            "single" -> SINGLE
            "ep" -> EP
            "compilation" -> COMPILATION
            else -> ALBUM
        }
    }
}

/** An album, single or EP on one service. */
data class Release(
    val provider: ProviderId,
    val id: String,
    val title: String,
    val artist: String,
    val kind: ReleaseKind = ReleaseKind.ALBUM,
    val year: Int? = null,
    val artworkUrl: String? = null,
)

/** A playlist on a service that features the artist ("This Is …", a radio, a fan list). */
data class ServicePlaylist(
    val provider: ProviderId,
    val id: String,
    val title: String,
    val owner: String? = null,
    val artworkUrl: String? = null,
    val link: PlaylistLink? = null,
)

/** One service's page for an artist. */
data class ArtistProfile(
    val artist: ServiceArtist,
    /** A wide image for the top of the page, when the service has one. */
    val headerUrl: String? = null,
    val about: String? = null,
    val monthlyListeners: Long? = null,
    val followers: Long? = null,
    val verified: Boolean = false,
    /** Their most played songs, most played first. */
    val topTracks: List<Track> = emptyList(),
    /** How often each of [topTracks] was played, by [Track.key], where the service says. */
    val plays: Map<String, Long> = emptyMap(),
    val releases: List<Release> = emptyList(),
    /** Releases by others the artist is on. */
    val appearsOn: List<Release> = emptyList(),
    val playlists: List<ServicePlaylist> = emptyList(),
    val related: List<ServiceArtist> = emptyList(),
)

/** A release opened: its songs, in order. */
data class ReleaseTracks(
    val release: Release,
    val tracks: List<Track>,
    val label: String? = null,
)

/**
 * A service that has artist pages. Implemented by the adapters that can (Spotify through its
 * catalogue, YouTube Music through its browse pages); [ArtistDirectory] puts them together.
 */
interface ArtistCatalog {
    val provider: ProviderId

    /** Artists whose name matches [query], best first. */
    suspend fun findArtists(query: String, limit: Int = 6): List<ServiceArtist>

    /** The artist's page here. */
    suspend fun artist(id: String): ArtistProfile

    /** A release's songs here. */
    suspend fun release(id: String): ReleaseTracks

    /** Albums, singles and EPs matching [query], best first; none where the service has no such search. */
    suspend fun findReleases(query: String, limit: Int = 8): List<Release> = emptyList()

    /** Playlists matching [query], best first; none where the service has no such search. */
    suspend fun findPlaylists(query: String, limit: Int = 8): List<ServicePlaylist> = emptyList()
}

/** An artist as found across services: one name, every service's copy of them. */
data class ArtistResult(
    val name: String,
    val imageUrl: String?,
    val copies: List<ServiceArtist>,
) {
    val key: String get() = artistKey(name)
}

/** A release as found across services: the same title from each, shown once. */
data class MergedRelease(
    val title: String,
    val artist: String,
    val kind: ReleaseKind,
    val year: Int?,
    val artworkUrl: String?,
    /** Each service's copy, the one to open first first. */
    val copies: List<Release>,
) {
    val key: String get() = releaseKey(title)
    val services: List<ProviderId> get() = copies.map { it.provider }.distinct()
}

/** An artist's page put together from every service that has them. */
data class ArtistView(
    val name: String,
    val imageUrl: String?,
    val headerUrl: String?,
    val about: String?,
    val monthlyListeners: Long?,
    val followers: Long?,
    val verified: Boolean,
    /** The services this page was made from, and the artist's id on each. */
    val sources: List<ServiceArtist>,
    /** Most played songs, each recording once, from the service that plays it here. */
    val popular: List<Track>,
    /** Play counts of [popular] by [Track.key] (where a service gave one). */
    val plays: Map<String, Long>,
    val releases: List<MergedRelease>,
    val appearsOn: List<MergedRelease>,
    val playlists: List<ServicePlaylist>,
    val related: List<ArtistResult>,
    /** Services that have the artist but could not be read, with why. */
    val missing: Map<ProviderId, String> = emptyMap(),
)

/** Names that are the same artist: case, accents of punctuation and a leading "the" aside. */
fun artistKey(name: String): String =
    name.lowercase().replace('’', '\'')
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
        .removePrefix("the ").replace(Regex("""\s+"""), " ")

/** Titles that are the same release on two services ("Discovery" and "Discovery (Remastered)"). */
fun releaseKey(title: String): String {
    var t = title.lowercase().replace('’', '\'')
    t = Regex("""[\(\[]([^\)\]]*)[\)\]]""").replace(t) { m ->
        if (RELEASE_NOISE.containsMatchIn(m.groupValues[1])) " " else " ${m.groupValues[1]} "
    }
    t = t.split(" - ").filterIndexed { i, part -> i == 0 || !RELEASE_NOISE.containsMatchIn(part) }.joinToString(" ")
    return t.replace(Regex("""[^\p{L}\p{N}]+"""), " ").replace(Regex("""\s+"""), " ").trim()
}

private val RELEASE_NOISE = Regex("""\b(remaster(ed)?|\d{4} remaster|explicit|clean)\b""")

/**
 * Artist pages from every service at once. A name is looked up on each service that can be used
 * now; a service's artist counts only when its name is the same (never "the closest one"), so two
 * artists are never mixed. Songs on several services appear once and play from the copy that
 * plays here; releases with the same title appear once and open from the best copy.
 *
 * Pages and releases are kept for a while, so going back and forth is instant.
 */
class ArtistDirectory(
    /** The catalogues that can be asked now (a signed-out Spotify is left out). */
    private val catalogs: () -> List<ArtistCatalog>,
    /** The services in the person's order (the first is preferred for names, pictures and copies). */
    private val priority: () -> List<ProviderId>,
    /** Whether a song can be played here now (its service is usable). */
    private val playable: (Track) -> Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val pages = LinkedHashMap<String, Pair<ArtistView, Long>>()
    private val releases = LinkedHashMap<String, Pair<ReleaseTracks, Long>>()
    /** One service's artist page by its own id: a name search can answer with another artist. */
    private val profiles = LinkedHashMap<String, Pair<ArtistProfile, Long>>()
    private val lock = Any()

    private fun rank(p: ProviderId): Int = priority().indexOf(p).let { if (it < 0) 99 else it }

    /** Artists named like [query] on every service, one entry per artist, the exact name first. */
    suspend fun search(query: String, limit: Int = 8): List<ArtistResult> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val found = askAll { it.findArtists(q, limit) }.values.filterNotNull().flatten()
        val wanted = artistKey(q)
        return mergeArtists(found)
            .sortedWith(compareBy({ it.key != wanted }, { r -> r.copies.minOf { rank(it.provider) } }))
            .take(limit)
    }

    /**
     * Albums, singles and EPs matching [query] on every service, each once: the same title by the
     * same artist from two services is one release, opened from the preferred service. They keep
     * the services' own order of relevance, the preferred service's best first, then the next's.
     */
    suspend fun searchReleases(query: String, limit: Int = 8): List<MergedRelease> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val groups = LinkedHashMap<String, MutableList<Release>>()
        for (r in interleaved(askAll { it.findReleases(q, limit) })) {
            val by = artistsIn(r.artist).firstOrNull() ?: r.artist
            groups.getOrPut(releaseKey(r.title) + "|" + artistKey(by)) { mutableListOf() } += r
        }
        return groups.values.map { group ->
            val ordered = group.sortedBy { rank(it.provider) }
            val lead = ordered.first()
            MergedRelease(
                title = lead.title,
                artist = lead.artist,
                kind = lead.kind,
                year = group.mapNotNull { it.year }.minOrNull(),
                artworkUrl = ordered.firstNotNullOfOrNull { it.artworkUrl },
                copies = ordered.distinctBy { it.provider },
            )
        }.take(limit)
    }

    /** Playlists matching [query] on every service, in the same order as [searchReleases]. */
    suspend fun searchPlaylists(query: String, limit: Int = 8): List<ServicePlaylist> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return interleaved(askAll { it.findPlaylists(q, limit) })
            .distinctBy { it.provider to it.id }
            .take(limit)
    }

    /** Each service's first, in the person's order, then each one's second, and so on. */
    private fun <T> interleaved(answers: Map<ProviderId, List<T>?>): List<T> {
        val lists = answers.entries.sortedBy { rank(it.key) }.mapNotNull { it.value }
        val longest = lists.maxOfOrNull { it.size } ?: 0
        return (0 until longest).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
    }

    /**
     * The page of the artist called [name]. [known] are the artist's ids already in hand (from a
     * search, a related artist); the other services are searched for the same name.
     */
    suspend fun open(name: String, known: List<ServiceArtist> = emptyList(), fresh: Boolean = false): ArtistView {
        val key = artistKey(name)
        if (!fresh) cached(pages, key, PAGE_KEEP_MS)?.let { return it }
        val missing = LinkedHashMap<ProviderId, String>()
        val profiles = askAll { catalog ->
            val id = known.firstOrNull { it.provider == catalog.provider }
                ?: catalog.findArtists(name, 6).firstOrNull { artistKey(it.name) == key }
                ?: return@askAll null
            catalog.artist(id.id)
        }.let { answers ->
            answers.mapNotNull { (provider, profile) -> profile?.let { provider to it } }
        }
        lastFailures.forEach { (p, why) -> missing[p] = why }
        if (profiles.isEmpty()) {
            throw IllegalStateException(missing.values.firstOrNull() ?: "No service here has an artist called $name")
        }
        val view = merge(profiles.sortedBy { rank(it.first) }.map { it.second }, missing)
        remember(pages, key, view)
        return view
    }

    /** The page of [name] when it is still kept: what a page coming back shows at once. */
    fun kept(name: String): ArtistView? = cached(pages, artistKey(name), PAGE_KEEP_MS)

    /**
     * The page of one service's artist, by that service's own id: no name search, so a different
     * artist with the same name on another service can never answer for this one. A mix uses this
     * for a related artist: the service that called them related says exactly who they are.
     */
    suspend fun profile(provider: ProviderId, id: String): ArtistProfile {
        val key = "$provider:$id"
        cached(profiles, key, PAGE_KEEP_MS)?.let { return it }
        val catalog = catalogs().firstOrNull { it.provider == provider }
            ?: throw IllegalStateException("${provider.label} has no artist pages here")
        val found = withTimeout(ASK_TIMEOUT_MS) { catalog.artist(id) }
        remember(profiles, key, found)
        return found
    }

    /** The songs of [release] when a copy of them is still kept. */
    fun keptTracks(release: MergedRelease): ReleaseTracks? =
        release.copies.sortedBy { rank(it.provider) }.firstNotNullOfOrNull { cached(releases, "${it.provider.name}:${it.id}", PAGE_KEEP_MS) }

    /** The songs of [release], from the first copy that answers (the preferred one first). */
    suspend fun tracks(release: MergedRelease): ReleaseTracks {
        val ordered = release.copies.sortedBy { rank(it.provider) }
        var failure: Exception? = null
        for (copy in ordered) {
            val id = "${copy.provider.name}:${copy.id}"
            cached(releases, id, PAGE_KEEP_MS)?.let { return it }
            val catalog = catalogs().firstOrNull { it.provider == copy.provider } ?: continue
            try {
                val found = withTimeout(ASK_TIMEOUT_MS) { catalog.release(copy.id) }
                if (found.tracks.isEmpty()) continue
                remember(releases, id, found)
                return found
            } catch (e: CancellationException) {
                if (e is kotlinx.coroutines.TimeoutCancellationException) failure = IllegalStateException("${copy.provider.label} did not answer") else throw e
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure ?: IllegalStateException("No service here has the songs of ${release.title}")
    }

    /** Failures of the last [askAll], by service. */
    @Volatile private var lastFailures: Map<ProviderId, String> = emptyMap()

    /** Asks every usable catalogue at once; a slow or failing one is left out (and named). */
    private suspend fun <T> askAll(ask: suspend (ArtistCatalog) -> T?): Map<ProviderId, T?> = coroutineScope {
        val failures = LinkedHashMap<ProviderId, String>()
        val answers = catalogs().map { catalog ->
            async {
                catalog.provider to try {
                    withTimeout(ASK_TIMEOUT_MS) { ask(catalog) }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    synchronized(failures) { failures[catalog.provider] = "${catalog.provider.label} did not answer" }
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    synchronized(failures) { failures[catalog.provider] = e.message ?: "${catalog.provider.label} could not be read" }
                    null
                }
            }
        }.awaitAll()
        lastFailures = failures
        answers.toMap()
    }

    /** Every service's page as one; [profiles] in the person's order. */
    internal fun merge(profiles: List<ArtistProfile>, missing: Map<ProviderId, String> = emptyMap()): ArtistView {
        val first = profiles.first()
        // Songs: each recording once, in the order the services rank them (the first service's
        // list leads), played from the copy that can play here.
        val copies = LinkedHashMap<String, MutableList<Track>>()
        val plays = HashMap<String, Long>()
        val longest = profiles.maxOf { it.topTracks.size }
        for (i in 0 until longest) {
            for (p in profiles) {
                val t = p.topTracks.getOrNull(i) ?: continue
                copies.getOrPut(recordingKey(t.title, t.artist)) { mutableListOf() } += t
            }
        }
        val popular = copies.values.map { group ->
            val pick = group.sortedWith(compareBy({ !playable(it) }, { rank(it.provider) })).first()
            group.firstNotNullOfOrNull { t -> profiles.firstNotNullOfOrNull { it.plays[t.key] } }?.let { plays[pick.key] = it }
            pick
        }.take(POPULAR_KEPT)
        return ArtistView(
            name = first.artist.name,
            imageUrl = profiles.firstNotNullOfOrNull { it.artist.imageUrl },
            headerUrl = profiles.firstNotNullOfOrNull { it.headerUrl },
            about = profiles.firstNotNullOfOrNull { it.about?.trim()?.takeIf { a -> a.isNotEmpty() } },
            monthlyListeners = profiles.firstNotNullOfOrNull { it.monthlyListeners },
            followers = profiles.firstNotNullOfOrNull { it.followers },
            verified = profiles.any { it.verified },
            sources = profiles.map { it.artist },
            popular = popular,
            plays = plays,
            releases = mergeReleases(profiles.flatMap { it.releases }),
            appearsOn = mergeReleases(profiles.flatMap { it.appearsOn }),
            playlists = profiles.flatMap { it.playlists }.distinctBy { it.provider to it.id }.take(PLAYLISTS_KEPT),
            related = mergeArtists(profiles.flatMap { it.related }).filter { it.key != artistKey(first.artist.name) }.take(RELATED_KEPT),
            missing = missing,
        )
    }

    /** The same title on several services once, newest first; the preferred service's cover. */
    internal fun mergeReleases(all: List<Release>): List<MergedRelease> {
        val groups = LinkedHashMap<String, MutableList<Release>>()
        for (r in all) groups.getOrPut(releaseKey(r.title)) { mutableListOf() } += r
        return groups.values.map { group ->
            val ordered = group.sortedBy { rank(it.provider) }
            val lead = ordered.first()
            MergedRelease(
                title = lead.title,
                artist = lead.artist,
                kind = lead.kind,
                year = group.mapNotNull { it.year }.minOrNull(),
                artworkUrl = ordered.firstNotNullOfOrNull { it.artworkUrl },
                copies = ordered.distinctBy { it.provider },
            )
        }.sortedWith(compareBy<MergedRelease> { -(it.year ?: 0) }.thenBy { it.title.lowercase() })
    }

    private fun mergeArtists(all: List<ServiceArtist>): List<ArtistResult> {
        val groups = LinkedHashMap<String, MutableList<ServiceArtist>>()
        for (a in all) groups.getOrPut(artistKey(a.name)) { mutableListOf() } += a
        return groups.values.map { group ->
            val ordered = group.sortedBy { rank(it.provider) }
            ArtistResult(ordered.first().name, ordered.firstNotNullOfOrNull { it.imageUrl }, ordered.distinctBy { it.provider })
        }
    }

    private fun <T> cached(map: LinkedHashMap<String, Pair<T, Long>>, key: String, keepMs: Long): T? = synchronized(lock) {
        val (value, at) = map[key] ?: return null
        if (clock() - at > keepMs) {
            map.remove(key)
            null
        } else value
    }

    private fun <T> remember(map: LinkedHashMap<String, Pair<T, Long>>, key: String, value: T) = synchronized(lock) {
        map.remove(key)
        map[key] = value to clock()
        while (map.size > KEPT) map.remove(map.keys.first())
    }

    companion object {
        const val ASK_TIMEOUT_MS = 20_000L
        const val PAGE_KEEP_MS = 30 * 60_000L
        const val KEPT = 24
        const val POPULAR_KEPT = 10
        const val PLAYLISTS_KEPT = 16
        const val RELATED_KEPT = 16
    }
}

/**
 * The artists named in a song's artist line ("Daft Punk, Pharrell Williams", "A feat. B"),
 * in order, for "go to the artist".
 */
fun artistsIn(line: String): List<String> =
    // Not "&": "Simon & Garfunkel" and "Mumford & Sons" are one act each; services list several
    // artists with commas.
    line.split(Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+|\s*,\s*""", RegexOption.IGNORE_CASE))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { artistKey(it) }
