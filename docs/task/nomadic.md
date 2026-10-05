# Nomadic Merchant

The shop is a fixed 3×2 grid on the 720×1280 viewport. Pass one takes every
card whose price strip has no gem (natural-resource price, including
accelerators, teleports, and a VIP if one is ever priced that way). Pass two
buys remaining VIP product icons with the existing gem sheet taps. After both
passes, the routine searches for Free Refresh. When detected, it records the
tap decision, dispatches one tap, waits 500 ms, then starts a new resource
scan. The refresh offer can remain visible after a tap; only the next full
scan's Free Refresh search decides whether another single tap is available.
Offer equality before and after a refresh is not used because replacement
offers are random and can repeat. A tap that returns successfully counts as a
dispatched tap; an exception during the action leaves the outcome unknown.

The take tap is the price strip. Tapping the product icon opens the item
description instead of buying.

Gem-price template `gemprice.png` was cropped from a 19-gem VIP card on
2026-09-30. Search is limited to each slot's price strip so the wallet gem is
ignored. Threshold 80.

Confirmation of a resource take is a per-slot product-region mean channel
change of 12 after the reward flyout (2.5–4 s). An unchanged slot is skipped
for the rest of that scan so other offers can be collected. The visit then
remains unconfirmed and retries soon, starting with a fresh scan of every slot;
it must not refresh or record completion while a skipped offer remains.
An initial unconfirmed visit can schedule up to three five-minute retries. If
all three retries end with an unknown outcome, it defers the next scan until
one minute after the daily reset. The in-memory state becomes
`PARTIAL_RESCHEDULED` if a resource or VIP offer was confirmed during the
cycle, or `FAILED_RESCHEDULED` if none was confirmed. `FAILED_RETRY` marks an
unknown visit within the retry budget; `COMPLETED_SUCCESS` marks an exhausted
shop scan. These states choose the next schedule and never skip a scan. The
retry budget and confirmed-offer tracking restart after the reset or a
completed shop scan. Free Refresh taps are counted as dispatched attempts, not
confirmed refreshes. End-of-visit logs include confirmed and unconfirmed
collections, failed actions, dispatched Free Refresh taps, and the number of
short retries scheduled by that visit.

An emulator capture failure during this confirmation leaves the purchase and
remaining cards unknown. The routine propagates that failure and retries soon;
after retry exhaustion, an unknown outcome is deferred until one minute after
the reset as `PARTIAL_RESCHEDULED` or `FAILED_RESCHEDULED`. A confirmed
exhausted shop scan uses `COMPLETED_SUCCESS` and is also scheduled after the
reset. A 2026-10-02 account log showed a capture failure after the first
resource tap, before any purchase was confirmed.
