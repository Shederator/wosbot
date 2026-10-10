# Storehouse Chest

## Detection and cooldown

The ready chest is a wooden crate bubble. Night lighting scores chest/chest2
at about 80, so a 90 cut misses a claimable crate. Day lighting on the same
crate scores those crops at about 71. Chest search uses threshold 75 and a
third crop (`chest3.png`) of the daylight bubble. Stamina-can and cooldown
frames stay below 75 on all three chest templates. Stamina search stays at
90 so the top-right shop icon (about 78) is not treated as a can.

The on-building cooldown is a dark pill. Daylight remaining time is green
RGB(61, 216, 13). Night cooldown glyphs are near-white. Read green first, then
white, with whitelist `0123456789:d`. Compact `001558` is 00:15:58. These are
visual/OCR assumptions; do not infer the timer's game meaning from its text
alone.

The Storehouse stamina reward uses its own `A Warm Welcome` title and Claim-text
templates. Search the Claim text in the full lower button area (x=200..520,
y=900..1020 on 720x1280 frames); the Daily Mission Claim crop is too small for
this button. A `Chief Stamina` tooltip can cover the reward title and button
after tapping the 120 stamina tile. Dismiss it with Android Back only when its
title is detected, then reacquire the reward title and Claim text before
tapping. Never clear this popup with a neutral coordinate tap: that region
overlaps the stamina tile and can open the tooltip again. Saved-frame coverage
is in `StorehouseStaminaClaimFrameTest`.

After claiming a chest, dismiss its reward overlay with exactly one randomized
tap in the neutral blue ground to its right: `(565,530)` through `(715,705)` on
the 720×1280 annotated frame. This area must remain clear of buildings. Do not
repeat the tap at the left edge: the overlay may already have closed, making a
second tap act on the restored city. Keep the existing settle wait, city-anchor
check, and bubble-disappearance confirmation; the tap alone is not collection
evidence.

## Visit flow and scheduling

Keep a task `VisitState` (`READY`, `WAITING_COOLDOWN`, or `RETRY_ON_ERROR`)
in memory on the task object. Recalculate it from fresh observations on every
`execute()` call, starting that visit at `READY`; retain the previous value only
for in-process state and transition logging. Do not persist it to disk or a
database; a new bot process starts at `READY`. Track the current activity phase
separately for each visit (searching/collecting chest or stamina, reading
cooldown, and rescheduling), and log state transitions.

On each due visit, search both POI types on fresh, settled city frames. Collect
each detected POI using its kind-specific interaction. After each action has
settled, confirm that same bubble has disappeared, then rescan both types:
collecting one POI can make the other appear. Bound the scan/collection loop by
an action limit and a visit time limit.

Only a reliable scan that finds neither chest nor stamina enters cooldown
reading and sets `WAITING_COOLDOWN`. A valid positive cooldown determines the
next delay, capped at one hour. OCR failure or an invalid numeric value falls
back to one hour; an invalid value alone is not an interface failure and does
not warrant a snapshot. Do not read the cooldown while a POI remains to
collect.

An unconfirmed collection, uncertain screen/capture, or failed Storehouse open
is an unknown outcome: set `RETRY_ON_ERROR` and retry in five minutes. Do not
credit `StaminaService` until the stamina bubble's disappearance is confirmed.
For handled outcomes, make exactly one reschedule at the end of the visit,
using the in-memory visit state and the completed activity phase to choose the
delay. Keep state changes and the selected delay explainable in logs.

Live search is `StorehouseBubbleDetector`: white bubble then crate wood vs
stamina copper. Six captures over about 1.5 s cover one bob. Overlay and
preprocess scores: `./tools/storehouse-detection/detect.sh`. Template `chest3`
BGR at 75 remains the OpenCV comparison winner; grey and dropped-blue do not
recover the wood crops. See the tool README.
