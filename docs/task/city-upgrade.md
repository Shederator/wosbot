# City Upgrade

Building requirement dialogs have different vertical sizes. Fire Crystal
buildings can show additional prerequisite rows, which moves the final blue
`Upgrade` action below the position used by younger cities.

City Upgrade therefore detects the fixed `Upgrade` label inside the right-hand
bottom action region and taps the detected match. The adjacent premium `Finish`
action is outside that region. After the tap, the routine requires the Upgrade
dialog evidence to disappear and the Home anchor to return before requesting
alliance help or processing a second construction queue. A missing button or an
unproven Home transition is a bounded unresolved attempt and uses the recovery
policy below.

Saved-frame coverage uses
`modules/tasks/src/test/resources/city/fire-crystal-building-upgrade-ready-20260821.png`.
The frame preserves the lower Fire Crystal action position without account
identifiers.

The Furnace detail panel is a separate entry screen: the September 2026 frame
shows an orange `Upgrade` button around (595, 704), while the generic building
arrow template scores only 54.56% on that frame. Require both its `Furnace`
title and orange button in their shared regions before tapping the detected
entry. This is not the final confirmation. The furnace dial and On/Off controls
must never be fallback tap targets. Unknown layouts are unsupported and stop
conservatively after two attempts.

Each handled construction queue, including a production blocker left on a
Speedup panel, returns to confirmed Home before the next queue. Two unresolved
attempts stop this execution instead of trying another queue. Ordinary failures
retain evidence and attempt bounded recovery to either Home or World; successful
recovery still propagates the original failure for the scheduler's retry and
incident tracking. Failed recovery preserves the original as a suppressed cause
and uses the existing navigation-error routing. Stop, preemption, reconnect,
profile cooldown and ADB failures bypass recovery.

Attempt diagnostics distinguish control recognition, resource replenishment,
confirmation and Home transition failures. Captures precede recovery under
`logs/snapshot` with activity `cityupgrade` and the existing 20-capture quota.
Logs identify decision frames versus best-effort later captures; capture/write
failures do not replace ordinary task failures. Recovery failure gets its own
capture. Runtime captures stay local and need redaction before sharing.

Saved-frame coverage also uses `furnace-detail-upgrade-20260927.png` and
`furnace-detail-upgrade-guidance-overlay-20260927.png`. Both account headers are
irreversibly removed; the second frame preserves the game's tutorial hand over
the orange button. The Furnace title and bounded button region gate a 70% button
template match to tolerate that overlay. Generic blue-arrow detection remains
available when the orange entry is absent, even if the Furnace title matches. No
saved low-level Furnace frame is available yet, so that layout's compatibility
is not established.
Tests cover the observed entry, missing identity/control negatives,
separation from final Fire Crystal confirmation, and failure recovery sequencing.
Live account-log confirmation of the new Furnace entry and next-task handoff
remains outstanding.
