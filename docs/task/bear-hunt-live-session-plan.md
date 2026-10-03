# Bear Hunt frame-driven session

## Confirmed event mechanics

One protected scheduler lease owns the full configured 30-minute event. Normal tasks do not run
until terminal cleanup is verified. Manual Run Now replaces a pending protected retry.

Ownership rules while the event is active:

- The acquired lease, not the editable schedule, decides activation and end. Editing the schedule
  during the event cannot defer, move, or end the session.
- No recovery directive reschedules Bear later than 30 seconds while the event is active. An
  exhausted budget or an operator-action failure raises an alert and switches the rest of that event
  to observe-only: the profile stays owned and recorded but sends no input. The armed finalizer
  restores normal work and clears the switch only after the event end.
- Durable state is cleared only after the event end. A normal return before the end keeps it and
  resumes inside the window.
- An unexpired finalizer or checkpoint is ownership after a restart or crash: the restarted queue
  queues Bear immediately and the emulator stays pinned.
- While any profile owns the event on an emulator, automatic release is refused: idle suspension,
  the session cap, cooldown force-stop, failed idle-wake slot release, character-switch close, and
  ADB-exhaustion relaunch. User-initiated stop, close, and reboot remain allowed.
- Bear performs no inherited blind navigation; a missing game process is a protected app restart.
- Disabling Bear participation or the profile is an explicit revocation: a running session is
  cancelled, the lease and device pin are released, and a cleanup-only finalization restores normal
  work without any Bear input. Every later run for that event is cleanup-only, unless the operator
  re-enables Bear and the profile, which resumes the session. A Bear claim re-reads the stored
  profile after it publishes its run, so a disable that lands while nothing is owned yet still stops
  the claim before any input. A profile re-enabled after its revoked event ended gets Bear
  planned for the next event. A profile disabled before restart is not resumed.

The bot's own rally uses the Special march. Its exact expected return is:

`Send tap instant + 5 minute rally countdown + 2 × one-way travel`

The last own rally is allowed only when that complete interval fits before event end. At the return
deadline no new allied-rally join may begin. A join whose green plus was already committed may finish,
after which the own rally is recreated immediately. On restart, a visible Special rally is never
duplicated. A persisted exact deadline is adopted. If the process dies after arming Send but before
the exact postcondition checkpoint, a conservative event-end deadline suppresses duplicate Special
rallies and records that degraded recovery explicitly. Ordinary Rally rows are never accepted as
evidence of the bot's own rally.

Allied rallies are processed in visible top-to-bottom order. The routine taps the green plus directly,
never opens a detail view, never ranks rows, and never predicts game capacity. It selects the first
configured normal formation without the orange occupied badge, leaves troop composition untouched,
and treats game rejection as authoritative. Success returns to the same Rally list and continues
below the completed row.

When no usable plus is visible, generic Bear rows (including grey/full/departed rows) remain visible
to the cursor and ordered frames are observed for three seconds before one bounded scroll. The scroll
must retain the bottom visible Bear row proven by freshly read captain identity. The swipe's
authorizing rows, not an older pre-scan, are retained for the immediate/settled comparison.
Absolute bottom is accepted only when the exact swipe-authorizing rows, the immediate newer frame,
and a second settled frame at least 250 ms later contain the same ordered rows at the same
positions. The routine then exits the Rally list, reopens it, and starts a new pass from the top.
If every formation is occupied, it stays at the current list position until a formation, the own
deadline, or event end changes the legal action.

## Transition contract

Every Bear input follows the same contract:

1. obtain a fresh monotonically sequenced frame;
2. classify the observed UI state;
3. verify the requested edge is legal from that state;
4. execute the input against that exact observation;
5. require a newer frame proving the named postcondition;
6. otherwise enter an explicit bounded recovery outcome.

The recorder is ordered screenshot sampling from a bounded Android H.264 stream, not continuous
video. Diagnostics include phase, prior/observed state, frame sequence and age, action, expected and
actual postcondition, attempt/recovery reason, and terminal reason. No image payload or secret is
written to the checkpoint.

The durable checkpoint is versioned and backward-compatible. In addition to scheduler recovery
budget it records exact own-rally Send/deadline and join transaction/list-row provenance. UI
observation updates preserve those tactical fields.
Because a crash can occur between a plus tap and the `PLUS_COMMITTED` write, `JOIN_ARMED` is treated
as an input that may already have occurred. Restart recovery aborts any visible formation transaction
and preserves the durable spatial frontier rather than repeating that row. Arming is bound to the
row in the exact tap-authorizing frame and requires a reconstructable semantic fingerprint plus
fresh leader OCR; failed/blank/duplicate OCR therefore fails closed before the plus. Reordered
topmost candidates force a fresh decision instead of tapping the former candidate out of order.
A recovered ambiguous transaction
keeps that frontier across another restart. If the frontier row departs and successors collapse
upward, its old screen coordinate is never reused: the list is closed and reopened for a new top
pass. Persisted join provenance is established before dismissing a restart-time dialog, allowing an
empty Rally list to be classified when Back returns directly to it.

## Validation status

Deterministic tests cover transition ordering, stale/superseded authorization, stream renewal,
interruption, exact own-rally deadline, committed-join completion, restart deadline adoption,
strict fresh list order after reorder, zero-plus viewports with generic rows, rows that remain or
move or disappear, reconstructable durable row identity, repeated-crash frontier retention,
restart-dialog list provenance, conservative adoption of an existing Special rally, three-second
observation, scroll-overlap proof, bottom reset, occupied-formation waiting, and orange formation
occupancy.

These tests do not establish template/OCR accuracy. The exact remaining real-frame requirements are
maintained in `bear-hunt-frame-evidence.md`. The work is not live-ready until those fixtures exist,
the focused and reactor suites pass, an adversarial review finds no Bear input outside the state
machine, and a real event validates the entire 30-minute session.

Departures from the 2026-10-02 fix plan, approved by the operator on 2026-10-02:

- An unclassified screen that survives a bounded wait gets exactly one Back; the next unknown
  recovery restarts the game. UNKNOWN is an explicit legal source of `RECOVER_UNKNOWN_BACK`:
  the observation must still be current and fresh, transport is single-attempt, and a newer
  recognized postcondition is required. It is not permission for a direct unguarded Back.
- Missing templates fail the build through `TemplateResourceAvailabilityTest`, not application
  startup, because seven non-Bear templates are already missing upstream.
- Slot starvation is solved by sibling profiles yielding the slot to the Bear owner rather than by a
  slot-priority change in `EmulatorController`.
- March reads stay scheduled on the known return time: each read opens the sidebar, and the
  event-end placeholder that made the cache stale is gone.
- A formation conflict is shown in Bear settings and refused at session start (which then observes
  the event), not also when the schedule is computed.

Not done, by design: the capture journal does not log the removed fast-revalidation heuristic.

The earlier speculative at-Bear states were removed because no classifier produced them.
October 3 footage now grounds `WORLD_AT_CONFIGURED_BEAR`: centered Trap 1 text plus active
status, followed separately by the Bear rally panel. Trap 2 arrival and pre-event configured-trap
Go remain gated. A visible shortcut alone is never proof of a centered target.

Pet confirmation uses checkpoint version 3, with backward reads of versions 1 and 2. An armed
Use is never repeated after a restart, including when the temporary post-input panel looks like
the pre-input selection. Pet panels left open across activation can be closed through recovery.
