# SPDX-License-Identifier: GPL-3.0-only
{
  description = "Valen desktop AppImage with its Linux runtime dependencies";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      # Releases provide an x86_64 AppImage (including its JVM). Bump version and hash for a new
      # release; the hash is the release manifest's linux-appimage sha256 (valen-release.json).
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        # Chrome includes Widevine for Spotify. This is local to this package
        # set; no global allowUnfree setting or browser installation is needed.
        config.allowUnfreePredicate = pkg: nixpkgs.lib.getName pkg == "google-chrome";
      };
      pname = "valen";
      version = "1.0.0-alpha.14";
      src = pkgs.fetchurl {
        url = "https://github.com/dodo986/valen/releases/download/v${version}/Valen-${version}-x86_64.AppImage";
        hash = "sha256-uc3S+Uhmlqhj1vFyZYJHocWUmBh+YKBKncAj+DzMs90=";
      };
      contents = pkgs.appimageTools.extract { inherit pname version src; };
      valen = pkgs.appimageTools.wrapType2 {
        inherit pname version src;

        # appimageTools already supplies GTK, NSS, libsecret, X11/Wayland,
        # GL/Vulkan, ALSA/PulseAudio/PipeWire, D-Bus, xdg-utils and zenity.
        extraPkgs = p: [ p.ffmpeg p.cacert p.libnotify ];

        profile = ''
          # All three sign-ins use Chrome via localhost CDP, not Electron or an
          # OAuth URL handler. Select the Nix wrapper, never an unpatched binary.
          export VALEN_CHROME_BIN="''${VALEN_CHROME_BIN:-${pkgs.google-chrome}/bin/google-chrome-stable}"
          export VALEN_FFPLAY_BIN="''${VALEN_FFPLAY_BIN:-${pkgs.ffmpeg}/bin/ffplay}"
          export VALEN_FFMPEG_BIN="''${VALEN_FFMPEG_BIN:-${pkgs.ffmpeg}/bin/ffmpeg}"
          export VALEN_FFPROBE_BIN="''${VALEN_FFPROBE_BIN:-${pkgs.ffmpeg}/bin/ffprobe}"
          export SSL_CERT_FILE="''${SSL_CERT_FILE:-${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt}"
          export NIX_SSL_CERT_FILE="''${NIX_SSL_CERT_FILE:-$SSL_CERT_FILE}"
          # The bundled JVM uses its own cacerts, not these PEM variables.
          # Keep the session bus, display, GPU drivers and Chrome profile from
          # the user's session. Do not disable Chromium's sandbox or keyring.
        '';

        extraInstallCommands = ''
          install -Dm644 ${contents}/valen.desktop $out/share/applications/valen.desktop
          substituteInPlace $out/share/applications/valen.desktop \
            --replace-fail 'Exec=Valen' "Exec=$out/bin/valen" \
            --replace-fail 'Categories=AudioVideo' 'Categories=AudioVideo;Audio;Player;'
          install -Dm644 ${contents}/valen.png $out/share/icons/hicolor/512x512/apps/valen.png
          install -Dm644 /dev/stdin $out/share/valen/package-version <<< "${version}"
        '';

        meta = {
          description = "Music player for Spotify, YouTube Music and SoundCloud";
          homepage = "https://github.com/dodo986/valen";
          license = pkgs.lib.licenses.gpl3Only;
          platforms = [ system ];
          mainProgram = "valen";
        };
      };
    in {
      packages.${system}.default = valen;
      apps.${system}.default = {
        type = "app";
        program = "${valen}/bin/valen";
        meta.description = valen.meta.description;
      };
      checks.${system}.package = valen;
    };
}
