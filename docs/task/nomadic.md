# Nomadic Merchant

The shop is a fixed 3×2 grid on the 720×1280 viewport. Each cycle scans for a
card whose price strip has no gem (natural-resource price, including
accelerators, teleports, and a VIP if one is ever priced that way). If one is
found, the routine taps it and scans again, starting at the next slot so a
repeated offer cannot starve the other slots. If none is found, it buys a
detected VIP product icon with the existing gem sheet taps and scans again.
Only when neither pass finds an offer does it look for Free Refresh. One
detected refresh gets one dispatched tap, a 500 ms wait, and another full scan.
This repeats while Free Refresh is detected. Replacement cards are random and
can repeat, so card equality never determines whether to continue or stop.

The take tap is the price strip. Tapping the product icon opens the item
description instead of buying.

Gem-price template `gemprice.png` was cropped from a 19-gem VIP card on
2026-09-30. Search is limited to each slot's price strip so the wallet gem is
ignored. Threshold 80.

Confirmation of a resource take is a per-slot product-region mean channel
change of 12 after the reward flyout (2.5–4 s). An unchanged slot is an
unconfirmed attempt, not a reason to stop scanning. A VIP icon still present
after the gem-sheet taps is likewise unconfirmed, and the next scan may select
it again. These signals affect statistics, not the action loop. Free Refresh
taps count as dispatched attempts, not confirmed refreshes.

A visit ends normally only after a full scan finds neither an eligible offer
nor Free Refresh. It then enters `COMPLETED` and schedules one minute after the
daily reset. The two-minute execution limit is the other normal loop exit. A
timeout with a confirmed collection in the current in-memory cycle enters
`PARTIAL_RESCHEDULED` and waits until after reset. Without a confirmed
collection, the first two timeouts enter `FAILED_RETRY` and retry in five
minutes; the third enters `FAILED_RESCHEDULED` and waits until after reset.
An invalid scan result or a refused Free Refresh dispatch is tried once more
within the visit. Persisting scan and action errors enter `ERROR_RETRY` and
retry in five minutes; emulator connection and interruption exceptions
propagate to the task runner. A new
cycle starts after a completed shop scan or the deferred post-reset visit.
Visit logs separate confirmed collections, unconfirmed attempts, dispatched
refresh taps, errors, and retry decisions.

An emulator capture failure during confirmation leaves the purchase and
remaining cards unknown. The routine propagates that failure and schedules an
error retry. A 2026-10-02 account log showed a capture failure after the first
resource tap, before any purchase was confirmed.
