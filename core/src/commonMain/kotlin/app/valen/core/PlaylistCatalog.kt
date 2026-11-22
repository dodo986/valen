/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

/**
 * One playlist as a service lists it, before its songs are read: enough for a list row and for
 * importing it. [link] is the address that reads it again, so an account playlist and a pasted
 * one are imported by exactly the same path.
 */
data class PlaylistRef(
    val name: String,
    val trackCount: Int = 0,
    val artworkUrl: String? = null,
    val owner: String? = null,
    val link: PlaylistLink,
) {
    val provider: ProviderId get() = link.provider

    /** Stable identity for a row, even when the service only gave a share address. */
    val key: String get() = link.playlistId ?: link.url
}

/**
 * A service that can list the account's playlists and read one of them. Optional on purpose: an
 * adapter that cannot read playlists simply does not implement it, and the app says so instead of
 * offering a button that does nothing.
 *
 * Implementations never open a browser to answer: the desktop page-based hosts already hold the
 * session (or refuse), and the native adapters read the service's own API.
 */
interface PlaylistCatalog {
    val provider: ProviderId

    /** The account's own playlists and the ones it follows, as the service orders them. */
    suspend fun playlists(): List<PlaylistRef>

    /**
     * One playlist's songs, by any address the user pasted. The answer's [ReadPlaylist.link] is
     * the list's own identity when the address was a short link.
     */
    suspend fun read(link: PlaylistLink): ReadPlaylist
}

/** One service's playlists: what it listed, or why it could not be asked. */
data class PlaylistChoices(
    val provider: ProviderId,
    val playlists: List<PlaylistRef> = emptyList(),
    val error: String? = null,
)

/**
 * A service that can find its own sets by name without having artist pages (SoundCloud). Optional
 * like [PlaylistCatalog]: a service without such a search is simply not asked. [ServicePlaylist.link]
 * must be able to read the set again, so the search result opens and imports by the same path.
 */
interface PlaylistSearch {
    val provider: ProviderId

    /** Playlists and albums matching [query], best first. */
    suspend fun findPlaylists(query: String, limit: Int = 8): List<ServicePlaylist>
}
