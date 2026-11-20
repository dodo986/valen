/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ProviderSearchResult(
    val provider: ProviderId,
    val tracks: List<Track> = emptyList(),
    val error: String? = null,
)

/** One song in the unified list, with the other services that list the same recording. */
data class SearchHit(
    val track: Track,
    val alsoOn: List<ProviderId> = emptyList(),
)

/** The merged answer to one query: each recording once, plus whichever services failed. */
data class UnifiedSearch(
    val hits: List<SearchHit> = emptyList(),
    val errors: Map<ProviderId, String> = emptyMap(),
) {
    val tracks: List<Track> get() = hits.map { it.track }
}

/**
 * The one notice that is a confirmation rather than a provider's message: the UI shows it as a
 * brief nod and lets it leave, where a failure stays until it is dismissed.
 */
const val NOTICE_QUEUED = "Added to queue"

/**
 * Owns the one queue and the one visible player. Providers are interchangeable behind it: the
 * queue asks the active adapter to play a single song and never cares which service that is.
 */
class PlaybackCoordinator(
    private val providers: List<MusicProvider>,
    private val scope: CoroutineScope,
    private val settingsStore: SettingsStore,
    initial: AppSettings = settingsStore.load(),
    /** Counts listening for the profile's stats; none in tests that do not ask for it. */
    val stats: ListeningStats = ListeningStats(ListeningStore.None),
    /**
     * The clock the coordinator reads: the sleep timer, the listening tick, a start's stale window
     * and the session save; a test's own in tests.
     */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** The words of the songs that play (Lyprio, then LRCLIB); none in tests that do not ask. */
    val lyrics: LyricsBook = LyricsBook.None,
    /**
     * Playlists kept on this device (the desktop's downloads); null where songs are not kept. A
     * kept song plays from its file through the [ProviderId.LOCAL] adapter, whatever service the
     * queue names it on, so it plays offline and signed out.
     */
    val offline: OfflineShelf? = null,
    /** The link to Discord that shows what plays; null where there is none (Android, tests). */
    val presence: PresenceLink? = null,
    /** This build's version, as Settings and About name it. */
    val version: String = "dev",
    /** New releases from GitHub, and installing them; null where there are none (tests). */
    val updates: Updates? = null,
) {
    /** The playlist the current queue was started from, for the stats; null for anything else. */
    private var playingFrom: String? = null
    /**
     * The queue is a list Valen made (a mix, a radio, "For you"): the listening it gets is counted
     * apart, so the taste never mistakes what the algorithm offered for what the person chose.
     */
    @Volatile private var queueGenerated = false

    /**
     * Songs in the queue that Valen put there to keep it going ([AppSettings.keepPlaying]), by
     * key: like a generated list's, their listening counts as offered, not chosen.
     */
    private val offered: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var lastTickAt = 0L

    /** The list's own order while shuffle has it in another; null when not shuffled. */
    private var unshuffled: List<Track>? = null

    /**
     * Where a restored song picks up: set at start from the last session, used by the first play
     * (the restored song is shown paused, nothing plays until asked).
     */
    private var resumeAtMs: Long? = null
    private var sessionSavedAt = 0L

    /** The effects on their way to the player: one apply at a time, the latest wins. */
    private var effectsJob: Job? = null

    /** One command decides at a time: MPRIS calls come in on other threads than the window's. */
    private val commands = Mutex()

    /** Guards the queue and the index, which commands and queue edits both change. */
    private val queueLock = Any()

    /** One song starts at a time; a start overtaken before its turn does nothing. */
    private val starting = Mutex()
    @Volatile private var startGen = 0L
    private var startJob: Job? = null
    private var prepareJob: Job? = null

    /** [previous] decided to go back to 0:00 of this song rather than to the one before. */
    @Volatile private var restart = false

    private val byId = providers.associateBy { it.id }

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index.asStateFlow()

    private val _active = MutableStateFlow<ProviderId?>(null)
    val active: StateFlow<ProviderId?> = _active.asStateFlow()

    private val _snapshot = MutableStateFlow<PlayerSnapshot?>(null)
    val snapshot: StateFlow<PlayerSnapshot?> = _snapshot.asStateFlow()

    /**
     * [snapshot] without its moving position (always 0 here): it changes only when the song, its
     * state or its length does. Everything but the widgets that show the time should read this —
     * the position moves four times a second, and a screen that collected the whole snapshot was
     * composed again at every move.
     */
    val nowPlaying: StateFlow<PlayerSnapshot?> = _snapshot
        .map { it?.copy(positionMs = 0L) }
        .stateIn(scope, SharingStarted.Eagerly, _snapshot.value?.copy(positionMs = 0L))

    private val _settings = MutableStateFlow(initial)
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Whether [notice] is a confirmation that leaves by itself, rather than a message to read. */
    private val _confirmation = MutableStateFlow(false)
    val noticeIsConfirmation: StateFlow<Boolean> = _confirmation.asStateFlow()

    private val _sleep = MutableStateFlow<SleepTimer?>(null)

    /** The sleep timer, while one is set. */
    val sleep: StateFlow<SleepTimer?> = _sleep.asStateFlow()

    val sessions: StateFlow<Map<ProviderId, SessionState>> =
        combine(providers.map { it.session }) { states ->
            providers.mapIndexed { i, p -> p.id to states[i] }.toMap()
        }.stateIn(scope, SharingStarted.Eagerly, providers.associate { it.id to SessionState.SignedOut })

    private var lastRaw: PlayerSnapshot? = null
    private var lastRawAt = 0L

    /**
     * The song the service that is starting a new one still showed when it was asked to: its own
     * last state, which can be the song before or one it played long ago. Until the service shows
     * something else (at most [STALE_SHOW_MS]) the tick does not put that song back on show over
     * the new one. Copying it used to flip the screen old → new → old on a skip, and through songs
     * nobody had asked for when the service changed.
     */
    @Volatile private var staleKey: String? = null
    @Volatile private var staleSince = 0L

    /**
     * The song the active service was asked to play, as Valen knows it. While the service plays
     * it — the same id, or the same recording under an id of the service's own (Spotify swaps in a
     * regional copy) — this is what is shown, not the service's own description of it: the Web
     * Player describes a song with a cover of another size, which made the same song arrive twice
     * on screen (once starting, once loaded). Gaps (no cover, no album) are filled from the service.
     */
    @Volatile private var asked: Track? = null

    init {
        initial.session?.let { saved ->
            val at = saved.index.coerceIn(0, (saved.queue.size - 1).coerceAtLeast(0))
            val song = saved.queue.getOrNull(at)
            if (song != null) {
                _queue.value = saved.queue
                _index.value = at
                playingFrom = saved.from
                queueGenerated = saved.generated
                offered.addAll(saved.offered)
                unshuffled = saved.unshuffled?.takeIf { initial.shuffle }
                resumeAtMs = saved.positionMs.coerceAtLeast(0)
                _snapshot.value = PlayerSnapshot(
                    track = song,
                    isPlaying = false,
                    positionMs = saved.positionMs.coerceAtLeast(0),
                    durationMs = song.durationMs,
                    volume = initial.volume,
                )
                // The restored song's stream is found now, out of the way, so Play starts it at once.
                scope.launch {
                    delay(RESTORE_PREPARE_MS)
                    if (_active.value != null || _queue.value.getOrNull(_index.value)?.key != song.key) return@launch
                    val provider = targetFor(song)?.takeIf { canPlayNow(song) } ?: return@launch
                    try {
                        provider.prepare(song)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Only a head start: Play resolves it again.
                    }
                }
            }
        }
        providers.forEach { provider ->
            scope.launch {
                provider.events.collect { event ->
                    if (_active.value != provider.id) return@collect
                    when (event) {
                        is ProviderEvent.TrackEnded -> {
                            // A late end (the song the app already left) is not the end of this one.
                            // The event names its song; the service's own state is only the fallback
                            // (Spotify reports an end once its page is already on another song).
                            val ended = event.key ?: provider.player.value?.track?.key
                            val current = _queue.value.getOrNull(_index.value)
                            if (ended == null || current == null || ended == current.key) next(auto = true)
                        }
                        is ProviderEvent.Failed -> {
                            say(event.message)
                            // The song on show failed for good: the music goes on with the next one
                            // (it used to stop here, waiting for a press).
                            val current = _queue.value.getOrNull(_index.value)
                            if (current != null && (event.key == null || event.key == current.key)) moveOnAfterFailure(startGen)
                        }
                        ProviderEvent.SessionLost -> say("${provider.id.label} session expired")
                    }
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(250)
                (_sleep.value as? SleepTimer.At)?.let { timer ->
                    if (clock() >= timer.endsAt) {
                        _sleep.value = null
                        _active.value?.let { runCatching { byId[it]?.pause() } }
                        saveSession()
                        confirm("Paused by the sleep timer")
                    }
                }
                val id = _active.value ?: continue
                val provider = byId[id] ?: continue
                val raw = provider.player.value ?: continue
                staleKey?.let { stale ->
                    // The service has not moved to the new song yet: keep the new one on show.
                    if (raw.track?.key == stale && clock() - staleSince < STALE_SHOW_MS) continue
                    staleKey = null
                }
                // Sound from the song on show: the run of failures (see [moveOnAfterFailure]) is over.
                if (raw.isPlaying && !raw.isBuffering && raw.error == null && raw.positionMs > 0) failuresInARow = 0
                val now = clock()
                if (raw != lastRaw) {
                    lastRaw = raw
                    lastRawAt = now
                }
                val rate = playbackRate(provider)
                val elapsed = if (raw.isPlaying && !raw.isBuffering) ((now - lastRawAt).coerceAtLeast(0) * rate).toLong() else 0L
                val position = (raw.positionMs + elapsed).coerceAtMost(raw.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
                _snapshot.value = raw.copy(track = asShown(raw.track), positionMs = position, volume = _settings.value.volume)
                // Only time the song really played counts: not paused, not buffering, and never
                // more than a couple of seconds for one tick (a suspended machine is not listening).
                // Private mode stops the counting, not the music.
                val heard = raw.track
                if (heard != null && raw.isPlaying && !raw.isBuffering && lastTickAt > 0 && lent == null &&
                    !_settings.value.privateMode
                ) {
                    stats.count(heard, (now - lastTickAt).coerceIn(0, 2_000), playingFrom, queueGenerated || heard.key in offered)
                }
                lastTickAt = now
                // The place in the song reaches disk now and then, so a crash loses seconds, not the song.
                if (raw.isPlaying && now - sessionSavedAt >= SESSION_SAVE_MS) saveSession()
            }
        }
    }

    /** [playing] as the screen shows it: the song Valen asked for while it is that song (see [asked]). */
    private fun asShown(playing: Track?): Track? {
        val mine = asked ?: return playing
        if (playing == null) return null
        val same = playing.key == mine.key ||
            (playing.provider == mine.provider && recordingKey(playing.title, playing.artist) == recordingKey(mine.title, mine.artist))
        if (!same) return playing
        return mine.copy(
            artworkUrl = mine.artworkUrl ?: playing.artworkUrl,
            album = mine.album ?: playing.album,
            durationMs = mine.durationMs.takeIf { it > 0 } ?: playing.durationMs,
        )
    }

    fun provider(id: ProviderId): MusicProvider? = byId[id]

    /**
     * The streaming services this build has, in their usual order: what sign-ins, the welcome and
     * Settings list. Android leaves Spotify out for now, so it is not offered there at all.
     */
    val services: List<ProviderId> = ProviderId.services.filter { it in byId }

    suspend fun connect(id: ProviderId) {
        runCatching { byId[id]?.connect() }.onFailure { say(it.message ?: "Could not connect") }
    }

    suspend fun disconnect(id: ProviderId) {
        byId[id]?.let { provider ->
            if (_active.value == id) runCatching { provider.pause() }
            runCatching { provider.disconnect() }
        }
    }

    suspend fun search(query: String, enabled: Set<ProviderId> = byId.keys): List<ProviderSearchResult> {
        if (query.isBlank()) return emptyList()
        return coroutineScope {
            enabled.mapNotNull { byId[it] }.map { provider ->
                async {
                    runCatching { provider.search(query, 25) }
                        .fold(
                            // A service often lists the same recording twice (an album and a
                            // single upload): one row per title and artist keeps the list honest.
                            onSuccess = { ProviderSearchResult(provider.id, it.distinctRecordings()) },
                            onFailure = { ProviderSearchResult(provider.id, error = it.message ?: "Unavailable") },
                        )
                }
            }.awaitAll()
        }
    }

    /**
     * One query, one list. Every enabled service answers in parallel; the answers are merged into
     * a single list where a recording appears once, no matter how many services have it. The copy
     * that is kept — and therefore played — comes from the service highest in [priority], and the
     * others are reported as [SearchHit.alsoOn] so the row can say so.
     *
     * Rows are ordered by how well they matched (a song both services put first beats one only one
     * service found deep in its list), with the user's order breaking ties. A service that fails
     * is left out of the list and reported in [UnifiedSearch.errors]: one refusal never hides the
     * other services' answers.
     */
    suspend fun unifiedSearch(
        query: String,
        enabled: Set<ProviderId> = byId.keys,
        priority: List<ProviderId> = _settings.value.priority,
    ): UnifiedSearch {
        if (query.isBlank()) return UnifiedSearch()
        // A service that cannot play signed out is asked only when it is the one chosen: its copies
        // could not play, and asking it starts its whole web player (Chrome on the desktop).
        val order = priorityOrder(priority).filter { it in enabled }
            .filter { id -> enabled.size == 1 || byId[id]?.let { it.playsSignedOut || it.session.value is SessionState.Connected } == true }
        if (order.isEmpty()) return UnifiedSearch()

        val answers = coroutineScope {
            order.map { id ->
                async { id to runCatching { byId.getValue(id).search(query, 25) } }
            }.awaitAll()
        }

        val errors = LinkedHashMap<ProviderId, String>()
        // recording key -> every service's copy of it, with where that service ranked it
        val copies = LinkedHashMap<String, MutableList<Copy>>()
        for ((id, answer) in answers) {
            val tracks = answer.getOrElse { error ->
                errors[id] = error.message ?: "Unavailable"
                emptyList()
            }
            val rank = order.indexOf(id)
            tracks.distinctRecordings().forEachIndexed { position, track ->
                copies.getOrPut(recordingKey(track.title, track.artist)) { mutableListOf() }
                    .add(Copy(track, position, rank))
            }
        }

        val ranked = copies.values.mapNotNull { group ->
            // A copy that can play now wins over one that cannot (a signed-out Spotify), then the
            // person's order, then the service's own rank.
            val preferred = group.minWith(compareBy({ !canPlayNow(it.track) }, { it.providerRank }, { it.position }))
            // The best place any service put the song, so a great match is not buried because the
            // preferred service ranked its own copy lower.
            val position = group.minOf { it.position }
            val alsoOn = group.map { it.track.provider }
                .filter { it != preferred.track.provider }
                .distinct()
            position to SearchHit(preferred.track, alsoOn)
        }
        val hits = ranked
            .sortedWith(compareBy({ it.first }, { !canPlayNow(it.second.track) }, { order.indexOf(it.second.track.provider) }))
            .map { it.second }
            .take(MAX_UNIFIED_RESULTS)
        return UnifiedSearch(hits, errors)
    }

    /**
     * The service order actually used: the files on this device first (a song you have is the copy
     * that plays), then the user's order, cleaned, then any service missing appended.
     */
    fun priorityOrder(priority: List<ProviderId> = _settings.value.priority): List<ProviderId> {
        val seen = LinkedHashSet<ProviderId>(ProviderId.entries.size)
        if (ProviderId.LOCAL in byId) seen += ProviderId.LOCAL
        priority.forEach { if (it in byId) seen += it }
        ProviderId.entries.forEach { if (it in byId) seen += it }
        return seen.toList()
    }

    /** Reorders the services: the first one wins a song that more than one of them has. */
    /**
     * Forgets what Valen keeps of this person: playback stops, the queue empties, the settings go
     * back to their first-start values (so the welcome shows again) and the listening counted is
     * cleared. Playlists are the library's and pictures are files: the caller clears those. Service
     * sign-ins are the browser's and stay.
     */
    suspend fun clearData() {
        startGen++
        startJob?.cancel()
        prepareJob?.cancel()
        _active.value?.let { id -> runCatching { byId[id]?.pause() } }
        _active.value = null
        _queue.value = emptyList()
        _index.value = -1
        _snapshot.value = null
        playingFrom = null
        queueGenerated = false
        offered.clear()
        forgetKeepPlaying()
        unshuffled = null
        resumeAtMs = null
        radioFrom = null
        _sleep.value = null
        _settings.value = AppSettings()
        soundState.value = SoundEffects()
        settingsStore.save(_settings.value)
        stats.clear()
    }

    fun setOnboarded(done: Boolean = true) = updateSettings { it.copy(onboarded = done) }

    fun setFavourites(artists: List<String>, songs: List<Track>) =
        updateSettings { it.copy(favouriteArtists = artists.distinctBy(Taste::artistKey).take(50), favouriteSongs = songs.distinctBy { s -> s.key }.take(50)) }

    /** The taste as it stands now: what was said, what was played and skipped, what was refused. */
    fun taste(): Taste = Taste(
        log = stats.log.value,
        favouriteArtists = _settings.value.favouriteArtists,
        favouriteSongs = _settings.value.favouriteSongs,
        dislikedArtists = _settings.value.dislikedArtists,
        hiddenTracks = _settings.value.hiddenTracks,
    )

    /**
     * Builds song lists from the taste, searching every service and the Spotify follows; new artists
     * come from the ones the artist pages call related (kept pages first, so a page already seen
     * costs nothing). A related artist is carried as the service's own id, and its songs are read
     * from that exact page: a different artist with the same name on another service can never
     * stand in for it.
     */
    fun mixer(): SmartMixer = SmartMixer(
        search = { query -> unifiedSearch(query).tracks },
        follows = { (byId[ProviderId.SPOTIFY] as? ArtistFollows)?.followedArtists().orEmpty() },
        related = { name ->
            val page = artists.kept(name) ?: runCatching { artists.open(name) }.getOrNull()
            page?.related.orEmpty().mapIndexedNotNull { rank, artist ->
                val copy = artist.copies.firstOrNull() ?: return@mapIndexedNotNull null
                RelatedArtist(artist.name, copy.provider, copy.id, rank)
            }
        },
        relatedSongs = { artist ->
            runCatching { artists.profile(artist.provider, artist.id).topTracks }.getOrDefault(emptyList())
        },
        artistTop = { name ->
            runCatching { (artists.kept(name) ?: artists.open(name)).popular }.getOrDefault(emptyList())
        },
    )

    /**
     * The song menu's "Less like this": the artists this song credits are left out of everything
     * Valen makes for the person, until it is undone here (or from another song of theirs).
     */
    fun isDisliked(track: Track): Boolean {
        val disliked = _settings.value.dislikedArtists.mapTo(HashSet(), Taste::artistKey)
        return Taste.creditsOf(track.artist).any { it in disliked }
    }

    /** Flips [isDisliked]: returns true when the artists are now left out. */
    fun toggleDisliked(track: Track): Boolean {
        val keys = Taste.creditsOf(track.artist).toSet()
        val was = isDisliked(track)
        updateSettings { settings ->
            val kept = settings.dislikedArtists.filterNot { Taste.artistKey(it) in keys }
            settings.copy(
                dislikedArtists = (if (was) kept else kept + artistsIn(track.artist))
                    .distinctBy(Taste::artistKey).take(200),
            )
        }
        confirm(if (was) "Valen may recommend them again" else "Less like this")
        return !was
    }

    /** Whether this very upload is never to be recommended again ("Don't recommend this song"). */
    fun isHiddenFromMixes(track: Track): Boolean = track.key in _settings.value.hiddenTracks

    /** Flips [isHiddenFromMixes]: returns true when the song is now left out. */
    fun toggleHiddenFromMixes(track: Track): Boolean {
        val was = isHiddenFromMixes(track)
        updateSettings { settings ->
            settings.copy(
                hiddenTracks = if (was) settings.hiddenTracks - track.key else (settings.hiddenTracks + track.key).takeLast(500),
            )
        }
        confirm(if (was) "This song may be recommended again" else "This song will not be recommended")
        return !was
    }

    /** Black and pink (false), or the colour of the cover that is playing (true). */
    fun setCoverColour(on: Boolean) = updateSettings { it.copy(coverColour = on) }

    fun setProfileName(name: String) = updateSettings { it.copy(profileName = name.take(40)) }

    /** A picture already copied into Valen's own folder, as a file URI; null removes it. */
    fun setAvatar(uri: String?) = updateSettings { it.copy(avatar = uri) }

    fun setBanner(uri: String?) = updateSettings { it.copy(banner = uri) }

    private fun updateSettings(change: (AppSettings) -> AppSettings) {
        // One atomic step: a change made at the same moment elsewhere (a song starting writes the
        // last service) is never lost, nor does it undo this one.
        val next = _settings.updateAndGet(change)
        settingsStore.save(next)
    }

    fun setPriority(priority: List<ProviderId>) {
        _settings.update { it.copy(priority = priorityOrder(priority)) }
        settingsStore.save(_settings.value)
    }

    /** One service's copy of a recording: the track, its place in that service's list, and rank. */
    private class Copy(val track: Track, val position: Int, val providerRank: Int)

    /**
     * Starts [track] inside [context] (the whole list becomes the queue). With shuffle on, the
     * chosen song plays first and the rest of the list follows in a shuffled order.
     *
     * [generated] says the list is one Valen made (a mix, a radio, "For you"): its listening is
     * counted apart from what the person chose. Any other play sets it back to false.
     */
    suspend fun play(track: Track, context: List<Track> = listOf(track), from: String? = null, generated: Boolean = false) {
        // A new queue is no radio: it is not refilled behind the person's back.
        radioFrom = null
        // A song whose service cannot play now (a signed-out Spotify) plays from a service that
        // can, when one has the same recording; otherwise the reason is said and the queue is left
        // as it was. Replacing the queue first left the old song on the stage with the tapped one
        // "up next", and nothing playing.
        if (!canPlayNow(track)) {
            val copy = playableCopy(track)
            // A service this build does not have (Spotify on Android, for now) is not "signed out".
            val absent = track.provider !in byId
            if (copy == null) {
                say(if (absent) "${track.provider.label} is not in this version of Valen, and no other service has this song" else "Sign in to ${track.provider.label} to play this")
                return
            }
            confirm(
                if (absent) "This plays from ${copy.provider.label}"
                else "${track.provider.label} is signed out, so this plays from ${copy.provider.label}",
            )
            play(copy, context.map { if (it.key == track.key) copy else it }, from, generated)
            return
        }
        val job = commands.withLock {
            synchronized(queueLock) {
                playingFrom = from
                queueGenerated = generated
                // A new queue: what was found to follow the old one does not follow this one.
                offered.clear()
                forgetKeepPlaying()
                val list = if (context.any { it.key == track.key }) context else context + track
                if (_settings.value.shuffle && list.size > 1) {
                    unshuffled = list
                    _queue.value = shuffledAround(list, track)
                    begin(0)
                } else {
                    unshuffled = null
                    _queue.value = list
                    begin(list.indexOfFirst { it.key == track.key }.coerceAtLeast(0))
                }
            }
        }
        job?.join()
    }

    /**
     * Shuffle on: the playing song stays where it is as the first of the new order, everything else
     * follows shuffled. Off: the list goes back to its own order (songs added meanwhile at the end),
     * still on the same song.
     */
    fun setShuffle(on: Boolean) {
        synchronized(queueLock) {
        if (_settings.value.shuffle == on) return
        val list = _queue.value
        val current = list.getOrNull(_index.value)
        if (on) {
            if (list.size > 1) {
                unshuffled = list
                _queue.value = if (current != null) shuffledAround(list, current) else list.shuffled()
                if (current != null) _index.value = 0
            }
        } else {
            unshuffled?.let { original ->
                val keys = list.mapTo(HashSet()) { it.key }
                val known = original.mapTo(HashSet()) { it.key }
                val restored = original.filter { it.key in keys } + list.filter { it.key !in known }
                _queue.value = restored
                if (current != null) _index.value = restored.indexOfFirst { it.key == current.key }
            }
            unshuffled = null
        }
        _settings.update { it.copy(shuffle = on) }
        saveSession()
        }
    }

    /** Off → all → one → off, the order every player uses. */
    fun cycleRepeat() = setRepeat(
        when (_settings.value.repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        },
    )

    fun setRepeat(mode: RepeatMode) {
        updateSettings { it.copy(repeat = mode) }
        // A queue that repeats never runs out; one that stops repeating may be near its end.
        synchronized(queueLock) { if (mode == RepeatMode.OFF) findMoreToKeepPlaying() else forgetKeepPlaying() }
    }

    /** [AppSettings.keepPlaying]: on, songs like the queue's end are found to follow it; off, it stops. */
    fun setKeepPlaying(on: Boolean) {
        updateSettings { it.copy(keepPlaying = on) }
        synchronized(queueLock) { if (on) findMoreToKeepPlaying() else forgetKeepPlaying() }
    }

    /**
     * Songs like the last ones in the queue, found while its last songs play, to follow it when it
     * runs out ([AppSettings.keepPlaying]). They are not in the queue yet: a song added now still
     * goes before them, and the queue page shows them under it. Empty while there is no end near,
     * the queue repeats, or a radio refills itself anyway.
     */
    private val _keepPlaying = MutableStateFlow<List<Track>>(emptyList())
    val keepPlaying: StateFlow<List<Track>> = _keepPlaying.asStateFlow()
    private var keepPlayingJob: Job? = null

    /** The end of the queue [keepPlaying] was found for (its last songs' keys); another end makes it stale. */
    private var keepPlayingFor: String? = null

    /**
     * Looks for songs to follow the queue when its end is near. Like a radio's refill: the artists
     * of its last songs and those related to them, scored by the taste, none already queued.
     * Called under [queueLock].
     */
    private fun findMoreToKeepPlaying() {
        val settings = _settings.value
        if (!settings.keepPlaying || settings.repeat != RepeatMode.OFF || radioFrom != null || lent != null) return
        val list = _queue.value
        if (list.isEmpty() || _index.value !in list.indices) return
        if (list.size - 1 - _index.value > KEEP_PLAYING_LEFT) {
            // Songs were added since: the end they were found for is far off again.
            forgetKeepPlaying()
            return
        }
        val end = list.takeLast(RADIO_SEED_SONGS)
        val endKey = end.joinToString("|") { it.key }
        if (endKey == keepPlayingFor) return
        keepPlayingJob?.cancel()
        keepPlayingFor = endKey
        _keepPlaying.value = emptyList()
        val artists = end.flatMap { artistsIn(it.artist) }.distinctBy(Taste::artistKey)
        if (artists.isEmpty()) return
        keepPlayingJob = scope.launch {
            val spec = SmartSpec(sources = setOf(SmartSource.Artists), artists = artists, size = KEEP_PLAYING_SONGS, discovery = 0.6f)
            val found = runCatching { mixer().build(spec, taste()) }.getOrDefault(emptyList())
            synchronized(queueLock) {
                if (keepPlayingFor != endKey) return@launch
                val have = _queue.value.mapTo(HashSet()) { recordingKey(it.title, it.artist) }
                _keepPlaying.value = found
                    .filter { recordingKey(it.title, it.artist) !in have && canPlayNow(it) }
                    .distinctBy { recordingKey(it.title, it.artist) }
            }
        }
    }

    /** Drops what was found to follow the queue, and any search for it still on its way. */
    private fun forgetKeepPlaying() {
        keepPlayingJob?.cancel()
        keepPlayingJob = null
        keepPlayingFor = null
        _keepPlaying.value = emptyList()
    }

    /**
     * The queue has run out: the songs found to follow it join it, and the first plays. If they are
     * still being looked for, the last song waits on them and the music goes on once they are
     * found (or stops, if none are). Null when there is nothing to go on with. Called under
     * [queueLock].
     */
    private fun keepGoing(): Job? {
        if (!_settings.value.keepPlaying || _settings.value.repeat != RepeatMode.OFF || lent != null) return null
        if (_keepPlaying.value.isNotEmpty()) return joinKeepPlaying(at = 0)
        findMoreToKeepPlaying()
        val finding = keepPlayingJob?.takeIf { it.isActive } ?: return null
        val endKey = keepPlayingFor
        val queueThen = _queue.value
        val indexThen = _index.value
        return scope.launch {
            finding.join()
            val job = commands.withLock {
                synchronized(queueLock) {
                    // Only if nothing else was started meanwhile: the same queue, still at its end.
                    val still = _queue.value === queueThen && _index.value == indexThen && keepPlayingFor == endKey
                    if (!still || _keepPlaying.value.isEmpty()) null else joinKeepPlaying(at = 0)
                }
            }
            job?.join()
        }
    }

    /**
     * [keepPlaying] joins the end of the queue, offered rather than chosen, and the one at [at]
     * among them plays. Called under [queueLock].
     */
    private fun joinKeepPlaying(at: Int): Job? {
        val more = _keepPlaying.value
        if (at !in more.indices) return null
        val list = _queue.value
        more.forEach { offered += it.key }
        _queue.value = list + more
        unshuffled = unshuffled?.let { it + more }
        keepPlayingJob = null
        keepPlayingFor = null
        _keepPlaying.value = emptyList()
        return begin(list.size + at, skipUnplayable = true)
    }

    /** Leaves [track] out of what follows the queue (its row's remove, on the queue page). */
    fun dropFromKeepPlaying(track: Track) {
        synchronized(queueLock) { _keepPlaying.value = _keepPlaying.value.filterNot { it.key == track.key } }
    }

    /** A song of [keepPlaying] tapped on the queue page: they all join the queue, and it plays. */
    suspend fun playFromKeepPlaying(track: Track) {
        val job = commands.withLock {
            synchronized(queueLock) {
                val at = _keepPlaying.value.indexOfFirst { it.key == track.key }
                if (at < 0) null else joinKeepPlaying(at)
            }
        }
        job?.join()
    }

    /**
     * Writes the queue and the place in it to the settings, so the next start shows the same song,
     * paused, where it was left. Called on every change of queue or song, on pause, now and then
     * while playing, and by the platform as the app closes.
     */
    fun saveSession() {
        // While the player is lent, the session on disk stays the person's own.
        if (lent != null) return
        sessionSavedAt = clock()
        val list = _queue.value
        val at = _index.value
        val session = if (list.isEmpty() || at !in list.indices) null else SavedSession(
            queue = list,
            index = at,
            positionMs = _snapshot.value?.takeIf { it.track?.key == list[at].key }?.positionMs ?: 0L,
            from = playingFrom,
            unshuffled = unshuffled,
            generated = queueGenerated,
            offered = list.mapNotNull { t -> t.key.takeIf { it in offered } }.distinct(),
        )
        updateSettings { it.copy(session = session) }
    }

    /**
     * Makes [position] the song, at once: the index, the active service and the session say so
     * before anything plays. The playing itself is a job of its own, returned for the caller to wait
     * on. Starts go one at a time ([starting]); a start that a newer one overtook gives up before it
     * reaches its service, and every other service is silenced inside the same turn, so whatever the
     * order of a crowd of commands, one song sounds and it is the one the queue points at.
     *
     * Called under [queueLock].
     */
    private fun begin(from: Int, startMs: Long = 0L, skipUnplayable: Boolean = false): Job? {
        val list = _queue.value
        var at = from
        // Moving through the queue passes songs nothing can play right now (a signed-out Spotify);
        // a song asked for by name says why it will not play instead of staying silent.
        if (skipUnplayable) {
            while (at in list.indices && !canPlayNow(list[at])) at++
            if (at !in list.indices) {
                list.getOrNull(from)?.let { say("Sign in to ${it.provider.label} to play the rest of the queue") }
                return null
            }
        }
        val track = list.getOrNull(at) ?: return null
        val target = targetFor(track) ?: run {
            say("${track.provider.label} is not available")
            return null
        }
        if (!canPlayNow(track)) {
            say("Sign in to ${track.provider.label} to play this")
            return null
        }
        resumeAtMs = null
        val position = at
        val gen = ++startGen
        _index.value = position
        // What that service shows now is its last song, not this one: kept off the screen.
        staleKey = target.player.value?.track?.key?.takeIf { it != track.key }
        asked = track
        staleSince = clock()
        // The adapter that plays it: its own service's, or this device's when the song is kept here.
        _active.value = target.id
        _settings.update { it.copy(lastProvider = track.provider) }
        // The new song is on show at once, starting: never "nothing" between two songs. A null here
        // read as "the session is over" on Android, which ended its playback service at every song
        // change and, with the screen off, could not start it again for the next one.
        _snapshot.value = _snapshot.value?.takeIf { it.track?.key == track.key }?.copy(positionMs = startMs)
            ?: PlayerSnapshot(
                track = track,
                isPlaying = false,
                isBuffering = true,
                positionMs = startMs,
                durationMs = track.durationMs,
                volume = _settings.value.volume,
            )
        saveSession()
        lastRaw = null
        startJob?.cancel()
        prepareJob?.cancel()
        val job = scope.launch {
            starting.withLock {
                if (gen != startGen) return@withLock
                byId.values.filter { it.id != target.id }.forEach { runCatching { it.stop() } }
                try {
                    target.setVolume(_settings.value.volume)
                    if (target.effectSupport == EffectSupport.Full) runCatching { target.setEffects(_settings.value.sound.effective()) }
                    if (gen != startGen) return@withLock
                    target.play(track, startMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (gen == startGen) {
                        say(e.message ?: "${track.provider.label} could not play this")
                        moveOnAfterFailure(gen)
                    }
                }
            }
            if (gen == startGen) prepareNext(gen)
        }
        startJob = job
        return job
    }

    /**
     * The same recording from a service that can apply the effects (YouTube Music, SoundCloud),
     * swapped in for [track] where it stands in the queue and played from the same place. False
     * when no such copy is found (the reason is said).
     */
    suspend fun playWithEffects(track: Track): Boolean {
        val able = providers.filter { it.id != track.provider && it.effectSupport == EffectSupport.Full && (it.playsSignedOut || it.session.value is SessionState.Connected) }
        if (able.isEmpty()) {
            say("No service here can play it with effects")
            return false
        }
        val key = recordingKey(track.title, track.artist)
        val copy = runCatching { unifiedSearch("${track.title} ${track.artist}", able.map { it.id }.toSet()).tracks }
            .getOrDefault(emptyList())
            .firstOrNull { recordingKey(it.title, it.artist) == key }
        if (copy == null) {
            say("Not found on ${able.joinToString(" or ") { it.id.label }}")
            return false
        }
        val at = _snapshot.value?.takeIf { it.track?.key == track.key }?.positionMs ?: 0L
        val job = commands.withLock {
            synchronized(queueLock) {
                val list = _queue.value
                val i = list.indexOfFirst { it.key == track.key }.takeIf { it >= 0 } ?: _index.value
                if (i !in list.indices) {
                    _queue.value = listOf(copy)
                    begin(0, at)
                } else {
                    _queue.value = list.toMutableList().also { it[i] = copy }
                    unshuffled = unshuffled?.map { if (it.key == track.key) copy else it }
                    begin(i, at)
                }
            }
        }
        job?.join()
        return true
    }

    /** The same recording as [track] from another service that can play now, if one has it. */
    private suspend fun playableCopy(track: Track): Track? {
        val able = providers.filter { it.id != track.provider && (it.playsSignedOut || it.session.value is SessionState.Connected) }
        if (able.isEmpty()) return null
        val key = recordingKey(track.title, track.artist)
        return try {
            unifiedSearch("${track.title} ${track.artist}", able.map { it.id }.toSet()).tracks
                .firstOrNull { recordingKey(it.title, it.artist) == key }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Artist pages from every service that has them and can be used now (Spotify once signed in,
     * YouTube Music always), put together (see [ArtistDirectory]).
     */
    val artists: ArtistDirectory = ArtistDirectory(
        catalogs = {
            providers.filterIsInstance<ArtistCatalog>().filter { c ->
                byId[c.provider]?.let { it.playsSignedOut || it.session.value is SessionState.Connected } == true
            }
        },
        priority = { priorityOrder() },
        playable = { canPlayNow(it) },
    )

    /**
     * Whether [track] can play now: kept on this device, or its service can play it (one that needs
     * an account, only once signed in).
     */
    fun canPlayNow(track: Track): Boolean {
        if (isKept(track)) return true
        val provider = byId[track.provider] ?: return false
        return provider.playsSignedOut || provider.session.value is SessionState.Connected
    }

    /** Whether [track] is a downloaded copy on this device that the device's adapter can play. */
    fun isKept(track: Track): Boolean =
        track.provider != ProviderId.LOCAL && ProviderId.LOCAL in byId && offline?.has(track) == true

    /** The adapter that plays [track]: this device's for a kept song, else its own service's. */
    private fun targetFor(track: Track): MusicProvider? =
        if (isKept(track)) byId[ProviderId.LOCAL] else byId[track.provider]

    /** No song: whatever was starting gives up, the service lets go, nothing is shown playing. */
    private fun silence() {
        startGen++
        startJob?.cancel()
        prepareJob?.cancel()
        _active.value?.let { id -> byId[id]?.let { p -> scope.launch { runCatching { p.stop() } } } }
        _active.value = null
        _snapshot.value = null
    }

    /**
     * The next two songs, so a song ending or a skip (even two quick ones) starts without a wait:
     * the next is looked up and kept whole on disk ([MusicProvider.prepare]); the one after it is
     * only looked up ([MusicProvider.warm]), which costs one request and no bytes. A song kept on
     * this device needs neither.
     */
    private fun prepareNext(gen: Long) {
        refillRadio()
        synchronized(queueLock) { findMoreToKeepPlaying() }
        val list = _queue.value
        if (list.isEmpty()) return
        val at = _index.value
        val wraps = _settings.value.repeat == RepeatMode.ALL && list.size > 1
        fun ahead(n: Int): Track? = (at + n).let { i ->
            list.getOrNull(i) ?: if (wraps) list.getOrNull(i % list.size)?.takeIf { (i % list.size) != at } else null
        }
        val next = ahead(1) ?: return
        val then = ahead(2)?.takeIf { it.key != next.key }
        prepareJob = scope.launch {
            if (gen != startGen) return@launch
            targetFor(next)?.let { p -> runCatching { p.prepare(next) } }
            if (then == null || isKept(then) || gen != startGen) return@launch
            targetFor(then)?.let { p -> runCatching { p.warm(then) } }
        }
    }

    /** Songs warmed lately, by key, with when: each is looked up at most once in [WARM_KEEP_MS]. */
    private val warmed = LinkedHashMap<String, Long>()
    private var warmJob: Job? = null

    /**
     * The person is about to pick [track] (a pointer resting on its row, a finger pressing it, its
     * list opened): its address is looked up now, so the tap starts at once. Cheap and bounded —
     * at most one lookup in flight (a newer hint replaces an older one), each song once in a few
     * minutes, at most [WARM_PER_MINUTE] a minute — so scrolling past rows never floods a service.
     * Songs that need nothing (kept, cached, playing, unplayable) are skipped.
     */
    fun warm(track: Track) {
        val now = clock()
        if (_snapshot.value?.track?.key == track.key || isKept(track) || !canPlayNow(track)) return
        val provider = byId[track.provider] ?: return
        if (provider.isCached(track)) return
        synchronized(warmed) {
            warmed[track.key]?.let { if (now - it < WARM_KEEP_MS) return }
            if (warmed.values.count { now - it < 60_000L } >= WARM_PER_MINUTE) return
            warmed[track.key] = now
            if (warmed.size > 64) warmed.entries.iterator().let { it.next(); it.remove() }
        }
        warmJob?.cancel()
        warmJob = scope.launch { runCatching { provider.warm(track) } }
    }

    /** Whether [track] plays with no network: downloaded, or kept whole by its service's cache. */
    fun playsOffline(track: Track): Boolean =
        track.provider != ProviderId.LOCAL && (isKept(track) || byId[track.provider]?.isCached(track) == true)

    suspend fun toggle() {
        val job = commands.withLock {
            val id = _active.value ?: run {
                // Nothing has played yet this run: the song shown (restored from last time) starts
                // where it was left.
                return@withLock synchronized(queueLock) {
                    if (_queue.value.isNotEmpty()) begin(_index.value.coerceIn(0, _queue.value.lastIndex), resumeAtMs ?: 0L) else null
                }
            }
            val provider = byId[id] ?: return@withLock null
            val snap = provider.player.value
            // A service that fails here says so; it never takes the app down with it.
            try {
                if (snap?.isPlaying == true) {
                    provider.pause()
                    saveSession()
                } else if (snap?.isBuffering != true) {
                    provider.resume()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(e.message ?: "${provider.id.label} could not do that")
            }
            null
        }
        job?.join()
    }

    suspend fun next(auto: Boolean = false) {
        val job = commands.withLock { synchronized(queueLock) { decideNext(auto) } }
        job?.join()
    }

    /** What [next] does, decided under the locks: a job to wait on when a song starts. */
    private fun decideNext(auto: Boolean, failed: Boolean = false): Job? {
        // A song left in its first 30 s (or first 40 %) by hand counts against it in the taste,
        // unless private mode is on: then no skip is held against anything.
        val snap = _snapshot.value
        val leaving = snap?.track
        if (!auto && leaving != null && lent == null && !_settings.value.privateMode) {
            val early = minOf(30_000L, (snap.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE) * 2 / 5)
            if (snap.positionMs < early) stats.skip(leaving)
        }
        // A sleep timer set to the end of the song stops here: the next song waits, paused at its start.
        if (auto && _sleep.value == SleepTimer.EndOfSong) {
            _sleep.value = null
            val list = _queue.value
            val after = _index.value + 1
            val park = when {
                // Repeat one: the song that ended is the one waiting.
                !failed && _settings.value.repeat == RepeatMode.ONE && _index.value in list.indices -> _index.value
                after in list.indices -> after
                _settings.value.repeat != RepeatMode.OFF && list.isNotEmpty() -> 0
                else -> _index.value
            }
            val parked = parkAt(park)
            confirm("Paused by the sleep timer")
            return parked
        }
        // Repeat one replays the song when it ends by itself; a press of next still moves on, and so
        // does a song that failed (replaying it would only fail again).
        if (auto && !failed && _settings.value.repeat == RepeatMode.ONE && _index.value in _queue.value.indices) {
            return begin(_index.value)
        }
        val next = _index.value + 1
        if (next in _queue.value.indices && _queue.value.drop(next).any(::canPlayNow)) {
            return begin(next, skipUnplayable = true)
        } else if (_settings.value.repeat != RepeatMode.OFF && _queue.value.any(::canPlayNow)) {
            return begin(0, skipUnplayable = true)
        } else {
            // The end of the queue, unless songs like it are there to go on with.
            keepGoing()?.let { return it }
            // The service is told to stop where it is (waited on off the lock).
            if (auto) {
                _index.value = _queue.value.lastIndex.coerceAtLeast(0)
                _snapshot.value = _snapshot.value?.copy(isPlaying = false, positionMs = 0)
            }
            return _active.value?.let { id -> byId[id]?.let { p -> scope.launch { runCatching { p.pause() } } } }
        }
    }

    /**
     * The song started by [gen] failed: after a moment (so the reason can be read) the next one
     * plays, as if it had ended. Once per start; a run of failures as long as the queue (or
     * [MAX_FAILED_IN_A_ROW]) stops there, with the reason on show — every song failing is a
     * network or service problem that skipping cannot fix.
     */
    private fun moveOnAfterFailure(gen: Long) {
        if (failedGen == gen) return
        failedGen = gen
        failuresInARow++
        if (failuresInARow >= minOf(MAX_FAILED_IN_A_ROW, _queue.value.size)) {
            failuresInARow = 0
            return
        }
        scope.launch {
            delay(FAILED_MOVE_ON_MS)
            val job = commands.withLock {
                synchronized(queueLock) { if (gen != startGen) null else decideNext(auto = true, failed = true) }
            }
            job?.join()
        }
    }

    @Volatile private var failedGen = -1L
    @Volatile private var failuresInARow = 0

    suspend fun previous() {
        val job = commands.withLock {
            synchronized(queueLock) {
                val snap = _snapshot.value
                when {
                    snap != null && snap.positionMs > 3_000 -> null
                    _index.value > 0 -> begin(_index.value - 1)
                    _settings.value.repeat == RepeatMode.ALL && _queue.value.size > 1 -> begin(_queue.value.lastIndex)
                    else -> null
                }.also { if (it == null) restart = true }
            }
        }
        if (restart) {
            restart = false
            seek(0)
        }
        job?.join()
    }

    suspend fun seek(positionMs: Long) {
        val id = _active.value ?: run {
            // The restored song, not playing yet: move where it will start.
            if (resumeAtMs != null) {
                resumeAtMs = positionMs.coerceAtLeast(0)
                _snapshot.value = _snapshot.value?.copy(positionMs = positionMs.coerceAtLeast(0))
            }
            return
        }
        runCatching { byId[id]?.seek(positionMs) }
    }

    suspend fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        _settings.update { it.copy(volume = v) }
        settingsStore.save(_settings.value)
        _active.value?.let { byId[it]?.setVolume(v) }
    }

    suspend fun setQuality(quality: AudioQuality) {
        _settings.update { it.copy(quality = quality) }
        settingsStore.save(_settings.value)
        providers.forEach { runCatching { it.setQuality(quality) } }
    }

    /**
     * Shows the song at [position] paused at its start without playing it: nothing is active, so the
     * next play starts it, exactly as a song restored from the last session does.
     */
    private fun parkAt(position: Int): Job? {
        // Nothing that was starting goes on starting.
        startGen++
        startJob?.cancel()
        prepareJob?.cancel()
        val pausing = _active.value?.let { id -> byId[id]?.let { p -> scope.launch { runCatching { p.pause() } } } }
        val song = _queue.value.getOrNull(position) ?: return pausing
        _active.value = null
        _index.value = position
        resumeAtMs = 0L
        _snapshot.value = PlayerSnapshot(track = song, isPlaying = false, durationMs = song.durationMs, volume = _settings.value.volume)
        saveSession()
        return pausing
    }

    /**
     * The player as it stood when something borrowed it to play songs of its own (the weekly
     * recap): the queue, the song, the place in it and whether it was playing. While lent, nothing
     * that plays counts as listening, skips are not held against a song, and the session on disk
     * stays the one the person left.
     */
    private class Lent(
        val queue: List<Track>,
        val index: Int,
        val positionMs: Long,
        val playing: Boolean,
        val from: String?,
        val unshuffled: List<Track>?,
        val generated: Boolean,
    )

    @Volatile private var lent: Lent? = null

    /** Whether the player is lent out ([lend]). */
    val isLent: Boolean get() = lent != null

    /** Borrows the player; [giveBack] returns it as it was. A second lend while lent does nothing. */
    fun lend() {
        synchronized(queueLock) {
            if (lent != null) return
            val snap = _snapshot.value
            val list = _queue.value
            val at = _index.value
            lent = Lent(
                queue = list,
                index = at,
                positionMs = snap?.takeIf { it.track?.key == list.getOrNull(at)?.key }?.positionMs ?: resumeAtMs ?: 0L,
                playing = _active.value != null && (snap?.isPlaying == true || snap?.isBuffering == true),
                from = playingFrom,
                unshuffled = unshuffled,
                generated = queueGenerated,
            )
        }
    }

    /**
     * Plays [track] from [startMs] on the lent player, as a queue of its own. A song its service
     * cannot play now plays from one that can when one has the same recording; otherwise nothing
     * plays, quietly (a recap goes on without its song rather than stopping on a message).
     */
    suspend fun playLent(track: Track, startMs: Long = 0L) {
        if (lent == null) return
        val song = if (canPlayNow(track)) track else playableCopy(track) ?: return
        val job = commands.withLock {
            synchronized(queueLock) {
                if (lent == null) return@withLock null
                playingFrom = null
                unshuffled = null
                _queue.value = listOf(song)
                begin(0, startMs.coerceAtLeast(0))
            }
        }
        job?.join()
    }

    /**
     * Gives the player back: the person's queue and song where they were, playing again if they
     * were playing, otherwise shown paused at that place (as a song restored at start is).
     */
    suspend fun giveBack() {
        val job = commands.withLock {
            synchronized(queueLock) {
                val back = lent ?: return@withLock null
                lent = null
                _queue.value = back.queue
                playingFrom = back.from
                unshuffled = back.unshuffled
                queueGenerated = back.generated
                when {
                    back.index !in back.queue.indices -> {
                        _index.value = -1
                        silence()
                        null
                    }
                    back.playing -> begin(back.index, back.positionMs)
                    else -> parkAt(back.index).also {
                        resumeAtMs = back.positionMs
                        _snapshot.value = _snapshot.value?.copy(positionMs = back.positionMs)
                        saveSession()
                    }
                }
            }
        }
        job?.join()
    }

    /**
     * Ends the lend without putting anything back: what plays next is the person's own (the recap's
     * "Play these" starts a queue of its songs with [play] after this).
     */
    fun keepLent() {
        synchronized(queueLock) { lent = null }
    }

    /** Stops the music after [minutes]; setting it again replaces the last one. */
    fun sleepIn(minutes: Int) {
        val m = minutes.coerceIn(1, 24 * 60)
        _sleep.value = SleepTimer.At(endsAt = clock() + m * 60_000L, minutes = m)
    }

    /** Stops the music when the song that is playing ends. */
    fun sleepAtEndOfSong() {
        _sleep.value = SleepTimer.EndOfSong
    }

    fun cancelSleep() {
        _sleep.value = null
    }

    /** What is left on a timed sleep timer, in ms; null for none or the end of the song. */
    fun sleepRemainingMs(): Long? = (_sleep.value as? SleepTimer.At)?.let { (it.endsAt - clock()).coerceAtLeast(0) }

    fun setKeepScreenOn(on: Boolean) = updateSettings { it.copy(keepScreenOn = on) }

    /**
     * Private mode: while it is on, nothing about what is listened to is counted — no stats, no
     * "Your week", no new taste for the mixes, no Discord card. The music itself is untouched, and
     * what was already counted stays.
     */
    fun setPrivateMode(on: Boolean) {
        updateSettings { it.copy(privateMode = on) }
        confirm(if (on) "Private mode on — nothing is counted" else "Private mode off — listening counts again")
    }

    /** Settings → Test: the "Your week" card at any time, not only at the end of the week. */
    fun setRecapAlways(on: Boolean) = updateSettings { it.copy(recapAlways = on) }

    /** Whether the library keeps the automatic "Offline" list. */
    fun setOfflineList(on: Boolean) = updateSettings { it.copy(offlineList = on) }

    /** Whether the stage shows the song's words. */
    fun setLyricsShown(on: Boolean) = updateSettings { it.copy(lyrics = on) }

    /** Whether the pen marks sung words of word-timed lyrics. */
    fun setLyricsMarks(on: Boolean) = updateSettings { it.copy(lyricsMarks = on) }

    /** Whether sung words and lines move (rise, ripple, pour), or simply light up. */
    fun setLyricsMotion(on: Boolean) = updateSettings { it.copy(lyricsMotion = on) }

    /** Whether Valen looks for updates, and takes test builds. */
    fun setUpdates(change: (UpdatePrefs) -> UpdatePrefs) = updateSettings { it.copy(updates = change(it.updates)) }

    /** Discord's card: any change to it (on, the application id, what it shows). */
    fun setPresence(change: (PresenceSettings) -> PresenceSettings) =
        updateSettings { it.copy(presence = change(it.presence)) }

    /** The sound effects as they are set (on or off). */
    private val soundState = MutableStateFlow(initial.sound)
    val sound: StateFlow<SoundEffects> = soundState.asStateFlow()

    /**
     * Sets the sound effects. The value is shown at once; the player gets it a moment later, the
     * last of a quick run of changes only (a slider being dragged), and it is written to disk then.
     */
    fun setEffects(effects: SoundEffects) {
        val next = effects.normalized()
        soundState.value = next
        _settings.update { it.copy(sound = next) }
        effectsJob?.cancel()
        effectsJob = scope.launch {
            delay(EFFECTS_SETTLE_MS)
            settingsStore.save(_settings.value)
            val id = _active.value ?: return@launch
            val provider = byId[id] ?: return@launch
            if (provider.effectSupport == EffectSupport.Full) runCatching { provider.setEffects(next.effective()) }
        }
    }

    /** Starts from a Valen preset while leaving the independent parametric EQ alone. */
    fun usePreset(preset: SoundPreset) = setEffects(preset.effects.copy(on = true, preset = preset.id, edited = false, parametricEq = soundState.value.parametricEq))

    /** Applies an Astra EQ preset without replacing any of Valen's other effects. */
    fun useParametricPreset(preset: ParametricPreset) = setEffects(preset.applyTo(soundState.value))

    fun saveParametricPreset(id: String, preset: ParametricPreset) {
        require(id.isNotBlank() && preset.name.isNotBlank())
        val normalized = ParametricEq(true, preset.preamp, preset.bands).normalized()
        updateSettings { it.copy(parametricEqLibrary = it.parametricEqLibrary.copy(presets = it.parametricEqLibrary.presets + (id to preset.copy(preamp = normalized.preamp, bands = normalized.bands)))) }
    }

    fun deleteParametricPreset(id: String) = updateSettings {
        it.copy(parametricEqLibrary = it.parametricEqLibrary.copy(presets = it.parametricEqLibrary.presets - id))
    }

    fun saveParametricOutputProfile(outputKey: String) {
        require(outputKey.isNotBlank())
        updateSettings { it.copy(parametricEqLibrary = it.parametricEqLibrary.copy(outputProfiles = it.parametricEqLibrary.outputProfiles + (outputKey to soundState.value.parametricEq.normalized()))) }
    }

    fun restoreParametricOutputProfile(outputKey: String): Boolean {
        val eq = _settings.value.parametricEqLibrary.outputProfiles[outputKey] ?: return false
        setEffects(soundState.value.copy(parametricEq = eq))
        return true
    }

    fun deleteParametricOutputProfile(outputKey: String) = updateSettings {
        it.copy(parametricEqLibrary = it.parametricEqLibrary.copy(outputProfiles = it.parametricEqLibrary.outputProfiles - outputKey))
    }

    /** Whether the service of [track] (the playing one when null) can apply the effects here. */
    fun effectSupport(track: Track? = _snapshot.value?.track): EffectSupport? =
        track?.let { byId[it.provider]?.effectSupport }

    /** How many seconds of song pass per second now: the effects' speed on a service that applies them, else 1. */
    fun currentRate(): Float = _active.value?.let { byId[it] }?.let { playbackRate(it) } ?: 1f

    /** How many seconds of song pass per second while [provider] plays: its speed, if it applies effects. */
    private fun playbackRate(provider: MusicProvider): Float {
        val sound = _settings.value.sound
        return if (sound.on && provider.effectSupport == EffectSupport.Full) sound.normalized().speed else 1f
    }

    /**
     * Puts the song straight after the one playing (moving it there if it is queued further on).
     * With nothing queued it simply plays.
     */
    fun playNext(track: Track) {
        synchronized(queueLock) {
        val list = _queue.value
        if (list.isEmpty()) {
            scope.launch { play(track) }
            return
        }
        val current = list.getOrNull(_index.value)
        if (current?.key == track.key) return
        val without = list.filterNot { it.key == track.key }
        val at = (without.indexOfFirst { it.key == current?.key } + 1).coerceIn(0, without.size)
        _queue.value = without.toMutableList().also { it.add(at, track) }
        if (current != null) _index.value = _queue.value.indexOfFirst { it.key == current.key }
        // Turning shuffle off later keeps it next, too.
        unshuffled?.let { original ->
            val rest = original.filterNot { it.key == track.key }
            val after = (rest.indexOfFirst { it.key == current?.key } + 1).coerceIn(0, rest.size)
            unshuffled = rest.toMutableList().also { it.add(after, track) }
        }
        confirm("Plays next")
        saveSession()
        }
    }

    /**
     * A radio from one song: it plays now, and more by its artist follow, scored by the taste the
     * way a mix is. The songs are found while the first one plays; if something else was started
     * meanwhile, they are not forced on it.
     */
    suspend fun startRadio(track: Track) {
        play(track, listOf(track))
        // The artist and, through their related artists, others like them: a radio, not a discography.
        val spec = SmartSpec(sources = setOf(SmartSource.Artists), artists = artistsIn(track.artist), size = 50, discovery = 0.6f)
        val found = runCatching { mixer().build(spec, taste()) }.getOrDefault(emptyList())
        val own = recordingKey(track.title, track.artist)
        val rest = found.filter { recordingKey(it.title, it.artist) != own && it.key != track.key }
        synchronized(queueLock) {
            val list = _queue.value
            if (list.size != 1 || list.first().key != track.key) return
            if (rest.isEmpty()) {
                say("No more songs found for ${track.artist}")
                return
            }
            val radio = listOf(track) + rest
            if (_settings.value.shuffle) {
                unshuffled = radio
                _queue.value = shuffledAround(radio, track)
            } else {
                unshuffled = null
                _queue.value = radio
            }
            _index.value = 0
            radioFrom = track.key
            // The radio's queue is Valen's: what it gets played counts as offered, not chosen.
            queueGenerated = true
            saveSession()
        }
        prepareNext(startGen)
        confirm("Radio from ${track.artist}")
    }

    /** The song a radio began with, while its queue stands: the queue is refilled before it runs out. */
    @Volatile private var radioFrom: String? = null
    private var radioJob: Job? = null

    /**
     * A radio near its end gets more: songs like the last ones played (their artists and those
     * related to them), none already in the queue. Only while the queue is still the radio's.
     */
    private fun refillRadio() {
        val origin = radioFrom ?: return
        val list = _queue.value
        if (list.firstOrNull()?.key != origin) {
            radioFrom = null
            return
        }
        if (list.size - 1 - _index.value > RADIO_REFILL_LEFT || radioJob?.isActive == true) return
        val recent = list.takeLast(RADIO_SEED_SONGS).flatMap { artistsIn(it.artist) }.distinctBy(Taste::artistKey)
        radioJob = scope.launch {
            val spec = SmartSpec(sources = setOf(SmartSource.Artists), artists = recent, size = RADIO_REFILL_SONGS, discovery = 0.7f)
            val found = runCatching { mixer().build(spec, taste()) }.getOrDefault(emptyList())
            val added = synchronized(queueLock) {
                val now = _queue.value
                if (radioFrom != origin || now.firstOrNull()?.key != origin) return@launch
                val have = now.map { recordingKey(it.title, it.artist) }.toSet()
                val more = found.filter { recordingKey(it.title, it.artist) !in have }
                if (more.isEmpty()) return@launch
                _queue.value = now + more
                unshuffled = unshuffled?.let { it + more }
                saveSession()
                more.size
            }
            if (added > 0) prepareNext(startGen)
        }
    }

    fun enqueue(track: Track) {
        synchronized(queueLock) {
        if (_queue.value.isEmpty()) {
            scope.launch { play(track) }
            return
        }
        if (_queue.value.any { it.key == track.key }) return
        _queue.value = _queue.value + track
        confirm(NOTICE_QUEUED)
        saveSession()
        }
    }

    fun removeAt(position: Int) {
        synchronized(queueLock) {
        val list = _queue.value
        if (position !in list.indices) return
        _queue.value = list.toMutableList().also { it.removeAt(position) }
        when {
            position < _index.value -> _index.value -= 1
            position == _index.value -> {
                // The song playing was taken out: the queue and the speakers must still agree.
                // The service's own state, not the snapshot that is refreshed a quarter second later.
                val wasPlaying = _active.value?.let { byId[it]?.player?.value?.isPlaying } == true || _snapshot.value?.isPlaying == true
                if (_queue.value.isEmpty()) {
                    silence()
                    _index.value = -1
                } else {
                    val at = position.coerceAtMost(_queue.value.lastIndex)
                    if (wasPlaying) begin(at) else parkAt(at)
                }
            }
        }
        saveSession()
        }
    }

    fun move(from: Int, to: Int) {
        synchronized(queueLock) {
        val list = _queue.value.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val current = list.getOrNull(_index.value)
        list.add(to, list.removeAt(from))
        _queue.value = list
        if (current != null) _index.value = list.indexOfFirst { it.key == current.key }
        saveSession()
        }
    }

    fun clearQueue() {
        synchronized(queueLock) {
        silence()
        offered.clear()
        forgetKeepPlaying()
        _queue.value = emptyList()
        _index.value = -1
        unshuffled = null
        resumeAtMs = null
        saveSession()
        }
    }

    fun clearNotice() {
        _notice.value = null
        _confirmation.value = false
    }

    /** A provider's message or a failure: it stays until it is dismissed. */
    private fun say(message: String) {
        _confirmation.value = false
        _notice.value = message
    }

    /** A nod that something was done ("Added to queue", "Link copied"): it leaves by itself. */
    fun confirm(message: String) {
        _confirmation.value = true
        _notice.value = message
    }

    /** [current] first, then the rest of [list] in a shuffled order. */
    private fun shuffledAround(list: List<Track>, current: Track): List<Track> =
        listOf(current) + list.filter { it.key != current.key }.shuffled()

    private companion object {
        /** A radio is refilled when this few songs are left after the playing one. */
        const val RADIO_REFILL_LEFT = 3
        /** The last songs whose artists seed a refill, and how many songs it brings. */
        const val RADIO_SEED_SONGS = 5
        const val RADIO_REFILL_SONGS = 25

        /** Songs to follow the queue are looked for once this few (or fewer) are left after the one playing. */
        const val KEEP_PLAYING_LEFT = 2

        /** How many songs are found to follow it, each time. */
        const val KEEP_PLAYING_SONGS = 20

        /** How often the place in a playing song is written down. */
        const val SESSION_SAVE_MS = 10_000L

        /** How long effects wait for the next change before they reach the player. */
        const val EFFECTS_SETTLE_MS = 90L
        /** How long after start the restored song's stream is looked up (the app's first screen comes first). */
        const val RESTORE_PREPARE_MS = 1_500L
        const val WARM_KEEP_MS = 5 * 60_000L
        const val WARM_PER_MINUTE = 12

        /** How long a failed song's reason shows before the next song starts. */
        const val FAILED_MOVE_ON_MS = 1_200L

        /** Failures in a row after which the queue is no longer walked. */
        const val MAX_FAILED_IN_A_ROW = 5

        /** A starting service's own last song is kept off the screen this long at most. */
        const val STALE_SHOW_MS = 10_000L

        /** One page of merged answers; enough to scroll, not so many that a search never ends. */
        const val MAX_UNIFIED_RESULTS = 50
    }
}

/** A sleep timer: a time on the clock, or the end of the song that is playing. */
sealed interface SleepTimer {
    data class At(val endsAt: Long, val minutes: Int) : SleepTimer
    data object EndOfSong : SleepTimer
}
