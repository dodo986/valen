/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import app.valen.core.ProviderId
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Persisted service sessions on the desktop (`$XDG_DATA_HOME/valen/sessions.json`).
 *
 * Kept in a private file (`0600` permissions) separate from any browser profile so:
 * - YouTube Music and SoundCloud never need Chromium for sign-in, session reads, or token refresh.
 * - Spotify's imported `sp_dc` survives across runs and is injected into Valen's headless playback
 *   browser without ever signing in inside an embedded or automated window.
 *
 * Secret values from this store must never be logged.
 */
class DesktopSessionStore(
    private val file: Path = defaultFile(),
) {
    @Serializable
    data class StoredSessions(
        val spotifySpDc: String? = null,
        val youtubeCookieHeader: String? = null,
        val soundcloudAccessToken: String? = null,
        val soundcloudRefreshToken: String? = null,
    )

    private val lock = Any()
    private var cached: StoredSessions? = null

    fun spotifySpDc(): String? = synchronized(lock) { loadLocked().spotifySpDc?.takeIf { it.isNotBlank() } }

    fun saveSpotifySpDc(spDc: String?) = synchronized(lock) {
        updateLocked { it.copy(spotifySpDc = spDc?.takeIf { v -> v.isNotBlank() }) }
    }

    fun youtubeCookies(): String? = synchronized(lock) { loadLocked().youtubeCookieHeader?.takeIf { it.isNotBlank() } }

    fun saveYoutubeCookies(header: String?) = synchronized(lock) {
        updateLocked { it.copy(youtubeCookieHeader = header?.takeIf { v -> v.isNotBlank() }) }
    }

    fun soundCloudTokens(): Pair<String, String?>? = synchronized(lock) {
        val current = loadLocked()
        val access = current.soundcloudAccessToken?.takeIf { it.isNotBlank() } ?: return@synchronized null
        access to current.soundcloudRefreshToken?.takeIf { it.isNotBlank() }
    }

    fun saveSoundCloudTokens(accessToken: String?, refreshToken: String?) = synchronized(lock) {
        updateLocked {
            it.copy(
                soundcloudAccessToken = accessToken?.takeIf { v -> v.isNotBlank() },
                soundcloudRefreshToken = refreshToken?.takeIf { v -> v.isNotBlank() },
            )
        }
    }

    fun clear(provider: ProviderId) = synchronized(lock) {
        when (provider) {
            ProviderId.SPOTIFY -> updateLocked { it.copy(spotifySpDc = null) }
            ProviderId.YOUTUBE_MUSIC -> updateLocked { it.copy(youtubeCookieHeader = null) }
            ProviderId.SOUNDCLOUD -> updateLocked { it.copy(soundcloudAccessToken = null, soundcloudRefreshToken = null) }
            ProviderId.LOCAL -> Unit
        }
    }

    private fun loadLocked(): StoredSessions {
        cached?.let { return it }
        val loaded = runCatching {
            if (!Files.exists(file)) StoredSessions()
            else json.decodeFromString<StoredSessions>(Files.readString(file))
        }.getOrDefault(StoredSessions())
        cached = loaded
        return loaded
    }

    private fun updateLocked(transform: (StoredSessions) -> StoredSessions) {
        val next = transform(loadLocked())
        cached = next
        writeLocked(next)
    }

    private fun writeLocked(state: StoredSessions) {
        val parent = file.parent
        if (parent != null) {
            Files.createDirectories(parent)
            runCatching {
                Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))
            }
        }
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(tmp, json.encodeToString(state))
        runCatching {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"))
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        runCatching {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

        fun defaultFile(): Path {
            val data = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/.local/share")
            return Paths.get(data, "valen", "sessions.json")
        }
    }
}
