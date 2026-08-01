# Installation

This guide covers building and launching Frostguard from source on Windows.

## Prerequisites

- Basic Git and terminal usage.
- Windows 10 or Windows 11.
- Java JDK 21 or newer.
- Apache Maven 3.8 or newer.
- Git LFS for large binary assets.

Install common tools from PowerShell:

```powershell
winget install Microsoft.Git
winget install EclipseAdoptium.Temurin.21.JDK
winget install GitHub.GitLFS
```

Download Maven from <https://maven.apache.org/download.cgi> and add its `bin` directory to `PATH`.

Verify the toolchain:

```powershell
java -version
mvn -version
git lfs version
```

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

## Source Checkout

Clone the repository and fetch LFS assets:

```sh
git clone <repository-url>
cd frostguard
git lfs install
git lfs pull
```

If certificate configuration blocks LFS temporarily:

```sh
GIT_SSL_NO_VERIFY=true git lfs pull
```

## Build

Run the full build from the repository root:

```sh
mvn clean install package
```

On Windows, the helper script performs the same build with one retry for transient file-lock issues:

```batch
fg-build.bat
```

Build outputs are written under `fg-app/target`, including `frostguard-<version>.jar` and the desktop bundle ZIP.

## Run

From the repository root:

```sh
java -jar fg-app/target/frostguard-<version>.jar
```

Replace `<version>` with the generated version, for example `2.1.0`.

## Configure

Before starting automation, open the Configuration screen and set the emulator executable path.

Common MuMu path:

```text
C:\Program Files\Netease\MuMuPlayer\nx_main\MuMuManager.exe
```

Copy `database.*` files from an older Frostguard folder only when intentionally migrating existing settings.
