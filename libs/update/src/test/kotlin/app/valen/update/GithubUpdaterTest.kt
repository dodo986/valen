/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.update

import app.valen.core.AppRelease
import app.valen.core.UpdatePrefs
import app.valen.core.UpdateRoute
import app.valen.core.UpdateState
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The updater against a local server standing in for GitHub's API and its release files. */
class GithubUpdaterTest {
    @TempDir lateinit var dir: File

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient()
    private val requests = CopyOnWriteArrayList<String>()

    /** The release list GitHub answers; each test sets it. */
    @Volatile private var releases = "[]"
    @Volatile private var etag = "\"one\""
    @Volatile private var rateLimited = false
    private val files = HashMap<String, ByteArray>()

    /** Cut the next download of a file after this many bytes (the connection drops). */
    @Volatile private var cutAfter = -1
    private val ranges = AtomicInteger()

    @BeforeEach
    fun up() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> answer(ex) }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @AfterEach
    fun down() {
        server.stop(0)
        scope.cancel()
    }

    private fun answer(ex: HttpExchange) {
        val path = ex.requestURI.path
        requests += "${ex.requestMethod} $path ${ex.requestHeaders.getFirst("If-None-Match") ?: ""}".trim()
        when {
            path.endsWith("/releases") -> {
                if (rateLimited) {
                    ex.responseHeaders.add("x-ratelimit-reset", ((System.currentTimeMillis() / 1000) + 3600).toString())
                    ex.sendResponseHeaders(403, -1)
                } else if (ex.requestHeaders.getFirst("If-None-Match") == etag) {
                    ex.sendResponseHeaders(304, -1)
                } else {
                    ex.responseHeaders.add("ETag", etag)
                    send(ex, 200, releases.toByteArray())
                }
            }
            path.startsWith("/files/") -> {
                val bytes = files[path.removePrefix("/files/")]
                if (bytes == null) {
                    ex.sendResponseHeaders(404, -1)
                } else {
                    val range = ex.requestHeaders.getFirst("Range")
                    val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                    if (range != null) ranges.incrementAndGet()
                    val rest = bytes.copyOfRange(from, bytes.size)
                    val cut = cutAfter
                    if (cut in 0 until rest.size) {
                        cutAfter = -1
                        ex.sendResponseHeaders(if (range != null) 206 else 200, rest.size.toLong())
                        ex.responseBody.write(rest, 0, cut)
                        ex.responseBody.flush()
                        ex.close() // the connection drops part way
                        return
                    }
                    send(ex, if (range != null) 206 else 200, rest)
                }
            }
            else -> ex.sendResponseHeaders(404, -1)
        }
        ex.close()
    }

    private fun send(ex: HttpExchange, code: Int, bytes: ByteArray) {
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Publishes a release the way the workflow does: the file, the manifest, the release entry. */
    private fun release(version: String, code: Int, prerelease: Boolean = false, bytes: ByteArray = ByteArray(300_000) { (it % 251).toByte() }, lie: Boolean = false): String {
        val name = "Valen-$version-x86_64.AppImage"
        files[name] = bytes
        val manifest = """{"version":"$version","code":$code,"assets":[{"platform":"linux-appimage","name":"$name","size":${bytes.size},"sha256":"${if (lie) "0".repeat(64) else sha(bytes)}"}]}"""
        files["$version.json"] = manifest.toByteArray()
        return """{"tag_name":"v$version","draft":false,"prerelease":$prerelease,"html_url":"https://example.invalid/$version",
            "assets":[{"name":"$name","size":${bytes.size},"browser_download_url":"$base/files/$name"},
                      {"name":"valen-release.json","size":${manifest.length},"browser_download_url":"$base/files/$version.json"}]}"""
    }

    private class FakeInstaller(
        override val route: UpdateRoute = UpdateRoute.Itself,
        /** What the installer does while it is being handed the file (it may answer at once). */
        private val during: (() -> Unit)? = null,
    ) : UpdateInstaller {
        override val platform: String? = "linux-appimage"
        val installed = CopyOnWriteArrayList<Pair<File, AppRelease>>()
        override suspend fun install(file: File, release: AppRelease): UpdateInstaller.Outcome {
            installed += file to release
            during?.invoke()
            return if (route == UpdateRoute.Itself) UpdateInstaller.Outcome.InPlace else UpdateInstaller.Outcome.HandedOver
        }
    }

    private fun updater(current: String, installer: UpdateInstaller, prefs: UpdatePrefs = UpdatePrefs()) =
        GithubUpdater(current, "o/valen", installer, http, scope, File(dir, "state"), api = base, firstLookMs = 0, everyMs = 60_000)
            .also { it.start(flowOf(prefs)) }

    private fun GithubUpdater.settled(): UpdateState = runBlocking {
        withTimeout(15_000) {
            state.first { it is UpdateState.UpToDate || it is UpdateState.Installed || it is UpdateState.Failed || it is UpdateState.Ready || it is UpdateState.Available }
        }
    }

    @Test
    fun `a newer release is downloaded, checked and put in place by itself`() {
        releases = "[${release("1.0.0-alpha.3", 10_000_103)}, ${release("1.0.0-alpha.1", 10_000_101)}]"
        val installer = FakeInstaller()
        val updater = updater("1.0.0-alpha.2", installer)
        val state = updater.settled()
        assertTrue(state is UpdateState.Installed, "state: $state")
        state as UpdateState.Installed
        assertEquals("1.0.0-alpha.3", state.release.version)
        assertEquals(1, installer.installed.size)
        val (file, _) = installer.installed.single()
        assertEquals(sha(files["Valen-1.0.0-alpha.3-x86_64.AppImage"]!!), Sha256.of(file))
    }

    @Test
    fun `a second look sends the ETag and reads the kept answer`() {
        releases = "[${release("1.0.0-alpha.1", 10_000_101)}]"
        val updater = updater("1.0.0-alpha.2", FakeInstaller())
        assertTrue(updater.settled() is UpdateState.UpToDate)
        updater.check()
        runBlocking { withTimeout(10_000) { while (requests.none { it.endsWith("\"one\"") }) kotlinx.coroutines.delay(20) } }
        assertTrue(updater.settled() is UpdateState.UpToDate)
        assertTrue(requests.any { it == "GET /repos/o/valen/releases \"one\"" }, "requests: $requests")
    }

    @Test
    fun `a release build is not offered test builds unless asked`() {
        releases = "[${release("1.1.0-beta.1", 10_100_201, prerelease = true)}]"
        assertTrue(updater("1.0.0", FakeInstaller()).settled() is UpdateState.UpToDate)
    }

    @Test
    fun `a test build takes test builds`() {
        releases = "[${release("1.1.0-beta.1", 10_100_201, prerelease = true)}]"
        val state = updater("1.0.0-alpha.2", FakeInstaller()).settled()
        assertTrue(state is UpdateState.Installed, "state: $state")
    }

    @Test
    fun `a file that is not the manifest's is thrown away, never installed`() {
        releases = "[${release("1.0.0-alpha.3", 10_000_103, lie = true)}]"
        val installer = FakeInstaller()
        val state = updater("1.0.0-alpha.2", installer).settled()
        assertTrue(state is UpdateState.Failed, "state: $state")
        assertTrue(installer.installed.isEmpty())
        assertFalse(File(dir, "state/downloads/Valen-1.0.0-alpha.3-x86_64.AppImage").exists())
    }

    @Test
    fun `a manifest whose number is not the tag's is passed over`() {
        releases = "[${release("1.0.0-alpha.3", 12_345)}]"
        assertTrue(updater("1.0.0-alpha.2", FakeInstaller()).settled() is UpdateState.UpToDate)
    }

    @Test
    fun `tags that are not versions are passed over`() {
        releases = """[{"tag_name":"ALPHA-1","draft":false,"prerelease":true,"html_url":"x","assets":[]}]"""
        assertTrue(updater("1.0.0-alpha.2", FakeInstaller()).settled() is UpdateState.UpToDate)
    }

    @Test
    fun `a cut download continues from where it stopped`() {
        releases = "[${release("1.0.0-alpha.3", 10_000_103)}]"
        cutAfter = 100_000
        val installer = FakeInstaller()
        val state = updater("1.0.0-alpha.2", installer).settled()
        assertTrue(state is UpdateState.Installed, "state: $state")
        assertTrue(ranges.get() >= 1, "no ranged request after the cut")
        assertEquals(sha(files["Valen-1.0.0-alpha.3-x86_64.AppImage"]!!), Sha256.of(installer.installed.single().first))
    }

    @Test
    fun `a system installer waits for the press, and a cancel leaves it ready`() {
        releases = "[${release("1.0.0-alpha.3", 10_000_103)}]"
        val installer = FakeInstaller(UpdateRoute.System)
        val updater = updater("1.0.0-alpha.2", installer)
        val state = updater.settled()
        assertTrue(state is UpdateState.Ready, "state: $state")
        assertTrue(installer.installed.isEmpty())
        updater.update()
        // Wait until the file really is with the system's installer, not just until the state says
        // "installing" (the state is set before the hand-over, which was the race here).
        runBlocking { withTimeout(10_000) { while (installer.installed.isEmpty()) delay(10) } }
        assertEquals(1, installer.installed.size)
        updater.installerSaid(InstallerReport.Cancelled)
        assertTrue(updater.state.value is UpdateState.Ready, "state: ${updater.state.value}")
    }

    @Test
    fun `an installer that answers while it is handed the file does not put the state back to installing`() {
        releases = "[${release("1.0.0-alpha.3", 10_000_103)}]"
        lateinit var updater: GithubUpdater
        // The system's installer can report a Cancel before install() returns: that answer is newer
        // than the hand-over and must not be overwritten by it.
        val installer = FakeInstaller(UpdateRoute.System) { updater.installerSaid(InstallerReport.Cancelled) }
        updater = updater("1.0.0-alpha.2", installer)
        assertTrue(updater.settled() is UpdateState.Ready)
        updater.update()
        runBlocking { withTimeout(10_000) { while (installer.installed.isEmpty()) delay(10) } }
        // Let the hand-over's own steps run out, then the sheet must still say Ready.
        Thread.sleep(200)
        assertTrue(updater.state.value is UpdateState.Ready, "state: ${updater.state.value}")
    }

    @Test
    fun `GitHub's rate limit is said and respected`() {
        rateLimited = true
        val updater = updater("1.0.0-alpha.2", FakeInstaller())
        val state = updater.settled()
        assertTrue(state is UpdateState.Failed && state.reason.contains("wait"), "state: $state")
        val memory = File(dir, "state/updates.json").readText()
        assertTrue(memory.contains("waitUntil"), memory)
    }

    @Test
    fun `switched off, it does not look`() {
        releases = "[${release("1.0.0-alpha.3", 10_000_103)}]"
        val updater = updater("1.0.0-alpha.2", FakeInstaller(), UpdatePrefs(check = false))
        runBlocking { withTimeout(5_000) { updater.state.first { it == UpdateState.Off } } }
        Thread.sleep(300)
        assertTrue(requests.isEmpty(), "requests: $requests")
    }
}
