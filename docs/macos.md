# macOS Setup (BlueStacks)

This document covers macOS-specific runtime and packaging for Frostguard 3 with
BlueStacks. For the normal Windows Stable/Nightly MSI flow, start with
[Install Frostguard on Windows](installation.md).

macOS Stable/Nightly packages are built on GitHub-hosted `macos-latest` runners
with jpackage (`app-image` + `.pkg`). They ship a bundled Java runtime, ADB, OCR
models, and Tesseract natives so end users do not need Terminal, Homebrew, or a
separate JDK.

Apple notarization is not part of the first Mac release. Gatekeeper may require
**Open** / Privacy & Security approval the first time, similar to Windows
Unknown publisher warnings on MSI.

## Runtime requirements

- macOS 12+ on Apple Silicon (recommended; packages target `mac-aarch64`).
- [BlueStacks](https://www.bluestacks.com/) for Mac (`BlueStacks.app` or BlueStacks Air).
- No separate Java or `brew install tesseract` for packaged installs.

Configure BlueStacks for a stable `720x1280` display at `320 DPI`.

Inside Whiteout Survival:

- Set language to English.
- Disable day/night effects.
- Disable snow effects.
- Keep graphics settings stable between runs.

## Install a published pkg

1. Download the macOS `.pkg` from the matching GitHub Release (Stable or Nightly).
2. Open the pkg and complete installation (typically under `/Applications`).
3. Start **Frostguard** or **Frostguard Nightly**.
4. Open **Configuration** and confirm BlueStacks Air is selected. Browse to
   `/Applications/BlueStacks.app` if auto-detect fails.

Stable and Nightly remain separate applications with separate workspaces under
`~/.frostguard/workspaces/`, same channel model as Windows.

## BlueStacks setup

### Enable ADB

**Settings → Advanced → Android Debug Bridge → ON → Save changes**

Frostguard prefers BlueStacks’ bundled `hd-adb`:

```text
/Applications/BlueStacks.app/Contents/MacOS/hd-adb
```

Fallback ADB is bundled at `Contents/app/lib/adb/adb` inside the app image.

### Display settings

- Resolution: `720 x 1280` portrait
- DPI: `320`
- Language: `English`

### Profile emulator numbers

Use the ADB port (`5555`, `5565`, `5615`, …) or `host:port`. Indexes `0`, `1`,
`2` map relative to the configured base port (default `5555`).

### Close emulator

BlueStacks on macOS has no safe CLI shutdown. Idle close leaves BlueStacks
running and only releases the bot slot.

## Build from source (developers)

```sh
brew install tesseract leptonica   # packaging machine only; natives are copied into the .app
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"

./mvnw -Djavafx.platform=mac-aarch64 \
  -Dmacos.app.image -Dmacos.pkg \
  -Pmacos-app-image,macos-pkg \
  -pl packaging/desktop -am package

python3 build-support/verification/verify_app_image.py \
  packaging/desktop/target/app-image/Frostguard.app \
  --platform macos
```

Outputs:

- `packaging/desktop/target/app-image/Frostguard.app`
- `packaging/desktop/target/installers/stable/*.pkg`

Add `-Pwindows-nightly` (shared channel identity profile) for Nightly branding.

## Out of scope (follow-ups)

- DMG distribution
- Apple Developer ID signing / notarization
- LaunchAgent watcher login items and `pmset` sleep policies
