# Task Design Guidelines

These guidelines apply when creating or updating Frostguard automation routines. Routines should be readable, bounded, observable, and conservative with UI interaction.

## Task Documentation

Document non-trivial routines under `docs/task/`. Include the purpose, prerequisites, main algorithm, exit cases, known unsupported states, statistics, configuration, templates, and improvement ideas. Keep documentation in the same change as behavior updates.

## Code Structure

Use named constants for important coordinates, delays, retry limits, and execution windows.

```java
private static final PointData EXPLORATION_ENTRY_TOP_LEFT = new PointData(40, 1190);
private static final long MAX_FIGHTING_WINDOW_MS = 120_000L;
```

Keep `execute()` at the algorithm level and move details into intent-named helpers such as `open...Screen`, `detect...Button`, `tap...Button`, `waitFor...`, `handle...`, and `reschedule...`.

## Detection And Tapping

Prefer detection-driven interaction over fixed coordinates. When template search returns `ImageSearchResultData`, tap inside the detected template area using `getPoint()` and `getTemplateSize()` when available. Use fixed tap areas only when no reliable template or OCR result exists, and name those areas with constants.

## Bounded Execution

Every loop must have an explicit exit condition: an execution window, retry limit, or state transition. Every expected exit path should reschedule or intentionally stop the task. Avoid hidden double-rescheduling by centralizing schedule decisions when practical.

## Human-Like Interaction

Use `tapRandomPoint(...)` for bounded areas rather than repeated exact coordinates. Add realistic settle delays after opening screens, changing tabs, confirming dialogs, starting battles, and dismissing rewards. Avoid fast repeated taps while waiting for UI state changes.

## Scheduler And Profile Switching

Scheduler behavior should allow screens and emulator state to stabilize. Avoid aggressive back-to-back task execution after profile load unless a task is explicitly time-sensitive.

## Logging And Statistics

Log important state transitions: task start, entry state found or missing, major action performed, terminal state detected, timeout or unknown state, and next schedule decision. Update statistics for meaningful outcomes such as fights won/lost, items claimed, purchases made, or refreshes used.

## Reliability Improvements

Prefer passing detected state into action methods instead of re-detecting blindly after navigation. Verify that important actions succeeded before continuing when practical. Document known unsupported states instead of leaving them as implicit loops.
