/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui

import androidx.compose.ui.input.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class KeysTest {
    @Test
    fun `the transport is on Space and the arrows, the songs on Ctrl and the arrows`() {
        assertEquals(Shortcut.PlayPause, shortcutOf(Press(Key.Spacebar, ' ')))
        assertEquals(Shortcut.SeekForward, shortcutOf(Press(Key.DirectionRight)))
        assertEquals(Shortcut.SeekBack, shortcutOf(Press(Key.DirectionLeft)))
        assertEquals(Shortcut.Next, shortcutOf(Press(Key.DirectionRight, ctrl = true)))
        assertEquals(Shortcut.Previous, shortcutOf(Press(Key.DirectionLeft, ctrl = true)))
        assertEquals(Shortcut.VolumeUp, shortcutOf(Press(Key.DirectionUp, ctrl = true)))
        assertEquals(Shortcut.VolumeDown, shortcutOf(Press(Key.DirectionDown, ctrl = true)))
        assertEquals(Shortcut.Search, shortcutOf(Press(Key.F, ctrl = true)))
        // The plain up and down arrows are the lists' own.
        assertNull(shortcutOf(Press(Key.DirectionUp)))
    }

    @Test
    fun `letters are read by what they type, so they sit where the layout puts them`() {
        // An AZERTY keyboard types "m" on the key a US one calls the semicolon.
        assertEquals(Shortcut.Mute, shortcutOf(Press(Key.Semicolon, 'm')))
        assertEquals(Shortcut.Shuffle, shortcutOf(Press(Key.S, 'S', shift = true)))
        assertEquals(Shortcut.Help, shortcutOf(Press(Key.Slash, '?', shift = true)))
        assertEquals(Shortcut.Help, shortcutOf(Press(Key.Slash, shift = true)))
        assertEquals(Shortcut.Search, shortcutOf(Press(Key.Slash, '/')))
        assertEquals(Shortcut.Stage, shortcutOf(Press(Key.N, 'n')))
        assertEquals(Shortcut.Queue, shortcutOf(Press(Key.Q, 'q')))
        assertNull(shortcutOf(Press(Key.X, 'x')))
    }

    @Test
    fun `alt and super are the desktop's, and a field with the caret keeps every key but Esc and the media keys`() {
        assertNull(shortcutOf(Press(Key.DirectionRight, alt = true)))
        assertNull(shortcutOf(Press(Key.Spacebar, ' ', meta = true)))
        assertNull(shortcutOf(Press(Key.Spacebar, ' '), typing = true))
        assertNull(shortcutOf(Press(Key.M, 'm'), typing = true))
        assertNull(shortcutOf(Press(Key.DirectionRight, ctrl = true), typing = true))
        assertEquals(Shortcut.Back, shortcutOf(Press(Key.Escape), typing = true))
        assertEquals(Shortcut.PlayPause, shortcutOf(Press(Key.MediaPlayPause), typing = true))
        assertEquals(Shortcut.Next, shortcutOf(Press(Key.MediaNext), typing = true))
        assertNotEquals(Key.MediaPlayPause, Key.MediaNext)
    }

    @Test
    fun `a held key repeats only where repeating means something`() {
        var now = 0L
        val keys = KeyShortcuts(clock = { now })
        val done = mutableListOf<Shortcut>()
        keys.perform = { done += it; true }
        keys.press(Press(Key.Spacebar, ' '))
        now += 40
        keys.press(Press(Key.Spacebar, ' '))
        now += 40
        keys.press(Press(Key.DirectionRight))
        now += 40
        keys.press(Press(Key.DirectionRight))
        // Pressed again well after: a fresh press, even with its release never seen.
        now += 2_000
        keys.press(Press(Key.Spacebar, ' '))
        assertEquals(listOf(Shortcut.PlayPause, Shortcut.SeekForward, Shortcut.SeekForward, Shortcut.PlayPause), done)
    }
}
