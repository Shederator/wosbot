# Task interval defaults

These defaults apply to new or missing profile settings. Frostguard preserves
explicitly saved values, including older 60-minute settings.

| Setting | Default | Decision |
|---|---:|---|
| Alliance Chests | 240 min | Gifts can arrive throughout the day, but opening all chest tabs hourly is unnecessary. Four hours gives six claim passes per day. This is an operational default, not a documented game cooldown. |
| Alliance Tech | 200 min | One contribution attempt regenerates every 10 minutes and up to 25 stack. Running after 20 regenerated attempts leaves a five-attempt buffer before the cap without opening the full Tech flow for every single attempt. |
| Alliance Triumph | 240 min | The daily chest unlocks at 800 points earned through Intel rewards and the last two Daily Mission chests. Four-hour checks follow meaningful progress windows without hourly polling; claimed rewards already reschedule to reset. |
| Accept New Survivors | 360 min | Survivor replacement is a slow recovery flow reported on the order of a day. Six-hour checks avoid hourly polling while limiting how long newly available workers remain unassigned. |
| Daily Missions | 720 min | Two passes per day are sufficient for accumulating daily progress; the routine retains its final pre-reset check. Auto-schedule mode is unchanged. |
| Mail Rewards | 720 min | Reward mail is not an hourly cooldown and mail exposes its own expiration. Two daily passes balance prompt collection with task cost. |
| Personal Life Essence | 360 min | Six-hour collection avoids hourly island navigation while retaining four collection opportunities per day. No exact public storage cap was found, so this remains a conservative operational default. |
| Exploration Chest | 360 min | Community observations put idle-income storage at seven hours. Six hours stays below that cap with one hour of scheduling margin. |
| Alliance Life Essence retry | 60 min | Retained. The routine searches for other members' currently available islands until its three daily assists are used, then schedules directly at reset. |

Sources checked in August 2026:

- [Alliance Tech attempts regenerate every 10 minutes](https://medievalfun.com/white-out-survival-alliances-guide/)
- [Alliance Triumph thresholds and point sources](https://outof.games/realms/whiteoutsurvival/guides/476-alliance-activity-triumph-in-whiteout-survival/)
- [Exploration idle income has a seven-hour cap](https://www.reddit.com/r/whiteoutsurvival/comments/1ecvtcc/when_to_collect_idle_exploration_income/)
- [Daybreak Island permits three daily alliance assists](https://whiteoutsurvival.pl/guides/daybreak-island-how-to-develop-quickly-guide/)
- [Reward mail displays its own expiration](https://www.reddit.com/r/whiteoutsurvival/comments/1f343bj/question_about_ingame_mail/)
- [New survivors may take roughly a day to return](https://www.reddit.com/r/whiteoutsurvival/comments/17n42gf/survivors/)

Hard-coded timers were reviewed separately. Detected build, research, march,
pet-skill, storehouse, and event timers remain authoritative. Short retry and
navigation backoffs remain technical failure policy rather than gameplay
defaults. Fixed event anchors such as daily reset, weekly reset, Intel windows,
and the 48-hour Bear Trap cycle were not changed.
