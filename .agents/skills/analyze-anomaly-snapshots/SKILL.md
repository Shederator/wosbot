---
name: analyze-anomaly-snapshots
description: >
  Collect Frostguard logs for snapshot-backed diagnosis, save a logs-only
  archive, or route a focused log analysis. Use when the user asks to inspect
  anomaly captures or to collect/archive remote logs for a targeted analysis.
user-invocable: true
---

# Frostguard evidence collection and analysis routing

Choose the smallest workflow that satisfies the request. This skill collects or routes evidence; `analyze-logs` owns routine matching and timeline analysis. Never modify the source workspace, delete source files, or commit collected runtime data.

## Choose a workflow

Infer the workflow from the user's request; ask only for missing information that blocks it.
For snapshot-backed analysis, check existing local dumps before asking for remote host or workspace details.

| Workflow | Use when | Collect and inspect |
|---|---|---|
| **Snapshot-backed analysis** | The user asks to analyze a diagnostic snapshot or investigate an anomaly using captures. | Relevant logs plus the selected activity's `snapshot/` captures. If the activity is not known yet, list candidate folder names and wait before transferring or opening capture contents. Include a database only after the user asks for it. |
| **Logs-only archive** | The user asks to save, copy, or archive logs without analyzing them. | Current account/global text logs, all rotated text logs, and all available snapshots, unless the user gives a narrower date range. Exclude the database by default. Do not inspect log or snapshot contents; report the saved path and inventory, then stop. |
| **Focused log analysis** | The user asks what a routine did or asks a scoped question that can be answered from logs. | Use a supplied/local logs directory directly. For remote logs, collect only the matching account log(s), global log, and relevant rotated files; do not collect snapshots or the database. Then follow `analyze-logs`. |

Do not require a snapshot-folder choice for logs-only archiving or focused log analysis. Logs-only archives include every snapshot activity folder by default without opening PNG contents. In snapshot-backed analysis, use a folder or files the user already named. If no target is specified, list candidate activity folders and stop for the user's choice before opening logs or PNG contents.

## Resolve source and scope

- Use the host/workspace supplied in the request or saved operator preferences when available. Reuse valid saved values without asking again. Never invent a host, path, or profile.
- A source may be `localhost` or a remote `user@host`; support the source OS and shell rather than assuming a fixed account name, Windows path, or remote `tar` implementation.
- If the needed host or workspace cannot be resolved, ask only for that missing value. For SSH, first verify non-interactive access with `ssh -o BatchMode=yes`; stop if it fails.
- For focused analysis, resolve routine, profiles, and time window using `analyze-logs`. Its defaults apply: all profiles and today's local calendar day unless the request or capture timestamps give a different scope.
- For snapshot analysis, use the requested time window and activity. A supplied snapshot folder may be outside `logs/`; keep its path and pair it with the corresponding logs directory.

## Reuse or collect

For snapshot-backed analysis, check existing `<repo>/.garbage/logs-*/` dumps before asking for a host or workspace. List them newest first. Use file names, folder names, and timestamps only to identify candidates; these do not prove that the requested log lines are present. Do not treat a newer but incomplete-looking dump as a match. If the user names a valid dump or snapshot path, use it directly. Do not read evidence contents while the user is choosing among candidates. After the target is clear, inspect the selected logs' actual time coverage; if it is stale or incomplete, report the gap and collect a fresh scoped copy when that is within the requested task.

Use a timestamped local destination such as:

```text
<repo>/.garbage/logs-<UTC-stamp>/
```

Keep the layout explicit: log files go under `DEST/logs/`, so pass `DEST/logs` to `analyze-logs`. Do not overwrite an existing dump; choose a new destination.

- **Snapshot-backed analysis:** copy the relevant current/rotated text logs and selected activity's captures under `DEST/logs/`. If the user must choose an activity first, list activity folder names without reading PNG contents, wait for the choice, then copy only that activity's captures. Leave the source unchanged. Only access a database after the user asks for it; for a live SQLite database, use a consistent backup mechanism rather than copying a changing file or sidecars.
- **Logs-only archive:** copy current account/global logs, all rotated text logs, and the complete `snapshot/` tree under `DEST/logs/`, unless the user gives a narrower date range. Preserve activity folders and capture filenames. Exclude the database and unrelated workspace files. Do not inspect the copied logs or PNG contents. Report the destination and inventory, including whether snapshots were present.
- **Focused log analysis:** copy only the selected account log(s), `frostguard.log`, and their date-matching rotations to a scoped local directory when direct local access is unavailable. Do not create a broad workspace dump.

Use read-only transfer methods appropriate to the host, such as `scp` or a verified archive stream. Check whether remote tools and path quoting work before relying on them; Windows OpenSSH may use different shell quoting or lack a compatible `tar`. Preserve filenames and timestamps where practical. If the transfer is incomplete, do not silently analyze it as complete; report the gap and continue only with evidence that is available.

Never truncate, clear, rotate, or delete source logs. Keep collected data under `.garbage/` or another user-selected local destination, outside tracked source paths. Do not commit it.

## Snapshot-backed analysis

After locating the logs, list activity folder names under `logs/snapshot/`. If the user already named a folder or specific captures, proceed with those. Otherwise ask which candidate folder to analyze and wait before opening log or PNG contents or copying the selected captures.

Once the target is clear, follow `.agents/skills/analyze-logs/SKILL.md` with:

| Input | Value |
|---|---|
| Logs directory | The paired `logs/` directory from the selected dump or source; the selected captures may be in another folder |
| Window | The requested interval or selected PNG UTC timestamps converted to account-local time, expanded to each enclosing visit |
| Routine | Match the activity folder to `TaskRegistrations` and its display text; ask only if ambiguous |
| Question | The user's anomaly description |

Inspect only the selected activity's captures and the routine's relevant log lines. Join a capture to a log event using its UTC timestamp and activity token; use the `frostguard.log` offset to convert it to local account time. State when the available evidence cannot confirm an action or outcome.
