# Bear Hunt frame evidence gate

The Bear routine now routes device input through `BearUiStateMachine`: a current ordered frame from
the bounded Android H.264 recorder authorizes one legal edge, and a later frame must prove the edge's postcondition. The replay
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
9. Rally pages with mixed green/grey/no-plus rows; a viewport with zero plus but additional rows
   below; reorder and disappearance between authorization frames; before/after scroll with the
   repeated overlap row and readable leader text; a genuine absolute-bottom frame; and the
   intermediate War screen used to reopen the Rally tab. Current traversal tests prove cursor
   rules, not these visual identities.
10. Formation screens for every supported normal slot in both idle and orange/occupied states.
    Current sanitized evidence covers slots 1, 3, 4, and 5; slots 6–8 still require real fixtures.
11. Successful Deploy returning to the identical list/scroll position; every game rejection
    variant; a failed Back; and all configured formations occupied followed by one becoming free.
12. Special preparing, outbound, returning, and idle across process restart, both with and without
    an exact persisted deadline; five normal slots and the VIP sixth slot while Special is active.
13. Event end during the final pre-Deploy authorization window and terminal cleanup from every
    classified Bear screen.
14. The actual Android H.264 120-second renewal, stale-frame suppression, ADB disconnect/rebind,
    Whiteout restart, and checkpoint resume on the supported emulator.

Until those fixtures exist, pre-activation preparation, red-indicator arrival, and high formation
slots return an explicit operator-action or fail-closed recovery outcome. The disappearance of the
red indicator is not treated as proof that the correct Trap is centered. A real Bear event is still
required to validate timing, visual thresholds, trap identity, and game-side rally behaviour.
