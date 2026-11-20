/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.update

import app.valen.core.AppRelease
import app.valen.core.ReleaseManifest
import app.valen.core.UpdatePrefs
import app.valen.core.UpdateRoute
import app.valen.core.UpdateState
import app.valen.core.Updates
import app.valen.core.ValenVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Valen's updates from its GitHub releases.
 *
 * A look asks GitHub for the repository's releases with the last answer's ETag, so a look that
 * finds nothing new is a 304 that costs no part of GitHub's hourly allowance (60 an hour, per
 * address, for an app with no account). Only a release whose tag is a [ValenVersion] newer than
 * this build and which carries `valen-release.json` counts; the manifest must name the same version
 * and number as the tag, and must list a file for [UpdateInstaller.platform]. Anything else is
 * passed over, never half-installed.
 *
 * The file is downloaded beside the state (`downloads/`), resumed after a cut from where it stopped,
 * and handed to the installer only when its size and SHA-256 are the manifest's. With looking on,
 * a release is downloaded as soon as it is found (on Android only off a metered network) and, where
 * the installer installs quietly (the AppImage), put in place: the next start is the new version.
 *
 * One piece of work at a time: a look, a download or an install; a press while one runs does nothing.
 */
class GithubUpdater(
    override val current: String,
    private val repo: String,
    private val installer: UpdateInstaller,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    /** Where the updater keeps its state (`updates.json`) and its downloads (`downloads/`). */
    private val folder: File,
    private val api: String = "https://api.github.com",
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * A release reached a state worth a word (put in place, ready to install, or out where Valen
     * only tells), once per version across runs: the app says so ([announcement]).
     */
    private val onAnnounce: (UpdateState) -> Unit = {},
    private val firstLookMs: Long = FIRST_LOOK_MS,
    private val everyMs: Long = EVERY_MS,
) : Updates {

    private val currentVersion = ValenVersion.parse(current)
    private val currentCode = currentVersion?.code ?: 0

    override val route: UpdateRoute get() = installer.route
    override val elsewhere: String? get() = installer.elsewhere

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    override val state: StateFlow<UpdateState> = _state.asStateFlow()

    @Volatile private var prefs = UpdatePrefs()
    @Volatile private var work: Job? = null

    /** A look waits behind the running work (see [launchWork]). */
    private var queued = false
    private val stateFile = File(folder, "updates.json")
    private val downloads = File(folder, "downloads")
    private var memory: Memory = readMemory()

    /** Follows the person's choices: looks a little after start, then every few hours while on. */
    fun start(choices: Flow<UpdatePrefs>) {
        scope.launch {
            choices.distinctUntilChanged().collectLatest { chosen ->
                val testsChanged = chosen.takesTestBuilds(currentVersion) != prefs.takesTestBuilds(currentVersion)
                prefs = chosen
                if (!chosen.check) {
                    if (work?.isActive != true) _state.value = UpdateState.Off
                    return@collectLatest
                }
                if (_state.value == UpdateState.Off) _state.value = UpdateState.Idle
                // Taking test builds or no longer taking them changes what counts as newer: look again.
                if (testsChanged && memory.checkedAt > 0) memory = memory.copy(checkedAt = 0)
                delay(firstLookMs)
                launchWork { look(force = false) }
                while (true) {
                    delay(everyMs)
                    if (clock() - memory.checkedAt >= LOOK_EVERY_MS) launchWork { look(force = false) }
                }
            }
        }
    }

    /**
     * A look now. A look asked for while other work is still finishing waits for it and then
     * runs: the state can already read "Up to date" while that work's last steps run, and a press
     * dropped there did nothing at all.
     */
    override fun check() {
        launchWork(afterCurrent = true) { look(force = true) }
    }

    /**
     * Download (when needed) and install. Like [check] it waits for work that is finishing; it
     * reads the state only when its turn comes, so a press during a download installs that
     * download once it is done and never starts a second one.
     */
    override fun update() {
        launchWork(afterCurrent = true) {
            when (val now = _state.value) {
                is UpdateState.Available -> obtain(now.release, install = true)
                is UpdateState.Ready -> obtain(now.release, install = true)
                is UpdateState.Failed -> now.release?.let { obtain(it, install = true) } ?: look(force = true)
                else -> look(force = true)
            }
        }
    }

    override fun restart() = installer.restart()

    override fun openPage(release: AppRelease) = installer.open(release.page)

    /** The system installer's answer, after [UpdateInstaller.Outcome.HandedOver]. Success needs none: the app is replaced. */
    fun installerSaid(report: InstallerReport) {
        val release = when (val now = _state.value) {
            is UpdateState.Installing -> now.release
            is UpdateState.Ready -> now.release
            else -> null
        } ?: return
        _state.value = when (report) {
            InstallerReport.Cancelled -> UpdateState.Ready(release)
            is InstallerReport.Failed -> UpdateState.Failed(report.reason, release)
        }
    }

    /** Every state change after a look goes through here, so a release is announced once. */
    private fun publish(next: UpdateState) {
        _state.value = next
        val release = when (next) {
            is UpdateState.Installed -> next.release
            is UpdateState.Ready -> next.release
            is UpdateState.Available -> next.release.takeIf { installer.route == UpdateRoute.Tell || installer.platform == null }
            else -> null
        } ?: return
        if (memory.announced == release.code) return
        memory = memory.copy(announced = release.code)
        saveMemory()
        runCatching { onAnnounce(next) }
    }

    /**
     * One piece of work at a time. The scheduler's own looks are dropped while work runs; a
     * person's press ([afterCurrent]) waits behind it instead, since the state can already show
     * the end of that work while its last steps run. At most one waits; more presses are dropped.
     */
    private fun launchWork(afterCurrent: Boolean = false, block: suspend () -> Unit) {
        synchronized(this) {
            val running = work?.takeIf { it.isActive }
            if (running != null && (!afterCurrent || queued)) return
            if (running != null) queued = true
            work = scope.launch(Dispatchers.IO) {
                if (running != null) {
                    running.join()
                    synchronized(this@GithubUpdater) { queued = false }
                }
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    _state.value = UpdateState.Failed(e.message ?: "The update failed", releaseOf(_state.value))
                }
            }
        }
    }

    // ---- looking ----

    private suspend fun look(force: Boolean) {
        if (!force && clock() < memory.waitUntil) return
        val before = _state.value
        _state.value = UpdateState.Checking
        val found = try {
            newest(fetchReleases())
        } catch (e: Refusal) {
            _state.value = UpdateState.Failed(e.message ?: "GitHub did not answer", releaseOf(before))
            return
        }
        memory = memory.copy(checkedAt = clock())
        saveMemory()
        if (found == null) {
            _state.value = UpdateState.UpToDate(clock())
            clearDownloads(keep = null)
            return
        }
        clearDownloads(keep = found.asset.name)
        when {
            // This run already put it in place; the next start is that version.
            memory.inPlace == found.code && installer.route == UpdateRoute.Itself -> publish(UpdateState.Installed(found))
            installer.route == UpdateRoute.Tell || installer.platform == null -> publish(UpdateState.Available(found))
            verified(fileFor(found), found) -> settle(found, install = installer.installsQuietly && prefs.check)
            prefs.check && installer.mayDownloadQuietly() -> obtain(found, install = installer.installsQuietly)
            else -> _state.value = UpdateState.Available(found)
        }
    }

    /** The newest release this copy should take, or null when this build is the newest. */
    private suspend fun newest(releases: List<GhRelease>): AppRelease? {
        val tests = prefs.takesTestBuilds(currentVersion)
        val candidates = releases
            .filter { !it.draft }
            .mapNotNull { r -> ValenVersion.parse(r.tag)?.let { v -> r to v } }
            .filter { (r, v) -> v.code > currentCode && (tests || (!v.isTest && !r.prerelease)) }
            .sortedByDescending { (_, v) -> v.code }
        // A release with no manifest, a broken one, or none for this platform is passed over; a few
        // at most are read, newest first.
        for ((release, version) in candidates.take(MANIFESTS_READ)) {
            val manifestAsset = release.assets.firstOrNull { it.name == MANIFEST } ?: continue
            val manifest = runCatching { fetchManifest(manifestAsset.url) }.getOrNull() ?: continue
            if (manifest.code != version.code || ValenVersion.parse(manifest.version)?.code != version.code) continue
            val platform = installer.platform
            if (platform == null) {
                // Told only: any release counts, the page is where it is.
                return AppRelease(version.toString(), version.code, version.isTest || release.prerelease, NO_FILE, release.page, release.page, release.body.orEmpty())
            }
            val file = manifest.assets.firstOrNull { it.platform == platform } ?: continue
            val listed = release.assets.firstOrNull { it.name == file.name } ?: continue
            if (listed.size != file.size || !HEX64.matches(file.sha256)) continue
            return AppRelease(version.toString(), version.code, version.isTest || release.prerelease, file, release.page, listed.url, release.body.orEmpty())
        }
        return null
    }

    private fun fetchReleases(): List<GhRelease> {
        val request = Request.Builder()
            .url("${api.trimEnd('/')}/repos/$repo/releases?per_page=30")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Valen/$current")
            .apply { if (memory.body != null && memory.etag != null) header("If-None-Match", memory.etag!!) }
            .build()
        val body = try {
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    304 -> memory.body ?: throw Refusal("GitHub answered with nothing new and Valen kept nothing")
                    200 -> {
                        val text = response.body.string()
                        memory = memory.copy(etag = response.header("ETag"), body = text)
                        saveMemory()
                        text
                    }
                    403, 429 -> {
                        val reset = response.header("x-ratelimit-reset")?.toLongOrNull()?.times(1000)
                        val retry = response.header("Retry-After")?.toLongOrNull()?.times(1000)?.plus(clock())
                        memory = memory.copy(waitUntil = retry ?: reset ?: (clock() + RATE_LIMIT_WAIT_MS))
                        saveMemory()
                        throw Refusal("GitHub asked Valen to wait; it looks again later")
                    }
                    404 -> throw Refusal("No releases were found for $repo")
                    else -> throw Refusal("GitHub answered ${response.code}")
                }
            }
        } catch (e: IOException) {
            throw Refusal("Could not reach GitHub")
        }
        return try {
            json.decodeFromString<List<GhRelease>>(body)
        } catch (e: Exception) {
            throw Refusal("GitHub's answer could not be read")
        }
    }

    private fun fetchManifest(url: String): ReleaseManifest {
        val request = Request.Builder().url(url).header("User-Agent", "Valen/$current").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("manifest: ${response.code}")
            if (response.body.contentLength() > MANIFEST_MAX_BYTES) throw IOException("manifest too large")
            val text = response.body.string()
            if (text.length > MANIFEST_MAX_BYTES) throw IOException("manifest too large")
            return json.decodeFromString<ReleaseManifest>(text)
        }
    }

    // ---- downloading and installing ----

    private suspend fun obtain(release: AppRelease, install: Boolean) {
        if (installer.route == UpdateRoute.Tell || installer.platform == null) {
            publish(UpdateState.Available(release))
            return
        }
        val file = fileFor(release)
        if (!verified(file, release)) download(release, file)
        settle(release, install)
    }

    /** The file is here and right: install it now, or wait for the press. */
    private suspend fun settle(release: AppRelease, install: Boolean) {
        if (!install) {
            publish(UpdateState.Ready(release))
            return
        }
        _state.value = UpdateState.Installing(release)
        when (val outcome = installer.install(fileFor(release), release)) {
            UpdateInstaller.Outcome.InPlace -> {
                memory = memory.copy(inPlace = release.code)
                saveMemory()
                publish(UpdateState.Installed(release))
            }
            // The system's installer can answer while it is being handed the file (a quick Cancel
            // or a refusal): its answer is the newer one and must stand, not go back to "installing".
            UpdateInstaller.Outcome.HandedOver -> Unit
            is UpdateInstaller.Outcome.Failed -> publish(UpdateState.Failed(outcome.reason, release))
        }
    }

    private fun download(release: AppRelease, file: File) {
        downloads.mkdirs()
        val part = File(downloads, file.name + ".part")
        val total = release.asset.size
        if (part.length() > total) part.delete()
        var tries = 0
        while (true) {
            try {
                fetchInto(part, release, total)
                break
            } catch (e: IOException) {
                // A cut keeps what arrived; the next try continues from there.
                if (++tries >= DOWNLOAD_TRIES) throw IOException("The download stopped: ${e.message ?: "no connection"}")
                Thread.sleep(DOWNLOAD_RETRY_MS * tries)
            }
        }
        if (part.length() != total || !Sha256.of(part).equals(release.asset.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("The downloaded file is not the one the release lists, so it was thrown away")
        }
        Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun fetchInto(part: File, release: AppRelease, total: Long) {
        val have = part.length()
        if (have == total) return
        val request = Request.Builder()
            .url(release.url)
            .header("User-Agent", "Valen/$current")
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub answered ${response.code}")
            val resumed = response.code == 206
            RandomAccessFile(part, "rw").use { out ->
                if (resumed) out.seek(have) else out.setLength(0)
                var done = if (resumed) have else 0L
                var shownAt = 0L
                val buffer = ByteArray(64 * 1024)
                response.body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (done + n > total) throw IOException("the file is longer than the release says")
                        out.write(buffer, 0, n)
                        done += n
                        val now = clock()
                        if (now - shownAt >= PROGRESS_MS || done == total) {
                            shownAt = now
                            _state.value = UpdateState.Downloading(release, done, total)
                        }
                    }
                }
                if (done != total) throw IOException("the file ended early")
            }
        }
    }

    private fun fileFor(release: AppRelease) = File(downloads, release.asset.name.substringAfterLast('/'))

    private fun verified(file: File, release: AppRelease): Boolean =
        file.isFile && file.length() == release.asset.size && Sha256.of(file).equals(release.asset.sha256, ignoreCase = true)

    /** Old downloads go: only the file of the release found is kept (with its part). */
    private fun clearDownloads(keep: String?) {
        downloads.listFiles()?.forEach { f ->
            if (keep == null || (f.name != keep && f.name != "$keep.part")) f.delete()
        }
    }

    private fun releaseOf(state: UpdateState): AppRelease? = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.Ready -> state.release
        is UpdateState.Installing -> state.release
        is UpdateState.Installed -> state.release
        is UpdateState.Failed -> state.release
        else -> null
    }

    // ---- what the updater keeps ----

    @Serializable
    private data class Memory(
        val etag: String? = null,
        /** GitHub's last 200 answer, for the 304s that follow. */
        val body: String? = null,
        val checkedAt: Long = 0,
        /** GitHub asked to wait until then. */
        val waitUntil: Long = 0,
        /** The release last said out loud (its number). */
        val announced: Int = 0,
        /** The release this copy put in place itself (the AppImage), not yet started. */
        val inPlace: Int = 0,
    )

    private fun readMemory(): Memory = runCatching { json.decodeFromString<Memory>(stateFile.readText()) }.getOrDefault(Memory())

    private fun saveMemory() {
        runCatching {
            folder.mkdirs()
            val tmp = File(folder, "updates.json.tmp")
            tmp.writeText(json.encodeToString(Memory.serializer(), memory))
            Files.move(tmp.toPath(), stateFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private class Refusal(message: String) : Exception(message)

    @Serializable
    private data class GhRelease(
        @SerialName("tag_name") val tag: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        @SerialName("html_url") val page: String = "",
        val body: String? = null,
        val assets: List<GhAsset> = emptyList(),
    )

    @Serializable
    private data class GhAsset(
        val name: String,
        val size: Long = 0,
        @SerialName("browser_download_url") val url: String,
    )

    companion object {
        /** The manifest every release carries (written by `.github/workflows/release.yml`). */
        const val MANIFEST = "valen-release.json"

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        private val HEX64 = Regex("^[0-9a-fA-F]{64}$")
        private val NO_FILE = app.valen.core.ReleaseAsset(platform = "", name = "", size = 0, sha256 = "")

        /** The first look, a little after start, so the first screen comes first. */
        const val FIRST_LOOK_MS = 5_000L

        /** How often the loop wakes to see whether a look is due. */
        const val EVERY_MS = 30 * 60_000L

        /** A look at most every six hours by itself (a press looks at once). */
        const val LOOK_EVERY_MS = 6 * 60 * 60_000L

        private const val RATE_LIMIT_WAIT_MS = 60 * 60_000L
        private const val MANIFESTS_READ = 3
        private const val MANIFEST_MAX_BYTES = 64L * 1024
        private const val DOWNLOAD_TRIES = 4
        private const val DOWNLOAD_RETRY_MS = 2_000L
        private const val PROGRESS_MS = 150L
    }
}

/** SHA-256 of a file, as lowercase hex. */
object Sha256 {
    fun of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
