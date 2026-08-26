# Life Essence Claim Detection

`LifeEssenceRoutine` claims the badges on the player's own My Island screen.
`LifeEssenceCaringRoutine` covers the separate alliance caring flow and is
unaffected by anything here.

Badges appear over the Life Tree and over each crafting station. Players place
their stations wherever they like and the tree does not always carry a badge, so
the number and position of badges vary per account and per visit. Nothing in the
detection is position-based: the island is scanned and whatever is found is
tapped.

Template matching is not usable for this screen. The badge bubbles bounce, so a
template cut from a live frame only self-matched at ~0.84 against a 0.90
threshold, and the badge over the tree is different artwork from the ones over
the stations.

## Three independent checks

A tap needs all of the following, because no one of them is sufficient.

**The right screen.** `onIslandScreen` requires the Life Essence counter in the
top bar, which is the same crystal artwork as the badges and appears on no other
screen the routine can reach. Without it the detector would work on whatever a
failed navigation left behind, and two unrelated decoys have already been caught
live doing exactly that: the world map's marching panel carries a 36x37 green
gathering icon at 0.46 fill, and the city view's Infirmary carries a 36x33 green
cross at 0.58 fill. Both pass every crystal rule. Tapping the first starts a
march; the second matters more because the city view is where the bot idles
between tasks. Across 60 live frames the counter was present on every island
frame and absent from every world and city frame.

**The right shape.** `IslandClaimBadges` accepts a `GameColors.isVividGreen`
blob 28-46 wide, 30-70 tall, at least 0.38 filled, of at least 600 pixels. From
live frames the crystal covers 913-1476 pixels in a 35-42 x 37-59 box, the
height varying because the bubble bounces. Green alone does not identify a
badge; the size rules do.

Width carries the identification and fill only backs it up. The island holds a
permanent 50x62 grass patch at 0.33 fill at (545,637) that is otherwise
badge-sized and present in every frame; width rejects it outright, which is what
frees the fill floor to sit low. That matters for the badge over the tree,
because the tutorial hand points at a claimable badge and covers part of the
crystal while doing so, dropping a real tree badge to 0.486 fill. The floor
belongs between the grass and that badge, not next to the badge: an earlier 0.45
left the tree badge clearing by 0.036 while the grass sat 0.12 below it.

**A readable island.** Both captures a scan compares must pass the screen check.
Claiming triggers a screen transition whose frame is nearly blank and carries no
blobs at all, so a capture landing on it looks exactly like an island with
nothing left to claim. On 2026-08-23 that reported a real tree badge as
"Claimed: 0". An island that cannot be read is unknown, never empty: the scan is
retried, up to `MAX_UNREADABLE_SCANS`, and a run that claims nothing after any
unreadable scan says so in the log rather than reporting a clean zero.

**Not moving.** Claiming sends a reward crystal flying from the badge up to the
counter. It is the same colour and size with no bubble around it, so a scan
landing mid-flight would count empty sky as a claim and tap it. Each scan
therefore compares two captures 700ms apart and keeps only blobs that held their
place: a badge bounces at most 8px in that interval, a reward crystal covers
roughly 30px.

## The search window

`CommonGameAreas.ISLAND_CLAIM_BADGE_AREA` is `(0,200)-(720,1100)`. Both edges
are load-bearing and neither should be widened.

The top edge sits below the HUD counter, which is the same crystal artwork,
passes every shape rule, and ends at y=54 on live frames - excluding it is the
only thing that stops the routine tapping the currency counter. Everything below
that and above the island is clear of green, so the window starts at y=100 and
keeps the rest as headroom for the badge over the tree.

That badge floats higher than any other and reaches y=323 on a level 9 tree. A
clipped crystal loses pixels and height until it falls out of the shape rules:
an earlier `y=340` edge cut it to 40x40 and it passed by luck. A taller tree on
another account puts the badge higher still, which is why the window starts as
high as the counter allows rather than just above the one tree measured here.

The bottom edge stops short of the chat and navigation bars.

## Calibration warning

Do not calibrate against the bundled `claim.png` and `claimPin.png` templates.
They are cut at a smaller scale than the game renders and measure 22-24 x 33, so
a window derived from them finds nothing on a real frame - which is exactly what
happened on the first attempt here.

## Counting

Claim counts are real counts. Each pass rescans, and a badge found again within
`SAME_BADGE_RADIUS` of one already tapped is logged as a warning rather than
counted a second time, so a screen where claiming silently fails reports zero
instead of a full total.

## Evidence

Saved real-frame verification, automated tests, and live account-log
confirmation. On 2026-08-23 the first run of this code claimed three badges -
two stations and the tree - and the account's essence counter moved from 42,955
to 44,012 in that run. The three preceding runs of the fixed-tap version had
reported "Claimed: 3" each while leaving the counter unchanged.

The unreadable-island fix is confirmed the same way. The 16:58 run logged
"Claimed: 1" against frames showing one badge before and none after, and the
counter moved 44,012 to 44,510. The run before it, on the unfixed build, had
reported "Claimed: 0" against frames showing that same badge on screen.

Unsupported: badges outside the search window, resolutions other than 720x1280,
and any island layout where two badges overlap closely enough to merge into one
blob.

Known risk, observed but not yet seen to cause a miss: the game sometimes opens
the Tree of Life panel over the island, whose Details/History/Upgrade/Collect
row and tutorial hand cover part of the lower island. A badge behind that panel
would be occluded. On the frame where this was seen the island had no badges, so
whether it can actually hide one is unconfirmed.
