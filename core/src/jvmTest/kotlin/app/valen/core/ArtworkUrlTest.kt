/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ArtworkUrlTest {
    @Test
    fun `google and youtube thumbnail urls upgrade without shrinking or distorting`() {
        assertNull(largestArtworkUrl(null))
        assertEquals("", largestArtworkUrl(""))
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj",
            largestArtworkUrl("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj"),
        )
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w1200-h1200-p-k-no-nu",
            largestArtworkUrl("https://lh3.googleusercontent.com/abc=w226-h226-p-k-no-nu"),
        )
        assertEquals(
            "https://yt3.googleusercontent.com/abc=s1200-c-k-c0x00ffffff-no-rj",
            largestArtworkUrl("https://yt3.googleusercontent.com/abc=s176-c-k-c0x00ffffff-no-rj"),
        )
        assertEquals(
            "https://yt3.ggpht.com/abc=s1200-c-k-c0x00ffffff-no-rj",
            largestArtworkUrl("https://yt3.ggpht.com/abc=s88-c-k-c0x00ffffff-no-rj"),
        )
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w1200-h499-l90-rj",
            largestArtworkUrl("https://lh3.googleusercontent.com/abc=w544-h226-l90-rj"),
        )
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w2880-h1200-l90-rj",
            largestArtworkUrl("https://lh3.googleusercontent.com/abc=w2880-h1200-l90-rj"),
        )
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w1400-h1400-l90-rj",
            largestArtworkUrl("https://lh3.googleusercontent.com/abc=w1400-h1400-l90-rj"),
        )
        assertEquals(
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
            largestArtworkUrl("https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg"),
        )
        assertEquals(
            "https://i.scdn.co/image/abc",
            largestArtworkUrl("https://i.scdn.co/image/abc"),
        )
    }

    @Test
    fun `persisted playlists, listening history and settings upgrade low-res covers on load`() {
        val low = "https://lh3.googleusercontent.com/cover=w120-h120-l90-rj"
        val high = "https://lh3.googleusercontent.com/cover=w1200-h1200-l90-rj"
        val track = Track(ProviderId.YOUTUBE_MUSIC, "vid1", "Song", "Artist", artworkUrl = low)

        var playlistJson: String? = null
        val playlistStore = PlaylistStore(read = { playlistJson }, write = { playlistJson = it })
        playlistStore.save(listOf(Playlist("p1", "List", listOf(track), artworkUrl = low, added = listOf(track))))
        val loadedPlaylist = playlistStore.load().single()
        assertEquals(high, loadedPlaylist.artworkUrl)
        assertEquals(high, loadedPlaylist.tracks.single().artworkUrl)
        assertEquals(high, loadedPlaylist.added.single().artworkUrl)

        var logJson: String? = null
        val logStore = ListeningStore(read = { logJson }, write = { logJson = it })
        logStore.save(
            ListeningLog(
                songs = mapOf(track.key to Listened("Song", "Artist", artworkUrl = low, provider = ProviderId.YOUTUBE_MUSIC, ms = 1000)),
            ),
        )
        assertEquals(high, logStore.load().songs.getValue(track.key).artworkUrl)

        var settingsJson: String? = null
        val settingsStore = SettingsStore(read = { settingsJson }, write = { settingsJson = it })
        settingsStore.save(
            AppSettings(
                favouriteSongs = listOf(track),
                session = SavedSession(queue = listOf(track), index = 0),
            ),
        )
        val loadedSettings = settingsStore.load()
        assertEquals(high, loadedSettings.favouriteSongs.single().artworkUrl)
        assertEquals(high, loadedSettings.session?.queue?.single()?.artworkUrl)
    }
}
