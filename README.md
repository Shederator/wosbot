<div align="center">

<img src="https://capsule-render.vercel.app/api?type=waving&color=0:081522,45:17324d,100:4f86a8&height=210&section=header&text=Frostguard&fontSize=72&fontColor=eaf7ff&fontAlignY=36&desc=Whiteout%20Survival%20Automation&descSize=20&descAlignY=58&descColor=b9e2f5" width="100%" alt="Frostguard" />

### A free, open-source Windows automation assistant for Whiteout Survival

Automate recurring tasks, coordinate scheduled events, and manage multiple
accounts through supported Android emulators.

[![Download Stable](https://img.shields.io/badge/Download-Stable-2f855a?style=for-the-badge&logo=github&logoColor=white)](https://github.com/Shederator/wosbot/releases/latest)
[![Join Discord](https://img.shields.io/badge/Join-Discord-5865f2?style=for-the-badge&logo=discord&logoColor=white)](https://discord.gg/sUthSHRVvU)

**Windows 10/11 · Self-contained installer · Java included · Free**

</div>

<p align="center">
  <img src="docs/images/readme/overview.png" width="1000" alt="Frostguard Profile Manager showing multiple profiles and queue controls" />
</p>

## Why Frostguard?

| | Benefit |
|:--:|:--------|
| 👥 | **Manage multiple profiles** from one desktop application |
| 🕒 | **Schedule unattended routines** instead of repeating daily interactions manually |
| 👁️ | **Keep control and visibility** through profile state, queues, and live logs |
| 🔓 | **Open source** so the project and its development remain transparent |
| ♾️ | **Free forever** with no paid tier or locked automation features |
| 🚀 | **Actively developed** with ongoing improvements, fixes, and community testing |

## Install Stable

This is the complete normal-user path. You do not need to follow the Nightly,
PR-build, or developer instructions afterward.

### 1. Prepare the emulator

Install one supported Android emulator and configure its game instance:

| Component | Required setting |
|:----------|:-----------------|
| Operating system | Windows 10 or Windows 11 |
| Emulator | MuMu Player, MEmu, or LDPlayer 9; MuMu Player is recommended |
| Resolution | `720 × 1280` in portrait mode |
| DPI | `320` |
| Game language | English |
| Graphics | Disable Snowfall and Day/Night Cycle; avoid Ultra graphics |

Install Whiteout Survival from Google Play and start it once. LDPlayer users
must also enable local ADB under
**Settings → Other settings → ADB debugging → Enable local connection**.

### 2. Download Frostguard

Open **[Latest Stable](https://github.com/Shederator/wosbot/releases/latest)**
and download the file ending in **`windows-x64.msi`** from **Assets**. Do not
download the JSON manifest.

> **Official downloads only:** Download Frostguard from the
> [`Shederator/wosbot` releases](https://github.com/Shederator/wosbot/releases).
> Do not use installers from mirrors, reposts, or third-party download sites.

Stable is the tested, versioned release intended for normal use. Its installer
already contains Java, ADB, OCR data, and the required runtime libraries.

### 3. Run the installer

Open the MSI. A Windows **Unknown publisher** or SmartScreen warning is
currently expected because the project does not yet have a verified Windows
publisher.

Choose whether to create a desktop shortcut and complete the per-user
installation. The final page starts **Frostguard** by default.

### 4. Connect Frostguard

Open **Configuration** and select the emulator's command-line controller—not
the graphical emulator application.

| Emulator | Common controller path |
|:---------|:-----------------------|
| MuMu Player | `C:\Program Files\Netease\MuMuPlayerGlobal-12.0\shell\MuMuManager.exe` |
| MEmu | `C:\Program Files\Microvirt\MEmu\memuc.exe` |
| LDPlayer 9 | `C:\LDPlayer\LDPlayer9\ldconsole.exe` |

Create or select a Frostguard profile, review the tasks you want enabled, and
start the queue.

The separate [installation guide](docs/installation.md) covers Nightly and PR
builds, named workspaces, migration from Frostguard 2.x, and additional setup
details. Normal Stable installation is complete after the steps above.

## Feature highlights

| Feature | What it automates |
|:--------|:------------------|
| **Multi-profile scheduling** | Account rotation, priorities, independent task configuration, and queue control |
| **Combat and rallies** | Arena, Polar Terror, Bear Trap, Beast Hunting, and Alliance Rallies |
| **City progression** | Training, Research, Furnace priorities, Fire Crystals, and recurring city rewards |
| **Gathering and Intel** | Resource marches, stamina-aware activity, Intel missions, and Experts |
| **Pets and exploration** | Pet Adventure, Tundra Trek, Journey of Light, and Exploration Chests |
| **Alliance and rewards** | Alliance Tech, Gifts, Mail, Nomadic Merchant, and Gift Codes |
| **Recovery and retries** | Rescheduling after recoverable failures, bounded retries, and unhealthy emulator recovery |

| Event configuration | Task overview |
|:-------------------:|:-------------:|
| ![Bear Trap timers, preparation, and rally configuration](docs/images/readme/events.png) | ![Frostguard task list showing ready and executing automation tasks](docs/images/readme/tasks.png) |

See the **[complete feature overview](docs/features.md)** for the broader task
catalog and long-running automation capabilities.

## How Frostguard works

Frostguard controls a supported Android emulator through its command-line
interface and ADB. It reads the visible game interface with OCR and image
recognition, then performs the configured interactions through the emulator.
It does not modify the game client.

Profiles, schedules, runtime decisions, and diagnostic logs remain visible in
one desktop application.

<p align="center">
  <img src="docs/images/readme/runtime.png" width="1000" alt="Frostguard runtime log showing task decisions and profile context" />
</p>

## Other build paths

Stable is the normal choice. Use an alternative only when it matches your
purpose:

| Channel | Choose it when | Start here |
|:--------|:---------------|:-----------|
| **Nightly** | You want the newest preview and accept unfinished or unstable behavior | [Latest Nightly](https://github.com/Shederator/wosbot/releases/tag/nightly) |
| **PR build** | You were asked to test one or more open pull requests | [Discord #request-a-build](https://discord.com/channels/1475434539495981137/1533460326111117322) |
| **Source build** | You want to change, build, test, or debug Frostguard | [Developer setup](docs/development.md) |

Nightly installs as a separate application with separate settings and data. PR
builds contain unmerged code, use temporary ZIP bundles, and expire
automatically.

## For developers

The repository uses Java 21 and the checked-in Maven Wrapper. Git LFS supplies
the required vision and runtime assets.

```sh
git clone https://github.com/Shederator/wosbot.git
cd wosbot
git lfs install
git lfs pull
./mvnw package
```

| Goal | Command |
|:-----|:--------|
| Build and test the complete reactor | `./mvnw package` |
| Test an affected module and its dependencies | `./mvnw -pl modules/tasks -am test` |
| Start an isolated development instance | `./mvnw javafx:run` |
| Run a reproducible clean verification | `./mvnw clean install` |

On Windows PowerShell, use `.\mvnw.cmd` instead of `./mvnw`. Source runs keep
their data in the ignored `.frostguard-dev/` workspace and do not share live
data with installed Stable or Nightly builds.

Read the **[developer setup](docs/development.md)** for tool installation,
module-focused examples, development workspaces, and native Windows packaging.

## License

Frostguard is licensed under the
[GNU Affero General Public License version 3 only](LICENSE) (`AGPL-3.0-only`).
Each contributor retains copyright in their own contributions.

Contributions are accepted under the same license and require a
[Developer Certificate of Origin](DCO) sign-off. See
[Contributing](CONTRIBUTING.md#developer-certificate-of-origin) for details.

## Documentation

| I want to… | Read… |
|:-----------|:------|
| See everything Frostguard can automate | [Feature overview](docs/features.md) |
| Install Stable, Nightly, or a PR build | [Installation guide](docs/installation.md) |
| Build, test, or run from source | [Developer setup](docs/development.md) |
| Understand modules and runtime ownership | [Architecture](docs/architecture.md) |
| Change automation, OCR, or screen interaction | [Design guidelines](docs/design-guidelines.md) |
| Build native Windows packages or configure autostart | [Windows setup](docs/windows.md) |
| Understand release channels and publication | [Release process](docs/releases.md) |

## Community & contribution

The **[Frostguard Discord](https://discord.gg/sUthSHRVvU)** is the central place
for setup help, suggestions, bug discussion, testing, project updates, and
community exchange.

You do not need to write code to contribute. Testing changes, improving
documentation, reporting reproducible bugs, sharing ideas, helping other users,
and making the project easier to discover all matter. Use GitHub's visible
**[Contributing](CONTRIBUTING.md)** tab for the complete guide.

If Frostguard helps you, consider starring the repository to help others
discover the official project.

- Follow priorities on the **[public Frostguard project board](https://github.com/users/Shederator/projects/2)**.
- Support continued development through **[Buy Me a Coffee](https://buymeacoffee.com/Shederator)**.

### Star history

<div align="center">

<a href="https://www.star-history.com/?repos=Shederator%2Fwosbot&type=date&legend=top-left">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=Shederator/wosbot&type=date&theme=dark&legend=top-left&sealed_token=Q4rcyFr92ZWBzZQ20e-IzVUjxqfb5_eM5u09bqV8HyzPBtTvEvoQpkN-YO7JzMG8uRS50EcA9FGzM65sJJmceYHi43KwVqBIFrYbqa7ImNnjTUMFTGryFQ" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=Shederator/wosbot&type=date&legend=top-left&sealed_token=Q4rcyFr92ZWBzZQ20e-IzVUjxqfb5_eM5u09bqV8HyzPBtTvEvoQpkN-YO7JzMG8uRS50EcA9FGzM65sJJmceYHi43KwVqBIFrYbqa7ImNnjTUMFTGryFQ" />
    <img alt="Frostguard star history" src="https://api.star-history.com/chart?repos=Shederator/wosbot&type=date&legend=top-left&sealed_token=Q4rcyFr92ZWBzZQ20e-IzVUjxqfb5_eM5u09bqV8HyzPBtTvEvoQpkN-YO7JzMG8uRS50EcA9FGzM65sJJmceYHi43KwVqBIFrYbqa7ImNnjTUMFTGryFQ" />
  </picture>
</a>

</div>

<div align="center">

Made for the Whiteout Survival community.

<img src="https://capsule-render.vercel.app/api?type=waving&color=0:081522,45:17324d,100:4f86a8&height=100&section=footer" width="100%" alt="" />

</div>
