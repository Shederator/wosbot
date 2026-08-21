# Sidebar Navigation

Whiteout Survival's August 2026 sidebar separates status and destination rows into
`City`, `Wilderness`, and `Daily`. Frostguard treats the selected section as screen-state
evidence rather than assuming that an open or tab tap succeeded.

The selected tab is classified from the fill brightness of fixed interior samples. In the
five supplied 720 x 1280 frames, the selected sample averages 165-172 while unselected
samples average 84-97. The classifier requires both a minimum brightness and a wide lead
over the next tab. It also requires the stable near-white close-handle anchor so changing
map scenery cannot masquerade as a selected tab. A missing classification is an
unknown/closed state, not permission to continue tapping.

Opening the collapsed panel retains one fixed handle area because no stable visible target
exists in the supplied open-panel frames. That tap is allowed only while a Home or World
anchor is present, is issued once, and must produce a classified selected tab. Section taps,
scrolls, and close taps likewise require the expected panel state.

Queue inspection opens or reuses its verified City or Wilderness section without changing the
scroll position. This avoids unconditional reset gestures and allows one logical operation to
inspect and act on the same frame.

The left City and Daily icons provide row identity. Row order, text, height, and right-side
controls do not: completed rows may disappear when `Hide after mission completion` is enabled,
and a row can expose either Go or Claim. The navigator first matches the destination icon, derives
that row's action area, and only then accepts an allowed Go or Claim pattern inside the same row.
Go has saved variants with and without the notification badge.

Opening the collapsed sidebar or changing its section resets the game's list to the top. A
destination scan therefore checks that initial viewport and then moves only toward the bottom in
short overlapping 120-pixel gestures. It waits two seconds for the list to settle and scans the
icon column after every gesture. An unchanged settled icon column establishes the bottom boundary.
The scan is bounded, and a destination action must close the sidebar to confirm the transition.
Code that deliberately reuses an already-open section preserves its current position; March Queue
recovery closes and reopens Wilderness once when no visible row contains reliable queue evidence.

City destination icons use the same row-relative Go association. Research Center is migrated
through its detected Center Research icon. After the Go transition, the building's detected
Research button is preferred; the detected tutorial hand supplies a relative target only while
the onboarding overlay occludes that button.

Trek Supplies is a conditional Daily destination: it is absent after the timed reward has
already been claimed and on accounts without Dawn Academy. The claim routine treats absence as
unavailable rather than scanning the unrelated City queues. When present, the existing supply
icon identifies its row; the routine then accepts either direct claim-panel entry or Dawn Academy
entry followed by the supply counter.

Saved evidence lives under
`modules/automation/src/test/resources/navigation/sidebar-update-20260817` and
`modules/automation/src/test/resources/navigation/sidebar-dynamic-20260821`. It covers City,
Wilderness, multiple Daily positions, notification and non-notification Go actions, Claim,
the active-tab classifier, Research Center, Arena, Pet Adventure, Land of Heroes, Life Essence,
and the dynamic shift caused by hiding completed rows. Other destinations must not be migrated
to a guessed section or reused icon without a real post-update frame.
