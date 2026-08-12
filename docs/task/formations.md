# Saved formations

Frostguard supports saved formation slots 1-12 through the shared
`FormationSlots` capability. Configuration keys retain the legacy `FLAG` name
for compatibility.

On the 720x1280 Deploy/Squad screen, slots 1-8 are reliably fully visible at
`x=62,136,210,285,359,433,507,582`, all at `y=120`. Slot 9 can be only partly
visible or occupy the position used by the save control, so it must not be
selected from the initial view. A saved slot contains a white flag, a locked
slot contains a padlock, and an unlocked but empty slot shows only its label.
Selection must verify one of those states before tapping and fail closed for
locked, empty, unreadable, or unsupported slots.

For slots 9-12, swipe left from visible formation 8 at `(582,120)` to
`(182,120)` over 600ms. The strip ignores drags starting in its blue gaps. A
measured swipe reveals the right-end view with slots 5-12 and centres
`x=336,409,482,556` for
slots 9-12. One swipe reaches the measured end view; do not swipe a second time.
