# Land of Heroes (Labyrinth) — Troop Ratio Research Brief

Researched 2026-08-10. Web search + WebFetch only, no game access. Reddit r/WhiteoutSurvival
searched multiple ways (site:reddit.com, direct query) — **no relevant thread was found/indexed**;
community consensus below comes from fan-site guides and search-engine AI-overview snippets, not
a verified Reddit post. YouTube video pages (see bottom) would not yield transcript text via
WebFetch (only nav/footer scraped) — flagging as unconfirmed, not fabricated.

## 0. Important discrepancy vs. the brief: squad count is 3, not 2

Two independent sources say Land of Heroes deploys **three** full squads (9 heroes total, 3 per
squad), not two:
- ["The Labyrinth in Whiteout Survival" — Out of Games](https://outof.games/realms/whiteoutsurvival/guides/468-the-labyrinth-in-whiteout-survival/): "you can deploy up to 3 armies... similar to Canyon Clash squad setup where you will be using up to 9 heroes, three for each army."
- [mone.gg Labyrinth guide](https://www.mone.gg/blog/whiteout-survival/labyrinth-guide.html) (via WebFetch extraction): "this stage allow[s] three complete marches with heroes," gives a distinct ratio for each of March 1/2/3.

Both are independent of each other and agree on 3 squads. **Flag this to matt/whiteout-labyrinth
before acting on a 2-squad assumption** — either the in-game UI has since changed to 2, or these
guides (and the ratio recommendations below, which are structured around 3 squads) are describing
an older/different version of the mode. Could not independently confirm which is current.

## 1. Zone identity confirmation

"Land of Heroes" = renamed "Land of the Brave" (per [BuffBuff](https://buffbuff.com/blog/whiteout-survival-labyrinth) search summary, dated to an April 2026 update). Confirmed by two independent
sources:
- [Whiteout Survival Wiki](https://www.whiteoutsurvival.wiki/events/the-labyrinth/): "Unlocked for — Monday, Tuesday of each week. Source of stats — Heroes, Hero Gear, Hero Exclusive Gear."
- [Out of Games](https://outof.games/realms/whiteoutsurvival/guides/468-the-labyrinth-in-whiteout-survival/): "Land of the Brave... Monday, Tuesday... Heroes, Hero Gear, Hero Exclusive Gear," and notes it's PvE (expedition stats/skills, not exploration stats) — "similar to solo Beast or Mercenary attacks rather than Arena-style combat," with "2v2 or 3v3" combat and turn-order RNG.

This matches your stated in-game rule ("only Hero/Hero Gear/Hero Exclusive Gear stats apply") —
cross-checked, high confidence.

## 2. Recommended per-squad ratios (3-squad structure)

**Caveat on source independence:** several of the numbers below trace back to what appears to be
one underlying article (whiteoutsurvivalhandbook.com's "Labyrinth Guide 2026") that a direct
WebFetch could not retrieve (JS-rendered page, only nav/footer scraped) but which repeatedly
surfaced through Google's AI-overview search snippets. Treat that as **one source**, not several,
even though it appeared in multiple search results. mone.gg is a genuinely separate, independently
fetched source and partially disagrees with it on exact numbers (see Squad 2).

| Squad | whiteoutsurvivalhandbook.com (via search snippet, unverified direct) | [mone.gg](https://www.mone.gg/blog/whiteout-survival/labyrinth-guide.html) (independently fetched) |
|---|---|---|
| 1 (frontline) | 60% Infantry / 40% Lancer / 0% Marksman | 60% Infantry / 40% Lancer / 0% Marksman |
| 2 (Marksman/hybrid) | 50% Infantry / 0% Lancer / 50% Marksman | 54% Infantry / 3% Lancer / 43% Marksman |
| 3 (flex/remaining) | 50% Infantry / 20% Lancer / 30% Marksman (or reuse 60/40/0) | 50% Infantry / 20% Lancer / 30% Marksman |

**Disagreement flagged:** Squad 2 numbers differ (50/0/50 vs 54/3/43) between the two sources —
same *shape* (infantry-marksman heavy, near-zero lancer, built around a Marksman-type hero) but
not identical numbers. Cross-checked on concept, not on exact digits.

**Squad 1 (60/40/0) is cross-checked across both sources identically** — higher confidence than
Squad 2/3 numbers.

### Rationale given (from mone.gg, fetched directly)
- Squad 1 = durable frontline: Infantry+Lancer, 0% Marksman, built to tank and absorb hits.
- Squad 2 = "Mia-Based Hybrid," built around Mia's ability bonuses, heavy Infantry+Marksman.
- Squad 3 = balanced remainder for whatever heroes are left over.
- Core strategic principle stated explicitly: **"most players do not have nine fully upgraded
  heroes with top-tier gear. Concentrating resources into stronger primary marches is much more
  effective"** — i.e., stack your best gear/heroes into Squads 1–2, let Squad 3 be the weak link.
- Hero names surfaced for Squad 1 candidates: **Greg** (survivability), **Lynn** (damage boosts),
  **Bradley** (late-game option).
- A companion snippet (same handbook-cluster source) adds a gear-reallocation micro-tactic: pull
  Hero Gear off **Greg** and **Mia** specifically and give it to your top carry heroes instead —
  use Greg/Mia ungeared for their base hero skill/kit, since their value is more about
  kit synergy than raw gear stats. This is a single-source tactic, not cross-checked elsewhere —
  treat as a "try it" idea, not confirmed optimal.

## 3. Independent alternative view (different reasoning, single source)

[Out of Games](https://outof.games/realms/whiteoutsurvival/guides/468-the-labyrinth-in-whiteout-survival/) gives a genuinely different framework — starts from the general WoS baseline **50/20/30**
(Infantry/Lancer/Marksman) but argues Land of Heroes specifically should skew *more infantry-heavy*
than that baseline:

> "since this zone involves 2v2 or 3v3 combat with turn order RNG, alternative ratios like 52/13/35
> or 60/12/28 are noted as potentially more effective, accounting for troops suffering losses
> across multiple rounds."

Reasoning: combat is multi-round PvE, troops die off round-by-round, so more frontline durability
(infantry) buys you more rounds of survival before your damage dealers get exposed. This is a
single, independent source — not cross-checked against the two-source table above, and it doesn't
break ratios out per-squad the way mone.gg/handbook do. Worth weighing but flag as single-source.

## 4. General WoS troop-role doctrine (why Infantry/Lancer/Marksman split matters at all)

Cross-checked across multiple general troop guides (not Labyrinth-specific, but underlies all the
above reasoning): [TopUpLive](https://www.topuplive.com/news/whiteout-survival-troops-guide.html), [BlueStacks](https://www.bluestacks.com/blog/game-guides/white-out-survival/wos-troops-guide-en.html), [A Jack Of](https://www.ajackof.com/games/whiteout-survival-wos/whiteout-survival-best-troop-formation-ratio/), [One Chilled Gamer](https://onechilledgamer.com/whiteout-survival-troop-guide/):

- **Infantry** = frontline tank/meatshield — high DEF/HP, absorbs the brunt of incoming damage,
  shields troops behind it.
- **Lancer** = midline — deals damage while somewhat protected, primarily targets enemy Infantry
  and Marksman.
- **Marksman** = backline glass-cannon damage dealer — relies on Infantry to soak damage while it
  unloads ranged DPS; low survivability.
- Rock-paper-scissors counter cycle described as **Infantry → Lancer → Marksman → Infantry**.
- General-purpose "most stable" baseline ratio cited repeatedly across sites: **50% Infantry / 20%
  Lancer / 30% Marksman** — this is the same number Land of Heroes Squad 3 lands on, suggesting
  Squad 3 is just "use the generic safe default" rather than a zone-tuned number.

## 5. How ratio should track the hero lineup (answering your Q3)

Consistent pattern across the sources above: **the squad's troop ratio should mirror its lead
hero's type**, i.e.
- A squad led by your best Infantry + best Lancer heroes → skew Infantry/Lancer heavy, drop
  Marksman to 0 (Squad 1 pattern, 60/40/0).
- A squad led by a Marksman hero (or Mia-type hybrid) → skew Infantry/Marksman heavy, drop Lancer
  to near-0 (Squad 2 pattern, 50/0/50 or 54/3/43).
- Leftover/weaker squad → fall back to the generic balanced 50/20/30.

This is consistent across both cross-checked sources (handbook-cluster and mone.gg) and matches
the general troop-role doctrine in section 4 — reasonably high confidence on the *pattern*, medium
confidence on the *exact numbers* per squad.

## 6. What could not be confirmed

- **No Reddit thread found** despite several targeted searches (site:reddit.com, direct r/
  queries). Community consensus above is fan-site/guide-site sourced, not verified against
  Reddit discussion.
- **No YouTube transcript extracted.** Candidate videos surfaced by search but WebFetch only
  returned page chrome (nav/footer), not transcript/description text:
  - ["Whiteout Survival Labyrinth Guide 2026 | Best Troop Ratios, March Setup & Shop Priority"](https://www.youtube.com/watch?v=HJTJ5UE57Cs)
  - ["Master the Labyrinth – Best Hero & Troop Ratio + Power Synergy Explained!"](https://www.youtube.com/watch?v=6CNXaijCkAs)
  - ["Best Hero Setup for Labyrinth! Land of the Brave Guide"](https://www.youtube.com/shorts/poceXu_QlVM) (Short)
  View counts/upload dates not visible through this fetch method — if precise video-sourced
  numbers matter, these need a direct browser/YouTube-API check rather than WebFetch.
- Could not confirm whether the true current squad count is 2 (per your brief) or 3 (per two
  guide sources) — this is the single most important thing to resolve before applying any of
  the ratio tables above, since the whole squad-1/2/3 structure assumes 3.
- [whiteoutsurvivalhandbook.com](https://whiteoutsurvivalhandbook.com/guides/whiteoutsurvival-labyrinth-troop-ratio-guide-2026) is JS-rendered and could not be fetched directly — all its
  numbers here came secondhand via search-engine AI-overview snippets, not a verified page read.
