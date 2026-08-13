# Stage 8-10 loss — full OCR (all 3 rounds)

Best-of-3, 2 squads/side, damage carries over. Enemy = "Bramble Champion" (heroes Lv22).
Our heroes Lv63–65. **Loss cost 1 attempt (5 → 4).**

## Troop counts & result (from summary + detail overviews)
| Round | Our squad (comp) | Result | Us troops end / start | Enemy end / start | Our power hit | Enemy power hit |
|---|---|---|---|---|---|---|
| 1 | Squad 2 (50 Inf/0 Lan/50 Mrk) | DEFEAT | 0 / 188,030 (wiped) | 92,526 / 167,775 | −12,409,980 | −4,966,434 |
| 2 | Squad 1 (60 Inf/40 Lan/0 Mrk) | VICTORY | 98,538 / 188,180 | 0 / 167,775 (wiped) | (win) | (wiped) |
| 3 | Squad 1 leftovers | DEFEAT | 0 / 98,538 (wiped) | 18,966 / 92,526 | −6,503,508 | −4,854,960 |

Kill-efficiency (their losses ÷ our losses): R1 = 0.40 (awful), R2 = 1.87 (great), R3 = 0.75.

## Troop-composition matchup per round (the KEY data)
| Round | OUR comp (Inf/Lan/Mrk) | ENEMY comp (Inf/Lan/Mrk) |
|---|---|---|
| 1 | 50.00 / 0 / 50.00 | **53.33 / 26.66 / 20.00** (enemy Squad A) |
| 2 | 60.00 / 40.00 / 0 | 53.33 / 26.66 / 20.00 (enemy Squad A) |
| 3 | 23.61 / 76.38 / 0 (depleted, Inf died tanking) | **15.38 / 48.35 / 36.26** (enemy Squad B — DIFFERENT!) |

→ **The enemy fields TWO different squads:** Squad A = Infantry-heavy (53/27/20), Squad B = Lancer/Marksman-heavy (15/48/36).

## Stat bonuses (constant across rounds) — US (red) vs ENEMY NPC (green)
| Stat | Us | Enemy |
|---|---|---|
| Infantry Attack / Defense | +88.5% | **+160.0%** |
| Infantry Lethality | +35.6% | +50.0% |
| Infantry Health | +63.1% | +50.0% |
| Lancer Attack / Defense | +118.3% | **+160.0%** |
| Lancer Lethality | +91.8% | +50.0% |
| Lancer Health | +47.9% | +50.0% |
| Marksman Attack / Defense | +93.0% | **+160.0%** |
| Marksman Lethality | +64.7% | +50.0% |
| Marksman Health | +33.9% | +50.0% |

→ Enemy has a flat **+160% Atk/Def on every troop type** vs our ~+88–118%. That's the baseline handicap (why Lv22 beats Lv65).

## Why we lost
1. **Baseline:** enemy NPC has ~2× our Atk/Def bonuses.
2. **Squad 2 (50/0/50) hard-countered by enemy Squad A:** their 27% Lancers eat our 50% Marksmen (Lancer›Marksman); we had 0 Lancer to hold their 53% Infantry → wiped at 0.40 efficiency, threw Round 1.
3. **Cascade:** losing R1 forced Squad 1 to fight R2 **and** R3; by R3 it was down to 98k depleted troops (and skewed to 76% Lancer after Infantry tanked losses) vs a fresh, differently-composed enemy Squad B → lost.

## Fix (applied)
Squad 2 changed **50/0/50 → 60/40/0** (mirrors Squad 1, which beat enemy Squad A at 1.87 eff).
60/40/0 counters BOTH enemy squads: Infantry›their Lancer, Lancer›their Marksman, no exposed Marksman.
Winnable: Squad 1's 60/40/0 alone out-kills the enemy's entire force — the loss was Squad 2 wasting a round.
