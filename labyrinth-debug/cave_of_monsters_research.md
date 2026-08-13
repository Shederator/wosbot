# Cave of Monsters (Labyrinth) — Community Research Brief
Compiled 2026-08-11. Sources: fan wikis, guide aggregator sites, official WOS_Global social post. No
Reddit or YouTube transcript content was actually retrievable this session (see Gaps section) — flagged
explicitly rather than fabricated.

---

## 1. Combat mechanics — confirmed vs uncertain

**Confirmed, cross-checked across 4+ independent sources** (whiteoutsurvival.wiki, ldplayer.net,
theriagames.com, heaven-guardian.com, outof.games, wosguru.com all agree):
- Cave of Monsters is **pure pet-stat combat**. In-game text matches what you already have: "Only the
  stats of pets take effect here, and pet skills are automatically effective."
- Pet skills fire automatically on entry — no manual pre-activation, no cooldown-wasting risk (unlike the
  overworld where you'd hand-trigger a pet skill and burn its timer).
- You fight standardized **Lv.10 "Labyrinth Explorer" troops** as the enemy baseline — i.e. the opposing
  troops are NOT your real rivals' armies, they're a fixed NPC baseline. This is stated for the Labyrinth
  generally (outof.games, theriagames) and is consistent with a zone designed to isolate one account
  subsystem (pets) as the only variable. [outof.games](https://outof.games/realms/whiteoutsurvival/guides/468-the-labyrinth-in-whiteout-survival/), [theriagames.com](https://theriagames.com/guide/whiteout-survival-the-labyrinth/)

**No rock-paper-scissors element/type counter system for pets** — cross-checked across 2 independent
sources (a general WOS pet-type search summary via wostools/BlueStacks-adjacent results, and
[vortexgaming.io's pet guide](https://vortexgaming.io/en/postdetail/648133)). The Infantry/Lancer/Marksman
counter triangle (~+10% bonus attack to the countered class) is a **troop/hero-class mechanic only** and
does not apply to pets. Pets are categorized instead by **rarity tier** (Common/N up through
Legendary/SSR — one source counts 5 rarity tiers, 14 total pets) and by **skill function** (Growth/utility
skills like construction speed, gathering, stamina restore vs. Combat skills like troop attack/defense/
lethality/health/enemy-debuff). Confidence: reasonably solid, but I could not find a source that explicitly
addresses "does Cave of Monsters specifically use any counter mechanic" — this is inferred from "pets
don't have types with counters" generally, not a zone-specific statement. Flag as **inferred, not directly
confirmed for this zone**.

**Which pet attributes matter in Cave of Monsters specifically**: no source breaks out zone-specific
weighting (e.g. "attack matters 2x defense here"). General pet-stat guides (BlueStacks tier list,
whiteout-survival.com tier list) rank **Attack > Health/Lethality > Defense** for combat value overall,
reasoning that attack scales damage directly and defense is considered the weakest multiplier vs. health.
This is a general-combat-pet ranking, not confirmed as Cave-of-Monsters-specific. **Single-source-family
claim, treat as directional not authoritative for this exact zone.**

---

## 2. Setup / deployment

**Confirmed (moderate confidence, 3+ sources agree on the broad strokes):**
- Zone is pet-only; no hero stats apply (explicit in-game text you already have, and consistent across
  every guide).
- Enemy is the fixed Lv.10 Labyrinth Explorer troop baseline, not real troops.

**NOT confirmed / gap in available community documentation** — I searched specifically for pet count per
deployment, formation/slot mechanics, and whether any troop composition choice exists in this zone, and
could not find a source that states it explicitly:
- How many pets you select/bring into a Cave of Monsters battle (all owned pets? a subset?) — unconfirmed.
- Whether there's a "formation" screen for pets akin to Land of Heroes' hero formation — unconfirmed.
- One aggregator (mone.gg) made a passing, vague statement that Cave of Monsters is "related to pets and
  Chief Charms" and that Labyrinth stages generally split into "single march without heroes" vs "three
  full marches with heroes" — but this reads as a general Labyrinth-mechanics note bleeding across zones,
  not a specific, reliable Cave-of-Monsters statement, and it's the **only** source claiming a Chief
  Charms tie-in (every other source says Cave of Monsters = Pets/Pet Skills only, and Chief Charms is
  its own separate zone, "Charm Mine"). **Sources disagree here — flagging explicitly.** Treat mone.gg's
  Chief-Charms claim as likely an extraction error, not a confirmed mechanic.

> **CORRECTION (see Troop Ratio section below, added 2026-08-11 follow-up):** the "no troop composition
> choice" conclusion above is now known to be WRONG based on a live in-game observation — the Challenge
> screen does show an Infantry/Lancer/Marksman ratio slider identical to Land of Heroes, and troops (fixed
> "Apex" max-tier, 250,000 cap each) ARE deployed. Only heroes are excluded; pets are the stat buff layer
> on top of a real troop-ratio choice. Section 2 above reflects last session's (incomplete) picture — see
> below for the corrected mechanic and what community guides say about the ratio itself.

Bottom line: I could not verify pet-selection/formation mechanics for this zone from public guides. This
would need an in-game screenshot check or a Reddit/Discord thread I wasn't able to reach this session.

---

## 3. Best pets — community consensus (general combat tier lists, not Cave-of-Monsters-specific)

No source produced a Cave-of-Monsters-specific tier list. What exists is a **general combat-pet tier
list** that multiple aggregator sites converge on (BlueStacks, whiteout-survival.com, ldcloud.net, u7buy
all overlap heavily — this is cross-checked, 3-4 sources agree on the same names, though exact tier
letters (S vs A) shift slightly between sites, which is the kind of "one-level disagreement" pattern you
flagged happening before):

**Top combat pets (S/A tier consensus):**
- **Cave Lion** — "Feral Anthem," +10% all-troop Attack. Ranked S-tier by whiteout-survival.com and
  BlueStacks alike.
- **Saber-tooth Tiger** — "Apex Assault," +10% Troop Lethality (converts wounds to permanent kills).
  S-tier on whiteout-survival.com; S-tier on BlueStacks too.
- **Frost Gorilla** — +10% Troop Health. S-tier on whiteout-survival.com.
- **Snow Ape** — +squad/march capacity (up to 15,000). A-tier consensus.
- **Mammoth** — +10% Troop Defense. A-tier consensus, though ranked lowest-value stat by some (defense
  seen as weaker than health/attack for scaling).
- **Frostscale Chameleon** — -10% enemy Troop Defense, "endgame" pet per BlueStacks, S-tier there.
- **Iron Rhino** — +150,000 rally capacity. Valuable for rally leaders specifically, ranked lower
  (B-tier) by whiteout-survival.com since it's situational, but S-tier by BlueStacks for that same
  rally-leader use case. **This is a disagreement between sources** — likely reflects different framing
  (general value vs. rally-leader value) rather than a factual conflict.
- **Snow Leopard** — +30% march speed, -5% enemy Troop Lethality. A-tier, both sources agree.

**Early/utility pets** (Cave Hyena, Musk Ox, Arctic Wolf, Giant Tapir) are consistently ranked
low-combat-value / early-economy pets, not relevant to Cave of Monsters combat power — 3+ sources agree.

**Caveat:** none of this is stated as Cave-of-Monsters-specific optimization; it's general "which pets to
invest Pet Food/materials into" advice. Since Cave of Monsters is explicitly pet-STATS + pet-SKILLS only,
these army-wide-buff combat pets (attack/defense/health/lethality debuffs/buffs) are the logical carryover,
but no guide explicitly says "these are the best Cave of Monsters pets" by name.

---

## 4. How to climb stages — levers, ranked by what's actually sourced

Cross-checked across allclash.com and BlueStacks (2 independent sources agreeing on structure):

1. **Get every pet to Level 10 first** — this unlocks each pet's Talent Skill. Baseline requirement
   before deeper investment pays off. (allclash.com, BlueStacks)
2. **Push your active combat pets (Cave Lion, Saber-tooth Tiger, Snow Ape, Iron Rhino, etc.) to Level
   20+** rather than spreading Pet Food evenly — mid-game pivot from utility pets to combat pets.
   (allclash.com)
3. **Wild Marks (stat refinement)** — separate from leveling, these push individual stat lines
   (Common→Uncommon→Rare→Epic→Legendary quality). Guidance: use cheap Common Wild Marks broadly up to
   Purple/Epic quality on everything, then save rare Advanced Wild Marks specifically for your main
   combat pets to push to Gold/Legendary. (allclash.com)
4. **Resource discipline**: resetting a pet's level refunds Pet Food but never returns advancement
   materials or Wild Marks — don't experiment carelessly, materials are the scarce resource, pet food is
   not. (allclash.com)
5. Cave of Monsters itself is called out as a specific reward source for **pet food**, meaning it's
   partly self-funding — clearing further stages nets the material you need to clear even further ones.
   (ldplayer.net, whiteoutsurvival.wiki via mone.gg-style framing)

**Not sourced / uncertain:** no guide explicitly ranks "pet skill level" vs "pet star/rank" vs "raw pet
level" by ROI for Cave of Monsters climbing specifically. The above is the best available general pet
progression logic, not a zone-tuned priority list.

---

## 5. Stage structure

**Confirmed, 2 independent sources agree:**
- **10 stages per zone** (theriagames.com, heaven-guardian.com both state clearing stages 1-10 unlocks
  the "raid" auto-farm feature for that zone).
- **5 challenge attempts per zone per day** — this is stated repeatedly and consistently across nearly
  every source touched (whiteoutsurvival.wiki, theriagames.com, heaven-guardian.com, outof.games,
  buffbuff.com). High confidence, essentially unanimous.
- Weekly cycle resets Monday (heaven-guardian.com); Cave of Monsters itself is only open **Wednesday and
  Thursday** each week (unanimous across every source, and matches the official WOS_Global account: a
  November 2025 tweet listing all six Labyrinth zones and their open days).

**Not confirmed:** whether an individual stage attempt is a single battle or a best-of-3 round format like
Land of Heroes. No source I could reach stated this explicitly for Cave of Monsters (or for Labyrinth
zones generally). This needs an in-game check or a Reddit thread — I could not fetch reddit.com directly
this session (blocked/tool limitation), and general web search did not surface a specific r/whiteoutsurvival
thread on this question. **Genuine gap — do not assume best-of-3 carries over from Land of Heroes.**

---

## Gaps / things I could not confirm this session (be upfront with the strategist agent)

- **Reddit**: `WebFetch` could not reach reddit.com directly ("unable to fetch"), and web search did not
  surface specific r/whiteoutsurvival threads on Cave of Monsters pet strategy. No Reddit-sourced claims
  in this brief — everything is fan-wiki/aggregator sourced.
- **YouTube transcripts**: Located several plausibly relevant videos by title (e.g. "Whiteout Survival -
  The Labyrinth | The Cave of Monsters", "STOP LOSING! Whiteout Survival Labyrinth Guide (Secret
  Formations You NEED to Know)", "Complete Guide & Tips on Cave of Monsters") but `WebFetch` against
  youtube.com only returned page chrome/footer, not transcript or description text, for every video tried.
  Could not extract view counts, upload dates, or actual spoken content. **No YouTube claims in this
  brief** — flagging rather than guessing at what those videos say.
- **Pet count/formation on deployment** and **single-battle-vs-best-of-3** are both unconfirmed — see
  sections 2 and 5.
- **mone.gg's "Chief Charms" mention for Cave of Monsters** conflicts with every other source (which say
  Pets/Pet Skills only) — treat as likely error, not fact.
- **Iron Rhino tier placement** disagrees between whiteout-survival.com (B-tier, situational) and
  BlueStacks (S-tier) — likely a framing difference (rally-leader value vs. general value), not corrected.

---

## Sources used
- [whiteoutsurvival.wiki — The Labyrinth](https://www.whiteoutsurvival.wiki/events/the-labyrinth/)
- [ldplayer.net — Pet Skills & Pet Effects In Labyrinth Battles](https://www.ldplayer.net/blog/whiteout-survival-pet-skills-and-pet-effects-in-labyrinth-battles.html)
- [theriagames.com — The Labyrinth Guide](https://theriagames.com/guide/whiteout-survival-the-labyrinth/)
- [heaven-guardian.com — Labyrinth Guide & Rewards](https://heaven-guardian.com/whiteout-survival-labyrinth-guide/)
- [outof.games — The Labyrinth in Whiteout Survival](https://outof.games/realms/whiteoutsurvival/guides/468-the-labyrinth-in-whiteout-survival/)
- [wosguru.com — The Labyrinth](https://wosguru.com/labyrinth)
- [buffbuff.com — Labyrinth Guide: Zones, Rewards, and Best Strategies](https://buffbuff.com/blog/whiteout-survival-labyrinth)
- [mone.gg — Labyrinth Guide: Best Troop Ratios, March Setups, and Shop Priorities](https://www.mone.gg/blog/whiteout-survival/labyrinth-guide.html) (conflicting/low-confidence claim flagged)
- [x.com/WOS_Global status (official account, Labyrinth zone list)](https://x.com/WOS_Global/status/1866815196079198645) (fetch blocked by paywall this session, cited via search snippet only)
- [BlueStacks — Whiteout Survival Tier List for the Best Pets](https://www.bluestacks.com/blog/game-guides/white-out-survival/wos-pets-guide-en.html)
- [whiteout-survival.com — Pets Tier List](https://whiteout-survival.com/guide/pets-tier-list/)
- [vortexgaming.io — Ultimate Pet Guide](https://vortexgaming.io/en/postdetail/648133)
- [allclash.com — Pet Priority in Whiteout Survival](https://www.allclash.com/pet-priority-in-whiteout-survival/) (fetch failed directly; cross-checked via search snippet)

---

## TROOP RATIO — follow-up research, 2026-08-11 (session 2)

**Live in-game finding this correction is based on (matt's report, not a guide claim):** the Cave of
Monsters Challenge screen shows Apex Infantry/Lancer/Marksman with a Balance-ratio slider, Equalize/
Balance/Deploy buttons — identical UI to Land of Heroes. Troops are issued as standardized max "Apex"
units (250,000 cap each), so troop tier/training is normalized and irrelevant. An in-game banner states
heroes cannot be deployed here and only Pet stats are effective. This means combat = your chosen troop
ratio, buffed by pet stats — not pet-only as Section 1/2 above (last session) concluded. That earlier
conclusion is superseded for the ratio question specifically.

### 1. Best troop ratio (Infantry/Lancer/Marksman) — what guides actually say

No two sources give the identical number, but there is a real convergent **pattern**: heavy Infantry,
minimal Lancer, moderate-to-heavy Marksman. Nobody recommends a Lancer-heavy or perfectly balanced
33/33/33 split as optimal.

| Source | Ratio (Inf/Lan/Mrk) | Notes |
|---|---|---|
| [outof.games](https://outof.games/realms/whiteoutsurvival/guides/468-the-labyrinth-in-whiteout-survival/) | **50/10/40 or 60/5/35** | Most directly on-point: explicitly describes Cave of Monsters (grouped with Glowstone Mine, Earthlab, Dark Forge) as a "1v1, no-Heroes" fight where "tweaking troop ratio shows the result almost immediately... because often times the fight is decided" quickly — this matches your live finding of the ratio slider almost exactly. Distinguishes this from 2v2/3v3 zones (Land of the Brave, Gaia Heart) which the same guide says want 52/13/35 or 60/12/28 instead. |
| [topuplive.com](https://www.topuplive.com/news/whiteout-survival-troops-guide.html) | **45/15/40** | Labeled "damage-oriented" for Cave of Monsters specifically. |
| [mone.gg](https://www.mone.gg/blog/whiteout-survival/labyrinth-guide.html) | **45/10/45** | Labeled "good balance between durability and damage output" for Cave of Monsters specifically. |
| [ajackof.com](https://www.ajackof.com/games/whiteout-survival-wos/whiteout-survival-best-troop-formation-ratio/) | 60/15/25 | **Not** Cave-of-Monsters-specific — this is ajackof's general Labyrinth ratio; direct fetch of the page found no Cave-of-Monsters-specific number despite an earlier search-engine AI summary attributing "50/15/35" to Cave of Monsters citing this cluster of sites. That 50/15/35 figure could not be verified against any source's actual text and should be treated as a **search-summarizer artifact, not a real citation** — do not use it. |
| [wos.h5joy-games.com](https://wos.h5joy-games.com/guides/the-labyrinth-guide/) | 55/17/28 or 45/20/35 | General "single squad" Labyrinth baseline, not Cave-of-Monsters-specific; explicitly says to fine-tune per zone's stat buffs. |
| [theriagames.com](https://theriagames.com/guide/whiteout-survival-the-labyrinth/) | 33/33/33 (stated default) or 50/20/30 (general attacking comp) | Neither number is stated as Cave-of-Monsters-specific. |

**Confidence: cross-checked directional consensus, not a single agreed-upon number.** Three independent,
zone-specific citations (outof.games, topuplive, mone.gg) all land in the same neighborhood — roughly
**45-60% Infantry / 5-15% Lancer / 35-45% Marksman** — which is a real, non-trivial agreement given they're
different sites. If you want one number to start from, **50/10/40** (outof.games, most mechanically
on-point given it independently and correctly describes the exact "1v1 no-hero, fast-feedback" mechanic
you observed) is the best-supported single answer. Treat all of these as starting points to be tuned by
trial (5 attempts/day) against your own pet stat profile, not hard rules — every guide that gives a number
also says to adjust from there.

### 2. Do the monsters have a troop TYPE to counter, and does composition vary per stage?

**Partially confirmed, single-source depth (outof.games), not independently cross-checked for the exact
numbers — but internally consistent with everything else found:**

- outof.games states the AI enemy in these 1v1 no-hero Labyrinth zones (grouped with Cave of Monsters)
  runs on the **same Infantry/Lancer/Marksman troop-type engine as the player**, not typeless monsters.
  Exact quote: *"The AI is using a default troop ratio of 33/33/33 Infantry/Lancer/Marksman for every
  stage except the 10th where it uses ~53/27/20."*
- This means: **enemy composition is fixed per stage number** (not per-player-random), and it **does
  vary** — balanced 33/33/33 on stages 1-9, then a sharp swing to **Infantry-heavy (~53% Inf/27% Lan/20%
  Mrk) on stage 10.**
- I could not find a second independent source confirming these exact percentages — this is currently a
  **single-source claim**, though a second web-search AI summary surfaced the same 53/27/20 stage-10 figure,
  it's unclear whether that's an independent source or just re-surfacing outof.games' own content, so I'm
  not counting it as true cross-verification.
- **Could not confirm whether the game visually shows you the enemy's troop composition before you fight**
  (the way Land of Heroes reportedly does) — no source addressed this directly for Cave of Monsters. If
  outof.games derived their 33/33/33 / 53/27/20 numbers from on-screen display, they didn't say so; it
  reads like empirical testing/battle-report deduction rather than a stated UI feature. **Flag as
  unconfirmed** — worth checking in-game directly (does the Challenge screen show an enemy-side ratio, not
  just your own slider?).

### 3. The counter itself, and a caveat on directionality (sources disagree)

**The RPS cycle direction is genuinely disputed across guides — flagging this explicitly per your
instruction, because it changes which class you'd bring to counter an Infantry-heavy stage 10.**

- [wostools.net (dedicated troop wiki)](https://wostools.net/wiki/troops/lancers) states: Lancers are
  "Strong Against Marksmen (+10% Attack)" and "Weak To Infantry" — i.e. **Infantry beats Lancer, Lancer
  beats Marksman, Marksman beats Infantry.** This matches [ajackof.com](https://www.ajackof.com/games/whiteout-survival-wos/whiteout-survival-best-troop-formation-ratio/)'s
  independent statement of the same cycle direction.
- [topuplive.com](https://www.topuplive.com/news/whiteout-survival-troops-guide.html) states the
  **opposite** direction: "Infantry counters Marksman, Lancer counters Infantry, Marksman counters Lancer."
- Two independent sources (wostools.net's dedicated troop-skill wiki, backed by a specific in-game skill
  quote — Lancer's "Ambusher" skill giving "20% chance to strike Marksmen" — plus ajackof.com) agree on
  **Infantry > Lancer > Marksman > Infantry**. Only one source (topuplive) gives the reverse. **Lean toward
  the wostools/ajackof direction (Infantry beats Lancer, Lancer beats Marksman, Marksman beats Infantry)
  as more likely correct** since it's corroborated by a cited in-game skill effect, not just asserted, but
  this is not unanimous — treat as 2-vs-1, not fully settled.
- **Applying this to stage 10** (if the wostools/ajackof direction and the outof.games stage-10 enemy
  ratio are both correct): stage 10's enemy is Infantry-heavy, and **Marksman beats Infantry**, so a
  Marksman-heavy player ratio should be the counter specifically for stage 10. This lines up neatly with
  every Cave-of-Monsters-specific guide number found above (all of which lean Marksman-heavy relative to
  Lancer) — but I want to be honest that no guide explicitly states "bring more Marksman because stage 10
  is Infantry-heavy," this is my synthesis connecting two separately-sourced facts, not a direct quote.
  Flag as **inference, not a directly sourced claim.**
- On stages 1-9 (enemy roughly balanced 33/33/33 per the same single source), the RPS counter matters far
  less since no single enemy class dominates — this favors treating ratio choice on those stages more as
  "maximize your own class balance of tankiness vs damage" than "counter a specific enemy type," which is
  consistent with why guides converge on a durability+damage split (Infantry front line, Marksman damage,
  minimal Lancer) rather than a hard counter-pick.

### 4. Concrete ratio recommendations for higher/harder stages

- No guide gives a stage-by-stage ratio table (e.g. "use X on stage 7, Y on stage 10"). The only
  stage-specific number found is the **enemy's** stage-10 composition (53/27/20 Inf/Lan/Mrk, outof.games,
  single source) — not a player-ratio recommendation tied to stage number.
- Best-supported general starting ratio for the zone as a whole: **50/10/40** (outof.games) to
  **45/15/40** (topuplive) to **45/10/45** (mone.gg) — pick one, then use your 5 daily attempts to nudge
  Infantry up if your line breaks too fast, or Marksman up if damage is the bottleneck (this tuning
  heuristic itself is stated generically by ajackof.com and topuplive, not Cave-of-Monsters-specific, but
  it's standard advice across every troop-ratio guide found).
- If stage 10 specifically is the wall: the inference in section 3 above suggests **leaning further
  Marksman-heavy** (e.g. toward mone.gg's 45/10/45 or beyond) for that one stage, since Marksman
  reportedly counters the Infantry-heavy stage-10 enemy — again, flagged as inference, not a sourced
  stage-10-specific recommendation.

### 5. True win levers for Cave of Monsters, in priority order (restated per corrected mechanic)

No single source states this priority order explicitly — this is a synthesis of confirmed facts from
both research sessions, each piece individually sourced above, ordered by what's actually adjustable and
how directly it's been shown to matter:

1. **Pet stats (level, refinement/Wild Marks quality, skill tier)** — the explicit in-game banner text
   says only pet stats are effective; this is the one lever with no ceiling shared with other players
   (troops are normalized to identical max "Apex" 250k-cap units for everyone). This is the primary
   differentiator, per your live finding plus every general pet-progression source in the original
   research (Section 3/4 above: Attack > Health/Lethality > Defense general pet-stat ranking; Level 10
   pets first, then push combat pets to 20+, then Wild Marks refinement — allclash.com/BlueStacks).
2. **Troop ratio (Infantry/Lancer/Marksman split)** — troop *quantity/tier* is fully normalized and
   irrelevant (both sides get max Apex troops), but *ratio* is a real, player-controlled choice per your
   live finding, and three independent zone-specific guides (outof.games, topuplive, mone.gg) converge on
   a directional recommendation (~45-60% Infantry / 5-15% Lancer / 35-45% Marksman). This is the second
   lever and the one with concrete, sourced numbers to act on immediately.
3. **RPS counter-tuning against the specific stage's enemy composition** — lower confidence/more
   speculative: enemy composition is reportedly fixed and mostly balanced (33/33/33) except stage 10
   (Infantry-heavy, ~53/27/20), single-sourced (outof.games). If true, nudging Marksman up specifically
   for stage 10 is the highest-leverage single adjustment for that stage, per the RPS direction
   corroborated by wostools.net/ajackof.com (Marksman beats Infantry). Treat this as the most useful
   actionable idea in this brief but the least independently verified — worth testing empirically with the
   5 daily attempts rather than assuming.
4. **Heroes and troop tier/training — confirmed NOT a lever here.** Heroes are explicitly banned (banner
   text). Troop tier/training level does not matter since deployment is normalized max-tier "Apex" troops
   regardless of your actual barracks progress.

### Sources newly used this follow-up
- [outof.games — The Labyrinth in Whiteout Survival](https://outof.games/realms/whiteoutsurvival/guides/468-the-labyrinth-in-whiteout-survival/) (most load-bearing source this session — Cave-of-Monsters-specific ratio + enemy-composition claims)
- [topuplive.com — Whiteout Survival Troops Guide](https://www.topuplive.com/news/whiteout-survival-troops-guide.html)
- [mone.gg — Labyrinth Guide: Best Troop Ratios, March Setups, and Shop Priorities](https://www.mone.gg/blog/whiteout-survival/labyrinth-guide.html)
- [ajackof.com — Whiteout Survival Best Troop Formation & Ratio](https://www.ajackof.com/games/whiteout-survival-wos/whiteout-survival-best-troop-formation-ratio/)
- [wos.h5joy-games.com — The Labyrinth 2026: Troop Ratios & Stage Strategy](https://wos.h5joy-games.com/guides/the-labyrinth-guide/)
- [theriagames.com — The Labyrinth Guide](https://theriagames.com/guide/whiteout-survival-the-labyrinth/)
- [wostools.net — Lancers Troop Guide](https://wostools.net/wiki/troops/lancers)
- Attempted and could not use: `wosguru.com/labyrinth` and `whiteoutsurvival-community.com` (both HTTP 403
  on fetch this session); YouTube video transcripts (2 Cave-of-Monsters-titled videos and 2 general
  Labyrinth-ratio videos located by search, but `WebFetch` returned only page chrome/footer for all of
  them, no transcript or description text extractable — same limitation as the original session). No
  Reddit thread specifically on Cave of Monsters troop ratio was surfaced by search this session either.
