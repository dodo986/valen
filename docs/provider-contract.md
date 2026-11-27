# Provider contract

Everything below lives in `:core` (`app.valen.core`). Adapters implement `MusicProvider`; the platforms
build a host and a `ProviderSignIn` for each service. The UI only sees `PlaybackCoordinator`.

```kotlin
interface ProviderSignIn {
    val state: StateFlow<SessionState>
    suspend fun connect()      // opens the service's own sign-in page
    suspend fun disconnect()
}

interface MusicProvider {
    val id: ProviderId
    val session: StateFlow<SessionState>
    val player: StateFlow<PlayerSnapshot?>
    val events: SharedFlow<ProviderEvent>
    suspend fun connect(); suspend fun disconnect()
    suspend fun search(query: String, limit: Int = 25): List<Track>
    suspend fun play(track: Track, startMs: Long = 0)
    suspend fun pause(); suspend fun resume(); suspend fun seek(ms: Long)
    suspend fun setVolume(v: Float); suspend fun setQuality(q: AudioQuality) {}
    val effectSupport: EffectSupport                 // default None(reason)
    suspend fun setEffects(effects: SoundEffects) {} // default: ignored
    val playsSignedOut: Boolean                      // default true; Spotify false
    suspend fun stop()                               // default pause(); another service takes over
    suspend fun prepare(track: Track) {}             // default nothing; look the song up ahead
    suspend fun close()
}
```

Rules:
- `play` replaces the adapter's current song; the queue is app-owned, so adapters never invent a queue.
- Emit `ProviderEvent.TrackEnded(track.key)` when the service finishes the song (not when the user
  pauses), naming the song that ended — not the one the service shows by the time it says so.
  `Failed(message, key)` names its song too; the coordinator moves on to the next song after a
  failure of the song on show, and ignores either event for a song it already left.
- Report position at the moment of the snapshot; the coordinator extrapolates while playing.
- `PlayerSnapshot.canSkip` means "the transport can move to another song", which is the queue's job,
  so an adapter that plays one song at a time reports `true` whatever its service allows itself.
- `play` must not block on anything optional: set a level, start the song, let the rest settle in the
  background (a service's own volume answer can take 30 s on an idle page).
- Adapter constructors take their transport plus a `ProviderSignIn`; no Android/desktop types.
- `connect()` delegates to `ProviderSignIn.connect()`; it must not ask for credentials itself.
- On desktop, Spotify plays through a headless private Chrome (`ChromeLauncher`, bundled or
  system) while all three services sign in via the user's real external browser (`xdg-open` +
  `BrowserCookieReader` / `cookies.txt` / paste into `DesktopSessionStore`), avoiding embedded
  in-app login blocks. YouTube Music and SoundCloud never start or require Chromium for sign-in,
  session reads, or playback. A closed Spotify Chrome page is a transport failure, not evidence of
  sign-out; a replaced browser invalidates its adapter generation, and `DesktopHosts.spotify()`
  re-injects the stored `sp_dc` cookie via CDP when the tab attaches. Cookie deletion and account
  probes are serialised, and failed deletion must not be reported as a successful disconnect.
- Every copied Orpheon file keeps its SPDX header and package name.
- **Sound effects.** `setEffects` gets `SoundEffects.effective()` (flat while the person has them
  off) and applies it to what plays now and every song after, until changed; the coordinator also
  hands it over before each `play`. Report `EffectSupport.Full` only when the effects really reach
  the audio: the native adapters do, through FFmpeg on the desktop (`FfmpegEffects`, needs ffplay)
  and through `SoundDsp` in ExoPlayer's audio chain on Android (`ExoAudioPlayer`); the older page
  adapters did through Web Audio (`SoundWebAudio`, injected with the page script). Spotify's audio is DRM-protected media, which Web Audio hears as silence, so it stays
  `None` and must never route it. A speed other than 1× means the song's time runs at that rate:
  report positions in song time; the coordinator extrapolates at the same rate.
  An enabled parametric EQ replaces the saved graphic bands, preamp, shelves and cuts.
  Native output honors `peakLimiter` (on by default for existing settings). When off,
  output uses fixed attenuation equal to half the maximum positive EQ response;
  this preserves dynamics but does not guarantee protection from clipping.
- **Signed out.** `playsSignedOut` false means the service plays for a signed-in account only
  (Spotify). The coordinator then never picks that copy over a playable one, skips such songs when it
  moves through the queue, and answers a tap with "Sign in to … to play this" instead of calling
  `play`. A service that plays signed out leaves the default.
- **`stop`** is called on every other service inside a start, before the new song plays: it must
  leave nothing sounding and nothing that will start sounding (a pending open included). Native
  adapters drop their stream; the default pauses.
- **`prepare`** is a hint that `track` is likely next (the next queue row, or the song restored at
  start). It may resolve and keep its address, and the native adapters have the disk keep the song;
  it must not make sound and must be cheap to ignore.
- `Track.link` is the song's own page on its service when the service hands one over (SoundCloud's
  `permalink_url`; both SoundCloud adapters fill it). Spotify and YouTube Music leave it null: their
  pages follow from the id (`Track.webLink()`). It is what Share and "Open on …" use, so an adapter
  that knows a song's address should pass it rather than leave the menu without one.

## This device, downloads and Discord

`ProviderId.LOCAL` ("This device") is the fourth source: a `Track` whose `id` is the file's
absolute path. `ProviderId.services` is the three streaming services (sign-ins, the order in
Settings, account playlists); `PlaybackCoordinator.priorityOrder()` always puts `LOCAL` first.
The desktop's adapter is `providers/native` `LocalProvider` (also a `PlaylistCatalog`: `read`
takes a playlist file or a folder, `playlists()` lists playlist files under `~/Music`; a missing
file is returned in `ReadPlaylist.blocked` so the importer matches it on the services).

`OfflineShelf` (`core/Offline.kt`) is the downloads: `fileFor(track)` by `Track.key`. When a song
is kept and a `LOCAL` adapter exists, `begin()` plays it through that adapter under the song's own
`Track` (`isKept`, `targetFor`), so `active` is `LOCAL` while the snapshot still names the original
service. `canPlayNow` is true for a kept song even with its service signed out.

`PresenceLink` (`core/Presence.kt`) is the platform's link to Discord, `AppSettings.presence` its
settings; only the desktop has one (`host/desktop` `DiscordPresence`, `DiscordIpc`).

## Artist pages

```kotlin
interface ArtistCatalog {          // :core, beside MusicProvider
    val provider: ProviderId
    suspend fun findArtists(query: String, limit: Int = 6): List<ServiceArtist>
    suspend fun artist(id: String): ArtistProfile      // top songs (with plays), releases, playlists, related
    suspend fun release(id: String): ReleaseTracks     // an album / single and its songs
    suspend fun findReleases(query: String, limit: Int = 8): List<Release>          // default: none
    suspend fun findPlaylists(query: String, limit: Int = 8): List<ServicePlaylist> // default: none
}
```

An adapter that has artist pages implements it (Spotify, YouTube Music). `ArtistDirectory` asks
every catalogue whose service can be used now (a signed-out Spotify is left out), keeps a service's
artist only when its name is the same as the one asked for, and merges the pages: each recording
once (from the copy that plays here), each release title once, related artists by name. A wrapper
(`RetryingProvider`) passes the interface through to the adapter it holds.

### Playlist search

A service can find its own sets by name without having artist pages:

```kotlin
interface PlaylistSearch {         // :core, beside ArtistCatalog
    val provider: ProviderId
    suspend fun findPlaylists(query: String, limit: Int = 8): List<ServicePlaylist>
}
```

SoundCloud implements it: its API's `search/playlists` is public data (the runtime `client_id` is
all it takes), so search shows its playlists and albums signed out, and each one carries a
`PlaylistLink` that `PlaylistCatalog.read` opens for import. `ArtistDirectory.searchPlaylists`
asks both kinds — the artist catalogues' `findPlaylists` and every `PlaylistSearch` — at once,
interleaves them in the person's order, and one service that fails loses only its own answer (and
is not recorded as a missing artist page either). An adapter without such a search is not asked.

## Native playback (YouTube Music and SoundCloud, desktop and Android)

Those two adapters do not own a page. They resolve a stream and hand it to the platform's audio
output:

```kotlin
interface AudioPlayer {            // :libs:audio
    val state: StateFlow<AudioState>
    val canSeek: Boolean
    suspend fun play(stream: ResolvedStream, startMs: Long)
    suspend fun pause(); suspend fun resume(); suspend fun seek(ms: Long)
    suspend fun setVolume(v: Float); suspend fun stop(); suspend fun release()
    val takesEffects: Boolean get() = false
    suspend fun setEffects(effects: SoundEffects) {}
}
```

`AudioState.started` means sound really came out of this stream; `stalled` means it stopped moving
while it should play (a dead connection). Two sinks implement it: `FfplayPlayer` on the desktop and
`ExoAudioPlayer` on Android (main thread, audio focus, effects inside; `play` throws when the stream
will not open).

- `YoutubeAudio` resolves a video id to a playable address through NewPipeExtractor (signatures, the
  `n` challenge and PO tokens are its job); a plain InnerTube answer serves only its first megabyte,
  which is why the extractor is not optional.
- `SoundcloudAudio` reads the API's `client_id` from `soundcloud.com` at runtime, searches, and picks
  a progressive MP3 or an HLS AAC stream. A rejected id is re-read once. A track offered only as
  encrypted HLS (DRM) is left out of search and, played anyway, says it is copy-protected.
- `FfplayPlayer` runs one `ffplay` per stream: `-ss` starts part-way in, SIGSTOP/SIGCONT pause,
  and seeking or a settled effects/volume change restarts the process. Position uses ffplay's audio
  clock when available. A clean exit well before a trusted YouTube duration triggers up to two
  reopens at the last position; if it still ends early, it reports a failure, not `TrackEnded`.
  Other sources such as SoundCloud previews do not opt in because their duration may describe the
  full song rather than the playable preview. Missing stream durations use catalogue metadata
  when available; otherwise the desktop learns the duration from ffplay's container description,
  including for existing cached files. An exit without a usable audio clock is a failure, even
  with exit code zero. Unknown durations cannot be checked for an early end; reopens use the same
  URL, not a freshly resolved one.
- `StreamCache` keeps songs on disk by song key (`DiskAudioCache` on the desktop, `ExoStreamCache`
  on Android). `NativePlayback` asks it first: a song kept whole plays with no lookup. It is told of
  every stream played (`keep(playing = true)`) or prepared (`playing = false`), and `forget`s a
  kept copy that will not open. `ResolvedStream.cacheKey` carries the key to a player that caches
  what it streams.
- `NativePlayback` is the shared half: it maps `AudioState` to `PlayerSnapshot`, emits `TrackEnded`
  once per song so the coordinator advances exactly once, keeps resolved addresses for 8 minutes
  (`prepare` fills that cache), retries a kept address that will not open with a fresh one, reopens
  a dropped or stalled stream where it was (up to 3 times, then `Failed`), reopens after a pause
  longer than 2 minutes, and lets Play retry a song that failed.

The page-based adapters in `providers/ytmusic` and `providers/soundcloud` are no longer built by
either app; they and their page scripts stay, tested, as the fallback design.
