# Bear Hunt frame evidence gate

The Bear routine now routes device input through `BearUiStateMachine`: a current ordered screenshot
authorizes one legal edge, and a later screenshot must prove the edge's postcondition. The replay
tests validate ordering, transition legality, retry bounds, and interruption semantics. They are
state replays; they do **not** validate the accuracy of templates against the game UI.

## Evidence already represented by production classifiers

- World root and the active Bear indicator
- Bear rally panel, rally timer, and formation screen
- War list and join controls
- deploy confirmation, same-target dialog, and march-queue-full dialog
- open Wilderness march sidebar and its row/special-rally classifiers
- Alliance menu and configured Special Buildings `Go` readiness
- reconnect and app-loading screens

## Exact live frames still required

These flows deliberately fail closed instead of falling back to the former coordinate-and-sleep
scripts. Capture each item at the supported 720 × 1280 viewport, including one negative neighbour
frame where noted, before enabling preparation in production:

1. Alliance War landing screen; Autojoin panel; Autojoin running; Autojoin stopped.
2. City/World with march sidebar closed; sidebar open on a non-Wilderness tab; recall button;
   recall confirmation; confirmed recalled/empty row.
3. Pets overview; Razorback selected; Quick Use dialog; positive activated-benefit state.
4. Alliance Territory landing screen, plus the Alliance-menu frame immediately before it.
5. Special Buildings list with Trap 1 active, Trap 2 active, and Trap 2 inactive/disabled.
6. World centered on configured Trap 1 and Trap 2, plus an ordinary World frame. This is needed to
   prove the center target rather than relying on elapsed time after a `Go` tap.
7. Formation strip scrolled to slots 9–12, with a locked and a selected high-slot example.
8. Event-end UI while the routine is on World, War list, Bear panel, formation, and each deploy
   dialog, so cleanup destinations can be checked against real pixels.

Until those fixtures exist, pre-activation preparation, red-indicator arrival, and high formation
slots return an explicit operator-action or fail-closed recovery outcome. The disappearance of the
red indicator is not treated as proof that the correct Trap is centered. A real Bear event is still
required to validate timing, visual thresholds, trap identity, and game-side rally behaviour.
