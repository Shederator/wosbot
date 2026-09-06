# Shop Navigation

The Shop footer is an ordered, non-wrapping strip in the standard 720 x 1280 viewport.
It contains Mystery, Nomadic Merchant, Arena, VIP, Alliance Championship, Labyrinth,
State of Power, Foundry, Canyon, Skin, and Gem shops in that order. Exactly three complete
tabs are visible. A fresh Shop open starts with Mystery Shop selected at the far left.

Each tab occupies `(2 + 198n, 1208)` through `(191 + 198n, 1273)` for visible slot `n`.
Interaction uses a ten-pixel inset. A measured left gesture from `(600,1240)` to
`(350,1240)` reveals later tabs without the overshoot caused by a longer swipe; the reverse
gesture reveals earlier tabs. Navigation never assumes how many positions a gesture moved.

After every settled swipe, OCR reads the complete leftmost tab area. Matching is
case-insensitive containment against measured English markers: `Mystery`, `eee`, `Arena`,
`VIP`, `Championship`, `Labyrinth`, `State`, `Foundry`, `Canyon`, `Skin`, and `Gem`.
`eee` is the observed stable fragment for the multiline Nomadic Merchant label. Unknown,
ambiguous, unchanged, or directionally inconsistent observations stop without clicking.

The order, geometry, swipe, and navigation policy have automated coverage. Saved-frame OCR
coverage and live account-log confirmation are still required before merge readiness.
