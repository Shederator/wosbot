---
name: analyze-logs
description: >
  Read Frostguard account logs and frostguard.log for one routine.
  Use when the user asks to analyze logs, read an account log, explain what
  a routine did, or runs /analyze-logs. Confirm the routine, profiles, day,
  and logs directory, then return a visit timeline.
user-invocable: true
---

# Analyze Frostguard logs

Extract one routine's visits from the workspace logs and return a timeline that can support an issue. Stop after the timeline. Do not edit code, file an issue, or commit logs.

## Scope

State the scope in the reply, then search.

Ask only when the routine is missing, matches more than one class, or matches nothing, or when more than one logs directory exists and the user did not choose one. Profiles default to all. The window defaults to the machine's current local calendar day. Name that date in the scope. Captures the user names replace that default: the window is those UTC filenames converted to local time.

| Input | Resolution |
|---|---|
| Routine | Required. A class simple name, a `TpDailyTaskEnum` constant, its display text, or a spoken alias below. |
| Profiles | `all`, or the profile names the user gave. |
| Window | A local calendar day, or an inclusive range of local days. Default: today. Named PNG captures set the window from their UTC stamps. |
| Logs directory | A directory the user names. Otherwise the first existing path below. |
| Question | Optional. With no question, return the timeline. |

Logs directory, in order:

1. The directory the user names. A copied folder is valid. A folder that holds only PNG captures is not a logs directory: use a logs path named in the same request, including a dump copied for another routine that ran the same night.
2. `<repo>/.frostguard-dev/logs` when that directory exists.
3. `~/.frostguard/workspaces/<channel>/<name>/logs` for an installed Stable or Nightly workspace. If several exist, list them and ask.

## Routine

Read `modules/tasks/src/main/java/dev/frostguard/tasks/TaskRegistrations.java`. Each `case` constructs the class that logs. The quoted display text in `TpDailyTaskEnum` is the name `TaskQueue` writes (`Shop Mystery`, not `MysteryShopRoutine`). Also accept any `extends DelayedTask` class under `modules/tasks/src/main/java`.

Match the user's words against the class simple name, the enum constant, and the display text. Several matches: ask which class. No match: list `display text -> ClassName` from that file and stop. When the user then confirms an existing class, add one spoken alias below. A class that is not in the tree is a new routine, not an alias.

## Spoken aliases

Add a line only after the user confirms a name that the scan missed.

```text
spoken words: ClassName
```

## Files

For each selected profile, open `account_<safeName>_<id>.log`. `<safeName>` is the profile name with every character outside `A-Za-z0-9._-` replaced by `_`. `all` means every `account_*.log` in the directory.

Also open `frostguard.log` in that directory.

Include rolled files for the selected days:

- `account_<safeName>_<id>.<yyyy-MM-dd>.<n>.gz` beside the account log
- `archive/frostguard.<yyyy-MM-dd>.<n>.log.gz`

The account file is that profile's routine stream. `frostguard.log` is the SLF4J log for every profile. A routine `INFO`, `WARN`, or `ERROR` is written to both, in different formats. Routine `DEBUG` is written to the account file and is dropped from `frostguard.log` while the tasks logger stays at INFO. `TaskQueue` lines are in `frostguard.log`. Template-search DEBUG (`TemplateSearchHelper`) and routine DEBUG OCR live in the account file; `frostguard.log` at INFO may only keep the routine's INFO or WARN summary of those reads.

Account files can still hold earlier local days after `frostguard.log` has rotated to the current slice. Include those account lines. Do not treat a day as empty because `frostguard.log` starts later.

## Line format

Account line, local time, no timezone:

```text
yyyy-MM-dd HH:mm:ss [LEVEL] ClassName: ProfileName - message
```

`frostguard.log` line:

```text
yyyy-MM-dd'T'HH:mm:ss.SSS±offset LEVEL [thread] logger - message
```

On `frostguard.log`, routine INFO is `ProfileName | ProfileName - message`. Routine WARN and ERROR, and every `TaskQueue` line, use a single `ProfileName - message`. Keep both shapes. A search for `ProfileName |` drops the warnings.

`Completed:` carries a second clock, `dd-MM-yyyy HH:mm:ss`. That field is the next schedule. It is not the line's timestamp.

## Search

Select lines with an anchored pattern. Use the editor Grep when it is available. Otherwise use `grep -E`. The shell may not have `rg`. For a `.gz` file, run `gzip -dc` and then `grep -E`.

Account lines for class `MysteryShopRoutine` on `2026-09-30`:

```text
^2026-09-30 [0-9]{2}:[0-9]{2}:[0-9]{2} \[(INFO|WARN|ERROR|DEBUG)\] MysteryShopRoutine:
```

`frostguard.log` lines for that class:

```text
^2026-09-30T.*MysteryShopRoutine -
```

Replace the date and the class. For a profile subset, keep a `frostguard.log` line when the message contains `ProfileName -` or `ProfileName |`.

Queue lines use the display text, on logger `TaskQueue`. Keep `Executing: <display>`, `Completed: <display>`, and a `PREEMPTED:` that falls after that `Executing:` and before the next `Executing:` for the same profile.

Leave a line whose class or logger is a different routine, including a line that names a template this routine also uses.

Search the message only after the class prefix matches. Keep the full message. A negative is its own outcome: `not found` is a miss, and a test for `found` must not count it.

A snapshot path in a message joins a capture by the UTC stamp and the activity token. The filename stamp is UTC (`yyyyMMdd'T'HHmmss.SSSZ`). Convert it to the account clock with the offset on `frostguard.log` (`+02:00` means add two hours). Filter account lines on that local time. Flat path: `logs/snapshot/<UTC>-<activity>-<type>.png`. Grouped path: `logs/snapshot/<activity>/<UTC>-<type>.png`. Words added after the activity in a renamed file are the user's annotation.

## Timeline

1. **Scope.** Directory, files opened, profiles, day or range, class, display text, lines kept.
2. **Visits, in time order.** One block per `Executing:` / routine start through `Completed:`, `PREEMPTED:`, or the routine's own exit. Each block names the profile, the start, the end, the exit sentence copied from the log, the next schedule copied from the log, and any `snapshot=` path. When the user gave captures, lead with the visits that join those files and summarize the other visits in the window as counts.
3. **Decision lines under the visit, copied.** Search, hit, miss, tap, confirmation, and exit stay in order. Copy those lines for the joined visits.
4. **Queue lines,** each marked `TaskQueue`, separate from the routine's own lines.
5. **What the log does not show.** No capture, no balance, a dump that ends before a later PNG, or a matching template logged by another class.

Answer the user's question from that timeline. Profile names may appear in the reply. Keep them out of GitHub issues, fixtures, filenames, and commits.
