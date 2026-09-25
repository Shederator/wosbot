# Bear Hunt streaming-session plan

Status: confirmed design for the PR #346 rewrite. The current implementation is not live-ready.

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
- `bearAnchorVerifiedAt` treats a timestamp up to 35 minutes old as proof that the current camera is still at the Bear.
- `observeBearScreen()` cannot positively identify Territory, Special Buildings, the active Bear icon, the active Bear location, or a page still rendering.
- Pet preparation, Bear navigation, own-rally creation, joining, recovery, and march inspection each capture and wait independently.
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
