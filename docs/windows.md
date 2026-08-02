# Windows Setup

This document summarizes Windows-specific setup for Frostguard.

The [latest Stable Windows bundle](https://github.com/Shederator/wosbot/releases/latest/download/frostguard-windows-desktop-bundle.zip)
is versioned and remains unchanged until the next Stable release. The
[latest Nightly](https://github.com/Shederator/wosbot/releases/download/nightly/frostguard-windows-desktop-bundle.zip)
is rebuilt daily from `main`. Git, Git LFS and Maven are needed only when
building from source.

## Build Requirements

- Windows 10 or Windows 11.
- Java JDK 21 or newer.
- Apache Maven 3.8 or newer.
- Git and Git LFS.

Recommended installs:

```powershell
winget install Microsoft.Git
winget install EclipseAdoptium.Temurin.21.JDK
winget install GitHub.GitLFS
```

After installing Maven, verify:

```powershell
java -version
mvn -version
git lfs version
```

## Build Commands

Use the standard Maven build:

```powershell
mvn clean install package
```

Or use the Windows helper:

```batch
fg-build.bat
```

The helper stops leftover Java and ADB processes, retries once after transient resource-copy failures, verifies the packaged application JAR, and opens the generated bundle location.

## Runtime Requirements

Configure the emulator for a stable `720x1280` display at `320 DPI`. MuMu Player is recommended.

Inside Whiteout Survival:

- Set language to English.
- Disable day/night effects.
- Disable snow effects.
- Keep graphics settings stable between runs.

The application currently packages Windows ADB and Tesseract assets from `tools/`.

## Starting Frostguard

After downloading a desktop bundle, extract the complete ZIP into an empty
folder and double-click `Start Frostguard.bat`. The launcher locates the
versioned application JAR and reports a clear error if Java 21 is missing.

For a source build, run from the repository root:

```powershell
java -jar fg-app\target\frostguard-<version>.jar
```

For automatic startup through scripts or Task Scheduler:

```powershell
java -jar fg-app\target\frostguard-<version>.jar --autostart
```

## Scheduled Automation

Optional Task Scheduler templates are in `docs/schedule-autostart/`.

Use them when the machine should wake, run Frostguard for a fixed window, stop the emulator, and return to standby. Edit imported task actions before enabling them:

- Update the path to `launch.ps1`.
- Update the working directory to your Frostguard installation.
- Adjust the schedule times.
- Confirm the emulator process name, for example `MuMuNxMain`.

The templates are examples and should be reviewed on the target Windows machine before unattended use.
