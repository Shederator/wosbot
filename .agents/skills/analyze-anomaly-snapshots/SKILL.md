---
name: analyze-anomaly-snapshots
description: >
  Dump a remote Frostguard workspace's logs and database, list diagnostic
  snapshot folders, then analyze the one the user picks. Use when the user
  asks to analyze anomaly snapshots, dump bot logs, review logs/snapshot,
  or runs /analyze-anomaly-snapshots. Do not start log analysis until they
  choose a snapshot folder.
user-invocable: true
---

# Analyze anomaly snapshots

Reuse an existing Frostguard log dump when possible; otherwise copy a live workspace (logs + database) into `.garbage`. List snapshot folders and stop. Analyze only after the user picks a folder. Then follow `analyze-logs`. Do not edit game code or commit the dump.

## Existing dumps

Before asking for workspace or host details, look for existing `<repo>/.garbage/logs-*/` directories. List the existing dump paths, newest first, and propose analyzing one of them before starting a transfer. Ask which dump to use; when several exist, recommend the newest. If they choose one, skip the transfer and use it as the logs directory for the remaining steps; list its snapshot folders and ask which folder to analyze. Do not read its logs or PNG contents before they choose a snapshot folder. If they decline, or no dump exists, continue with Session preferences and Dump below.

## Session preferences

The first time this session, ask for:

1. Frostguard **workspace** path on the bot machine (the directory that contains `logs/` and `frostguard.db`, for example `C:\Users\<username>\Documents\dev\wosbot.new\.frostguard-dev`).
2. **Host**: `user@host` or `localhost`, same rule as `.agents/skills/emulator-snapshot/SKILL.md`.

If either value is already stored in operator preferences, re-propose it and use it when they agree or stay silent on that field.

Save both in operator preferences for the rest of the session (workspace `user-preferences` when that store exists). Do not invent a host or a workspace path.

## Dump

SSH: follow the Host section of `emulator-snapshot` (BatchMode test, then stop on failure). `localhost` copies from this machine.

UTC stamp `yyyyMMdd'T'HHmmss'Z'`. Destination:

`<repo>/.garbage/logs-<stamp>/`

Copy into that directory:

- the workspace `logs/` tree (including `snapshot/` and `archive/`)
- `frostguard.db`, and `frostguard.db-wal` / `frostguard.db-shm` when they exist

Windows remote, from the agent machine:

```sh
mkdir -p DEST
ssh -o BatchMode=yes USER@HOST tar -cf - -C "WORKSPACE/logs" . | tar -xf - -C DEST
scp -o BatchMode=yes USER@HOST:"WORKSPACE/frostguard.db" DEST/
```

If `tar` is missing on Windows, `scp -r` the `logs` folder. Copy the database sidecars (`frostguard.db-wal` and `frostguard.db-shm`) when they exist. Never delete or truncate the source logs or database. If a file is locked (bot running), leave it; do not retry or use Clear-Content.

Do not commit the dump.

## List, then stop

Snapshot folders are `DEST/snapshot/<activity>/` (grouped). If PNG files sit directly in `DEST/snapshot/`, list those names too.

Reply with the dump path, whether the database copied, and the snapshot **directory names**. Ask which folder to analyze. **Do not** read logs or PNG contents in this step.

## Analyze after the choice

The user names one snapshot folder. They may add a problem note. Then follow `.agents/skills/analyze-logs/SKILL.md` with:

| Input | Value |
|---|---|
| Logs directory | The selected existing dump or the dump just created |
| Window | UTC stamps in that folder's PNG names, converted with the `frostguard.log` offset |
| Routine | Match the folder name to `TaskRegistrations` / display text; ask if several match |
| Question | The user's problem note, if any |

## Motion / template

If the PNG shows the control and the log is a template miss, or the control is animated (bobbing icon, looping bubble), stop and ask:

1. Capture the live situation with `emulator-snapshot` (confirm profile Default unless they name one).
2. Switch the live search from that template to a colour detector.
3. Whether to keep the template path as a comparison.

Do not change code until they answer. Do not capture until they confirm the profile.
