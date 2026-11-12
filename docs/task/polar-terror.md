# Polar Terror Reward Details

The supported 720x1280 Reward Details dialog has two layouts. Without Expert Bonus, the legacy reader
uses its existing lower-strip scan and bounded upward swipes. When Expert Bonus is active, its header is
at `(35,510)`–`(685,565)` and `Special Rewards (N left)` is initially at `(35,800)`–`(685,860)`; that
mapping must read before any swipe moves the header upward.

`Not defined` probes the Expert Bonus header once on the first Special Rewards dialog of a task run. A
confirmed header persists `Exists` per profile. An inconclusive probe is transient `Not confirmed` and
falls back to the legacy mapping. Operators can set `Exists` or `Missing` when the Expert lifecycle is
known. A terminal failed hunt action retains one workspace diagnostic frame and logs its retry decision.
