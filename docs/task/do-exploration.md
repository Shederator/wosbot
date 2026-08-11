# Exploration Routine Constraints

`DoExplorationRoutine` uses detected templates for the exploration entry and
battle results, but quick deploy, fight, and victory continuation still use
fixed tap regions. Those taps are not confirmation that the requested state
transition succeeded.

Quick Deploy is enabled by default for backward compatibility. Profiles may
disable it to preserve their saved exploration formation; the routine then
taps Fight without changing the selected heroes.

The routine therefore treats an unreadable result as an unknown or locked
battle and exits conservatively. Both the overall fighting loop and result
polling are time-bounded so a missed victory or defeat cannot trap the task.

Known unsupported states:

- quick deploy, fight, or victory continuation taps that do not take effect;
- result screens that appear outside the bounded detection window;
- visually changed victory or defeat screens that no longer match the saved
  templates.

Replace fixed tap regions only when a reliable detected target or state signal
is available. Improvement work belongs in the workboard or an issue rather than
in this design note.
