# Install Frostguard on Windows

This guide is for running a published or temporary Frostguard build. Choose
**exactly one** of the three build options below, then complete the shared
emulator and game setup once. The options are alternatives, not consecutive
steps.

Developers who want to build, test, or run the repository should use the
separate [developer setup](development.md).

## Choose a build

| Order | Build | Use it when | Distribution |
|:------|:------|:------------|:-------------|
| **1 — Recommended** | Stable | You want to use Frostguard normally | Self-contained Windows x64 MSI |
| **2 — Preview** | Nightly | You want newer changes and accept unfinished or unstable behavior | Separate self-contained Windows x64 MSI |
| **Testing only** | PR build | You were asked to test specific open pull requests | Temporary ZIP requiring Java 21 |

If you are unsure, install **Stable**.

## Option 1: Install Stable (recommended)

1. Open the [latest Stable release](https://github.com/Shederator/wosbot/releases/latest).
2. Download its Windows x64 MSI installer.
3. Confirm that the file comes from the official `Shederator/wosbot` GitHub
   release, then run it. A Windows **Unknown publisher** or SmartScreen warning
   is currently expected because the project does not yet have a verified
   Windows publisher.
4. Choose whether to create a desktop shortcut and complete the per-user
   installation. The final page starts **Frostguard** by default.
5. Continue at [Configure the emulator and game](#configure-the-emulator-and-game).

The installer includes the Java runtime. You do not need Git, Git LFS, Maven,
or a separate Java installation.

## Option 2: Install Nightly

Nightly is the second choice for users who deliberately want preview changes.
It may contain unfinished or unstable behavior.

1. Open the permanent [Latest Nightly](https://github.com/Shederator/wosbot/releases/tag/nightly)
   channel page.
2. Download its Windows x64 MSI installer.
3. Confirm that the file comes from the official `Shederator/wosbot` GitHub
   release, then run it. The same **Unknown publisher** or SmartScreen warning
   described for Stable is currently expected.
4. Complete the installer and start **Frostguard Nightly**.
5. Continue at [Configure the emulator and game](#configure-the-emulator-and-game).

Stable and Nightly install as separate applications. They can run side by side
and use separate workspaces, databases, profiles, schedules, Telegram settings,
logs, caches, and locks.

On the first Nightly start, Frostguard offers either a fresh configuration or a
one-time snapshot of the matching Stable workspace. Stable and its watcher must
be closed during the copy. Later changes are not synchronized, and Nightly data
is never copied back into Stable automatically.

The Nightly installer is also self-contained and needs no separate development
toolchain.

## Option 3: Request a PR build

PR builds are for testing unmerged pull requests. They are temporary, expire
automatically, and are not installed Stable or Nightly releases.

1. Join the [Frostguard Discord](https://discord.gg/sUthSHRVvU).
2. Open [**#request-a-build**](https://discord.com/channels/1475434539495981137/1533460326111117322).
3. Run `/build-pr prs: <PR number>`. Add further open PR numbers when you need a
   combined test build.
4. Review the pinned build plan and confirm it.
5. When the result appears in the same channel, download the ZIP, install a
   [Java 21 JDK](https://adoptium.net/temurin/releases/?version=21) if necessary,
   and extract the complete ZIP into an empty folder.
6. Start `Start Frostguard.bat`, then continue at
   [Configure the emulator and game](#configure-the-emulator-and-game).

Keep the extracted PR-build folder together. The launcher, application JAR,
libraries, OCR data, and templates are all required. Automatic Stable/Nightly
updates are disabled in PR builds.

## Configure the emulator and game

The remaining setup applies once to whichever build option you chose above.

### Emulator setup

Supported emulators are MuMu Player, LDPlayer, and MEmu. MuMu Player is recommended.

Use these emulator display settings:

- Resolution: `720x1280`
- DPI: `320`
- CPU: 4 cores recommended
- Memory: 2 GB recommended
- Frame rate: 30 FPS optional

Start the emulator once and confirm Android boots normally.

### Game setup

Install Whiteout Survival from Google Play inside the emulator.

In game settings:

- Set the language to English.
- Disable day/night effects.
- Disable snow effects.
- Use normal graphics and 30 FPS if available.

### Configure Frostguard

Open the Configuration screen and select the emulator's command-line
controller, not its graphical executable. A common MuMu path is:

```text
C:\Program Files\Netease\MuMuPlayer\nx_main\MuMuManager.exe
```

Installed runs use named workspaces below
`%USERPROFILE%\.frostguard\workspaces\<channel>\<name>\`. Each workspace owns
its database, configuration, logs, custom tasks, cache, Telegram watcher state,
and process lock. A workspace can be opened by only one Frostguard process at a
time. A second normal launch reports the already-running instance instead of a
generic JVM error. Advanced users can run another isolated instance with
`Frostguard.exe --workspace <name>` or
`Frostguard Nightly.exe --workspace <name>`. Source runs use the isolated
`.frostguard-dev/` workspace described in the [developer setup](development.md).

Close Frostguard before updating or uninstalling it. The installer refuses to
continue while the matching desktop process is running, so Windows cannot leave
an unregistered but partially installed application behind. The channel-specific
background watcher is stopped automatically during maintenance.

## Migrating an older installation

Do not overwrite a new workspace with an entire old Frostguard folder. Frostguard
3.0 does not migrate the legacy flat `.frostguard` watcher files or a 2.x
database. Keep a backup and recreate settings; copy only reviewed custom-task
source files into the new workspace.
