---
name: analyze-logs
description: >
  Analyze Frostguard account and global logs to explain one routine's visits.
  Use when the user asks what a routine did, requests its visit timeline, or
  runs /analyze-logs. Resolve only missing scope and inspect relevant log lines.
user-invocable: true
---

# Analyze Frostguard logs

Extract the relevant visits from an existing or specifically collected logs directory. Return a timeline and answer the user's question from the evidence. This skill analyzes logs; it does not require a snapshot dump. Do not commit logs. If the user has separately authorized a code fix, the timeline does not block that work.

## Scope

State the scope in the reply, then search.

State the scope before searching. Ask only when the routine is missing, matches more than one class, or matches nothing, or when the source logs directory cannot be resolved. Profiles default to all. The window defaults to the bot/account's current local calendar day; derive its offset from the log timestamps when available and name the resolved date in the scope. If the user names captures, use their UTC filename times converted to account-local time as anchors, then include each enclosing visit from its queue/routine start through its exit.

| Input | Resolution |
|---|---|
| Routine | Required. A class simple name, a `TpDailyTaskEnum` constant, its display text, or a spoken alias below. |
| Profiles | `all`, or the profile names the user gave. |
| Window | An account-local calendar day or inclusive range. Default: the bot's current local day. Named PNG captures anchor the enclosing visit using their UTC stamps. |
| Logs directory | A directory the user names or passes from a collection workflow. Otherwise the first existing path below. |
| Question | Optional. With no question, return the timeline. |

Logs directory, in order:

1. The directory the user names or passes from a collection workflow. If a dump root contains `logs/`, use that child directory. A copied folder is valid. A folder that holds only PNG captures is not a logs directory: use a logs path named in the same request, including a dump copied for another routine that ran the same night.
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

## Result

1. **Scope.** Logs directory, files opened, profiles, day or range, class, display text, and lines kept.
2. **Visits, in time order.** One block per `Executing:` / routine start through `Completed:`, `PREEMPTED:`, or the routine's own exit. Each block names the profile, start and end, the exit sentence from the log, the next schedule from the log, and any `snapshot=` path. When the user gave captures, lead with the visits joined to those files and summarize other visits in the window as counts.
3. **Decision lines under each visit,** in order: search, hit, miss, tap, confirmation, and exit. Copy the relevant lines for joined visits.
4. **Queue lines,** marked `TaskQueue` and separate from routine lines.
5. **Evidence limits.** State what the logs cannot establish, such as a missing capture, a dump ending before a later PNG, or a template result logged by another class.

Answer the user's question from the timeline. Include concise diagnosis or next-step recommendations when requested. Profile names may appear in the reply; keep them out of GitHub issues, fixtures, filenames, and commits.
