# Storehouse Chest

The ready chest is a wooden crate bubble. Night lighting scores chest/chest2
at about 80, so a 90 cut misses a claimable crate. Day lighting on the same
crate scores those crops at about 71. Chest search uses threshold 75 and a
third crop (`chest3.png`) of the daylight bubble. Stamina-can and cooldown
frames stay below 75 on all three chest templates. Stamina search stays at
90 so the top-right shop icon (about 78) is not treated as a can.

The on-building cooldown is a dark pill. Daylight remaining time is green
RGB(61, 216, 13). Night cooldown glyphs are near-white. Read green first, then
white, with whitelist `0123456789:d`. Compact `001558` is 00:15:58. Construction
`3d03:53:22` is accepted, then treated as out of range (cap two hours) and
retried in one hour.

After a chest tap, close the reward overlay and read that building pill. The red
OCR band at the bottom of the reward screen returns 00:00:28–00:00:59 and must
not schedule the next visit.

When the crate is absent and the pill is unreadable, retry in one hour. A failed
Storehouse open still retries in five minutes. A visible stamina can is claimed
on the same visit.
