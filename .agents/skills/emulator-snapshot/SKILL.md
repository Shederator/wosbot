---
name: emulator-snapshot
description: >
  Capture a live emulator PNG over SSH or on localhost with adb screencap.
  Use when the user asks for an emulator snapshot, ADB capture, screencap,
  live frame, or runs /emulator-snapshot. The user must name a host
  (user@host or localhost). Confirm the Frostguard profile, then save the PNG.
user-invocable: true
---

# Emulator snapshot

Take one live emulator screenshot and copy it next to the other local dumps. Stop after the file is saved. Do not edit game code, commit the PNG, or analyze logs unless the user asked for that in the same request.

## Inputs

| Input | Resolution |
|---|---|
| Host | Required. `user@hostname`, `user@ip`, or `localhost` / `local`. |
| Profile | Ask to confirm **Default** unless the user named a profile. |
| Destination | Optional directory. Default: `<repo>/.garbage/emulator-snapshot/`. |

Ask only when Host is missing. Do not invent a host.

## Host

`localhost` or `local`: run adb on this machine.

Otherwise treat the value as SSH `user@host`. Test, then stop if it fails:

```sh
ssh -o BatchMode=yes -o ConnectTimeout=10 USER@HOST "echo ok"
```

Use BatchMode. Nested PowerShell quoting over Windows OpenSSH is fragile; prefer `cmd` / `where.exe` / `adb`.

## Profile → serial

1. Use the user's stored profile→serial map when it exists (workspace preferences / memory).
2. Else compute from the Frostguard emulator index:
   - MuMu: `127.0.0.1:(16384 + index × 32)`
   - LDPlayer: `127.0.0.1:(5555 + index × 2)`
   - MEmu: `127.0.0.1:(21503 + index × 10)`
3. List devices. The serial must appear as `device`. If it is missing or not `device`, relist `adb devices -l` and pick; say which serial and why.

Default in this workspace maps to MuMu index 0 (`127.0.0.1:16384`). `main` maps to index 1 (`127.0.0.1:16416`).

## Find adb

Resolve `adb` on the machine that owns the emulator (the SSH host, or this machine for localhost).

Windows:

```bat
where.exe adb
```

If that is empty, try:

`C:\Program Files\Google\Play Games Developer Emulator\current\emulator\adb.exe`

Linux:

```sh
command -v adb || echo "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platform-tools/adb"
```

Also check `$HOME/Android/Sdk/platform-tools/adb`.

macOS:

```sh
command -v adb || echo "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platform-tools/adb"
```

Also check `$HOME/Library/Android/sdk/platform-tools/adb`.

Then:

```sh
adb devices -l
adb -s SERIAL get-state
```

`get-state` must print `device`.

## Capture

Write to a device path, then pull a file. Do not pipe `screencap -p` through Windows SSH: CRLF corrupts the PNG.

Remote path on the device: `/sdcard/frostguard-adb-capture.png`.

Windows (local or SSH to Windows):

```bat
adb -s SERIAL shell screencap -p /sdcard/frostguard-adb-capture.png
adb -s SERIAL pull /sdcard/frostguard-adb-capture.png %TEMP%\frostguard-adb-capture.png
adb -s SERIAL shell rm /sdcard/frostguard-adb-capture.png
```

Copy off a Windows SSH host with `scp USER@HOST:"C:/Users/<user>/AppData/Local/Temp/frostguard-adb-capture.png"` then delete that temp file on the host (`del`).

Linux and macOS (local or SSH to Unix):

```sh
adb -s SERIAL shell screencap -p /sdcard/frostguard-adb-capture.png
adb -s SERIAL pull /sdcard/frostguard-adb-capture.png /tmp/frostguard-adb-capture.png
adb -s SERIAL shell rm /sdcard/frostguard-adb-capture.png
```

On a Unix host, `adb -s SERIAL exec-out screencap -p > dest.png` is also valid. Keep the file-then-pull path when the agent runs on Linux and the emulator is on Windows.

## Destination

Default directory: `<repo>/.garbage/emulator-snapshot/`. Use the directory the user names when they give one.

Default filename: `yyyyMMdd'T'HHmmss'Z'-live-adb.png` (UTC, example `20261001T220058Z-live-adb.png`).

Create the directory. Do not write under `logs/snapshot/` on a live bot workspace (the bot owns that tree). Do not commit the PNG.

## Reply

Host, profile, serial, `adb` path, destination path, `file(1)` size and pixels. Look at the PNG and name the visible screen in one sentence.
