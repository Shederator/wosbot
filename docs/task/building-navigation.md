# City building navigation

`NavigationHelper.navigateToBuilding(BuildingDestination)` reaches supported
new City buildings through Survivor Status. It always normalizes to City
(`HOME`) first and returns with the destination unfocused, ready for a caller's
own interaction. It does not collect rewards and does not prove that the final
tap changed the game UI.

The supported destinations are `BAKERY` and `FARM`. The helper uses named
fixed regions in `CommonGameAreas` and a 500 ms settle between its UI inputs.
The controls are declared in the shared source mapping; raw coordinates are
deliberately not repeated here.

## Bakery

The helper opens Survivor Status, then makes one named downward swipe to reset
the list to its head before selecting Bakery. The reset is required because the
dialog retains the previous building-list position, including after Farm
navigation. It then presses the Bakery **Go** button and taps the named neutral
area to leave focused mode. It does not recognise the label or **Go** button
before tapping. The controls are assumed reliable at their fixed positions;
this is an unverified navigation result until live validation confirms it.

## Farm

The Farm entry is below the initial Survivor Status viewport. The helper opens
the dialog, performs one measured list swipe, and OCRs the expected Farm label
in its named region before touching the Farm entry. If that read is missing or
invalid, it presses Android Back once to dismiss the dialog and returns
`false`; it does not tap a possible stale or shifted row. On confirmation, it
selects Farm, presses **Go**, and taps the named neutral area to leave focused
mode.

## Task Builder

The `BUILDING_NAVIGATION` node exposes the same `BuildingDestination` choices.
An invalid saved target or an unconfirmed Farm entry fails the node without
continuing the flow. Generated custom-task code uses the same helper API rather
than duplicating its zones or gesture sequence.

## Evidence

Automated tests cover the bounded decision paths and Task Builder wiring. Live
manual validation confirmed the Bakery -> Farm -> Bakery sequence, including
the Bakery list reset. Saved real-frame evidence is provided for reward-icon
detection, not for the navigation controls.
