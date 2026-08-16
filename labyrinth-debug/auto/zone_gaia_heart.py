"""
Gaia Heart climb loop.

Live-calibrated 2026-08-16 (a Sunday -- Gaia Heart's actual open rotation, so this was
built against the real zone, not guessed). Genuinely different UI/mechanic from every
other zone in this folder:

- Entry screen is "Squad Config" (2 squads, later a 3rd unlocked at stage 15-10), same
  shape as Land of Heroes -- Quick Deploy / Edit Formation / Deploy. BUT unlike Land of
  Heroes (troops normalized to Lv.10 "Labyrinth Explorers") and unlike Cave of
  Monsters/Charm Mine (troops normalized to Apex), Gaia Heart deploys your REAL troops
  at their real tier/count and your REAL heroes at their real level -- matches the
  in-game description ("you can unleash your full power here... without worrying about
  casualties").
- Quick Deploy (197, 1193) auto-fills both squads with real heroes + max troops in one
  tap -- confirmed live, went from 0/0 to 150,000/150,000 with real Lv.65-67 heroes
  instantly. Idempotent -- safe to tap every run even if squads are already configured.
- Edit Formation -> Balance popup shares the exact same coordinates as the troop-ratio
  zones (BAL_MINUS_X/PLUS_X, CONFIRM_BTN all matched live) -- same shared UI component.
  Row Y positions read slightly different in the popup this time (~530/655/800 vs the
  other zones' 530/675/820) -- close enough that the existing tap counts probably still
  land in each row, but NOT deterministically confirmed here (we backed out of the
  popup without confirming a change, since Quick Deploy's default comp already won
  cleanly through 5+ stages). Treat set_ratio_deterministic() for this zone as
  UNVERIFIED until a live run actually confirms a changed ratio took effect.
- THE BIG DIFFERENCE: one Deploy does NOT mean one stage. "Auto-Challenge Next Stage"
  is checked by default on the Victory screen, and the game auto-advances through
  stage after stage on its own (confirmed live: one Deploy chained 7-4 -> 7-5 -> 7-6 ->
  7-7 -> ... -> 8-9 unattended, each Victory auto-closing after "Next Stage (1s)").
  "Remaining attempts today" only decremented ONCE for that whole chain. An attempt is
  spent per Challenge/Retry tap, not per stage. This script's job while a chain is
  running is just to WAIT and poll for the eventual Defeat -- never tap anything mid-
  chain, a stray tap here could misfire into the live battle screen.
  - Victory screen anchor: "next stage" or "auto-challenge" in the button-row text.
  - Defeat screen anchor: "remaining attempts" in the same region (Retry replaces
    Next Stage, no Auto-Challenge checkbox).
- Retry (522, 1002) on the Defeat screen re-fights ONLY the stage that lost, with the
  same squad already saved -- confirmed live (lost on 8-9, tapped Retry, landed right
  back on 8-9, not a restart from 7-4). Costs one more attempt.
- Back arrow (40, 40) from Defeat returns to the zone's stage-select screen (validated
  live). From there, the normal BACK_ARROW pattern returns to The Labyrinth map.

Retry/power-wall policy (matches the spirit of zone_troop_ratio's, simplified): no
reactive enemy-comp counter here yet (Gaia Heart's post-loss report layout hasn't been
mapped) -- so a loss gets exactly ONE same-config retry (RNG/timing can differ even
with an identical squad). Losing twice on the same stage = power wall, logged to
zone_history same as every other zone, and the run stops rather than burning through
the small daily attempt pool blindly. A future pass can add the reactive counter once
Gaia Heart's Battle Report shape is mapped (see read_enemy_comp_from_loss_report in
zone_troop_ratio.py for the pattern to port).
"""

import re
import time
from datetime import datetime

from labyrinth_common import (
    tap, screenshot, ocr_text, wait_for_screen, is_stage_screen,
    get_current_stage_label, BACK_ARROW,
)
import zone_history

# ---------------------------------------------------------------------------
# UI coordinates -- calibrated live 2026-08-16 against the real Gaia Heart zone.
# ---------------------------------------------------------------------------

CHALLENGE_BTN = (360, 1215)          # on the stage-select screen
QUICK_DEPLOY_BTN = (197, 1193)       # Squad Config -- fills both squads w/ real troops
DEPLOY_BTN = (522, 1195)             # Squad Config -- starts the chain
RETRY_BTN = (522, 1002)              # on the Defeat screen

TITLE_ANCHOR_BOX = (30, 20, 400, 60)
RESULT_BUTTON_ROW_BOX = (0, 850, 720, 1010)

MAX_ATTEMPTS_SAME_STAGE = 2          # 1 loss -> 1 retry -> power wall if it loses again
CHAIN_POLL_INTERVAL = 4.0
CHAIN_POLL_TIMEOUT = 20 * 60.0       # a long chain (many stages) can genuinely take a
                                      # while unattended -- 20 min ceiling before this
                                      # gives up and reports rather than polling forever


def is_squad_config_screen(img):
    return "squad" in ocr_text(img, TITLE_ANCHOR_BOX, psm=7).lower()


def is_gaia_victory_screen(img):
    text = ocr_text(img, RESULT_BUTTON_ROW_BOX, psm=6).lower()
    return "next stage" in text or "auto-challenge" in text or "auto challenge" in text


def is_gaia_defeat_screen(img):
    text = ocr_text(img, RESULT_BUTTON_ROW_BOX, psm=6).lower()
    return "remaining attempts" in text


ATTEMPTS_LEFT_BOX = (150, 915, 570, 950)


def read_attempts_remaining(img):
    text = ocr_text(img, ATTEMPTS_LEFT_BOX, psm=7)
    m = re.search(r"(\d+)\s*$", text.strip())
    return int(m.group(1)) if m else None


def enter_squad_config(log=print):
    """Assumes the stage-select screen is showing (Challenge visible). Taps Challenge
    and waits for Squad Config. Returns True on success."""
    tap(*CHALLENGE_BTN)
    img = wait_for_screen(is_squad_config_screen, label="gaia_squad_config")
    if img is None:
        log("[gaia_heart] never reached Squad Config after Challenge -- aborting.")
        return False
    return True


def wait_for_chain_end(log=print):
    """Polls (WITHOUT tapping anything) until the current chain resolves into a
    Defeat screen -- Victory screens auto-advance on their own via "Auto-Challenge
    Next Stage", so the only thing worth doing mid-chain is watching. Returns
    ('defeat', img) or ('timeout', None) -- never guesses past a timeout."""
    elapsed = 0.0
    saw_victory_at_least_once = False
    while elapsed < CHAIN_POLL_TIMEOUT:
        img = screenshot("gaia_chain_poll")
        if is_gaia_defeat_screen(img):
            return "defeat", img
        if is_gaia_victory_screen(img):
            saw_victory_at_least_once = True
            # game auto-advances on its own -- just keep polling, never tap here.
        time.sleep(CHAIN_POLL_INTERVAL)
        elapsed += CHAIN_POLL_INTERVAL

    log(f"[gaia_heart] chain poll timed out after {CHAIN_POLL_TIMEOUT/60:.0f} min "
        f"(saw_victory_at_least_once={saw_victory_at_least_once}) -- stopping rather "
        f"than guessing what screen we're on.")
    return "timeout", None


def deploy_and_wait_for_chain_end(log=print):
    """Taps Quick Deploy (idempotent -- fills both squads with real troops/heroes) then
    Deploy, then waits for the chain to end. Returns ('defeat'|'timeout', img|None)."""
    tap(*QUICK_DEPLOY_BTN)
    time.sleep(0.8)
    tap(*DEPLOY_BTN)
    return wait_for_chain_end(log=log)


def climb(max_attempts_today=None, log=print):
    """Runs Gaia Heart from whatever stage is currently loaded on the stage-select
    screen (caller must already have navigated into the zone). Spends real daily
    attempts -- each Deploy or Retry is one attempt, win-chains inside a single attempt
    don't cost extra. Returns a result dict: {zone, stopped_reason, final_stage,
    attempts_used}.
    """
    zone_name = "gaia_heart"
    attempts_used = 0

    img = wait_for_screen(is_stage_screen, label="gaia_start", timeout=4.0)
    if img is None:
        log("[gaia_heart] not on a stage screen at start -- aborting rather than "
            "guessing where we are.")
        return {"zone": zone_name, "stopped_reason": "not_on_stage_screen",
                "final_stage": None, "attempts_used": 0}

    stage_label = get_current_stage_label(img)
    log(f"[gaia_heart] current wall stage: {stage_label}")

    if stage_label and zone_history.is_known_power_wall(zone_name, stage_label):
        rec = zone_history.get_stage_record(zone_name, stage_label)
        log(f"[gaia_heart] {stage_label} is a known power wall (recorded "
            f"{rec.get('last_checked')}) -- giving it one retry-of-the-day rather than "
            f"assuming it's still a wall (pets/gear may have improved).")

    # First attempt: Squad Config -> Quick Deploy -> Deploy -> wait for the chain
    # (which may clear several stages unattended) to end in a Defeat.
    if not enter_squad_config(log=log):
        return {"zone": zone_name, "stopped_reason": "failed_to_reach_squad_config",
                "final_stage": stage_label, "attempts_used": attempts_used}

    result, img = deploy_and_wait_for_chain_end(log=log)
    attempts_used += 1
    same_stage_attempts = 1

    # Subsequent attempts (if any): Retry drops straight back into a live chain --
    # confirmed live 2026-08-16 (lost on 8-9, tapped Retry, the next frame showed the
    # 8-9 battle screen directly, NOT Squad Config or stage-select). So a retry is just
    # "tap Retry, wait for the chain to end" -- no re-deploy step.
    while True:
        if result == "timeout":
            return {"zone": zone_name, "stopped_reason": "chain_poll_timeout",
                    "final_stage": stage_label, "attempts_used": attempts_used}

        # result == "defeat" -- read where we landed and how many attempts remain
        attempts_left = read_attempts_remaining(img) if img is not None else None
        log(f"[gaia_heart] chain ended in defeat (attempt {same_stage_attempts} on "
            f"this stage). Attempts remaining today: {attempts_left}.")

        if same_stage_attempts >= MAX_ATTEMPTS_SAME_STAGE:
            log(f"[gaia_heart] lost {same_stage_attempts} time(s) on the same stage "
                f"with no comp-counter lever available yet -- power wall. Stopping "
                f"rather than burning the rest of today's attempts.")
            zone_history.record_stage_result(
                zone_name, stage_label or "unknown", "power_wall",
                datetime.now().strftime("%Y-%m-%d"),
                note=f"{same_stage_attempts} loss(es) this run, same default squad "
                     f"comp each time (no reactive counter built yet).")
            break

        if attempts_left is not None and attempts_left <= 0:
            log("[gaia_heart] out of attempts for today -- stopping.")
            break

        log(f"[gaia_heart] retrying the same stage ({same_stage_attempts}/"
            f"{MAX_ATTEMPTS_SAME_STAGE}) -- same squad, no adjustment available yet.")
        tap(*RETRY_BTN)
        result, img = wait_for_chain_end(log=log)
        attempts_used += 1
        same_stage_attempts += 1

    final_img = wait_for_screen(is_stage_screen, label="gaia_final_stage_check",
                                 timeout=6.0)
    final_stage = get_current_stage_label(final_img) if final_img is not None else None

    return {"zone": zone_name, "stopped_reason": "power_wall_or_out_of_attempts",
            "final_stage": final_stage or stage_label, "attempts_used": attempts_used}


def return_to_stage_select(log=print):
    """From the Defeat screen (View Battle Report / Retry visible), back arrow returns
    to the zone's stage-select screen -- validated live 2026-08-16."""
    tap(*BACK_ARROW)
    img = wait_for_screen(is_stage_screen, label="gaia_return_to_stage_select")
    return img is not None
