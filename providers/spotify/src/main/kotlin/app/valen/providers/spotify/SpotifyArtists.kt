/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.spotify

import app.myspoti.api.Album as SpotifyAlbum
import app.myspoti.api.Artist as SpotifyArtist
import app.myspoti.api.Image
import app.myspoti.api.Playlist as SpotifyPlaylist
import app.myspoti.spotify.SpotifyApi
import app.valen.core.ArtistProfile
import app.valen.core.PlaylistLink
import app.valen.core.ProviderId
import app.valen.core.Release
import app.valen.core.ReleaseKind
import app.valen.core.ReleaseTracks
import app.valen.core.ServiceArtist
import app.valen.core.ServicePlaylist

/**
 * Spotify's artist pages for [app.valen.core.ArtistDirectory], read through the Web Player's own
 * catalogue (the queries its artist and album pages make). [ready] waits for the page first.
 */
internal class SpotifyArtists(private val api: () -> SpotifyApi, private val ready: suspend () -> Unit) {

    suspend fun find(query: String, limit: Int): List<ServiceArtist> {
        ready()
        return api().search.artists(query, 0, limit).items.mapNotNull { it.toService() }
    }

    suspend fun findReleases(query: String, limit: Int): List<Release> {
        ready()
        return api().search.albums(query, 0, limit).items.mapNotNull { it.toRelease() }
    }

    suspend fun findPlaylists(query: String, limit: Int): List<ServicePlaylist> {
        ready()
        return api().search.playlists(query, 0, limit).items.mapNotNull { it.toService() }
    }

    suspend fun artist(id: String): ArtistProfile {
        ready()
        val page = api().catalog.artistPage("spotify:artist:$id")
        val artist = page.artist.toService() ?: ServiceArtist(ProviderId.SPOTIFY, id, page.artist.name)
        val tops = page.topTracks.mapNotNull { top -> top.track.toValenTrack()?.let { it to top.playcount } }
        val own = page.popularReleases.mapNotNull { it.toRelease() }
        // The popular releases are a handful; the full discography is one more query.
        val all = runCatching { api().catalog.discography("spotify:artist:$id", 0, 50).items.mapNotNull { it.toRelease() } }
            .getOrDefault(emptyList())
        return ArtistProfile(
            artist = artist,
            headerUrl = page.headerImages.widest(),
            about = page.biography,
            monthlyListeners = page.artist.monthlyListeners,
            followers = page.artist.followers,
            verified = page.artist.verified,
            topTracks = tops.map { it.first },
            plays = tops.mapNotNull { (t, n) -> n?.let { t.key to it } }.toMap(),
            releases = (own + all).distinctBy { it.id },
            appearsOn = page.appearsOn.mapNotNull { it.toRelease() },
            playlists = page.playlists.mapNotNull { it.toService() },
            related = page.related.mapNotNull { it.toService() },
        )
    }

    suspend fun release(id: String): ReleaseTracks {
        ready()
        val uri = "spotify:album:$id"
        val album = api().catalog.album(uri)
        val tracks = api().catalog.albumTracks(uri, 0, 100).items.mapNotNull { it.toValenTrack() }
        val release = album.toRelease() ?: Release(ProviderId.SPOTIFY, id, album.name, album.artists.joinToString(", ") { it.name })
        return ReleaseTracks(release, tracks, label = album.label)
    }
}

private fun List<Image>.widest(): String? = maxByOrNull { it.width ?: 0 }?.url

private fun SpotifyArtist.toService(): ServiceArtist? {
    val id = uri.takeIf { it.startsWith("spotify:artist:") }?.substringAfterLast(':') ?: return null
    return ServiceArtist(ProviderId.SPOTIFY, id, name, images.widest())
}

private fun SpotifyAlbum.toRelease(): Release? {
    val id = uri.takeIf { it.startsWith("spotify:album:") }?.substringAfterLast(':') ?: return null
    return Release(
        provider = ProviderId.SPOTIFY,
        id = id,
        title = name,
        artist = artists.joinToString(", ") { it.name },
        kind = ReleaseKind.of(type),
        year = releaseDate?.take(4)?.toIntOrNull(),
        artworkUrl = images.widest(),
    )
}

private fun SpotifyPlaylist.toService(): ServicePlaylist? {
    val id = uri.takeIf { it.startsWith("spotify:playlist:") }?.substringAfterLast(':') ?: return null
    return ServicePlaylist(
        provider = ProviderId.SPOTIFY,
        id = id,
        title = name,
        owner = owner,
        artworkUrl = images.widest(),
        link = PlaylistLink(ProviderId.SPOTIFY, id, "https://open.spotify.com/playlist/$id"),
    )
}
