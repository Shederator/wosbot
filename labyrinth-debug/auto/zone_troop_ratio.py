"""
Generic climb loop for troop-ratio Labyrinth zones (Cave of Monsters, Charm Mine, and
any future zone with the same UI shape: pet/chief-charm stats decide the fight, troops
are normalized Apex troops, only the Inf/Lan/Mrk COMPOSITION ratio is a lever).

Design (corrected live 2026-08-12, twice):
- Pre-fight "View Details" (magnifying glass) shows OUR stats vs the opponent's per-type
  Attack/Defense/Lethality/Health bonus %, but NOT the opponent's troop composition. We
  still scout it before every Challenge (matt's rule) -- it's the only free look at
  whether the matchup still looks winnable, even though it can't drive a comp counter.
- The opponent's actual Inf/Lan/Mrk comp is only visible on the POST-fight Battle Report
  -> Battle Details screen for a completed battle.
- So the ratio-counter loop is necessarily REACTIVE: fight, and only after a LOSS do we
  have enough info (enemy's real comp) to compute a counter and retry.
- Every screen transition is verified via wait_for_screen() before the next tap -- a
  blind Challenge-then-Deploy sequence silently landed on the wrong screen once (stage
  3-8, 2026-08-12) and a blind OCR-failure fallback exited all the way to the City
  screen. Never guess past a timeout -- stop and report.

Stop rule (Observed live refined): don't hard-cap at a fixed 2 losses -- go up to
the real daily attempt ceiling (default 5) IF trying a different comp still makes sense.
But stop the moment the diagnosis says it doesn't, even if attempts remain:
  - the freshly-computed counter is identical to the ratio we just lost with (nothing
    left to adjust -- re-fighting would just repeat the same loss), or
  - the attempt cap is reached.
Each stop is diagnosed and written to zone_history.py (labyrinth_stage_history.json) so
a future run knows why before spending another attempt. A stage already on record as a
power wall gets exactly ONE retry with the last-known-best ratio (24h may mean stronger
pets) -- win it and keep climbing, lose it and move straight to the next zone.
"""

import time
from datetime import datetime

from labyrinth_common import (
    tap, swipe, screenshot, ocr_text, find_pct_row, rotate_counter,
    dismiss_confirmation_dialog, detect_result,
    wait_for_screen, is_stage_screen, is_troop_adjust_screen, is_result_screen,
    is_battle_report_list_screen, is_battle_details_screen, get_current_stage_label,
    BACK_ARROW, VIEW_BATTLE_REPORT_BTN, CLOSE_VICTORY_TAP,
)
import zone_history

# ---------------------------------------------------------------------------
# UI coordinates shared by every troop-ratio zone (720x1280, validated live 2026-08-12
# for both Cave of Monsters and Charm Mine -- same underlying UI component).
# ---------------------------------------------------------------------------

SCOUT_MAGNIFIER = (133, 1092)      # on the stage screen, before Challenge
CHALLENGE_BTN = (360, 1217)
EQUALIZE_BTN = (199, 1185)
BALANCE_BTN = (330, 1195)
DEPLOY_BTN = (549, 1213)
CONFIRM_BTN = (360, 978)           # Balance popup confirm

BAL_MINUS_X, BAL_PLUS_X = 202, 511
BAL_ROW_Y = {"inf": 530, "lan": 675, "mrk": 820}
DET_TAP_DELAY = 0.09
FLOOR_TAPS = 105

ENEMY_PCT_X = {"inf": (385, 490), "lan": (495, 600), "mrk": (605, 715)}

DEFAULT_MAX_ATTEMPTS_PER_STAGE = 5  # "max attempts... if it makes sense"


def _raw_tap(x, y):
    from labyrinth_common import adb
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(DET_TAP_DELAY)


def set_ratio_deterministic(inf_pct, lan_pct, mrk_pct):
    """Open Balance popup, zero all rows, fill each to target, confirm.
    Validated live 2026-08-12: landed 26/20/53 exact on the first automated pass."""
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


def set_ratio_equalize():
    """One-tap 33/33/33 -- validated live 2026-08-12, exact 50k/50k/50k every time."""
    tap(*EQUALIZE_BTN)


def scout_current_stage(zone_name, stage_index, log=print):
    """Tap the magnifying glass, capture the pre-fight stat-bonus comparison (our
    Atk/Def/Leth/Health % vs the opponent's), and back out. Doesn't drive any decision
    by itself (no comp info available pre-fight) but matt wants this checked every
    stage as a sanity read, and it's logged for the record."""
    tap(*SCOUT_MAGNIFIER)
    img = wait_for_screen(lambda i: "stats" in ocr_text(i, (0, 460, 400, 500), psm=6).lower(),
                           label="scout", timeout=4.0)
    if img is not None:
        screenshot(f"{zone_name}_scout_s{stage_index}")
        log(f"[{zone_name}] scouted stage before challenging (stat-bonus comparison "
            f"captured; enemy comp not visible pre-fight -- only on a loss report).")
    tap(*BACK_ARROW)
    time.sleep(1.0)


def read_enemy_comp_from_loss_report(log=print):
    """Assumes we're on the Defeat screen. Opens the report, opens the top (current
    stage) Defeated card, scrolls to the comp row, OCRs it, and backs out to the stage
    screen. Returns {'inf': int|None, 'lan': int|None, 'mrk': int|None} on success, or
    None if any screen transition fails to verify (caller must treat None as a hard
    stop, not a guess -- a blind version of this once silently misnavigated and a blind
    caller then exited all the way to the City screen).

    Extra settle before the first tap: confirmed live 2026-08-12 that the Defeat
    screen's banner color (what detect_result checks) can render before the buttons
    below it finish fading in -- a tap that lands "too early" silently misses the real
    button. 0.8s extra buffer here fixed it."""
    time.sleep(0.8)
    tap(*VIEW_BATTLE_REPORT_BTN)
    img = wait_for_screen(is_battle_report_list_screen, label="report_list")
    if img is None:
        log("[read_enemy_comp] never reached the Battle Report list -- aborting.")
        return None

    tap(360, 335)  # top (most recent / current stage) Defeated card
    img = wait_for_screen(is_battle_details_screen, label="report_details")
    if img is None:
        log("[read_enemy_comp] never reached Battle Details -- aborting.")
        return None

    swipe(360, 900, 360, 400, 400)
    time.sleep(1.0)
    img = screenshot("loss_report_detail")
    enemy = {}
    for key, (x0, x1) in ENEMY_PCT_X.items():
        val, _y = find_pct_row(img, x0, x1)
        enemy[key] = val

    tap(*BACK_ARROW)
    img = wait_for_screen(is_battle_report_list_screen, label="back_to_report_list")
    if img is None:
        log("[read_enemy_comp] back-out #1 didn't land on the report list -- stopping "
            "rather than blindly backing out again.")
        return None
    tap(*BACK_ARROW)
    img = wait_for_screen(is_stage_screen, label="back_to_stage")
    if img is None:
        log("[read_enemy_comp] back-out #2 didn't land on the stage screen -- "
            "stopping.")
        return None
    return enemy


def _challenge_and_deploy(zone_name, ratio_fn, label, log=print):
    """Assumes we're on the stage screen with Challenge visible. Taps Challenge, waits
    for the troop-adjust screen, applies ratio_fn() if given (None = leave whatever the
    game defaulted to), taps Deploy, and returns the settled result: 'victory' |
    'defeat' | 'unknown' | one of the 'timeout:*' strings below (caller must treat any
    timeout as a hard stop, never guess past it)."""
    tap(*CHALLENGE_BTN)
    img = wait_for_screen(is_troop_adjust_screen, label=f"troop_adjust_{label}")
    if img is None:
        return "timeout:troop_adjust_screen"

    if ratio_fn is not None:
        ratio_fn()

    tap(*DEPLOY_BTN)
    img = wait_for_screen(is_result_screen, label=f"{zone_name}_result_{label}")
    if img is None:
        return "timeout:result_screen"

    result = detect_result(img)
    if result == "confirmation_dialog":
        dismiss_confirmation_dialog()
        img = wait_for_screen(is_result_screen, label=f"{zone_name}_result_{label}_post_confirm")
        if img is None:
            return "timeout:result_screen_post_confirm"
        result = detect_result(img)
    return result


def _is_uniform_comp(comp, tolerance=3):
    """True if enemy comp is ~evenly split (e.g. 33/33/33) -- rock-paper-scissors has
    no leverage against a uniform comp, so rotating it just gives the same ratio back.
    That's a clean 'no new info, stop' signal even before comparing to the last try."""
    vals = list(comp.values())
    return max(vals) - min(vals) <= tolerance


def climb(zone_name, max_stages=50, max_attempts_per_stage=DEFAULT_MAX_ATTEMPTS_PER_STAGE,
          scout=True, log=print):
    """Climbs stages starting from whatever stage is currently loaded on the stage
    screen (caller must already have navigated into the zone, and the stage screen --
    with a visible Challenge button -- must be showing right now).

    Per-stage retry policy (Observed live ): keep adjusting and retrying up to
    max_attempts_per_stage IF each retry has genuinely new comp information to act on.
    Stop early -- before the attempt cap -- the moment the diagnosis shows it doesn't
    (enemy comp is uniform, or the freshly-computed counter matches what we already
    just lost with). Every stop is written to zone_history.py.

    Returns a result dict: {zone, stages_cleared, stopped_reason, final_ratio}
    """
    stages_cleared = 0
    last_ratio = None

    img = wait_for_screen(is_stage_screen, label="start", timeout=4.0)
    if img is None:
        log(f"[{zone_name}] not on a stage screen at start -- aborting rather than "
            f"guessing where we are.")
        return {"zone": zone_name, "stages_cleared": 0,
                "stopped_reason": "not_on_stage_screen", "final_ratio": None}

    while stages_cleared < max_stages:
        stage_label = get_current_stage_label(img)
        known_wall_retry = False
        ratio_fn = set_ratio_equalize  # safe neutral default -- nothing to counter yet

        if stage_label and zone_history.is_known_power_wall(zone_name, stage_label):
            rec = zone_history.get_stage_record(zone_name, stage_label)
            log(f"[{zone_name}] stage {stage_label} is a known power wall from a prior "
                f"day (recorded {rec.get('last_checked')}, {rec.get('losses_recorded')} "
                f"loss(es) on file, enemy comp was {rec.get('enemy_comp')}) -- giving it "
                f"ONE retry with the last-known-best ratio (24h may mean stronger "
                f"pets/gear) before moving on to the next zone if it loses again.")
            known_wall_retry = True
            best = rec.get("best_ratio_tried")
            if best:
                last_ratio = (best["inf"], best["lan"], best["mrk"])
                ratio_fn = lambda r=last_ratio: set_ratio_deterministic(*r)

        if scout:
            scout_current_stage(zone_name, stages_cleared, log=log)
            img = wait_for_screen(is_stage_screen, label="post_scout", timeout=4.0)
            if img is None:
                log(f"[{zone_name}] lost the stage screen after scouting -- stopping.")
                return {"zone": zone_name, "stages_cleared": stages_cleared,
                        "stopped_reason": "lost_screen_after_scout",
                        "final_ratio": last_ratio}

        result = _challenge_and_deploy(zone_name, ratio_fn, f"s{stages_cleared}_a0", log)
        if result.startswith("timeout:"):
            log(f"[{zone_name}] {result} -- stopping rather than guessing (this class "
                f"of bug once made an earlier run 'quit out' to the City screen).")
            return {"zone": zone_name, "stages_cleared": stages_cleared,
                    "stopped_reason": result, "final_ratio": last_ratio}

        attempt = 1  # this stage's fight count so far (the one just fought)

        # --- retry sub-loop: only entered on defeat, exited on victory or a stop ---
        while result == "defeat":
            log(f"[{zone_name}] LOSS #{attempt} on stage {stage_label}")

            if known_wall_retry:
                log(f"[{zone_name}] {stage_label} lost again on the known-wall retry "
                    f"-- confirms it's still a wall. Not spending another attempt "
                    f"here today; moving on to the next zone.")
                zone_history.record_stage_result(
                    zone_name, stage_label, "power_wall",
                    datetime.now().strftime("%Y-%m-%d"),
                    best_ratio_tried=(dict(zip(("inf", "lan", "mrk"), last_ratio))
                                      if last_ratio else None),
                    note="Re-confirmed on a later day's retry -- still a wall.")
                return {"zone": zone_name, "stages_cleared": stages_cleared,
                        "stopped_reason": "known_power_wall_reconfirmed",
                        "final_ratio": last_ratio}

            enemy = read_enemy_comp_from_loss_report(log=log)
            if enemy is None or None in enemy.values():
                log(f"[{zone_name}] could not OCR enemy comp -- stopping rather than "
                    f"guessing.")
                return {"zone": zone_name, "stages_cleared": stages_cleared,
                        "stopped_reason": "ocr_failure", "final_ratio": last_ratio}

            new_ratio = rotate_counter(enemy["inf"], enemy["lan"], enemy["mrk"])
            no_new_info = (new_ratio == last_ratio) or _is_uniform_comp(enemy)
            at_cap = attempt >= max_attempts_per_stage

            if no_new_info or at_cap:
                reason = ("enemy comp is uniform -- no RPS leverage left"
                          if _is_uniform_comp(enemy) else
                          "the computed counter is identical to the ratio we already "
                          "lost with -- nothing new to try"
                          if new_ratio == last_ratio else
                          f"hit the {max_attempts_per_stage}-attempt cap for this stage")
                log(f"[{zone_name}] STOP on {stage_label} after {attempt} loss(es): "
                    f"{reason}. Power wall, not comp-fixable. Halting.")
                zone_history.record_stage_result(
                    zone_name, stage_label, "power_wall",
                    datetime.now().strftime("%Y-%m-%d"),
                    enemy_comp=enemy,
                    best_ratio_tried=dict(zip(("inf", "lan", "mrk"), new_ratio)),
                    note=f"{reason}. {attempt} loss(es) this run.")
                return {"zone": zone_name, "stages_cleared": stages_cleared,
                        "stopped_reason": "power_wall", "final_ratio": last_ratio}

            log(f"[{zone_name}] enemy comp {enemy} -> new counter {new_ratio} -- "
                f"genuinely different from last try ({last_ratio}), worth another "
                f"attempt ({attempt}/{max_attempts_per_stage}).")
            img = wait_for_screen(is_stage_screen, label="post_loss_report")
            if img is None:
                log(f"[{zone_name}] not back on the stage screen after reading the "
                    f"loss report -- stopping.")
                return {"zone": zone_name, "stages_cleared": stages_cleared,
                        "stopped_reason": "lost_screen_after_loss_report",
                        "final_ratio": last_ratio}

            last_ratio = new_ratio
            attempt += 1
            result = _challenge_and_deploy(
                zone_name, lambda r=new_ratio: set_ratio_deterministic(*r),
                f"s{stages_cleared}_a{attempt}", log)
            if result.startswith("timeout:"):
                log(f"[{zone_name}] {result} on retry -- stopping.")
                return {"zone": zone_name, "stages_cleared": stages_cleared,
                        "stopped_reason": result, "final_ratio": last_ratio}
        # --- end retry sub-loop ---

        if result == "victory":
            stages_cleared += 1
            log(f"[{zone_name}] stage cleared (#{stages_cleared}) -- victory")
            if known_wall_retry:
                log(f"[{zone_name}] {stage_label} was a known power wall and just "
                    f"CLEARED on retry -- pets/gear must have improved.")
                zone_history.record_stage_result(
                    zone_name, stage_label, "cleared",
                    datetime.now().strftime("%Y-%m-%d"),
                    note="Cleared on a retry after being a recorded power wall.")
            last_ratio = None  # fresh stage next loop -> Equalize baseline again
            tap(*CLOSE_VICTORY_TAP)
            img = wait_for_screen(is_stage_screen, label="post_victory")
            if img is None:
                log(f"[{zone_name}] didn't land back on a stage screen after victory -- "
                    f"stopping rather than guessing.")
                return {"zone": zone_name, "stages_cleared": stages_cleared,
                        "stopped_reason": "post_victory_screen_timeout",
                        "final_ratio": last_ratio}
            continue

        log(f"[{zone_name}] could not detect Victory/Defeat ({result!r}) -- stopping "
            f"rather than guessing.")
        return {"zone": zone_name, "stages_cleared": stages_cleared,
                "stopped_reason": f"unknown_result:{result}", "final_ratio": last_ratio}

    return {"zone": zone_name, "stages_cleared": stages_cleared,
            "stopped_reason": "max_stages_reached", "final_ratio": last_ratio}
