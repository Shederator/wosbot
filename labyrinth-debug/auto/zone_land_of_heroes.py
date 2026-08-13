"""
Land of Heroes climb loop.

Different mechanic from the troop-ratio zones (Cave of Monsters / Charm Mine):
- Troops are normalized to Lv.10 ("Labyrinth Explorers") -- troop TIER is irrelevant.
- Only HERO level / hero gear / hero exclusive gear + troop COMPOSITION ratio matter.
- Best-of-3 rounds, 2 (later 3) squads, damage carries over between rounds.
- CONFIRMED EMPIRICALLY (2026-08-10, twice) + by research: hard stages are HERO-gated,
  not comp-gated. Comp tuning only flips fights that are already hero-viable. So unlike
  the troop-ratio zones, there's no value in a reactive rotate-counter loop here -- the
  known-good ratio (60/40/0 on every squad, matt's mirrored fix) is climbed as far as it
  goes, and a wall is a hero-progression wall, not fixable by this script.
- matt called this zone "done for now" on 2026-08-10 (cleared 8-1 -> 8-9; 8-10 is the
  hero wall). This module exists so the daily orchestrator can still climb any FRESH
  ground that opens up as heroes improve, without needing a human to re-drive it.

Coordinates below come from the already-live-validated Java routine
(fg-tasks/.../DailyLabyrinthRoutine.java) and dev memory (bearguard-labyrinth-loh-flow.md)
-- formation setup + Save-and-Exit were driven live 2026-08-10 and work. The Deploy
button and the post-battle result screen were NEVER reached in that session (the test
flow deliberately stopped before Deploy to avoid burning attempts) and Land of Heroes is
NOT open this rotation ("was from yesterday" per matt, 2026-08-12) -- so those two pieces
are UNCALIBRATED. climb() refuses to run past formation setup until a human confirms
the Deploy point live, rather than guess and risk a misclick burning a real attempt.
"""

import time

from labyrinth_common import (
    tap, screenshot, deploy_and_get_result, BACK_ARROW, CLOSE_VICTORY_TAP,
    POST_ACTION_SETTLE,
)

# -- validated live 2026-08-10 (Java DailyLabyrinthRoutine + dev memory) --
CHALLENGE_BTN = (360, 1218)
ZONE_BANNER = (460, 337)
QUICK_DEPLOY_BTN = (197, 1193)          # fills heroes+troops in place on Squad Config
SQUAD_EDIT_BTNS = [(360, 357), (360, 700)]  # Squad 1 / Squad 2 "Edit Formation"
BALANCE_BTN = (330, 1195)
FORMATION_BACK_ARROW = (40, 40)
SAVE_AND_EXIT_BTN = (511, 788)          # on the "save the formation first?" dialog

BAL_MINUS_X, BAL_PLUS_X = 202, 511
BAL_ROW_Y = {"inf": 530, "lan": 675, "mrk": 820}
CONFIRM_BTN = (360, 978)
DET_TAP_DELAY = 0.09
FLOOR_TAPS = 105

# Known-good ratio, matt's mirrored fix (2026-08-10): 60/40/0 on every squad beats every
# stage that's hero-viable; comp tuning cannot crack a hero wall.
KNOWN_GOOD_RATIO = {"inf": 60, "lan": 40, "mrk": 0}

# --- NOT YET LIVE-CALIBRATED -- zone is locked this rotation (2026-08-12) ---
DEPLOY_BTN = None            # TODO: calibrate live once Land of Heroes reopens
RESULT_CHECK_NOTE = (
    "Land of Heroes result-screen colors/coordinates were never captured live -- "
    "the troop-ratio zones' Victory/Defeat banner detector should still work (same "
    "game-wide UI component) but has not been confirmed for this zone specifically."
)


def _raw_tap(x, y):
    from labyrinth_common import adb
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(DET_TAP_DELAY)


def set_ratio_deterministic(inf_pct, lan_pct, mrk_pct):
    tap(*BALANCE_BTN)
    for row in BAL_ROW_Y.values():
        for _ in range(FLOOR_TAPS):
            _raw_tap(BAL_MINUS_X, row)
    for key, pct in {"inf": inf_pct, "lan": lan_pct, "mrk": mrk_pct}.items():
        row = BAL_ROW_Y[key]
        for _ in range(pct):
            _raw_tap(BAL_PLUS_X, row)
    time.sleep(0.6)
    tap(*CONFIRM_BTN)


def setup_squad_formation(squad_index, ratio=KNOWN_GOOD_RATIO):
    """Squad Config -> Quick Deploy -> Edit Formation -> Balance -> set ratio ->
    Save-and-Exit. Validated live 2026-08-10 for both squads."""
    tap(*QUICK_DEPLOY_BTN)
    tap(*SQUAD_EDIT_BTNS[squad_index])
    set_ratio_deterministic(ratio["inf"], ratio["lan"], ratio["mrk"])
    tap(*FORMATION_BACK_ARROW)
    tap(*SAVE_AND_EXIT_BTN)


def climb(max_stages=50, stop_after_consecutive_losses=2, log=print):
    if DEPLOY_BTN is None:
        raise RuntimeError(
            "zone_land_of_heroes.climb() cannot run past formation setup: the Deploy "
            "button coordinate has never been live-calibrated (Land of Heroes is not "
            "open this rotation). Formation setup (setup_squad_formation) is safe to "
            "run standalone -- it's free and deliberately stops before Deploy. Once "
            "this zone reopens, calibrate DEPLOY_BTN + verify detect_result() against "
            "one real Victory/Defeat screen, then remove this guard."
        )
    # (kept intentionally unimplemented past this point -- see docstring)
