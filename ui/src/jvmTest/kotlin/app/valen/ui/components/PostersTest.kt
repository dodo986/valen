/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

import app.valen.core.ProviderId
import app.valen.core.Track
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** When a stage poster is lifted off (going back) rather than a new one slapped on. */
class PostersTest {
    private fun song(id: String) = Track(provider = ProviderId.SOUNDCLOUD, id = id, title = id, artist = "a")

    private val queue = listOf(song("1"), song("2"), song("3"))

    @Test
    fun previousInTheSameQueueGoesBack() {
        assertTrue(wentBack(before = queue[2].key, beforeIndex = 2, key = queue[1].key, index = 1, queue = queue))
    }

    @Test
    fun nextIsNotBack() {
        assertFalse(wentBack(before = queue[1].key, beforeIndex = 1, key = queue[2].key, index = 2, queue = queue))
    }

    @Test
    fun aNewQueueIsNotBackEvenAtAnEarlierPlace() {
        val other = listOf(song("9"), song("8"), song("7"))
        assertFalse(wentBack(before = queue[2].key, beforeIndex = 2, key = other[0].key, index = 0, queue = other))
    }

    @Test
    fun theFirstSongShownIsNotBack() {
        assertFalse(wentBack(before = null, beforeIndex = 0, key = queue[0].key, index = 0, queue = queue))
    }

    @Test
    fun theSameSongIsNotBack() {
        assertFalse(wentBack(before = queue[1].key, beforeIndex = 1, key = queue[1].key, index = 1, queue = queue))
    }
}
