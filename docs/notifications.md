# Action-required notifications

Use `ActionRequiredIncidentService.report(...)` only when bounded automatic
recovery has been exhausted and an operator must act. Normal intermediate
retries remain logs and task status; they do not create incidents.

Incidents are persisted in the workspace SQLite database and deduplicated by
profile ID, task key, and a caller-supplied stable failure signature. A repeated
condition updates the same record's latest details and retry time, increments
its occurrence count, and makes it unread again. Acknowledgement marks an active
incident as seen but does not claim the problem is fixed. It immediately leaves
the default `Action required` view and appears under `Acknowledged` and `All`;
the red badge counts only unread active records. Another occurrence clears the
acknowledgement and shows the deduplicated record as unread again. A later
successful task run recovers active incidents for that profile and task;
recovered history remains available without contributing to the red badge.

Unhandled queue exceptions use `TaskFailureIncidentService`: the same stable
profile/task/error signature is persisted as a consecutive streak, retried no
earlier than five minutes, and escalated on its third failed execution. Success
clears the streak and recovers the active incident. Tasks that catch and
reschedule their own errors can call `recordFailure(TaskFailureReport)` with
their own retry budget and must call `recordSuccess(...)` after recovery.
Frostguard cannot infer failures that task code completely swallows or returns
as success without reporting.

The service has no JavaFX dependency, so headless queues create the same records
as desktop queues. The desktop launcher observes snapshots, shows unread active
incidents on the status-bar bell, and renders the right-side drawer inside the
existing window. Cards keep their cause concise and human-readable; measured
pattern scores and color ratios stay in the bounded correlated log excerpt
copied with diagnostics.

Copied diagnostics deliberately omit database/profile IDs. They include the
incident signature and structured expected/observed state, last action,
retry/fallback, resource outcome, and a bounded correlated log excerpt. Common
secret and private-ID assignments are redacted before persistence. `Open logs`
resolves the current account log by profile suffix and falls back to the
workspace log directory when no account log exists yet.
