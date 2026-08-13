"""
Shared ADB / OCR / decision-making helpers for the Labyrinth automation.
Used by zone_troop_ratio.py (Cave of Monsters, Charm Mine, ...), zone_land_of_heroes.py,
zone_scan.py, and the daily_labyrinth.py orchestrator.
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

TAP_WAIT = 1.2
POST_ACTION_SETTLE = 2.5

# ---------------------------------------------------------------------------
# ADB helpers
# ---------------------------------------------------------------------------

def adb(*args):
    return subprocess.run([ADB, "-s", DEVICE, *args], capture_output=True, text=True)


def tap(x, y, wait=TAP_WAIT):
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(wait)


def swipe(x1, y1, x2, y2, ms=400, wait=TAP_WAIT):
    adb("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms))
    time.sleep(wait)


def screenshot(label="frame"):
    remote = "/sdcard/screen.png"
    adb("shell", "screencap", "-p", remote)
    local = SCRATCH / f"{label}.png"
    adb("pull", remote, str(local))
    return Image.open(local)


# ---------------------------------------------------------------------------
# OCR helpers
# ---------------------------------------------------------------------------

def ocr_text(img, box, psm=7):
    crop = img.crop(box)
    crop = crop.resize((crop.width * 3, crop.height * 3), Image.LANCZOS)
    return pytesseract.image_to_string(crop, config=f"--psm {psm}").strip()


def ocr_digits(img, box, whitelist="0123456789"):
    crop = img.crop(box)
    crop = crop.resize((crop.width * 4, crop.height * 4), Image.LANCZOS)
    config = f"--psm 7 -c tessedit_char_whitelist={whitelist}"
    text = pytesseract.image_to_string(crop, config=config)
    return re.sub(r"[^0-9]", "", text)


PCT_PATTERN = re.compile(r"(\d{1,3})\.\d{2}\s*%")


def parse_pct(text):
    """'33.33%' -> 33 (whole-number precision is all we need for tap counts)."""
    m = PCT_PATTERN.search(text)
    return int(m.group(1)) if m else None


def find_pct_row(img, x0, x1, y_range=(140, 420), y_step=8, row_h=28):
    """Scan a vertical band at column [x0,x1] for the first row that OCRs as a clean
    NN.NN% -- the row's Y position drifts between captures depending on where a manual
    swipe() gesture lands, so a fixed box is not reliable (confirmed live 2026-08-12)."""
    for y0 in range(*y_range, y_step):
        box = (x0, y0, x1, y0 + row_h)
        val = parse_pct(ocr_text(img, box))
        if val is not None:
            return val, y0
    return None, None


# ---------------------------------------------------------------------------
# Result detection -- color-based (fast, no OCR needed).
# Victory banner center is warm gold/tan (R clearly above B).
# Defeat banner center is cool blue-gray (B clearly above R).
# Calibrated live 2026-08-12 against 6 real result screenshots at varying brightness.
# ---------------------------------------------------------------------------

RESULT_BANNER_SAMPLE_PT = (360, 330)
CONFIRMATION_TITLE_BOX = (230, 425, 490, 465)  # "Confirmation" dialog title, when present


def is_confirmation_dialog(img):
    """Some Deploy actions (e.g. squads under the 150k capacity) throw a 'Confirmation'
    popup instead of fighting immediately. Must be dismissed (Continue) before the real
    result can be read -- confirmed live 2026-08-12, was a false-positive 'defeat' read
    before this check existed."""
    text = ocr_text(img, CONFIRMATION_TITLE_BOX).lower()
    return "confirm" in text


def detect_result(img):
    if is_confirmation_dialog(img):
        return "confirmation_dialog"
    r, g, b = img.convert("RGB").getpixel(RESULT_BANNER_SAMPLE_PT)
    if r > b + 20:
        return "victory"
    if b > r:
        return "defeat"
    return "unknown"


def dismiss_confirmation_dialog():
    """Tap the 'Continue' button on the squads-under-capacity dialog."""
    tap(360, 789, wait=POST_ACTION_SETTLE)


def deploy_and_get_result(deploy_pt, settle=POST_ACTION_SETTLE + 1.5, label="deploy"):
    """Taps Deploy, handles an optional Confirmation dialog, and returns the real
    'victory' / 'defeat' / 'unknown' result."""
    tap(*deploy_pt, wait=settle)
    img = screenshot(f"{label}_raw")
    result = detect_result(img)
    if result == "confirmation_dialog":
        dismiss_confirmation_dialog()
        img = screenshot(f"{label}_post_confirm")
        result = detect_result(img)
    return result, img


# ---------------------------------------------------------------------------
# Screen-state verification -- poll for an expected screen instead of trusting a
# fixed delay. Ports the pattern already proven live in the Java DailyLabyrinthRoutine
# (navStep/waitForScreen). Added 2026-08-12 after a live bug: a blind Challenge-then-
# Deploy tap sequence landed on the wrong screen for stage 3-8 (the troop-adjust screen
# never loaded in time), and the OCR-failure fallback then blindly back-tapped twice,
# exiting all the way out to the City screen ("it just quit out").
# ---------------------------------------------------------------------------

SCREEN_POLL_INTERVAL = 0.5
SCREEN_POLL_TIMEOUT = 6.0

# Anchor crop boxes, calibrated live 2026-08-12. STAGE_SCREEN_ANCHOR_BOX was originally
# the Challenge-button/attempts-text area, but that sits exactly where every Challenge
# tap lands -- MuMu renders a persistent tap-visualization icon there (NOT Android's own
# show_touches, which was tried and didn't fix it) that corrupts the OCR. Moved to the
# "Stage X-Y" label instead, which is never covered by a tap.
STAGE_SCREEN_ANCHOR_BOX = (20, 965, 220, 1000)     # "Stage X-Y" label
TROOP_ADJUST_ANCHOR_BOX = (0, 1060, 400, 1110)     # "Troop Ratio:" label


def is_stage_screen(img):
    text = ocr_text(img, STAGE_SCREEN_ANCHOR_BOX, psm=7).lower()
    return text.startswith("stage")


def is_troop_adjust_screen(img):
    return "troop" in ocr_text(img, TROOP_ADJUST_ANCHOR_BOX, psm=6).lower()


def is_result_screen(img):
    return detect_result(img) in ("victory", "defeat", "confirmation_dialog")


TITLE_ANCHOR_BOX = (30, 20, 400, 60)


def is_battle_report_list_screen(img):
    return "report" in ocr_text(img, TITLE_ANCHOR_BOX, psm=7).lower()


def is_battle_details_screen(img):
    return "details" in ocr_text(img, TITLE_ANCHOR_BOX, psm=7).lower()


LABYRINTH_MAP_ANCHOR_BOX = (30, 15, 350, 55)


def is_labyrinth_map_screen(img):
    return "labyrinth" in ocr_text(img, LABYRINTH_MAP_ANCHOR_BOX, psm=7).lower()


def get_current_stage_label(img):
    """OCRs the 'Stage X-Y' anchor and returns just 'X-Y', or None if unreadable."""
    text = ocr_text(img, STAGE_SCREEN_ANCHOR_BOX, psm=7)
    m = re.search(r"(\d+-\d+)", text)
    return m.group(1) if m else None


def wait_for_screen(check_fn, label="wait", timeout=SCREEN_POLL_TIMEOUT,
                     interval=SCREEN_POLL_INTERVAL):
    """Polls screenshots until check_fn(img) is True or timeout. Returns the matching
    image, or None on timeout (caller must handle -- never guess past a timeout)."""
    elapsed = 0.0
    while elapsed < timeout:
        img = screenshot(f"{label}_poll")
        if check_fn(img):
            return img
        time.sleep(interval)
        elapsed += interval
    return None


# ---------------------------------------------------------------------------
# Counter-ratio math (rock-paper-scissors rotation)
# Infantry beats Lancer, Lancer beats Marksman, Marksman beats Infantry.
# ---------------------------------------------------------------------------

def rotate_counter(enemy_inf, enemy_lan, enemy_mrk):
    return enemy_lan, enemy_mrk, enemy_inf


# ---------------------------------------------------------------------------
# Common screen coordinates (720x1280) shared across zones
# ---------------------------------------------------------------------------

BACK_ARROW = (40, 40)
VIEW_BATTLE_REPORT_BTN = (200, 1000)
ADJUST_TROOPS_LINK = (360, 618)     # on the Defeat screen
CLOSE_VICTORY_TAP = (360, 700)      # "Tap anywhere to close"


def run_length_json_path():
    return Path(r"C:\Bearguard\labyrinth-debug\auto\labyrinth_log.json")
