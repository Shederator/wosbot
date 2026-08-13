"""
Scans The Labyrinth map screen and reports which of the 6 zones are open vs locked.

Zone label format on the map (confirmed live 2026-08-12):
  OPEN zone:   "<Zone Name>\n<bare countdown, e.g. 1d 09:43:21>"
  LOCKED zone: "<Zone Name>\nOpens in <countdown>"
The presence of the substring "opens in" is the reliable open/locked signal.

Layout note: only 4 of the 6 zones are visible on the default map scroll position
(Cave of Monsters, Charm Mine, Research Center, Gear Forge, as of 2026-08-12). Land of
Heroes and Gaia Heart apparently rotate in/out or sit elsewhere on the map and were not
visible this session -- matt confirmed Land of Heroes was "from yesterday" (a prior
rotation) and Gaia Heart is a weekly (~Sunday) zone. The scan function below only reports
on what's actually visible in the captured frame; a caller wanting the other zones must
scroll the map first (not yet automated -- flagged as a follow-up).
"""

from labyrinth_common import screenshot, ocr_text

# Label crop boxes, calibrated live 2026-08-12 against the default (unscrolled) map view.
# Each box covers the zone-name line + the countdown/"Opens in" line beneath it.
ZONE_LABEL_BOXES = {
    "cave_of_monsters": (100, 460, 320, 520),
    "charm_mine": (400, 610, 620, 670),
    "research_center": (30, 775, 290, 830),
    "gear_forge": (455, 890, 680, 945),
}

# Tap point on each zone's banner/icon to enter it (calibrated live 2026-08-12).
ZONE_ENTER_POINT = {
    "cave_of_monsters": (205, 430),
    "charm_mine": (505, 570),
    "research_center": (160, 750),   # ESTIMATED -- not yet live-verified (zone was locked)
    "gear_forge": (565, 865),        # ESTIMATED -- not yet live-verified (zone was locked)
}


def scan_visible_zones():
    """Returns {zone_key: {'open': bool, 'raw_text': str}} for the zones visible in the
    current (unscrolled) Labyrinth map screenshot. Caller must already be on the map."""
    img = screenshot("zone_scan")
    result = {}
    for key, box in ZONE_LABEL_BOXES.items():
        text = ocr_text(img, box, psm=6)
        low = text.lower()
        # "Opens in" sometimes OCRs with the leading O dropped/mangled ("pens in") --
        # confirmed live 2026-08-12 on the Gear Forge label. Check both forms, plus a
        # bare "opens" in case "in" gets clipped too.
        locked = ("opens in" in low) or ("pens in" in low) or ("opens" in low)
        result[key] = {"open": not locked, "raw_text": text}
    return result


if __name__ == "__main__":
    for key, info in scan_visible_zones().items():
        status = "OPEN" if info["open"] else "locked"
        print(f"{key:20s} {status:7s} | {info['raw_text']!r}")
