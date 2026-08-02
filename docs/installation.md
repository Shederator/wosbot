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

Source builds additionally require basic Git and terminal usage, Apache Maven
3.8 or newer, and Git LFS. Install the common tools from PowerShell:

```powershell
winget install Microsoft.Git
winget install EclipseAdoptium.Temurin.21.JDK
winget install GitHub.GitLFS
```

Download Maven from <https://maven.apache.org/download.cgi>, add its `bin`
directory to `PATH`, and verify the toolchain:

```powershell
java -version
mvn -version
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
mvn clean install package
```

On Windows, the helper script performs the same build with one retry for transient file-lock issues:

```batch
fg-build.bat
```

Build outputs are written under `fg-app/target`, including
`frostguard-<version>.jar` and the desktop bundle ZIP. End users should extract
the ZIP and launch `Start Frostguard.bat`; the `target` path is only for source
builds.

### Run a source build

Run the generated application JAR from the repository root:

```sh
java -jar fg-app/target/frostguard-<version>.jar
```

Replace `<version>` with the generated version, for example `2.1.0`.

## Migrating an older installation

Do not overwrite a new installation with an entire old Frostguard folder.
Migration of legacy configuration and database files is not yet automated; keep
a backup and copy individual `database.*` files only when intentionally carrying
existing settings forward.
