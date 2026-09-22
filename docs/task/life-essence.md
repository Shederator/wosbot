# Life Essence collection

The player stated on 2026-09-22 that the island shows at most three claim
markers at once. Those markers can return before the daily reset, so an empty
island is not evidence that the next opportunity is the reset itself. No
recharge duration was supplied. The scheduler must not invent one, and the
marker count is not a storage capacity.

A valid capture with no marker means the island is already collected. The
routine then reschedules at the profile offset. A failed capture — device
missing or offline, incomplete screencap, null frame, unsupported pixel depth,
or an invalid pixel buffer — uses `LifeEssenceRetryPolicy` and keeps the
failure count.

A claim counts only after the tapped marker is absent from the next capture.
The loop stops at three confirmed claims. Ignored taps remain inside the
six-capture bound; exhausting that bound without a clean finish uses the same
retry policy. The weekly scroll and the Like action run only after a clean
finish.

Alliance Life Essence caring is a separate routine: it spends up to three
daily assists on other members' islands, then schedules the reset.

A marker is an orange region with a vivid-green leaf in its upper part. On both
saved island frames the leaf centroid sits 6 to 14 pixels above the box center,
because the bubble tail and the tutorial finger extend the orange region
downward. The tap point is that centroid. The same upper-leaf rule rejects the
alliance Tech node at (360, 406) and the alliance event icon at (573, 953).
No other saved task or automation frame produced an accepted marker.

`threeMenu.png` scores about 50 to 57 percent on the island frames and about
48 to 53 percent on those alliance frames, with hits away from the menu button,
so it is not an island-screen proof. The Like button is also absent from a real
island log. An overlay whose green mass sits in the upper part of an orange
region can still be tapped. Live confirmation is still required.
