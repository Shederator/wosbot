# Do Exploration Routine

## Introduction

`DoExplorationRoutine` drives exploration combats from the exploration screen. It starts fights, waits for battle result screens, records victory/defeat statistics, and stops when it cannot safely follow the game state.

## Prerequisites

The routine starts from the world screen. The exploration entry button must be reachable from the main screen.

## Execution

```text
Open the exploration screen.
Detect EXPLORATION_BUTTON.
If the button is missing, reschedule and exit.

Tap inside the detected explore button area when template size is available.
Start a fighting window limited to 2 minutes.

While the fighting window is active:
    tap quick deploy.
    tap fight.
    wait 15 seconds before result detection.
    search every second for up to 25 seconds:
        if EXPLORATION_VICTORY is found:
            increment "Exploration Fights Won".
            tap continue.
            start the next battle.
        if EXPLORATION_DEFEAT is found:
            increment "Exploration Fights Lost".
            reschedule and exit.
    if neither result appears:
        treat the combat as locked or unknown.
        reschedule and exit.

Reschedule and exit.
```

## Exit Cases

- Success: one or more victories are handled, then the 2-minute fighting window ends.
- Success: defeat is detected and the routine exits.
- Failure: the explore button is not found.
- Failure: neither victory nor defeat is detected inside the result polling window.

## Known Unsupported States

- Quick deploy tap failed.
- Fight tap failed.
- Victory continue tap failed.
- Victory or defeat is visible but not detected.
- Game timing changes so the result appears outside the configured detection window.

The routine avoids unmanaged loops by limiting fighting execution to 2 minutes.

## Statistics

The routine updates:

- `Exploration Fights Won`
- `Exploration Fights Lost`

## Configuration And Capture

Static tap areas and timings are defined in `fg-tasks/src/main/java/dev/frostguard/tasks/exploration/DoExplorationRoutine.java`.

Template mappings live in `fg-api/src/main/java/dev/frostguard/api/configs/TemplatesEnum.java`; template images live under `fg-vision/src/main/resources/templates/exploration/`.

Relevant templates:

- `EXPLORATION_BUTTON`
- `EXPLORATION_VICTORY`
- `EXPLORATION_DEFEAT`

## Future Improvements

- Add templates for quick deploy, fight, and victory continue, then replace fixed tap areas with detected template areas.
- Add stronger state detection before tapping quick deploy and fight.
- Add a dedicated locked-combat template if the game displays a reliable locked-state message.
