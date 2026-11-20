/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.host.desktop

import app.valen.core.AppSettings
import app.valen.core.PlaybackCoordinator
import app.valen.core.PresenceCard
import app.valen.core.PresenceLink
import app.valen.core.PresenceNow
import app.valen.core.PresencePause
import app.valen.core.PresenceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import kotlin.math.abs

class DiscordPresence(
    private val scope: CoroutineScope,
    /** Valen's page, for the second button; `VALEN_HOMEPAGE` may name another. */
    private val homepage: String? = System.getenv("VALEN_HOMEPAGE")?.takeIf { it.startsWith("https://") }
        ?: PresenceCard.HOMEPAGE,
    private val envAppId: String? = System.getenv("VALEN_DISCORD_APP_ID")?.trim()?.takeIf { it.isNotEmpty() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) : PresenceLink {
    private val _status = MutableStateFlow<PresenceStatus>(PresenceStatus.Off)
    override val status: StateFlow<PresenceStatus> = _status.asStateFlow()

    private var loop: Job? = null
    private lateinit var coordinator: PlaybackCoordinator

    /** Starts following [player]: the link waits for Settings, then for Discord. */
    fun start(player: PlaybackCoordinator) {
        if (loop != null) return
        coordinator = player
        loop = scope.launch(Dispatchers.IO) { run() }
    }

    private suspend fun run() {
        while (scope.isActive) {
            val all = coordinator.settings.value
            val settings = all.presence
            val id = appId(envAppId)
            when {
                // Private mode keeps the listening off Discord too: the card is cleared and the
                // link rests until it is off again.
                all.privateMode || !settings.enabled -> {
                    _status.value = PresenceStatus.Off
                    awaitChange(all, Long.MAX_VALUE)
                }
                !id.all { it.isDigit() } -> {
                    _status.value = PresenceStatus.NeedsAppId
                    awaitChange(all, Long.MAX_VALUE)
                }
                else -> {
                    _status.value = PresenceStatus.Connecting
                    val ipc = try {
                        DiscordIpc.open(id)
                    } catch (e: DiscordIpc.Refused) {
                        _status.value = PresenceStatus.Failed(refusal(e.message))
                        awaitChange(all, REFUSED_AGAIN_MS)
                        continue
                    }
                    if (ipc == null) {
                        _status.value = PresenceStatus.NoDiscord
                        awaitChange(all, LOOK_AGAIN_MS)
                        continue
                    }
                    _status.value = PresenceStatus.Connected(ipc.user)
                    try {
                        session(ipc, id)
                    } catch (e: CancellationException) {
                        runCatching { ipc.setActivity(null) }
                        ipc.close()
                        throw e
                    } catch (_: IOException) {
                        // Discord quit or restarted: look for it again.
                    }
                    ipc.close()
                }
            }
        }
    }

    /** Waits until the presence settings (or private mode) differ from [was], or [ms] passes. */
    private suspend fun awaitChange(was: AppSettings, ms: Long) {
        withTimeoutOrNull(ms) {
            coordinator.settings.first { it.presence != was.presence || it.privateMode != was.privateMode }
        }
    }

    /** One connection: reads Discord's answers beside it, sends what changes, ends when it breaks. */
    private suspend fun session(ipc: DiscordIpc, id: String) {
        val reader = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val answer = ipc.next()
                    if (answer["evt"]?.toString()?.contains("ERROR") == true) {
                        val message = (answer["data"] as? JsonObject)?.get("message")?.toString()?.trim('"')
                        System.err.println("valen: Discord did not take the activity: $message")
                    }
                }
            } catch (_: Exception) {
                // The sender notices on its next write.
            }
        }

        var sent: PresenceCard? = null
        var sentOnce = false
        var lastSendAt = 0L
        var pausedSince = 0L
        var askedLyricsFor: String? = null
        try {
            while (scope.isActive && reader.isActive) {
                val all = coordinator.settings.value
                val settings = all.presence
                if (all.privateMode || !settings.enabled || appId(envAppId) != id) {
                    runCatching { ipc.setActivity(null) }
                    return
                }
                val snap = coordinator.snapshot.value
                val track = snap?.track
                val now = clock()
                val playing = snap?.isPlaying == true || snap?.isBuffering == true
                pausedSince = if (track != null && !playing) (if (pausedSince == 0L) now else pausedSince) else 0L

                val pauseGone = track != null && !playing && settings.pause.gone(now - pausedSince)
                val line = if (track != null && playing && PresenceCard.needsLine(settings)) {
                    if (askedLyricsFor != track.key && coordinator.lyrics.known(track) == null) {
                        askedLyricsFor = track.key
                        scope.launch { runCatching { coordinator.lyrics.lyricsFor(track) } }
                    }
                    PresenceCard.lineAt(coordinator.lyrics.known(track), snap.positionMs)
                } else {
                    null
                }
                val card = if (track == null || pauseGone) {
                    null
                } else {
                    PresenceCard.of(
                        now = PresenceNow(
                            track = track,
                            playing = playing,
                            positionMs = snap.positionMs,
                            durationMs = snap.durationMs.takeIf { it > 0 } ?: track.durationMs,
                            line = line,
                            kept = coordinator.isKept(track),
                            place = coordinator.index.value + 1,
                            of = coordinator.queue.value.size,
                        ),
                        settings = settings,
                        homepage = homepage,
                        nowMs = now,
                    )
                }
                val changed = !sentOnce || cardChanged(sent, card)
                if (changed && now - lastSendAt >= MIN_GAP_MS) {
                    ipc.setActivity(card?.activity(settings.display))
                    sent = card
                    sentOnce = true
                    lastSendAt = now
                }
                delay(TICK_MS)
            }
        } finally {
            reader.cancel()
        }
    }

    private fun refusal(message: String?): String = when {
        message == null -> "Discord refused the link"
        message.contains("client id", ignoreCase = true) || message.contains("4000") ->
            "Discord does not know that application id"
        else -> message
    }

    companion object {
        const val MIN_GAP_MS = 4_100L
        const val DRIFT_MS = 2_500L
        const val TICK_MS = 1_000L
        const val LOOK_AGAIN_MS = 15_000L
        const val REFUSED_AGAIN_MS = 120_000L

        /**
         * The Discord application every card is named after: Valen's own, created once and shipped
         * with the app. An application id is a public identifier, not a token — any Discord client
         * may use it, and nothing about the person's account reaches it.
         */
        const val DEFAULT_APP_ID = "1571814573663584338"

        /**
         * The application every card is named after: Valen's own, unless `VALEN_DISCORD_APP_ID`
         * names another. Never empty, so a session always has an id to hand over.
         */
        fun appId(env: String?): String = env?.trim().orEmpty().ifEmpty { DEFAULT_APP_ID }

        internal fun cardChanged(previous: PresenceCard?, next: PresenceCard?): Boolean {
            if (previous == null || next == null) return previous != next
            if (previous.copy(startMs = null, endMs = null) != next.copy(startMs = null, endMs = null)) return true
            return !close(previous.startMs, next.startMs) || !close(previous.endMs, next.endMs)
        }

        private fun close(a: Long?, b: Long?): Boolean {
            if (a == null || b == null) return a == b
            return abs(a - b) <= DRIFT_MS
        }
    }
}
