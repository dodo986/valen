/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.native

import app.myspoti.api.Album as CatalogAlbum
import app.myspoti.api.Artist as CatalogArtist
import app.myspoti.api.Image
import app.myspoti.api.Playlist as CatalogPlaylist
import app.myspoti.ytmusic.InnerTube
import app.myspoti.ytmusic.YtmParse
import app.valen.core.ArtistProfile
import app.valen.core.PlaylistLink
import app.valen.core.ProviderId
import app.valen.core.Release
import app.valen.core.ReleaseKind
import app.valen.core.ReleaseTracks
import app.valen.core.ServiceArtist
import app.valen.core.ServicePlaylist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * YouTube Music's artist and album pages for [app.valen.core.ArtistDirectory], read signed out
 * through the web app's own API (its search, its artist and album browse pages).
 */
internal class YtmusicArtists(
    private val api: InnerTube,
    private val searches: YtmSearches = YtmSearches(api, CoroutineScope(SupervisorJob() + Dispatchers.IO)),
) {

    suspend fun find(query: String, limit: Int): List<ServiceArtist> =
        YtmParse.search(searches.answer(query))
            .filterIsInstance<YtmParse.Item.ArtistItem>()
            .mapNotNull { it.artist.toService() }
            .take(limit)

    suspend fun findReleases(query: String, limit: Int): List<Release> =
        YtmParse.search(searches.answer(query))
            .filterIsInstance<YtmParse.Item.AlbumItem>()
            .mapNotNull { it.album.toRelease(fallbackArtist = "") }
            .distinctBy { it.id }
            .take(limit)

    suspend fun findPlaylists(query: String, limit: Int): List<ServicePlaylist> =
        YtmParse.search(searches.answer(query))
            .filterIsInstance<YtmParse.Item.PlaylistItem>()
            .mapNotNull { it.playlist.toService() }
            .distinctBy { it.id }
            .take(limit)

    suspend fun artist(id: String): ArtistProfile {
        val page = YtmParse.artist(id, api.browse(id))
        val artist = page.artist.toService() ?: ServiceArtist(ProviderId.YOUTUBE_MUSIC, id, page.artist.name)
        return ArtistProfile(
            artist = artist,
            about = page.about,
            followers = YtmParse.count(page.subscribers),
            topTracks = page.songs.mapNotNull { it.toValenTrack() },
            releases = page.albums.mapNotNull { it.toRelease(artist.name) },
            playlists = page.playlists.mapNotNull { it.toService() },
            related = page.related.mapNotNull { it.toService() },
        )
    }

    suspend fun release(id: String): ReleaseTracks {
        val (album, songs) = YtmParse.album(id, api.browse(id))
        val release = album.toRelease(album.artists.joinToString(", ") { it.name })
            ?: Release(ProviderId.YOUTUBE_MUSIC, id, album.name, album.artists.joinToString(", ") { it.name })
        return ReleaseTracks(release, songs.mapNotNull { it.toValenTrack() })
    }
}

/** The largest picture, as wide as YouTube serves it (its thumbnails take a size in the address). */
private fun List<Image>.largest(): String? = maxByOrNull { it.width ?: 0 }?.url
    ?.replace(Regex("""=w\d+-h\d+"""), "=w720-h720")

private fun CatalogArtist.toService(): ServiceArtist? {
    val id = uri.substringAfterLast(':').takeIf { it.isNotBlank() && uri.contains(":artist:") } ?: return null
    return ServiceArtist(ProviderId.YOUTUBE_MUSIC, id, name, images.largest())
}

private fun CatalogAlbum.toRelease(fallbackArtist: String): Release? {
    val id = uri.substringAfterLast(':').takeIf { it.isNotBlank() && uri.contains(":album:") } ?: return null
    return Release(
        provider = ProviderId.YOUTUBE_MUSIC,
        id = id,
        title = name,
        artist = artists.joinToString(", ") { it.name }.ifBlank { fallbackArtist },
        kind = ReleaseKind.of(type),
        year = releaseDate?.take(4)?.toIntOrNull(),
        artworkUrl = images.largest(),
    )
}

private fun CatalogPlaylist.toService(): ServicePlaylist? {
    val id = uri.substringAfterLast(':').takeIf { it.isNotBlank() } ?: return null
    return ServicePlaylist(
        provider = ProviderId.YOUTUBE_MUSIC,
        id = id,
        title = name,
        owner = owner,
        artworkUrl = images.largest(),
        link = PlaylistLink(ProviderId.YOUTUBE_MUSIC, id, "https://music.youtube.com/playlist?list=$id"),
    )
}
