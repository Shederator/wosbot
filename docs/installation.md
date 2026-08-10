# Installation

This guide covers installing a verified Frostguard build, configuring the
required emulator, and building the project from source on Windows.

## Choose a build

| Build | Use it when | Download |
|:------|:------------|:---------|
| Stable | You want a tested, versioned build that changes only with a release | [Latest Stable](https://github.com/Shederator/wosbot/releases/latest/download/frostguard-windows-desktop-bundle.zip) |
| Nightly | You want the latest `main` build, updated daily | [Latest Nightly](https://github.com/Shederator/wosbot/releases/download/nightly/frostguard-windows-desktop-bundle.zip) |
| PR build | You want to test one or more open pull requests | Run `/build-pr` in Discord `#request-a-build` |

Stable and Nightly are public Windows desktop bundles. They require Java 21,
but not Git, Git LFS, or Maven. Nightly may contain unfinished changes; PR
builds additionally contain unmerged code.

## Install a downloaded build

1. Install a Java 21 JDK, such as [Eclipse Temurin](https://adoptium.net/temurin/releases/?version=21).
2. Download the desired ZIP from the table above.
3. Extract the complete ZIP into an empty folder. Do not run Frostguard from inside the ZIP.
4. Double-click `Start Frostguard.bat`.
5. Open **Configuration** and select the emulator command-line controller.

Keep the extracted installation together. Its launcher, application JAR,
runtime libraries, OCR data and templates are all required.

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
and the transitional desktop bundle ZIP below `packaging/desktop/target`. End
users should extract the ZIP and launch `Start Frostguard.bat`; individual
module JARs are not standalone distributions.

### Run a source build

Run the application from the repository root through the same Maven Wrapper:

```sh
./mvnw javafx:run
```

On Windows Command Prompt, use `mvnw.cmd javafx:run`; in PowerShell, use
`.\mvnw.cmd javafx:run`. The JavaFX goal compiles the required reactor modules
and starts only the desktop module; developers do not need to locate a
versioned JAR or assemble its classpath.

## Migrating an older installation

Do not overwrite a new installation with an entire old Frostguard folder.
Migration of legacy configuration and database files is not yet automated; keep
a backup and copy individual `database.*` files only when intentionally carrying
existing settings forward.
