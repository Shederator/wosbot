# Scheduled Autostart

This guide describes how to run Frostguard from Windows Task Scheduler and
return the machine to standby after the run window. The imported startup example
targets a Stable installation created by the MSI. A separate development mode
runs a source checkout through the Maven Wrapper.

## Files

- `Frostguard-Startup_bot.xml`: production example that wakes the machine and
  starts an installed Stable release.
- `Frostguard-SystemStandby.xml`: example task that returns the machine to
  standby.
- `launch.ps1`: starts an installed release or a source checkout with
  `--autostart`, enforces a timeout, and stops the emulator process.

## Prerequisites

Prepare the launch mode that you intend to schedule:

- For an installed release, install Frostguard with the Stable or Nightly MSI
  and start that installation manually at least once.
- For a development checkout, complete the
  [developer setup](../development.md) and start it manually once with
  `.\mvnw.cmd javafx:run`.

In that same runtime, configure the profiles, emulator, and schedules that
`--autostart` should run, then close Frostguard.

Copy `launch.ps1` to a stable location that is not replaced by application
updates, for example:

```text
C:\Frostguard-Autostart\launch.ps1
```

The supplied startup XML uses that example path. The script defaults to the
Stable MSI location:

```text
%LOCALAPPDATA%\Frostguard\Frostguard.exe
```

Enable wake timers from an elevated PowerShell session:

```powershell
powercfg /setacvalueindex SCHEME_CURRENT SUB_SLEEP RTCWAKE 1
powercfg /setdcvalueindex SCHEME_CURRENT SUB_SLEEP RTCWAKE 1
powercfg /setactive SCHEME_CURRENT
powercfg /query SCHEME_CURRENT SUB_SLEEP RTCWAKE
```

The scheduled task starts a desktop application, so configure it to run only
when your Windows user is logged on. Review local Windows security policy before
storing credentials in Task Scheduler.

## Choose the launch mode

### Installed Stable release

The imported `Frostguard-Startup_bot.xml` already uses this mode:

```text
Program/script: powershell.exe
Arguments: -NoProfile -ExecutionPolicy Bypass -File "C:\Frostguard-Autostart\launch.ps1" -Mode Installed -VmProcessName "MuMuNxMain"
Start in: C:\Frostguard-Autostart
```

With no `-LauncherPath`, `launch.ps1` resolves the default Stable MSI path from
the scheduled user's local application-data directory. MSI updates may replace
the application files, but the launcher path remains stable.

### Installed Nightly or custom location

Nightly and custom MSI destinations require the absolute executable path:

```text
Arguments: -NoProfile -ExecutionPolicy Bypass -File "C:\Frostguard-Autostart\launch.ps1" -Mode Installed -LauncherPath "C:\Users\YOUR_NAME\AppData\Local\Frostguard Nightly\Frostguard Nightly.exe" -VmProcessName "MuMuNxMain"
```

Replace the example with the actual launcher path. Do not use the Start-menu or
desktop shortcut path.

### Development checkout

Development mode runs the equivalent of this command from the prepared
repository or worktree root:

```powershell
.\mvnw.cmd "-Djavafx.args=--autostart" javafx:run
```

Configure the task action with an absolute repository path:

```text
Program/script: powershell.exe
Arguments: -NoProfile -ExecutionPolicy Bypass -File "C:\Frostguard-Autostart\launch.ps1" -Mode Development -RepositoryPath "C:\src\wosbot" -VmProcessName "MuMuNxMain"
Start in: C:\Frostguard-Autostart
```

Each worktree uses its own `.frostguard-dev` workspace. A development task does
not read the profiles or schedules from an installed Stable or Nightly
workspace.

## Create the tasks

1. Open Windows Task Scheduler.
2. Create a folder such as `Frostguard`.
3. Import `Frostguard-Startup_bot.xml`.
4. Select **Run only when user is logged on** for the task.
5. Edit its action for the chosen launch mode, including the script path,
   working directory, emulator process name, and any required launcher or
   repository path.
6. Adjust the trigger time and append a `-TimeoutSec <seconds>` argument when
   the default 2,700-second (45-minute) run window is not suitable.
7. Import `Frostguard-SystemStandby.xml`.
8. Adjust its trigger times so standby happens after the Frostguard run window.

Run the startup task manually once while observing Frostguard and the emulator.
Confirm the configured profiles start, the timeout closes Frostguard and the
emulator, and `launch.log` contains no error before enabling unattended runs.

## Notes

- Disable the tasks while actively using the machine.
- `launch.ps1` writes `launch.log` next to the script.
- The production XML is an example for an MSI installation; it does not launch
  an extracted PR-test bundle.
- Task Scheduler repeat settings do not always wake a sleeping machine; explicit calendar triggers are more reliable.
- Hardware wake behavior depends on BIOS and Windows power settings.
