# Releasing Valen

## The short way

```bash
scripts/release.sh --watch
```

That releases the next version (1.0.0-alpha.3 → 1.0.0-alpha.4, or 1.2.0 → 1.2.1): it sets
`valen.version`, commits, pushes `main` and the tag, then waits for the build and prints the
release's address. Give a version to choose it (`scripts/release.sh 1.1.0-beta.1`); leave out
`--watch` to return as soon as the tag is pushed. It refuses to run off `main`, with uncommitted
changes, or when `main` is behind GitHub's (pull first).

The rest of this file is what happens underneath.

A release is a tag. Push `vMAJOR.MINOR.PATCH`, optionally with `-alpha.N`, `-beta.N` or `-rc.N`, and
`.github/workflows/release.yml` does the rest:

```bash
git tag v1.0.0-alpha.3
git push valen v1.0.0-alpha.3
```

The workflow tests the core and the updater, builds the APK, the AppImage and the `.deb` at the
tag's version, writes `valen-release.json`, and publishes them together. The release stays a draft
until every file is on it. A tag with `-alpha`/`-beta`/`-rc` becomes a pre-release, and only copies
that take test builds are offered it.

The release body is what the apps show under **What changed**. Write it for a tag in
`.github/release-notes/<version>.md` (for example `.github/release-notes/1.0.0-alpha.9.md`): the
workflow uses that file when it exists, and GitHub's generated notes otherwise. It reads like the
update sheet: `##` lines are headings (a leading "What's Changed" is dropped), `-` lines are the
dashed items a person reads, plain lines are smaller text, and links collapse to their label. Keep
it short — a heading and a handful of dashes, in plain words a listener understands.

Once the release is out, a second job (`flake`) points `flake.nix` on `main` at its AppImage
(version and hash, `scripts/bump-flake.sh`). It only moves forward, so releasing an older tag
again leaves the flake alone. NixOS machines pick it up with `nix flake update valen`.

Bump `valen.version` in `gradle.properties` after a release, so local builds name the next version.
The tag decides the version of a release build, not the file.

## What the apps do with it

Both apps read `https://api.github.com/repos/dodo986/valen/releases`. `VALEN_UPDATE_REPO` changes the
repository, for a fork. The first look is 20 s after start and the next at most every 6 hours, each
with the last answer's ETag. A look that finds nothing new is a 304 and doesn't count against
GitHub's 60 requests an hour.

A release counts only when all of these hold:

- Its tag is a version.
- It is newer by number (`ValenVersion.code`, the same number as Android's `versionCode`).
- It carries `valen-release.json` naming the same version and number.
- The manifest lists a file for this platform, with the size GitHub shows.

A download is used only when its size and SHA-256 are the manifest's. After a cut it resumes where
it stopped.

| Copy | Route | What happens |
| --- | --- | --- |
| AppImage | Itself | Downloaded, written beside the AppImage, then moved over it in one rename. The next start is the new version, or press **Restart** in Settings → About. |
| `.deb` (`/opt/valen`) | System | Downloaded, then **Install** runs `pkexec apt-get install`, or the desktop's installer opens it. |
| Android release app | System | Downloaded off metered networks. **Install** checks that the APK is Valen, newer, and signed by the same key, then hands it to `PackageInstaller`. On Android 12+ this asks nothing once Valen itself installed the app. |
| Nix, source build, Android debug app | Tell | Only says a release is out, with a link to its page. |

## The signing key (required)

Android installs an update only when it is signed with the key of the installed app. The workflow
refuses to run without a keystore. `release-files.sh` also refuses an APK whose signer differs from
the last release's.

**Chosen (2026-11-17): the debug key below.** The repository's secrets hold it, so phones that
already have Valen update in place. To move to a real key later, see the second option.

**Keep the key the phone builds already use.** APKs built on the development machine are signed with
`~/.android/debug.keystore`. Giving the workflow that keystore lets phones that already have Valen
update in place:

```bash
base64 -w0 ~/.android/debug.keystore | gh secret set ANDROID_KEYSTORE_BASE64 -R dodo986/valen
gh secret set ANDROID_KEYSTORE_PASSWORD -R dodo986/valen --body android
gh secret set ANDROID_KEY_ALIAS -R dodo986/valen --body androiddebugkey
gh secret set ANDROID_KEY_PASSWORD -R dodo986/valen --body android
```

**Or make a real release key.** Phones then uninstall Valen once and install the new APK by hand.
Uninstalling removes the app's data. Keep the keystore safe: losing it means no more updates.

```bash
keytool -genkeypair -v -keystore valen-release.jks -alias valen -keyalg RSA -keysize 4096 -validity 36500
```

To sign local builds with the same key, put these in `local.properties` (git-ignored):

```properties
VALEN_KEYSTORE=/absolute/path/to/valen-release.jks
VALEN_KEYSTORE_PASSWORD=…
VALEN_KEY_ALIAS=valen
VALEN_KEY_PASSWORD=…
```

## Lyrics secrets

`LYPRIO_BASE_URL` and `LYPRIO_HMAC_KEY` are Lyprio's signed endpoint and key — the pair Metrolist's
published build carries, which is why Valen uses it instead of a public address. They are the primary
lyrics source; without them a build leaves Lyprio out and lyrics come from LiriQo, a service's own
card and LRCLIB. The release workflow warns when either is missing (the builds still ship, just
without word-timed Lyprio lyrics).

The pair never goes in the source. The workflow takes it from repository secrets; a hand build can
give it in the environment or in the git-ignored `local.properties` (the same two names), and a
running app can be pointed at another Lyprio with `VALEN_LYPRIO_URL`/`VALEN_LYPRIO_KEY` or
`$XDG_CONFIG_HOME/valen/lyprio.json`. To set the repository's:

```bash
gh secret set LYPRIO_BASE_URL -R dodo986/valen --body "https://<lyprio-host>"
gh secret set LYPRIO_HMAC_KEY -R dodo986/valen --body "<base64, 32 bytes>"
```

A newer Metrolist release can carry a changed pair; it is read from that release's published APK (the
dex string pool), and checked with a signed protocol-2 search before it is used.

## A release by hand

The same steps, locally:

```bash
source scripts/env.sh
V=1.0.0-alpha.3
CODE=$(./gradlew -q -Pvalen.version=$V printVersion | awk '{print $2}')
LYPRIO_BASE_URL=... LYPRIO_HMAC_KEY=... \
  ./gradlew -Pvalen.version=$V :app-android:assembleRelease :app-desktop:packageAppImage :app-desktop:packageDeb
VALEN_VERSION=$V scripts/appimage.sh
VALEN_REPO=dodo986/valen scripts/release-files.sh $V $CODE build/release
```

Upload every `Valen-*` file first and `valen-release.json` last.
