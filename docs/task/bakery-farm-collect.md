# Collect Bakery and Farm

# Table of contents

1. Business reference: principles, rules, and objectives.
2. Recommended gameplay strategy: decisions, priorities, and special cases.
3. UI reference: screens, important areas, visual feedback, and ambiguities.
4. Target coverage: expected features, explicit limitations, and unsupported behavior.
5. Task design: navigation, states, steps, exits, and rescheduling.
6. Risk management: FMEA-like analysis, detection, impact, and safe recovery.
7. Observability and statistics: data, metrics, and limitations.
8. Implementation status and gaps: delivered features, evidence, and differences from the design.

Chapters 1 through 3 are the author-maintained business reference. This English
version translates them without adding design decisions; the assistant may only
comment on them. Chapters 4 through 7 are the design to evolve together.
Chapter 8 describes implementation from available sources. The actual effects
of taps, including animation duration, visual changes, and UI variants, must be
confirmed by observation.

# Business reference: principles, rules, and objectives

The task is named **Collect Bakery and Farm**.

This is an unconditional daily reward-collection activity. Newer,
non-essential buildings are visible in the north-east part of the city. Each
day, a building with a reward offers a collection which only needs to be
claimed.

These buildings include the Farm, Bakery, and Record House. The Farm has a
dedicated collection. Collecting at a non-Farm building collects the grouped
reward for the other buildings.

# Recommended gameplay strategy: decisions, priorities, and special cases

Collect every available reward every day.

# UI reference: screens, important areas, visual feedback, and ambiguities

The UI uses the project's standard portrait viewport: 720x1280.

The buildings are installed in the upper-right city area and have persistent
positions. When a reward is available, the building displays a white square
icon with a treasure chest in the centre. The icon gently rocks back and forth;
it does not change colour or texture.

## Building and reward interface

Navigation must start from the City view, not the World view. A building can
be reached either by freely swiping in the city or by using its entry in the
Survivor Status dialog. The dialog path reaches the building in focused mode.

Tapping the building toggles focused mode: the building is zoomed and its roof
is removed so that its interior is visible.

## Collection interaction

Tapping the reward icon starts collection.

The Farm grants its dedicated reward. Tapping another building grants the
grouped reward for the other buildings; the Bakery is the first created
building and is used for that grouped collection.

A reward screen displays the rewards received. A tap on a neutral area closes
the screen and returns to the City view.

# Target coverage: expected features, explicit limitations, and unsupported behavior

The task will support Farm collection and grouped collection at the Bakery.
Other new buildings are covered only through the Bakery's grouped collection.

Free city panning, direct navigation to every other building, and collection
rules not described above are out of scope.

# Task design: navigation, states, steps, exits, and rescheduling

## Navigation to the Bakery

The task uses the shared [City building navigation](building-navigation.md)
helper with the `BAKERY` destination. Bakery is the predictable Survivor Status
path to the grouped non-Farm reward. Navigation ends with the building
unfocused and does not itself prove a reward action.

## Navigation to the Farm

Farm navigation uses the shared [City building navigation](building-navigation.md)
helper with the `FARM` destination. The Farm row is OCR-confirmed after its
bounded Survivor Status scroll; an unconfirmed row ends navigation safely
without touching it.

## Task states

Progress is kept in memory by the task instance between executions in the same
bot process. It does not survive a restart. A separate cycle state describes
only the current visit and is reset at the beginning of every execution.

Rescheduling uses in-memory progress, actions sent during the cycle, and the
latest visit result. A visit calls rescheduling and records statistics exactly
once.

| State | Meaning | Next execution |
| --- | --- | --- |
| INACTIVE | Reserved for an unconfigured task. A configured routine does not enter it. | No execution. |
| READY | Initial state of a configured routine. | Normal execution. |
| COMPLETED_SUCCESS_RESCHEDULED | Farm and Bakery/grouped rewards were collected. | Daily reset + one minute. |
| COMPLETED_UNVERIFIED | No reward icon was detected on either building. | Daily reset + one minute. |
| TIMEOUT_RETRY | First, second, or third retryable exit: timeout, navigation, scan, or action. | Five minutes. |
| PARTIAL_RESCHEDULED | Fourth retryable exit after at least one confirmed collection. | Daily reset + one minute. |
| FAILED_RESCHEDULED | Fourth retryable exit with no confirmed collection. | Daily reset + one minute. |

## Cycle steps

The temporary cycle starts from the beginning on the next execution:

1. **Navigate to Farm**.
2. **Verify Farm reward icon presence** — use `BuildingRewardDetector` with
   `FARM_REWARD_ICON` in `FARM_REWARD_ICON_SEARCH`.
3. **Collect Farm reward** — if present, tap the icon and seek negative icon
   detection as visual confirmation.
4. **Navigate to Bakery**.
5. **Verify Bakery reward icon presence** — use `BuildingRewardDetector` with
   `BAKERY_REWARD_ICON` in `BAKERY_REWARD_ICON_SEARCH`.
6. **Collect grouped reward** — if present, tap the icon and seek negative icon
   detection as visual confirmation.
7. **Choose the exit** — stop the visit when complete, anomalous, or too long.
8. **Finalize** — record statistics, calculate progress state, and make the
   single rescheduling decision.

Every step must terminate in a controlled way so finalization can choose an
exit and a next execution time. Screen mapping (templates, detection
parameters, and named areas) must be declared separately from business logic.

The execution time limit and retry budget beyond the four listed retryable
exits are **TBC**.

## Task control UI

Under **Extra city events** -> **Other tasks**, provide one checkbox:
**Enable Collect of Bakery and Farm rewards**.

# Risk management: FMEA-like analysis, detection, impact, and safe recovery

| Risk | Available signal | Intended safe behaviour | Missing decision or information |
| --- | --- | --- | --- |
| Navigation is not confirmed | Expected screen is not recognized. | Do not collect; finalize the visit and schedule a retry. | Define acceptable intermediate screens. |
| ADB error during capture, scan, or tap | Emulator communication exception. | Continue only to finalization without an ADB-dependent recovery action; let the scheduler retry. | Immediate recovery is impossible without a connection. |
| Invalid scan result | Vision returns no usable result. | Retry once, then classify the result as unknown and leave the cycle in a controlled way. | Distinguish this from normal absence of a reward icon. |
| UI or template is incompatible | Expected control is absent or the result is inconsistent. | Do not infer collection; produce diagnostics and schedule a bounded retry. | Absence may also be a manual collection. |
| Cycle takes too long | Execution limit is reached. | End the visit cleanly and schedule a bounded retry. | Define duration limit and retry budget. |

# Observability and statistics: data, metrics, and limitations

Statistics must distinguish confirmed collections, collection actions that were
only sent, and failures. They measure task coverage and diagnose ambiguity; they
do not prove the in-game outcome.

Expected metrics are:

- confirmed Farm collections;
- confirmed Bakery/grouped collections;
- sent but unconfirmed collection actions;
- failed activity actions;
- short retries scheduled, grouped by reason.

Each visit must write one summary log with the chosen state and next execution
time. Diagnostic captures are reserved for exits that justify them and do not
replace live-account confirmation.

# Implementation status and gaps: delivered features, evidence, and differences from the design

The shared navigation and reward-icon mapping are implemented. The Bakery and
Farm indicators use dedicated bundled templates, named search zones, and their
own color-search settings. Automated mapping tests and redacted real-frame
tests cover visible reward indicators. Live manual validation confirms the
navigation sequence, including Bakery after Farm.

Reward collection, confirmation, task state, scheduling, statistics, and the
control checkbox remain for the next implementation stage. The historical
custom tasks are disabled but retained on the test workstation as comparison
fixtures; runtime code and documentation do not depend on them.
