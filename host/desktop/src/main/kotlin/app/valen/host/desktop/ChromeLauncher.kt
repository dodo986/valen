/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import app.valen.host.cdp.CdpBrowser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * One Chrome, launched by Valen itself with a private profile, for the CDP hosts of this host.
 *
 * The profile lives in `$XDG_DATA_HOME/valen/chrome` (default `~/.local/share/valen/chrome`), so
 * the user's sign-ins survive restarts and never touch the browser they use for everything else.
 * A Chrome already listening on [port] is reused as it is (the user may have started it, or a
 * previous run left it open); only a Chrome this class started is stopped by [close]. The
 * exceptions are switching to a visible sign-in, or returning to headless mode once every page
 * of a visible browser has been closed. The private profile is reused in either direction.
 *
 * It runs **headless** unless asked otherwise: Spotify's Web Player needs a browser engine, not a
 * window, and a headless engine still opens a real audio stream (verified against PipeWire), so
 * music plays with nothing on screen. A sign-in is the one thing a person has to do in a window,
 * hence [start] takes `visible`; `VALEN_CHROME_VISIBLE=1` keeps every launch visible.
 */
class ChromeLauncher(
    val port: Int = DEFAULT_PORT,
    /** A fixed profile folder; null: [profileFor] the browser in use. */
    private val profile: Path? = null,
    val startUrl: String = "https://open.spotify.com",
    /** Re-discovered on a later [start] when the first search found nothing. */
    var binary: Path? = findBinary(),
    /** Waits this long for `/json/version` after launching before giving up. */
    private val readyTimeoutMs: Long = 30_000,
) : AutoCloseable {

    /**
     * The browser's profile: `$XDG_DATA_HOME/valen/chrome` ([VALEN_CHROME_PROFILE] overrides), or a
     * folder inside a sandboxed browser's own when that is the only browser here (see [profileFor]).
     */
    val profileDir: Path get() = profile ?: profileFor(binary)

    /** How many browsers this launcher has launched, so a caller notices a replacement. */
    @Volatile var launchCount: Int = 0
        private set

    /** Whether the browser this launcher uses has no window. */
    @Volatile var headless: Boolean = System.getenv("VALEN_CHROME_VISIBLE") == null
        private set

    /** The DevTools endpoint, the url the CDP hosts attach to. */
    val cdpUrl: String get() = "http://127.0.0.1:$port"

    @Volatile private var process: Process? = null

    /**
     * True while [start] is bringing a browser up. A browser that is being started is not a dead
     * browser: without this, a sign-in's restart looks like a crash to anything polling the port
     * and Spotify is reported closed in the middle of a login.
     */
    @Volatile var starting: Boolean = false
        private set

    /** One browser per profile and port: two callers must never launch (or replace) at once. */
    private val startLock = Mutex()

    /** Whether this launcher is talking to a Chrome it started itself. */
    val startedHere: Boolean get() = process != null

    /**
     * Starts Chrome with the private profile, or reuses the one already listening on [port].
     * Returns true when the browser Valen needs is up afterwards; never throws for a missing Chrome
     * (the reason is in [lastError]). With `visible = true` the browser must have a window: a
     * headless one is replaced, and a browser whose state cannot be read is a false with a reason.
     *
     * Calls are serialised: a play that reaches for the browser while a sign-in is replacing it
     * waits for that replacement instead of launching a second Chrome on the same profile (which
     * would hand its command line to the first and exit, leaving the caller with no browser).
     */
    suspend fun start(visible: Boolean = !headless): Boolean = startLock.withLock { startLocked(visible) }

    private suspend fun startLocked(visible: Boolean): Boolean {
        starting = true
        try {
            return launchLocked(visible)
        } finally {
            starting = false
        }
    }

    private suspend fun launchLocked(visible: Boolean): Boolean {
        val wantHeadless = !visible
        if (probe()) {
            // Keep every window the user is still using. Some Chrome builds remain alive after
            // the last window closes; creating a player tab there would reopen a visible window.
            // Only a positively empty page list allows a silent switch back to headless mode.
            val actual = isHeadless()
            if (actual != null) headless = actual
            val returnToBackground = !visible && actual == false && hasOpenPages() == false
            if ((visible && actual == true) || returnToBackground) {
                // A headless browser is replaced by a windowed one even when this launcher did not
                // start it: an earlier Valen run that was killed never called close(), and a
                // person cannot log in to a page they cannot see. Silence here was the bug - a
                // Connect that did nothing looked like a dead button.
                if (!stopBrowserLocked()) {
                    lastError = "Could not replace Chrome on $cdpUrl: quit it and try again"
                    return false
                }
            } else if (visible && actual == null) {
                // Something answers on the port but no browser process owns it: a window cannot be
                // promised, and guessing would either kill the wrong thing or do nothing again.
                lastError = "Chrome answers on $cdpUrl but its window state could not be read: quit that browser and try again"
                return false
            } else {
                lastError = null
                return true
            }
        }
        val exe = binary ?: findBinary()?.also { binary = it } ?: run {
            lastError = "Chrome not found: install Chrome or Chromium, or set VALEN_CHROME_BIN"
            return false
        }
        if (!Files.isExecutable(exe)) { lastError = "Chrome is not executable: $exe"; return false }
        runCatching { Files.createDirectories(profileDir) }
            .onFailure { lastError = "Could not create ${profileDir}: ${it.message}"; return false }
        val log = profileDir.resolve("chrome.log")
        // The log is appended to by every launch; past a few megabytes it is started afresh.
        runCatching { if (Files.size(log) > MaxLogBytes) Files.delete(log) }
        val logOffset = runCatching { if (Files.exists(log)) Files.size(log) else 0L }.getOrDefault(0L)
        val command = buildList {
            add(exe.toString())
            if (wantHeadless) {
                // The engine, without a window. Media still reaches the sound card.
                add("--headless=new")
            }
            // An ordinary Chrome user agent, in a window too: a headless engine would announce
            // itself, and the Web Player refuses a brand browser by name as well ("Helium/0.21…",
            // Edge, Brave). Either way Spotify has to see a Chrome it knows.
            userAgent(exe)?.let { add("--user-agent=$it") }
            add("--user-data-dir=${profileDir.toAbsolutePath()}")
            add("--remote-debugging-port=$port")
            add("--remote-debugging-address=127.0.0.1")
            add("--no-first-run")
            add("--no-default-browser-check")
            add("--disable-default-apps")
            add("--disable-background-networking")
            // Keep the bundled playback engine out of desktop MPRIS / media-key ownership; Valen's
            // own MprisService owns media integration.
            add("--disable-features=HardwareMediaKeyHandling,MediaSessionService")
            add("--autoplay-policy=no-user-gesture-required")
            add("--disable-background-timer-throttling")
            add("--disable-renderer-backgrounding")
            add("--disable-backgrounding-occluded-windows")
            add(startUrl)
        }
        headless = wantHeadless
        launchCount++
        process = runCatching {
            ProcessBuilder(guarded(command))
                .also { it.environment().remove("LD_PRELOAD") }
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start()
        }.onFailure { lastError = "Could not start Chrome: ${it.message}" }.getOrNull() ?: return false
        val ok = awaitReady()
        if (!ok) {
            // A Chrome that refused to run its sandbox (AppImage FUSE mounts or distros that
            // restrict user namespaces) exits at once and says so in the log: retry without it.
            val newLog = runCatching {
                val all = Files.readAllBytes(log)
                val start = logOffset.toInt().coerceIn(0, all.size)
                String(all, start, all.size - start)
            }.getOrDefault("")
            val saidSandbox = newLog.contains("sandbox", ignoreCase = true)
            if (saidSandbox) {
                process?.destroy()
                runCatching { process?.waitFor() }
                process = runCatching {
                    ProcessBuilder(guarded(command + "--no-sandbox"))
                        .also { it.environment().remove("LD_PRELOAD") }
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                        .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
                        .start()
                }.getOrNull()
                if (awaitReady()) {
                    lastError = null
                    return true
                }
            }
            if (lastError == null) {
                lastError = "Chrome did not open $cdpUrl within ${readyTimeoutMs / 1000}s: see $log"
            }
        }
        if (ok) lastError = null
        return ok
    }

    /**
     * Whether the browser on [port] has no window; null when no browser process can be identified.
     *
     * Read from the process itself, not from `/json/version`: a headless engine is launched with an
     * ordinary user agent on purpose (the Web Player refuses a headless one), so the endpoint's
     * user agent says "Chrome", never "HeadlessChrome", and the old check always answered "visible".
     */
    suspend fun isHeadless(): Boolean? = withContext(Dispatchers.IO) {
        val line = browserCommandLine() ?: return@withContext null
        flags(line).any { it == "--headless" || it.startsWith("--headless=") }
    }

    /**
     * The command line of the browser itself, when one can be identified. Not its helpers: every
     * renderer and zygote repeats the launch flags (including the DevTools port) but says nothing
     * about windows, so only the process without a `--type=` is the browser.
     */
    private fun browserCommandLine(): String? = runCatching {
        ProcessHandle.allProcesses()
            .filter { isBrowserOnPort(it, "--remote-debugging-port=$port") }
            .filter { handle -> flags(commandLineOf(handle)).none { it.startsWith("--type=") } }
            .findFirst()
            .map { handle -> commandLineOf(handle) }
            .orElse(null)
    }.getOrNull()

    /**
     * Every live process belonging to the browser on this launcher's DevTools port - the browser and
     * its helpers - whichever process started it.
     */
    private fun browserProcesses(): List<ProcessHandle> = runCatching {
        ProcessHandle.allProcesses()
            .filter { isBrowserOnPort(it, "--remote-debugging-port=$port") }
            .toList()
    }.getOrDefault(emptyList())

    private fun isBrowserOnPort(handle: ProcessHandle, flag: String): Boolean {
        val line = commandLineOf(handle)
        if (!flags(line).contains(flag)) return false
        val exe = line.substringBefore(' ').substringAfterLast('/')
        return BROWSER_EXECUTABLE.containsMatchIn(exe)
    }

    /**
     * The process's own command line, from `/proc` when this machine has it.
     *
     * `ProcessHandle.Info.commandLine()` is not dependable here: for the Helium browser process on
     * this machine it answers with argv[0] alone (`/opt/helium-browser-bin/helium `), so the
     * DevTools flag and `--headless` were invisible — `isHeadless()` said "no browser", the sign-in
     * reported "its window state could not be read", and `stopBrowser()` found nothing to stop.
     * `/proc/<pid>/cmdline` has the complete line, NUL separated, and is readable for our own
     * processes. [ProcessHandle] stays the source of pids and of signals.
     */
    private fun commandLineOf(handle: ProcessHandle): String =
        procCommandLine(handle.pid())
            ?: runCatching { handle.info().commandLine().orElse("") }.getOrDefault("")

    private fun flags(line: String): List<String> = line.split(' ')

    /**
     * Stops the browser this launcher is talking to, whether it started it or an earlier run left
     * it behind, and waits until the port is free. Only ever called when a window is required and
     * the running browser has none: one browser per profile and port can exist at a time.
     */
    suspend fun stopBrowser(): Boolean = startLock.withLock { stopBrowserLocked() }

    private suspend fun stopBrowserLocked(): Boolean {
        val targets = buildList {
            process?.let { add(it.toHandle()) }
            addAll(browserProcesses())
        }.distinct()
        process = null
        if (targets.isEmpty()) return true
        // A signal immediately after login can beat Chromium's asynchronous cookie write.
        // Browser.close flushes the profile; signals remain the fallback for an unresponsive CDP.
        if (runCatching { CdpBrowser.closeBrowser(cdpUrl) }.getOrDefault(false)) {
            val gracefulBy = System.currentTimeMillis() + STOP_TIMEOUT_MS
            while (System.currentTimeMillis() < gracefulBy && targets.any { it.isAlive }) delay(100)
            if (targets.none { it.isAlive } && !probe()) {
                lastError = null
                return true
            }
        }
        targets.forEach { runCatching { it.destroy() } }
        val politeBy = System.currentTimeMillis() + STOP_TIMEOUT_MS
        while (System.currentTimeMillis() < politeBy && targets.any { it.isAlive }) delay(100)
        targets.filter { it.isAlive }.forEach { runCatching { it.destroyForcibly() } }
        val goneBy = System.currentTimeMillis() + STOP_TIMEOUT_MS
        while (System.currentTimeMillis() < goneBy && (targets.any { it.isAlive } || probe())) delay(100)
        if (targets.any { it.isAlive } || probe()) return false
        lastError = null
        return true
    }

    /** The engine's own major version, for a plausible user agent. */
    internal fun userAgent(exe: Path): String? {
        cachedUserAgent?.let { return it }
        val major = runCatching {
            val p = ProcessBuilder(exe.toString(), "--version").redirectErrorStream(true).start()
            val said = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor(5, TimeUnit.SECONDS)
            engineMajor(said)
        }.getOrNull() ?: return null
        // The whole line is cached, not just its tail: an `also` on the second half of the
        // concatenation cached "Chrome/… Safari/537.36" alone, so every launch after the first
        // announced itself with no Mozilla prefix and the Web Player refused it.
        val agent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$major.0.0.0 Safari/537.36"
        cachedUserAgent = agent
        return agent
    }

    @Volatile private var cachedUserAgent: String? = null

    private suspend fun hasOpenPages(): Boolean? = withContext(Dispatchers.IO) {
        runCatching {
            val client = http ?: return@runCatching null
            val request = HttpRequest.newBuilder(URI.create("$cdpUrl/json/list"))
                .timeout(Duration.ofSeconds(2)).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) hasPageTargets(response.body()) else null
        }.getOrNull()
    }

    /** Whether `/json/version` answers now. */
    suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val client = http ?: HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build().also { http = it }
            val request = HttpRequest.newBuilder(URI.create("$cdpUrl/json/version")).timeout(Duration.ofMillis(1200)).GET().build()
            client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
        }.onFailure {
            // A probe that fails for a reason other than "not up yet" is not a slow start: say so.
            if (System.getenv("VALEN_CHROME_LOG") != null) System.err.println("valen: CDP probe $cdpUrl: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
    }

    /** Waits until `/json/version` answers, or [readyTimeoutMs] passes. */
    suspend fun awaitReady(timeoutMs: Long = readyTimeoutMs): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (probe()) return true
            if (process?.isAlive == false) {
                // A second Chrome on the same profile hands its command line to the first one
                // and exits at once; the port may answer the moment it is gone.
                if (probe()) return true
                lastError = "Chrome exited while starting: see ${profileDir.resolve("chrome.log")}"
                return false
            }
            delay(200)
        }
        return probe()
    }

    /** Opens [url] in a new tab of this Chrome (a no-op when it is not running). */
    suspend fun openTab(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val client = http ?: HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build().also { http = it }
            val request = HttpRequest.newBuilder(URI.create("$cdpUrl/json/new?$url"))
                .timeout(Duration.ofSeconds(5))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build()
            client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
        }.getOrDefault(false)
    }

    /** The reason the last [start] failed, when it did. */
    @Volatile var lastError: String? = null
        private set

    @Volatile private var http: HttpClient? = null

    /** Stops the Chrome this launcher started; a Chrome that was already running is left alone. */
    override fun close() {
        val p = process ?: return
        process = null
        runCatching {
            p.destroy()
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    companion object {
        const val DEFAULT_PORT = 9333

        /** How long a browser gets to close politely, then forcibly, before a sign-in gives up. */
        private const val STOP_TIMEOUT_MS = 4_000L

        /**
         * The Chromium major version in a browser's `--version` output.
         *
         * "Google Chrome 157.0.8103.78" and Helium's "Helium 0.21.3.1 (Chromium 157.0.8103.78)"
         * both mean 157: the engine's number, not the brand's. Reading Helium's own 0.21.3.1 as the
         * engine produced `Chrome/0.0.0.0`, and the Web Player answered "Unsupported browser" — the
         * player never started and every bridge call timed out behind it.
         */
        internal fun engineMajor(versionOutput: String): String? {
            Regex("""(?:Chrome|Chromium)[/ ](\d+)\.""").find(versionOutput)?.let { return it.groupValues[1] }
            // A build that prints only a version: take the plausible engine one, never a product's
            // 0.x or 1.x number.
            return Regex("""\b(\d{2,3})\.\d+\.\d+\.\d+""").findAll(versionOutput)
                .map { it.groupValues[1] }
                .maxByOrNull { it.toInt() }
        }

        /**
         * The complete argv of [pid] from `/proc/<pid>/cmdline`, arguments joined by spaces, or
         * null when the file cannot be read (another user's process, a non-Linux machine, a pid
         * that is gone). Arguments are NUL separated there; the NULs become the spaces the flag
         * scanner expects.
         *
         * This exists because [ProcessHandle.Info.commandLine] can answer with argv[0] only, which
         * made a running browser invisible to every process check in this class.
         */
        internal fun procCommandLine(pid: Long): String? = runCatching {
            val raw = Files.readAllBytes(Paths.get("/proc", pid.toString(), "cmdline"))
            if (raw.isEmpty()) return@runCatching null
            String(raw, Charsets.UTF_8).trimEnd('\u0000').replace('\u0000', ' ')
                .takeIf { it.isNotBlank() }
        }.getOrNull()

        /**
         * Where [browser] can keep Valen's profile. A snap or Flatpak browser cannot write to a
         * hidden folder of the home directory, so `~/.local/share/valen/chrome` does not survive it:
         * Chromium falls back to a profile that dies with the window, the sign-in is gone at every
         * start, and each start opens a sign-in window again (a tester on a snap Chromium: "keeps
         * opening Spotify in Chromium, won't stay logged in once I close it"). Inside the sandbox's
         * own folder it persists.
         */
        fun profileFor(browser: Path?): Path {
            System.getenv("VALEN_CHROME_PROFILE")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
            val home = System.getProperty("user.home")
            when (val box = browser?.let { sandboxOf(it) }) {
                is Sandbox.Snap -> return Paths.get(home, "snap", box.name, "common", "valen-chrome")
                is Sandbox.Flatpak -> return Paths.get(home, ".var", "app", box.appId, "data", "valen-chrome")
                null -> {}
            }
            return defaultProfileDir()
        }

        /** A browser packaged in a sandbox, and how to name its own folder. */
        sealed interface Sandbox {
            data class Snap(val name: String) : Sandbox
            data class Flatpak(val appId: String) : Sandbox
        }

        /**
         * Whether [exe] is a snap or a Flatpak: by its path (`/snap/bin/…`, a Flatpak export), by
         * where it really points, or — Ubuntu's `/usr/bin/chromium-browser` is a small script that
         * starts the snap — by what a launcher script runs.
         */
        fun sandboxOf(exe: Path): Sandbox? {
            fun byPath(p: String): Sandbox? = when {
                p.startsWith("/snap/bin/") -> Sandbox.Snap(p.removePrefix("/snap/bin/").substringBefore('.').ifBlank { "chromium" })
                p.startsWith("/snap/") -> Sandbox.Snap(p.removePrefix("/snap/").substringBefore('/'))
                "/flatpak/exports/bin/" in p -> Sandbox.Flatpak(p.substringAfterLast('/'))
                else -> null
            }
            byPath(exe.toString())?.let { return it }
            val real = runCatching { exe.toRealPath().toString() }.getOrNull()
            if (real != null && real.endsWith("/usr/bin/snap")) return Sandbox.Snap(exe.fileName.toString().removeSuffix("-browser"))
            real?.let { byPath(it) }?.let { return it }
            // A launcher script: read its start (a real browser binary is not text).
            val head = runCatching {
                Files.newInputStream(exe).use { it.readNBytes(8192) }
            }.getOrNull() ?: return null
            if (head.size < 2 || head[0] != '#'.code.toByte() || head[1] != '!'.code.toByte()) return null
            val text = String(head, Charsets.UTF_8)
            Regex("/snap/bin/([A-Za-z0-9_-]+)").find(text)?.let { return Sandbox.Snap(it.groupValues[1]) }
            Regex("snap run ([A-Za-z0-9_-]+)").find(text)?.let { return Sandbox.Snap(it.groupValues[1]) }
            Regex("""flatpak run[^\n]*?([A-Za-z0-9_-]+\.[A-Za-z0-9_.-]+)""").find(text)?.let { return Sandbox.Flatpak(it.groupValues[1]) }
            return null
        }

        /** `$XDG_DATA_HOME/valen/chrome`, default `~/.local/share/valen/chrome`. */
        fun defaultProfileDir(): Path {
            System.getenv("VALEN_CHROME_PROFILE")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
            val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/.local/share")
            return Paths.get(dataHome, "valen", "chrome")
        }

        /**
         * `$VALEN_CHROME_BIN`, then the Chrome runtime bundled inside the packaged app
         * (`$VALEN_BUNDLED_CHROME` or beside `java.home`), then the project's
         * `.tools/chrome/opt/google/chrome/chrome`, then any Chromium-family browser this machine has.
         */
        fun findBinary(): Path? {
            System.getenv("VALEN_CHROME_BIN")?.takeIf { it.isNotBlank() }?.let {
                val p = Paths.get(it)
                if (Files.isExecutable(p)) return p
            }
            bundledBinary()?.let { return it }
            val local = projectRoot()?.resolve(".tools/chrome/opt/google/chrome/chrome")
            if (local != null && Files.isExecutable(local)) return local
            return systemBinary()
        }

        /**
         * A private Chrome runtime packaged alongside Valen (AppImage `chrome/chrome` or `.deb`
         * `/opt/valen/lib/chrome/chrome`). Used exclusively as Valen's headless playback engine.
         */
        fun bundledBinary(): Path? {
            System.getenv("VALEN_BUNDLED_CHROME")?.takeIf { it.isNotBlank() }?.let {
                val p = Paths.get(it)
                if (Files.isExecutable(p)) return p
            }
            val anchors = buildList {
                System.getProperty("java.home")?.takeIf { it.isNotBlank() }?.let { add(Paths.get(it)) }
                runCatching {
                    ProcessHandle.current().info().command().orElse(null)?.let { Paths.get(it).parent }
                }.getOrNull()?.let { add(it) }
                runCatching {
                    ChromeLauncher::class.java.protectionDomain?.codeSource?.location?.toURI()?.let { Paths.get(it).parent }
                }.getOrNull()?.let { add(it) }
            }
            val relativePaths = listOf(
                "chrome/chrome",
                "../chrome/chrome",
                "../../chrome/chrome",
                "../lib/chrome/chrome",
                "../../lib/chrome/chrome",
            )
            for (anchor in anchors) {
                for (rel in relativePaths) {
                    val candidate = runCatching { anchor.resolve(rel).normalize() }.getOrNull() ?: continue
                    if (Files.isExecutable(candidate)) return candidate
                }
            }
            val deb = Paths.get("/opt/valen/lib/chrome/chrome")
            if (Files.isExecutable(deb)) return deb
            return null
        }

        /**
         * A Chromium-family browser already on the machine. Only the DevTools port and the usual
         * autoplay flags are used, so Chrome, Chromium, Helium, Brave, Vivaldi and Edge all work.
         * Each candidate is asked its `--version` first: a `chrome` on `PATH` that is something
         * else (a wrapper, an unrelated tool) is never launched.
         */
        fun systemBinary(): Path? {
            val found = chromiumCandidates()
            // An ordinary install first: a sandboxed one (snap, Flatpak) only when it is all there is.
            return found.firstOrNull { sandboxOf(it) == null } ?: found.firstOrNull()
        }

        /** Every Chromium-family browser found, in the order of [systemBinary]'s search. */
        private fun chromiumCandidates(): List<Path> {
            val out = ArrayList<Path>()
            val seen = LinkedHashSet<Path>()
            val names = listOf(
                "google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "chrome",
                "helium-browser", "brave-browser", "vivaldi-stable",
                "microsoft-edge", "microsoft-edge-stable",
            )
            fun consider(candidate: Path) {
                if (!seen.add(candidate)) return
                // Asking a sandboxed browser its version starts its sandbox: once the first plain
                // install is found, sandboxed ones are not asked at all.
                if (out.any { sandboxOf(it) == null } && sandboxOf(candidate) != null) return
                if (isChromium(candidate)) out.add(candidate)
            }
            for (name in names) {
                val candidate = onPath(name) ?: continue
                consider(candidate)
                if (out.any { sandboxOf(it) == null }) return out
            }
            for (fixed in KNOWN_BINARIES) {
                val candidate = runCatching { Paths.get(fixed).toAbsolutePath().normalize() }.getOrNull() ?: continue
                if (!Files.isExecutable(candidate)) continue
                consider(candidate)
                if (out.any { sandboxOf(it) == null }) return out
            }
            for (candidate in desktopExecutables()) {
                consider(candidate)
                if (out.any { sandboxOf(it) == null }) return out
            }
            return out
        }

        /** The executable [name] resolves to on `PATH`, if any. */
        private fun onPath(name: String): Path? {
            val dirs = System.getenv("PATH").orEmpty().split(':').filter { it.isNotBlank() }
            return dirs.asSequence()
                .map { runCatching { Paths.get(it, name) }.getOrNull() }
                .filterNotNull()
                .firstOrNull { Files.isExecutable(it) }
                ?.let { runCatching { it.toAbsolutePath().normalize() }.getOrNull() }
        }

        /**
         * Executables named by this machine's application entries. A browser installed under a name
         * Valen does not know (Helium, a distro build, a Flatpak) still declares itself in its
         * `.desktop` file, so this is the fallback that keeps the list from being a guess.
         */
        private fun desktopExecutables(): List<Path> {
            val dirs = buildList {
                System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { add(Paths.get(it, "applications")) }
                add(Paths.get(System.getProperty("user.home"), ".local/share/applications"))
                (System.getenv("XDG_DATA_DIRS")?.takeIf { it.isNotBlank() }?.split(':')
                    ?: listOf("/usr/local/share", "/usr/share"))
                    .forEach { add(Paths.get(it, "applications")) }
                add(Paths.get("/var/lib/flatpak/exports/share/applications"))
                add(Paths.get(System.getProperty("user.home"), ".local/share/flatpak/exports/share/applications"))
            }
            val found = LinkedHashSet<Path>()
            for (dir in dirs.distinct()) {
                val files = runCatching { Files.list(dir).use { s -> s.toList() } }.getOrNull() ?: continue
                for (file in files) {
                    if (!file.fileName.toString().endsWith(".desktop")) continue
                    val text = runCatching { Files.readString(file).take(16_384) }.getOrNull() ?: continue
                    if (!CHROMIUM_HINT.containsMatchIn(text)) continue
                    for (line in text.lineSequence()) {
                        if (!line.startsWith("Exec=")) continue
                        val token = line.removePrefix("Exec=").trim().split(' ').firstOrNull() ?: continue
                        val name = token.trim('"', '\'').substringBefore(' ')
                        if (name.isBlank() || name.contains('%') || name.contains('$')) continue
                        val exe = if (name.contains('/')) Paths.get(name) else onPath(name)
                        if (exe == null || !Files.isExecutable(exe)) continue
                        val absolute = runCatching { exe.toAbsolutePath().normalize() }.getOrNull() ?: continue
                        found.add(absolute)
                    }
                }
            }
            return found.toList()
        }

        /** Whether [exe] answers `--version` as a Chromium build. Never throws, never keeps it open. */
        private fun isChromium(exe: Path): Boolean = runCatching {
            val process = ProcessBuilder(exe.toString(), "--version")
                .redirectErrorStream(true)
                .start()
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) return@runCatching false
                val text = process.inputStream.bufferedReader().use { it.readText() }.take(600)
                text.contains("chromium", ignoreCase = true) || text.contains("chrome/", ignoreCase = true)
            } finally {
                process.destroyForcibly()
            }
        }.getOrDefault(false)

        private val CHROMIUM_HINT = Regex("(?i)chromium|google.chrome|helium|brave|vivaldi|microsoft.edge")

        /** A process whose executable name is a Chromium-family browser. */
        private val BROWSER_EXECUTABLE = Regex("(?i)chrom|helium|brave|vivaldi|edge")

        private val KNOWN_BINARIES = listOf(
            "/opt/google/chrome/chrome",
            "/opt/google/chrome-beta/chrome",
            "/opt/helium-browser-bin/helium",
            "/opt/brave.com/brave/brave",
            "/opt/vivaldi/vivaldi",
            "/opt/microsoft/msedge/msedge",
            "/usr/bin/google-chrome",
            "/usr/bin/google-chrome-stable",
            "/usr/bin/chromium",
            "/usr/bin/chromium-browser",
            "/usr/lib/chromium/chromium",
            "/snap/bin/chromium",
            "/var/lib/flatpak/exports/bin/com.google.Chrome",
            "/var/lib/flatpak/exports/bin/org.chromium.Chromium",
        )

        /** The Valen checkout holding `.tools/`, found from the working directory upwards. */
        fun projectRoot(): Path? {
            var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            while (dir != null) {
                if (Files.isDirectory(dir.resolve(".tools"))) return dir
                dir = dir.parent
            }
            return null
        }
    }
}

/** Unknown or malformed target lists must never be treated as permission to close a window. */
internal fun hasPageTargets(value: String): Boolean? = runCatching {
    Json.parseToJsonElement(value).jsonArray.map {
        requireNotNull(it.jsonObject["type"]).jsonPrimitive.content
    }.any { it == "page" }
}.getOrNull()


/** Past this size the browser's log is started afresh at the next launch. */
private const val MaxLogBytes = 4L * 1024 * 1024

/**
 * [command] under a guardian that ends the browser when Valen ends, however it ends.
 *
 * A browser started directly outlives a Valen that crashes or is killed: it keeps the Spotify
 * page — and whatever it was playing — running headless with nothing left to stop it, and the next
 * Valen adopts that leftover engine (seen live: a crash at 18:05 left its headless browser playing
 * on, adopted by the next run at 18:20). The guardian is a shell that starts the browser and then
 * waits on its own stdin, a pipe only Valen holds: when Valen's process ends for any reason the
 * pipe closes, the wait returns, and the guardian sends the browser SIGTERM (a clean Chrome exit,
 * so the profile's cookies are written). A browser that exits by itself ends the guardian too.
 *
 * Without a `/bin/sh` the command runs as it is.
 */
internal fun guarded(command: List<String>): List<String> {
    if (!Files.isExecutable(java.nio.file.Paths.get("/bin/sh"))) return command
    val script = """
        "${'$'}@" &
        child=${'$'}!
        trap 'kill -TERM ${'$'}child 2>/dev/null; exit 0' TERM INT HUP
        ( while kill -0 ${'$'}child 2>/dev/null; do sleep 2; done; kill -TERM ${'$'}${'$'} 2>/dev/null ) &
        read _ignored
        kill -TERM ${'$'}child 2>/dev/null
        wait ${'$'}child
    """.trimIndent()
    return listOf("/bin/sh", "-c", script, "valen-browser") + command
}
