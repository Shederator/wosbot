# Arena Routine Constraints

`ArenaRoutine` automates challenge attacks only. It does not open, replace, or
otherwise manage the Arena defense formation.

Attack Quick Deploy is enabled by default for backward compatibility. Profiles
may disable it to keep the saved attack formation when starting a challenge.
This setting has no effect on defense because the routine never enters that
flow.

Quick Deploy, Fight, Pause, and Retreat still use fixed tap points. Those taps
assume the corresponding Arena screen state and are not direct detection of the
individual controls.
