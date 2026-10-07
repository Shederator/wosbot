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
it again. Confirmation is only logged and counted as a metric; it never affects
the loop, the progress state, or scheduling. Free Refresh taps count as
dispatched attempts, not confirmed refreshes.

The execution limit is evaluated after a grid scan has established whether an
offer is selectable, or after the Free Refresh check when no offer is found.
The resulting state is based on dispatched actions, never on confirmation:

| State | Condition | Next run |
| --- | --- | --- |
| `COMPLETED_SUCCESS_RESCHEDULED` | No offer and no Free Refresh remain; at least one offer collection or Free Refresh was dispatched in the cycle. | Daily reset + 1 minute. |
| `COMPLETED_UNVERIFIED` | No offer and no Free Refresh remain; no offer collection and no Free Refresh was dispatched in the cycle. | Daily reset + 1 minute. |
| `TIMEOUT_RETRY` | First or second timeout after the current scan shows a selectable offer or Free Refresh. | 5 minutes. |
| `PARTIAL_RESCHEDULED` | Third consecutive timeout with at least one offer collection dispatched without a scan or tap error. | Daily reset + 1 minute. |
| `FAILED_RESCHEDULED` | Third consecutive timeout with no offer collection dispatched. Free Refresh attempts do not affect this state. | Daily reset + 1 minute. |

An invalid scan result or a refused Free Refresh dispatch is tried once within
the visit. A persistent error schedules a short retry through `TIMEOUT_RETRY`;
emulator connection and interruption exceptions propagate to the task runner.
A new cycle starts after a completed shop scan or the deferred post-reset visit.
Visit logs separate confirmed collections, unconfirmed attempts, dispatched
offer actions, dispatched refresh taps, errors, and retry decisions.

An emulator capture failure during confirmation leaves the purchase and
remaining cards unknown. The routine propagates that failure and schedules a
short retry. A 2026-10-02 account log showed a capture failure after the first
resource tap, before any purchase was confirmed.
