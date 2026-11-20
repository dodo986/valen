/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.native

import app.myspoti.ytmusic.InnerTube
import app.myspoti.ytmusic.YtmParse
import app.valen.audio.NativePlayback
import app.valen.core.AudioQuality
import app.valen.core.ArtistCatalog
import app.valen.core.MusicProvider
import app.valen.core.PlaylistCatalog
import app.valen.core.PlaylistLink
import app.valen.core.PlaylistRef
import app.valen.core.PlayerSnapshot
import app.valen.core.ProviderEvent
import app.valen.core.ProviderId
import app.valen.core.EffectSupport
import app.valen.core.SoundEffects
import app.valen.core.ProviderSignIn
import app.valen.core.ReadPlaylist
import app.valen.core.SessionState
import app.valen.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

/**
 * YouTube Music on the desktop with no browser: the web app's search API ([InnerTube]) for
 * looking, NewPipeExtractor for the stream ([YoutubeAudio]), and FFmpeg for the sound
 * ([NativePlayback]).
 *
 * Signing in is still the browser's job, and only when asked: [signIn] opens the private Chrome on
 * its own page on `connect()` and never on a play, so a machine with no browser at all still plays,
 * searches and skips.
 */
class YtmusicNativeProvider(
    private val signIn: ProviderSignIn,
    private val playback: NativePlayback,
    scope: CoroutineScope,
    private val api: InnerTube = InnerTube(OkHttpClient(), cookies = { null }),
    /**
     * The browser's cookies for YouTube Music, when one is signed in. The account's own playlists
     * are read on that session; a null or empty answer is a signed-out machine, and the playlists
     * screen then says so instead of opening a browser.
     */
    cookies: (suspend () -> String?)? = null,
) : MusicProvider, PlaylistCatalog, ArtistCatalog {

    override val id: ProviderId = ProviderId.YOUTUBE_MUSIC

    private val catalog = YtmusicPlaylistCatalog(api, cookies?.let { InnerTube(OkHttpClient(), it) })

    override val provider: ProviderId get() = id

    /** The songs, artists, albums and playlists of a query come in one reply, asked for once. */
    private val searches = YtmSearches(api, scope)

    private val artists = YtmusicArtists(api, searches)

    override suspend fun findArtists(query: String, limit: Int) = artists.find(query, limit)

    override suspend fun findReleases(query: String, limit: Int) = artists.findReleases(query, limit)

    override suspend fun findPlaylists(query: String, limit: Int) = artists.findPlaylists(query, limit)

    override suspend fun artist(id: String) = artists.artist(id)

    override suspend fun release(id: String) = artists.release(id)

    override suspend fun playlists(): List<PlaylistRef> = catalog.playlists()

    override suspend fun read(link: PlaylistLink): ReadPlaylist = catalog.read(link)

    override val session: StateFlow<SessionState> get() = signIn.state

    override val player: StateFlow<PlayerSnapshot?> get() = playback.snapshot

    override val events: SharedFlow<ProviderEvent> get() = playback.events

    override suspend fun connect() = signIn.connect()

    override suspend fun disconnect() = signIn.disconnect()

    override suspend fun search(query: String, limit: Int): List<Track> {
        if (query.isBlank() || limit <= 0) return emptyList()
        return YtmParse.search(searches.answer(query))
            .filterIsInstance<YtmParse.Item.Song>()
            .mapNotNull { it.track.toValenTrack() }
            .distinctBy { it.id }
            .take(limit)
    }

    override suspend fun play(track: Track, startMs: Long) = playback.play(track, startMs)

    override suspend fun pause() = playback.pause()

    override suspend fun resume() = playback.resume()

    override suspend fun seek(positionMs: Long) = playback.seek(positionMs)

    override suspend fun setVolume(volume: Float) = playback.setVolume(volume)

    /** FFmpeg applies the effects; without ffplay nothing plays here at all. */
    override val effectSupport: EffectSupport
        get() = if (playback.takesEffects) EffectSupport.Full else EffectSupport.None("Needs FFmpeg's ffplay")

    override suspend fun setEffects(effects: SoundEffects) = playback.setEffects(effects)

    /** Another service's song starts: this one's stream is let go, not left paused and open. */
    override suspend fun stop() = playback.stop()

    /** Finds the next song's stream now, so it starts without a gap. */
    override suspend fun prepare(track: Track) = playback.prepare(track)
    override suspend fun warm(track: Track) = playback.warm(track)
    override fun isCached(track: Track): Boolean = playback.isCached(track)

    /** The next song is resolved at the new quality; the current one keeps its stream. */
    override suspend fun setQuality(quality: AudioQuality) = playback.setQuality(quality)

    override suspend fun close() = playback.release()
}
