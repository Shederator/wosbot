# October 3 failed supervised session

## Observed outcome

The configured event moved from 15:00 to 15:05 UTC. The isolated test restarted at
15:10 with ownership through 15:35. Six recording runs contain no confirmed own
rally or allied deployment. Confirmed navigation totals were 140 list opens,
139 Back transitions and 12 scrolls. This is failed functional validation.
Private recordings and databases must not be committed or uploaded.

## Causes and correction scope

- **Join self-rejection:** `requireTacticalCheckpoint(JOIN_ARMED)` advanced the live
  traversal cursor through `restoreCompleted` before `authorizeCandidate`. The
  candidate was therefore excluded even with identical OCR/frames. Arming now
  preserves the live cursor; restart still conservatively skips an ambiguous
  armed row. Completion/recovered-abort alone advances the live checkpoint cursor.
- **Own rally misclassification:** run 001, segment 004, decoded frame 511 visibly
  contains the configured Trap 1, active countdown and open Raging Bear panel.
  Old numbered/active templates missed this rendered variant even at 90%; panel
  title/button matched at 95%. Added separately calibrated variants at 95%, retaining
  numbered-trap and active-status conjunctions. The shortcut transition also accepts
  a positively identified open panel, without an unnecessary second target tap.
- **Expired authorization:** run 001 frame 95 refused scrolling at 1,185 ms; frame
  585 at 1,299 ms. Search is now restricted to the recorded target/plus columns,
  preserving full OCR identity and order checks. The one-second limit is unchanged.
  Exact failed-session frames 631/637/656/660 have the same rows/identities as the
  full-screen scan. Offline classification plus narrowed scan measured 446–600 ms
  in one run; this excludes capture, persistence and dispatch and is not a live guarantee.
- **Navigation mistaken for progress:** successful Back used to reset recovery
  strikes. Repeated failed goals now consume the budget even if Back succeeds;
  confirmed deployment resets the corresponding goal. This bounds failure rather
  than claiming the underlying visual problem is solved.
- **Disabled plus falsely joinable:** broader replay of the earlier manual recording
  found that the plus template also matches grey disabled controls. Exact captain identity
  is not join permission. The production scanner now additionally requires green background
  pixels in the detected control. Segment 006 frames 115/128 show a stable captain but a
  green-to-grey control; the previous replay's positive-authorization assertion was wrong.
  Frames 171/182/194/235 additionally exercise scrolling, disabled/blue controls and a green
  control becoming grey. Decorated-name OCR remains unstable; no fuzzy identity fallback was added.
- **Unverified cleanup discarded:** the finalizer cleared state after
  `terminal-cleanup-not-verified`. Failed cleanup now retains a sticky durable hold
  beyond event/lease expiry, blocks normal task/idle injection/device release, and
  survives storage failure. An explicitly disabled/revoked profile releases ownership.
  This is an operator hold, not automated proof of successful cleanup or permission
  to resume tactical input after the event. Generic typed recorder failures in
  cleanup are tagged as terminal cleanup failures too.
  Cleanup now observes transient UNKNOWN/loading/reconnect states under bounded deadlines,
  accepts a late fresh World postcondition without repeating an uncertain Back, and checks
  success after the fourth permitted edge. Every terminal success requires current World
  evidence within one second. If this bounded recovery cannot verify cleanup, the durable
  operator hold remains; there is no unbounded automatic retry or tactical restart after expiry.
- **Test setup error:** the private launcher setup used invalid idle value
  `DO_NOTHING` (parsed as `CLOSE_EMULATOR`) and stored the profile-only keep-open
  switch globally. Correct values are global `KEEP_RUNNING` and profile
  `KEEP_EMULATOR_OPEN_BOOL=true`. The setup/validation scripts are corrected outside
  this PR; the recorded database was not edited during this investigation.

## Replay and remaining gates

Verification after the final corrections: full offline `package` reactor exited
successfully, 1,099 tests, zero failures/errors, six skipped. Private failed-session
and earlier private OCR/scroll replays were enabled. The final replay measured
684–957 ms for classification plus row scanning on four actual failed-session frames;
capture, persistence and input dispatch remain additional costs. The one-second
authorization budget has not been increased. The Linux portable bundle passed
structural verification (603 entries, 85 runtime JARs, 466 template sprites).
Six launcher/config-validation tests also passed earlier. A fresh adversarial review found no remaining concrete blocker
in the changed paths after cleanup-expiry, storage, revocation and recorder-failure
corrections. This is offline verification, not live behavior verification.

The cleanup-driver additions cover transient UNKNOWN recovery without input,
bounded persistent UNKNOWN, aged World refusal, success on the final permitted
edge and late completion without repeating Back. These are scripted transition
tests, not evidence of real game-side cleanup timing.

`BearFailedSessionReplayTest` uses `FROSTGUARD_BEAR_GLD_FRAMES` containing private
`decoded-631.png`, `decoded-637.png`, `decoded-656.png`, `decoded-660.png` from run 001
segment 001. It compares full/narrow row scans and the live arm/authorize sequence.
`BearLiveFrameReplayTest` includes the redacted shortcut panel; wrong-trap refusal
and prior negative fixtures remain mandatory. Tests must never contact a device.

Architecture is still incomplete: automated recall/Auto Join preparation and
broader Trap 2 visual evidence remain gated. Full-event labelled replay, ambiguous
formation/deploy/rejection sequences, concurrent-device end-to-end timing and
supervised successful own/join/return/cleanup behavior remain unproven. Do not use
these corrections or synthetic passing tests as a live-ready claim.
