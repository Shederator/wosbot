# Bear Hunt frame evidence gate

The Bear routine now routes device input through `BearUiStateMachine`: a current ordered frame from
the bounded Android H.264 recorder authorizes one legal edge, and a later frame must prove the edge's postcondition.
State replay tests validate ordering, transition legality, retry bounds, and interruption semantics.
`BearLiveFrameReplayTest` additionally exercises nineteen privacy-redacted real frames from the
October 3 manual event. Fixture provenance and limitations are in
`modules/tasks/src/test/resources/bear/live-20261003/README.md`. Neither suite proves a complete
automated event works.

## Latest verification (2026-10-03)

The afternoon supervised run **failed: no rally creation or join was confirmed**.
Earlier passing suites below did not establish functional live readiness.
See `bear-hunt-failed-session-20261003.md` for the evidence-backed causes and fixes.

- Follow-up recording/pre-event navigation changes: full reactor 1,086 tests,
  zero failures/errors, five skipped, including the private two-frame OCR replay.
  Focused Bear tests also passed. Reviewer verified Territory/Special Back recovery
  and required-recorder-health input gating; this is not live-event approval.
- Device 1 read-only recording preflight: 40 warmed samples, median 580 ms,
  p95 647 ms, max 760 ms for capture/classification/recording. This excludes
  authorization OCR and dispatch. A separate 135-second capture decoded 3,477
  frames across two segments, proving a real 120-second renewal on that device.
- Earlier verification before these follow-up changes:

- Focused Bear suite passed with the private recorded-frame replay enabled.
- Full reactor: `./mvnw -q -o -Djava.awt.headless=true test`, with
  `FROSTGUARD_BEAR_RECORDING_FRAMES` set for the offline replay: 1,073 tests, no failures or
  errors, five skipped. Localhost socket permission was needed by existing server tests.
- Fresh adversarial review found and then verified fixes for ambiguous pet confirmation,
  pet-panel recovery, and duplicate completed/scroll anchors. No final live approval was given.
- Architecture remains incomplete: unsupported preparation/Trap 2 calibration and complete
  dispatch/return/recovery/cleanup sequence coverage remain gated. Decorated-name OCR can refuse
  a valid rally. No emulator input, normal-branch integration or deployment was performed.

## Collecting evidence with observe-only

Automated supervised runs may also set `-Dfrostguard.bear.record=true`. This uses
the decision stream itself rather than a second recorder. Required-recording health
is checked at every physical input boundary. Open/write failures or journal overflow
latch an operator-action outcome; later healthy segments do not hide missing evidence.

Enable **Observe only** in the profile's Bear settings for an event. The session owns the event
window like a normal Bear run but refuses every device input. It writes, under the workspace's
`logs/bear-capture/event-<end>/`:

- `segment-NNN.h264`: the raw recorder output, one file per 120-second recorder renewal. Replay it
  with `H264FrameDecoder`; it contains only frames emitted on display changes.
- `frames.jsonl`: one line per ordered frame with capture time, transport age, the production
  classification, total classification time, and time per template.

State changes are also saved as `logs/snapshot/*bear-observe*` PNGs. Redact account details before
committing any of it as a fixture (`tools/privacy-redactor`).

`BearCaptureLiveDeviceTest` is an opt-in wire check of the capture path against a real device with
no input (`FROSTGUARD_LIVE_ADB`, `FROSTGUARD_LIVE_ADB_SERIAL`). On an idle emulator it showed that the
recorder is silent on a static screen; the source now samples such screens with one `screencap`,
which took about 0.55 s per frame on that emulator. That cost counts against the one-second input
budget and must be measured on the supported setup.

## Still evidence-gated in production

- Trap 2 now has a pre-event numbered-identity calibration, but no active positive.
  Trap 1 requires a centered numbered
  nameplate plus active status, followed by a separate Bear-panel postcondition. These calibration
  frames do not prove the live target tap or all animation variants.
- Every preparation edge listed in `BearUiActionTest.EVIDENCE_GATED`.
- Live classification latency: recorded classification exceeded the one-second authorization
  budget in 83.7% of observations. Bounded template searches improve offline timings, but leave
  limited margin on World and need concurrent-emulator live measurement. Freshness is unchanged.
- Join and scroll now re-read captain OCR from the authorizing frame, rather than comparing exact
  pixel hashes across lossy H.264 frames. Identity requires the Bear target, exact normalized captain
  text and a versioned semantic key. A duplicate name or changed topmost order refuses the join.
  The one-second budget still includes OCR. More consecutive-frame and concurrent-device timing
  coverage is needed; the old comment asserting OCR was necessarily too slow was not supported by
  the measured warmed two-row scans (339–356 ms before end-to-end classification).
- Complete labelled clip equivalence: selected real PNGs are covered, but the full event is not
  labelled. Do not confuse single-frame regression coverage with complete sequence coverage.
- Labelled replay: put `<name>.h264` with a `<name>.labels` file (one expected screen per frame) under
  `modules/tasks/src/test/resources/bear/recordings/`; `BearRecordingReplayTest` then checks them.

## Recording-derived corrections

- War-list identity requires the War heading and selected Rally tab. A plus, close cross, or
  remembered previous visit is not page identity. Back requires the same-frame list identity and
  top-left arrow, then a newer World postcondition. Header identity supports empty lists without
  treating a zero-plus viewport as absolute bottom; a genuine empty-list frame is still needed.
- Rally-card scanning uses the recorded target artwork, not the World shortcut icon. The former
  detector returned zero rows in six inspected list frames. The real-frame regression checks two
  fully visible cards and their green plus controls; this does not validate leader OCR or joins.
- `BearPrivateRallyReplayTest` accepts `FROSTGUARD_BEAR_RECORDING_FRAMES` pointing to private
  decoded segment-6 frames 115/128/171/182/194/235. It tests real OCR identity across compression changes without
  committing account-bearing source frames. It is skipped unless explicitly enabled. This is
  offline evidence, not a live successful join or a CI guarantee for every captain name.
  In the local focused replay, classification plus the fresh two-row scan took 651 ms. The first
  captain matched across the two actual frames, but its plus had turned grey: identity must NOT
  authorize that join. The earlier replay assertion missed this and has been corrected. Template
  shape alone matches grey controls too; production now also requires a substantial green
  background in the detected button area. Frames 171/182/194/235 cover disabled rows, scrolling,
  a green plus and its later grey state. Excluding the sword edge from the name crop (x=291,
  previously x=280) now preserves the exact decorated identity across 182/194 and authorizes
  that offline green-control pair. The grey 235 control remains refused. Other names still
  need coverage; no fuzzy identity matching was introduced.
  Duplicate completed/pending anchors and duplicate bottom-proof rows are also refused.
- Special Buildings and Territory Buildings require their page title and selected tab; blue/green
  controls cannot identify either page. The recorded rally-detail and Settings negatives are covered.
- Trap status requires page/tab, the numbered trap heading and row-specific active/cooldown text.
  Both traps have Go buttons. An observed configured cooldown trap refuses own-rally navigation
  with an operator-action outcome. This does not silently change the saved trap or schedule, and
  does not establish trap identity from World.
- The generic rally indicator can remain for non-Bear rallies after Bear ends. It is neither
  activation evidence nor a cleanup-failure signal. The event-ended World fixture retains it.
- UNKNOWN recovery Back now uses the same frame freshness, single-attempt input boundary and
  newer-postcondition handling as other inputs, while preserving the one-Back-then-restart rule.
- Pet preparation follows the recorded Pet Skill panel, Razorbeak selection, Quick Use, the
  specific all-Battle-Skills confirmation, and positive active-benefit state. A pending panel
  after confirmation is not success. Durable `USE_ARMED` intent prevents repeating an ambiguous
  confirmation across scheduler/process retries; if arming succeeds but dispatch is refused,
  activation may conservatively be skipped. Recovery uses detected Close/Cancel and verifies
  the parent; it never confirms skills after activation merely to escape a panel.
- Back binds the exact authorizing source state. Known non-list parents use Android Back rather
  than the former generic top-left coordinate; pet and War controls use their detected targets.

## Evidence already represented by production classifiers

- World root and the active Bear indicator
- Bear rally panel, rally timer, and formation screen
- War list and join controls
- deploy confirmation, same-target dialog, and march-queue-full dialog
- open Wilderness march sidebar and its row/special-rally classifiers
- Alliance menu, Territory/Special Buildings page identity, and row-specific trap status
- reconnect and app-loading screens

## Exact live frames still required

These flows deliberately fail closed instead of falling back to the former coordinate-and-sleep
scripts. Capture each item at the supported 720 × 1280 viewport, including one negative neighbour
frame where noted, before enabling preparation in production:

1. Alliance War landing screen; Autojoin panel; Autojoin running; Autojoin stopped.
2. City/World with march sidebar closed; sidebar open on a non-Wilderness tab; recall button;
   recall confirmation; confirmed recalled/empty row.
3. Pet activation is already recorded (segment 1, decoded 258/480/490/505/512). Still needed:
   wider pet layouts, unavailable/cooldown battle skills, cancellation/close input timing and
   restart footage. Growth-skill Use must never substitute for the Battle-Skills confirmation.
4. Wider Alliance-menu to Territory navigation variants. Pre-event Bonus Overview,
   Special Buildings, row-specific Go, and Back to Alliance were observed October 3.
5. Special Buildings with Trap 2 active. Trap 1 active / Trap 2 cooldown is covered; both Go buttons
   remain enabled, so an inactive trap must not be modelled as a disabled Go button.
6. Centered Trap 1 is recorded (segment 13, decoded 569/788), with 788 represented in fixtures.
   Trap 2 centring and before/after target-input timing remain required. Trap 1 must not silently
   substitute for a configured Trap 2. Ordinary active World and event-ended World are negatives.
7. Formation strip scrolled to slots 9–12, with a locked and a selected high-slot example.
8. Event-end UI while the routine is on World, War list, Bear panel, formation, and each deploy
   dialog, so cleanup destinations can be checked against real pixels.
9. Rally pages with mixed green/grey/no-plus rows; a viewport with zero plus but additional rows
   below; reorder and disappearance between authorization frames; before/after scroll with the
   repeated overlap row and readable leader text; a genuine absolute-bottom frame; and the
   intermediate War screen used to reopen the Rally tab. Current traversal tests prove cursor
   rules, not these visual identities.
10. Formation screens for every supported normal slot in both idle and orange/occupied states.
    Current sanitized evidence covers slots 1, 3, 4, and 5; slots 6–8 still require real fixtures.
11. Successful Deploy returning to the identical list/scroll position; every game rejection
    variant; a failed Back; and all configured formations occupied followed by one becoming free.
12. Special preparing, outbound, returning, and idle across process restart, both with and without
    an exact persisted deadline; five normal slots and the VIP sixth slot while Special is active.
13. Event end during the final pre-Deploy authorization window and terminal cleanup from every
    classified Bear screen.
14. The actual Android H.264 120-second renewal, stale-frame suppression, ADB disconnect/rebind,
    Whiteout restart, and checkpoint resume on the supported emulator.

Until those fixtures exist, unsupported preparation, Trap 2 arrival, and high formation
slots return an explicit operator-action or fail-closed recovery outcome. The disappearance of a
rally indicator is not treated as proof that the correct Trap is centered. A real Bear event is still
required to validate timing, visual thresholds, trap identity, and game-side rally behaviour.
