# Polar Terror Reward Details

The supported 720x1280 Reward Details dialog has two layouts. Without Expert Bonus, `Special Rewards
(N left)` is initially at `(35,510)`–`(685,565)`. When Expert Bonus is active, its header is at
`(35,510)`–`(685,565)` and `Special Rewards (N left)` is initially at `(35,800)`–`(685,860)`. Both
layouts are read directly without scrolling.

`Not defined` probes the Expert Bonus header once on the first Special Rewards dialog of a task run. A
confirmed header persists `Exists` per profile. An inconclusive probe is transient `Not confirmed` and
uses the no-Expert mapping. Operators can set `Exists` or `Missing` when the Expert lifecycle is known.

In Special Rewards mode, the first successful count read becomes an in-memory budget for that task visit.
Each confirmed rally consumes one unit; failed, cancelled, and contested attempts do not. A zero budget
defers to daily reset. Any later visit reads the game counter again. A terminal failed hunt action retains
one workspace diagnostic frame and logs its retry decision.
