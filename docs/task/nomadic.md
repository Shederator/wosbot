# Nomadic Merchant

The shop is a fixed 3×2 grid on the 720×1280 viewport. Pass one takes every
card whose price strip has no gem (natural-resource price, including
accelerators, teleports, and a VIP if one is ever priced that way). Pass two
buys remaining VIP product icons with the existing gem sheet taps. Free
Refresh runs after both passes.

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
all three retries are still unconfirmed, the routine keeps that progress and
defers the next scan until one minute after the daily reset. The retry budget
starts over after that reset or a completed shop scan.

An emulator capture failure during this confirmation leaves the purchase and
remaining cards unknown. The routine propagates that failure and retries soon;
only an exhausted shop scan can schedule the next reset. A 2026-10-02 account
log showed a capture failure after the first resource tap, before any purchase
was confirmed.
