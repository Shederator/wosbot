# Intel Routine Constraints

The Daily sidebar is only an availability gate for Intel. After every sidebar
scroll, navigation waits two seconds for inertial movement and content loading.
The routine then locates the stable Lighthouse Intel icon in the left row-icon
column and searches only that detected row for the fixed green `Intel Gain`
label and its green pixels, without OCR. Dynamic row heights therefore do not
change the identity or availability check. The routine closes the sidebar,
switches to Wilderness, and opens Intel through the right-side Intel shortcut.
Mission completion returns through that same Wilderness shortcut; it must not
route through City or the Lighthouse.

`Hide after mission completion` may remove completed rows and shift every row
below them. Navigation must not change that account preference. A missing
Lighthouse Intel icon after the bounded icon scan is treated as unavailable,
because the completed Intel row may have been hidden; it is not a reason to tap
an assumed coordinate. When the gate reports unavailable, the routine still
enters Intel once through the Wilderness shortcut to read the "Refreshes In"
cooldown banner and schedule the next run precisely, instead of guessing a
fixed retry delay that would otherwise hold the emulator slot from sibling
profiles.

When completed Intel markers expose the stable green `Claim All` control, claim
them with that single detected action and verify that the control disappears.
Individual completed-marker taps are retained only as a bounded fallback for a
missing or ineffective `Claim All` state.

Hero's Journey is complete only after the exploration victory template is
visible. Live logs show the victory state settling several seconds after Fight,
so the routine waits four seconds before polling once per second. The overall
wait remains bounded so a missed or changed result screen cannot trap the task
in a fight or Android Back loop.

After two Survivor deployments, wait 20 seconds before taking a fresh marker
snapshot. Never retain and tap the marker coordinates detected before that
cooldown because the Intel map may change while the task waits.

Intel Beast deployment follows these constraints:

- attempt deployment without a March Queue pre-scan;
- treat the shared deployment signals for no troops, red stamina cost, a full
  March Queue, same-target rejection, or missing deploy confirmation as a
  failed attempt without deducting stamina;
- accept a read travel time only when it is greater than zero and below five
  minutes;
- reserve the Intel march until `travel time * 2` has elapsed;
- when a flag formation is configured, allow only one Intel Beast march at a
  time and retry at its calculated return time.

Two complete Intel scans without progress stop the current run and schedule a
later retry. This bound prevents a visible but unsupported or untappable marker
from creating a hot loop.

Intel marker era is detected automatically and is not a profile setting. Each
run starts with normal Beast, Survivor, and Journey marker patterns, then tries
their Fire Crystal variants. After the first Fire Crystal marker match, every
category searches Fire Crystal variants first for the rest of that run while
retaining normal patterns as fallback.

Saved-frame coverage includes the Daily green availability label, the
Wilderness Intel shortcut, and the Hero's Journey victory screen. Full-queue
and no-troops deployment states reuse `DeploymentHelper`; forcing those states
on a live account remains optional because it can interfere with unrelated
marches.
