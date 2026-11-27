/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import app.valen.core.ProviderId
import app.valen.host.cdp.cookieDomainMatches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reads service session cookies from the user's real Linux desktop browsers (Firefox/Gecko
 * profiles and Chromium-family profiles), Netscape `cookies.txt` exports, or pasted cookie strings.
 *
 * Nothing here opens or embeds a login page; browser SQLite databases are always copied into a
 * private temporary directory before reading so live browsers are never locked or modified.
 * Cookie values are never logged.
 */
class BrowserCookieReader(
    private val home: Path = Paths.get(System.getProperty("user.home")),
    private val configHome: Path = Paths.get(
        System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            ?: (System.getProperty("user.home") + "/.config"),
    ),
    private val pathEnv: String = System.getenv("PATH").orEmpty(),
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    private val secretLookup: (String) -> String? = ::lookupLinuxSecret,
    private val discoveredSecretApps: () -> List<String> = ::discoverChromiumSecretApps,
) {
    data class CookieEntry(
        val domain: String,
        val name: String,
        val value: String,
    )

    sealed interface ImportedSession {
        data class Spotify(val spDc: String) : ImportedSession
        data class YoutubeMusic(val cookieHeader: String) : ImportedSession
        data class SoundCloud(val accessToken: String, val refreshToken: String?) : ImportedSession
    }

    data class ScanOutcome(
        val session: ImportedSession? = null,
        val checkedProfiles: List<String> = emptyList(),
        val decryptionFailedProfiles: List<String> = emptyList(),
    )

    data class BrowserProfile(
        val label: String,
        val kind: Kind,
        val cookieDb: Path,
        val appNames: List<String> = emptyList(),
    ) {
        enum class Kind { GECKO, CHROMIUM }
    }

    /** True when at least one readable Gecko or Chromium cookie store exists on this machine. */
    fun hasReadableBrowser(): Boolean = discoverProfiles().isNotEmpty()

    /** Searches installed browsers (newest-modified cookie stores first) for [provider]'s session. */
    suspend fun readFromBrowsers(provider: ProviderId): ImportedSession? =
        scanBrowsers(provider).session

    /** Searches installed browsers and returns non-secret diagnostics alongside any matched session. */
    suspend fun scanBrowsers(provider: ProviderId): ScanOutcome = withContext(Dispatchers.IO) {
        val profiles = discoverProfiles()
        val checked = ArrayList<String>(profiles.size)
        val decryptFailed = ArrayList<String>()
        val diag = System.getenv("VALEN_COOKIE_LOG") != null
        for (profile in profiles) {
            checked += profile.label
            val outcome = readProfile(profile)
            if (diag) {
                System.err.println(
                    "valen: cookie scan [${profile.label}] rows=${outcome.cookies.size} decryptFailed=${outcome.decryptionFailed}",
                )
            }
            if (outcome.decryptionFailed) decryptFailed += profile.label
            val parsed = extractSession(provider, outcome.cookies)
            if (parsed != null) {
                return@withContext ScanOutcome(parsed, checked, decryptFailed)
            }
        }
        ScanOutcome(null, checked.distinct(), decryptFailed.distinct())
    }

    /** Parses a `cookies.txt` (Netscape) or Cookie-Editor JSON file at [filePath] for [provider]. */
    suspend fun readFromFile(provider: ProviderId, filePath: Path): ImportedSession? = withContext(Dispatchers.IO) {
        val text = runCatching { Files.readString(filePath, StandardCharsets.UTF_8) }.getOrNull() ?: return@withContext null
        parseExported(provider, text)
    }

    /** Parses pasted text (Netscape `cookies.txt`, JSON array, `Cookie:` header, or bare token). */
    fun parseInput(provider: ProviderId, raw: String): ImportedSession? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        parseExported(provider, text)?.let { return it }
        if ('=' in text) {
            val pairs = text.removePrefix("Cookie:").removePrefix("cookie:").trim()
                .split(';')
                .mapNotNull { part ->
                    val eq = part.indexOf('=')
                    if (eq <= 0) null
                    else {
                        val k = part.substring(0, eq).trim()
                        val v = part.substring(eq + 1).trim()
                        if (k.isNotEmpty() && v.isNotEmpty()) k to v else null
                    }
                }
            if (pairs.isNotEmpty()) {
                val syntheticDomain = defaultDomain(provider)
                val entries = pairs.map { (k, v) -> CookieEntry(syntheticDomain, k, v) }
                extractSession(provider, entries)?.let { return it }
            }
        }
        val bare = text.lineSequence().firstOrNull()?.trim().orEmpty()
        if (bare.isNotBlank() && !bare.contains(' ') && !bare.contains(';')) {
            return when (provider) {
                ProviderId.SPOTIFY -> ImportedSession.Spotify(bare)
                ProviderId.SOUNDCLOUD -> ImportedSession.SoundCloud(bare, null)
                ProviderId.YOUTUBE_MUSIC, ProviderId.LOCAL -> null
            }
        }
        return null
    }

    internal fun parseExported(provider: ProviderId, text: String): ImportedSession? {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) {
            val jsonCookies = parseJsonCookies(trimmed)
            if (jsonCookies.isNotEmpty()) return extractSession(provider, jsonCookies)
        }
        val netscape = parseNetscapeCookies(trimmed)
        if (netscape.isNotEmpty()) return extractSession(provider, netscape)
        return null
    }

    internal fun parseNetscapeCookies(text: String): List<CookieEntry> {
        val now = nowSeconds()
        val out = ArrayList<CookieEntry>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val clean = if (line.startsWith("#HttpOnly_")) line.removePrefix("#HttpOnly_")
            else if (line.startsWith("#")) continue
            else line
            val cols = clean.split('\t')
            if (cols.size < 7) continue
            val domain = cols[0].trim()
            val expiry = cols[4].trim().toLongOrNull() ?: 0L
            val name = cols[5].trim()
            val value = cols[6].trim()
            if (domain.isEmpty() || name.isEmpty() || value.isEmpty()) continue
            if (expiry in 1 until now) continue
            out += CookieEntry(domain, name, value)
        }
        return out
    }

    internal fun parseJsonCookies(text: String): List<CookieEntry> {
        val now = nowSeconds().toDouble()
        val array = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
        val out = ArrayList<CookieEntry>(array.size)
        for (el in array) {
            val obj = runCatching { el.jsonObject }.getOrNull() ?: continue
            val domain = obj["domain"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val value = obj["value"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val exp = obj["expirationDate"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            if (domain.isEmpty() || name.isEmpty() || value.isEmpty()) continue
            if (exp != null && exp > 0.0 && exp < now) continue
            out += CookieEntry(domain, name, value)
        }
        return out
    }

    internal fun extractSession(provider: ProviderId, cookies: List<CookieEntry>): ImportedSession? = when (provider) {
        ProviderId.SPOTIFY -> {
            val matching = cookies.filter { cookieDomainMatches(it.domain, "open.spotify.com") }
            val spDc = matching.firstOrNull { it.name == "sp_dc" }?.value?.takeIf { it.isNotBlank() }
                ?: return null
            ImportedSession.Spotify(spDc)
        }
        ProviderId.YOUTUBE_MUSIC -> {
            val matching = cookies.filter { cookieDomainMatches(it.domain, "music.youtube.com") }
            val hasAuth = matching.any { it.name == "SAPISID" || it.name == "__Secure-3PAPISID" }
            if (!hasAuth) return null
            val deduped = LinkedHashMap<String, String>()
            for (c in matching.sortedBy { if (it.domain.startsWith('.')) 0 else 1 }) {
                if (c.value.isNotBlank()) deduped[c.name] = c.value
            }
            val header = deduped.entries.joinToString("; ") { "${it.key}=${it.value}" }
            if (header.isBlank()) null else ImportedSession.YoutubeMusic(header)
        }
        ProviderId.SOUNDCLOUD -> {
            val matching = cookies.filter { cookieDomainMatches(it.domain, "soundcloud.com") }
            val access = matching.firstOrNull { it.name == "oauth_token" }?.value?.takeIf { it.isNotBlank() }
                ?: return null
            val refresh = matching.firstOrNull { it.name == "oauth_refresh_token" }?.value?.takeIf { it.isNotBlank() }
            ImportedSession.SoundCloud(access, refresh)
        }
        ProviderId.LOCAL -> null
    }

    internal fun discoverProfiles(): List<BrowserProfile> {
        val found = ArrayList<BrowserProfile>()
        val geckoRoots = listOf(
            "Firefox" to home.resolve(".mozilla/firefox"),
            "Firefox (Flatpak)" to home.resolve(".var/app/org.mozilla.firefox/.mozilla/firefox"),
            "Firefox (Snap)" to home.resolve("snap/firefox/common/.mozilla/firefox"),
            "Zen" to home.resolve(".zen"),
            "Zen" to configHome.resolve("zen"),
            "Zen (Flatpak)" to home.resolve(".var/app/app.zen_browser.zen/.zen"),
            "Zen (Flatpak)" to home.resolve(".var/app/app.zen_browser.zen/.config/zen"),
            "LibreWolf" to home.resolve(".librewolf"),
            "LibreWolf (Flatpak)" to home.resolve(".var/app/io.gitlab.librewolf-community/.librewolf"),
            "Floorp" to home.resolve(".floorp"),
            "Floorp (Flatpak)" to home.resolve(".var/app/one.ablaze.floorp/.floorp"),
            "Waterfox" to home.resolve(".waterfox"),
            "Mullvad Browser" to home.resolve(".mullvad/mullvadbrowser"),
        )
        for ((label, root) in geckoRoots) {
            if (!Files.isDirectory(root)) continue
            for (dir in geckoProfileDirs(root)) {
                val db = dir.resolve("cookies.sqlite")
                if (Files.isRegularFile(db)) found += BrowserProfile(label, BrowserProfile.Kind.GECKO, db)
            }
        }
        val chromiumRoots = listOf(
            Triple("Helium", configHome.resolve("net.imput.helium"), listOf("helium", "net.imput.helium", "chromium", "chrome")),
            Triple("Helium", configHome.resolve("helium"), listOf("helium", "net.imput.helium", "chromium", "chrome")),
            Triple("Helium (Flatpak)", home.resolve(".var/app/net.imput.helium/config/net.imput.helium"), listOf("helium", "net.imput.helium", "chromium", "chrome")),
            Triple("Chrome", configHome.resolve("google-chrome"), listOf("chrome", "chromium")),
            Triple("Chrome Beta", configHome.resolve("google-chrome-beta"), listOf("chrome", "chromium")),
            Triple("Chrome Unstable", configHome.resolve("google-chrome-unstable"), listOf("chrome", "chromium")),
            Triple("Chromium", configHome.resolve("chromium"), listOf("chromium", "chrome")),
            Triple("Ungoogled Chromium", configHome.resolve("ungoogled-chromium"), listOf("chromium", "chrome")),
            Triple("Brave", configHome.resolve("BraveSoftware/Brave-Browser"), listOf("brave", "chromium", "chrome")),
            Triple("Vivaldi", configHome.resolve("vivaldi"), listOf("vivaldi", "chromium", "chrome")),
            Triple("Edge", configHome.resolve("microsoft-edge"), listOf("microsoft-edge", "chromium", "chrome")),
            Triple("Opera", configHome.resolve("opera"), listOf("opera", "chromium", "chrome")),
            Triple("Thorium", configHome.resolve("thorium"), listOf("thorium", "chromium", "chrome")),
            Triple("Chrome (Flatpak)", home.resolve(".var/app/com.google.Chrome/config/google-chrome"), listOf("chrome", "chromium")),
            Triple("Chromium (Flatpak)", home.resolve(".var/app/org.chromium.Chromium/config/chromium"), listOf("chromium", "chrome")),
            Triple("Brave (Flatpak)", home.resolve(".var/app/com.brave.Browser/config/BraveSoftware/Brave-Browser"), listOf("brave", "chromium", "chrome")),
            Triple("Chromium (Snap)", home.resolve("snap/chromium/common/chromium"), listOf("chromium", "chrome")),
        )
        for ((label, root, apps) in chromiumRoots) {
            if (!Files.isDirectory(root)) continue
            val dirs = runCatching { Files.list(root).use { s -> s.toList() } }.getOrDefault(emptyList())
                .filter { Files.isDirectory(it) }
            for (dir in dirs) {
                for (rel in listOf("Cookies", "Network/Cookies")) {
                    val db = dir.resolve(rel)
                    if (Files.isRegularFile(db)) {
                        found += BrowserProfile(label, BrowserProfile.Kind.CHROMIUM, db, apps)
                    }
                }
            }
        }
        return found.distinctBy { it.cookieDb.toAbsolutePath().normalize() }
            .sortedByDescending { latestMtime(it.cookieDb) }
    }

    private fun latestMtime(db: Path): Long {
        val candidates = listOf(db, db.resolveSibling("${db.fileName}-wal"), db.resolveSibling("${db.fileName}-journal"))
        return candidates.maxOfOrNull { p ->
            runCatching { if (Files.exists(p)) Files.getLastModifiedTime(p).toMillis() else 0L }.getOrDefault(0L)
        } ?: 0L
    }

    private fun geckoProfileDirs(root: Path): List<Path> {
        val dirs = LinkedHashSet<Path>()
        val ini = root.resolve("profiles.ini")
        if (Files.isRegularFile(ini)) {
            val text = runCatching { Files.readString(ini) }.getOrDefault("")
            var currentPath: String? = null
            var currentRelative = true
            var isDefault = false
            val installDefaults = HashSet<String>()
            val prioritized = ArrayList<Pair<Boolean, Path>>()
            fun flush() {
                val p = currentPath ?: return
                val resolved = if (currentRelative) root.resolve(p) else Paths.get(p)
                if (Files.isDirectory(resolved)) prioritized += (isDefault || p in installDefaults) to resolved
                currentPath = null
                currentRelative = true
                isDefault = false
            }
            var inInstall = false
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith("[")) {
                    flush()
                    inInstall = line.startsWith("[Install", ignoreCase = true)
                    continue
                }
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                if (inInstall && key == "Default") {
                    installDefaults.add(value)
                } else {
                    when (key) {
                        "Path" -> currentPath = value
                        "IsRelative" -> currentRelative = value != "0"
                        "Default" -> if (value == "1") isDefault = true
                    }
                }
            }
            flush()
            prioritized.sortedByDescending { it.first }.forEach { dirs.add(it.second) }
        }
        runCatching {
            Files.list(root).use { s ->
                s.filter { Files.isDirectory(it) && Files.isRegularFile(it.resolve("cookies.sqlite")) }
                    .forEach { dirs.add(it) }
            }
        }
        return dirs.toList()
    }

    internal data class ProfileReadOutcome(
        val cookies: List<CookieEntry>,
        val decryptionFailed: Boolean = false,
    )

    internal fun readProfile(profile: BrowserProfile): ProfileReadOutcome {
        val tmpDir = runCatching { Files.createTempDirectory("valen-ck-") }.getOrNull() ?: return ProfileReadOutcome(emptyList())
        return try {
            val copied = tmpDir.resolve(profile.cookieDb.fileName)
            Files.copy(profile.cookieDb, copied, StandardCopyOption.REPLACE_EXISTING)
            for (suffix in listOf("-wal", "-shm", "-journal")) {
                val side = profile.cookieDb.resolveSibling("${profile.cookieDb.fileName}$suffix")
                if (Files.isRegularFile(side)) {
                    runCatching { Files.copy(side, copied.resolveSibling("${copied.fileName}$suffix"), StandardCopyOption.REPLACE_EXISTING) }
                }
            }
            when (profile.kind) {
                BrowserProfile.Kind.GECKO -> ProfileReadOutcome(readGeckoDb(copied))
                BrowserProfile.Kind.CHROMIUM -> readChromiumDb(copied, profile.appNames)
            }
        } catch (_: Exception) {
            ProfileReadOutcome(emptyList())
        } finally {
            runCatching {
                Files.walk(tmpDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun readGeckoDb(db: Path): List<CookieEntry> {
        val now = nowSeconds()
        val sql = "SELECT hex(host), hex(name), hex(value) FROM moz_cookies " +
            "WHERE (expiry = 0 OR expiry >= $now) AND (" +
            "host LIKE '%spotify.com' OR host LIKE '%youtube.com' OR host LIKE '%soundcloud.com');"
        return queryHexRows(db, sql).mapNotNull { cols ->
            if (cols.size < 3) null
            else {
                val host = hexToUtf8(cols[0])
                val name = hexToUtf8(cols[1])
                val value = hexToUtf8(cols[2])
                if (host.isNotEmpty() && name.isNotEmpty() && value.isNotEmpty()) CookieEntry(host, name, value) else null
            }
        }
    }

    private fun readChromiumDb(db: Path, preferredApps: List<String>): ProfileReadOutcome {
        val versionRow = queryHexRows(db, "SELECT hex(value) FROM meta WHERE key='version';").firstOrNull()?.firstOrNull()
        val dbVersion = versionRow?.let { hexToUtf8(it).toIntOrNull() } ?: 0
        val nowChromeUs = (nowSeconds() + 11644473600L) * 1_000_000L
        val sql = "SELECT hex(host_key), hex(name), hex(value), hex(encrypted_value) FROM cookies " +
            "WHERE (expires_utc = 0 OR expires_utc >= $nowChromeUs) AND (" +
            "host_key LIKE '%spotify.com' OR host_key LIKE '%youtube.com' OR host_key LIKE '%soundcloud.com');"
        val rows = queryHexRows(db, sql)
        if (rows.isEmpty()) return ProfileReadOutcome(emptyList())

        var loadedSecrets: List<String>? = null
        var winningSecret: String? = null
        var anyDecryptFailed = false
        val out = ArrayList<CookieEntry>(rows.size)
        for (cols in rows) {
            if (cols.size < 4) continue
            val host = hexToUtf8(cols[0])
            val name = hexToUtf8(cols[1])
            val plain = hexToUtf8(cols[2])
            val value = if (plain.isNotEmpty()) {
                plain
            } else {
                val enc = hexToBytes(cols[3])
                if (enc.isEmpty()) ""
                else {
                    val prefix = if (enc.size > 3) String(enc, 0, 3, StandardCharsets.US_ASCII) else ""
                    if (prefix == "v10") {
                        decryptChromiumValue(enc, host, dbVersion) { null }.orEmpty()
                    } else if (prefix == "v11") {
                        val firstTry = winningSecret?.let { s ->
                            decryptChromiumValue(enc, host, dbVersion) { s }
                        }
                        if (firstTry != null) {
                            firstTry
                        } else {
                            if (loadedSecrets == null) {
                                loadedSecrets = collectCandidateSecrets(preferredApps)
                            }
                            var found: String? = null
                            for (candidate in loadedSecrets) {
                                val decrypted = decryptChromiumValue(enc, host, dbVersion) { candidate }
                                if (decrypted != null) {
                                    winningSecret = candidate
                                    found = decrypted
                                    break
                                }
                            }
                            if (found == null) anyDecryptFailed = true
                            found.orEmpty()
                        }
                    } else {
                        anyDecryptFailed = true
                        ""
                    }
                }
            }
            if (host.isNotEmpty() && name.isNotEmpty() && value.isNotEmpty()) {
                out += CookieEntry(host, name, value)
            }
        }
        return ProfileReadOutcome(out, decryptionFailed = anyDecryptFailed && out.isEmpty())
    }

    private fun collectCandidateSecrets(preferredApps: List<String>): List<String> {
        val candidates = LinkedHashSet<String>()
        val appOrder = LinkedHashSet<String>()
        appOrder.addAll(preferredApps)
        appOrder.addAll(discoveredSecretApps())
        appOrder.addAll(listOf("chromium", "chrome", "net.imput.helium", "helium", "brave", "vivaldi", "microsoft-edge"))
        for (app in appOrder) {
            secretLookup(app)?.takeIf { it.isNotEmpty() }?.let { candidates.add(it) }
        }
        // Fallback: Chromium builds with basic store or empty keyring entry sometimes use peanuts.
        candidates.add("peanuts")
        return candidates.toList()
    }

    internal fun queryHexRows(db: Path, sql: String): List<List<String>> {
        val sqlite = findOnPath("sqlite3", pathEnv)
        if (sqlite != null) {
            val out = runExternal(listOf(sqlite.toString(), "-readonly", "-separator", "\t", db.toString(), sql))
            if (out != null) return parseTsv(out)
        }
        val python = findOnPath("python3", pathEnv)
        if (python != null) {
            val script = """
                import sqlite3, sys
                con = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)
                for row in con.execute(sys.argv[2]):
                    print("\t".join("" if c is None else str(c) for c in row))
            """.trimIndent()
            val out = runExternal(listOf(python.toString(), "-c", script, db.toString(), sql))
            if (out != null) return parseTsv(out)
        }
        return emptyList()
    }

    private fun parseTsv(output: String): List<List<String>> =
        output.lineSequence()
            .filter { it.isNotBlank() }
            .map { it.split('\t') }
            .toList()

    private fun runExternal(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .also { cleanAppImageEnv(it.environment()) }
            .start()
        try {
            val text = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            if (!process.waitFor(5, TimeUnit.SECONDS)) return@runCatching null
            if (process.exitValue() == 0) text else null
        } finally {
            process.destroyForcibly()
        }
    }.getOrNull()

    companion object {
        private fun defaultDomain(provider: ProviderId): String = when (provider) {
            ProviderId.SPOTIFY -> ".spotify.com"
            ProviderId.YOUTUBE_MUSIC -> ".youtube.com"
            ProviderId.SOUNDCLOUD -> ".soundcloud.com"
            ProviderId.LOCAL -> ""
        }

        internal fun decryptChromiumValue(
            encrypted: ByteArray,
            hostKey: String,
            dbVersion: Int,
            secretForV11: () -> String?,
        ): String? {
            if (encrypted.size <= 3) return null
            val prefix = String(encrypted, 0, 3, StandardCharsets.US_ASCII)
            val password = when (prefix) {
                "v10" -> "peanuts"
                "v11" -> secretForV11()?.takeIf { it.isNotEmpty() } ?: return null
                else -> return null
            }
            return runCatching {
                val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(PBEKeySpec(password.toCharArray(), "saltysalt".toByteArray(StandardCharsets.US_ASCII), 1, 128))
                    .encoded
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ByteArray(16) { 0x20 }))
                val plain = cipher.doFinal(encrypted, 3, encrypted.size - 3)
                val hostDigest = MessageDigest.getInstance("SHA-256").digest(hostKey.toByteArray(StandardCharsets.UTF_8))
                val hasDomainPrefix = plain.size > 32 && plain.copyOfRange(0, 32).contentEquals(hostDigest)
                val payload = if (hasDomainPrefix) plain.copyOfRange(32, plain.size) else plain
                // Verify the key decrypted cleanly: if dbVersion >= 24 without a matching host hash,
                // only accept strictly printable ASCII (rejects random bytes from a wrong key).
                if (dbVersion >= 24 && !hasDomainPrefix && payload.any { (it.toInt() and 0xFF) !in 0x20..0x7E }) {
                    return@runCatching null
                }
                val text = String(payload, StandardCharsets.UTF_8)
                if (text.any { it.code < 0x20 && it != '\t' }) return@runCatching null
                text
            }.getOrNull()
        }

        internal fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim()
            if (clean.length % 2 != 0) return ByteArray(0)
            return ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        internal fun hexToUtf8(hex: String): String =
            String(hexToBytes(hex), StandardCharsets.UTF_8)

        internal fun lookupLinuxSecret(application: String): String? {
            if (application.isBlank()) return null
            val secretTool = findOnPath("secret-tool", System.getenv("PATH").orEmpty()) ?: return null
            return runCatching {
                val process = ProcessBuilder(secretTool.toString(), "lookup", "application", application)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .also { cleanAppImageEnv(it.environment()) }
                    .start()
                try {
                    val text = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText().trimEnd('\r', '\n')
                    if (!process.waitFor(2, TimeUnit.SECONDS)) return@runCatching null
                    if (process.exitValue() == 0 && text.isNotEmpty()) text else null
                } finally {
                    process.destroyForcibly()
                }
            }.getOrNull()
        }

        internal fun discoverChromiumSecretApps(): List<String> {
            val secretTool = findOnPath("secret-tool", System.getenv("PATH").orEmpty()) ?: return emptyList()
            val found = LinkedHashSet<String>()
            for (schema in listOf("chrome_libsecret_os_crypt_password_v2", "chrome_libsecret_os_crypt_password")) {
                runCatching {
                    val process = ProcessBuilder(
                        secretTool.toString(), "search", "--all", "xdg:schema", schema,
                    )
                        .redirectErrorStream(true)
                        .also { cleanAppImageEnv(it.environment()) }
                        .start()
                    try {
                        val text = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
                        if (process.waitFor(2, TimeUnit.SECONDS)) {
                            text.lineSequence().forEach { line ->
                                val trimmed = line.trim()
                                if (trimmed.startsWith("attribute.application =")) {
                                    trimmed.substringAfter('=').trim().takeIf { it.isNotEmpty() }?.let { found.add(it) }
                                }
                            }
                        }
                    } finally {
                        process.destroyForcibly()
                    }
                }
            }
            return found.toList()
        }

        internal fun findOnPath(name: String, pathEnv: String): Path? =
            pathEnv.split(':').asSequence()
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { Paths.get(it, name) }.getOrNull() }
                .firstOrNull { Files.isExecutable(it) }

        /** Remove AppImage loader overrides so system tools load system shared libraries. */
        internal fun cleanAppImageEnv(env: MutableMap<String, String>) {
            env.remove("LD_LIBRARY_PATH")
            env.remove("LD_PRELOAD")
        }
    }
}
