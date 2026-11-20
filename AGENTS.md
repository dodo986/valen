# AGENTS.md — working on Valen

Valen is a cross-platform music player (Linux desktop + Android) built from one Kotlin codebase:
Jetpack Compose Multiplatform UI, one app-owned queue, three services (Spotify, YouTube Music,
SoundCloud) behind a single provider contract. Package name `app.valen`. Not a git repo: everything
is uncommitted files on disk, so treat every file as possibly hand-edited.

This file is the durable contract: how to build, what must not break, where things live, and the
rules the project is being held to. `HANDOFF.md` is the current state and next steps.

---

## 1. Ground rules

- Work only inside `/home/dodo986/Projects/valen`. Reading reference repos (`Orpheon`, `Mochy`) is
  allowed; editing them is not.
- Ask before deleting files outside the project, installing system packages, or logging into a real
  account.
- Never hardcode API keys, tokens, cookies or credentials. SoundCloud's `client_id` is scraped at
  runtime from `soundcloud.com`; Spotify uses the user's Web Player session; nothing is committed.
- Every Kotlin file, page script and vendored bridge file carries `/* SPDX-License-Identifier: GPL-3.0-only */`
  (or the JS comment equivalent). Keep that header on new files — `NOTICE` claims it per file.
- Vendored Orpheon/Mochy code keeps its licence and credit in `NOTICE`. Do not relicense.
- After each milestone print exactly one line: `✅ [what was completed]`.
- Log a failure after 3 attempts and move on; do not loop forever.

---

## 2. Build, run, verify

```bash
cd /home/dodo986/Projects/valen
source scripts/env.sh          # project-local JDK 21, Android SDK, Gradle home, Chrome under .tools/
./gradlew :app-desktop:run     # normal app
```

Tools are project-local and git-ignored under `.tools/`; `scripts/env.sh` is mandatory for every
Gradle call in a fresh shell.

| What | Command |
| --- | --- |
| Compile everything that matters for UI work | `./gradlew :core:compileKotlinJvm :ui:compileKotlinJvm :app-desktop:compileKotlin` |
| Distributable binary | `./gradlew :app-desktop:createDistributable` → `app-desktop/build/compose/binaries/main/app/Valen/bin/Valen` |
| Core tests | `./gradlew :core:jvmTest` |
| UI click-through tests (fake services, no network, ~1 min) | `./gradlew :ui:jvmTest` |
| Audio/providers tests | `./gradlew :libs:audio:test :providers:soundcloud:test :host:desktop:test` |
| Lyrics sources (local server stands in for Lyprio and LRCLIB) | `./gradlew :providers:lyrics:test` |
| Updater (local server stands in for GitHub) | `./gradlew :libs:update:test` |
| This build's version and number | `./gradlew -q printVersion` (the version lives in `gradle.properties`, `valen.version`) |
| A release | push a `v1.2.3[-alpha.N]` tag; `.github/workflows/release.yml`, see `RELEASING.md` |
| Android compile check | `./gradlew :app-android:compileDebugKotlin` |
| Android emulator (see §5) | `source scripts/env.sh; ANDROID_AVD_HOME=$PWD/.tools/avd $ANDROID_HOME/emulator/emulator -avd valen-test -no-window -no-boot-anim -gpu host -no-snapshot` |
| APK for development | `./gradlew :app-android:assembleDebug` (`app.valen.debug`; debuggable, slow — never judge lag on it) |
| APK for a phone | `./gradlew :app-android:assembleRelease` → `app-android/build/outputs/apk/release/app-android-release.apk` (`app.valen`, R8, debug-signed) |
| Deb | `./gradlew :app-desktop:packageDeb` (needs `dpkg-deb`, `fakeroot`) |
| AppImage | `./gradlew :app-desktop:packageAppImage && ./scripts/appimage.sh` (needs `appimagetool`) |

Runtime dependencies that packages do **not** bundle: `ffplay` (FFmpeg) for native YouTube Music and
SoundCloud playback (`$VALEN_FFPLAY_BIN` overrides), and a Chrome/Chromium for Spotify
(`$VALEN_CHROME_BIN` overrides; `.tools/chrome` is used in development).

### Development flags (desktop binary)

```
valen [--width N] [--height N] [--route home|search|playlists|import|now-playing|queue|settings]
      [--query TEXT] [--play PROVIDER:ID] [--screenshot PATH]
```

- `--play 'spotify:get lucky'` searches the service and starts the first result. Without a provider
  prefix it uses the unified search.
- `--screenshot` renders offscreen (5 s settle so artwork loads), writes a PNG, exits. This is the
  only reliable way to check layout here.
- `--screenshot` with `--play` really plays the song for those seconds. To keep it silent and leave
  the user's settings alone, point `XDG_CONFIG_HOME` at a scratch directory whose
  `valen/settings.json` has `"volume": 0.0` (ffplay is started at that level). Prefer
  `--play 'soundcloud:…'` or `'ytmusic:…'` for screenshots: neither needs an account.
- `--route` + `--query` open a screen directly, e.g.
  `--route search --query "daft punk" --screenshot /tmp/opencode/search.png`.

### Environment variables

| Variable | Effect |
| --- | --- |
| `VALEN_CAPTURE_GO=ROUTE` | `--screenshot` only: switch to ROUTE halfway through the settle, to see a page change |
| `VALEN_CAPTURE_FRAMES=DIR` | `--screenshot` only: save every frame drawn as `DIR/frame-NNN.png` (motion review) |
| `VALEN_CAPTURE_STEP_MS=N` | `--screenshot` only: animation time per frame (default 80) |
| `VALEN_CAPTURE_MENU=main\|playlist\|sleep` | `--screenshot` only: open the playing song's menu on that page halfway through the settle (`sleep` sets a 30 min timer first) |
| `VALEN_CAPTURE_PLAYLIST=ID\|new` | `--screenshot` with `--route playlists`: open that playlist, or put a blank "New playlist" sleeve on the shelf |
| `VALEN_CAPTURE_TAB=0..4` | `--screenshot --route sound`: open that tab of the notebook's settings page |
| `VALEN_CAPTURE_TURN=0..4` | `--screenshot --route sound`: turn to that tab halfway through the settle |
| `VALEN_CAPTURE_SOUND=PRESET` | `--screenshot`: pick that sound preset (`SoundPresets` id) halfway, to see the notes rewrite |
| `VALEN_CAPTURE_SETTLE_MS=N` | `--screenshot` only: settle for N ms instead of 5 s (with `VALEN_CAPTURE_IDLE`, to check the steady state once a song's opening motion is over) |
| `VALEN_CAPTURE_LYRICS=FILE` | answer every song's lyrics with FILE (TTML, MLX, LRC or text, by extension) ahead of the real sources, kept nowhere: word-timed lyrics without a Lyprio key. Use invented words, never a real song's |
| `VALEN_CAPTURE_SCROLL=KEY:PX` | `--screenshot` only: start a scrolling page (its `ScrollPage` key, e.g. `settings`, `import`) scrolled by PX |
| `VALEN_CAPTURE_SEEK=MS` | `--screenshot` only: move the song to MS once it plays (lyrics mid-song) |
| `VALEN_CAPTURE_WORDS=1` | `--screenshot` only: turn the stage's lyrics on halfway (the swap) |
| `VALEN_CAPTURE_RECAP=N` | `--screenshot` only: open "Your week" on page N (0 is the first) a moment in, so it pours in |
| `VALEN_CAPTURE_RECAP_TURN=1` | `--screenshot` only: turn the story to its next page halfway (with `VALEN_CAPTURE_FRAMES`, to see the slap) |
| `VALEN_CAPTURE_SETTINGS=PART` | `--screenshot --route settings`: open that part of Settings (`services`, `playback`, `look`, `week`, `downloads`, `discord`, `about`, `test`) |
| `VALEN_CAPTURE_UPDATE=STATE` | `--screenshot` only: Settings → About shows that update state (`uptodate`, `checking`, `available`, `downloading`, `ready`, `installed`, `failed`, `told`, `off`) of an invented 9.9.9-beta.1, which also opens the update sheet over any route (`pour` runs a download in it, then Ready); GitHub is not asked. Unset, a screenshot has no updater |
| `VALEN_CAPTURE_SKIP=next\|prev[,…]` | `--screenshot` only: move the queue on (or back) halfway, then once more every 1.5 s for each further step (`next,prev` slaps a poster on, then lifts it off) |
| `VALEN_CAPTURE_ENQUEUE=1` | `--screenshot` only: add the playing song to the queue halfway (the poured notice, its drawn tick, the dock's queue glyph swallowing it) |
| `VALEN_CAPTURE_PAUSE=1` | `--screenshot` only: pause the song halfway (the stage's and Home's lights going down) |
| `VALEN_CAPTURE_POINTER=X,Y` | `--screenshot` only: rest a mouse pointer there from a third of the way in (hover states: a rail's ghost and its times, a cover's lean) |
| `VALEN_CAPTURE_CLICK=X,Y` | `--screenshot` only: click there halfway (a search row's + at 1600×900: the drop flying into the dock) |
| `VALEN_CAPTURE_DRAG=X,Y,X2,Y2` | `--screenshot` only: press at X,Y halfway, drag to X2,Y2 over a second, rest and let go at 1.6 s (a held queue row slapped back: its handle is at about 1537,211 at 1600×900; a pulled mini player: about 230,732 → 100,732 at 390×844) |
| `VALEN_CAPTURE_KEYS=help[,stage,…]` | `--screenshot` only: do those keyboard shortcuts (`Shortcut` names: `help`, `stage`, `queue`, `search`, `shuffle`…) halfway, 1.2 s apart (`help` slaps the keys page up) |
| `VALEN_CAPTURE_ALONE=1` | with `--play`: queue the song found by itself, not with the other results (a queue at its end: "Then, songs like these") |
| `VALEN_CAPTURE_TRACE=1` | `--screenshot` only: print the stack of a render failure (a teardown failure is always named) |
| `VALEN_CAPTURE_IDLE=1` | `--screenshot` only: after the settle, print how many of 20 more frames still ask to be redrawn (a redraw loop shows as 20 of 20) |
| `VALEN_FFPLAY_BIN` | ffplay used for native playback |
| `VALEN_CHROME_BIN` | Chrome/Chromium for Spotify |
| `VALEN_CHROME_PROFILE` | private Chrome profile dir (default `$XDG_DATA_HOME/valen/chrome`) |
| `VALEN_CHROME_VISIBLE=1` | force a windowed Spotify browser (normally headless) |
| `VALEN_SPOTIFY_LOG=1` | Web Player bridge lines (refusals, device join, timeouts) to stderr |
| `VALEN_CHROME_LOG=1` | Chrome launcher lines |
| `VALEN_MPRIS_LOG=1` | MPRIS lifecycle lines |
| `VALEN_DOWNLOADS_DIR` | where downloaded playlists go (default `$XDG_MUSIC_DIR/Valen`, else `~/Music/Valen`) |
| `VALEN_FFPROBE_BIN`, `VALEN_FFMPEG_BIN` | FFmpeg's tools for local tags/covers and for tagging downloads |
| `VALEN_DISCORD_APP_ID` | a Discord application of one's own, instead of Valen's built-in id |
| `VALEN_HOMEPAGE` | an `https://` page for the card's "Get Valen" button (default <https://github.com/dodo986/valen>) |
| `VALEN_UPDATE_REPO` | `owner/name` whose GitHub releases are the updates (default `dodo986/valen`; also a build property) |
| `VALEN_LYPRIO_URL`, `VALEN_LYPRIO_KEY` | Lyprio's address and signing key (also `LYPRIO_BASE_URL`/`LYPRIO_HMAC_KEY`, or `$XDG_CONFIG_HOME/valen/lyprio.json`); unset means LRCLIB only |

---

## 3. Architecture map

```
core/            Track, MusicProvider, ProviderSignIn, PlaybackCoordinator (the only UI-facing
                 facade), Taste.kt (the learned taste: evidence, trust, disliked/hidden),
                 MixEngine.kt (SmartMixer: seeds, related-artist expansion, deep gates, quotas,
                 spread), Listening.kt (listening log, generated provenance, stats), Artists.kt
                 (ArtistDirectory: one artist page from every service by `artistKey`), Lyrics.kt
                 (model, LyricsSource, LyricsBook), LyricsFormats.kt (TTML, MLX,
                 LRC readers), Recordings.kt (cross-service recording identity), Playlists.kt (the
                 app-owned playlist library), PlaylistLinks/TrackMatcher/PlaylistImporter/
                 PlaylistCatalog (import), AppSettings, settings store
ui/              Compose Multiplatform design system + screens, shared by both platforms
  theme/         Color.kt, Type.kt, Dimens.kt, Shapes.kt, Motion.kt  ← all design tokens live here
  components/    one file per component (Sidebar, PlayerBar, MiniPlayer, ResultRow, Artwork,
                 Ambient, Page, Group, PlaylistCard, …); Interactive.kt holds `tap`, `glass`, `IconTap`
  screens/       Home, Search, Playlists, PlaylistImport, NowPlaying, Queue, Settings
  artist/        the catalogue's pages: `Browser` (a stack of artist / release / service-playlist
                 pages, `LocalBrowser`, shown as `Route.Artist`) and `ArtistPages.kt`
  artwork/       Tone extraction (expect/actual pixel read), tones cache
  recap/         "Your week": RecapState (`LocalRecap`), WeekStory (the poster, its chrome, turning,
                 the lent player), WeekPages (the pages), WeekCard (Home's and the profile's way in)
  lyrics/        the stage's words: LyricsPane (states, follow-scroll), SungText (masked singing),
                 Marks.kt (which words the pen marks), Scribbles.kt (the marks' strokes), LyricsClock
  ValenApp.kt    the shell: width switch, navigation state, floating chrome, `LocalAppScope`
  src/jvmTest    ShellSmokeTest: every screen at every width, then a click-through
libs/api,web,engine,spotify,ytmusic   vendored Orpheon code (GPL-3.0, do not restructure)
libs/audio/      AudioPlayer (the sink contract), NativePlayback (resolve, cache, recover, hand to
                 the sink), FfplayPlayer (desktop sink: one ffplay per stream) + FfmpegEffects,
                 SoundDsp (the effects as sample arithmetic, for Android's sink), StreamCache +
                 DiskAudioCache (songs kept on disk), YoutubeAudio (NewPipeExtractor),
                 SoundcloudAudio (API + runtime client id)
                 DiskShelf + StreamFetcher (downloaded playlists: tagged files in ~/Music/Valen),
                 SongFinisher (FFmpeg re-wrap, or PlainFinisher: OggOpus, Mp4Tags, Id3Tags)
providers/spotify        Spotify adapter over the vendored Web Player
providers/native         YouTube Music + SoundCloud adapters on BOTH platforms (no browser at all);
                 LocalProvider + FfprobeTags (this device's files and playlists; Android reads tags
                 with app-android's AndroidTags)
providers/lyrics LyprioLyrics (signed, protocol 2 → 1), LrclibLyrics, LyricsSetup (where Lyprio's
                 address and key come from), DiskLyricsStore; a JVM library both apps use
providers/ytmusic,soundcloud   the older page-based adapters (hidden WebView); no app builds them
                 now, kept with their page scripts and tests
libs/update/     GithubUpdater (look with ETags, pick the newest release that fits, download with
                 resume, SHA-256 check, hand to the platform's UpdateInstaller); app-desktop's
                 DesktopInstaller (AppImage swap, .deb, Nix/source told) and app-android's
                 ApkInstaller + UpdateReceiver (PackageInstaller session)
host/desktop     ChromeLauncher, CdpHost/CdpPageHost, DesktopHosts, desktop sign-ins, MPRIS,
                 DiscordIpc + DiscordPresence (the "Listening to Valen" card over Discord's socket)
host/android     ExoAudioPlayer (Android sink, effects inside), ExoStreamCache (ExoPlayer's song
                 cache behind StreamCache), WebView hosts (Spotify's player,
                 the sign-in pages), Widevine, Android sign-ins, foreground playback service,
                 DownloadService (a running download's notification)
app-desktop, app-android      entry points, CLI flags, platform wiring
docs/            design-spec.md (the visual contract), provider-contract.md, screens/*.png
packaging/       desktop entry, icon, packaging notes
```

Key invariants:

- **Spotify is frozen on Android.** `valen.android.spotify=false` (gradle.properties →
  `BuildConfig.SPOTIFY`) means `ValenRuntime` makes no Web Player, no Spotify sign-in and no adapter.
  The UI lists `coordinator.services` (the services the build has), never `ProviderId.services`, so
  nothing offers Spotify there. A saved Spotify song plays from another service's copy of the same
  recording. Setting the property to true brings it all back unchanged.
- **Updates come only from a release's manifest, and nothing unverified is installed.** The version
  lives in `gradle.properties` (`valen.version`); the root build makes its number
  (`ValenVersion.code`, also Android's `versionCode`) and both must stay in step (`ValenVersionTest`).
  `GithubUpdater` takes a release only when its tag is a newer version and its
  `valen-release.json` names the same version and number and a file for this platform. The file is
  handed to the installer only when its size and SHA-256 match. Looks use ETags, so a 304 costs no
  rate limit, and a 403/429 is waited out. One piece of work at a time. The AppImage is replaced by
  a rename in its own folder (never a half-written file under its name); Restart waits for this
  process to exit before starting the new one (one Valen at a time). Android checks package, number
  and signer before `PackageInstaller`, and downloads by itself only off metered networks. Nix,
  source builds and the debug app are only told. A ready release is shown by the UI's update sheet
  (`ui/update/UpdateSheet.kt`), by itself once per release a run, unless "Later" holds it (a day,
  for that release: `UpdatePrefs.laterCode/laterUntil`); Settings → About's row opens it too. The
  sheet reuses the song menu's containers (`PouredPanel`, `PouredSheet` in `ui/menu/MenuHost.kt`).

- **The queue is Valen's.** Adapters play exactly one track. `PlaybackCoordinator` owns
  queue/index/now-playing and is the only thing the UI talks to. Never let an adapter invent a queue.
- **The UI never knows which service is playing.** It reads `ProviderId` for labels only.
- **`MusicProvider` is the contract.** See `docs/provider-contract.md`. Additions go there first.
- **One song menu.** Anything that shows a song opens `LocalSongMenu` (`MoreButton`,
  `songMenuOnRightClick`, `rememberSongMenuOpener` for a long press) with a `MenuOrigin`; the shell
  draws it (`SongMenuHost`). Never build a second menu inside a screen.
- **Every list but Liked songs takes songs** (`Playlist.takesSongs`). A list read from a service or
  a mix (`isSourced`) keeps what was added and removed here in `added`/`removed`, and a rename in
  `renamed`: `PlaylistLibrary.add` (refresh and remake both go through it) lays them back over the
  source's songs. Nothing is written to the service. "Liked songs" is the fixed id `liked`, made on
  the first like, gone with its last song.
- **Sound effects are one value** (`core/Sound.kt`, `SoundEffects`, in `AppSettings.sound`), applied
  by the player that plays: FFmpeg on the desktop (`libs/audio/FfmpegEffects`: an `ffplay -af`
  graph; ffplay takes filters at start-up only, so a change restarts the stream where it is after
  220ms of stillness, the clock re-based for a speed change) and `libs/audio/SoundDsp` inside
  ExoPlayer's audio chain on Android (`host/android/ExoAudioPlayer`; it runs ahead of ExoPlayer's
  own speed/pitch, which are `PlaybackParameters`; a change applies on the next buffer, no
  restart). The reverb differs on purpose (a convolution with Valen's room on the desktop, a
  Freeverb network on a phone); everything else must mean the same (band Q, shelf slope, cut Q,
  echo law, compressor curve), and `SoundEffects.responseDb` is the curve both apply:
  `FfmpegEffectsTest` checks the graph against the machine's FFmpeg and `SoundDspTest` checks the
  arithmetic (tone vs `responseDb` within 0.3 dB, limiter, tails, 8D, cost ≈1 % of real time).
  The Web Audio engine (`core/SoundWebAudio.SCRIPT`) belongs to the page adapters no app builds now;
  `node tools/sound/check-webaudio.mjs <chrome> <scratch>` still checks it in a muted headless
  Chrome. The page engine must never route an element that would go silent: not
  before its AudioContext runs, not while a CORS reload is pending, never a tainted (cross-origin
  without CORS) or DRM element — Spotify reports `EffectSupport.None` for that reason. YouTube Music's
  CSP refuses worklets, so the engine uses built-in nodes only (pitch is a two-tap delay shifter).
- **Android back is layered.** The UI's `SystemBack` handlers (menu, sheets, a place back to Home)
  register as it composes, above `MainActivity`'s callbacks; `MainActivity` re-adds the sign-in's
  own back on top each time a sign-in opens, so back closes the sign-in page first.
- **Playlists are Valen's, like the queue.** `core/Playlists.kt` owns them and persists
  `$XDG_DATA_HOME/valen/playlists.json`; nothing is written back to a service account. An imported
  song already on a playable service is kept exactly, others are matched across services, and what
  nothing matches stays visible as unmatched — never replaced by a different recording. Re-importing
  a source refreshes it in place (`PlaylistSource.key`). Account lists are read through the
  browser session the service's page already holds; YouTube Music and SoundCloud must not open a
  browser for browsing.
- **Desktop YouTube Music and SoundCloud never open a browser.** Chrome belongs to Spotify alone and
  starts on first use (headless by default), never at boot.
- **Android plays YouTube Music and SoundCloud natively too** — the same `providers/native`
  adapters and resolvers as the desktop, with `ExoAudioPlayer` as the sink. Their hidden WebViews
  are for signing in (and the cookies the account's playlists are read with) and never load a page
  on their own. Music in a hidden page was the old way, and YouTube's own player in it stopped
  fetching after the first minute of every song on the emulator. `ExoAudioPlayer` lives on the main
  thread, holds the audio focus (a call or unplugged headphones pause it, reported like any other
  pause), and its `play` throws when the stream will not open, so `NativePlayback` re-resolves an
  expired address. A media session that is rewritten on every 250 ms tick is a bug:
  `PlaybackService` only re-publishes what its own clock could not have predicted (`onCourse`).
- **The player can be lent, and is given back as it was.** `PlaybackCoordinator.lend()` keeps the
  queue, the song, its place and whether it played; `playLent(track, startMs)` plays a song as a
  queue of its own (a signed-out service's song from a copy, or quietly nothing); `giveBack()` puts
  everything back — playing from the same place if it was playing, otherwise parked there like a
  restored session — and `keepLent()` ends the lend with nothing put back (the recap's "Play
  these"). While lent nothing counts as listening (the tick), no skip is held against a song, and
  `saveSession` writes nothing, so a crash mid-story leaves the person's own session on disk. Only
  "Your week" borrows it (`ui/recap/WeekStory`), from a `DisposableEffect`, so every way out gives
  it back. `RecapTest` pins both ways back.
- **The weekly recap is counted by day, in the device's own time.** `ListeningLog.daily` keeps the
  last 63 days (`DAILY_KEEP_DAYS`) by local epoch day: each song's time and starts, and the time by
  hour. `Listened` keeps what it takes to play a song again (length, uri, page) and when it was
  first heard. `ListeningLog.week(today)` is the last seven days: when counting began inside them
  the whole log is the week (the totals as they are, and no "new to you"); otherwise the days are
  added up. The day bars take the larger of the local day and the older UTC `days`. The Home and
  profile card shows by itself on Sunday and Monday (`recapDue`), or every day with Settings →
  Test (`AppSettings.recapAlways`); the story itself never opens on its own.
- **Private mode stops the counting, not the player.** `AppSettings.privateMode` — the eye with a
  line through it at the foot of the side panel, and Settings → Playback's first switch — gates
  every write that records listening: the 250 ms tick does not `stats.count`, `next()` holds no
  early skip, and `DiscordPresence` clears its card and rests at `Off` (its wait follows the whole
  `AppSettings`, so the switch wakes it). The taste, the stats and "Your week" are made from that
  log, so while it is on they freeze rather than reset; what was counted before the switch stays.
  Playback, the queue, the saved session, playlists, search, downloads and MPRIS are untouched.
- **On a phone the catalogue's pages are a page of the wall, not a sheet.** `PhoneApp` shows
  `Route.Artist` inside the `Wall` while it is in `nav.over`, so the mini player and the tab bar float
  over an artist, a release or a service's playlist as over any place (they used to be a
  `FullScreenSheet` that covered both). A sheet opened before them (the stage an artist was opened
  from) steps aside while they show and comes back when Back closes them (`sheetShown`: a sheet
  shows only when it is above `Route.Artist` in `nav.over`); one opened after them covers them.
- **The coordinator decides under locks and plays outside them.** `commands` (a Mutex) serialises
  decisions, `queueLock` guards queue/index, and `begin()` bumps `startGen` and returns the start
  job, which the caller joins after letting go. The job takes `starting` (one start at a time, the
  latest generation wins) and stops every other service inside that turn, so two services can never
  sound at once. A `TrackEnded` counts only for the song the queue is on (a late end used to skip the
  next song). Removing the playing row starts or parks its successor; clearing the queue silences.
  The stress tests in `PlaybackStressTest` (thousands of interleaved commands) hold all of this.
- **A service that cannot play signed out is never the copy chosen.** `MusicProvider.playsSignedOut`
  is false for Spotify; `canPlayNow` ranks a signed-out Spotify copy after a playable one in search,
  a queue move skips such songs, and a direct tap says "Sign in to Spotify to play this" instead of
  failing inside the Web Player.
- **`NativePlayback` keeps a song going.** It caches resolved addresses (8 min, 12 songs) and
  resolves the next song ahead (`prepare`, which also has the disk keep it); an early end, a failure
  after sound began, or a stall is reopened where it was (3 tries, a lookup that fails meanwhile is
  tried again, ten seconds of steady sound forget earlier drops). "Sound came out" is its own
  per-play flag: each reopen is a new stream whose own `started` is false, and judging by that made
  the second drop look like "never opened". A failure or a silent reopen in the last seconds is the
  end of the song; a stall after the last try is a failure (it used to buffer forever); an address
  from the cache that will not open is looked up once more; Play after a failure or the end opens
  the song again. Publishing and the switch to a new song hold one lock, so a late "ended" of the
  old stream cannot be reported under the new song. The coordinator also prepares the restored song 1.5 s after start, so the first Play
  is instant (tap → stream open measured at 55 ms on the emulator).
- **Songs are kept on disk, by song, never by address.** `StreamCache` (keys like
  `youtube_music-<id>`) is asked before any lookup: a song kept whole plays from disk with no
  resolve and no network. The desktop's `DiskAudioCache` (`$XDG_CACHE_HOME/valen/audio`, 3 GB, at
  most a quarter of the free space) downloads beside the stream that plays and ahead for the next
  song, in 4 MB ranges, HLS as one file, never a copy-protected playlist, never a file over an
  hour; a file appears only once complete (`.part` then an atomic move), and the folder is trimmed
  oldest-played first. Android's `ExoStreamCache` is ExoPlayer's `SimpleCache` (`cacheDir/songs`,
  1.5 GB): the player keeps what it streams (no second download), the next song is fetched ahead in
  1 MB ranges, HLS is not kept. Two traps found on the emulator: YouTube's servers slow one long
  open-ended request to about playing speed (fetch in ranges), and ExoPlayer's "for downloading"
  cache source waits for any span the player holds — a song started while it was fetched ahead
  blocked every later prefetch (use a plain cache source, and a song that starts cancels its own
  prefetch). A kept song that will not open is dropped and looked up again.
- **Provider events name their song.** `TrackEnded(key)` and `Failed(message, key)`: an end that
  arrives late, or once the service is already on another song (Spotify's page reports it that
  way), is matched by key, not by what the service shows now. A `Failed` for the song on show moves
  the music on after `FAILED_MOVE_ON_MS` (it used to stop and wait for a press); repeat-one never
  replays a failed song; five failures in a row (or the queue's length) stop there.
- **A starting service's own last song is never put back on show.** `begin()` shows the new song
  at once, but the 250 ms tick copies the active service's `player` state, and until the service
  has the new song that state still names its previous one — or, when the queue moves to another
  service, whatever that service played last, long ago. Copying it flipped the screen old → new →
  old on one skip and through songs nobody asked for (seen in the owner's recording once the stage
  poster and wash made every change loud). `begin()` notes that song (`staleKey`) and the tick skips
  a state still showing it, until the service shows anything else or `STALE_SHOW_MS` passes
  (`PlaybackCoordinatorTest`: "a service that has not moved yet…").
- **The song shown is the song Valen asked for, while the service plays it.** The tick shows
  `asked` (the queue's track handed to the service in `begin()`), not the service's own
  description, whenever the service reports that song: the same id, or the same recording
  (`recordingKey`) on the same service, as when Spotify swaps in a regional copy. Gaps (cover,
  album, length) are filled from the service. The Web Player describes a song with another size of
  cover, which made the stage's poster slap the same song on twice (once starting, once loaded);
  it also kept a playing row unmarked when the page's id differed. A different song is shown as
  the service reports it. Pinned by "the service's own description of the song asked for…".
- **What keeps the music going is not the queue until it is played.** With `keepPlaying` on (the
  default) and nothing repeating, `findMoreToKeepPlaying()` (from `prepareNext`, and when the
  setting or repeat changes) looks for songs like the queue's last five once two or fewer are left;
  they sit in `keepPlaying`, apart from `queue`, so an `enqueue` still goes before them. Only at the
  end of the queue (`decideNext` → `keepGoing()`) do they join it, their keys in `offered`: their
  listening counts as generated, and the saved session keeps that (`SavedSession.offered`). A new
  queue (`play`, `clearQueue`, `clearData`) forgets both, a radio refills itself instead, and a
  lent player (the recap) never keeps going. Pinned by "when the queue runs out, songs like it…".
- **Between two songs there is always a song on show.** `begin()` shows the new song as starting
  instead of null: Android read null as "the session ended", stopped its playback service at every
  song change, and with the screen off could not start it again. `ValenRuntime` also ends the
  session only when the queue is empty, the service holds its wake lock while a song starts, and it
  republishes only what the session's own clock could not predict.
- **Lyrics are looked up by song, not by service, and Lyprio's key is never in the source.**
  `LyricsBook` (on the coordinator) asks its sources in order (Lyprio when set up, Spotify's own
  card through `LyricsCatalog`/`ServiceLyrics`, then LRCLIB), keyed by `recordingKey` and the song's length in 4 s steps (`LyricsBook.keyOf`: a music video's intro must never take the album version's timing); a failure is
  `Failed` and never remembered as a miss (a miss is kept 3 days). Spotify is asked only signed in
  and with its Web Player already up: `SpotifyProvider.serviceLyrics` throws `LyricsSkipped` rather
  than wake the page, and the desktop's `RetryingProvider` asks only an adapter already attached
  (never `ready()`, which would launch Chrome). A skipped source keeps a miss out of the store.
  `AppSettings.lyricsMarks`/`lyricsMotion` (Settings → Lyrics) turn the pen and the motion off.
  Lyprio's address and HMAC key come only from the environment, `lyprio.json`, or a build's
  own properties (`LyricsSetup`), never from the source. The pen marks only word-timed lyrics, chosen from the song's key
  (`markWords`), and every mark is drawn from the song's time, so a seek back unwrites it.
- **No frame loops.** The UI never asks for frames on a timer it does not need: the wash drifts on
  a 200 ms `delay` (not `withFrameNanos`), the glass orbit goes round twice when a song starts and
  rests (25 steps a second, in its own layer), and screens read `coordinator.nowPlaying` (no
  position) — only the seek bar, player bar and mini player read the moving position, through
  `rememberPosition`, inside themselves. The one exception is word-timed lyrics on the stage while
  the song plays (`rememberLyricsClock`): then frames are asked for, and only the line being sung
  (each line has its own layer) reads the clock in its draw. "Your week" is the other: while the
  story is on screen its page timer (the segment filling) asks for frames, read in the segment's
  draw only; closed, it is gone from the composition. Before this a playing song kept every screen drawing at the
  display's full rate (60 frames a second on the emulator, 20–27 % of a core; now about 4 a second
  and 3–6 %). `systemReducedMotion()` (Android: animations off or Battery Saver) stops the motion
  and draws the glass solid. Check with `VALEN_CAPTURE_IDLE=1 VALEN_CAPTURE_SETTLE_MS=16000` on the
  desktop and `dumpsys gfxinfo app.valen.debug` on Android. `rememberInfiniteTransition` is a
  frame loop too: `PlayingBars` (the equaliser on the playing row) used one and kept every page,
  and the page under the stage, at 60 fps while a song played (found in Pass 24: 601 frames in
  10 s with the lyrics hidden). It steps on `Motion.PlayingBarsStepMs` now and is read in the draw.
- **The lyrics draw without layers.** Each line's words are drawn from its `TextLayoutResult`
  with the tone in the paint (`drawText(layout, color/brush)`); the sung line's soft edge is a
  horizontal gradient brush per row, and words at one height are drawn in one clipped run. A
  `saveLayer` per line (to dim it, then a `DstIn` mask for the sung one) was most of a phone's
  GPU time on the stage. `Geometry` (a box per letter) is built once per laid-out paragraph and
  its word pieces only when the line is sung; mark ink only for marked words. The pane's one
  offscreen layer is its edge fade.
- **The desktop JVM is tuned for a player, not a server** (`app-desktop` `jvmArgs`): G1 with
  `-Xmx1g`, idle heap returned after 5 s (`G1PeriodicGCInterval`, free ratio 10–30 %), two GC
  threads; the AppImage's `AppRun` sets `MALLOC_ARENA_MAX=2`. Default flags kept a heap grown at
  its peak and per-core GC bookkeeping (455 → 367 MB resident in the same capture). Measure with
  `JAVA_TOOL_OPTIONS=-XX:NativeMemoryTracking=summary` and `jcmd <pid> VM.native_memory summary`.
- **A signed-out Spotify is not asked to search, and its Web Player loads late.** Unified search
  skips a service that cannot play now unless it is the only one chosen; a tap on its song plays the
  same recording from a service that can (or says why and leaves the queue alone). On Android the
  Web Player (half a gigabyte, two cores while it boots) loads 4 s after start for a signed-in
  account, or when Spotify is first needed, never at boot. "Needed" is `SpotifyProvider`'s `wake`
  hook (→ `WebPlayerHost.ensureLoaded`), called before it waits for the page (25 s for a search,
  30 s for a play): the adapter checks readiness before it sends anything, so a hook on the first
  bridge command alone never fired and Spotify stayed "still starting" for good. The WebView
  announces Chrome at its engine's real version (`BrowserIdentity`, used by every WebView host); a fixed
  `Chrome/133` is the "Unsupported browser" trap the desktop already hit. **The user agent and the client
  hints must name the same browser**: `BrowserIdentity.applyTo` sets both (brands, grease and order as
  Chromium derives them, `BrowserIdentityTest`). A UA at the engine's version beside hints frozen at
  "Chromium 133" broke both sign-ins on a phone (Spotify: "Oops! Something went wrong"; Google: "This
  browser or app may not be secure").
- **Android runs at the screen's fastest refresh rate.** `MainActivity.preferFastestRefresh`
  picks the fastest display mode at the current resolution (many makers hold an app at 60 Hz
  unless it asks). Since nothing asks for frames when nothing moves, the rate costs little;
  short motion (the glass orbit's two laps) follows the display's frames so it is smooth at 120 Hz,
  slow drift (the wash) steps on a timer.
- **NewPipe is given ids, never watch URLs.** Its URL check upper-cases the host in the device's
  language, and in Turkish "music" becomes "MUSİC": every YouTube Music song failed with "URL not
  accepted" on a phone set to Turkish. `YoutubeAudio` builds the extractor from `fromId`. Keep any
  of our own formatting in `Locale.ROOT` (the FFmpeg graph does).
- **Artist pages are put together from every service, and only from the same artist.**
  `core/Artists.kt`: adapters that have artist pages implement `ArtistCatalog` (Spotify through its
  Web Player catalogue, YouTube Music through its browse pages; SoundCloud has none);
  `PlaybackCoordinator.artists` (`ArtistDirectory`) asks every usable one at once and merges by
  `artistKey`. A service's artist counts only when its name is the same — never "the closest one" —
  so a tribute band is never mixed in. Songs on several services appear once and play from the copy
  that plays here; releases with the same title (`releaseKey`: "Discovery" = "Discovery
  (Remastered)") appear once and open from the preferred copy; a failing service leaves the page to
  the others and is named. Pages and releases are kept 30 minutes. The UI opens them through
  `LocalBrowser`: search's Artists row, Home's "Your artists", the stage's artist names (one link per
  artist, `artistsIn` splits commas and "feat." but not "&"), the song menu's "Go to artist", and
  every page's own links. On a phone the pages are a page of the wall under the mini player (see
  below), and Back steps down the stack before it closes them. A page coming back starts from what the directory keeps
  (`kept`/`keptTracks`): a single frame of "loading" shortened its list and Back lost the scroll. On a wide window a shelf lives inside `ContentColumn` and is
  bled back out (a `LazyRow` outside it slid under the side panel).
- **SoundCloud's copy-protected tracks are not offered.** Newer label releases come only as
  `cbc-`/`ctr-encrypted-hls` (DRM). Valen does not decrypt them: `SoundcloudAudio.protectedOnly`
  drops them from search, so the same song on another service is the copy offered, and playing one
  anyway says so in words.
- **Spotify signs in inside Valen's private Chrome.** A sign-in is the only reason a window may
  appear (`connect()` → `DesktopHosts.ensureVisible()`), and replacing the browser bumps
  `DesktopHosts.generation`, which invalidates the attached adapter. Spotify is therefore always
  built behind `RetryingProvider` (`app-desktop` `buildProviders`): the next use after a
  replacement builds a fresh adapter on the new page, while the sign-in's own poller keeps the
  account on show. An adapter that is kept across a replacement talks to a page that no longer
  exists and every bridge call times out — that is what "Spotify does not play after signing in"
  looks like.
- **A sign-in state is the service's session cookie, not its page's markup.** Spotify is connected
  when the browser holds `sp_dc`, YouTube Music when it holds `SAPISID`/`__Secure-3PAPISID`,
  SoundCloud when it holds `oauth_token`. Reading the page instead ("no login button") reported the
  "Unsupported browser" page as signed in, and the Web Player's own tab can be left on the sign-in
  screen after a login that finished elsewhere (`SpotifySignIn` reloads it once on the transition).
  Disconnect deletes those cookies; a sign-out that could not is reported, never claimed.
- **One browser launch or replacement at a time.** `ChromeLauncher.start`/`stopBrowser` take the
  same mutex (a play reaching for the browser during a sign-in's replacement used to launch a second
  Chrome on the profile, which handed off and exited), and `starting` is true while a browser comes
  up, so the port's brief absence during a restart is not mistaken for a crash. The death watchdog
  re-checks the generation *inside* its lock: a replacement can land between a failed probe and the
  lock, and that is not a death.
- **The Web Player parks itself on `/preferences`.** The bridge moves the tab there once it boots
  (the light page; `player-bridge.js` `IDLE_ROUTE`), so a route is no evidence of a sign-in page: a
  page with `window.Myspoti` is the player. Reading `/preferences` as "login" reloaded the player at
  every start (confirmed on a live tab). Android's Spotify session is the `sp_dc` cookie too, never
  "is the page up".
- **The browser dies with Valen.** `ChromeLauncher` starts it under `guarded()`, a `/bin/sh`
  guardian that kills it when Valen's end of a stdin pipe closes — a crash or a kill included. A
  browser started bare outlived a crashed Valen and kept playing headless.
- **A dropped adapter stops publishing.** `RetryingProvider` cancels the collectors of the adapter it
  replaces or loses: a `StateFlow` never completes, so a closed page otherwise keeps writing its
  session and its last song over the new one. The volume/quality a rebuilt page is given are restored
  under the same mutex a slider move takes and re-read inside it, so an older restore can never land
  on top of a newer choice.
- **A page host's close is atomic with its commands.** `CdpHost`/`CdpPageHost` keep the closed state
  and the waiter map under one lock: shutdown fails every waiter registered before it and refuses a
  command that starts afterwards, instead of leaving it to wait out the send timeout. The send uses
  `withTimeoutOrNull`, so only its own expiry becomes "the browser did not answer" and a caller's
  cancellation stays a cancellation.
- **This device is a source, never a service.** `ProviderId.LOCAL` ("This device") is a `Track`
  whose id is the file's absolute path. Anything that means "the streaming services" (sign-ins,
  the Sources order, the welcome, account playlists) uses `ProviderId.services`, never
  `entries`; `priorityOrder()` always puts `LOCAL` first (your own copy wins). A device playlist's
  missing file is `ReadPlaylist.blocked`, so the importer matches it on the services; a local list
  keeps songs added to it from services in `added`, like any sourced list. The desktop's file
  chooser is `zenity`/`kdialog` in its own process (`rememberLocalPicker`), and folders open with
  `xdg-open` (`rememberFolderOpener`) — never AWT's `Desktop` or `FileDialog` (GTK aborts the JVM,
  §5). On Android the chooser is the system's Files picker after `READ_MEDIA_AUDIO`, and its
  `content://` answer is turned back into a path (`ui/androidMain/PhoneFiles`); local ids stay paths.
- **A downloaded song plays from its file under its own track.** `OfflineShelf.fileFor` is by
  `Track.key`; `PlaybackCoordinator.targetFor` hands a kept song to the `LOCAL` adapter (`active`
  is `LOCAL`, the snapshot still names Spotify/YouTube Music/SoundCloud), and `canPlayNow` is true
  for it signed out. `LocalProvider.playback` resolves any kept song to its path through
  `NativePlayback(idFor = …)`.
- **Downloads never touch Spotify and never hurry.** `DiskShelf` (desktop) keeps a Spotify song as
  the same recording from YouTube Music, else SoundCloud (`downloadableCopy` + `TrackMatcher`, as
  strict as an import); Spotify's DRM streams are never fetched and its servers are not asked
  anything. Two lanes (`DiskShelf.LANES`): every *lookup* (a copy's search, a stream address) goes
  through `paced`, 0.7–1.4 s apart across both lanes; the bytes from the file servers are not paced.
  8 MB ranges (`RANGE_BYTES`, under YouTube's ~10 MB slow-down) continued after a cut; a copy
  already kept is aliased, never fetched twice; parts are named by the song's key and `place`
  reserves a name, so two lanes never collide. A 429/403 is a `Throttled` that rests every lane
  30 s doubling to 10 min (or `Retry-After`), sets `calm` (one lane for the rest of the run) and
  resolves again. Single songs (`keepSong`/`forgetSong`, the song menu's Download row) live in
  `Index.singles` and count as "needed" when a list is forgotten, and vice versa. Each song is
  `AudioQuality.HIGH`, re-wrapped by FFmpeg with `-c copy` (never re-encoded): tags plus the cover
  (an attached picture in m4a/mp3, a `METADATA_BLOCK_PICTURE` tag in Opus — FFmpeg's Ogg muxer
  refuses a picture stream, and a cover is too long for one argv string, hence an ffmetadata file).
  Files are `Artist/Album/Title.ext`, each kept list also `Playlists/<name>.m3u8` (written before
  the state says "done"). A phone has no FFmpeg: `PlainFinisher` (Valen's own Ogg Opus, MP4 and
  ID3 writers) and `AndroidFinisher` (MediaMuxer for HLS AAC) do the same re-wrap without
  re-encoding; a stream nothing can re-wrap keeps its own container's extension, never a wrong one.
  Android keeps songs in the shared `Music/Valen` (unfinished ones in the cache: that folder takes
  music files only) and tells the media scanner of each file. Forgetting a list deletes only files no other kept list needs, aliases
  (the copy's own key) included.
- **Songs are looked up ahead, within a budget.** `PlaybackCoordinator.prepareNext` prepares the
  next song (address + whole file) and only `warm`s the one after (address, no bytes).
  `coordinator.warm(track)` is the UI's hint (`Modifier.tap(onIntent)`: a 250 ms hover or a press;
  `ResultRow` passes it through `LocalWarm`; a playlist page warms its first song and the one
  Shuffle will start with). Budget: one in flight, each song once per 5 min, 12 a minute, skipped
  for kept/cached/playing/unplayable songs. `NativePlayback` shares an in-flight look-up between
  callers (`lookups`), so a tap after a press never resolves twice, and keeps 32 addresses so warms
  never push the next song's out. `StreamCache.has` asks without touching (the desktop's
  `offline()` bumps a file's age).
- **"Offline" is Valen's list** (`OFFLINE_PLAYLIST_ID`, `core/OfflineList.kt`): every known song
  that `playsOffline` (downloaded, or `MusicProvider.isCached`), recomputed 1.5 s after the
  downloads, the library, the playing song or `AppSettings.offlineList` change — never polled.
  `PlaylistLibrary.setOffline` writes only on a change and removes the list when empty or off. It
  takes no songs and has no rename/delete (`isOffline`); Settings → Downloads hides it.
- **Discord is told, never polled.** `DiscordPresence` speaks Discord's local IPC (framed JSON on
  `discord-ipc-N`, Flatpak/snap folders too) with an application id: Valen's own
  (`DiscordPresence.DEFAULT_APP_ID`, a public identifier every copy ships), or one named by
  `VALEN_DISCORD_APP_ID` (the only override; there is no id field in Settings). At most one
  `SET_ACTIVITY` per 4.1 s (Discord allows
  5 per 20 s), the latest winning; a seek only when the bar moves > 2.5 s. Activity type 2
  (Listening). Discord closed is a quiet `NoDiscord`, looked for every 15 s. Its loop is a
  background 1 s tick, not a UI frame loop. Settings → Discord shapes the card: `core/Presence.kt`
  holds `PresenceSettings` (both lines as templates, the two hover texts, the member-list display,
  the time, what a pause does, cover/mark and the two buttons) and `PresenceCard.of(now, settings, homepage)` works the activity out from it and
  the song. Templates take `{title}`, `{artist}`, `{album}`, `{source}`, `{queue}`, `{line}`,
  `{status}`, `{played}`, `{elapsed}`, `{left}`, `{length}`, fall back with `{line|artist}`, and
  keep unknown words as typed, so nothing on the card is a fixed string. `status_display_type` is
  the member-list choice (0 Valen, 1 first line, 2 second line). The settings part draws the same
  card as a preview and offers the words as chips that add their token; `DiscordPresence` sends the
  activity again on any `PresenceSettings` change (`cardChanged`), and the sung line is looked up
  only while a line asks for it. Old `settings.json` files load: their switches land on the
  templates. Valen's mark rides on the cover as Discord's *small image* (`MARK`, the art asset
  `valen`): RPC has only `large_image` and `small_image`, no compositing, so the badge is drawn by
  Discord from that asset — `packaging/discord/valen.png`, uploaded once to the application in the
  developer portal. The card's second button reads **Get Valen** and opens `PresenceCard.HOMEPAGE`
  (`VALEN_HOMEPAGE` overrides). Discord shows buttons only to *other*
  people: no field of them ever appears on one's own profile, so that is not a bug to chase (the
  payload is what counts, and a second account is the only way to see them).
- **Provider failures are shown, never hidden.** A failed service is named beside the other
  services' results; Spotify's own refusal wording ("Spotify refused to play this") is shown verbatim.
  A native provider whose next song cannot be resolved stops the previous stream before it publishes
  the failure: the old sink keeps ticking otherwise, and those ticks moved the failed song and could
  clear its reason.

---

## 4. Design system — rules for any visual work

Read `docs/design-spec.md` before changing pixels. Direction: a dark page in one colour (the playing
cover's, in tones) and floating liquid-glass chrome, in the language of Mochy Music and Orpheon's Immersive look, with a
street layer drawn in one pen (the owner's own tag, `components/TagStrokes.kt`, and the round
marker it was written with). Motion-led: glass pours and wobbles, paint is written and poured, paper
is slapped on. Not: neon (glows, halos, lit saturated edges), a second hue, stacked glass, a font for the tag, hand-lettering on anything read to use the app,
gradients unrelated to artwork, stock card grids, uppercase eyebrow labels, icons in circles as
empty states, purple-blue gradients, emoji icons, placeholder copy, shadows.

- **Tokens, not literals.** Sizes/shapes from `Dimens.kt` and `Shapes.kt` (`Space`, `Radii`,
  `Layout`), colours from `ValenColors`, motion from `Motion.kt`, type from `Type.kt`. No hex
  colour, easing curve or duration in a screen; no font size outside `Type.kt`. A component may
  state its own fixed measurements (a 48dp cover, a 52dp field); a screen may not invent spacing
  that is not a `Space` step. If a new token is genuinely needed, add it to the token file and to
  `docs/design-spec.md` in the same change.
- **One colour, in tones** (Material-3-style dynamic colour): `ValenColors` reads the
  `ValenScheme` that `Tonal.scheme(seed, black)` builds. Default: black and pink (seed
  `#E84C8B`, black page); `AppSettings.coverColour` switches to the playing cover's accent.
  `ValenApp` installs it. Use the roles — `Primary`/`OnPrimary`
  for the one filled thing and marks, `Container`/`OnContainer` for what is selected, the surfaces
  for steps — never a raw artwork colour, never a second hue, never a glow. At most one wash per
  screen.
- **Hierarchy = surface step on the black page.** Separators only between rows of one group.
  No shadows. Glass (`GlassSurface`) is for floating chrome only: side panel, dock, mini player,
  tab bar, toasts, and the song menu on a wide window. It draws the `Backdrop` that `ValenApp`'s
  `Wall` records; chrome must stay *outside* the `Wall`, which is what keeps glass from ever seeing
  glass. A new piece of chrome goes beside the Sidebar/dock under `LocalBackdrop`, never inside a
  page. The song menu (`ui/menu/`) is an opaque sheet on a phone instead: the phone's stage is a
  `FullScreenSheet` outside the `Wall`, so glass over it would blur the page underneath.
  The sound notebook (`ui/sound/`) is a page, not chrome: no glass in it.
  `drawGlassLight` paints its sheen over its whole draw scope: call it inside a clip to the shape
  (unclipped, it washed the whole panel faintly while the menu opened).
- **Profile** (`ui/profile/`): the 3D set is pre-rendered (`tools/tv/render_tv.py` →
  `composeResources/drawable/tv_{body,detail}_NN.webp` + `TvFrames.kt`); re-run the script and
  regenerate `TvFrames.kt` to change the model, never hand-edit the frames. Pictures are copied by
  `rememberPicturePicker` into `$XDG_DATA_HOME/valen/profile` (Android: `filesDir/profile`); the
  name and the two file URIs live in `AppSettings`. Listening stats are `core/Listening.kt`,
  counted by the coordinator's 250 ms tick (only time actually playing) and persisted to
  `listening.json` beside the playlists.
- **Words in the app are short and plain** (see design-spec "Words"). The only emoji is the 🥺
  on the welcome's hello, asked for by the owner.
- **Taste and mixes live in core** (`core/Taste.kt`, `core/MixEngine.kt`): `Taste` scores artists
  and songs from the welcome's favourites, listening time (recent weeks weigh as much as all
  before, halving every 14 days) and early skips as a *rate* (`ListeningLog.skips`, recorded by
  `PlaybackCoordinator.next()`). Artists are each credited name (`Taste.creditsOf`: `artistsIn`,
  " - Topic"/VEVO off); a song is heard by recording, from any service. `SmartMixer` builds from
  trust-gated seeds (favourite and most-played artists that the person really listens to: a trust
  of listening days, recordings and plays minus skips; one generated-only afternoon is not
  evidence) in three candidate layers, and every layer is entity-exact:
  - a **seed artist's** songs come from its merged artist page's popular tracks (`artistTop`) plus
    the person's own loved/on-repeat recordings, **never** from an unfiltered name search — that
    is what let SoundCloud fan uploads and the two unrelated "365MUSIC" artists in;
  - **new artists** come from the related lists of an artist page, carried as
    `RelatedArtist(name, provider, id, rank)` and read from that exact service page
    (`relatedSongs`): a same-named artist on another service can never stand in for them; the
    discovery budget is `min(0.5, 0.6 × discovery)`, so discovery 0 adds no new artist at all;
  - **genres** search, and a stranger is only accepted when the artist page confirms they belong,
    never from repetition alone (a name seen twice in one search can be a compilation channel; it
    takes three hits before repetition even counts, and channel-looking names are skipped).
  Hard gates run before scoring: hidden uploads (`AppSettings.hiddenTracks`, the song menu's
  "Don't recommend this song"), disliked artists (`AppSettings.dislikedArtists`, "Less like this";
  a track with an explicitly disliked credit is left out unless that very credit is named in the
  spec — a feature does not ride in on a named collaborator), derivatives (covers, karaoke,
  nightcore, slowed, 8D, loops, clips under a minute or over 12) only when that very upload was
  played, a cover recognised by title (`songTitleKey`: a distinctive title heard only from other
  acts — once a version by this act was heard too, its own upload is not a cover; this catches a
  re-upload whose title looks ordinary), an artist skipped away from (3+
  skips, 60 %+ of plays, never named) resting, recording dedupe, one copy per recording, at most
  a quarter per artist, at most two songs per new artist, at least a quarter from familiar artists,
  and a bounded served-history avoid set. Scoring keeps an artist's best-known songs first, boosts
  love/on-repeat, penalises recent fatigue, samples seeds by weight and spreads artists apart.
  Listening from a generated list is counted apart (`Listened.genMs/genPlays`,
  `DayListening.genSongs`; `PlaybackCoordinator.play(..., generated = true)` for Home's "For you",
  artist mixes, radios and saved smart mixes): it shows in the stats but feeds back into the taste
  only as the person's own choice, so a mix cannot seed itself. Home's "For you" (made again every
  5 songs), artist mixes, a radio and smart playlists all go through it. A radio is refilled from
  its last songs' artists when 3 songs are left, only while the queue is still the radio's
  (`radioFrom`; `play()` ends it). Tests in `TasteTest`, `MixEngineTest` and
  `PlaybackCoordinatorTest`.
- **Street marks** (`components/Street.kt`) are paint on the wall, never on chrome and never on
  text a person reads to use the app: the tag, the marker under a page title, the swipe behind the
  playing row, tape on a lone cover. Do not add a graffiti font; the tag is the owner's drawing.
- **Screens draw edge to edge** and keep content inside `ContentColumn`, which reads
  `LocalChrome` for what the floating panel and dock take. The column fills the width it is given:
  only reading screens (Settings, Import, a lone paragraph) pass a `maxWidth`. Do not pad a whole screen,
  and do not cap a browsable screen — a wide window is room for more music, not more margin.
- **One shape per component**, from `Radii`: superellipses for anything that is or holds a cover
  (12 covers, 18 rows/tiles, 28 cards, 40 stage cover and side panel), pills for controls.
- **Type**: Bricolage Grotesque is the app's voice (greeting, screen and section titles, wordmark);
  Inter is the music (song titles, artists, controls), semi-bold and tight. Song titles are never
  set in Bricolage. Nothing is uppercase. Tabular numbers for times (`timeStyle`).
- **One pressable**: `Modifier.tap` (hover/press/focus fill, no ripple) or `IconTap`. Do not call
  `clickable` with a ripple in new code.
- **Responsive by width, not platform** (`ValenApp.kt`): ≥1000dp floating side panel + player
  dock; 700–999 icon rail; <700 tab bar + mini player capsules + full-screen sheets. Same
  components in all three. Navigation state lives above the width switch.
- **Motion**: 280ms standard, 320ms enter, 200ms exit; pages pour (620ms) and their items `rise`;
  titles are `KineticText`; presses squeeze and spring; queue reorder spring(520, .83). Every
  duration, curve and spring is a `Motion` token. Reduced motion → 120ms fades, no pour, no rise,
  no drift, marks drawn complete. Check motion with the capture frame dump (§2), not by eye on a
  still.
- **Icons**: Material Symbols Rounded only, no service logos. **Hit targets ≥44dp. Rows truncate
  at one line.**
- **Empty states are a title and a real sentence with at most one action**, on the page's left
  edge. A section with nothing to show is left out rather than shown empty.
- **Artwork** crossfades; never shimmer. Tone extraction must stay off the composition callback
  path (see `Artwork.kt`), and only covers of the playing song set `measure = true`.
- **Commands go on `LocalAppScope`**, never on a screen's own `rememberCoroutineScope()`: a scope
  that leaves the composition cancels the command, and using it afterwards throws
  `rememberCoroutineScope left the composition` and freezes the window.

Android and desktop share every screen; platform differences belong in `expect/actual`, never in
duplicated screens.

---

## 5. Debugging and machine guardrails

These cost hours before; do not relearn them.

- **Tiled windows under XWayland**: niri, sway and Hyprland resize the frame but XWayland leaves
  AWT's content window at the size it was created with, so the UI draws in the corner of a larger
  window. `Main.kt` sets AWT's own non-reparenting switch (`awtWMNonReparenting`, what
  `_JAVA_AWT_WM_NONREPARENTING=1` sets) through reflection when it sees `NIRI_SOCKET`, `SWAYSOCK`,
  `HYPRLAND_INSTANCE_SIGNATURE` or `I3SOCK`. Both halves are required: without
  `--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED` in `app-desktop/build.gradle.kts`
  (`jvmArgs`, so it is in the packaged launcher too) the reflection fails and the window silently
  regresses. Reparenting desktops are deliberately left alone. Verify with
  `xwininfo`-style geometry: the `Valen` frame, `Content window` and `sun-awt-X11-XCanvasPeer`
  must report the same size after a compositor resize.
- **Never `pkill -f` / `pgrep -f`.** A pattern matches the agent's own shell and has killed the
  session three times. Kill by scanning `/proc/*/cmdline` and matching `argv[0]`:
  `.endswith('/bin/Valen')`, `== '/usr/bin/ffplay'`, or `.endswith('/chrome')` with
  `--user-data-dir=/home/dodo986/.local/share/valen/chrome`.
- **One Valen at a time.** Instances share the Chrome profile (`SingletonLock`), DevTools port 9333
  and the MPRIS bus name. A second instance steals `next` or fails to register MPRIS.
- **Stale Chrome singletons**: only delete `~/.local/share/valen/chrome/Singleton*` when no Chrome
  with that profile is running.
- **Headless is read from the process, never from `/json/version`.** Valen launches the headless
  engine with an ordinary user agent (the Web Player refuses a headless one), so the endpoint's
  `User-Agent` says "Chrome" and a UA check answers "visible" forever — Connect then did nothing,
  silently, for every service. `ChromeLauncher` scans `ProcessHandle.allProcesses()` for
  `--remote-debugging-port=9333` without a `--type=` (renderers and zygotes repeat the launch flags),
  and a sign-in replaces a headless browser even when an earlier run started it. `ensureVisible()`
  returns false with a reason and the sign-in publishes `SessionState.Unavailable`: a Connect that
  cannot show its page must say so.
- **The browser's argv comes from `/proc/<pid>/cmdline`, not `ProcessHandle.Info.commandLine()`.**
  On this machine OpenJDK answers that with argv[0] alone for the Helium browser process
  (`/opt/helium-browser-bin/helium `, `arguments()` empty) while `/proc` holds the complete NUL-separated
  line. Every process check therefore saw no browser: `isHeadless()` said "cannot be read" (the exact
  Connect error the user hit), `stopBrowser()` found nothing to stop, and a sign-in could not replace
  a leaked headless engine. `ChromeLauncher.procCommandLine(pid)` is the source; `ProcessHandle`
  supplies pids and signals only. `ChromeLauncherTest` pins it with a process whose argv has a marker.
- **The browser announces an ordinary Chrome user agent, in a window too.** The Web Player refuses a
  brand build by name (Helium, Edge, Brave) and a headless engine too, so `ChromeLauncher` passes
  `--user-agent=` on every launch, built from the *engine's* major version. Take it from the version
  output's `Chrome`/`Chromium` number: Helium prints "Helium 0.21.3.1 (Chromium 157.0.8103.78)", and
  reading its own 0.21.3.1 produced `Chrome/0.0.0.0`, which Spotify answers with "Unsupported
  browser" — no player, so every bridge call times out behind it. `ChromeLauncher.userAgent` caches
  the *complete* line: caching only the concatenation's tail made every launch after the first send
  an agent with no Mozilla prefix, and a sign-in's replacement is exactly such a launch.
- **The private Chrome profile belongs to the user** (signed out on this machine until they sign in).
  Do not clear it; do not log in on their behalf. Sign-out is the app's own Disconnect, which deletes
  that service's session cookies.
- `playerctl -p valen status|position|next|play` is the fastest end-to-end check that playback,
  queue and MPRIS are actually connected.
- MPRIS state is derived from `PlaybackCoordinator.snapshot`, which is updated from the provider's
  `player` flow every 250 ms. If MPRIS says Paused while audio plays, the provider flow is stale.
- Spotify page needs focus emulation (`Emulation.setFocusEmulationEnabled`) or it never fetches
  media; `CdpHost.activate()` does this before a play. Keep it.
- The Gradle/Skiko "version mismatch" warning is benign.
- **ffplay lies about failure: exit 0 is not an end.** It exits 0 when a stream will not open
  (missing file, refused connection, 403), which silently skipped songs. `FfplayPlayer` counts exit 0
  as an end only once sound came out, and takes the error text from the `-stats` line's tail. Its
  clock keeps running over silence when the stream starves, so a stall is `aq=0KB` held for
  `stallAfterMs`, not a still clock. Every public op goes through one mutex (an effects restart that
  raced a seek left two ffplay processes).
- **ffplay's `-ss` is not exact, so the position is taken from its clock.** It lands on the nearest
  seek point: a YouTube WebM asked for 37.3 s began at 34.98 s, so after every seek, sound change
  (each one restarts ffplay where it was) or recovery the lyrics ran seconds early. `FfplayPlayer`
  runs ffplay with `-loglevel level+info`, reads the stream's `start:` from its description (an
  MPEG-TS clock starts at 1.4 s), and anchors the position at `clock × FfmpegEffects.clockScale −
  start` (a turntable's `asetrate` scales the timestamps; atempo and rubberband keep them). Info
  lines never reach the error text shown to a person. `FfplayPlayerTest` pins WebM, a 1.25× tempo,
  a turntable and MPEG-TS against where sound really began.
- **Compose resources in an Android library need `androidResources { enable = true }`** in `ui`'s
  `android { }` block (AGP's KMP library plugin). Without it the APK has no fonts or drawables and
  the welcome screen crashed with `MissingResourceException` on its first image.
- **A page script announces with the latest injection's number** (`window.__mspPageGen` read at
  announce time, not captured once). Android's WebView reports a page load again for a web app's own
  in-page navigations; an announce carrying the first injection's number was refused by
  `PageBridge` forever after, and the page never became ready.
- **The Web Player's load watchdog needs its page starts counted.** `WebPlayerHost.pageStarts` is
  incremented in `onPageStarted`; without it (as it was until 2026-11-15) `watchLoad` reloaded the
  Spotify page at about 4, 12, 24 and 40 s after every load, each reload wiping the song just asked
  for back to the account's last one ("shows another song, plays neither"). Debug builds log the
  bridge as `ValenSpotify`. On the API 35 emulator (WebView 124) a signed-in play still does not
  start: the page registers its player device (track-playback 200, connect-state hidden observer
  200, dealer socket open), `player/command` and `connect/transfer` from the device to itself answer
  200, but no command ever comes back down the dealer socket, so no license or audio is fetched.
  That was not the WebView's age: it is `X-Requested-With`. Every WebView request carries
  `X-Requested-With: <package>` (current WebViews no longer let an app turn it off), and Spotify
  answers a Connect command carrying it with 200 and never delivers it. `SpotifyRelay` (a condensed
  port of Orpheon's `ControlRelay`) wraps the page's `fetch` at document start and sends every
  `connect-state`/`track-playback` request from the app with OkHttp, the page's own headers and
  identity, without the mark; with it the emulator and a phone play. Never remove it.
- **A snap or Flatpak browser keeps Valen's profile inside its own folder.** It cannot write to
  `~/.local/share/valen/chrome` (a hidden folder of the home directory), so Chromium fell back to
  a profile that died with the window: the sign-in was gone at every start and each start opened a
  sign-in window (a tester on Ubuntu's snap Chromium). `ChromeLauncher.systemBinary` prefers an
  ordinary install, and `profileFor` puts a sandboxed browser's profile in
  `~/snap/<name>/common/valen-chrome` or `~/.var/app/<id>/data/valen-chrome`. `sandboxOf` reads
  Ubuntu's `chromium-browser` launcher script to see the snap behind it.
- **The Android emulator** is `valen-test` (API 35, google_apis x86_64, pixel_7) under `.tools/avd`.
  Only `-gpu host` works on this machine (swiftshader and guest renderers crash with "bad color
  buffer handle"). Keep test playback silent: `pm clear app.valen.debug`, then write
  `{"volume":0.0,"onboarded":true}` to `files/settings.json` through `run-as app.valen.debug`
  before launching. A debug build logs the sink (`ValenAudio`: stream host, states, "effects chain
  on") and every user-facing message (`ValenNotice`); `dumpsys media_session` shows what the lock
  screen sees. Drive it with `uiautomator dump` + `input tap` (match labels exactly: "Allow" is also
  a dialog title). WebViews are debuggable in a debug build: `adb forward tcp:N
  localabstract:webview_devtools_remote_<pid>` exposes them over CDP. `$ADB emu kill` when done.
- `--screenshot` needs ~5 s settle; a shorter settle produces half-loaded artwork in the PNG.
- **Android's `Regex` is ICU.** A bare `}` (or other syntax the JVM forgives) throws
  `PatternSyntaxException` there, inside a class initialiser, as an `ExceptionInInitializerError`
  that no `catch (e: Exception)` sees: the lyrics crashed the app this way. Escape literal braces
  and check new patterns on the emulator. `LyricsBook` now treats any `Throwable` from a source as
  that source failing.
- **Every `ImageComposeScene.render` returns a native image: close it.** The capture loop kept
  them for the finaliser and a 75 s settle grew past 1.8 GB, which looked like an app leak.
- **In `ShellSmokeTest`, press a button below the fold with `performSemanticsAction(SemanticsActions.OnClick)`,
  never `performScrollTo()`.** The tests step the clock by hand; `performScrollTo` on a freshly
  opened page waited for idle forever (minutes of software rendering, then a killed build). Put
  `@Timeout` on a new test that drives many screens.
- **No `AnimatedContent` (or other subcomposition) inside every row of a lazy list.** A
  plus-to-tick `AnimatedContent` in `ResultRow` made Compose throw `LayoutNode N not found in
  RectList` (its spatial index, `RectManager`) when the list's rows were taken down — on leaving
  Search, and in 3 of 4 search captures; without it, 7 of 7 were clean. Animate one composable's
  own properties instead (the row's + now pops on `Animatable`). `--screenshot` prints a failed
  teardown as "tearing the window down failed" and `VALEN_CAPTURE_TRACE=1` prints the stack.
  Not the same thing: a capture that sends pointer events on Search (`VALEN_CAPTURE_CLICK`, even
  on empty space) trips that error too, and so did the untouched v1.0.0-alpha.10 (2 of 3 runs);
  it is the offscreen scene's synthetic pointer, not a regression. Judge a click capture by its
  frames.
- **A new method on a service interface (`ArtistCatalog`, `PlaylistCatalog`, `MusicProvider`…)
  is also passed on by the desktop's `RetryingProvider`**, which stands in for Spotify. A method
  with a default answer compiles without it and quietly answers for the wrapper instead: Spotify's
  albums and playlists in search were empty that way while YouTube Music's (not wrapped) showed.
  Check it with a fake adapter that answers (`ProviderRecoveryTest`), not by reflection: Kotlin
  gives the wrapper its own copy of every default, so a "declared" check passes either way.
- **Every text field carries `Modifier.typing()`** (`ui/Keys.kt`). The desktop window's shortcuts
  run on keys nothing inside took, and a text field takes only the character a key types, not
  the key: without the mark, Space in a new field would pause the music as it typed a space. The
  mark is also what lets a mouse press outside the field let go of it (`blurOnOutsideClick`).
  Put it on the field's capsule when the capsule has buttons of its own (Search's clear), so a
  press on them keeps the caret.
- **Never open AWT's `FileDialog` (or anything else that runs `gtk_init`) inside Valen.** On this
  machine GTK 3's accessibility bridge aborts the JVM in `g_bus_get_sync` (SIGABRT under
  `atk_bridge_adaptor_init`), so the profile's "change picture" killed the app on every click.
  The desktop picker runs `zenity`/`kdialog` as a separate process off the UI thread, with
  Swing's `JFileChooser` as the fallback (`ui/src/jvmMain/.../profile/ImagePicker.jvm.kt`,
  pinned by `PicturePickerTest`). `coredumpctl info <pid>` is how this was found.

---

## 6. Definition of done for a change

1. Compiles for the affected modules (`:core`, `:ui`, `:app-desktop`, and `:app-android` when shared
   code or `host/android` changed).
2. Relevant tests pass (`:core:jvmTest`, and `:ui:jvmTest` for anything under `ui/`); new behavior
   gets a test in `core/src/jvmTest`, or a step in `ui/src/jvmTest/.../ShellSmokeTest.kt` when it
   is a screen or a navigation path.
3. Visually checked with `--screenshot` at 1600×900, 2200×1200 and phone size (390×844) for UI work.
4. No new hardcoded colour, size, curve, font or credential; SPDX headers intact.
5. `TODO.md` risks/log updated when a real constraint was discovered; `README.md` and
   `docs/provider-contract.md` updated when behaviour or the provider contract changed.
6. One `✅` line summarizing the milestone.
