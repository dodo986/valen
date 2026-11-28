![the valen tag, drawn stroke by stroke](docs/readme/hero.svg)

# NOT READY TO USE, YET.

![linux + android · kotlin · compose multiplatform · spotify · youtube music · soundcloud · gpl-3.0](docs/readme/badges.svg)

<p align="center">
  <em>One player for Linux and Android — Spotify, YouTube Music, and SoundCloud in a single app.</em>
</p>

Valen is a music player written once in Kotlin for Linux and Android. One queue, one now-playing screen, one library. You can throw a Spotify track between a SoundCloud set and a YouTube Music album without caring where each file came from—unless you actually want to check.

Check out the [Roadmap (TODO.md)](TODO.md) to see what's coming next.

## three services, one queue

![a marker line with a drip](docs/readme/divider.svg)

The queue belongs to Valen. Spotify, YouTube Music, and SoundCloud sit behind a single interface, so the UI doesn't care which backend is active and no service gets to hijack your queue. Each service plays one track at a time, in the exact order you lined them up.

Playback runs through each service's **own official player** (Spotify Web Player in a headless bundled Chrome instance on desktop, the YouTube Music web app, and SoundCloud's streams). No DRM is bypassed, Valen never asks for your passwords, and it doesn't store credentials. On Linux desktop, signing in opens the service's login page in your normal default browser and imports the session cookies (`sp_dc`, YouTube Music cookies, or SoundCloud `oauth_token`) into a private local store (`0600`). Your playlists, likes, and queue stay local to Valen—nothing gets written back to your accounts.

<details>
<summary><b>how does it work??</b></summary>

- **Spotify** runs through its official Web Player via Chrome DevTools on desktop, and in a WebView on Android (the sign-in is Spotify's own page; Valen never sees the password). A build can leave it out with `valen.android.spotify=false`, and then Spotify tracks fall back to YouTube Music or SoundCloud. Since the Spotify web player pauses if it thinks the tab is hidden, desktop Valen spoofs window focus so you can keep it in the background.
- **YouTube Music and SoundCloud** run natively on both platforms without a browser: [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) grabs the stream URL, and `ffplay` handles audio on desktop while ExoPlayer takes care of it on Android.
- **Spotify streams are never downloaded.** The audio is DRM-protected and Spotify actively flags bulk fetching. If you save a Spotify track for offline use, Valen grabs the matching recording from YouTube Music (or SoundCloud as a fallback) using strict track-matching rules.
- **Encrypted HLS tracks on SoundCloud are skipped in search** rather than decrypted. Valen will offer the same song from another service instead.

</details>

## the wall

![home on a wide window: a greeting, the playing song, the services](docs/readme/screens/home-desktop.jpg)

<p align="center"><sub>Home screen on desktop, pulling colors from the current cover art.</sub></p>

![home, now playing, lyrics and your week on a phone](docs/readme/screens/phones-a.jpg)

![a marker line with a drip](docs/readme/divider.svg)

## the small things

- **Downloads.** Save any playlist or individual track right from its menu.
- **Playlists.** Playlists live inside Valen. Paste a Spotify, YouTube Music, or SoundCloud link—or pull from a connected account—and Valen keeps the exact playable tracks, matches the rest across other services, and flags anything it couldn't find instead of playing a bad cover or remix. Re-importing updates the list in place without wiping your manual edits, and nothing ever syncs back to your cloud accounts.
- **Disk caching.** YouTube Music and SoundCloud streams cache as you listen, and the next track pre-fetches automatically so replays start instantly. Cache caps at 3 GB on desktop and 1.5 GB on Android, evicting the oldest plays first.
- **Offline mode.** Valen automatically maintains an "Offline" playlist containing everything currently cached or downloaded on your device. It shows up once you have a song saved, disappears if you clear everything, and can be hidden under Settings → Downloads.
- **Local files.** Play your local audio files and mix them directly into playlists alongside cloud tracks.
- **Self-updating.** Both clients pull updates straight from GitHub Releases. The Linux AppImage swaps itself out for the next launch, and the Android app downloads and triggers the installer in one tap. Everything is verified via SHA-256 before applying. See [RELEASING.md](RELEASING.md).
- **Unified artist pages.** Artist views combine metadata and catalogs from Spotify and YouTube Music into one page.
- **Discord.** Your profile can say what you're playing, and everything on that card is yours to set — the two lines (with the song's title, artist, album, service, queue place or the line being sung filled in), what the member list says, the cover and Valen's mark, the time bar, what happens when you pause, and two buttons. Settings → Discord previews the card as you change it; Discord draws the buttons for other people only.
- **Recommendations & Radio.** Home mixes, artist mixes, and "For You" rankings adapt based on your onboarding picks, listen duration, and early skips. Hit "Start radio" on any track to keep similar music rolling.

![a marker line with a drip](docs/readme/divider.svg)

## credit

Valen is licensed under GPL-3.0. It builds on a lot of great open-source work (see [`NOTICE`](NOTICE) for full details):

- **[Orpheon](https://github.com/kota-zo/Orpheon)** — Spotify Web Player plumbing, hidden-page scripts, YouTube Music integration, and the base immersive UI style (GPL-3.0).
- **[Mochy Music](https://github.com/realidkroo/Mochy-Music)** — UI inspiration: tight semi-bold typography, full-bleed fading covers, ambient backdrops, liquid glass, the floating capsule player, queue layout, and playlist-matching logic (GPL-3.0; all code in Valen is written from scratch).
- **[Metrolist](https://github.com/mostafaalagamy/Metrolist)** — Lyrics handling and the ported Lyprio lyrics provider (GPL-3.0).
- **[Astra](https://github.com/Boof2015/astra)** — Parametric EQ filter model, presets, and AutoEQ parsing (GPL-3.0).

Valen is not affiliated with Spotify AB, Google/YouTube, or SoundCloud.

*Note: LLMs were used to assist during development, though the architecture, direction, and plenty of the code are my own. If you still want to call it vibecoded, I don't really mind.*
