<p align="center">
  <img src="docs/readme/hero.svg" alt="Valen, hand-drawn in pink marker ink over a dark, moving canvas" width="100%">
</p>


<p align="center">
  <a href="#the-experience">the experience</a> &nbsp;·&nbsp;
  <a href="#all-your-music-one-place">the music</a> &nbsp;·&nbsp;
  <a href="#the-details">the details</a> &nbsp;·&nbsp;
  <a href="#build-valen">build it</a>
</p>


## the experience

Valen is a music player made for everyday use. Not another generic audio player with a sidebar, Valen is made to be beautiful but also usable and lightweight
The entire app can be themed from the colours on the album art so every song or podcast you listen to completely changes the UI of Valen

![Valen desktop home screen, showing album-led colour, handwritten details and the floating glass player](docs/readme/screens/home-desktop.jpg)


![Valen on Android, showing Home, the immersive player, animated lyrics and the listening recap](docs/readme/screens/phones-a.jpg)

![Desktop search across music services and the Valen playlist library](docs/readme/screens/pair-search-playlists.jpg)

## a music player that looks alive.

Valen's aesthetics arent just a regular music player, the entire UI is designed around beautiful visuals and extremely fluid animations

- **Colour that follows the album art.** Choose between the default black and pink pallete or theme the app around the album art.
- **Layered visuals** Floating playback controls, translucent dock and navigation UI that reacts to your cursors movement.
- **Fluid animations** Pages glide, move, roll and settle into place. The album art is interactive within valen reacting to your cursor instead of being stationary.
- **1 app, 2 experiences** The same visual identity on Linux desktops and Android without forcing a desktop layout onto a phone

![Valen's full desktop now-playing view with a taped album cover and floating controls](docs/readme/screens/now-playing-desktop.jpg)

## get closer to the music.

Lyrics arent an afterthought tucked under a cover. Valen makes the lyrics match the animated UI with time synced lines and word level timing whenever the source provides it

![Valen's illustrated lyrics experience](docs/readme/lyrics.svg)

![Valen's illustrated sound notebook and audio controls](docs/readme/notebook.svg)

## the songs stay with you.

A music player should remember more than the last thing you pressed play on.

Valen keeps a local listening history and then builds personal recommendations from what you actually finish or skip and offers mixes and radio for when you want to discover something new

Then there's **Your week**, a visual little recap of the songs and artists that followed you around.

![Valen's illustrated weekly listening recap](docs/readme/week.svg)

## the details

| | |
| :--- | :--- |
| **One library, your rules** | Build local playlists, import from music services, and mix cloud and local tracks without writing changes back to your streaming accounts. |
| **Take it offline** | Download songs and playlists, cache native streams as you listen, and find saved music in an automatic Offline playlist. |
| **A proper queue** | Reorder what's next, combine sources, and let the next native stream prefetch while the current track plays. |
| **Artist pages** | Explore artist information and music brought together from Spotify and YouTube Music. |
| **Discord presence** | Show what you're listening to, with editable text, art, buttons and a live preview of your activity card. |
| **Desktop integration** | MPRIS support for Linux media keys, player controls and desktop widgets. |
| **Updates** | A built in update flow for packaged Linux and Android builds, with download verification before installation. |
| **Made in Kotlin** | A shared Compose Multiplatform interface, with desktop and Android hosts and a provider-based playback engine. |

<br>

![Valen on Android, showing search, playlists, a personalised profile and the queue](docs/readme/screens/phones-b.jpg)

![Valen desktop profile and queue screens](docs/readme/screens/pair-profile-queue.jpg)

![Valen desktop settings and onboarding screens](docs/readme/screens/pair-settings-welcome.jpg)

</details>

## build valen

Valen is **still in alpha development**. This is a look at where it's heading, not a promise of a polished release today.

The project is built with **Kotlin and Compose Multiplatform**. For local development you'll need **JDK 21**. The Android build also requires an Android SDK.

```bash
git clone https://github.com/dodo986/valen.git
cd valen

# Linux desktop
./gradlew :app-desktop:run

# Android debug APK
./gradlew :app-android:assembleDebug
```

On Linux, native YouTube Music and SoundCloud playback needs **FFmpeg's `ffplay`**. Spotify desktop playback needs a **Chromium based browser** for its Web Player. See [packaging](packaging/README.md) for the exact requirements and [releasing](RELEASING.md) for how distributable builds are made.

Follow the [roadmap](TODO.md) for what is being planned or browse the [source](https://github.com/dodo986/valen).

## built with love 

Valen is licensed under **[GPL-3.0](LICENSE)**. Below are credits for the repos used for inspiration/ components of valen.

- **[Orpheon](https://github.com/kota-zo/Orpheon)** for Spotify Web Player integration, page scripts, YouTube Music work and the base immersive UI direction.
- **[Mochy Music](https://github.com/realidkroo/Mochy-Music)** for visual inspiration and playlist-matching ideas. Valen's code was written from scratch.
- **[Metrolist](https://github.com/mostafaalagamy/Metrolist)** for lyrics handling and the Lyprio lyrics provider.
- **[Astra](https://github.com/Boof2015/astra)** for the parametric equalizer model, presets and AutoEQ parsing.

Full third party attribution lives in [NOTICE](NOTICE). Valen is not affiliated with Spotify, Google, YouTube or SoundCloud.

<p align="center">
  <img src="docs/readme/footer.svg" alt="Valen's closing illustration, signed in its hand-painted visual language" width="78%">
</p>
