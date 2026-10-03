# Recorded Bear UI regression fixtures

Source: manual play, 2026-10-03 UTC, 720×1280. No bot input was sent. These PNGs are
exact decoded frames with irreversible privacy masks, not generated or reconstructed UI.
Indices below are one-based within each original H.264 segment. The original recordings
remain private; filenames intentionally contain no account identity.

| Fixture | Observation | Segment / decoded frame | Expected screen |
|---|---:|---|---|
| world-active | 357 | 5 / 1004 | WORLD_ACTIVE_BEAR_ICON_READY |
| rally-timer | 364 | 6 / 16 | RALLY_TIMER_PANEL |
| formation | 366 | 6 / 29 | FORMATION |
| war-list | 374 | 6 / 115 | WAR_LIST |
| capacity | 878 | 11 / 803 | DEPLOY_CONFIRMATION |
| rally-detail | 895 | 11 / 929 | UNKNOWN, never Special Buildings or War list |
| march-queue | 979 | 12 / 758 | MARCH_QUEUE_FULL (modal identity, not proof of actual occupancy) |
| territory | 1032 | 13 / 458 | ALLIANCE_TERRITORY, never Pets |
| trap-status | 1033 | 13 / 485 | SPECIAL_BUILDINGS; Trap 1 active, Trap 2 cooldown |
| event-ended | 1669 | 20 / 659 | WORLD despite generic rally indicator remaining |
| settings | 1688 | 20 / 897 | UNKNOWN, never Special Buildings or War list |
| pet-panel | 15 | 1 / 258 | PET_SKILL_PANEL |
| pet-selected | decoded between observations | 1 / 480 | PET_BATTLE_SELECTED |
| pet-confirmation | decoded between observations | 1 / 490 | PET_CONFIRMATION: use all available Battle Skills |
| pet-pending | decoded between observations | 1 / 505 | PET_BATTLE_SELECTED, not activation success |
| pet-active | 24 | 1 / 512 | PET_BATTLE_ACTIVE |
| pet-growth | 25 | 1 / 537 | PET_SKILL_PANEL, not Battle-Skills confirmation |
| bear-centered | 1053 | 13 / 788 | WORLD_AT_CONFIGURED_BEAR only for configured Trap 1 |
| bear-panel | decoded frame | 6 / 1 | BEAR_RALLY_PANEL only for configured Trap 1 |

World frames were processed by `tools/privacy-redactor/CityLabelRedactor.java`, then visually
reviewed. Other frames use opaque masks over leader/account identifiers and coordinates;
dialog backgrounds and formation notification banner are masked where appropriate. Masks do
not cover the page headings, navigation buttons, selected tabs, trap status or expected controls.
Unrelated world players may remain, as permitted by AGENTS.md; source-account portrait, chat,
name and coordinate display are obscured. Templates were cropped from reviewed fixtures;
they contain only fixed game UI, not identifiers. Fixture order is not a continuous clip.

The title/selected-tab images are template calibration frames; passing those positive examples
alone does not establish generalization. Wrong-screen negatives exercise the recorded failures.
Pet backgrounds are masked; the World redactor's incidental masks over fixed trap and Raging Bear
text are restored only over those non-identifying game labels. No alliance tag is restored.
The numbered nameplate and active-status templates include two lighting/background variants from
the recorded centered/panel states; exact-template positives are calibration, not generalization.
No genuine empty-list image, recall completion, or input timestamps are included. Do not describe
these tests as complete session validation. State-order tests use synthetic timing, not recorded
touch events. Pet confirmation recovery cancels rather than repeats an ambiguous Use.

Additional pre-event direct screenshots from October 3 (not H.264 frame indices):

- `territory-overview`: actual Alliance Territory landing on Bonus Overview;
  validates landing identity and the unselected Special Buildings tab target.
- `trap2-list-cooldown`: both numbered traps on cooldown, with row-specific Go
  buttons. Coordinates are masked. Go can navigate before activation; it does not
  activate an event or authorize a rally.
- `trap2-cooldown-centered`: numbered Trap 2 World identity, privacy-redacted with
  only fixed trap text restored. It is a negative for active Bear/rally state.

These are calibration fixtures, not active Trap 2 validation. Live Back from both
Territory and Special Buildings returned to Alliance menu; transition tests model
those observed parents. No rally, join, or event-enable input was used to obtain
these additional preparation observations.
