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

Daily destination icons provide row identity. The identical Go arrows do not. The navigator
therefore finds the destination icon and derives the Go area from the detected row geometry.
It resets the section to the top with a bounded gesture count, uses a destination-specific
bounded scan, and requires the panel to disappear after the Go tap.

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
`modules/automation/src/test/resources/navigation/sidebar-update-20260817`. It covers City,
Wilderness, three Daily scroll positions, the active-tab classifier, Research Center, Arena, Land of Heroes,
Life Essence, and relative Go-area association. Other destinations must not be migrated to a
guessed section or reused icon without a real post-update frame.
