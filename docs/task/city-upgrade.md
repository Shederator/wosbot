# City Upgrade

Building requirement dialogs have different vertical sizes. Fire Crystal
buildings can show additional prerequisite rows, which moves the final blue
`Upgrade` action below the position used by younger cities.

City Upgrade therefore detects the fixed `Upgrade` label inside the right-hand
bottom action region and taps the detected match. The adjacent premium `Finish`
action is outside that region. After the tap, the routine requires the Upgrade
dialog evidence to disappear and the Home anchor to return before requesting
alliance help or processing a second construction queue. A missing button or an
unproven Home transition is a bounded unresolved attempt and returns through
normal Home recovery.

Saved-frame coverage uses
`modules/tasks/src/test/resources/city/fire-crystal-building-upgrade-ready-20260821.png`.
The frame preserves the lower Fire Crystal action position without account
identifiers.
