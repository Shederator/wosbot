# Fishing Tournament

Tracking: [#128](https://github.com/Shederator/wosbot/issues/128).
Experimental rewards-first workflow; not ready for unattended operation.

## Supported mechanics and safety

- Verified layout: 720x1280 portrait, Winter season. Descent avoids all
  foreground objects, not just fish. Practice uses a 100m line and ten slots;
  the observed ordinary cast uses a 550m line and twenty slots.
- Home shortcut detection covers the shifting event column down to 320px and
  tolerates its animated art at 85% matching. This only opens the event page;
  the title and bait-spending controls retain independent 90% verification.
- The event remembers Club/leaderboard tabs under the same title. Verify the
  overview's Ice Fishing control, selecting the first tournament tab once when
  absent, before reading bait. Unverified navigation never authorizes a cast.
- On live event entry the first decoded frame read `710`, then subsequent
  frames read `7/10`. Bait verification uses a four-second polling budget for two
  agreeing distinct fresh frames using one retained OCR engine. Malformed text
  resets agreement; never infer a missing slash or spend on one observation.
- A real paid stage can be suspended through Pause -> Exit. Its locked
  overview offers Go Fish, which restarts the same stage without another bait.
  Practice Exit instead returns to an ordinary overview. Do not confuse these
  states or select replacement bait during recovery. Re-check recovery after
  opening the event from home. Go Fish gets a bounded 15-second HUD transition
  wait without a second trigger tap; the observed five-second wait expired
  before the resumed gameplay was recognized.
- Normal Cast and no-item recognition must finish inside the 250ms input frame
  budget. A stale result is discarded and another frame checked within five
  seconds; only the verified fresh result can send the single cast tap.
- Ascent color alone is unreliable: a returning hook can remain green.
  Confirm phase with depth trend and hook evidence. Fish-head contact matters;
  ordinary foreground segmentation does not positively identify heads, species,
  points, or healthy versus troubled divers.
- Quantity-first ascent pursues reachable foreground opportunities. Keep target
  lanes inland (100..620); caught sprites can obscure the hook at a wall. Bound
  finger displacement conservatively for a provisional 2.5x hook response,
  retaining the hook inside 70..650. This is not a universal physics guarantee.
- Full haul is a rewards-first success even without 80% descent. Unverified
  setup, costs, controls, or outcomes must preserve remaining bait.

## Resource ownership and evidence

- One Android-serial-pinned H264 recording and scrcpy touch session per bounded
  cast/retry. Decode only the latest observation; initialize native decoding and
  retained OCR before bait selection. No desktop focus or mouse ownership.
- The bundled unmodified scrcpy 4.1 server is hash-verified and attributed in
  `modules/automation/src/main/resources/android/README.md`. Its video, audio,
  clipboard synchronization and power changes are disabled; a separate recorder
  supplies frames. Cleanup targets only owned UUID files, forwards and verified
  Android PID/start-time identities, not other emulator processes.
- Movement feedback must settle before another gesture. Cancellation releases
  owned touch contact and closes recording, OCR, socket and forwarding resources.
  Repeated steering failures stop inputs but continue observing a paid result.
- Crop before expensive matching; use color/outline segmentation and mature
  motion tracks rather than matching hundreds of species templates. Guide art,
  color histograms and raw feature-match counts were unreliable gameplay IDs.
- Retain rope evidence above the bright indicator halo. Hook occlusion by the
  HUD or caught stack must not itself prove a phase change or suppress retries.
- A haul needs both Haul and Exit OCR, an unchanged Exit button and a freshly
  matched Haul title before tapping. The heading's background light animates;
  freezing its entire region blocked a live 20/20 haul despite correct OCR.
  Receipt time alone does not prove capture latency.

## Settings and retries

- Per-profile task-session limits, not daily quotas: fresh casts 1..10, boosted
  fresh casts 0..10, target catches capped by actual capacity, and same-bait
  restarts 0..10. Invalid persisted budgets use conservative defaults.
- Defaults: target twenty, two maximum short-haul restarts, retry enabled,
  random-corner fallback enabled, zero boosted casts, Lantern/Stabilizer off.
  Optional unavailable items permit an ordinary cast; required items stop it.
- Without a verified Lantern, three distinct dark observations select one random
  left/right corner per cast and hold it. Verified Lantern casts retain avoidance.
  Darkness thresholds have synthetic coverage, not a verified deep-water run.
- Restart only on confirmed ascent with fresh depth and haul (under one second),
  a short catch count, depth 1..40m and remaining budget. Forty metres is a
  safety buffer, not guaranteed time before tally. Final hauls cannot restart.
- Verify Pause, its Exit/Continue controls and the locked Go Fish overview.
  Retry never selects a fresh Normal Cast or changes the original item loadout.
  Renew bounded resources and retain the independently verified line maximum.
- Horn/Scanner, multipliers, Voucher, daily chest and Treasure Hunt are not
  automated. Gem buying is not implemented or exposed as a working setting:
  the inspected Pro Set shop was a cash pack, not verified gem confirmation.

## Validation boundary

Saved real frames cover entry, cast controls, HUD/rope occlusion, depth flashes,
loadout reservation checkmarks, pause and suspension. Fixtures are anonymized.
Four authorized ordinary-bait component trials yielded 1,090 points in total;
the latest yielded 20/20 and 330 points with two steering commands. These were
diagnostic controller runs, not completed production-routine/account-log proof.
Manual same-bait suspension/replay was observed; the latest policy run filled
its haul before the retry gate and did not validate automatic short-haul retry.
No boosters, gems, vouchers, chest claims or Treasure Hunt spins were consumed.

An actual production EXE cast reached 20/20 and a 360-point Haul, but timed out
at the animated heading guard. The corrected guard has saved-frame coverage.
A subsequent authorized ordinary cast was interrupted by an unexplained game
reload; its paid stage survived. The EXE reopened the event and tapped Go Fish,
but its five-second HUD wait expired before gameplay verification. That stage
yielded 270 points. A private component probe invoked only the packaged
production `exitVerifiedHaul` method and verified automatic Exit plus return to
the overview: total points 1,720, bait 5/10, item counts unchanged. This is live
exit-component proof, not an uninterrupted production EXE cast-to-exit pass.

The local EXE includes this workflow. Integrated full testing passed 855 tests
before these latest corrections; subsequent Linux and Windows installer/smoke
CI passed. Automatic short-haul retry, the extended Go Fish transition wait,
booster restoration, first-cast tutorial variants, value/head recognition, full
runtime resource/performance evidence and unattended production validation
remain unverified live.
