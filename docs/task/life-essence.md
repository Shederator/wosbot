# Life Essence collection

The player stated on 2026-09-22 that the island shows at most three claim
markers at once. Those markers can return before the daily reset, so an empty
island is not evidence that the next opportunity is the reset itself. No
recharge duration was supplied. The scheduler must not invent one, and the
marker count is not a storage capacity.

A capture counts as valid only when `LifeEssenceIslandScreen` finds the
top-bar Life Essence counter. Otherwise the policy's `SCREEN_UNCONFIRMED`
outcome stops the run and uses `LifeEssenceRetryPolicy`. A blank transition
frame or a screen left by failed navigation has no markers, and without this
check it would read as an already collected island; a 2026-08-23 live run
reported "Claimed: 0" that way. The counter crystal is a 28 x 40-41 region of
674 to 684 vivid-green pixels on all four saved island frames, and no saved
non-island frame has a vivid-green region of 100 pixels in that area.

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

`tools/life-essence-detection` draws this same detector onto PNG copies. A
green box is accepted, a red box is rejected, the cyan cross is the tap, and
the orange cross is the box center. The pictures are local inspection output.

`threeMenu.png` scores about 50 to 57 percent on the island frames and about
48 to 53 percent on those alliance frames, with hits away from the menu button,
so it is not an island-screen proof. The Like button is also absent from a real
island log. An overlay whose green mass sits in the upper part of an orange
region can still be tapped. Live confirmation is still required.

## Candidate crystal search

`IslandClaimBadges` (`--search blob` in the detection tool) finds the green
crystal by size and fill whatever colour its bubble has. It is not used by the
task. Bubbles fill with orange as essence accumulates. On the 2026-08-23 island
frame all three bubbles are white with a small orange tail; a live run that day
claimed all three and the counter rose from 42,955 to 44,012. The orange-region
detector accepts none of them, and none on the partially filled 2026-09-23
frame, where the blob search finds two. The blob search misses both markers on
the 2026-08-18 frame, whose 50 x 70-72 crystals exceed its 46 x 70 window.
These counts come from a script port of both detectors, not the Java tool, so
the tool benchmark is still required before either detector replaces the
other. Its two-capture stability check, which drops a reward crystal in
flight, is kept with it but needs two frames to measure.
