/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.desktop

import app.valen.ui.KeyShortcuts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.valen.core.PlaybackCoordinator
import app.valen.core.PlaylistCatalog
import app.valen.core.PlaylistLibrary
import app.valen.core.PlaylistManager
import app.valen.core.PlaylistStore
import app.valen.core.ProviderId
import app.valen.core.SessionState
import app.valen.core.ListeningStats
import app.valen.core.ListeningStore
import app.valen.core.SettingsStore
import app.valen.core.Track
import app.valen.core.distinctRecordings
import app.valen.host.desktop.DesktopHosts
import app.valen.host.desktop.DiscordPresence
import app.valen.host.desktop.MprisService
import app.valen.ui.Route
import app.valen.ui.ValenApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Valen on the desktop: one private Chrome for the three services, the one queue behind
 * `PlaybackCoordinator`, the media keys and the desktop's media widget through MPRIS, and the
 * Compose window that shows `:ui`.
 *
 * The command line is for development and the screenshot pass only (see [Cli]).
 */
fun main(args: Array<String>) {
    val cli = try {
        Cli.parse(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("valen: ${e.message}")
        return
    }
    if (cli.help) {
        println(Cli.USAGE)
        return
    }
    if (cli.screenshot != null) screenshotRun(cli) else windowRun(cli)
}

/** The normal app: a window, the services, MPRIS. */
private fun windowRun(cli: Cli) {
    tellAwtWhenTheWindowManagerDoesNotReparent()
    val settings = settingsStore()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val hosts = DesktopHosts(scope)
    // Restart after an update closes Valen the usual way; the window's scope fills this in.
    val quit = java.util.concurrent.atomic.AtomicReference<() -> Unit> {}
    val updater = desktopUpdater(scope) { javax.swing.SwingUtilities.invokeLater { quit.get()() } }

    application {
        var boot: Boot? by remember { mutableStateOf(null) }
        var status: String by remember { mutableStateOf("Starting Valen…") }
        val windowState = rememberWindowState(size = DpSize(cli.width.dp, cli.height.dp))

        fun shutdown() {
            // The last seconds of listening are on disk before anything closes.
            boot?.coordinator?.stats?.flush()
            // The queue and the place in the song, so the next start picks up there.
            boot?.coordinator?.saveSession()
            boot?.mpris?.close()
            hosts.close()
            scope.cancel()
        }
        quit.set {
            shutdown()
            exitApplication()
        }

        LaunchedEffect(Unit) {
            // No browser at boot: YouTube Music and SoundCloud resolve and play natively. Chrome
            // is Spotify's alone, and starts in the background from buildProviders.
            status = "Starting the players…"
            val built = buildProvidersOrNothing(hosts, scope)
            status = "Starting the player…"
            val presence = DiscordPresence(scope)
            val coordinator = PlaybackCoordinator(
                built.providers, scope, settings,
                stats = ListeningStats(listeningStore()),
                lyrics = desktopLyrics(built.providers),
                offline = built.shelf,
                presence = presence,
                version = ValenBuild.VERSION,
                updates = updater.updates,
            )
            updater.follow(coordinator)
            val playlists = buildPlaylists(built, coordinator)
            presence.start(coordinator)
            val mpris = MprisService(coordinator, scope)
            if (!mpris.start()) System.err.println("valen: MPRIS unavailable: ${mpris.lastError}")

            // Spotify is warmed in the background (its browser is headless); its tile reports the
            // outcome, so a placeholder reason is not worth a line at every start.
            built.failures.filterKeys { it != ProviderId.SPOTIFY }
                .forEach { (id, reason) -> System.err.println("valen: ${id.label} unavailable: $reason") }
            boot = Boot(coordinator, playlists, mpris, built)
        }

        // Every key nothing in the window took: Valen's shortcuts.
        val keys = remember { KeyShortcuts() }
        Window(
            onCloseRequest = { shutdown(); exitApplication() },
            state = windowState,
            title = "Valen",
            onKeyEvent = keys::handle,
        ) {
            val ready = boot
            LaunchedEffect(ready) {
                val b = ready ?: return@LaunchedEffect
                cli.play?.let { play(b.coordinator, it) }
            }
            if (ready == null) {
                Starting(status)
            } else {
                ValenApp(ready.coordinator, ready.playlists, cli.route.name, cli.query, keys = keys)
            }
        }
    }
}

/**
 * Tiling Wayland compositors (niri, sway, Hyprland) resize a window's frame but leave AWT's
 * content window at the size it was created with, so Compose keeps drawing a strip the size of
 * the old window inside a much larger one. AWT knows how to handle this — its private
 * `_JAVA_AWT_WM_NONREPARENTING` switch — but that is read once, when the toolkit starts, and the
 * JVM's environment is already fixed by the time `main` runs.
 *
 * The switch only sets one field on its first read, so setting that field before the first window
 * has the same effect. The field is read lazily by the first `isNonReparentingWM()` call, which
 * happens when a window is mapped; loading the class here leaves the field at "not decided yet".
 *
 * Reparenting desktops are left alone: their window manager expects to adopt the frame, and
 * turning that off there would break placement instead of fixing it.
 */
private fun tellAwtWhenTheWindowManagerDoesNotReparent() {
    // An explicit value from the environment is the user's call, not ours to override.
    if (System.getenv("_JAVA_AWT_WM_NONREPARENTING") != null) return
    val tiling = listOf("NIRI_SOCKET", "SWAYSOCK", "HYPRLAND_INSTANCE_SIGNATURE", "I3SOCK")
        .any { System.getenv(it) != null }
    if (!tiling) return

    runCatching {
        // The X toolkit's display, which the XWM class' static setup expects to exist.
        java.awt.Toolkit.getDefaultToolkit()
        val xwm = Class.forName("sun.awt.X11.XWM")
        val switch = xwm.getDeclaredField("awtWMNonReparenting")
        switch.isAccessible = true
        switch.setInt(null, 1)
    }.onFailure {
        // Not fatal: the window is still usable, just smaller than its frame.
        System.err.println("valen: could not switch AWT off window reparenting: ${it.message}")
    }
}

/**
 * The `--screenshot` pass: everything a real start does except the window, then one offscreen draw.
 * Exits when the PNG is written.
 */
private fun screenshotRun(cli: Cli) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val hosts = DesktopHosts(scope)
    val path = cli.screenshot ?: return

    val (coordinator, playlists) = runBlocking {
        // Screenshots show the settled app: Chrome is given the head start a real run gets from
        // working on the native providers first.
        val chromeUp = runCatching { withContext(Dispatchers.IO) { hosts.start() } }.getOrDefault(false)
        if (!chromeUp) System.err.println("valen: Spotify's Chrome is not running: ${hosts.startError ?: "unknown reason"}")
        val built = buildProvidersOrNothing(hosts, scope)
        built.failures.forEach { (id, reason) -> System.err.println("valen: ${id.label} unavailable: $reason") }
        val coordinator = PlaybackCoordinator(
            built.providers, scope, settingsStore(),
            stats = ListeningStats(listeningStore()),
            lyrics = desktopLyrics(built.providers),
            offline = built.shelf,
            presence = DiscordPresence(scope),
            version = ValenBuild.VERSION,
            // A screenshot never looks for updates; VALEN_CAPTURE_UPDATE shows one state of them.
            updates = Capture.updates(),
        )
        cli.play?.let { play(coordinator, it) }
        coordinator to buildPlaylists(built, coordinator)
    }
    if (cli.query != null && cli.route != Route.Search) {
        System.err.println("valen: --query fills the search field; add --route search to open it")
    }

    // A short breath for the last provider work, then Capture draws while real time moves on.
    Thread.sleep(400)
    val ok = Capture.render(coordinator, playlists, cli.route, cli.width, cli.height, path, cli.query)

    hosts.close()
    scope.cancel()
    exitProcess(if (ok) 0 else 1)
}

/** Everything one run of the app holds. */
private class Boot(
    val coordinator: PlaybackCoordinator,
    val playlists: PlaylistManager,
    val mpris: MprisService?,
    val providers: ProviderSet,
)

private suspend fun buildProvidersOrNothing(hosts: DesktopHosts, scope: CoroutineScope): ProviderSet =
    runCatching { buildProviders(hosts, scope) }
        .getOrElse {
            ProviderSet(
                emptyList(),
                ProviderId.entries.associateWith { id -> it.message ?: "${id.label} did not start" },
            )
        }

/** A song asked for with `--play`: the service's own search answers, then the queue starts it. */
private suspend fun play(coordinator: PlaybackCoordinator, request: Cli.PlayRequest) {
    val results: List<Track> = if (request.provider != null) {
        val provider = coordinator.provider(request.provider)
        if (provider == null) {
            System.err.println("valen: ${request.provider.label} is not available")
            return
        }
        runCatching { provider.search(request.id, 25) }
            .onFailure { System.err.println("valen: ${request.provider.label} search failed: ${it.message}") }
            .getOrDefault(emptyList())
            .distinctRecordings()
    } else {
        runCatching { coordinator.unifiedSearch(request.id).tracks }
            .onFailure { System.err.println("valen: search failed: ${it.message}") }
            .getOrDefault(emptyList())
    }
    val track = results.firstOrNull { it.id == request.id } ?: results.firstOrNull()
    if (track == null) {
        System.err.println("valen: no result for ${request.raw}")
        return
    }
    // VALEN_CAPTURE_ALONE=1: the song by itself, a queue at its end (what keeps the music going).
    if (System.getenv("VALEN_CAPTURE_ALONE") != null) coordinator.play(track) else coordinator.play(track, results)
    println("valen: playing ${track.provider.label} · ${track.title} — ${track.artist} (${track.id})")
}

@Composable
private fun Starting(status: String) {
    Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0B)), contentAlignment = Alignment.Center) {
        BasicText(status, style = TextStyle(color = Color(0xFFA0A0A8)))
    }
}

/**
 * The playlist library: what the person keeps, so it lives with the app's data
 * (`$XDG_DATA_HOME/valen/playlists.json`, default `~/.local/share/valen/playlists.json`).
 *
 * The importer asks each service for a song's copy on that service only when the stored copy
 * cannot be played here. Spotify counts as playable while its own session says it is connected;
 * the two native services need no account to play, only a built adapter.
 */
private fun buildPlaylists(providers: ProviderSet, coordinator: PlaybackCoordinator): PlaylistManager {
    val library = PlaylistLibrary(playlistStore(), clock = { System.currentTimeMillis() })
    providers.attach(library)
    // "Offline": every song downloaded or kept whole by the song cache, while there is one.
    app.valen.core.OfflineList(
        coordinator,
        library,
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
    ).start()
    return PlaylistManager(
        library = library,
        catalogs = providers.providers.filterIsInstance<PlaylistCatalog>(),
        search = { provider, query, limit ->
            coordinator.provider(provider)?.search(query, limit) ?: emptyList()
        },
        // A downloaded song plays whatever its service says; any other needs its service (Spotify
        // only signed in).
        playable = { track ->
            coordinator.isKept(track) || (coordinator.provider(track.provider) != null &&
                (track.provider != ProviderId.SPOTIFY ||
                    coordinator.sessions.value[track.provider] is SessionState.Connected))
        },
        priority = { coordinator.priorityOrder() },
    )
}

private fun playlistStore(): PlaylistStore {
    val home = System.getProperty("user.home")
    val data = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
        ?: Paths.get(home, ".local", "share")
    val file: Path = data.resolve("valen").resolve("playlists.json")
    return PlaylistStore(
        read = { if (Files.exists(file)) Files.readString(file) else null },
        write = { text ->
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        },
    )
}

/**
 * Settings live where a Linux app keeps them: `$XDG_CONFIG_HOME/valen/settings.json`, default
 * `~/.config/valen/settings.json`.
 */
/**
 * The listening counted for the profile's stats: `$XDG_DATA_HOME/valen/listening.json`, default
 * `~/.local/share/valen/listening.json`, beside the playlists.
 */
private fun listeningStore(): ListeningStore {
    val data = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
        ?: Paths.get(System.getProperty("user.home"), ".local", "share")
    val file: Path = data.resolve("valen").resolve("listening.json")
    return ListeningStore(
        read = { if (Files.exists(file)) Files.readString(file) else null },
        write = { text ->
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        },
    )
}

private fun settingsStore(): SettingsStore {
    val home = System.getProperty("user.home")
    val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
        ?: Paths.get(home, ".config")
    val file: Path = config.resolve("valen").resolve("settings.json")
    return SettingsStore(
        read = { if (Files.exists(file)) Files.readString(file) else null },
        write = { text ->
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        },
    )
}
