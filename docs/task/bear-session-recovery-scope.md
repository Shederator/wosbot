# Bear session recovery — #340

Focused replacement for closed PR #346, based on upstream main `c899dd6`.
The experimental branch `elroy-bear-rally-fix` is preserved, not merged here.

## Contract

One invocation uses its configured absolute activation and end time, including
startup checks and preparation. Home-navigation and ADB failures get up to three
consecutive attempts with interruptible five-second delays. Recovery does not
clean up or schedule the next event. A successful participation cycle resets the
failure count.

After three consecutive failures, Bear retains its task slot until the same end
time but admits no further input. It then reports failure, restores ordinary
tasks, and schedules the next configured event. It does not pause the entire
profile. Expiry before recovery succeeds also reports failure. Cancellation and
fatal errors still exit immediately.

Completed preparation is retained during retries. Pet activation is attempted
only once because an interrupted activation has an unknown outcome. Known own
rally state survives recovery; an ambiguous Deploy keeps the duplicate guard
until event cleanup. Joining may continue while that guard is set.

Manual Run Now uses the configured schedule; a visible shortcut does not grant
another 30 minutes. A task-thread input guard checks helper taps, Back, swipe and
text admission against cancellation and the deadline. It cannot undo a command
already dispatched into the existing ADB retry layer.

## Evidence and limits

Deterministic tests cover preparation/active recovery, bounded exhaustion,
cancellation, deadline expiry inside a helper, actual next-event scheduling,
single pet activation, own-rally guards, and unchanged default task preparation.
These tests use controlled clocks and interaction collaborators. They do not
establish visual correctness or successful live Bear participation.

This is not durable recovery across application restarts. Existing navigation,
rally matching, formation selection, ADB retries, and rally-return timing remain.
An ambiguous own deployment is conservatively blocked rather than visually
reconciled. Long-running helper calls may return after expiry; the input guard
rejects new controller input, not an already dispatched command.

## Separate follow-ups

- Frame/action provenance and safe transport retry semantics.
- Recorded-frame navigation and rally-list correctness.
- Recording/replay tooling and measured OCR/matcher performance.
- Preparation redesign and additional formation/Trap 2 support.

No emulator integration, packaging, recording infrastructure, or full visual
state-machine rewrite belongs in this PR. Live-event validation is still needed.
