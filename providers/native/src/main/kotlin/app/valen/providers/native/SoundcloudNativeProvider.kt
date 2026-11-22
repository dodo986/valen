/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.providers.native

import app.valen.audio.NativePlayback
import app.valen.audio.SoundcloudAudio
import app.valen.audio.SoundcloudSession
import app.valen.core.AudioQuality
import app.valen.core.MusicProvider
import app.valen.core.PlaylistCatalog
import app.valen.core.PlaylistLink
import app.valen.core.PlaylistRef
import app.valen.core.PlaylistSearch
import app.valen.core.PlayerSnapshot
import app.valen.core.ProviderEvent
import app.valen.core.ProviderId
import app.valen.core.EffectSupport
import app.valen.core.SoundEffects
import app.valen.core.ProviderSignIn
import app.valen.core.ReadPlaylist
import app.valen.core.ServicePlaylist
import app.valen.core.SessionState
import app.valen.core.Track
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * SoundCloud on the desktop with no browser: its own API ([SoundcloudAudio]) for search and the
 * stream, FFmpeg for the sound ([NativePlayback]).
 *
 * The API's `client_id` is read from SoundCloud's page at runtime, never stored in the app.
 * Signing in, when the user asks for it, still goes through the private Chrome; playback does not.
 *
 * It has no artist pages (SoundCloud's are accounts, not artists) but it does find its own sets by
 * name ([PlaylistSearch]), which is what puts SoundCloud's playlists and albums in search results.
 */
class SoundcloudNativeProvider(
    private val signIn: ProviderSignIn,
    private val playback: NativePlayback,
    private val audio: SoundcloudAudio,
    /** The account's session from the browser's cookies, when one is signed in; never stored. */
    session: (suspend () -> SoundcloudSession?)? = null,
) : MusicProvider, PlaylistCatalog, PlaylistSearch {

    override val id: ProviderId = ProviderId.SOUNDCLOUD

    private val catalog = SoundcloudPlaylistCatalog(audio, session)

    override val provider: ProviderId get() = id

    override suspend fun playlists(): List<PlaylistRef> = catalog.playlists()

    override suspend fun read(link: PlaylistLink): ReadPlaylist = catalog.read(link)

    override suspend fun findPlaylists(query: String, limit: Int): List<ServicePlaylist> =
        catalog.findPlaylists(query, limit)

    override val session: StateFlow<SessionState> get() = signIn.state

    override val player: StateFlow<PlayerSnapshot?> get() = playback.snapshot

    override val events: SharedFlow<ProviderEvent> get() = playback.events

    override suspend fun connect() = signIn.connect()

    override suspend fun disconnect() = signIn.disconnect()

    override suspend fun search(query: String, limit: Int): List<Track> {
        if (query.isBlank() || limit <= 0) return emptyList()
        return audio.search(query.trim(), limit).map { it.toValenTrack() }
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

    override suspend fun setQuality(quality: AudioQuality) = playback.setQuality(quality)

    override suspend fun close() = playback.release()
}
