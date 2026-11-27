/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import app.valen.core.ProviderId
import app.valen.core.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Timeout(15)
class BrowserCookieReaderTest {

    @Test
    fun `parses Netscape cookies txt including HttpOnly rows and filters expired entries`() {
        val reader = BrowserCookieReader(nowSeconds = { 1_700_000_000L })
        val txt = """
            # Netscape HTTP Cookie File
            #HttpOnly_.spotify.com	TRUE	/	TRUE	1800000000	sp_dc	AQ_fresh_token
            .spotify.com	TRUE	/	TRUE	1600000000	sp_dc	AQ_expired
            #HttpOnly_.youtube.com	TRUE	/	TRUE	1800000000	SAPISID	yt_sapisid
            .youtube.com	TRUE	/	TRUE	1800000000	SID	yt_sid
            .soundcloud.com	TRUE	/	TRUE	0	oauth_token	sc_access
            .soundcloud.com	TRUE	/	TRUE	0	oauth_refresh_token	sc_refresh
        """.trimIndent()

        val sp = reader.parseExported(ProviderId.SPOTIFY, txt) as BrowserCookieReader.ImportedSession.Spotify
        assertEquals("AQ_fresh_token", sp.spDc)

        val yt = reader.parseExported(ProviderId.YOUTUBE_MUSIC, txt) as BrowserCookieReader.ImportedSession.YoutubeMusic
        assertTrue("SAPISID=yt_sapisid" in yt.cookieHeader)
        assertTrue("SID=yt_sid" in yt.cookieHeader)

        val sc = reader.parseExported(ProviderId.SOUNDCLOUD, txt) as BrowserCookieReader.ImportedSession.SoundCloud
        assertEquals("sc_access", sc.accessToken)
        assertEquals("sc_refresh", sc.refreshToken)
    }

    @Test
    fun `parses JSON export, Cookie header, and bare tokens`() {
        val reader = BrowserCookieReader(nowSeconds = { 1_700_000_000L })
        val json = """
            [
              {"domain": ".spotify.com", "name": "sp_dc", "value": "AQ_json", "expirationDate": 1800000000},
              {"domain": ".youtube.com", "name": "__Secure-3PAPISID", "value": "papisid_val"}
            ]
        """.trimIndent()
        assertEquals("AQ_json", (reader.parseInput(ProviderId.SPOTIFY, json) as BrowserCookieReader.ImportedSession.Spotify).spDc)
        assertEquals("__Secure-3PAPISID=papisid_val", (reader.parseInput(ProviderId.YOUTUBE_MUSIC, json) as BrowserCookieReader.ImportedSession.YoutubeMusic).cookieHeader)

        val header = "Cookie: foo=bar; sp_dc=AQ_pasted; sp_t=ignored"
        assertEquals("AQ_pasted", (reader.parseInput(ProviderId.SPOTIFY, header) as BrowserCookieReader.ImportedSession.Spotify).spDc)
        assertEquals("AQ_bare", (reader.parseInput(ProviderId.SPOTIFY, "AQ_bare") as BrowserCookieReader.ImportedSession.Spotify).spDc)
        assertEquals("sc_bare", (reader.parseInput(ProviderId.SOUNDCLOUD, "sc_bare") as BrowserCookieReader.ImportedSession.SoundCloud).accessToken)
        assertNull(reader.parseInput(ProviderId.YOUTUBE_MUSIC, "bare_without_equals"))
    }

    @Test
    fun `decrypts Linux Chromium v10, v11, and v24 domain-prefixed cookies`() {
        val host = ".spotify.com"
        val v10 = encryptChromium("v10", "peanuts", "AQ_v10", host, dbVersion = 20)
        assertEquals("AQ_v10", BrowserCookieReader.decryptChromiumValue(v10, host, dbVersion = 20) { null })

        val v11 = encryptChromium("v11", "keyring-secret", "AQ_v11_v24", host, dbVersion = 24)
        assertEquals("AQ_v11_v24", BrowserCookieReader.decryptChromiumValue(v11, host, dbVersion = 24) { "keyring-secret" })
        assertNull(BrowserCookieReader.decryptChromiumValue(v11, host, dbVersion = 24) { "wrong-secret" })
        assertNull(BrowserCookieReader.decryptChromiumValue(v11, host, dbVersion = 24) { null })
    }

    @Test
    fun `reads Helium net-imput-helium v11 cookies using chromium keyring secret`() = runBlocking {
        val sqlite = BrowserCookieReader.findOnPath("sqlite3", System.getenv("PATH").orEmpty())
            ?: return@runBlocking
        val root = Files.createTempDirectory("valen-helium-test")
        try {
            val heliumProfile = root.resolve("config/net.imput.helium/Default")
            Files.createDirectories(heliumProfile)
            val db = heliumProfile.resolve("Cookies")
            val encrypted = encryptChromium("v11", "helium-keyring-pass", "AQ_helium_sp_dc", ".spotify.com", dbVersion = 24)
            val hexEnc = encrypted.joinToString("") { "%02x".format(it) }
            execSql(
                sqlite, db,
                """
                PRAGMA journal_mode=WAL;
                CREATE TABLE meta (key LONGVARCHAR NOT NULL UNIQUE PRIMARY KEY, value LONGVARCHAR);
                INSERT INTO meta (key, value) VALUES ('version', '24');
                CREATE TABLE cookies (
                    host_key TEXT NOT NULL,
                    name TEXT NOT NULL,
                    value TEXT NOT NULL,
                    encrypted_value BLOB NOT NULL,
                    expires_utc INTEGER NOT NULL
                );
                INSERT INTO cookies (host_key, name, value, encrypted_value, expires_utc)
                VALUES ('.spotify.com', 'sp_dc', '', X'$hexEnc', 0);
                """.trimIndent(),
            )
            // Keyring stores the password under "chromium", not "helium": reader must still decrypt it.
            val reader = BrowserCookieReader(
                home = root.resolve("home"),
                configHome = root.resolve("config"),
                nowSeconds = { 1_700_000_000L },
                secretLookup = { app -> if (app == "chromium") "helium-keyring-pass" else null },
                discoveredSecretApps = { listOf("chromium") },
            )
            val scan = reader.scanBrowsers(ProviderId.SPOTIFY)
            assertEquals(listOf("Helium"), scan.checkedProfiles)
            val imported = scan.session as BrowserCookieReader.ImportedSession.Spotify
            assertEquals("AQ_helium_sp_dc", imported.spDc)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `reads Firefox WAL cookies sqlite and drives external sign-in without Chromium`() = runBlocking {
        val sqlite = BrowserCookieReader.findOnPath("sqlite3", System.getenv("PATH").orEmpty())
            ?: return@runBlocking // Skip DB round-trip only when sqlite3 isn't installed on build host.
        val root = Files.createTempDirectory("valen-ff-test")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val ffRoot = root.resolve("home/.mozilla/firefox")
            val profileDir = ffRoot.resolve("abc123.default-release")
            Files.createDirectories(profileDir)
            Files.writeString(
                ffRoot.resolve("profiles.ini"),
                """
                [Profile0]
                Name=default-release
                IsRelative=1
                Path=abc123.default-release
                Default=1
                """.trimIndent(),
            )
            val db = profileDir.resolve("cookies.sqlite")
            execSql(
                sqlite, db,
                """
                PRAGMA journal_mode=WAL;
                CREATE TABLE moz_cookies (
                    id INTEGER PRIMARY KEY,
                    name TEXT,
                    value TEXT,
                    host TEXT,
                    path TEXT,
                    expiry INTEGER,
                    isSecure INTEGER,
                    isHttpOnly INTEGER
                );
                INSERT INTO moz_cookies (name, value, host, path, expiry, isSecure, isHttpOnly)
                VALUES ('SAPISID', 'ff_sapisid', '.youtube.com', '/', 1900000000, 1, 0),
                       ('oauth_token', 'ff_sc_jwt', '.soundcloud.com', '/', 1900000000, 1, 1);
                """.trimIndent(),
            )

            val reader = BrowserCookieReader(
                home = root.resolve("home"),
                configHome = root.resolve("config"),
                nowSeconds = { 1_700_000_000L },
            )
            val store = DesktopSessionStore(root.resolve("sessions.json"))
            val openedUrls = ArrayList<String>()
            val external = ExternalSignIn(
                sessions = store,
                cookies = reader,
                scope = scope,
                openBrowser = { url -> openedUrls += url; true },
                pollMs = 100L,
            )

            val tabs = NoChromeTabs()
            val ytSignIn = YtMusicSignIn(tabs, scope, external = external)
            val scSignIn = SoundCloudSignIn(tabs, scope, external = external)
            val spSignIn = SpotifySignIn(tabs, scope, external = external)

            // YTM & SoundCloud import from Firefox immediately with zero Chrome involvement.
            ytSignIn.connect()
            scSignIn.connect()
            assertEquals(SessionState.Connected(), ytSignIn.state.value)
            assertEquals(SessionState.Connected(), scSignIn.state.value)
            assertEquals("SAPISID=ff_sapisid", store.youtubeCookies())
            assertEquals("ff_sc_jwt" to null, store.soundCloudTokens())

            // Spotify is not signed in yet: opens external browser via xdg-open hook and polls.
            spSignIn.connect()
            assertEquals(SessionState.Connecting, spSignIn.state.value)
            assertEquals(listOf("https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"), openedUrls)
            assertNotNull(external.prompt.value)

            // Simulate user finishing Spotify login in Firefox: row lands in cookies.sqlite.
            execSql(
                sqlite, db,
                "INSERT INTO moz_cookies (name, value, host, path, expiry, isSecure, isHttpOnly) " +
                    "VALUES ('sp_dc', 'ff_sp_dc_value', '.spotify.com', '/', 1900000000, 1, 1);",
            )
            val deadline = System.currentTimeMillis() + 5_000L
            while (spSignIn.state.value !is SessionState.Connected && System.currentTimeMillis() < deadline) {
                delay(50)
            }
            assertTrue(spSignIn.state.value is SessionState.Connected, "expected Connected, got ${spSignIn.state.value}")
            assertEquals("ff_sp_dc_value", store.spotifySpDc())
            assertNull(external.prompt.value)
            assertEquals(0, tabs.visibleRequests, "must never open a Chromium login window")
        } finally {
            scope.cancel()
            root.toFile().deleteRecursively()
        }
    }

    private fun execSql(sqlite: Path, db: Path, sql: String) {
        val process = ProcessBuilder(sqlite.toString(), db.toString(), sql)
            .redirectErrorStream(true)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0, "sqlite3 failed: $out")
    }

    private fun encryptChromium(
        prefix: String,
        password: String,
        value: String,
        hostKey: String,
        dbVersion: Int,
    ): ByteArray {
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            .generateSecret(PBEKeySpec(password.toCharArray(), "saltysalt".toByteArray(StandardCharsets.US_ASCII), 1, 128))
            .encoded
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ByteArray(16) { 0x20 }))
        val rawValue = value.toByteArray(StandardCharsets.UTF_8)
        val plain = if (dbVersion >= 24) {
            MessageDigest.getInstance("SHA-256").digest(hostKey.toByteArray(StandardCharsets.UTF_8)) + rawValue
        } else {
            rawValue
        }
        return prefix.toByteArray(StandardCharsets.US_ASCII) + cipher.doFinal(plain)
    }

    private class NoChromeTabs : SignInTabs {
        var visibleRequests = 0
        override suspend fun ensureVisible(): Boolean { visibleRequests++; return false }
        override suspend fun ensureBackground(): Boolean = false
        override suspend fun cookieNames(url: String): Set<String> = emptySet()
        override val windowError: String = "No Chrome in this test"
        override val browserStarting: Boolean = false
        override suspend fun spotify() = error("no Chrome")
        override suspend fun youtubeMusic() = error("no Chrome")
        override suspend fun soundCloud() = error("no Chrome")
    }
}
