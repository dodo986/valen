# Packaging

Desktop integration for Valen on Linux. The Gradle package tasks write the real
installers; this directory holds the hand-written launcher for a checkout or a
distro that prefers its own packaging.

## What is here

- `valen.desktop` — a launcher entry for a checkout install. It points at the
  Debian package's launcher (`/opt/valen/bin/Valen`); for a checkout install,
  change `Exec=` to `valen` and put a wrapper on `PATH`, and install the icon as
  `valen.png` under `hicolor/512x512/apps/`. The packages built by Gradle write
  their own entry (`Categories=AudioVideo`, `Icon=/opt/valen/lib/Valen.png`), so
  this file is only needed when packaging by hand.

## What Valen needs on a Linux desktop

- **FFmpeg (`ffplay`).** YouTube Music and SoundCloud play natively: Valen
  resolves the stream itself and hands it to one `ffplay` process per song, so
  no browser is involved. `$VALEN_FFPLAY_BIN` overrides the binary, otherwise
  `ffplay` is taken from `PATH`. Without FFmpeg those two services report it on
  the first play and Spotify still works.
- **A Chrome or Chromium binary, for Spotify playback only.** Spotify plays through the
  Web Player in a private headless Chrome instance with Widevine DRM (`--headless=new`),
  so no separate browser window, desktop shortcut, or MPRIS entry is exposed. It is
  started when Spotify is used, never for YouTube Music or SoundCloud. The binary is found
  in this order:
  1. `$VALEN_CHROME_BIN`
  2. Bundled Chrome inside the package (`$VALEN_BUNDLED_CHROME`, e.g. `Valen.AppDir/chrome/chrome`, or `/opt/valen/lib/chrome/chrome`)
  3. `<checkout>/.tools/chrome/opt/google/chrome/chrome`
  4. `google-chrome`, `google-chrome-stable`, `chromium`, `chromium-browser`,
     `/opt/google/chrome/chrome`, `/usr/bin/chromium` on `PATH`

  Sign-in no longer opens Chromium: clicking Connect opens the service in your default
  desktop browser (`xdg-open`) and imports the session cookies from Firefox/Gecko or
  Chromium profiles (or via `cookies.txt` / paste) into `$XDG_DATA_HOME/valen/sessions.json`
  (`0600`), injecting `sp_dc` into the headless Spotify playback engine when it starts.
  A missing Chrome is not fatal: the window opens, YouTube Music and SoundCloud work
  (including sign-in and account playlists), and Spotify's tile says why it cannot start.

  **Licensing & redistribution note:** Valen is GPL-3.0, while Google Chrome and its
  Widevine CDM (`libwidevinecdm.so`) are proprietary software subject to Google's Terms
  of Service and Widevine EULA. `ChromeLauncher` executes Chrome out-of-process over CDP
  (an aggregate, not linked into Valen's JVM). `scripts/appimage.sh` and the release
  workflow bundle Chrome inside the Linux AppImage so Spotify playback works out of the
  box (set `VALEN_BUNDLE_CHROME=0` to omit it if redistributing without Chrome/Widevine).
- **A session bus.** MPRIS (`org.mpris.MediaPlayer2.valen`) is what the media
  widget, the lock screen, the volume/media keys and `playerctl` talk to. Without
  a session bus the app still runs and says so on stderr.
- **A Discord desktop client, for the profile card.** Settings → Discord makes
  the profile read "Listening to Valen" through the client's local socket
  (`discord-ipc-N`); nothing is installed and no service runs. What the card says
  (both lines, the member list's text, the time, what a pause does, the cover and
  Valen's mark, and up to two buttons) is set in the same part, with the card
  previewed from the playing song. The card is named after Valen's own Discord
  application, so the person sets nothing up. Its art
  is uploaded once to that application: `discord/valen.png` here (1024×1024, the
  key `valen`). Discord draws it as the small image over the cover's corner —
  the only slot it offers for a mark on the cover.
- **No root, no service, no setuid helper.**

## Profile and settings

| What | Path (default) | Override |
| --- | --- | --- |
| Imported sessions (`sp_dc`, YTM, SoundCloud) | `~/.local/share/valen/sessions.json` (`0600`) | `$XDG_DATA_HOME` |
| Chrome profile (headless Spotify Web Player) | `~/.local/share/valen/chrome` | `$XDG_DATA_HOME`, `$VALEN_CHROME_PROFILE` |
| Chrome log | `<profile>/chrome.log` | — |
| Settings (`settings.json`) | `~/.config/valen/settings.json` | `$XDG_CONFIG_HOME` |

`sessions.json` holds the user's imported sessions (`0600` owner-only permissions), and the Chrome
profile holds Spotify's playback engine state. Treat both like a password store: never copy, log,
or commit them. Signing out in the app deletes that service's session from `sessions.json` and
clears any matching cookies from Chrome if running.

Closing a login window does not sign out. While Valen is open, it recovers the browser without a
window using the same profile. If Chrome stays alive after its last window closes, Valen switches
it back to headless mode only after confirming that no page remains. Browser replacements request
a normal Chrome shutdown so pending cookie writes can finish. After three failed starts, use
Connect/Retry to try again. Exiting Valen stops recovery and closes the browser it started.

On NixOS, the default flake package includes this recovery code. From the project directory:

```bash
nix flake check "path:$PWD"
nix build "path:$PWD"
cat result/share/valen/package-version
nix run "path:$PWD"
```

Close any older Valen instance before testing. The package version is the release it wraps
(`1.0.0-alpha.3`). A visible Chrome window is expected during sign-in; closing it should recover
the connection headlessly, retaining the private profile's session cookies. These commands do not
install or replace a system package. `path:` includes the untracked flake files without staging
them in Git.

The flake wraps the official release AppImage as it is (its JVM, launcher, UI, desktop entry and
icon) and adds the runtime pieces it does not bundle: Nixpkgs Chrome for Spotify, FFmpeg and CA
certificates. Releases since 1.0.0-alpha.2 include desktop session recovery, so the overlay that
patched ALPHA-1 is gone. The release workflow points `flake.nix` at each new release by itself (`scripts/bump-flake.sh`:
its version, and the manifest's `linux-appimage` sha256 in SRI form, committed to `main`); a
NixOS machine takes it with `nix flake update valen`. Valen's own updater does not replace a
Nix copy; it only says a new release is out. Only x86_64-linux is provided because that is the
architecture of the official AppImage. Chrome (including Widevine) is an unfree runtime dependency,
allowed only in this flake's package set.

For a NixOS configuration, add this repository as an input named `valen` and include
`inputs.valen.packages.${pkgs.stdenv.hostPlatform.system}.default` in `environment.systemPackages`.
After updating that configuration's locked `valen` input and rebuilding it, check
`/run/current-system/sw/share/valen/package-version` to distinguish the fixed package from the
old AppImage wrapper. Updating the input alone does not replace an already running process.

Chrome listens on `127.0.0.1:9333` (`--remote-debugging-port`) for the app's own
DevTools connection. If a Chrome is already listening there, Valen attaches to it
instead of starting another; only a Chrome Valen started is closed on exit.

## Building a package

```bash
cd valen && source scripts/env.sh
./gradlew :app-desktop:packageDeb
# -> app-desktop/build/compose/binaries/main/deb/valen_1.0.0_amd64.deb

./gradlew :app-desktop:packageAppImage
./scripts/appimage.sh
# -> app-desktop/build/compose/binaries/main/app/Valen-1.0.0-x86_64.AppImage
```

`packageDeb` needs `fakeroot` and `dpkg-deb` on the machine. The Debian package
installs to `/opt/valen`, registers the launcher itself, and uses the icon at
`app-desktop/src/main/resources/valen.png` (512×512, already in the repository),
the same name `packaging/valen.desktop` gives as `Icon=valen`.

`packageAppImage` alone leaves a jpackage app-image directory at
`app-desktop/build/compose/binaries/main/app/Valen/`; jpackage has no AppImage
type and the Compose plugin does not wrap it. `scripts/appimage.sh` copies the
image into `build/appimage/Valen.AppDir`, adds the desktop entry, icon and
`AppRun`, and calls `appimagetool --no-appstream` (it reads the version from
`app-desktop/build.gradle.kts`). Where FUSE is unavailable, run the result with
`--appimage-extract-and-run`.

Both packaged runtimes carry `jdk.security.auth` and `java.net.http`
(`app-desktop/build.gradle.kts`): without the first, MPRIS fails with
`No session bus: com/sun/security/auth/module/UnixSystem`; without the second,
the Chrome launcher reports Chrome as dead while it is in fact listening
(`NoClassDefFoundError: java/net/http/HttpClient`).

## Development flags

Documented for the screenshot and integration passes only; never part of the
packaged launcher.

```
--width N --height N   window size in px (desktop 900x640, phone 390x844)
--route ROUTE          home | search | now-playing | queue | settings
--query TEXT           text the search screen starts with (pair it with --route search)
--play PROVIDER:ID     start a song found by that provider's own search:
                       spotify:<id>, ytmusic:<id>, soundcloud:<id>
                       (a bare TEXT searches every provider and plays the first result)
--screenshot PATH      draw the app at --width×--height for about two seconds, write PATH as PNG, exit
--help                 the flags above
```

`--screenshot` renders one offscreen Skia scene of `ValenApp` (not a screen
capture: on Wayland a compositor does not give a window's pixels to an X11
client, so `java.awt.Robot` returns a black frame). It still starts Chrome and
builds the providers, so a screenshot can show real state; there is no window
and no MPRIS name while it runs. The scene keeps drawing for about two seconds
while the real clock moves, so a `--query` search and its artwork reach the
frame.

Examples:

```bash
./gradlew :app-desktop:run --args="--width 900 --height 640 --route home --screenshot /tmp/valen-home.png"
./gradlew :app-desktop:run --args="--width 900 --height 640 --route search \
  --query \"daft punk\" --screenshot /tmp/valen-search.png"
./gradlew :app-desktop:run --args="--width 390 --height 844 --route now-playing \
  --play ytmusic:dQw4w9WgXcQ --screenshot /tmp/valen-phone.png"
```

## MPRIS

- Bus name: `org.mpris.MediaPlayer2.valen`
- Object: `/org/mpris/MediaPlayer2`
- Interfaces: `org.mpris.MediaPlayer2`, `org.mpris.MediaPlayer2.Player` and the
  standard `org.freedesktop.DBus.Properties` (Get, GetAll, Set).
- `Identity` is "Valen"; `CanQuit`/`CanRaise` are false, `CanControl` true.

Check it by hand:

```bash
playerctl --player=valen status
playerctl --player=valen metadata
gdbus call --session --dest org.mpris.MediaPlayer2.valen \
  --object-path /org/mpris/MediaPlayer2 \
  --method org.freedesktop.DBus.Properties.Get org.mpris.MediaPlayer2.Player PlaybackStatus
```
