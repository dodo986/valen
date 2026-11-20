/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ListOrderTest {
    private fun song(id: String, title: String, artist: String, album: String? = null, ms: Long = 0) =
        Track(ProviderId.YOUTUBE_MUSIC, id, title, artist, album = album, durationMs = ms)

    private val list = listOf(
        song("1", "Halo", "Beyoncé", "I Am... Sasha Fierce", 261_000),
        song("2", "alright", "Kendrick Lamar", "To Pimp a Butterfly", 219_000),
        song("3", "Crazy in Love", "Beyoncé", "Dangerously in Love", 236_000),
        song("4", "Untitled", "Nobody", null, 0),
        song("5", "Halo", "Beyoncé", "I Am... Sasha Fierce", 261_000),
    )

    private fun ids(arranged: List<IndexedValue<Track>>) = arranged.map { it.value.id }

    @Test
    fun `the list's own order, and reversed it is the newest first`() {
        assertEquals(listOf("1", "2", "3", "4", "5"), ids(arrangeSongs(list, ListOrder.Own)))
        assertEquals(listOf("5", "4", "3", "2", "1"), ids(arrangeSongs(list, ListOrder.Own, reversed = true)))
    }

    @Test
    fun `names sort without caring about case, and ties keep the list's order`() {
        assertEquals(listOf("2", "3", "1", "5", "4"), ids(arrangeSongs(list, ListOrder.Title)))
        assertEquals(listOf("4", "5", "1", "3", "2"), ids(arrangeSongs(list, ListOrder.Title, reversed = true)))
        // By artist, then by album within the artist.
        assertEquals(listOf("3", "1", "5", "2", "4"), ids(arrangeSongs(list, ListOrder.Artist)))
        // A song with no album goes last.
        assertEquals(listOf("3", "1", "5", "2", "4"), ids(arrangeSongs(list, ListOrder.Album)))
    }

    @Test
    fun `length sorts both ways with an unknown length always last`() {
        assertEquals(listOf("2", "3", "1", "5", "4"), ids(arrangeSongs(list, ListOrder.Length)))
        assertEquals(listOf("1", "5", "3", "2", "4"), ids(arrangeSongs(list, ListOrder.Length, reversed = true)))
    }

    @Test
    fun `find keeps songs with every word typed, across title, artist and album, accents aside`() {
        assertEquals(listOf("1", "3", "5"), ids(arrangeSongs(list, ListOrder.Own, find = "beyonce")))
        assertEquals(listOf("3"), ids(arrangeSongs(list, ListOrder.Own, find = "  LOVE   crazy ")))
        assertEquals(listOf("2"), ids(arrangeSongs(list, ListOrder.Own, find = "butterfly")))
        assertEquals(emptyList<String>(), ids(arrangeSongs(list, ListOrder.Own, find = "nothing like this")))
        // Two copies of a song keep their own places, so each is still its own row.
        assertEquals(listOf(0, 4), arrangeSongs(list, ListOrder.Title, find = "halo").map { it.index })
    }

    @Test
    fun `each order's chip says which way it runs once chosen`() {
        assertEquals("Playlist order", ListOrder.Own.label(chosen = true, reversed = false))
        assertEquals("Reversed order", ListOrder.Own.label(chosen = true, reversed = true))
        assertEquals("Title", ListOrder.Title.label(chosen = false, reversed = false))
        assertEquals("Title A–Z", ListOrder.Title.label(chosen = true, reversed = false))
        assertEquals("Artist Z–A", ListOrder.Artist.label(chosen = true, reversed = true))
        assertEquals("Longest first", ListOrder.Length.label(chosen = true, reversed = true))
    }
}
