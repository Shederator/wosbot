"""
Daily Labyrinth orchestrator.

matt's spec (2026-08-12), verbatim goal: enter a zone, tail-of-the-tape scout, adjust,
fight. Lose once -> scout again, adjust, retry. Lose twice -> STOP, diagnose why (both
losses), log it. Back out to the Labyrinth map, check what else is open, jump into the
next zone, repeat. Next day (fresh attempts): for a stage already logged as a wall, try
it ONCE more with the best-known ratio (24h may mean stronger pets) -- win it and keep
climbing, lose it and immediately move to the next zone rather than re-spend attempts
re-discovering the same wall.

That reactive/diagnostic/persistent-memory loop lives in zone_troop_ratio.climb() +
zone_history.py. This file is just the dispatcher: scan the map, run each open+
automated zone's climb() to its stop point, chain to the next zone, log the run.

Coverage as of 2026-08-16:
- cave_of_monsters, charm_mine: fully wired, including the known-wall-retry logic.
- gaia_heart: wired 2026-08-16, live-calibrated against the real zone (it was actually
  open -- a Sunday). Genuinely different mechanic from the troop-ratio zones (real
  troops/heroes, one Deploy auto-chains many stages via the game's own "Auto-Challenge
  Next Stage", attempts are spent per Deploy/Retry not per stage) -- see
  zone_gaia_heart.py's module docstring for the full writeup. No reactive enemy-comp
  counter yet (a loss gets one same-config retry, then power-wall). Only actually
  climbable on the day it's open -- zone_scan.py only detects it when it renders on the
  default map view, which so far has only been confirmed true while open.
- research_center, gear_forge: mechanics unknown, zone entry point unverified (both
  locked all session) -- scanner reports them but this script will NOT auto-climb them
  until a human has scouted the zone once, per matt's own plan.
- land_of_heroes: not on the default map scroll position this rotation, not covered by
  zone_scan.py yet.

Every run appends one entry to labyrinth_log.json (the activity log). Per-stage "why we
lost" detail lives separately in labyrinth_stage_history.json (zone_history.py) --
that's the persistent knowledge base a fresh run reads BEFORE spending an attempt.
"""

import argparse
import json
import time
from datetime import datetime

from labyrinth_common import (
    tap, swipe, screenshot, wait_for_screen, is_labyrinth_map_screen, is_stage_screen,
    BACK_ARROW, run_length_json_path,
)
import zone_scan
import zone_troop_ratio
import zone_gaia_heart
import zone_history

AUTOMATED_ZONES = {"cave_of_monsters", "charm_mine"}
GAIA_HEART_ZONE = "gaia_heart"
NEEDS_HUMAN_SCOUT_FIRST = {"research_center", "gear_forge"}


def load_log():
    path = run_length_json_path()
    if path.exists():
        return json.loads(path.read_text())
    return []


def save_log(entries):
    path = run_length_json_path()
    path.write_text(json.dumps(entries, indent=2))


def navigate_to_labyrinth_map(log=print):
    """City screen -> left menu -> scroll down -> tap the Labyrinth row -> lands on
    The Labyrinth map. Validated live 2026-08-12 (repeated several times this session).
    Returns True if the map anchor confirms, False otherwise (caller should not
    proceed blind)."""
    tap(20, 550)   # open left menu
    time.sleep(1.5)
    swipe(220, 700, 220, 300, 500)  # scroll down to the Labyrinth section
    time.sleep(1.0)
    # the Labyrinth row's Y position can shift slightly depending on what else is
    # active above it (Alliance Contribution, Life Essence, etc.) -- tap where it's
    # consistently landed this session, then verify rather than trust blindly.
    tap(224, 830)
    img = wait_for_screen(is_labyrinth_map_screen, label="labyrinth_map", timeout=5.0)
    if img is None:
        log("navigate_to_labyrinth_map: didn't land on the map -- aborting rather "
            "than guessing.")
        return False
    return True


def enter_zone(zone_key, log=print):
    tap(*zone_scan.ZONE_ENTER_POINT[zone_key])
    img = wait_for_screen(is_stage_screen, label=f"enter_{zone_key}", timeout=5.0)
    if img is None:
        log(f"[{zone_key}] didn't land on a stage screen after entering -- aborting.")
        return False
    return True


def return_to_map(log=print):
    """From a zone's stage/map view, the top-left back arrow returns to The Labyrinth
    map (NOT the X close button, which exits all the way to the City screen)."""
    tap(*BACK_ARROW)
    img = wait_for_screen(is_labyrinth_map_screen, label="return_to_map", timeout=5.0)
    return img is not None


def run_daily(dry_run=False, log=print):
    if not dry_run:
        if not navigate_to_labyrinth_map(log=log):
            log("Could not reach the Labyrinth map -- stopping the whole run rather "
                "than guessing where we are.")
            return {"date": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
                    "zones": {}, "aborted": "could_not_reach_map"}

    zones = zone_scan.scan_visible_zones()
    log("Zone scan:")
    for key, info in zones.items():
        log(f"  {key:20s} {'OPEN' if info['open'] else 'locked'}")

    entries = load_log()
    run_record = {"date": datetime.now().strftime("%Y-%m-%d %H:%M:%S"), "zones": {}}

    for key, info in zones.items():
        if not info["open"]:
            continue

        if key in NEEDS_HUMAN_SCOUT_FIRST:
            log(f"[{key}] open, but mechanics/entry point not yet verified -- "
                f"skipping until a human scouts it once.")
            run_record["zones"][key] = {"status": "skipped_needs_human_scout"}
            continue

        if key != GAIA_HEART_ZONE and key not in AUTOMATED_ZONES:
            log(f"[{key}] open, but no climb module wired up yet -- skipping.")
            run_record["zones"][key] = {"status": "skipped_no_module"}
            continue

        if dry_run:
            log(f"[{key}] DRY RUN -- would enter zone and climb().")
            run_record["zones"][key] = {"status": "dry_run_skip"}
            continue

        log(f"[{key}] entering zone...")
        if not enter_zone(key, log=log):
            run_record["zones"][key] = {"status": "failed_to_enter"}
            continue

        if key == GAIA_HEART_ZONE:
            result = zone_gaia_heart.climb(log=log)
            log(f"[{key}] done: {result}")
            run_record["zones"][key] = result
            if not zone_gaia_heart.return_to_stage_select(log=log):
                log(f"[{key}] couldn't confirm return to stage-select after the run -- "
                    f"stopping here rather than guessing.")
                break
            if not return_to_map(log=log):
                log(f"[{key}] couldn't confirm return to the Labyrinth map -- "
                    f"stopping.")
                break
            continue

        result = zone_troop_ratio.climb(key, log=log)
        log(f"[{key}] done: {result}")
        run_record["zones"][key] = result

        if not return_to_map(log=log):
            log(f"[{key}] couldn't confirm return to the Labyrinth map -- stopping "
                f"the run here rather than guessing where we ended up (won't risk "
                f"blind taps into whatever zone comes next).")
            break

    entries.append(run_record)
    save_log(entries)
    log(f"\nLogged to {run_length_json_path()}")
    log(f"\nKnown power walls on record:\n{zone_history.summarize_known_walls()}")
    return run_record


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    run_daily(dry_run=args.dry_run)
