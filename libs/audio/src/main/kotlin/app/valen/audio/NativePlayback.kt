/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.audio

import app.valen.core.AudioQuality
import app.valen.core.PlayerSnapshot
import app.valen.core.ProviderEvent
import app.valen.core.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The half of a native provider that both services share: one song at a time, resolved by a
 * service-specific [resolve], played by the platform's [AudioPlayer], reported as the app's
 * [PlayerSnapshot] and [ProviderEvent]s.
 *
 * The queue stays the app's: [play] replaces whatever was playing, and the end of a stream is
 * reported once per song so the coordinator can move on.
 *
 * Streams are fragile on the way: an address expires, a connection drops, a server stops sending.
 * So a play that a newer one overtook never reaches the player; a resolved address is kept a few
 * minutes (and [prepare] finds the next song's ahead of time, so it starts without a gap); a
 * kept address that will not open is resolved again once; and a song that ends early, fails after
 * its sound began, or stalls is opened again where it was, with a fresh address — a few times,
 * before its failure (or its end) is reported. A long pause resumes by opening the song again
 * rather than waking a process whose connection has likely been closed under it.
 */
class NativePlayback(
    private val player: AudioPlayer,
    private val scope: CoroutineScope,
    /** The wall clock (tests pass their own). */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Songs kept on disk: a kept song plays without being looked up; null keeps nothing. */
    private val cache: StreamCache? = null,
    /**
     * What a song is resolved by. Default: its uri's last part, else its id. The device's adapter
     * passes its own (a kept song of any service resolves to its file).
     */
    private val idFor: ((Track) -> String)? = null,
    private val resolve: suspend (id: String, quality: AudioQuality) -> ResolvedStream,
) {

    private val _snapshot = MutableStateFlow<PlayerSnapshot?>(null)
    val snapshot: StateFlow<PlayerSnapshot?> = _snapshot.asStateFlow()

    private val _events = MutableSharedFlow<ProviderEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<ProviderEvent> = _events.asSharedFlow()

    @Volatile private var current: Track? = null
    @Volatile private var quality: AudioQuality = AudioQuality.AUTO
    @Volatile private var volume: Float = 1f

    /** The song whose end was already reported; [play] arms it again. */
    private var endedFor: String? = null
    private var lastError: String? = null

    /**
     * True from the start of [play] until the new stream is playing. The sink still holds the
     * previous song's state in that window (its ffplay process keeps ticking while the new URL is
     * resolved), and publishing that under the new track moved the new song's position to wherever
     * the old one was.
     */
    @Volatile private var resolving = false

    /** Bumped by every play and stop: a play that is no longer the latest leaves the player alone. */
    private val generation = AtomicLong()

    /** Hands the player one stream at a time. */
    private val handing = Mutex()

    /** Resolved addresses by song and quality, with when they were resolved. */
    private val streams = ConcurrentHashMap<String, Pair<ResolvedStream, Long>>()

    /**
     * Look-ups under way, by song and quality: a second ask for the same song (a tap right after a
     * press warmed it, a play racing the prepare) waits for the first instead of asking again.
     */
    private val lookups = HashMap<String, kotlinx.coroutines.CompletableDeferred<ResolvedStream>>()

    /** The song is being opened again after a drop: its end or failure is not reported meanwhile. */
    @Volatile private var recovering = false
    @Volatile private var recoveries = 0
    private var recovery: Job? = null

    /** When the song was paused (wall clock), 0 while it plays. */
    @Volatile private var pausedAt = 0L

    /**
     * Sound came out of this song since its [play]. The sink's own `started` is per stream, and
     * every reopen of a dropped song is a new stream: judging a reopen by it read a second drop as
     * "never opened", so the three tries usually stopped at one.
     */
    private var sounded = false

    /** The stream playing came from the address cache (it may have expired since). */
    private var fromKept = false

    /** The stream playing is the song kept on disk (it may be damaged). */
    private var fromDisk = false

    /** Where the last reopen started, for telling a song that ends early from one that broke. */
    private var reopenedAt = -1L

    /** Since when the song has played steadily (wall clock), 0 when it is not. */
    private var steadySince = 0L

    /** A failure already reported for this play; one per play reaches the app. */
    private var failedThisPlay = false

    /** Publishing and the switch to a new song never interleave (a late end must not land on the next song). */
    private val publishing = Any()

    init {
        scope.launch {
            player.state.collect { s -> synchronized(publishing) { if (!resolving) publish(s) } }
        }
    }

    private fun publish(s: AudioState) {
        val track = current ?: return
        if (!s.hasStream) return
        if (recovering) return
        if (s.started) sounded = true
        val now = clock()
        // Steady sound for a while: earlier drops are forgotten, a long song may drop again later.
        if (s.playing && s.started && !s.buffering && !s.stalled && s.error == null) {
            if (steadySince == 0L) steadySince = now else if (now - steadySince > STEADY_MS) recoveries = 0
        } else {
            steadySince = 0L
        }
        val duration = s.durationMs.takeIf { it > 0 } ?: track.durationMs
        val known = duration > 0
        // Close to the end is the end: a reopen or a seek there that makes no sound, or a failure in
        // the last seconds, is the song running out rather than breaking.
        val atEnd = known && s.positionMs >= duration - EARLY_END_MS
        val reopenNearEnd = reopenedAt >= 0 && known && reopenedAt >= duration - REOPEN_END_MS && !s.started
        val ends = s.ended || ((s.error != null || s.stalled) && (atEnd || reopenNearEnd))
        if (!ends) {
            // An address kept from earlier that will not open is looked up again, once.
            if (s.error != null && !s.started && fromKept) {
                fromKept = false
                if (fromDisk) {
                    fromDisk = false
                    cache?.forget(keyOf(track))
                }
                recover(track, s.positionMs, count = false)
                return
            }
            // A drop: a failure after the sound began, a stream that stopped arriving.
            val broke = (s.error != null && sounded) || s.stalled
            if (broke && recoveries < MAX_RECOVERIES) {
                recover(track, s.positionMs)
                return
            }
            if (broke || s.error != null) {
                failOnce(track, s.positionMs, s.error ?: "${track.provider.label} stopped sending the song")
                return
            }
        } else if (s.ended && sounded && known && s.positionMs < duration - EARLY_END_MS &&
            !reopenNearEnd && recoveries < MAX_RECOVERIES
        ) {
            // An early end: the connection closed before the song was over.
            recover(track, s.positionMs)
            return
        }
        if (ends && endedFor != track.key) {
            endedFor = track.key
            _events.tryEmit(ProviderEvent.TrackEnded(track.key))
        }
        _snapshot.value = PlayerSnapshot(
            track = track,
            isPlaying = s.playing,
            isBuffering = s.buffering,
            positionMs = s.positionMs,
            durationMs = duration,
            canSeek = player.canSeek,
            canSkip = true,
            volume = volume,
            error = if (ends) null else (s.error ?: lastError),
        )
    }

    /** Reports the song's failure once per play, with the sink stopped so nothing of it keeps ticking. */
    private fun failOnce(track: Track, at: Long, message: String) {
        if (failedThisPlay) return
        failedThisPlay = true
        scope.launch { runCatching { player.stop() } }
        failed(track, at, message)
    }

    /**
     * Opens [track] again at [at] with a freshly resolved address, after a short wait that grows
     * with each try. A lookup that fails (the network is down for a moment) is tried again while
     * tries are left, instead of ending the song at the first one.
     */
    private fun recover(track: Track, at: Long, count: Boolean = true) {
        recovering = true
        if (count) recoveries++
        val tries = recoveries.coerceAtLeast(1)
        val gen = generation.get()
        _snapshot.value = _snapshot.value?.copy(isPlaying = true, isBuffering = true, positionMs = at)
        recovery?.cancel()
        recovery = scope.launch {
            var again = false
            try {
                delay(RECOVERY_WAIT_MS * tries)
                val stream = streamFor(idOf(track), fresh = true).copy(cacheKey = keyOf(track))
                handing.withLock {
                    if (gen != generation.get() || current !== track) return@withLock
                    synchronized(publishing) { reopenedAt = at; fromDisk = false }
                    player.play(stream.withDurationOf(track), at)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation.get()) {
                    if (recoveries < MAX_RECOVERIES) {
                        again = true
                    } else {
                        recovering = false
                        synchronized(publishing) { failOnce(track, at, e.message ?: "${track.provider.label} could not play this song") }
                    }
                }
            } finally {
                if (gen == generation.get() && !again) recovering = false
            }
            if (again) {
                recovering = false
                synchronized(publishing) { recover(track, at) }
            }
        }
    }

    suspend fun play(track: Track, startMs: Long) {
        val gen = generation.incrementAndGet()
        recovery?.cancel()
        recovering = false
        recoveries = 0
        pausedAt = 0L
        var playing = false
        synchronized(publishing) {
            resolving = true
            endedFor = null
            lastError = null
            sounded = false
            fromKept = false
            fromDisk = false
            reopenedAt = -1L
            steadySince = 0L
            failedThisPlay = false
            current = track
        }
        try {
            val at = startMs.coerceAtLeast(0L)
            _snapshot.value = PlayerSnapshot(
                track = track,
                isPlaying = false,
                isBuffering = true,
                positionMs = at,
                durationMs = track.durationMs,
                canSeek = player.canSeek,
                canSkip = true,
                volume = volume,
            )
            val id = idOf(track)
            val key = keyOf(track)
            // A song kept on disk needs no lookup and no network: it starts at once.
            val offline = cache?.offline(key)
            val (stream, kept) = if (offline != null) offline to true else try {
                streamOf(id).let { (s, k) -> s.copy(cacheKey = key) to k }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation.get()) return
                // The previous stream is still running: the new one never replaced it. Its ticks
                // would be published under the failed song (moving it, claiming it is playing) the
                // moment [resolving] drops, so it is stopped before the failure becomes the state.
                runCatching { player.stop() }
                failed(track, at, e.message ?: "${track.provider.label} could not play this song")
                throw e
            }
            // Overtaken while the address was found: the newer play has the player.
            if (gen != generation.get()) return
            try {
                handing.withLock {
                    if (gen != generation.get()) return
                    synchronized(publishing) { fromKept = kept; fromDisk = offline != null }
                    try {
                        player.play(stream.withDurationOf(track, fromCache = offline != null), at)
                        if (offline == null && mayCache(track, stream)) cache?.keep(key, stream, playing = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A kept address can have expired since: once more, freshly resolved.
                        if (!kept) throw e
                        if (offline != null) cache?.forget(key)
                        synchronized(publishing) { fromKept = false; fromDisk = false }
                        val fresh = streamFor(id, fresh = true).copy(cacheKey = key)
                        player.play(fresh.withDurationOf(track), at)
                        if (mayCache(track, fresh)) cache?.keep(key, fresh, playing = true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(track, at, e.message ?: "${track.provider.label} could not play this song")
                throw e
            }
            playing = true
        } finally {
            if (gen == generation.get()) {
                synchronized(publishing) {
                    resolving = false
                    // The sink's first state was emitted while the flag was up: apply it now instead
                    // of leaving the UI on the "buffering" snapshot until the next tick.
                    if (playing && current === track) publish(player.state.value)
                }
            }
        }
    }

    /** A cache entry can omit metadata; keep the catalogue's length at the sink too. */
    private fun ResolvedStream.withDurationOf(track: Track, fromCache: Boolean = false): ResolvedStream {
        val timed = if (durationMs > 0 || track.durationMs <= 0) this else copy(durationMs = track.durationMs)
        return if (fromCache || mayCache(track, timed)) timed else timed.copy(cacheKey = null)
    }

    private fun mayCache(track: Track, stream: ResolvedStream): Boolean =
        maxOf(track.durationMs, stream.durationMs) <= MAX_CACHE_DURATION_MS

    /** Looks the song likely next up ahead, and has the disk keep it, so it starts without a wait. */
    suspend fun prepare(track: Track) {
        if (track.durationMs > MAX_CACHE_DURATION_MS) return
        val key = keyOf(track)
        if (cache?.has(key) == true) return
        val stream = try {
            streamOf(idOf(track)).first
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        if (mayCache(track, stream)) cache?.keep(key, stream.copy(cacheKey = key), playing = false)
    }

    /**
     * A song likely to be picked: its address is looked up (and kept for a few minutes), nothing
     * fetched. A song kept whole, or one whose address is still fresh, costs nothing.
     */
    suspend fun warm(track: Track) {
        if (cache?.has(keyOf(track)) == true) return
        try {
            streamOf(idOf(track))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** Whether the whole song is on disk, so it plays with no lookup and no network. */
    fun isCached(track: Track): Boolean = cache?.has(keyOf(track)) == true

    suspend fun pause() {
        pausedAt = clock()
        player.pause()
    }

    suspend fun resume() {
        val track = current
        val since = pausedAt
        pausedAt = 0L
        val s = player.state.value
        // Nothing is open under the song (it failed, or the player was stopped): Play tries it again
        // where it was, instead of doing nothing. Out of the caller's way: finding it takes a while.
        // The same after a failure or the end: the sink holds a stream that will not play again.
        if (track != null && (!s.hasStream || s.error != null || s.ended) && !resolving && !recovering) {
            val at = if (s.ended) 0L else (_snapshot.value?.positionMs ?: 0L)
            scope.launch {
                try {
                    play(track, at)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Said already: play() reports its own failure.
                }
            }
            return
        }
        // After a long pause the stream's connection has likely been closed under it: the song is
        // opened again where it was rather than woken to find its connection gone.
        if (track != null && since > 0 && clock() - since > LONG_PAUSE_MS && s.hasStream && !s.ended) {
            val gen = generation.get()
            val at = s.positionMs
            _snapshot.value = _snapshot.value?.copy(isPlaying = true, isBuffering = true)
            val offline = cache?.offline(keyOf(track))
            val stream = offline
                ?: runCatching { streamOf(idOf(track)).first.copy(cacheKey = keyOf(track)) }.getOrNull()
            if (stream != null && gen == generation.get()) {
                try {
                    handing.withLock { if (gen == generation.get()) player.play(stream.withDurationOf(track, fromCache = offline != null), at) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // The reopen did not take (Android's player throws for a stream that will not
                    // open): the whole play, which looks the song up afresh and reports failures.
                    scope.launch { runCatching { play(track, at) } }
                }
                return
            }
        }
        player.resume()
    }

    suspend fun seek(positionMs: Long) {
        if (player.canSeek) player.seek(positionMs.coerceAtLeast(0L))
    }

    /** Whether the player can take sound effects (it needs FFmpeg's ffplay). */
    val takesEffects: Boolean get() = player.takesEffects

    suspend fun setEffects(effects: app.valen.core.SoundEffects) = player.setEffects(effects)

    suspend fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        player.setVolume(volume)
    }

    /** Applies to the next song: a stream is resolved at the quality the app asks for. */
    suspend fun setQuality(value: AudioQuality) {
        quality = value
    }

    suspend fun stop() {
        generation.incrementAndGet()
        recovery?.cancel()
        recovering = false
        current = null
        lastError = null
        endedFor = null
        pausedAt = 0L
        _snapshot.value = null
        handing.withLock { player.stop() }
    }

    suspend fun release() {
        generation.incrementAndGet()
        recovery?.cancel()
        current = null
        _snapshot.value = null
        player.release()
    }

    /** The id a song is resolved by: its uri's last part when it has one. */
    /** The song's key in [cache]: its service and id, never its (expiring) address. */
    private fun keyOf(track: Track): String = "${track.provider.name.lowercase()}-${idOf(track)}"

    private fun idOf(track: Track): String = idFor?.invoke(track)
        ?: track.uri?.substringAfterLast(':')?.takeIf { it.isNotBlank() } ?: track.id

    /** A kept address while it is fresh, else a new one; and whether it was kept. */
    private suspend fun streamOf(id: String): Pair<ResolvedStream, Boolean> {
        streams["$id@$quality"]?.let { (stream, at) ->
            if (clock() - at < STREAM_KEEP_MS) return stream to true
        }
        return streamFor(id, fresh = true) to false
    }

    private suspend fun streamFor(id: String, fresh: Boolean): ResolvedStream {
        if (!fresh) streamOf(id).let { return it.first }
        val key = "$id@$quality"
        val mine = kotlinx.coroutines.CompletableDeferred<ResolvedStream>()
        val other = synchronized(lookups) {
            lookups[key]?.takeIf { it.isActive } ?: run { lookups[key] = mine; null }
        }
        if (other != null) {
            try {
                return other.await()
            } catch (e: CancellationException) {
                // The one who asked first gave up (a warm-up replaced by another): ask here instead,
                // unless it is this caller that is being cancelled.
                kotlinx.coroutines.currentCoroutineContext()[Job]?.let { if (!it.isActive) throw e }
                return resolveOwn(id, key)
            }
        }
        val stream = try {
            resolve(id, quality).also { mine.complete(it) }
        } catch (e: CancellationException) {
            mine.cancel(e)
            throw e
        } catch (e: Exception) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            synchronized(lookups) { if (lookups[key] === mine) lookups.remove(key) }
        }
        rememberStream(key, stream)
        return stream
    }

    /** A look-up of this caller's own, kept like any other. */
    private suspend fun resolveOwn(id: String, key: String): ResolvedStream {
        val stream = resolve(id, quality)
        rememberStream(key, stream)
        return stream
    }

    private fun rememberStream(key: String, stream: ResolvedStream) {
        synchronized(streams) {
            streams[key] = stream to clock()
            while (streams.size > STREAMS_KEPT) {
                streams.entries.minByOrNull { it.value.second }?.let { streams.remove(it.key) }
            }
        }
    }

    private fun failed(track: Track, at: Long, message: String) {
        lastError = message
        _snapshot.value = PlayerSnapshot(
            track = track,
            isPlaying = false,
            isBuffering = false,
            positionMs = at,
            durationMs = track.durationMs,
            canSeek = player.canSeek,
            canSkip = true,
            volume = volume,
            error = message,
        )
        _events.tryEmit(ProviderEvent.Failed(message, track.key))
    }

    companion object {
        /** An end more than this before the song's length is a dropped stream, not the end. */
        const val EARLY_END_MS = 4_000L

        /** A reopen this close to the stated end that makes no sound is the song running out. */
        const val REOPEN_END_MS = 15_000L

        /** Steady sound this long and the song's drops are forgotten (a long song may drop again later). */
        const val STEADY_MS = 10_000L

        /** How many times a dropped song is opened again before its failure is reported. */
        const val MAX_RECOVERIES = 3

        /** The wait before opening a dropped song again, times the attempt. */
        const val RECOVERY_WAIT_MS = 600L

        /** How long a resolved address is trusted (they are signed and expire). */
        const val STREAM_KEEP_MS = 8 * 60_000L

        /** Paused longer than this, a song is opened again on resume. */
        const val LONG_PAUSE_MS = 2 * 60_000L

        /**
         * Addresses kept at once: the songs around the queue's place and the ones warmed by a
         * pointer or a finger (a dozen a minute at most), so a warm never pushes the next song's
         * address out. Each is a short string.
         */
        private const val STREAMS_KEPT = 32
        private const val MAX_CACHE_DURATION_MS = 60 * 60_000L
    }
}
