# Shop Navigation

The Shop footer is an ordered, non-wrapping strip in the standard 720 x 1280 viewport.
It contains Mystery, Nomadic Merchant, Arena, VIP, Alliance Championship, Labyrinth,
State of Power, Foundry, Canyon, Skin, and Gem shops in that order. Exactly three complete
tabs are visible. A fresh Shop open starts with Mystery Shop selected at the far left.

Each tab occupies `(2 + 198n, 1208)` through `(191 + 198n, 1273)` for visible slot `n`.
Interaction uses a ten-pixel inset. Shop swipes have independent global database calibration
for the first gesture and for follow-up gestures (swipe 2 onward). Each gesture profile
controls the from/to coordinates, duration, and post-swipe settle time. A duration of `0` uses the
emulator's default duration, matching a Task Builder Swipe node; positive values send an
explicit duration. Earlier navigation reverses the configured endpoints. Defaults for both
profiles are `(600,1240)` to `(350,1240)`, default duration, and a 1500 ms settle. Navigation
never assumes how many positions a gesture moved.

The strip is end-clamped rather than circular. At the final position, Canyon is not aligned
with the original left edge; Gem is aligned against the right edge instead. Canyon, Skin,
and Gem therefore never use a left-derived tap position. Navigation continues swiping later
until OCR confirms Gem in the mirrored rightmost tab area `(529,1208)` through `(718,1273)`,
then computes the target backward by the fixed 198 px pitch. The inset tap areas are Canyon
`(143,1218)-(312,1263)`, Skin `(341,1218)-(510,1263)`, and Gem
`(539,1218)-(708,1263)`. If the Gem end anchor cannot be confirmed, navigation stops without
clicking.

The global configuration keys are prefixed with `SHOP_NAVIGATION_FIRST_SWIPE_` and
`SHOP_NAVIGATION_FOLLOW_UP_SWIPE_`. Coordinate values must remain inside the standard
720 x 1280 viewport, duration must be 0-10000 ms, and settle must be 0-30000 ms. Invalid
values fall back to their defaults and are logged. Effective values are logged for every
gesture so live calibration can be correlated with the observed leftmost tab.

After every settled swipe, OCR reads the complete leftmost tab area. Matching is
case-insensitive containment against measured English markers: `Mystery`, `eee`, `Arena`,
`VIP`, `Championship`, `Labyrinth`, `State`, `Foundry`, `Canyon`, `Skin`, and `Gem`.
`eee` is the observed stable fragment for the multiline Nomadic Merchant label. Unknown,
ambiguous, unchanged, or directionally inconsistent observations stop without clicking.

The order, geometry, swipe, and navigation policy have automated coverage. Saved-frame OCR
coverage and live account-log confirmation are still required before merge readiness.
