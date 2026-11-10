# Arena Routine Constraints

`ArenaRoutine` automates challenge attacks only. It does not open, replace, or
otherwise manage the Arena defense formation.

Attack Quick Deploy is enabled by default for backward compatibility. Profiles
may disable it to keep the saved attack formation when starting a challenge.
This setting has no effect on defense because the routine never enters that
flow.

Quick Deploy, Fight, Pause, and Retreat still use fixed tap points. Those taps
assume the corresponding Arena screen state and are not direct detection of the
individual controls.

Opponent rows have two supported layouts. The server-row layout shows a server
number below the power value and moves both power and score upward; the compact
layout omits that row. Arena detects the score-star position per opponent and
uses layout-specific power geometry. The compact geometry remains based on live
log evidence only. The server-row geometry has saved-frame OCR coverage using an
anonymized 720x1280 real frame.

Power color is the hard safety signal: green opponents may be eligible and red
or unreadable opponents are skipped. Numeric power OCR only ranks otherwise
eligible opponents from weakest to strongest. A missing or malformed numeric
read therefore falls behind valid values instead of overriding the color
decision.

The Challenge List has a Daily Challenges chest with a 0/5 to 5/5 count below
it. Only a finished count draws its leading digit green, so that color is the
readiness signal; the chest art pulses and is not matched. The routine claims it
from the list when its attempts end and still checks the city chest afterward.

Power below one million is printed in full (`660,265`) instead of with a unit
suffix, and both forms rank together.
