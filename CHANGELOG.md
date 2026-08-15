# Bearguard Changelog

Bearguard is matt's personal fork of [Frostguard](https://github.com/Shederator/wosbot).
This file tracks Bearguard-specific version history — not upstream's, which lives in
`.github/release-notes/` and their own release process. See `docs/architecture.md` /
`labyrinth-debug/HANDOFF_2026-08-12.md` for deeper background on individual features.

## 2.1.1 — 2026-08-14

### Tap-input jitter upgrade

Ported the coordinate-randomization *policy* from upstream Frostguard v3.0.0's
centralized tap-input work ([PR #42](https://github.com/Shederator/wosbot/pull/42))
without pulling in that PR's full API rename (`tapPoint`/`tapRandomPoint` →
`tapInside`/`tapNear`, deleted `EmulatorController.touchPoint`/`touchArea`) — that
rename would have broken all ~230 existing call sites across ~70 files in this fork,
many of them Bearguard-only code the upstream migration has no knowledge of.

Instead, `dev.frostguard.engine.input.TapJitterPolicy` (new file, lifted verbatim
from upstream) is now called from inside `EmulatorInstance.tap()` — the single choke
point both `tapPoint()` and `tapRandomPoint()` already funneled through. Every
existing call site gets the upgrade automatically, with zero signature or call-site
changes:

- **Center-weighted sampling** (Bates n=2 — mean of two uniforms) instead of flat
  `Random.nextInt()`, so taps cluster toward the middle of a control like a real tap
  instead of landing anywhere uniformly, including the rim.
- **15% edge margin** automatically shrunk off area targets before sampling, so a
  tap can never land right on a button's edge.
- **Upward-only delay jitter** (+0–15% of the requested delay, capped at 120ms) on
  every repeated tap, so consecutive multi-taps no longer have byte-identical
  timing — Bearguard's prior jitter (`TAP_JITTER_RADIUS_PX_INT`, still the config
  key backing the point-jitter radius) only randomized position, never timing.

Verified: `TapJitterPolicyTest` (14 tests, ported as-is from upstream, pure logic —
no emulator dependency) + full repo test suite (150/152 passing; the 2 failures are
`ResearchTimerPolicyTest`, pre-existing and unrelated — that policy was intentionally
rewritten from half-time-with-cap to remaining-time-plus-margin and the test was
never updated to match, confirmed by reading `ResearchTimerPolicy.java`'s own doc
comment). Full `mvn package` compiles clean — zero call sites touched. Live-launched
and confirmed real taps fire correctly post-change.

**Rollback point:** commit `f30cf47` (tagged checkpoint immediately before this work)
is the last commit before the jitter change, pushed to `origin/main`.

### Labyrinth default troop ratios

Updated the default Infantry/Lancer/Marksman formation ratios for Land of Heroes,
Cave of Monsters, and Charm Mine to match an alliance-mate's posted recommendation
("For the best results in the labyrinth ... this will allow you to get the farthest
you can"):

| Zone | Old default | New default |
|---|---|---|
| Land of Heroes (both squads) | 60/40/0, 50/0/50 | 50/20/30 |
| Cave of Monsters | 60/40/0 | 50/10/40 |
| Charm Mine | 60/40/0 | 60/20/20 |

Gear Forge and Research Center were also in the recommendation (60/10/30 and
50/20/30) but aren't wired to automated ratio-setting yet — those two zones are
currently only handled by `LabyrinthRaidRoutine` (safe Raid-only claiming; the real
Challenge/battle path with troop-ratio setup is unbuilt for them, see the
"tail of the tape" work-in-progress).
