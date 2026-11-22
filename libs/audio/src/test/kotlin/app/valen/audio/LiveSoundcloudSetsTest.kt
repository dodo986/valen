/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** SoundCloud's real set search and one of its sets read, only with `VALEN_LIVE=1`. */
class LiveSoundcloudSetsTest {

    @Test
    fun `sets by name are found, and one of them reads its songs`() = runBlocking {
        assumeTrue(System.getenv("VALEN_LIVE") == "1")
        val audio = SoundcloudAudio()
        val found = audio.searchPlaylists("ghostland forever", 8)
        println("sets: " + found.map { "${it.name} / ${it.owner} (${it.trackCount})" })
        assertTrue(found.isNotEmpty(), "SoundCloud answered a set search")
        assertTrue(found.any { it.owner != null }, "a set names the account that made it")

        val set = found.first { it.permalink != null }
        val read = audio.playlist(set.permalink!!)
        println("set '${read.name}' by ${read.owner}: ${read.tracks.size} of ${read.trackCount} songs, first=${read.tracks.firstOrNull()?.title}")
        assertTrue(read.tracks.isNotEmpty(), "the set's songs are read")
    }
}