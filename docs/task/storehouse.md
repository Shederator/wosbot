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
