# Bear Hunt live-session plan

Status: implemented on PR #346; deterministic tests pass. Live acceptance remains blocked until the next scheduled Bear.

## Confirmed rules

- Bear lasts 30 minutes and an own rally prepares for five minutes.
- Minimum observed travel is seven seconds each way.
- Start the last own rally before T-5:30. From T-5:30 onward the session is join-only.
- During the final five minutes, join rallies already present and stop only after three complete fresh scans confirm that the list is empty.
- Formation configuration means slot position, regardless of its user-visible name.
- A selected formation is proven only by its yellow border.
- A join candidate must target Bear, have a green button, have member room, have enough troop capacity for the entire selected formation, and still have time remaining.
- Keep the same formation after a rally fills, departs, disappears, turns grey, or loses a race. Advance only after a confirmed deployment.
- Reuse a returned formation immediately.
- Use the World-screen red War indicator for joins. Retain the Bear camera anchor for own rallies; Alliance -> Territory -> Special Buildings is recovery only.

## Runtime states

1. Prepare once: disable conflicting auto-join, perform configured recall/pet work, locate Bear, and retain the World anchor.
2. Read all march rows from one frame and reserve the tracked own-rally row.
3. Before T-5:30, launch an own rally only when its tracked row is visibly idle.
   Explicitly select five minutes and require the green tick before continuing.
4. Fill every genuinely free join march in configured formation order.
5. Rank visible candidates by capacity margin, remaining countdown, occupancy, then stable row position.
6. Use fresh-frame predicates for every transition. Fixed navigation/deploy sleeps are not allowed in the active loop.
7. Wait only for a known march-return/event-end deadline, and keep that wait cancellable.
8. Recover from the current screen without a blind second Back tap or resetting confirmed formation/rally state.

## Deterministic release gates

- No own launch at or after T-5:30.
- Five-minute preparation plus travel produces no more than five own rallies.
- A missed/slow page can never count as an empty list; three complete final-window scans are required before waiting for event end.
- Grey, non-Bear, full-member, departed, and insufficient-capacity candidates are rejected.
- Candidate races preserve the pending formation.
- Formation selection requires post-tap yellow-border evidence.
- Deployment succeeds only after the march queue shows a newly occupied rally row.
- Cancellation/preemption is checked by capture polling and again by the tap service.
- Full repository test suite must remain green and every commit must carry DCO sign-off.

## Next live acceptance

At the next Bear, record transition timestamps and verify:

- known World -> War list under 1 second p95;
- valid visible candidate -> Deploy under 1.5 seconds p95;
- failed candidate -> next candidate under 750 ms p95;
- returned formation -> next attempt under 5 seconds when a valid rally exists;
- full Bear recovery under 5 seconds;
- first own rally and all configured join formations select yellow and deploy;
- returned formations recycle, no own rally begins after T-5:30, and cleanup runs once at event end.

Do not mark the PR ready solely from simulation. The next scheduled Bear is the live release gate.
