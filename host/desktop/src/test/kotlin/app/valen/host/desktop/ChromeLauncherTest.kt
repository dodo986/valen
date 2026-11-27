/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * The launcher reads the browser's mode from its process, never from `/json/version`: Valen starts
 * a headless engine with an ordinary user agent (the Web Player refuses a headless one), so the
 * endpoint always says "Chrome". Reading it there made a sign-in believe a window was already up,
 * and Connect did nothing at all.
 *
 * A stand-in process is used instead of Chrome: what is under test is how the browser on the port
 * is identified and what a sign-in may do to it, not the browser.
 */
class ChromeLauncherTest {

    @Test
    fun `only a valid target list without pages permits returning to headless mode`() {
        assertEquals(false, hasPageTargets("[]"))
        assertEquals(false, hasPageTargets("""[{"type":"service_worker"}]"""))
        assertEquals(true, hasPageTargets("""[{"type":"page","url":"https://accounts.google.com/"}]"""))
        assertNull(hasPageTargets("not JSON"))
        assertNull(hasPageTargets("{}"))
        assertNull(hasPageTargets("[{}]"))
    }

    @Test
    fun `a headless browser on the port is read as headless`() {
        FakeBrowser(port = 39331, headless = true).use { fake ->
            assertEquals(true, awaitMode(ChromeLauncher(port = fake.port)), "headless")
        }
    }

    @Test
    fun `a browser with a window on the port is read as visible`() {
        FakeBrowser(port = 39332, headless = false).use { fake ->
            assertEquals(false, awaitMode(ChromeLauncher(port = fake.port)), "visible")
        }
    }

    @Test
    fun `a port with no browser behind it says nothing`() = runBlocking {
        assertNull(ChromeLauncher(port = 39333).isHeadless(), "no process")
    }

    @Test
    fun `a sign-in can stop a headless browser it did not start`() {
        FakeBrowser(port = 39334, headless = true).use { fake ->
            // Never start()ed: this is the browser an earlier Valen run left behind.
            val launcher = ChromeLauncher(port = fake.port)
            assertEquals(true, awaitMode(launcher), "headless before")
            assertTrue(runBlocking { launcher.stopBrowser() }, "stopped")
            assertFalse(fake.process.isAlive, "process gone")
            assertNull(runBlocking { launcher.isHeadless() }, "nothing left on the port")
        }
    }

    /**
     * Every process check in the launcher reads the browser's argv, not just its executable:
     * `ProcessHandle.Info.commandLine()` answered with argv[0] alone for this machine's Helium
     * process, so the DevTools port and `--headless` were invisible and a sign-in reported "its
     * window state could not be read". `/proc/<pid>/cmdline` has the whole line.
     */
    @Test
    fun `the full command line is read from proc, not just argv zero`() {
        val exe = listOf("/bin/bash", "/usr/bin/bash", "/bin/sh").map { Paths.get(it) }
            .first { Files.isExecutable(it) }
        val process = ProcessBuilder(
            exe.toString(), "-c", "while :; do sleep 0.2; done",
            "valen-marker-one", "--remote-debugging-port=39999",
        ).redirectErrorStream(true).start()
        try {
            var line: String? = null
            val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5)
            while (System.currentTimeMillis() < deadline) {
                line = ChromeLauncher.procCommandLine(process.pid())
                if (line?.contains("valen-marker-one") == true) break
                Thread.sleep(20)
            }
            assertTrue(line?.contains("valen-marker-one") == true, "arguments were read: $line")
            assertTrue(line?.contains("--remote-debugging-port=39999") == true, "flag was read: $line")
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    /**
     * The user agent is built from the engine's version, and a brand browser's own version is not
     * it. Helium 0.21 printed "Helium 0.21.3.1 (Chromium 157.0.8103.78)"; the old reading took
     * 0.21.3.1, so Chrome announced itself as `Chrome/0.0.0.0` and the Web Player answered
     * "Unsupported browser" instead of loading the player.
     */
    @Test
    fun `a brand browser's version output yields the engine's major version`() {
        assertEquals("157", ChromeLauncher.engineMajor("Helium 0.21.3.1 (Chromium 157.0.8103.78)"))
        assertEquals("157", ChromeLauncher.engineMajor("Google Chrome 157.0.8103.78"))
        assertEquals("128", ChromeLauncher.engineMajor("Chromium 128.0.6613.137"))
        assertEquals("109", ChromeLauncher.engineMajor("Microsoft Edge 109.0.1518.78"))
        assertNull(ChromeLauncher.engineMajor("not a browser"), "no version to read")
    }

    /**
     * The cache holds the *whole* user agent. It used to hold only the half the concatenation
     * ended with, so the first launch announced a normal Chrome and every launch after it
     * announced `Chrome/157.0.0.0 Safari/537.36` with no Mozilla prefix, which the Web Player
     * refuses. A sign-in replaces the browser, so this was every launch after the first one.
     */
    @Test
    fun `the cached user agent is the complete line on every call`() {
        val dir = Files.createTempDirectory("chrome-ua")
        val exe = dir.resolve("chrome")
        try {
            Files.writeString(exe, "#!/bin/sh\necho 'Chromium 157.0.8103.78'\n")
            assertTrue(exe.toFile().setExecutable(true), "the stand-in browser is executable")

            val launcher = ChromeLauncher(port = 39336)
            val first = launcher.userAgent(exe)
            val second = launcher.userAgent(exe)

            assertEquals(
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/157.0.0.0 Safari/537.36",
                first,
                "the agent is complete",
            )
            assertEquals(first, second, "the cached agent is the same complete line, not its tail")
        } finally {
            runCatching { Files.deleteIfExists(exe) }
            runCatching { Files.deleteIfExists(dir) }
        }
    }

    /** Polls [ChromeLauncher.isHeadless] until it answers: a new process takes a moment to appear. */
    private fun awaitMode(launcher: ChromeLauncher): Boolean? {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10)
        var seen: Boolean? = null
        while (System.currentTimeMillis() < deadline) {
            seen = runBlocking { launcher.isHeadless() }
            if (seen != null) return seen
            Thread.sleep(50)
        }
        fail("the launcher never found the browser on port ${launcher.port}")
    }

    /**
     * A process whose command line looks like this launcher's browser: a Chromium-family executable
     * name and the DevTools port. A copy of the shell is used under the name `chrome`, because Java
     * reads argv[0] from the executable, not from what the process calls itself.
     */
    private class FakeBrowser(port: Int, headless: Boolean) : AutoCloseable {
        val port = port
        private val dir: Path = Files.createTempDirectory("chrome-test")
        val process: Process

        init {
            val exe = dir.resolve("chrome")
            Files.copy(shell(), exe)
            process = ProcessBuilder(
                buildList {
                    add(exe.toString())
                    add("-c")
                    add("while :; do sleep 0.2; done")
                    add("--remote-debugging-port=$port")
                    if (headless) add("--headless=new")
                },
            ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        }

        override fun close() {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            runCatching { Files.deleteIfExists(dir.resolve("chrome")) }
            runCatching { Files.deleteIfExists(dir) }
        }

        private companion object {
            fun shell(): Path = listOf("/bin/bash", "/usr/bin/bash", "/bin/sh")
                .map { Paths.get(it) }
                .first { Files.isExecutable(it) }
        }
    }

    @Test
    fun `a browser under its guardian ends when Valen's end of the pipe closes`() {
        // Valen dying (a crash, a kill) closes the guardian's stdin; the browser must go with it.
        val guardian = ProcessBuilder(guarded(listOf("sleep", "300"))).start()
        var child: ProcessHandle? = null
        try {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (child == null && System.nanoTime() < deadline) {
                // Nixpkgs may implement sleep through the coreutils multicall executable.
                // Identify our direct child by its argument, not the resolved executable name
                // (and not the watchdog's short sleep).
                child = guardian.toHandle().children()
                    .filter { it.info().arguments().orElse(emptyArray()).contains("300") }
                    .findFirst().orElse(null)
                if (child == null) Thread.sleep(50)
            }
            org.junit.jupiter.api.Assertions.assertNotNull(child, "the guarded command never started")
            guardian.outputStream.close()
            assertTrue(guardian.waitFor(5, TimeUnit.SECONDS))
            val gone = System.nanoTime() + 3_000_000_000L
            while (child!!.isAlive && System.nanoTime() < gone) Thread.sleep(50)
            assertFalse(child.isAlive, "the browser outlived Valen")
        } finally {
            guardian.outputStream.close()
            guardian.destroy()
            guardian.waitFor(5, TimeUnit.SECONDS)
            child?.takeIf { it.isAlive }?.destroyForcibly()
        }
    }

    @Test
    fun `a guardian ends by itself when its browser exits`() {
        val guardian = ProcessBuilder(guarded(listOf("sleep", "1"))).start()
        org.junit.jupiter.api.Assertions.assertTrue(guardian.waitFor(8, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test
    fun `a snap or Flatpak browser is told apart, and its profile goes where its sandbox can write`() {
        val dir = Files.createTempDirectory("valen-sandbox")
        try {
            val ubuntuWrapper = dir.resolve("chromium-browser").also {
                Files.writeString(it, "#!/bin/sh\n# Ubuntu's transitional package\nexec /snap/bin/chromium \"$@\"\n")
            }
            val flatpakWrapper = dir.resolve("chromium-flatpak").also {
                Files.writeString(it, "#!/bin/sh\nexec flatpak run org.chromium.Chromium \"$@\"\n")
            }
            val plain = dir.resolve("chrome").also { Files.write(it, byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())) }

            assertEquals(ChromeLauncher.Companion.Sandbox.Snap("chromium"), ChromeLauncher.sandboxOf(ubuntuWrapper))
            assertEquals(ChromeLauncher.Companion.Sandbox.Snap("chromium"), ChromeLauncher.sandboxOf(Paths.get("/snap/bin/chromium")))
            assertEquals(ChromeLauncher.Companion.Sandbox.Flatpak("org.chromium.Chromium"), ChromeLauncher.sandboxOf(flatpakWrapper))
            assertEquals(
                ChromeLauncher.Companion.Sandbox.Flatpak("com.google.Chrome"),
                ChromeLauncher.sandboxOf(Paths.get("/var/lib/flatpak/exports/bin/com.google.Chrome")),
            )
            assertNull(ChromeLauncher.sandboxOf(plain))

            if (System.getenv("VALEN_CHROME_PROFILE").isNullOrBlank()) {
                val home = System.getProperty("user.home")
                assertEquals(Paths.get(home, "snap", "chromium", "common", "valen-chrome"), ChromeLauncher.profileFor(ubuntuWrapper))
                assertEquals(Paths.get(home, ".var", "app", "org.chromium.Chromium", "data", "valen-chrome"), ChromeLauncher.profileFor(flatpakWrapper))
                assertEquals(ChromeLauncher.defaultProfileDir(), ChromeLauncher.profileFor(plain))
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `bundledBinary finds Chrome relative to java home when present`() {
        val dir = Files.createTempDirectory("valen-bundled-chrome")
        val oldHome = System.getProperty("java.home")
        try {
            val runtime = dir.resolve("lib/runtime")
            val chrome = dir.resolve("chrome/chrome")
            Files.createDirectories(runtime)
            Files.createDirectories(chrome.parent)
            Files.writeString(chrome, "#!/bin/sh\necho 'Google Chrome 157.0.8103.78'\n")
            assertTrue(chrome.toFile().setExecutable(true))

            if (System.getenv("VALEN_BUNDLED_CHROME").isNullOrBlank()) {
                System.setProperty("java.home", runtime.toString())
                assertEquals(chrome.normalize(), ChromeLauncher.bundledBinary()?.normalize())
            }
        } finally {
            if (oldHome != null) System.setProperty("java.home", oldHome)
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `retries with no-sandbox even when Chrome exited immediately on sandbox failure`() = runBlocking {
        val dir = Files.createTempDirectory("valen-sandbox-retry")
        try {
            val exe = dir.resolve("chrome")
            val profile = dir.resolve("profile")
            val port = 39341
            val python = BrowserCookieReader.findOnPath("python3", System.getenv("PATH").orEmpty())
                ?: return@runBlocking
            // Fake browser: prints "--version" as Chromium, fails immediately with "sandbox" unless
            // --no-sandbox is passed, then serves /json/version on the DevTools port.
            Files.writeString(
                exe,
                """
                #!/bin/sh
                if [ "${'$'}1" = "--version" ]; then
                  echo "Google Chrome 157.0.8103.78"
                  exit 0
                fi
                for a in "${'$'}@"; do
                  if [ "${'$'}a" = "--no-sandbox" ]; then
                    exec $python -c "import http.server, socketserver, sys
                class H(http.server.BaseHTTPRequestHandler):
                    def do_GET(self):
                        self.send_response(200); self.end_headers(); self.wfile.write(b'{}')
                    def log_message(self, *a): pass
                socketserver.TCPServer(('127.0.0.1', $port), H).serve_forever()"
                  fi
                done
                echo "FATAL:sandbox/linux/services/credentials.cc: Check failed" >&2
                exit 1
                """.trimIndent() + "\n",
            )
            assertTrue(exe.toFile().setExecutable(true))

            val launcher = ChromeLauncher(port = port, profile = profile, binary = exe, readyTimeoutMs = 5_000)
            try {
                assertTrue(launcher.start(visible = false), "expected --no-sandbox retry to succeed: ${launcher.lastError}")
            } finally {
                launcher.close()
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
