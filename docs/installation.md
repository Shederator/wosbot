# Installation

This guide covers installing a verified Frostguard build, configuring the
required emulator, and building the project from source on Windows.

## Choose a build

| Build | Use it when | Download |
|:------|:------------|:---------|
| Stable | You want a tested, versioned build that changes only with a release | [Latest Stable release](https://github.com/Shederator/wosbot/releases/latest) |
| Nightly | You want the latest authenticated preview without replacing Stable | [Nightly releases](https://github.com/Shederator/wosbot/releases) |
| PR build | You want to test one or more open pull requests | Run `/build-pr` in Discord `#request-a-build` |

Stable and Nightly use self-contained Windows installers and do not require a
separately installed Java runtime. Their automatic update feeds are signed by
the Frostguard project. The installers currently have no Windows verified
publisher, so Windows may show an **Unknown publisher** or SmartScreen warning.
Nightly may contain unfinished changes; PR builds additionally contain
unmerged code and continue to use the temporary ZIP format.

## Install a downloaded build

1. Open the desired release and download its Windows x64 MSI installer.
2. Confirm that the download comes from the official `Shederator/wosbot`
   GitHub release. A Windows publisher identity is not currently expected.
3. Choose whether to create a desktop shortcut and complete the per-user
   installation. The final page starts `Frostguard` or `Frostguard Nightly` by
   default; clear the checkbox if you do not want to launch it yet.
4. Open **Configuration** and select the emulator command-line controller.

Stable and Nightly install as separate applications. They can run side by side
and use separate workspaces, databases, profiles, schedules, Telegram settings,
logs, caches, and locks.

On the first Nightly start, Frostguard offers either a fresh configuration or a
one-time snapshot of the matching Stable workspace. Stable and its watcher must
be closed during the copy. Later changes are not synchronized, and Nightly data
is never copied back into Stable automatically.

## Emulator Setup

Supported emulators are MuMu Player, LDPlayer, and MEmu. MuMu Player is recommended.

Use these emulator display settings:

- Resolution: `720x1280`
- DPI: `320`
- CPU: 4 cores recommended
- Memory: 2 GB recommended
- Frame rate: 30 FPS optional

Start the emulator once and confirm Android boots normally.

## Game Setup

Install Whiteout Survival from Google Play inside the emulator.

In game settings:

- Set the language to English.
- Disable day/night effects.
- Disable snow effects.
- Use normal graphics and 30 FPS if available.

## Configure Frostguard

Open the Configuration screen and select the emulator's command-line
controller, not its graphical executable. A common MuMu path is:

```text
C:\Program Files\Netease\MuMuPlayer\nx_main\MuMuManager.exe
```

## Build from source

Source builds additionally require basic Git and terminal usage plus Git LFS.
The checked-in Maven Wrapper downloads the pinned Maven version. Install the
common tools from PowerShell:

```powershell
winget install Microsoft.Git
winget install EclipseAdoptium.Temurin.21.JDK
winget install GitHub.GitLFS
```

Verify the toolchain from the repository root:

```powershell
java -version
mvnw.cmd -version
git lfs version
```

### Source checkout

Clone the repository and fetch LFS assets:

```sh
git clone https://github.com/Shederator/wosbot.git
cd wosbot
git lfs install
git lfs pull
```

### Build

Run the full build from the repository root:

```sh
./mvnw package
```

On Windows Command Prompt, use the wrapper batch launcher:

```batch
mvnw.cmd package
```

The build writes module artifacts below their respective `target` directories
and a transitional desktop bundle ZIP below `packaging/desktop/target`. That ZIP
is used for temporary PR testing and is not the installed Stable/Nightly product.
End users should use the published MSI installer; individual module JARs are not
standalone distributions.

### Build the native Windows package

Native packages must be built on Windows. Build and smoke-test the
self-contained application image with the JDK 21 `jpackage` tool:

```powershell
.\mvnw.cmd -Pwindows-app-image package
python build-support/verification/verify_app_image.py packaging/desktop/target/app-image/Frostguard
powershell -ExecutionPolicy Bypass -File build-support/verification/smoke_test_app_image.ps1 -ImagePath packaging/desktop/target/app-image/Frostguard
```

This produces
`packaging/desktop/target/app-image/Frostguard/Frostguard.exe`. The image
contains its Java runtime, so a machine running it does not need a separate
JDK.

Building the versioned installer additionally requires WiX Toolset 3.14.1 with
`candle.exe` and `light.exe` on `PATH`:

```powershell
.\mvnw.cmd "-Pwindows-app-image,windows-installer" package
```

The installer is written below `packaging/desktop/target/installers/stable`. It is a
per-user installer and defaults to
`%LOCALAPPDATA%\Frostguard`; the installer can offer another location.
Normal `mvn package` remains platform-neutral and does not invoke `jpackage` or
install Frostguard.

Build the independent Nightly identity by adding its explicit profile:

```powershell
.\mvnw.cmd "-Pwindows-app-image,windows-installer,windows-nightly" package
python build-support/verification/verify_app_image.py `
  "packaging/desktop/target/app-image/Frostguard Nightly" `
  --channel nightly --product-name "Frostguard Nightly"
```

Nightly uses its own application ID, upgrade UUID, installation directory,
shortcut, launcher identity, workspace channel, and update feed.

Stable and Nightly release builds expose a channel-specific update feed in
**Config > Updates**. Development and pull-request builds cannot install from
release feeds. Frostguard first verifies the project Ed25519 signature over the
manifest, then requires the manifest identity, immutable download, size, and
SHA-256 to match. A Windows Authenticode signer is checked in addition when the
build and manifest declare one. The current public ZIP feeds are not used by
this updater; automatic installer updates stay disabled in ordinary local and
PR builds. After confirmation, an in-app update closes Frostguard, applies the
same published MSI with compact progress but without the first-install wizard,
and reopens the same channel and workspace. Running a downloaded installer
manually remains interactive.

### Run a source build

Run the application from the repository root through the same Maven Wrapper:

```sh
./mvnw javafx:run
```

On Windows Command Prompt, use `mvnw.cmd javafx:run`; in PowerShell, use
`.\mvnw.cmd javafx:run`. The JavaFX goal compiles the required reactor modules
and starts only the desktop module; developers do not need to locate a
versioned JAR or assemble its classpath. It automatically uses the ignored
`.frostguard-dev/` workspace in that clone or worktree. No runtime argument is
required, and simultaneous production and worktree runs do not share data.

Installed runs use named workspaces below
`%USERPROFILE%\.frostguard\workspaces\<channel>\<name>\`. Each workspace owns
its database, configuration, logs, custom tasks, cache, Telegram watcher state,
and process lock. A workspace can be opened by only one Frostguard process at a
time. A second normal launch reports the already-running instance instead of a
generic JVM error. Advanced users can run another isolated instance with
`Frostguard.exe --workspace <name>` or
`Frostguard Nightly.exe --workspace <name>`.

Close Frostguard before updating or uninstalling it. The installer refuses to
continue while the matching desktop process is running, so Windows cannot leave
an unregistered but partially installed application behind. The channel-specific
background watcher is stopped automatically during maintenance.

## Migrating an older installation

Do not overwrite a new workspace with an entire old Frostguard folder. Frostguard
3.0 does not migrate the legacy flat `.frostguard` watcher files or a 2.x
database. Keep a backup and recreate settings; copy only reviewed custom-task
source files into the new workspace.
