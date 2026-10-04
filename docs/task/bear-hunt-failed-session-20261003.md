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
  control becoming grey. The captain crop included the sword's right edge, producing changing
  leading punctuation. Moving its left edge from x=280 to x=291 makes the exact decorated
  identity agree in frames 182/194 and the second captain agree in 115/128. This is calibrated
  to these recordings, not proof for all names. No fuzzy identity fallback was added.
- **Duplicate OCR on one observation:** the row scan is now reused only for the identical
  `Snapshot` object. A newer observation always rescans, even if its pixels or sequence
  compare equal. Age/sequence authorization remains enforced at the input boundary.
  Classifier matching now converts only its existing calibrated ROI. An eight-sample
  alternating A/B replay measured 425 ms baseline versus 421 ms region-first classification:
  the ROI conversion alone is not a meaningful latency solution. Full-screen loading/reconnect
  searches remain the largest classifier costs. Recorded classification plus one row scan
  measured 738–1012 ms in that run; capture, persistence, permission reads and dispatch are extra.
- **Unverified cleanup discarded:** the finalizer cleared state after
  `terminal-cleanup-not-verified`. Failed cleanup now retains a sticky durable hold
  beyond event/lease expiry, blocks normal task/idle injection/device release, and
  survives storage failure. An explicitly disabled/revoked profile releases ownership.
  The scheduler can now invoke a cleanup-only hook after expiry, never tactical execution,
  app bootstrap/restart or preparation. At most three attempts are persisted before input,
  across restarts and Run Now. Exhaustion retains the hold for operator action. Generic typed recorder failures in
  cleanup are tagged as terminal cleanup failures too.
  Cleanup now observes transient UNKNOWN/loading/reconnect states under bounded deadlines,
  accepts a late fresh World postcondition without repeating an uncertain Back, and checks
  success after the fourth permitted edge. Every terminal success requires current World
  evidence within one second. If this bounded recovery cannot verify cleanup, the durable
  operator hold remains after the cleanup-only budget; there is no unbounded automatic retry
  or tactical restart after expiry. Cleanup failure takes precedence over an earlier tactical
  exception so scheduler routing cannot miss it. Durable intent precedes terminal input and
  durable verification precedes normal-work restoration. Marker-write failure retains an
  immediate local hold; an ended unfinished checkpoint conservatively restores that hold on
  restart, including `TERMINAL/RECOVERY_EXHAUSTED`. Tactical retry counts do not consume the
  separate cleanup budget. Explicit observe-only, disable/cancellation and the persisted
  device binding are checked before the hook and again before each input. A task bound to
  an old emulator is refused rather than silently retargeted.
- **Test setup error:** the private launcher setup used invalid idle value
  `DO_NOTHING` (parsed as `CLOSE_EMULATOR`) and stored the profile-only keep-open
  switch globally. Correct values are global `KEEP_RUNNING` and profile
  `KEEP_EMULATOR_OPEN_BOOL=true`. The setup/validation scripts are corrected outside
  this PR; the recorded database was not edited during this investigation.

## Replay and remaining gates

Previous committed baseline (`bfbab07`): full offline `package` reactor exited
successfully, 1,099 tests, zero failures/errors, six skipped. Private failed-session
and earlier private OCR/scroll replays were enabled. The final replay measured
684–957 ms for classification plus row scanning on four actual failed-session frames;
capture, persistence and input dispatch remain additional costs. The one-second
authorization budget has not been increased. The Linux portable bundle passed
structural verification (603 entries, 85 runtime JARs, 466 template sprites).
Six launcher/config-validation tests also passed earlier. A fresh adversarial review found no remaining concrete blocker
in the changed paths after cleanup-expiry, storage, revocation and recorder-failure
corrections. This is offline verification, not live behavior verification.

Continuation verification: full offline `package` reactor passed with both private replay
environment variables enabled: **1,114 tests, zero failures/errors, six skipped**. The 54
scheduler ownership/cleanup tests and 20 driver tests passed. The bundle verifier's 15 unit
tests also passed. The first full run exposed an architecture source guard still requiring
direct `scanRows()`; the guard now requires the exact authorizing snapshot and the subsequent
freshness check, with a separate test proving new observations rescan. The entire reactor was
rerun after that correction and the stronger marker-write-failure regression.

The fresh targeted and broader adversarial reviews found no additional concrete blocker after
transport-binding, marker-write failure, budget separation and unverified-terminal restart
regressions were added. This does not establish full-event behavior. The final full-run replay
measured **1029–1208 ms** for failed-session classification plus row scanning, and 1229 ms on
the earlier private pair. The alternating classifier A/B was 559 ms baseline versus 538 ms
region-first. These samples exceed the one-second budget before dispatch costs: latency
remains a functional gate, not a completed optimization or permission to weaken freshness.

### Negative-match acceleration follow-up

The next offline pass targets the three dominant full-screen checks (Reconnect, Download
Now and mandatory Update). Native worker-count experiments did not produce a dependable
improvement; no global thread setting was changed. An opt-in negative-only color-correlation
prefilter is used only by these Bear classifier checks. It does not shrink their search area,
change their threshold, infer a UI state or authorize input. Possible matches still use the
original full-color matcher; masks, unsupported inputs and ill-conditioned cases fall back.

The derivation uses the per-channel centering and color summation defined in
[OpenCV's template-matching documentation](https://docs.opencv.org/4.13.0/df/dfb/group__imgproc__object.html).
Projecting RGB onto the normalized sum channel retains fraction
`w = Var(B+G+R) / (3 * sum(Var(channel)))` of template energy. If `r` bounds that
projection's positive correlation, Cauchy-Schwarz bounds the full color score by
`sqrt(1-w+w*r*r)`. Float channel sums avoid 8-bit grayscale rounding. The implementation
uses extra peak/rejection slack; that slack is empirical, not a proven bound on every
OpenCV native backend's floating-point behavior.

Native tests compare against the original color oracle: random and low-contrast
patches across four thresholds, all three real templates placed at screen edges (including
low contrast), masked-template parity, and degenerate/unsupported inputs. The adversarial
review additionally requested deliberately noise-mixed 84.9%/85.1% patches and nearly flat
local patches surrounded by noise; all six native oracle tests passed, including those cases.
The image-wide variance guard is not a guarantee of local-window conditioning. These are
numerical/matching tests, not evidence of actual game dialog behavior. Saved real Bear
fixtures and private OCR/scroll sequences also pass. A focused alternating eight-sample
classification replay measured 469 ms original versus 241 ms accelerated; classification
plus rally-row scanning measured 470–741 ms on the four failed-session frames, and 627 ms
on the earlier private pair. Capture, persistence and dispatch are still outside these
measurements. The subsequent full offline `package` reactor passed **1,118 tests, zero
failures/errors, six skipped**, with private frame replays enabled. The two additional
adversarial numerical test cases were added during that build and verified afterward in a
separate six-test native run; they are not included in the 1,118 count. The Linux bundle
passed structural verification (603 entries, 85 runtime JARs, 466 sprites).

The full-run A/B was 518 ms original versus 262 ms accelerated classification; classification
plus row scanning was 628–811 ms, with the earlier pair at 724 ms. These are saved-frame
results on this Linux/native build, not a cross-platform numerical proof or an end-to-end
input deadline guarantee. The one-second dispatch guard remains unchanged. Observe-only
timing on the actual capture pipeline and supervised live-event validation are still required;
unsupported preparation and missing Trap 2 evidence have not been silently enabled.
The final adversarial implementation/test review found no concrete blocker to the offline-tested
change; it did not endorse live readiness or claim a formal native floating-point error bound.

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

### Preparation isolation and pipeline measurement

Unsupported Auto Join and recall previously threw before pet activation and trap navigation,
skipping both even when their evidence was available. Preparation now handles each independent
step separately. Only the existing explicit unsupported-operation allowlist may be omitted;
an uncertain pet/navigation postcondition, device failure, persistence failure or cancellation
still aborts preparation. Each omission is journaled by operation with no input. Troop recall
and Auto Join themselves remain disabled until their positive before/after frames are available.

Every sampled frame now has a `sample-timing` diagnostic recording monotonic capture,
classification and synchronous evidence-writing durations. `postEvidenceAgeMs` includes the
source timestamp age up to that point, but excludes its own diagnostic callback. Actual input
boundaries emit `dispatch-timing` after returning/throwing, with entry/exit frame age and elapsed
boundary time; `boundaryCompleted` is not a game-side deployment confirmation. Existing newer-frame
postconditions remain authoritative. No input age budget was increased.

Observe-only sessions now run the production frame-scoped rally-row OCR on War-list frames,
logging `observe-only-row-scan` with frame id, row count, duration and freshness-budget status.
They do not arm candidates, persist tactical intent or send input. This measures capture,
classification, recording and row analysis together; it does not simulate permission reads,
device contention during dispatch or game-side action latency. It is instrumentation for the
remaining timing gate, not evidence that a live session has passed it.

Verification: the final offline `package` reactor, with both private replay directories enabled,
passed **1,128 tests, zero failures/errors, six skipped**. This includes the real-driver
unsupported-preparation regression, per-stage timing tests and read-only probe tests. Linux
bundle verification passed (603 entries, 85 runtime JARs, 466 sprites). The fresh adversarial
review found no concrete blocker in this delta. No new live timing or event validation was run.
