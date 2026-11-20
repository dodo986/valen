/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.desktop

import app.valen.core.Lyrics
import app.valen.core.LyricsBook
import app.valen.core.LyricsFormats
import app.valen.core.LyricsQuery
import app.valen.core.LyricsCatalog
import app.valen.core.LyricsSource
import app.valen.core.MusicProvider
import app.valen.core.ServiceLyrics
import app.valen.providers.lyrics.LyricsSetup
import okhttp3.OkHttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The desktop's lyrics: LiriQo, then Lyprio when set up (the environment, `lyprio.json` beside
 * settings.json, or the build's own endpoint), then Spotify's own for a signed-in account, then
 * LRCLIB, kept under `$XDG_CACHE_HOME/valen/lyrics`.
 *
 * Development: `VALEN_CAPTURE_LYRICS=<file>` answers every song with that file's words (TTML,
 * MLX, LRC or plain text, read by its extension) ahead of the real sources, and keeps nothing,
 * so a screenshot can show word-timed lyrics without a Lyprio key.
 */
internal fun desktopLyrics(providers: List<MusicProvider>): LyricsBook {
    val home = System.getProperty("user.home")
    val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: Paths.get(home, ".config")
    val cache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: Paths.get(home, ".cache")
    val setup = config.resolve("valen").resolve("lyprio.json")
    if (LyricsSetup.lyprioMisconfigured(configFile = setup)) {
        System.err.println("valen: Lyprio is named but its address or key is not usable (https, and a base64 key of 32 bytes); keeping the other lyrics sources")
    }
    // Spotify's own lyrics for a signed-in account, between Lyprio and LRCLIB.
    val services = providers.filter { it is LyricsCatalog }.map { ServiceLyrics(it.id.label, it as LyricsCatalog) }
    val sources = LyricsSetup.sources(
        OkHttpClient(),
        LyricsSetup.lyprio(configFile = setup, builtUrl = LyprioBuild.URL, builtKey = LyprioBuild.KEY),
        services,
    )
    val fixture = System.getenv("VALEN_CAPTURE_LYRICS")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
    if (fixture != null) return LyricsBook(listOf(FileLyrics(fixture)) + sources)
    return LyricsBook(sources, LyricsSetup.store(cache.resolve("valen").resolve("lyrics")))
}

/** One file's words for every song: the development fixture for screenshots. */
private class FileLyrics(private val file: Path) : LyricsSource {
    override val name = "a file"
    override suspend fun find(query: LyricsQuery): Lyrics? {
        if (!Files.isRegularFile(file)) return null
        val format = when (file.fileName.toString().substringAfterLast('.').lowercase()) {
            "ttml", "xml" -> "ttml"
            "mlx", "json" -> "mlx"
            "lrc" -> "lrc"
            else -> "plain"
        }
        return LyricsFormats.parse(Files.readString(file), format, "a file", query.durationMs)
    }
}
