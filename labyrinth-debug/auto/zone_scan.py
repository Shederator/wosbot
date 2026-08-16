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
rotation) and Gaia Heart is a weekly (~Sunday) zone.

Update 2026-08-16 (a Sunday, Gaia Heart's real open day): when actually open, Gaia
Heart DOES render on the default unscrolled map view, at the very bottom of the frame
(no scroll needed) -- box/enter-point below calibrated live against that. Its label
format differs slightly from the other 4: no "Opens in" prefix when open, just
"Gaia Heart\n<bare countdown>" (e.g. "10:02:52") same as the other zones' open state, so
the existing "opens in" substring check still works unmodified. Not yet confirmed
whether Gaia Heart still renders here on a day it's closed (i.e. whether it silently
drops off ZONE_LABEL_BOXES's crop entirely, vs. showing a "closed"/locked state) --
scan_visible_zones() only reports zones whose box is actually populated by the caller,
so daily_labyrinth.py should treat a missing/unreadable gaia_heart entry as "not this
rotation" rather than an error.

The scan function below only reports on what's actually visible in the captured frame;
a caller wanting Land of Heroes or a closed Gaia Heart must scroll the map first (not
yet automated -- flagged as a follow-up).
"""

from labyrinth_common import screenshot, ocr_text

# Label crop boxes, calibrated live 2026-08-12 against the default (unscrolled) map view.
# Each box covers the zone-name line + the countdown/"Opens in" line beneath it.
# gaia_heart added 2026-08-16, calibrated live while it was actually open.
ZONE_LABEL_BOXES = {
    "cave_of_monsters": (100, 460, 320, 520),
    "charm_mine": (400, 610, 620, 670),
    "research_center": (30, 775, 290, 830),
    "gear_forge": (455, 890, 680, 945),
    "gaia_heart": (140, 1005, 460, 1080),
}

# Tap point on each zone's banner/icon to enter it (calibrated live 2026-08-12).
ZONE_ENTER_POINT = {
    "cave_of_monsters": (205, 430),
    "charm_mine": (505, 570),
    "research_center": (160, 750),   # ESTIMATED -- not yet live-verified (zone was locked)
    "gear_forge": (565, 865),        # ESTIMATED -- not yet live-verified (zone was locked)
    "gaia_heart": (300, 1030),       # live-verified 2026-08-16 -- lands on Gaia Heart's
                                      # own stage-select screen, confirmed by title OCR.
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
