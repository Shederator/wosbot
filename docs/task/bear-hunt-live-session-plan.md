# Bear Hunt streaming-session plan

Status: confirmed design for the PR #346 rewrite. The current implementation is not live-ready.

## 2026-09-29 implementation review

The coordinator now encodes the core event contract as one continuous session:

- the own-rally flag is attempted first whenever its Special march is absent and the T-5:30
  cutoff has not passed;
- configured join flags fill ordinary march slots while the own rally is preparing, marching,
  or returning;
- an observed return makes own-rally recreation preempt the next join pass;
- recoverable own-rally failures yield to joining for that pass instead of starving rewards;
- active joining opens War from the World red rally indicator, never through Alliance;
- cleanup remains outside the coordinator and runs only after event end, cancellation, or an
  unrecoverable result.

The live driver now retains a verified march snapshot between meaningful deadlines. A failed
candidate or navigation recovery can therefore continue from the current Bear screen instead of
opening the World-only march sidebar every 250 ms. Exact march countdowns and the tracked Special
rally return time schedule the next refresh; only occupied rows with no trustworthy countdown use
a five-second safety refresh.

This is unit verified, not replay- or live-accepted. The remaining live-readiness gap is
positive visual proof that the World camera is still centered on the Bear. The existing bounded
anchor timestamp must not be treated as final acceptance evidence; replacing it requires a
sanitized real frame or a stable Bear-location marker from the next event.

### Scheduler containment implemented after the live failure

The scheduler now acquires an immutable per-profile Bear session lease before dispatching the
Bear task. Its deadline comes from the configured recurring event window at acquisition time, not
from the task's mutable next-run timestamp. While that lease is active:

- every non-lifecycle task is deferred until the captured event end plus the protection buffer;
- task exceptions, profile refreshes, and timer advances cannot release or shorten the lease;
- idle handling cannot suspend the device;
- a typed Bear-session failure is routed through a bounded directive-specific recovery policy and
  is no longer reported as a successful task completion;
- only the captured deadline or an explicit queue stop releases the lease.

An integration regression now executes the exact scheduler failure shape: Bear acquires its lease,
advances the mutable timer, fails, and Alliance Chests becomes due. Chests must record zero
executions and be deferred to the original event deadline. An unaligned visual marker also no
longer invents a new thirty-minute session; it fails closed and asks for timer correction.

### 2026-10-01 recovery substrate

The active-session driver now treats frame acquisition as a bounded, typed operation. Two transient
capture failures may recover inside the same frame stream. Exhaustion preserves the original cause and
reports the failure kind, device, operation, and recovery directive to the scheduler. The scheduler
is the only layer that assigns the protected retry time. It may perform at most two serial-specific
cache/probe attempts or two bounded Whiteout restarts during one queue lifetime; it never restarts
the process-global ADB bridge from the Bear recovery path. Exhausted or operator-required recovery
stops tactical retries but leaves a scheduler-owned event-end finalizer queued. Its deadline is
persisted per profile, restored when queues or Frostguard restart (even while Bear participation is
disabled), and cancelled before a deliberate Bear Run Now. The finalizer restores normal work and
resolves the next configured Bear window without re-entering the broken UI session.

Active-session screenshots requested by Bear navigation, rally scanning, deployment checks,
march classification, and sidebar navigation now pass through the session frame source. Frames
carry a monotonic sequence and capture time. The own-rally, join-rally, and War-opening taps reject
stale authorizing frames, and a join row's semantic identity, capacity, button state, and countdown
are rechecked on a second fresh frame before tapping. Some Back
and preparation actions still need full provenance conversion. Reconnect and app-loading frames are
explicit non-actionable states, and app-loading recovery must end at a state valid for the requested
resume goal. The old 35-minute
Bear-location timestamp has been replaced with a three-second transition proof that is useful only
for the immediate follow-up tap and is invalidated on navigation failure.

This is synthetic fault-injection/unit verified, not real-frame, replay, or live verified. Preparation still needs
saved evidence for Territory and the Bear location before those states can be accepted purely from
current-frame identity. Until those fixtures and the inactive Trap 2 wire trace exist, the PR stays
draft and the implementation must not be described as live-ready.

## 2026-09-27 live Bear findings

The Linux/Android SDK live session confirmed the following game mechanics and failure modes:

- Formation slot 1 is reserved for creating the five-minute own rally. Configured join slots
  (slot 2 onward for the observed account) are used in order for other leaders' rallies.
- The own rally occupies the separate `Special` row. It does not reduce the ordinary
  `Marching x/5` capacity available for joined rallies.
- While the own rally is preparing, marching, or returning, every free ordinary slot should be
  filled from the War list. When `Special` returns, own-rally recreation preempts the next join
  attempt, provided the T-5:30 cutoff has not been reached.
- The red rally indicator on World is the active-event route to the War list. Opening
  `Alliance -> War` wastes time and is not part of the live loop.
- A successful join returns to the War list. The bot must press Back exactly once, prove World,
  and only then inspect the World-only march sidebar. Reading marches directly from the War list
  reproduces the navigation failure seen during the event.
- Rally cards are volatile. They can depart, fill, or lose troop capacity between the list scan
  and Deploy. These outcomes keep the same configured formation and move to another candidate.
- The capacity warning (`Troops will exceed your deployment Capacity`) is a rejected candidate,
  not permission to Equalize or change troop sliders. Dismiss it, leave formation, and retain the
  same flag for the next candidate.
- Very short candidates observed at 6-20 seconds were not realistically deployable through the
  verified formation flow. Candidates now require at least 30 seconds at scan time and are checked
  again against elapsed time immediately before Deploy.
- Candidate choice is not screen-order-only. Prefer remaining troop capacity, then enough time to
  act, then lower occupancy, with row position only as the final deterministic tie-breaker.
- During the last five minutes, do not create another own rally; continue joining existing rallies
  until event end, using three fresh empty scans to distinguish a drained list from one transient
  refresh.

The 2026-09-27 event ended before these changes could receive another live acceptance run. They
therefore remain replay/unit verified until the next Bear window.

## Corrected contract

Bear has two different navigation routes. They must not be collapsed into one fallback loop.

### Before activation: preparation route

1. Reach a verified, unobstructed World screen.
2. Perform configured recall and pet preparation once.
3. Navigate `Alliance -> Territory -> Special Buildings`.
4. Wait for the configured Trap 1 or Trap 2 `Go` control to render.
5. Tap `Go` once and prove the camera arrived at that Bear Trap.
6. Keep the Bear location ready while waiting for activation.

### After activation: active-event route

1. Reach a verified, unobstructed World screen.
2. Prove that the active Bear icon is visible.
3. Tap that icon once; it is the fast path to the configured active Bear.
4. Prove arrival at the Bear before opening its panel.
5. If the camera is already at the Bear, continue immediately without reopening Alliance or Territory.

The Alliance route is not the normal active-event path. It is allowed after activation only as a bounded recovery when current visual evidence proves that the active Bear icon path is unavailable.

## What is broken today

- `reachBearTrap()` decides that Territory failed after a fixed 1.5-second window even when the game finishes the transition later.
- Territory success is inferred from the previous template disappearing instead of the destination screen appearing.
- The immediate Bear-arrival proof is transition-derived and expires after three seconds; a stable
  current-frame Bear-location identity still requires saved live evidence.
- `observeBearScreen()` cannot positively identify Territory, Special Buildings, the active Bear
  location, or a page still rendering. It can now identify the active Bear icon from a fresh frame,
  but the destination after tapping still lacks positive Bear-centered proof.
- Pre-activation pet and recall preparation still run outside the active-session frame stream.
- The coordinator opens and rereads the march sidebar at the top of each loop and again after a recoverable own-rally failure. That discards useful navigation state and creates the observed loop.
- Recovery uses generic navigation and template disappearance, so it can press Back or reroute without knowing the destination.
- Fixed sleeps in autojoin, pets, recall, and preparation make the critical route slow and unresponsive.

## Target architecture

### 1. One task-scoped frame stream

Add a `BearFrameStream` owned by the live Bear session. It is the only component that captures screens during the critical path.

Each frame carries:

- a monotonically increasing sequence number;
- capture time and age;
- the raw image;
- all Bear screen detections derived from that same image.

Consumers may reuse a fresh frame. After a tap, they must request a frame with a higher sequence number. Independent helper methods must not capture their own competing screenshots in the Bear hot path.

### 2. Positive screen classification

Replace the timestamp-based anchor with a `BearScreenClassifier` that can positively identify:

- `WORLD_OBSTRUCTED`;
- `WORLD_READY`;
- `WORLD_ACTIVE_BEAR_ICON_READY`;
- `WORLD_AT_BEAR`;
- `ALLIANCE_MENU`;
- `ALLIANCE_TERRITORY`;
- `SPECIAL_BUILDINGS`;
- `BEAR_PANEL`;
- `RALLY_TIMER_PANEL`;
- `FORMATION`;
- `WAR_LIST`;
- `MARCH_SIDEBAR`;
- `TRANSITIONING`;
- `UNKNOWN`.

Risky actions require combined evidence: a stable identity template plus the relevant enabled/selected color state. `TRANSITIONING` is not a failure and cannot authorize a tap.

### 3. Verified action executor

Every action follows one rule:

1. Frame N proves the source state and identifies the target.
2. Check cancellation immediately before input.
3. Tap exactly once.
4. Only a later frame may prove the destination state.
5. Retry only when a later frame proves that the source state and target are still unchanged.

An interrupted poll must never produce a retry tap. Template disappearance alone is not success.

### 4. Navigation state machine

Create one `BearNavigationMachine` with explicit phase and goal:

- phases: `PREPARING`, `ACTIVE`, `JOIN_ONLY`, `ENDED`;
- goals: `PREPARED_AT_BEAR`, `BEAR_PANEL`, `WAR_LIST`, `WORLD_READY`.

It selects the shortest valid edge from the current classified screen. It never restarts the whole route merely because one destination frame rendered slowly.

#### Preparation graph

`WORLD_READY -> ALLIANCE_MENU -> ALLIANCE_TERRITORY -> SPECIAL_BUILDINGS -> WORLD_AT_BEAR`

#### Active graph

`WORLD_READY -> WORLD_ACTIVE_BEAR_ICON_READY -> WORLD_AT_BEAR -> BEAR_PANEL`

#### Join graph

`WORLD_READY or WORLD_AT_BEAR -> WAR_LIST`

Known child screens may use one verified Back transition. Unknown screens first recover to a positively identified unobstructed World screen; they do not blindly press Back multiple times.

### 5. Preparation controller

Preparation runs each configured side effect once and records its result:

- pause conflicting autojoin;
- optionally recall gather marches;
- optionally activate the configured pet benefit;
- follow the full pre-activation Bear route;
- remain at the verified Bear location until activation.

Waiting for activation is a cancellable deadline, not one long sleep. The stream continues checking for activation, unexpected dialogs, lost World state, and cancellation.

### 6. March-state observer

Replace repeated synchronous `MarchHelper.readMarchQueueSinglePass()` calls with a session cache:

- obtain one reliable snapshot at session start;
- update the cache after a verified deployment;
- schedule the next sidebar refresh from known rally/travel/return deadlines;
- refresh early only when an action result makes the cache uncertain;
- restore the previous navigation goal after an unavoidable sidebar read.

A navigation failure must not trigger a march-sidebar read. March inspection and navigation recovery become separate decisions.

### 7. Own-rally controller

Before T-5:30:

1. Confirm the cached own march slot is free.
2. Use the active Bear-icon route or continue from `WORLD_AT_BEAR`.
3. Open the Bear panel and choose Rally.
4. Select and verify the five-minute timer.
5. Select the configured positional formation slot.
6. On a later frame, prove that exact slot is yellow. Names such as `BT` are irrelevant.
7. Verify deploy is enabled and the travel time still fits before the cutoff.
8. Tap Deploy once.
9. Confirm success from the post-deploy screen and cached march-state change.

One bounded own-rally recovery cycle is allowed. If it still cannot launch, joining must continue; own-rally navigation must never starve join rewards.

### 8. Join controller

- Open the War list directly from a verified World frame.
- Scan all visible Bear candidates from one frame.
- Reject grey, departed, non-Bear, full-member, or insufficient-troop-capacity rows.
- Rank candidates by capacity margin, remaining countdown, occupancy, then stable row position.
- Keep the same formation when a candidate fills, departs, or loses a race.
- Advance the configured positional formation only after a confirmed deployment or confirmed formation unavailability.
- Return to World after a successful join so the next rally moves up, then reopen the list immediately.
- Continue joining through event end. During the final window, stop only after three complete fresh scans show no joinable Bear rally.

### 9. Timing policy

- Own rally preparation is five minutes.
- Minimum observed travel is seven seconds outbound and seven seconds return.
- Stop creating own rallies at T-5:30.
- Continue joining existing rallies until the event ends.
- No fixed sleep is allowed in the active loop.
- Wait only for a known render deadline, march-return deadline, activation time, or event end; all waits remain cancellable.

## Implementation slices

### Slice A: saved evidence and RED tests

- Sanitize and save the relevant frames from the failed live run.
- Capture an inactive Trap 2 navigation trace without launching a rally or consuming formations.
- Add failing tests for destination-positive Territory/Special Buildings/Trap transitions.
- Add the exact live regression: `Go` succeeds and World-at-Bear appears after the old timeout; the machine must continue rather than restart.
- Add the exact coordinator regression: recoverable own navigation failure must not reread marches or starve joins.

### Slice B: frame stream, classifier, and executor

- Implement frame sequencing, freshness, and later-frame postconditions.
- Implement all screen states and `TRANSITIONING` handling.
- Move Bear-critical taps behind the verified action executor.
- Prove cancellation prevents a second tap.

### Slice C: split navigation routes

- Implement the pre-activation Alliance route.
- Implement the active-event Bear-icon route.
- Reuse `WORLD_AT_BEAR` without rerouting.
- Add bounded recovery from every known child screen.

### Slice D: preparation and march cache

- Convert pets, recall, and autojoin preparation to verified state transitions.
- Replace the activation sleep with a cancellable deadline loop.
- Add the march-state cache and remove sidebar reads from navigation recovery.

### Slice E: own rally and joining

- Drive both through the shared stream and navigation machine.
- Require later-frame yellow formation proof.
- Add bounded own retry with join fallback.
- Preserve formation order and candidate-race behavior.

### Slice F: integration and packaging

- Run focused Bear/navigation/formation/march tests.
- Run the full Maven reactor and compare it with baseline.
- Build the Elroy Nightly image only after deterministic gates pass.
- Keep PR #346 draft until live acceptance.

## Verification gates

### Automated replay gates

- No tap may be authorized by a stale or pre-action frame.
- An interrupted poll cannot issue a retry tap.
- A slow render cannot be mistaken for navigation failure.
- Pre-event navigation uses Alliance, Territory, and Special Buildings in order.
- Active-event navigation uses the visible Bear icon and does not reopen Alliance.
- Already-at-Bear continues directly to the panel.
- One own-navigation failure does not open the march sidebar.
- Repeated own failure degrades to joining within a bounded deadline.
- The configured formation position must be yellow on a later frame before deployment.
- No own rally starts at or after T-5:30.
- Joining remains active until event end.
- A Bear exception cannot dispatch Alliance Chests, Gather, or another normal task before the
  scheduler-owned session lease expires.
- Mutating the configured next Bear timer after lease acquisition cannot shorten the active lock.
- An unaligned visual Bear marker cannot manufacture a new event deadline.

### Inactive Trap 2 wire test

Using ADB only:

1. Start from an unobstructed World screen while Bear is inactive.
2. Run the preparation navigation graph to Trap 2.
3. Record frame sequence, classified states, actions, and transition durations.
4. Stop after positively proving arrival at Trap 2. Do not open a rally or deploy a formation.
5. Replay the trace as deterministic test fixtures.

### Next live Bear acceptance

- Preparation completes before activation and remains at the configured Bear.
- Activation switches to the Bear-icon fast path without restarting the Alliance route.
- World ready -> active Bear arrival is under two seconds p95 when the icon is visible.
- At Bear -> rally panel is under one second p95.
- Rally panel -> verified deploy tap is under two seconds p95.
- Failed join candidate -> next candidate is under 750 ms p95.
- Returned formation -> next attempt is under five seconds when a valid rally exists.
- Own and join formations visibly select yellow and deploy in configured positional order.
- No navigation/march-sidebar loop occurs.
- No own rally begins after T-5:30; joins continue to event end; cleanup runs once.

The PR becomes ready only after the automated replay gates, the inactive Trap 2 wire test, the full suite, and the next live Bear acceptance all pass.
