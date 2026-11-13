# Bear Trap Runtime

Bear Trap prioritizes a configured own rally before joining alliance rallies. After a confirmed own
deployment, the routine associates the newly occupied rally row in March Queue with that deployment.
The row becoming idle is the preferred evidence that another own rally may start. The calculated
rally-set plus round-trip travel time is only a fallback when the row cannot be identified.

Joining deliberately avoids OCRing rally names, player names, capacity, or countdowns. The white plus
shape locates candidate rows; colour saturation distinguishes an enabled blue or green control from a
disabled grey control. Each visible row is attempted at most once per pass and rejected rows cool down
briefly before another fresh scan. Deploy is successful only after the button disappears and the World
anchor is verified.

The UI does not expose a reliable machine-readable rally identity or departure time. Consequently, a
missing or rejected Deploy is logged as an aggregate of already joined, full, expired, or insufficient
capacity rather than claiming a more precise cause. The routine also cannot prove that a joining march
will arrive before rally departure. Traffic-derived rally IDs, capacity, departure time, membership,
and leader power can refine these decisions later without changing the bounded UI fallback.

Automated tests cover colour-state classification and own-rally slot tracking. Saved real frames and a
live Bear Trap account log are still required before treating visual thresholds and end-to-end behavior
as merge-ready evidence.
