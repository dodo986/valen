/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Artist pages put together from two services: the same artist only, each song and release once. */
class ArtistDirectoryTest {

    private class FakeCatalog(
        override val provider: ProviderId,
        val artists: List<ServiceArtist>,
        val profiles: Map<String, ArtistProfile>,
        val releases: Map<String, ReleaseTracks> = emptyMap(),
        var fail: String? = null,
        /** What this service's playlist search answers (none: the interface's default). */
        val foundPlaylists: List<ServicePlaylist> = emptyList(),
    ) : ArtistCatalog {
        var opened = 0
        override suspend fun findArtists(query: String, limit: Int) = artists.filter { it.name.contains(query, ignoreCase = true) }
        override suspend fun artist(id: String): ArtistProfile {
            fail?.let { error(it) }
            opened++
            return profiles.getValue(id)
        }
        override suspend fun release(id: String) = releases.getValue(id)
        override suspend fun findPlaylists(query: String, limit: Int) =
            foundPlaylists.filter { it.title.contains(query, ignoreCase = true) }.take(limit)
    }

    /** A service with a set search but no artist pages (SoundCloud's shape). */
    private class FakeSearcher(
        override val provider: ProviderId,
        val sets: List<ServicePlaylist>,
        var fail: String? = null,
    ) : PlaylistSearch {
        var asked = 0
        override suspend fun findPlaylists(query: String, limit: Int): List<ServicePlaylist> {
            asked++
            fail?.let { error(it) }
            return sets.filter { it.title.contains(query, ignoreCase = true) }.take(limit)
        }
    }

    private fun song(p: ProviderId, id: String, title: String, artist: String = "Daft Punk") = Track(p, id, title, artist)

    private val sp = ServiceArtist(ProviderId.SPOTIFY, "4tZ", "Daft Punk", "https://sp/img")
    private val yt = ServiceArtist(ProviderId.YOUTUBE_MUSIC, "UCdp", "Daft Punk", "https://yt/img")
    private val impostor = ServiceArtist(ProviderId.YOUTUBE_MUSIC, "UCx", "Daft Punk Tribute Band")

    private fun spotify() = FakeCatalog(
        ProviderId.SPOTIFY, listOf(sp),
        mapOf(
            "4tZ" to ArtistProfile(
                artist = sp, about = "French duo", monthlyListeners = 25_000_000, verified = true,
                topTracks = listOf(song(ProviderId.SPOTIFY, "s1", "Get Lucky"), song(ProviderId.SPOTIFY, "s2", "One More Time")),
                plays = mapOf("SPOTIFY:s1" to 1_500_000_000L),
                releases = listOf(
                    Release(ProviderId.SPOTIFY, "a1", "Random Access Memories", "Daft Punk", ReleaseKind.ALBUM, 2013, "https://sp/ram"),
                    Release(ProviderId.SPOTIFY, "a2", "Discovery", "Daft Punk", ReleaseKind.ALBUM, 2001, "https://sp/disc"),
                ),
                related = listOf(ServiceArtist(ProviderId.SPOTIFY, "j", "Justice")),
            ),
        ),
    )

    private fun youtube() = FakeCatalog(
        ProviderId.YOUTUBE_MUSIC, listOf(impostor, yt),
        mapOf(
            "UCdp" to ArtistProfile(
                artist = yt, about = "From YouTube",
                topTracks = listOf(song(ProviderId.YOUTUBE_MUSIC, "y1", "One More Time (Official Video)"), song(ProviderId.YOUTUBE_MUSIC, "y2", "Around the World")),
                releases = listOf(
                    Release(ProviderId.YOUTUBE_MUSIC, "MP1", "Discovery (Remastered)", "Daft Punk", ReleaseKind.ALBUM, 2001, "https://yt/disc"),
                    Release(ProviderId.YOUTUBE_MUSIC, "MP2", "Alive 2007", "Daft Punk", ReleaseKind.ALBUM, 2007),
                ),
                playlists = listOf(ServicePlaylist(ProviderId.YOUTUBE_MUSIC, "PL1", "This is Daft Punk")),
                related = listOf(ServiceArtist(ProviderId.YOUTUBE_MUSIC, "UCj", "Justice", "https://yt/justice"), ServiceArtist(ProviderId.YOUTUBE_MUSIC, "UCa", "Air")),
            ),
        ),
    )

    private fun directory(
        vararg catalogs: ArtistCatalog,
        searchers: List<PlaylistSearch> = emptyList(),
        playable: (Track) -> Boolean = { true },
        now: () -> Long = { 0L },
    ) =
        ArtistDirectory(
            catalogs = { catalogs.toList() },
            priority = { listOf(ProviderId.SPOTIFY, ProviderId.YOUTUBE_MUSIC, ProviderId.SOUNDCLOUD) },
            playable = playable,
            playlistSearches = { searchers },
            clock = now,
        )

    @Test
    fun `one page from both services, the same artist only, each song and release once`() = runTest {
        val view = directory(spotify(), youtube()).open("Daft Punk")
        assertEquals("Daft Punk", view.name)
        assertEquals("https://sp/img", view.imageUrl, "the preferred service's picture")
        assertEquals("French duo", view.about)
        assertEquals(25_000_000L, view.monthlyListeners)
        assertEquals(listOf("4tZ", "UCdp"), view.sources.map { it.id }, "the tribute band was not taken for them")

        // "One More Time" is on both: once, from the preferred service; the rest interleaved.
        assertEquals(listOf("Get Lucky", "One More Time", "Around the World"), view.popular.map { it.title })
        assertEquals(ProviderId.SPOTIFY, view.popular[1].provider)
        assertEquals(1_500_000_000L, view.plays["SPOTIFY:s1"])

        // "Discovery" and "Discovery (Remastered)" are one release with both copies; newest first.
        assertEquals(listOf("Random Access Memories", "Alive 2007", "Discovery"), view.releases.map { it.title })
        assertEquals(listOf(ProviderId.SPOTIFY, ProviderId.YOUTUBE_MUSIC), view.releases.last().services)
        assertEquals("https://sp/disc", view.releases.last().artworkUrl)

        assertEquals(listOf("Justice", "Air"), view.related.map { it.name })
        assertEquals("https://yt/justice", view.related.first().imageUrl, "a picture from whichever service has one")
        assertEquals(listOf("This is Daft Punk"), view.playlists.map { it.title })
    }

    @Test
    fun `a song that cannot play here is shown from the service that can`() = runTest {
        val view = directory(spotify(), youtube(), playable = { it.provider != ProviderId.SPOTIFY }).open("Daft Punk")
        val omt = view.popular.first { it.title.startsWith("One More Time") }
        assertEquals(ProviderId.YOUTUBE_MUSIC, omt.provider)
    }

    @Test
    fun `a service that fails leaves the page to the others, and says so`() = runTest {
        val broken = spotify().also { it.fail = "Spotify is still starting" }
        val view = directory(broken, youtube()).open("Daft Punk")
        assertEquals(listOf(ProviderId.YOUTUBE_MUSIC), view.sources.map { it.provider })
        assertEquals("Spotify is still starting", view.missing[ProviderId.SPOTIFY])

        val nobody = directory(spotify(), youtube())
        assertThrows<IllegalStateException> { nobody.open("Nobody At All") }
    }

    @Test
    fun `a page is kept for a while, so going back is instant`() = runTest {
        var now = 0L
        val sp = spotify()
        val dir = directory(sp, now = { now })
        dir.open("Daft Punk")
        dir.open("daft punk")
        assertEquals(1, sp.opened)
        // A page coming back reads what is kept without asking (and nothing once it has gone stale).
        assertEquals("Daft Punk", dir.kept("DAFT PUNK")?.name)
        now += ArtistDirectory.PAGE_KEEP_MS + 1
        assertEquals(null, dir.kept("Daft Punk"))
        dir.open("Daft Punk")
        assertEquals(2, sp.opened)
    }

    @Test
    fun `search lists each artist once, the exact name first`() = runTest {
        val found = directory(spotify(), youtube()).search("daft punk")
        assertEquals(listOf("Daft Punk", "Daft Punk Tribute Band"), found.map { it.name })
        assertEquals(2, found.first().copies.size)
    }

    @Test
    fun `a release opens from its preferred copy, or the next that answers`() = runTest {
        val sp = spotify()
        val yt = FakeCatalog(
            ProviderId.YOUTUBE_MUSIC, emptyList(), emptyMap(),
            releases = mapOf("MP1" to ReleaseTracks(Release(ProviderId.YOUTUBE_MUSIC, "MP1", "Discovery", "Daft Punk"), listOf(song(ProviderId.YOUTUBE_MUSIC, "y1", "One More Time")))),
        )
        val release = MergedRelease(
            "Discovery", "Daft Punk", ReleaseKind.ALBUM, 2001, null,
            listOf(Release(ProviderId.SPOTIFY, "a2", "Discovery", "Daft Punk"), Release(ProviderId.YOUTUBE_MUSIC, "MP1", "Discovery", "Daft Punk")),
        )
        // Spotify has no songs for it here (an empty answer): YouTube Music's copy opens.
        val tracks = directory(sp, yt).tracks(release)
        assertEquals(listOf("One More Time"), tracks.tracks.map { it.title })
    }

    /** A set's address as search finds it, which [PlaylistCatalog.read] opens for import. */
    private val ghostlandLink = PlaylistLink(
        ProviderId.SOUNDCLOUD,
        "2317140903",
        "https://soundcloud.com/overtonightofficial/sets/ghostland-forever",
    )

    @Test
    fun `a service without artist pages is asked for its sets by name`() = runTest {
        val soundcloud = FakeSearcher(
            ProviderId.SOUNDCLOUD,
            listOf(
                ServicePlaylist(
                    provider = ProviderId.SOUNDCLOUD,
                    id = "2317140903",
                    title = "ghostland forever",
                    owner = "overtonight",
                    artworkUrl = "https://i1.sndcdn.com/a-t500x500.jpg",
                    link = ghostlandLink,
                ),
            ),
        )
        val youtube = FakeCatalog(
            ProviderId.YOUTUBE_MUSIC, emptyList(), emptyMap(),
            foundPlaylists = listOf(ServicePlaylist(ProviderId.YOUTUBE_MUSIC, "PL1", "Ghostland forever mix")),
        )

        val found = directory(youtube, searchers = listOf(soundcloud)).searchPlaylists("ghostland")

        // The catalogue's own playlist first (YouTube Music is above SoundCloud in the order).
        assertEquals(listOf("Ghostland forever mix", "ghostland forever"), found.map { it.title })
        assertEquals("overtonight", found.last().owner)
        assertEquals(ghostlandLink, found.last().link, "the set keeps the address that opens it")
    }

    @Test
    fun `a set search that fails loses its own answer only, and is not a missing artist page`() = runTest {
        val youtube = FakeCatalog(
            ProviderId.YOUTUBE_MUSIC, listOf(yt),
            mapOf("UCdp" to ArtistProfile(artist = yt, topTracks = listOf(song(ProviderId.YOUTUBE_MUSIC, "y1", "One More Time")))),
            foundPlaylists = listOf(ServicePlaylist(ProviderId.YOUTUBE_MUSIC, "PL1", "Daft Punk mix")),
        )
        val broken = FakeSearcher(ProviderId.SOUNDCLOUD, emptyList(), fail = "SoundCloud did not answer")
        val dir = directory(youtube, searchers = listOf(broken))

        assertEquals(listOf("Daft Punk mix"), dir.searchPlaylists("Daft").map { it.title })
        assertEquals(1, broken.asked, "the service was asked")

        val view = dir.open("Daft Punk")
        assertTrue(view.missing.isEmpty(), "a failed set search is not a missing artist page: ${view.missing}")
    }

    @Test
    fun `the artists of a song line are told apart`() {
        assertEquals(listOf("Daft Punk", "Pharrell Williams"), artistsIn("Daft Punk, Pharrell Williams"))
        assertEquals(listOf("Calvin Harris", "Dua Lipa"), artistsIn("Calvin Harris feat. Dua Lipa"))
        assertEquals(listOf("Simon & Garfunkel"), artistsIn("Simon & Garfunkel"))
        assertEquals(listOf("Lil Nas X"), artistsIn("Lil Nas X"))
        assertTrue(artistKey("The Weeknd") == artistKey("weeknd"))
    }
}
