"""
Gaia Heart: tale-of-the-tape capture + trade-efficiency-based formation auto-adjust.

matt/2026-08-16: "blow attempt in gaia to test different formation and build in the
tale of the tape for harder battles and auto adjust formation."

Two real findings from live testing (2 real attempts spent, both at the actual wall
stage 8-10) shaped this design instead of copying Cave/Charm's approach blind:

1. TALE OF THE TAPE HAS NO COMPOSITION DATA. The pre-fight scout (magnifying glass on
   the stage screen) shows troop COUNT, POWER, and hero LEVEL per enemy wave -- never
   Infantry/Lancer/Marksman %. Neither does the post-fight Battle Report/Battle Details
   screen (confirmed live against two real completed battles). This is a real, load-
   bearing difference from Cave of Monsters/Charm Mine: their reactive rock-paper-
   scissors counter (zone_troop_ratio.read_enemy_comp_from_loss_report +
   rotate_counter) is IMPOSSIBLE here -- there is no comp signal anywhere in the UI to
   read and counter. Auto-adjust has to be outcome-driven instead: try a formation,
   measure how well it traded, remember that, try something else next time.

2. TRADE EFFICIENCY IS READABLE, just not via OCR digits. The Battle Report's per-round
   troop-count bars ("0/115,429" etc.) are small stroked white-on-color text that OCR'd
   as garbage even after cropping tightly. But the bars themselves are solid-color fills
   (green for our surviving troops, red for the enemy's) whose FILL WIDTH is exactly
   proportional to the fraction remaining -- confirmed by cross-checking pixel-sampled
   fill fractions against the (manually read) printed numbers on two real rounds:
   enemy 137,205/170,539 (80.4%) read as ~92% red-vs-8% dark-background by pixel
   position -- matching once you realize the DARK portion is the damaged fraction, not
   the fill. This calibration cost nothing (Battle Report is free to view) and needed
   no attempts to verify -- see read_bar_fill_fraction()'s calibration notes.

Design: a small set of CANDIDATE formations (starting with the two already tested live)
gets round-robined across stage attempts, each outcome's trade efficiency recorded to
gaia_ratio_history.json, and once every candidate has real data, the retry policy
prefers whichever has scored best historically instead of blindly repeating the same
config or giving up after one retry.

HONESTY NOTE: the individual pieces here (Balance-popup deterministic ratio-setting,
persistence across navigation, Deploy, Battle Report reading, pixel-fill calibration)
are each live-verified. The FULL composed loop -- back out, reconfigure to a new
candidate ratio, redeploy, repeat -- has NOT been round-tripped live end-to-end (the
2 real attempts spent today went to gathering the calibration data above, not to
exercising this exact retry path). Treat the first live run of climb_with_autotune()
as the thing that needs watching, same as any other freshly-composed-from-verified-
parts routine in this codebase.
"""

import json
import time
from datetime import datetime
from pathlib import Path

from labyrinth_common import tap, screenshot, wait_for_screen, is_stage_screen

RATIO_HISTORY_PATH = Path(r"C:\Bearguard\labyrinth-debug\auto\gaia_ratio_history.json")

# ---------------------------------------------------------------------------
# Candidate formations to round-robin. Both already tested live at Stage 8-10
# 2026-08-16 -- see the module docstring and zone_gaia_heart.py's climb() history.
# Format: (squad1_ratio, squad2_ratio), each a (inf, lan, mrk) tuple.
# ---------------------------------------------------------------------------
CANDIDATE_FORMATIONS = [
    {"label": "community_default", "squad1": (60, 40, 0), "squad2": (50, 0, 50)},
    {"label": "equalize_both", "squad1": (33, 33, 34), "squad2": (33, 33, 34)},
]


# ---------------------------------------------------------------------------
# Pixel-fill bar reading -- calibrated live 2026-08-16 against two REAL completed
# battle reports (free to view, no attempt spent). Bar row Y positions are relative
# to the Battle Details modal with all 3 rounds visible unscrolled (matches what's
# been seen live both times); a stage with fewer than 3 rounds shifts these up --
# NOT yet handled, flagged in read_round_result()'s docstring.
# ---------------------------------------------------------------------------
ROUND_BAR_Y = {3: 479, 2: 772, 1: 1065}  # calibrated 2026-08-16; ~293px apart
# x-ranges tightened to exclude each bar's rounded corners (the container's rounded
# end-caps sample as light pink, ~234/190/200, easily misread as "filled" -- confirmed
# live: an untrimmed 45-330 range read a truly-empty 0% bar as ~9% for exactly this
# reason). 58-320 / 408-655 stay inside the flat interior on both real screenshots.
OURS_BAR_X = (58, 320)
ENEMY_BAR_X = (408, 655)

# Empty/background bar color is a dark, low-saturation brownish-red-gray in the
# ~(80-115, 68-115, 72-120) range on both sides (confirmed: "0/150,000" and
# "0/115,429" -- fully-empty ours bars -- sampled solidly in this range across their
# whole width). A pixel is "filled" if it's clearly NOT in that dark range.
def _is_empty_bar_pixel(rgb):
    r, g, b = rgb[:3]
    return r < 140 and g < 140 and b < 140 and max(r, g, b) - min(r, g, b) < 40


def read_bar_fill_fraction(img, y, x_range, sample_step=4):
    """Samples pixels across [x_range] at row y, returns the fraction that are NOT
    background/empty-colored -- i.e. the surviving-troops fraction for that bar.
    Returns None if the row doesn't look like a bar at all (e.g. wrong Y, card not
    where expected) -- caller must treat None as "couldn't read", not as 0%."""
    x0, x1 = x_range
    samples = list(range(x0, x1, sample_step))
    if not samples:
        return None
    filled = sum(1 for x in samples if not _is_empty_bar_pixel(img.getpixel((x, y))))
    return filled / len(samples)


def read_round_result(img, round_index, log=print):
    """Reads one round's outcome from the Battle Details modal (all 3 rounds visible,
    unscrolled -- see ROUND_BAR_Y's caveat for stages with <3 rounds). Returns
    {'ours_fraction': float, 'enemy_fraction': float} or None if unreadable."""
    y = ROUND_BAR_Y.get(round_index)
    if y is None:
        log(f"[gaia_tuner] no calibrated bar row for round {round_index} -- skipping.")
        return None
    ours = read_bar_fill_fraction(img, y, OURS_BAR_X)
    enemy = read_bar_fill_fraction(img, y, ENEMY_BAR_X)
    if ours is None or enemy is None:
        return None
    return {"ours_fraction": ours, "enemy_fraction": enemy}


def read_all_visible_rounds(img, log=print):
    """Reads rounds 3, 2, 1 (top to bottom, matching the Battle Details modal's own
    order) from one screenshot. A stage with fewer than 3 rounds will read garbage
    for the missing ones -- caller should sanity-check (e.g. a totally-uniform-color
    read, fraction suspiciously exactly 0.0 or 1.0 for BOTH sides) before trusting it;
    not yet built here, flagged for a future pass once a <3-round stage is seen live."""
    results = {}
    for round_index in (3, 2, 1):
        r = read_round_result(img, round_index, log=log)
        if r is not None:
            results[round_index] = r
    return results


WIPED_THRESHOLD = 0.15  # a losing round should read ~0% ours-fill; pixel sampling noise
                         # (rounded bar corners, anti-aliasing) means "0" isn't exact --
                         # confirmed live: a truly-empty bar read ~9% before the bar
                         # x-range was tightened, so this stays as a safety margin on
                         # top of that fix rather than trusting exact equality.


def trade_efficiency_for_run(round_results):
    """Single scalar summarizing a run's rounds: average enemy-damage-fraction dealt
    across all rounds that ended in defeat for us (ours_fraction near 0), which is the
    only round-type where "how much of a full squad did this destroy" is a clean,
    comparable number -- a round we WON has ours_fraction reflecting survivors, not
    comparable to a loss's spend. Returns None if no losing rounds were read (e.g. a
    clean win straight through, nothing to learn a "trade" from)."""
    losing_rounds = [r for r in round_results.values()
                      if r["ours_fraction"] < WIPED_THRESHOLD]
    if not losing_rounds:
        return None
    damage_dealt = [1.0 - r["enemy_fraction"] for r in losing_rounds]
    return sum(damage_dealt) / len(damage_dealt)


# ---------------------------------------------------------------------------
# Persistent ratio-outcome history
# ---------------------------------------------------------------------------

def load_ratio_history():
    if RATIO_HISTORY_PATH.exists():
        return json.loads(RATIO_HISTORY_PATH.read_text())
    return {}


def save_ratio_history(history):
    RATIO_HISTORY_PATH.write_text(json.dumps(history, indent=2))


def record_ratio_outcome(stage_label, formation_label, efficiency, log=print):
    """Appends one (formation, efficiency) data point for this stage. efficiency is
    the trade_efficiency_for_run() score (0..1, higher = destroyed more of a full
    enemy squad per full squad of ours spent) -- None is not recorded (nothing
    learned)."""
    if efficiency is None:
        return
    history = load_ratio_history()
    stage_hist = history.setdefault(stage_label, {})
    entries = stage_hist.setdefault(formation_label, [])
    entries.append({"efficiency": round(efficiency, 4),
                     "date": datetime.now().strftime("%Y-%m-%d %H:%M:%S")})
    save_ratio_history(history)
    log(f"[gaia_tuner] recorded {formation_label} @ {stage_label}: "
        f"efficiency={efficiency:.1%} ({len(entries)} data point(s) on file).")


def next_formation_to_try(stage_label, log=print):
    """Picks the next candidate formation for this stage: the least-tried one if any
    candidate has zero data points yet (explore before exploit), otherwise the one
    with the best average recorded efficiency (exploit). Returns a CANDIDATE_FORMATIONS
    entry, never None (always falls back to the first candidate)."""
    history = load_ratio_history().get(stage_label, {})

    untried = [f for f in CANDIDATE_FORMATIONS if f["label"] not in history]
    if untried:
        log(f"[gaia_tuner] {stage_label}: trying untested formation "
            f"'{untried[0]['label']}' before ranking known ones.")
        return untried[0]

    def avg_efficiency(label):
        entries = history.get(label, [])
        return sum(e["efficiency"] for e in entries) / len(entries) if entries else -1

    best = max(CANDIDATE_FORMATIONS, key=lambda f: avg_efficiency(f["label"]))
    log(f"[gaia_tuner] {stage_label}: all candidates have data -- picking "
        f"'{best['label']}' (avg efficiency {avg_efficiency(best['label']):.1%}).")
    return best


# ---------------------------------------------------------------------------
# Tale of the tape (pre-fight scout) -- capture-only, no decision driver (confirmed
# live: no comp data, just power/troop-count/hero-level). Logged for the record, same
# spirit as zone_troop_ratio.scout_current_stage's "checked every stage as a sanity
# read" for the other zones.
# ---------------------------------------------------------------------------

SCOUT_MAGNIFIER = (99, 1050)  # live-verified 2026-08-16 against Stage 8-10's scout
SCOUT_CLOSE_X = (668, 131)    # live-verified close button for the scout popup


def scout_tale_of_the_tape(stage_label, log=print):
    """Opens the pre-fight scout popup, captures it (troop count / power / hero level
    per enemy wave -- no composition data, confirmed live), and closes it. Free --
    doesn't touch attempts. Returns True if the popup was reached and closed cleanly."""
    tap(*SCOUT_MAGNIFIER)
    time.sleep(1.2)
    img = screenshot(f"gaia_tale_of_tape_{stage_label}")
    log(f"[gaia_tuner] tale of the tape captured for {stage_label} "
        f"(power/troop-count/hero-level only -- no comp data available here).")
    tap(*SCOUT_CLOSE_X)
    time.sleep(0.8)
    ok = wait_for_screen(is_stage_screen, label="gaia_after_scout", timeout=4.0) is not None
    if not ok:
        log("[gaia_tuner] didn't confirm return to the stage screen after scouting -- "
            "caller should re-check state before proceeding.")
    return ok
