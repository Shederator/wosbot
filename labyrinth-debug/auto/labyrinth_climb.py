"""
Labyrinth stage-climber for Cave of Monsters / Charm Mine (and similar troop-ratio zones).

Design, corrected 2026-08-12 from live testing:
- The pre-fight "View Details" (magnifying glass) screen shows OUR troop stats vs the
  opponent's per-type Attack/Defense/Lethality/Health BONUS %, but NOT the opponent's
  Infantry/Lancer/Marksman composition breakdown.
- The enemy's actual comp % IS visible, but only on the POST-fight Battle Report -> Battle
  Details screen for a completed battle (win or loss).
- So the counter-ratio loop is necessarily REACTIVE: fight with the current ratio, and only
  after a LOSS do we have enough information (enemy's real comp) to compute a counter and
  retry. This matches what worked live for Cave of Monsters stage 3-6.

Counter formula (pure rock-paper-scissors rotation):
    Infantry beats Lancer, Lancer beats Marksman, Marksman beats Infantry.
    our_infantry_%  = enemy_lancer_%      (Infantry counters Lancer)
    our_lancer_%    = enemy_marksman_%    (Lancer counters Marksman)
    our_marksman_%  = enemy_infantry_%    (Marksman counters Infantry)

Stop condition (Observed live ): if the SAME stage is lost twice in a row -- even after
reading the post-loss report and applying the computed counter -- stop. That's a genuine
power wall (pet/chief-charm level), not a comp-fixable loss.

Requires: pytesseract (pip installed) + tesseract.exe (portable, extracted to
C:\\Bearguard\\tools\\tesseract-win -- no admin/installer needed).
"""

import subprocess
import time
import re
from pathlib import Path

import pytesseract
from PIL import Image

# ---------------------------------------------------------------------------
# Environment
# ---------------------------------------------------------------------------
ADB = r"C:\Bearguard\tools\adb\adb.exe"
DEVICE = "127.0.0.1:16384"
pytesseract.pytesseract.tesseract_cmd = r"C:\Bearguard\tools\tesseract-win\tesseract.exe"

SCRATCH = Path(r"C:\Bearguard\labyrinth-debug\auto\_frames")
SCRATCH.mkdir(parents=True, exist_ok=True)

TAP_WAIT = 1.2          # seconds after a tap before the UI is expected to settle
POST_ACTION_SETTLE = 2.5  # longer settle after Deploy/Challenge (battle animation)

# ---------------------------------------------------------------------------
# ADB helpers
# ---------------------------------------------------------------------------

def adb(*args):
    return subprocess.run([ADB, "-s", DEVICE, *args], capture_output=True, text=True)


def tap(x, y):
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(TAP_WAIT)


def swipe(x1, y1, x2, y2, ms=400):
    adb("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))
    time.sleep(TAP_WAIT)


def screenshot(label="frame"):
    remote = "/sdcard/screen.png"
    adb("shell", "screencap", "-p", remote)
    local = SCRATCH / f"{label}.png"
    adb("pull", remote, str(local))
    return Image.open(local)


# ---------------------------------------------------------------------------
# OCR helpers
# ---------------------------------------------------------------------------

def ocr_digits(img, box, whitelist="0123456789"):
    """box = (left, top, right, bottom). Returns the OCR'd string (digits only)."""
    crop = img.crop(box)
    # upscale small crops -- tesseract reads small UI digits far more reliably at 3x-4x
    crop = crop.resize((crop.width * 4, crop.height * 4), Image.LANCZOS)
    config = f'--psm 7 -c tessedit_char_whitelist={whitelist}'
    text = pytesseract.image_to_string(crop, config=config)
    return re.sub(r"[^0-9]", "", text)


def ocr_text(img, box):
    crop = img.crop(box)
    crop = crop.resize((crop.width * 3, crop.height * 3), Image.LANCZOS)
    return pytesseract.image_to_string(crop, config="--psm 7").strip()


# ---------------------------------------------------------------------------
# Result detection -- color-based, no OCR needed (fast + reliable)
# Victory banner center is warm gold/orange; Defeat banner center is cool blue-gray.
# ---------------------------------------------------------------------------

RESULT_BANNER_SAMPLE_PT = (360, 330)  # inside the banner text area on both screens


def detect_result(img):
    r, g, b = img.convert("RGB").getpixel(RESULT_BANNER_SAMPLE_PT)
    # Victory banner: warm gold/tan (R clearly above B). Defeat banner: cool blue-gray
    # (B clearly above R). Calibrated live 2026-08-12 against 4 real result screenshots
    # (varying background blur/brightness) -- thresholds are relative, not absolute,
    # since brightness varies but the R-vs-B relationship is consistent.
    if r > b + 20:
        return "victory"
    if b > r:
        return "defeat"
    return "unknown"


# ---------------------------------------------------------------------------
# Counter-ratio math
# ---------------------------------------------------------------------------

def rotate_counter(enemy_inf, enemy_lan, enemy_mrk):
    """Infantry beats Lancer, Lancer beats Marksman, Marksman beats Infantry."""
    return enemy_lan, enemy_mrk, enemy_inf


# ---------------------------------------------------------------------------
# UI coordinates (720x1280, validated live 2026-08-12 for Cave of Monsters / Charm Mine)
# ---------------------------------------------------------------------------

SCOUT_MAGNIFIER = (133, 1092)      # on the stage screen, before Challenge
CHALLENGE_BTN = (360, 1217)
BACK_ARROW = (40, 40)
EQUALIZE_BTN = (199, 1185)
BALANCE_BTN = (330, 1195)
DEPLOY_BTN = (549, 1213)
CONFIRM_BTN = (360, 978)           # Balance popup confirm
NEXT_BTN = (522, 864)              # on Victory screen -- NOT used by this script;
                                    # we deliberately go back to the stage map + rescout
                                    # instead of auto-chaining blind (matt's correction).
VIEW_BATTLE_REPORT_BTN = (200, 1000)  # on Defeat screen

# Balance popup slider rows
BAL_MINUS_X, BAL_PLUS_X = 202, 511
BAL_ROW_Y = {"inf": 530, "lan": 675, "mrk": 820}
BAL_PCT_BOX = {
    "inf": (558, 508, 632, 552),
    "lan": (558, 653, 632, 697),
    "mrk": (558, 798, 632, 842),
}
DET_TAP_DELAY = 0.09
FLOOR_TAPS = 105


def set_ratio_deterministic(inf_pct, lan_pct, mrk_pct):
    """Open Balance popup, zero all rows, fill each to target, confirm."""
    tap(*BALANCE_BTN)
    # zero all rows first (order-independent, avoids the 100%-cap block)
    for row in BAL_ROW_Y.values():
        for _ in range(FLOOR_TAPS):
            adb("shell", "input", "tap", str(BAL_MINUS_X), str(row))
            time.sleep(DET_TAP_DELAY)
    targets = {"inf": inf_pct, "lan": lan_pct, "mrk": mrk_pct}
    for key, pct in targets.items():
        row = BAL_ROW_Y[key]
        for _ in range(pct):
            adb("shell", "input", "tap", str(BAL_PLUS_X), str(row))
            time.sleep(DET_TAP_DELAY)
    time.sleep(0.6)
    tap(*CONFIRM_BTN)


def set_ratio_equalize():
    tap(*EQUALIZE_BTN)


# ---------------------------------------------------------------------------
# Post-loss report reading -- Battle Report -> tap the top (most recent, current stage)
# Defeated card -> scroll down to the Troops Details / % comp row.
# ---------------------------------------------------------------------------

# The icon+% row sits right under the "Stat Bonuses"-adjacent slider on the Battle
# Details screen, but its exact Y position DRIFTS by 50px+ between captures because it
# depends on exactly where a manual swipe() gesture happens to land -- confirmed live
# 2026-08-12 comparing two real screenshots (y~288 vs y~238 for the same UI element).
# So instead of a fixed box we scan a generous vertical band and take the first row
# that OCRs as a clean "NN.NN%" pattern. The X ranges (which column) are stable.
PCT_SCAN_Y_RANGE = (140, 420)
PCT_SCAN_Y_STEP = 8
PCT_ROW_HEIGHT = 28
ENEMY_PCT_X = {
    "inf": (385, 490),
    "lan": (495, 600),
    "mrk": (605, 715),
}
OUR_PCT_X = {
    "inf": (0, 135),
    "lan": (120, 250),
    "mrk": (230, 365),
}
PCT_PATTERN = re.compile(r"(\d{1,3})\.\d{2}\s*%")


def _parse_pct(text):
    """'33.33%' -> 33 (we only need whole-number precision for tap counts)."""
    m = PCT_PATTERN.search(text)
    return int(m.group(1)) if m else None


def find_pct_row(img, x0, x1):
    """Scan a vertical band at column [x0,x1] for the first row that OCRs as a clean
    NN.NN% -- returns (value, y_used) or (None, None)."""
    for y0 in range(*PCT_SCAN_Y_RANGE, PCT_SCAN_Y_STEP):
        box = (x0, y0, x1, y0 + PCT_ROW_HEIGHT)
        val = _parse_pct(ocr_text(img, box))
        if val is not None:
            return val, y0
    return None, None


def read_enemy_comp_from_loss_report():
    """Assumes we're already on the Defeat screen. Opens the report, scrolls to the
    comp/stat page, OCRs the enemy's Inf/Lan/Mrk %, and returns to the stage screen."""
    tap(*VIEW_BATTLE_REPORT_BTN)
    time.sleep(POST_ACTION_SETTLE)
    img = screenshot("loss_report_list")
    tap(360, 335)  # top (most recent / current stage) Defeated card
    time.sleep(POST_ACTION_SETTLE)
    swipe(360, 900, 360, 400, 400)  # scroll to comp/stat page
    time.sleep(1.0)
    img = screenshot("loss_report_detail")
    enemy = {}
    for key, (x0, x1) in ENEMY_PCT_X.items():
        val, _y = find_pct_row(img, x0, x1)
        enemy[key] = val
    # back out to the stage map: Battle Details -> Battle Report list -> stage screen
    tap(*BACK_ARROW)
    time.sleep(TAP_WAIT)
    tap(*BACK_ARROW)
    time.sleep(TAP_WAIT)
    return enemy


# ---------------------------------------------------------------------------
# Main climb loop
# ---------------------------------------------------------------------------

def climb(max_stages=20, stop_after_consecutive_losses=2):
    """Climbs stages starting from whatever stage is currently loaded on the stage
    screen. Assumes the current troop ratio is already set (equalized by default).
    Stops after `stop_after_consecutive_losses` losses on the SAME stage (power wall),
    or after `max_stages` clears, whichever comes first.
    """
    consecutive_losses_this_stage = 0
    stages_cleared = 0
    last_known_ratio = None  # (inf, lan, mrk) once we've had to correct one

    while stages_cleared < max_stages:
        tap(*CHALLENGE_BTN)
        time.sleep(POST_ACTION_SETTLE)
        # troop-adjust screen -> Deploy directly (ratio already set from prior stage,
        # or defaults to whatever the game remembered / Equalize left it at)
        tap(*DEPLOY_BTN)
        time.sleep(POST_ACTION_SETTLE + 1.5)  # battle animation

        img = screenshot(f"result_{stages_cleared}")
        result = detect_result(img)

        if result == "victory":
            print(f"[climb] stage cleared ({stages_cleared + 1}) -- victory")
            stages_cleared += 1
            consecutive_losses_this_stage = 0
            tap(360, 700)   # "Tap anywhere to close" on Victory screen
            time.sleep(TAP_WAIT)
            # deliberately do NOT tap Next / auto-chain -- go back to the stage map
            # so the next loop iteration re-enters via Challenge on the (now-updated)
            # current stage, keeping the option to rescout open.
            continue

        if result == "defeat":
            consecutive_losses_this_stage += 1
            print(f"[climb] LOSS #{consecutive_losses_this_stage} on this stage")
            if consecutive_losses_this_stage >= stop_after_consecutive_losses:
                print("[climb] STOP: lost twice on this stage even after adjusting -- "
                      "this looks like a genuine power wall (pet/chief-charm level), "
                      "not a comp-fixable loss. Halting for a human decision.")
                return {"stages_cleared": stages_cleared, "stopped_reason": "power_wall"}

            enemy = read_enemy_comp_from_loss_report()
            if None in enemy.values():
                print("[climb] could not OCR enemy comp from the report -- "
                      "stopping rather than guessing.")
                return {"stages_cleared": stages_cleared, "stopped_reason": "ocr_failure"}

            inf, lan, mrk = rotate_counter(enemy["inf"], enemy["lan"], enemy["mrk"])
            print(f"[climb] enemy comp read as {enemy} -> setting counter "
                  f"{inf}/{lan}/{mrk}")
            set_ratio_deterministic(inf, lan, mrk)
            last_known_ratio = (inf, lan, mrk)
            continue

        print("[climb] could not detect Victory/Defeat from the result screen -- "
              "stopping rather than guessing.")
        return {"stages_cleared": stages_cleared, "stopped_reason": "unknown_result"}

    return {"stages_cleared": stages_cleared, "stopped_reason": "max_stages_reached"}


if __name__ == "__main__":
    result = climb()
    print(result)
