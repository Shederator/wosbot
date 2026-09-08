# Telemetry Snapshot

The built-in `TELEMETRY_SNAPSHOT` task records per-profile history for the Statistics view. It is
automatically queued for every enabled profile and does not require custom-task installation.

## Capture behavior

- Start from the World screen and read power, coal, and gems from the shared 720x1280 HUD regions.
- Retry malformed OCR up to five times and reject implausible changes instead of recording a guess.
- Add cached stockpile, speedup, task-run, and action-counter values when they are available.
- Skip the entire sample when no live HUD value resolves.

The profile setting `TELEMETRY_INTERVAL_HOURS_INT` controls the normal interval. The default is six
hours and the Statistics view offers 1, 2, 3, 4, 6, 8, 12, and 24 hours. Runs at 23:00 and 08:30
local time remain schedule anchors so the overnight report has useful bookends. An interval change
realigns and persists the queued run immediately.

## Storage

History belongs to the selected workspace and stable numeric profile ID:

`data/telemetry/profiles/<profile-id>/history.jsonl`

`latest.json` in the same directory is atomically replaced and supplies the previous values for
plausibility checks. Profiles and Stable, Nightly, and source workspaces never share these files.

## Verification

Keep parser, plausibility, writer, schedule, registration, and queue-lifecycle unit coverage. HUD
region or OCR changes also require the redacted saved-frame test and a live account-log run that
confirms all three live metrics, workspace output, a persisted future schedule, and queue release.
