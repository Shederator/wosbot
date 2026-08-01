# Task Design Guidelines

These guidelines apply when creating or changing Frostguard automation,
navigation, OCR, template, pixel, color, or timing behavior. Routines should be
readable, bounded, observable, and conservative with UI interaction.

## Documentation And Comments

Document only behavior that is difficult to infer from the code: game mechanics,
screen-state variants, safety constraints, fallback rationale, known unsupported
states, and real-frame evidence. Keep notes under `docs/task/` concise and update
them when those assumptions change. Do not duplicate method-level control flow
or keep improvement backlogs there.

Tests preserve executable behavior. Comments preserve a non-obvious reason next
to the affected logic. Explain measured UI, OCR, pattern, color, timing, or game
mechanic assumptions; do not narrate statements or add author/date history.

## Code Structure

Use named constants for meaningful coordinates, regions, delays, thresholds,
retry limits, and execution windows. Keep `execute()` at the algorithm level and
move details into intent-named helpers. Share reusable vision primitives through
`fg-vision` and reusable game interactions through `fg-engine`.

## Detection Strategy

Choose the signal based on what must be proven:

1. Template matching for stable icons, buttons, and visual identity.
2. Color or pixel matching for state such as enabled, selected, or warning.
3. OCR for text, numbers, timers, costs, and labels.

No signal is perfect. Templates can fail because of scale, crops, weather, or
animation; OCR can misread icons, small text, and wrong regions; colors usually
prove state rather than identity. Combine evidence for risky actions and measure
regions, thresholds, and template sizes against real frames instead of guessing.

## Detection And Interaction

Prefer detected targets over fixed coordinates. When a template result includes
its point and size, tap within that detected area. Use named fixed regions only
when no reliable detected target exists, and document the reason when the choice
is fragile.

Use `tapRandomPoint(...)` for bounded areas. Allow realistic settle time after
navigation, tab changes, dialogs, battles, and rewards. Avoid rapid repeated taps
while waiting for a state change. Pass detected state into action methods rather
than immediately detecting the same state again.

## Bounded And Safe Execution

Every loop needs a timeout, retry limit, or explicit state transition. Expected
exit paths must reschedule or intentionally stop. Centralize scheduling decisions
when practical to avoid hidden double-rescheduling, and verify important actions
before continuing when a reliable success signal exists.

Scheduler and profile-switching behavior must leave enough time for screens and
emulator state to stabilize. Avoid aggressive back-to-back work after profile
load unless the task is explicitly time-sensitive. Unknown states should exit
conservatively rather than create implicit loops.

## Logging And Statistics

Log important state transitions, decisions, evidence, fallback reasons, and the
next scheduling outcome. Use debug or trace for failed intermediate attempts in
a retry chain that later succeeds. Avoid noisy logs in hot loops and do not emit
high-level incidents for recovered retries.

Update statistics only for meaningful outcomes such as fights won or lost,
items claimed, purchases made, or refreshes used.
