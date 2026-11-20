/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import java.text.Normalizer

/** How a playlist's songs are shown: in the list's own order, or sorted by a name or the length. */
enum class ListOrder {
    Own,
    Title,
    Artist,
    Album,
    Length,
    ;

    /** What the order's chip says, chosen ([reversed] or not) or not chosen at all. */
    fun label(chosen: Boolean, reversed: Boolean): String = when (this) {
        Own -> if (chosen && reversed) "Reversed order" else "Playlist order"
        Length -> when {
            !chosen -> "Length"
            reversed -> "Longest first"
            else -> "Shortest first"
        }
        else -> {
            val name = name
            when {
                !chosen -> name
                reversed -> "$name Z–A"
                else -> "$name A–Z"
            }
        }
    }
}

/**
 * The songs of a list as they are shown. [find] keeps those whose title, artist or album holds
 * every word typed, whatever their case or accents; [order] sorts them, and [reversed] turns the
 * sort round (the list's own order reversed is its newest additions first). Each song keeps its
 * place in the list ([IndexedValue.index]): two copies of one song are told apart by it, and a
 * sort's ties keep the list's own order.
 */
fun arrangeSongs(
    tracks: List<Track>,
    order: ListOrder,
    reversed: Boolean = false,
    find: String = "",
): List<IndexedValue<Track>> {
    val words = fold(find).split(' ').filter { it.isNotEmpty() }
    val kept = tracks.withIndex().filter { (_, t) ->
        if (words.isEmpty()) return@filter true
        val text = fold("${t.title} ${t.artist} ${t.album.orEmpty()}")
        words.all { it in text }
    }
    val sorted = when (order) {
        ListOrder.Own -> kept
        ListOrder.Title -> kept.sortedWith(compareBy({ fold(it.value.title) }, { it.index }))
        ListOrder.Artist -> kept.sortedWith(compareBy({ fold(it.value.artist) }, { fold(it.value.album.orEmpty()) }, { it.index }))
        ListOrder.Album -> kept.sortedWith(compareBy({ it.value.album.isNullOrBlank() }, { fold(it.value.album.orEmpty()) }, { it.index }))
        // A song whose length is not known goes last, either way round.
        ListOrder.Length -> kept.sortedWith(compareBy({ it.value.durationMs <= 0 }, { it.value.durationMs }, { it.index }))
    }
    if (!reversed) return sorted
    return if (order == ListOrder.Length) {
        sorted.sortedWith(compareBy({ it.value.durationMs <= 0 }, { -it.value.durationMs }, { it.index }))
    } else {
        sorted.asReversed()
    }
}

/** Lower case, accents taken off, every run of spaces one: "Beyoncé" and "beyonce" are one word. */
private fun fold(text: String): String =
    Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Marks, "")
        .replace(Spaces, " ")
        .trim()

private val Marks = Regex("\\p{M}+")
private val Spaces = Regex("\\s+")
