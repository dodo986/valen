/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.serialization.json.Json

/**
 * Persistence is given to us by the platform (a file on both): core stays free of java.io so the
 * same module serves Android and desktop.
 */
class SettingsStore(
    private val read: () -> String?,
    private val write: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun load(): AppSettings = runCatching {
        read()?.let { json.decodeFromString<AppSettings>(it) }?.let { s ->
            s.copy(
                favouriteSongs = s.favouriteSongs.map(Track::withLargestArtwork),
                session = s.session?.let { saved ->
                    saved.copy(
                        queue = saved.queue.map(Track::withLargestArtwork),
                        unshuffled = saved.unshuffled?.map(Track::withLargestArtwork),
                    )
                },
            )
        } ?: AppSettings()
    }.getOrDefault(AppSettings())

    fun save(settings: AppSettings) {
        runCatching { write(json.encodeToString(settings)) }
    }
}
