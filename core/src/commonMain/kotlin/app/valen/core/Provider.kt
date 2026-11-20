/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.core

/**
 * Where a song plays from: the three services Valen speaks to, and the files on this device. The
 * UI never switches on these for playback.
 */
@kotlinx.serialization.Serializable
enum class ProviderId(val label: String, val short: String) {
    SPOTIFY("Spotify", "Spotify"),
    YOUTUBE_MUSIC("YouTube Music", "YT Music"),
    SOUNDCLOUD("SoundCloud", "SoundCloud"),

    /**
     * A file on this device. Its [Track.id] is the file's absolute path; it needs no account, no
     * network, and is never something to sign in to.
     */
    LOCAL("This device", "Device"),
    ;

    /** A streaming service with an account (sign-in, account playlists, a place in the order). */
    val isService: Boolean get() = this != LOCAL

    companion object {
        /** The three services, in their usual order: what Settings, the welcome and sign-ins list. */
        val services: List<ProviderId> = entries.filter { it.isService }
    }
}

/** One playable song, independent of the service it came from. */
@kotlinx.serialization.Serializable
data class Track(
    val provider: ProviderId,
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long = 0L,
    val explicit: Boolean = false,
    /** Provider-specific opaque uri/token (Spotify uri, YTM video id, SoundCloud id). */
    val uri: String? = null,
    /** The song's own page on its service, when the service gave one (SoundCloud's permalink). */
    val link: String? = null,
) {
    val key: String get() = "${provider.name}:$id"
}

/**
 * The address of the song's page on its service, to share or open: the one the service gave, or
 * the one its id makes. SoundCloud's ids make no address, so a SoundCloud song saved before its
 * permalink was kept has none.
 */
fun Track.webLink(): String? = link?.takeIf { it.startsWith("https://") } ?: when (provider) {
    ProviderId.SPOTIFY -> id.takeIf { it.isNotBlank() }?.let { "https://open.spotify.com/track/$it" }
    ProviderId.YOUTUBE_MUSIC -> id.takeIf { it.isNotBlank() }?.let { "https://music.youtube.com/watch?v=$it" }
    ProviderId.SOUNDCLOUD -> null
    ProviderId.LOCAL -> null
}

sealed interface SessionState {
    data object SignedOut : SessionState
    data object Connecting : SessionState
    data class Connected(val accountName: String? = null) : SessionState
    data class Unavailable(val reason: String) : SessionState
}

data class PlayerSnapshot(
    val track: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val canSeek: Boolean = true,
    val canSkip: Boolean = true,
    val volume: Float = 1f,
    val error: String? = null,
)

sealed interface ProviderEvent {
    /**
     * The song with [key] ([Track.key]) ended by itself. The key says which one: an end that
     * arrives after the app has moved on, or one reported once the service is already on another
     * song (Spotify's page does that), must be matched to the song it belongs to. Null when the
     * adapter cannot tell; the song on show is assumed then.
     */
    data class TrackEnded(val key: String? = null) : ProviderEvent

    /** The song with [key] (null: the one playing) could not be played, said in [message]. */
    data class Failed(val message: String, val key: String? = null) : ProviderEvent
    data object SessionLost : ProviderEvent
}

/**
 * Sign-in lives on the platform (a WebView on Android, a Chrome tab on the desktop) and is handed
 * to an adapter as this small contract, so an adapter never knows how a page is shown.
 */
interface ProviderSignIn {
    val state: kotlinx.coroutines.flow.StateFlow<SessionState>
    suspend fun connect()
    suspend fun disconnect()
}

/**
 * One adapter per service. Everything the UI and the queue need is expressed here; the service,
 * its sign-in and its transport stay behind it.
 */
interface MusicProvider {
    val id: ProviderId
    val session: kotlinx.coroutines.flow.StateFlow<SessionState>
    val player: kotlinx.coroutines.flow.StateFlow<PlayerSnapshot?>
    val events: kotlinx.coroutines.flow.SharedFlow<ProviderEvent>

    /**
     * Whether a song plays here with no account signed in. YouTube Music and SoundCloud do; Spotify
     * plays only for a signed-in account, so a signed-out Spotify is never picked to play a song
     * another service could play, and is never asked to play one silently.
     */
    val playsSignedOut: Boolean get() = true

    /** Opens the service's own sign-in page. Never takes credentials itself. */
    suspend fun connect()

    suspend fun disconnect()

    /** Searches the service. Signed-out services may still answer; throw only when truly unavailable. */
    suspend fun search(query: String, limit: Int = 25): List<Track>

    /** Plays exactly this song, replacing whatever the adapter was playing. */
    suspend fun play(track: Track, startMs: Long = 0L)

    suspend fun pause()
    suspend fun resume()

    /**
     * Lets go of the song altogether (another service's song is starting): nothing kept sounding,
     * nothing kept open. A service that cannot let go pauses.
     */
    suspend fun stop() = pause()

    /**
     * The song likely to come next: an adapter that needs time to find a stream may find it now,
     * so the song starts without a gap when it comes. Only a hint; nothing plays.
     */
    suspend fun prepare(track: Track) {}

    /**
     * A song the person is about to pick (a pointer resting on it, a finger down, a list opened):
     * the adapter may look its address up now, and nothing more — no bytes are fetched. Only a
     * hint; the coordinator keeps how often it is given within a small budget.
     */
    suspend fun warm(track: Track) {}

    /** Whether the whole song is kept on disk by this adapter's own cache, to play with no network. */
    fun isCached(track: Track): Boolean = false

    suspend fun seek(positionMs: Long)
    suspend fun setVolume(volume: Float)

    /** Audio quality is a preference; an adapter that cannot honour it keeps its own. */
    suspend fun setQuality(quality: AudioQuality) {}

    /** Whether this service's player can take Valen's sound effects here, and why not. */
    val effectSupport: EffectSupport get() = EffectSupport.None("This service plays its own sound")

    /**
     * Applies [effects] (already [SoundEffects.effective]: flat when they are off) to what plays now
     * and to every song after it, until changed. An adapter without [EffectSupport.Full] ignores it.
     */
    suspend fun setEffects(effects: SoundEffects) {}

    suspend fun close()
}

enum class AudioQuality(val label: String) {
    AUTO("Auto"),
    STANDARD("Standard"),
    HIGH("High"),
}

@kotlinx.serialization.Serializable
data class AppSettings(
    val quality: AudioQuality = AudioQuality.AUTO,
    val volume: Float = 0.85f,
    val lastProvider: ProviderId? = null,
    /**
     * The order services are preferred in, for search results and for which copy of a song plays
     * when more than one service has it. Missing services are appended in their usual order.
     */
    val priority: List<ProviderId> = ProviderId.services,
    /**
     * The app's one colour. False (the default) is black and pink; true seeds the colour from the
     * cover that is playing.
     */
    val coverColour: Boolean = false,
    /** The profile: a name, and the pictures chosen for it, as file URIs in Valen's own folder. */
    val profileName: String = "",
    val avatar: String? = null,
    val banner: String? = null,
    /** Whether the welcome has been walked through; it shows once, on the first start. */
    val onboarded: Boolean = false,
    /** What the person said they love, on the welcome or later: the taste starts from these. */
    val favouriteArtists: List<String> = emptyList(),
    val favouriteSongs: List<Track> = emptyList(),
    /** Artists the person said to leave out of generated lists (the song menu's "Less like this"). */
    val dislikedArtists: List<String> = emptyList(),
    /** Songs the person said never to recommend ("Don't recommend this song"), by [Track.key]. */
    val hiddenTracks: List<String> = emptyList(),
    /** Whether a list started from here plays in a shuffled order. */
    val shuffle: Boolean = false,
    /** What happens at the end of a song and of the queue. */
    val repeat: RepeatMode = RepeatMode.OFF,
    /**
     * When the queue runs out (and nothing repeats), songs like its last ones play on, as a radio
     * would; off, the music stops with the last song.
     */
    val keepPlaying: Boolean = true,
    /** The queue and the place in it when Valen last closed, so the next start picks up there. */
    val session: SavedSession? = null,
    /** Keep the screen awake while the player is open (where the platform allows it). */
    val keepScreenOn: Boolean = false,
    /**
     * Nothing about what is listened to is counted while this is on: no stats, no weekly recap, no
     * new taste for the mixes, and no Discord card. Playback itself is unchanged, and what was
     * already counted stays (the switch stops the counting, it does not erase it).
     */
    val privateMode: Boolean = false,
    /** Whether the library shows "Offline": every song downloaded or kept whole by the cache. */
    val offlineList: Boolean = true,
    /** The stage shows the song's words beside (or instead of) its cover. */
    val lyrics: Boolean = false,
    /** The pen goes over some words of word-timed lyrics once they are sung (struck, circled…). */
    val lyricsMarks: Boolean = true,
    /** Sung words rise a little, held notes ripple and the lines pour after each other. */
    val lyricsMotion: Boolean = true,
    /** The sound effects, for every song a player can apply them to. */
    val sound: SoundEffects = SoundEffects(),
    val parametricEqLibrary: ParametricEqLibrary = ParametricEqLibrary(),
    /** What Discord shows while Valen plays (desktop), off until it is turned on. */
    val presence: PresenceSettings = PresenceSettings(),
    /**
     * Settings → Test: the "Your week" card on Home and in the profile at any time, not only at
     * the end of the week ([recapDue]). Off until turned on.
     */
    val recapAlways: Boolean = false,
    /** Whether Valen looks for new releases, and whether test builds count (Settings → About). */
    val updates: UpdatePrefs = UpdatePrefs(),
)

/** Whether a service's player can apply Valen's sound effects. */
sealed interface EffectSupport {
    data object Full : EffectSupport
    data class None(val reason: String) : EffectSupport
}

/** Off: the queue ends. All: it starts again from the top. One: the song plays again. */
@kotlinx.serialization.Serializable
enum class RepeatMode { OFF, ALL, ONE }

/**
 * Where listening stopped: the queue, the song in it and how far in, plus the order the list had
 * before it was shuffled (so turning shuffle off after a restart still puts it back).
 */
@kotlinx.serialization.Serializable
data class SavedSession(
    val queue: List<Track>,
    val index: Int,
    val positionMs: Long = 0L,
    val from: String? = null,
    val unshuffled: List<Track>? = null,
    /** The queue was made by the engine (a mix, a radio, "For you"): its listening is counted apart. */
    val generated: Boolean = false,
    /** Songs Valen added to the queue to keep it going ([AppSettings.keepPlaying]), by [Track.key]. */
    val offered: List<String> = emptyList(),
)
