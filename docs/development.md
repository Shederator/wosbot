# Developer setup

This guide is only for contributors and developers working from source. It is
separate from the [installation guide](installation.md): you do not need to
install Stable, Nightly, or a PR build before building the repository.

## Prerequisites

- Java JDK 21 or newer
- Git
- Git LFS
- Basic command-line and Maven knowledge

The checked-in Maven Wrapper downloads the pinned Maven version, so a separate
Maven installation is not required.

On Windows, the common tools can be installed from PowerShell:

```powershell
winget install Microsoft.Git
winget install EclipseAdoptium.Temurin.21.JDK
winget install GitHub.GitLFS
```

## Check out the source

```sh
git clone https://github.com/Shederator/wosbot.git
cd wosbot
git lfs install
git lfs pull
```

Verify the toolchain:

```sh
java -version
./mvnw -version
git lfs version
```

In Windows PowerShell, replace `./mvnw` with `.\mvnw.cmd`. In Windows Command
Prompt, use `mvnw.cmd`.

## Build, test, and run

Run commands from the repository root:

| Goal | Command | Result |
|:-----|:--------|:-------|
| Build and test everything | `./mvnw package` | Builds the complete reactor, runs its tests, and creates the transitional desktop ZIP |
| Test one area | `./mvnw -pl modules/tasks -am test` | Tests the selected module and required upstream modules |
| Start Frostguard | `./mvnw javafx:run` | Compiles what is needed and starts an isolated development instance |
| Reproduce a clean verification build | `./mvnw clean install` | Deletes generated output, then rebuilds and tests the reactor |

For example, use `modules/automation` or `modules/vision` instead of
`modules/tasks` when those are the affected modules. Run at least the affected
module tests; use `./mvnw package` for changes spanning modules or packaging.

Do not use `clean` for ordinary iteration unless you need to remove existing
generated output. Generated `target/` directories must not be committed.

## Run from source

`./mvnw javafx:run` is the normal development start command. It avoids locating
a versioned JAR or assembling a classpath manually.

Source and IDE launches use the ignored `.frostguard-dev/` directory in that
clone or worktree. It keeps the development database, configuration, logs,
custom tasks, cache, Telegram state, and process lock separate from installed
Stable and Nightly workspaces. `git clean -xdf` intentionally removes this
disposable development workspace.

Developers still need the emulator and game settings from
[Configure the emulator and game](installation.md#configure-the-emulator-and-game).

To test autostart behavior from PowerShell:

```powershell
.\mvnw.cmd "-Djavafx.args=--autostart" javafx:run
```

## Build outputs and Windows packaging

Normal module artifacts are written below their respective `target/`
directories. `./mvnw package` also writes the transitional PR-test desktop ZIP
below `packaging/desktop/target`; it does not install or update Frostguard.
Individual module JARs are not standalone distributions.

Creating a self-contained application image or MSI is a separate,
Windows-specific packaging task. See:

- [Windows source builds and native packaging](windows.md#source-build-commands-and-native-packaging)
- [CI and bundle verification](../build-support/verification/README.md)
- [Release process](releases.md)
