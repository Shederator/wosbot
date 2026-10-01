# Pet Adventure chests

The map shows at most three treasure pins. An idle pin is a coloured chest
with no pet portrait and no countdown. An occupied pin still shows the same
chest rim, plus a pet portrait and a dark timer pill under the marker.

`chestRed`, `chestPurple`, and `chestBlue` are rim crops, so they match both
idle and occupied pins. A start is only sent to a pin whose nearby countdown
template (`chestAdventureTimer`) is absent.

After Select Pet and Start, the accepted screen is the overlay whose subtitle
is `In Adventure` (`chestInAdventure`). That overlay with a full remaining
duration is a confirmed start, not an occupied-chest miss. The 2026-10-01
`start-outcome` frames are that overlay at 05:00:00 / 03:59:59 / 04:59:59.

If Start disappears and `In Adventure` is missing (for example the stamina
Obtain more sheet), the visit is unverified: the next run only claims and
re-reads, and does not tap Start. Daily attempts exhausted uses the game
reset.
