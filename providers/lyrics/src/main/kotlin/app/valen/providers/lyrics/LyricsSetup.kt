/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.lyrics

import app.valen.core.Lyrics
import app.valen.core.LyricsSource
import app.valen.core.LyricsStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * How Valen finds lyrics on both platforms.
 *
 * Lyprio is a signed service: its address and key belong to whoever runs it and are never in
 * Valen's source. They are read, first found wins, from:
 * 1. `VALEN_LYPRIO_URL` and `VALEN_LYPRIO_KEY` (or Metrolist's own names, `LYPRIO_BASE_URL` and
 *    `LYPRIO_HMAC_KEY`) in the environment;
 * 2. `lyprio.json` in Valen's config folder: `{"url": "https://…", "key": "<base64, 32 bytes>"}`;
 * 3. the build: the same Gradle properties (or `local.properties`) baked into both apps.
 * Without them LiriQo, service lyrics and LRCLIB still work without a Lyprio key.
 */
object LyricsSetup {

    /**
     * Lyprio when set up, with LiriQo behind it for the songs Lyprio cannot time word by word, then
     * music services' own lyrics ([services]: Spotify's card, for a signed-in account), then
     * LRCLIB. Both platforms use this same chain.
     */
    fun sources(http: OkHttpClient, lyprio: LyprioEndpoint?, services: List<LyricsSource> = emptyList()): List<LyricsSource> = buildList {
        val client = http.newBuilder()
            .callTimeout(30, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
        val liriqo = LiriqoLyrics(client)
        if (lyprio != null) add(WordTimedFallback(LyprioLyrics(client, lyprio), liriqo)) else add(liriqo)
        addAll(services)
        add(LrclibLyrics(client))
    }

    /**
     * The folder carries the selection rule: an older answer (or miss) must not bypass the LiriQo
     * word-timing fallback, and an untimed Lyprio answer kept before it existed is exactly what
     * must be looked up again. Previous folders stay untouched.
     */
    fun store(dir: Path): LyricsStore = DiskLyricsStore(dir.resolve("lyprio-timed-v1"))

    /** Lyprio's endpoint from the environment, the config file, or the build; null when none is set up. */
    fun lyprio(
        env: Map<String, String> = System.getenv(),
        configFile: Path? = null,
        builtUrl: String = "",
        builtKey: String = "",
    ): LyprioEndpoint? {
        fun pair(url: String?, key: String?) = if (!url.isNullOrBlank() && !key.isNullOrBlank()) LyprioEndpoint.of(url, key) else null
        pair(env["VALEN_LYPRIO_URL"], env["VALEN_LYPRIO_KEY"])?.let { return it }
        pair(env["LYPRIO_BASE_URL"], env["LYPRIO_HMAC_KEY"])?.let { return it }
        if (configFile != null && Files.isRegularFile(configFile)) {
            val file = runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<LyprioFile>(String(Files.readAllBytes(configFile), Charsets.UTF_8)) }.getOrNull()
            pair(file?.url, file?.key)?.let { return it }
        }
        return pair(builtUrl, builtKey)
    }

    /** Whether something was set up that is not a usable endpoint (to say so once, not silently). */
    fun lyprioMisconfigured(env: Map<String, String> = System.getenv(), configFile: Path? = null): Boolean {
        val named = listOf("VALEN_LYPRIO_URL", "VALEN_LYPRIO_KEY", "LYPRIO_BASE_URL", "LYPRIO_HMAC_KEY").any { !env[it].isNullOrBlank() } ||
            (configFile != null && Files.isRegularFile(configFile))
        return named && lyprio(env, configFile) == null
    }

    @Serializable
    private data class LyprioFile(val url: String? = null, val key: String? = null)
}

/**
 * Lyrics kept in a folder, one small file per recording: what was found, or that nothing was (so a
 * song without words is not asked about at every play). Files are written whole and moved into
 * place; the folder keeps its newest [maxFiles].
 */
class DiskLyricsStore(private val dir: Path, private val maxFiles: Int = 2_000) : LyricsStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var writes = 0

    override fun read(key: String): LyricsStore.Kept? {
        val file = dir.resolve(name(key))
        if (!Files.isRegularFile(file)) return null
        val kept = runCatching { json.decodeFromString<Entry>(String(Files.readAllBytes(file), Charsets.UTF_8)) }.getOrNull() ?: return null
        if (kept.key != key) return null
        return LyricsStore.Kept(kept.lyrics, kept.savedAtMs)
    }

    @Synchronized
    override fun write(key: String, lyrics: Lyrics?) {
        Files.createDirectories(dir)
        val file = dir.resolve(name(key))
        val part = dir.resolve(name(key) + ".part")
        // Java 8 file calls only: Android has no Files.writeString before API 33.
        Files.write(part, json.encodeToString(Entry.serializer(), Entry(key, System.currentTimeMillis(), lyrics)).toByteArray(Charsets.UTF_8))
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        if (++writes % 50 == 0) trim()
    }

    private fun trim() = runCatching {
        Files.list(dir).use { files ->
            val all = files.filter { it.toString().endsWith(".json") }.collect(java.util.stream.Collectors.toList())
            if (all.size <= maxFiles) return@use
            all.sortedBy { Files.getLastModifiedTime(it).toMillis() }.take(all.size - maxFiles).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun name(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(32) + ".json"

    @Serializable
    private data class Entry(val key: String, val savedAtMs: Long, val lyrics: Lyrics? = null)
}
