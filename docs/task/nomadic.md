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
dispatched attempts, not confirmed refreshes. A requested collection is counted
only if neither its scan nor its tap produced an error. Its visual confirmation
remains a log indicator and has no effect on the state.

The execution limit is evaluated after a grid scan has established whether an
offer is selectable, or after the Free Refresh check when no offer is found.
The resulting state is based on dispatched actions, never on confirmation:

| State | Exit condition | Selectable offers in latest scan | Free Refresh detected | Dispatched collections | Dispatched Free Refreshes | Consecutive retryable exits | Reschedule reason | Next run |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `READY` | New cycle or daily-reset cycle. | — | — | 0 | 0 | 0 | — | Normal execution. |
| `COMPLETED_SUCCESS_RESCHEDULED` | Shop exhausted; at least one collection or Free Refresh was dispatched. | 0 | 0 | ≥ 1, or 0 when a refresh was dispatched | ≥ 1, or 0 when a collection was dispatched | Reset to 0 | — | Daily reset + 1 minute. |
| `COMPLETED_UNVERIFIED` | Shop exhausted without an action in the cycle. | 0 | 0 | 0 | 0 | Reset to 0 | — | Daily reset + 1 minute. |
| `TIMEOUT_RETRY` | First, second, or third retryable visit exit: timeout, navigation, scan, or tap failure. | ≥ 1, 0, or unavailable after an error | Detected when offers are 0; otherwise not checked | No effect | No effect | 1, 2, or 3 | `navigation_error`, `adb_error`, `no_collect_error`, or `timeout` | 5 minutes. |
| `PARTIAL_RESCHEDULED` | Fourth consecutive retryable visit exit. | No effect | No effect | ≥ 1 | No effect | 4 | `navigation_error`, `adb_error`, `no_collect_error`, or `timeout` from the last visit | Daily reset + 1 minute. |
| `FAILED_RESCHEDULED` | Fourth consecutive retryable visit exit. | No effect | No effect | 0 | No effect | 4 | `navigation_error`, `adb_error`, `no_collect_error`, or `timeout` from the last visit | Daily reset + 1 minute. |

An invalid scan result or a refused Free Refresh dispatch is tried once within
the visit. Navigation, scan, and tap failures share the timeout retry budget;
their fourth consecutive exit uses the same deferred state. Emulator connection
and interruption exceptions propagate to the task runner.
The reason code is retained in each retryable state and logged with the chosen
schedule. Therefore a cycle that first dispatched a collection and later loses
navigation or ADB connectivity ends in `PARTIAL_RESCHEDULED` with that last
failure's code. A diagnostic snapshot is captured immediately after a terminal
`PARTIAL_RESCHEDULED` or `FAILED_RESCHEDULED` decision for `navigation_error`
or `no_collect_error`, before rescheduling or another game action. An
`adb_error` logs `snapshot=unavailable; reason=adb_error` without attempting a
second ADB capture. Timeouts and short retries do not create snapshots.
A new cycle starts after a completed shop scan or the deferred post-reset visit.
Visit logs separate confirmed collections, unconfirmed attempts, dispatched
offer actions, dispatched refresh taps, errors, and retry decisions.

An emulator capture failure during confirmation leaves the purchase and
remaining cards unknown. The routine propagates that failure and schedules a
short retry. A 2026-10-02 account log showed a capture failure after the first
resource tap, before any purchase was confirmed.
