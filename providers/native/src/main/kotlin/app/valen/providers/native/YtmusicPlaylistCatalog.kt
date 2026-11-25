/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.native

import app.myspoti.api.Image
import app.myspoti.api.Track as CatalogTrack
import app.myspoti.ytmusic.InnerTube
import app.myspoti.ytmusic.Ytm
import app.myspoti.ytmusic.YtmParse
import app.valen.core.PlaylistCatalog
import app.valen.core.PlaylistLink
import app.valen.core.PlaylistRef
import app.valen.core.ProviderId
import app.valen.core.ReadPlaylist
import app.valen.core.Track
import app.valen.core.largestArtworkUrl
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The largest picture, upgraded when YouTube serves a small size token in the address. */
internal fun List<Image>.largestYoutubeArtwork(): String? {
    val picked = fold<Image, Image?>(null) { best, img ->
        if (best == null || (img.width ?: 0) >= (best.width ?: 0)) img else best
    } ?: return null
    val url = picked.url
    val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?').lowercase()
    if (host.endsWith("ytimg.com") && "/vi/" in url && (picked.width ?: 0) in 1 until 300) {
        val path = url.substringBefore('?')
        if (path.endsWith("/default.jpg")) return path.removeSuffix("/default.jpg") + "/hqdefault.jpg"
        if ("?sqp=" in url) return path
    }
    return largestArtworkUrl(url)
}

/** One of the web app's songs as Valen's track, with the YouTube Music cover as its artwork. */
internal fun CatalogTrack.toValenTrack(): Track? {
    val videoId = uri.substringAfterLast(':').takeIf { Ytm.isVideoId(it) } ?: return null
    val artwork = (album?.images?.takeIf { it.isNotEmpty() } ?: images).largestYoutubeArtwork()
        ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    return Track(
        provider = ProviderId.YOUTUBE_MUSIC,
        id = videoId,
        title = name,
        artist = artists.joinToString(", ") { it.name }.ifBlank { "YouTube Music" },
        album = album?.name?.takeIf { it.isNotBlank() },
        artworkUrl = artwork,
        durationMs = durationMs,
        explicit = explicit,
        uri = "${Ytm.PROVIDER}:track:$videoId",
    )
}

/**
 * YouTube Music playlists, read through the web app's own API.
 *
 * A pasted playlist is public data and is read signed out ([api]); the account's own lists exist
 * only on its session ([account], the same client with the page's cookies). Nothing here opens a
 * browser: a signed-out machine reads any shared playlist, and the account lists say what is
 * missing instead of starting Chrome.
 */
class YtmusicPlaylistCatalog(
    private val api: InnerTube,
    private val account: InnerTube? = null,
) : PlaylistCatalog {

    override val provider: ProviderId = ProviderId.YOUTUBE_MUSIC

    override suspend fun playlists(): List<PlaylistRef> {
        val session = account
            ?: throw IllegalStateException("Sign in to YouTube Music to see your playlists")
        // The library answers one page of cards at a time; keep reading until a page adds nothing.
        val found = LinkedHashMap<String, PlaylistRef>()
        var answer: JsonElement = session.browse(LIKED_PLAYLISTS)
        var pages = 0
        while (true) {
            YtmParse.cards(answer).filterIsInstance<YtmParse.Item.PlaylistItem>()
                .mapNotNull { it.playlist.toRef() }
                .forEach { found.putIfAbsent(it.key, it) }
            val token = YtmParse.continuation(answer) ?: break
            if (++pages >= MAX_LIST_PAGES) break
            answer = session.browseMore(token)
        }
        return found.values.toList()
    }

    override suspend fun read(link: PlaylistLink): ReadPlaylist {
        val id = link.playlistId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("That link does not name a YouTube Music playlist")
        // The account's client reads a private list the account owns; signed out it is the same
        // (cookie-less) read a shared playlist needs.
        val session = account ?: api
        var answer: JsonElement = session.browse("VL$id")
        val header = YtmParse.header(answer)
        val tracks = ArrayList<CatalogTrack>()
        val blocked = LinkedHashSet<String>()
        var pages = 0
        var truncated = false
        var continuation = false
        var targetId: String? = null
        val seenTokens = HashSet<String>()
        while (true) {
            val shelves = playlistShelves(answer, continuation, targetId)
            if (shelves.isEmpty()) {
                throw IllegalStateException("YouTube Music returned an unreadable playlist page; try importing it again")
            }
            targetId = targetId ?: shelves.firstNotNullOfOrNull { it["targetId"]?.jsonPrimitive?.contentOrNull }
            for (item in playlistRows(shelves).filterIsInstance<YtmParse.Item.Song>()) {
                tracks += item.track
                if (!item.track.playable) {
                    item.track.toValenTrack()?.let { blocked += it.key }
                }
            }
            // Recommendations on the same page have their own continuation. Only follow the
            // playlist's shelf, or they can replace the real next page with suggested songs.
            val token = shelves.firstNotNullOfOrNull(YtmParse::continuation)
                ?: (YtmParse.deep(answer, "sectionListContinuation") + YtmParse.deep(answer, "sectionListRenderer"))
                    .firstNotNullOfOrNull { it["continuations"]?.let(YtmParse::continuation) }
                ?: break
            if (!seenTokens.add(token)) {
                throw IllegalStateException("YouTube Music repeated a playlist page; try importing it again")
            }
            if (++pages >= MAX_SONG_PAGES) {
                truncated = true
                break
            }
            answer = session.browseMore(token)
            continuation = true
        }
        val name = header?.title?.takeIf { it.isNotBlank() } ?: "YouTube Music playlist"
        val mapped = tracks.mapNotNull { it.toValenTrack() }
        return ReadPlaylist(
            name = name,
            tracks = mapped,
            artworkUrl = header?.images?.largestYoutubeArtwork(),
            note = if (truncated) "Only the first ${mapped.size} songs of this playlist were read" else null,
            link = PlaylistLink(provider, id, "https://music.youtube.com/playlist?list=$id"),
            blocked = blocked,
        )
    }

    /**
     * The playlist's own shelf only: a playlist page also carries a "recommended" shelf of songs,
     * and those are not part of the list even though the generic row reader would return them.
     */
    private fun playlistShelves(answer: JsonElement, continuation: Boolean, targetId: String?): List<JsonObject> {
        val shelves = YtmParse.deep(answer, "musicPlaylistShelfRenderer") +
            YtmParse.deep(answer, "musicPlaylistShelfContinuation")
        if (shelves.isNotEmpty() || !continuation) return shelves

        // Newer account responses append rows directly rather than returning a playlist shelf.
        // Keep these fallbacks on continuation responses so an initial recommendations shelf
        // can never be mistaken for the playlist itself.
        val appended = YtmParse.deep(answer, "appendContinuationItemsAction").filter { action ->
            val target = action["targetId"]?.jsonPrimitive?.contentOrNull
            action["continuationItems"] is JsonArray && (targetId == null || target == null || target == targetId)
        }
        if (targetId == null && appended.size > 1 &&
            appended.map { it["targetId"]?.jsonPrimitive?.contentOrNull }.toSet().let { it.size > 1 || null in it }
        ) {
            throw IllegalStateException("YouTube Music returned ambiguous playlist pages; try importing it again")
        }
        if (appended.isNotEmpty()) return appended
        val legacy = YtmParse.deep(answer, "musicShelfContinuation")
        if (legacy.isNotEmpty()) return legacy
        return YtmParse.deep(answer, "sectionListContinuation").flatMap {
            YtmParse.deep(it, "musicShelfRenderer")
        }
    }

    private fun playlistRows(shelves: List<JsonObject>): List<YtmParse.Item> =
        shelves.flatMap { shelf ->
            YtmParse.deep(shelf, "musicResponsiveListItemRenderer").mapNotNull { YtmParse.row(it) }
        }

    private fun app.myspoti.api.Playlist.toRef(): PlaylistRef? {
        val id = uri.removePrefix("${Ytm.PROVIDER}:playlist:").takeIf { it.isNotBlank() && it != uri } ?: return null
        return PlaylistRef(
            name = name,
            trackCount = trackCount,
            artworkUrl = images.largestYoutubeArtwork(),
            owner = owner,
            link = PlaylistLink(provider, id, "https://music.youtube.com/playlist?list=$id"),
        )
    }

    private companion object {
        /** The account library's playlists, as InnerTune and Metrolist read them. */
        const val LIKED_PLAYLISTS = "FEmusic_liked_playlists"

        /** A playlist's rows come ~100 a page; this reads up to 4,000 songs. */
        const val MAX_SONG_PAGES = 40

        /** The library's own list of playlists; a few pages cover a very large account. */
        const val MAX_LIST_PAGES = 10
    }
}
