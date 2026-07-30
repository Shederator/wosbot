# Scheduled Autostart

This guide describes how to run Frostguard from Windows Task Scheduler and return the machine to standby after the run window.

## Files

- `Frostguard-Startup_bot.xml`: example task that wakes the machine and starts Frostguard.
- `Frostguard-SystemStandby.xml`: example task that returns the machine to standby.
- `launch.ps1`: resolves the latest Frostguard JAR, starts it with `--autostart`, enforces a timeout, and stops the emulator process.

## Prerequisites

Place `launch.ps1` next to the built `frostguard-<version>.jar`, or update the task action paths to match your installation.

Enable wake timers from an elevated PowerShell session:

```powershell
powercfg /setacvalueindex SCHEME_CURRENT SUB_SLEEP RTCWAKE 1
powercfg /setdcvalueindex SCHEME_CURRENT SUB_SLEEP RTCWAKE 1
powercfg /setactive SCHEME_CURRENT
powercfg /query SCHEME_CURRENT SUB_SLEEP RTCWAKE
```

Configure Frostguard so `--autostart` can begin automation without manual setup.

## Create Tasks

1. Open Windows Task Scheduler.
2. Create a folder such as `Frostguard`.
3. Import `Frostguard-Startup_bot.xml`.
4. Edit the action and set the correct `launch.ps1` path and working directory.
5. Adjust trigger times to your schedule.
6. Import `Frostguard-SystemStandby.xml`.
7. Adjust its trigger times so standby happens after the Frostguard run window.

## Notes

- Disable the tasks while actively using the machine.
- `launch.ps1` writes `launch.log` next to the script.
- Task Scheduler repeat settings do not always wake a sleeping machine; explicit calendar triggers are more reliable.
- Hardware wake behavior depends on BIOS and Windows power settings.
