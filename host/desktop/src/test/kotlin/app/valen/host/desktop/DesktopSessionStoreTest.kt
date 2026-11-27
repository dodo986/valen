/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import app.valen.core.ProviderId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class DesktopSessionStoreTest {

    @Test
    fun `stores, reloads, and clears all three provider sessions with private file permissions`() {
        val dir = Files.createTempDirectory("valen-sessions")
        try {
            val file = dir.resolve("sessions.json")
            val store = DesktopSessionStore(file)
            assertNull(store.spotifySpDc())
            assertNull(store.youtubeCookies())
            assertNull(store.soundCloudTokens())

            store.saveSpotifySpDc("sp-dc-secret-value")
            store.saveYoutubeCookies("SAPISID=abc; SID=def")
            store.saveSoundCloudTokens("sc-access-jwt", "sc-refresh-token")

            val reloaded = DesktopSessionStore(file)
            assertEquals("sp-dc-secret-value", reloaded.spotifySpDc())
            assertEquals("SAPISID=abc; SID=def", reloaded.youtubeCookies())
            assertEquals("sc-access-jwt" to "sc-refresh-token", reloaded.soundCloudTokens())

            val perms = PosixFilePermissions.toString(Files.getPosixFilePermissions(file))
            assertEquals("rw-------", perms, "session file must be readable/writable by owner only")

            reloaded.clear(ProviderId.SOUNDCLOUD)
            assertNull(reloaded.soundCloudTokens())
            assertEquals("sp-dc-secret-value", reloaded.spotifySpDc())

            reloaded.clear(ProviderId.SPOTIFY)
            reloaded.clear(ProviderId.YOUTUBE_MUSIC)
            val afterClear = DesktopSessionStore(file)
            assertNull(afterClear.spotifySpDc())
            assertNull(afterClear.youtubeCookies())
            assertFalse(Files.readString(file).contains("sp-dc-secret-value"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `blank values are normalized to null`() {
        val dir = Files.createTempDirectory("valen-sessions-blank")
        try {
            val store = DesktopSessionStore(dir.resolve("sessions.json"))
            store.saveSpotifySpDc("   ")
            store.saveYoutubeCookies("")
            store.saveSoundCloudTokens("", "refresh")
            assertNull(store.spotifySpDc())
            assertNull(store.youtubeCookies())
            assertNull(store.soundCloudTokens())
            assertTrue(Files.exists(dir.resolve("sessions.json")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
