# Windows Setup

This document contains advanced Windows runtime and native-packaging details.
For a normal Stable, Nightly, or PR-build setup, start with
[Install Frostguard on Windows](installation.md). Developers should first
complete the separate [developer setup](development.md).

The [latest Stable release](https://github.com/Shederator/wosbot/releases/latest)
provides the tested Windows installer. The permanent
[Latest Nightly](https://github.com/Shederator/wosbot/releases/tag/nightly)
entry points to the current authenticated preview with a separate product
identity. Git, Git LFS, and a JDK are needed only when building from source; the
checked-in Maven Wrapper supplies Maven.

## Source development requirements

The following requirements and commands are for developers. They are not steps
in the normal Stable or Nightly installation.

### Requirements

- Windows 10 or Windows 11.
- Java JDK 21 or newer.
- Git and Git LFS.

WiX Toolset 3.14.1 is required only when producing the native MSI installer.
Running an installed native build does not require a separately installed JDK.

Recommended installs:

```powershell
winget install Microsoft.Git
winget install EclipseAdoptium.Temurin.21.JDK
winget install GitHub.GitLFS
```

From the repository root, verify:

```powershell
java -version
.\mvnw.cmd -version
git lfs version
```

## Native application updates

Installed Frostguard builds provide **Config > Updates** when their product
identity has a configured release manifest. The update flow:

1. selects only a newer artifact for the running channel, Windows, and x64;
2. shows the version, release notes, channel, and size before confirmation;
3. downloads below the selected workspace's `cache\updates` directory;
4. verifies the project Ed25519 signature, declared size, SHA-256, and any
   configured Authenticode signer;
5. stops scheduling and workspace services, closes SQLite, and releases the
   workspace lock;
6. exits before an external waiter applies the installer with compact Windows
   progress and no setup decisions;
7. preserves the current installation directory and restarts the same channel
   and workspace after Windows Installer reports success.

Development and PR-test packages cannot use automatic release updates. A
release whose manifest is not validly signed by the embedded project key is
never handed off. Authenticode remains an optional additional check.

Interrupted downloads remain `.part` files and resume when the server supports
byte ranges. A failed size, hash, or signature check prevents installer handoff
and leaves the current installation untouched. A failed installer upgrade uses
Windows Installer rollback, attempts to reopen the retained application, and
shows an update failure instead of silently claiming success.

Until release binaries have an Authenticode publisher, Java-only Stable and
Nightly updates keep their native desktop and watcher bootstrap files
byte-identical to the already accepted Stable 2.1.0 and Nightly 26.8.12004
launchers. The MSI version still increases for every release, and Frostguard
displays the version embedded in its application JAR. Changes to the bootstrap,
icon, or packaged JDK require a new Windows reputation decision; Authenticode
signing remains the durable solution.

## Source build commands and native packaging

Use the checked-in Maven Wrapper:

```powershell
.\mvnw.cmd package
```

The wrapper only builds the reactor. It does not stop running processes, install
Frostguard, or mutate user data.

To build a self-contained Windows application image:

```powershell
.\mvnw.cmd -Pwindows-app-image package
python build-support/verification/verify_app_image.py packaging/desktop/target/app-image/Frostguard
powershell -ExecutionPolicy Bypass -File build-support/verification/smoke_test_app_image.ps1 -ImagePath packaging/desktop/target/app-image/Frostguard
```

To build both the application image and a versioned per-user MSI installer,
install WiX Toolset 3.14.1, ensure `candle.exe` and `light.exe` are on `PATH`,
then run:

```powershell
.\mvnw.cmd "-Pwindows-app-image,windows-installer" package
```

Outputs remain below `packaging/desktop/target`: the directly runnable image is
at `app-image/Frostguard`, and the installer is under `installers/stable`. Native
packaging is opt-in because it is Windows-specific; ordinary
`.\mvnw.cmd package` continues to build and test the platform-neutral reactor.

Add `windows-nightly` to produce `Frostguard Nightly` with a distinct application
ID, upgrade UUID, install directory, shortcut, workspace channel, and update
feed:

```powershell
.\mvnw.cmd "-Pwindows-app-image,windows-installer,windows-nightly" package
python build-support/verification/verify_app_image.py `
  "packaging/desktop/target/app-image/Frostguard Nightly" `
  --channel nightly --product-name "Frostguard Nightly"
```

## Runtime Requirements

Configure the emulator for a stable `720x1280` display at `320 DPI`. MuMu Player is recommended.

Inside Whiteout Survival:

- Set language to English.
- Disable day/night effects.
- Disable snow effects.
- Keep graphics settings stable between runs.

The application currently packages Windows ADB and Tesseract assets from `tools/`.

## Starting Frostguard

After downloading a Frostguard 3.0 installer from the official GitHub release,
complete the per-user installation. The installer currently has no Windows
verified publisher, so an **Unknown publisher** or SmartScreen warning is
expected. Run the installed `Frostguard.exe` or `Frostguard Nightly.exe`.
The per-user installer defaults to `%LOCALAPPDATA%\Frostguard`, while
all mutable databases, configuration, logs, watcher state, and custom tasks
remain in the selected workspace below `%USERPROFILE%\.frostguard`. The
installation directory is treated as read-only application content.

The installer offers a checked desktop-shortcut option and a checked launch
option on its final page. Only the desktop application is exposed in the Start
menu and on the desktop; the channel-specific Telegram watcher is an internal
background launcher. Close the desktop application before update or uninstall.
Maintenance is blocked while it is running rather than leaving locked files and
a partially removed installation.

Stable and Nightly never share live settings. The first Nightly start can copy
a one-time Stable snapshot only while Stable and its watcher are closed and
before Nightly persistence opens. Choosing a fresh start or completing the copy
is recorded in Nightly; no continuous synchronization or automatic reverse
migration exists.

For a source build, run from the repository root:

```powershell
.\mvnw.cmd javafx:run
```

This automatically creates and uses `<worktree>\.frostguard-dev\`. Each
worktree therefore has isolated database, logs, custom tasks, cache, and
Telegram watcher state. `git clean -xdf` intentionally removes this disposable
development workspace.

For an additional installed instance of the same channel, select another named
workspace explicitly, for example `Frostguard.exe --workspace bot-2`. The
default launch continues to use the `default` workspace and refuses a second
owner, while each named workspace keeps its own data, ports, locks, and watcher.

For automatic startup through scripts or Task Scheduler:

```powershell
.\mvnw.cmd "-Djavafx.args=--autostart" javafx:run
```

Installed and extracted bundle deployments should use their supplied launcher;
the Maven command is only for source development.

## Scheduled Automation

Optional Task Scheduler templates are in `docs/schedule-autostart/`.

Use them when the machine should wake, run Frostguard for a fixed window, stop the emulator, and return to standby. Edit imported task actions before enabling them:

- Update the path to `launch.ps1`.
- Update the working directory to your Frostguard installation.
- Adjust the schedule times.
- Confirm the emulator process name, for example `MuMuNxMain`.

The templates are examples and should be reviewed on the target Windows machine before unattended use.
