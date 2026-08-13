package dev.frostguard.tasks.combat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.MarchSlotState;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.DeploymentHelper;
import dev.frostguard.engine.helper.StaminaTopUpResult;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.schedule.TroopSlotPolicy;

/**
 * Hosts Berserk Cryptid rallies (the Gina's Revenge event) for a configured
 * number of runs.
 *
 * <p><b>Stamina is the real limit, not Horns.</b> Hosting costs 25 stamina and
 * one Horn of the Cryptid. A typical Horn stock runs into the hundreds, so the
 * achievable run count is almost always {@code floor(stamina / 25)}. The task
 * works that out up front, logs the arithmetic, and refuses to start a rally it
 * cannot pay for rather than walking into a half-built deploy screen.
 *
 * <p><b>Why hosting rather than joining.</b> Joining already exists as
 * {@code ManualRallyJoinRoutine} and costs no stamina, but only the host earns
 * the 2-4 Gina shards per kill and progress toward the 10-host milestone.
 * The two are complementary; this task does not touch auto-join.
 *
 * <p><b>Gathering contention.</b> Gathering marches tie up troops. Before
 * recalling one this writes {@code GATHER_LAST_RECALL_TIME_STRING}, which is
 * the handshake {@code GatherRoutine} already honours - it will not redeploy
 * while a recent recall is still in transit. Without that write, Gather would
 * simply take the troops back in the gap between rallies.
 *
 * <p><b>INCOMPLETE - navigation to the target is not implemented.</b> Reaching
 * the cryptid means Events -> Gina's Revenge -> "Find Cryptid", and neither the
 * event tab nor that button has a template in this repo (verified: no "gina" or
 * "revenge" string anywhere in the source). Capturing those needs the buttons on
 * screen. {@link #locateCryptidTarget()} is the single seam where that goes;
 * everything around it is finished. The task refuses to run rather than
 * blind-tapping coordinates that were never observed.
 */
public class CryptidHostingRoutine extends DelayedTask {

    /** Hosting cost, matching {@link DeploymentHelper#MAX_RALLY_STAMINA_COST}. */
    private static final int STAMINA_PER_HOST = DeploymentHelper.MAX_RALLY_STAMINA_COST;

    private static final int DEFAULT_RUNS = 1;
    private static final int MAX_RUNS = 20;

    /**
     * Index 0 of {@link CommonGameAreas#RALLY_SET_TIME_MINUTES}, i.e. 3 minutes
     * - the shortest muster the game offers, so troops cycle back soonest.
     * The engine reads this picker but never sets it, so this task ticks it.
     */
    private static final int RALLY_MINUTES_INDEX = 0;

    /**
     * Flag preset to load, or 0 to leave the formation exactly as the game
     * presents it.
     *
     * <p>Defaults to 0 deliberately. Loading a flag replaces the WHOLE
     * formation, heroes included - selecting flag 1 on the first live run wiped
     * the three heroes the screen had already picked, because that preset has
     * none saved. The deploy screen already arrives with sensible heroes and
     * maxed troop sliders, so the correct behaviour is to touch nothing.
     */
    private int flagNumber = 0;
    private int requestedRuns = DEFAULT_RUNS;

    /**
     * Stamina items to keep in reserve when topping up. Zero means spend
     * whatever is needed - matt's instruction is to open more Chief Stamina
     * cans when the deploy cost shows red.
     */
    private int staminaItemReserve = 0;

    /** Whether to spend Chief Stamina cans when the deploy cost shows red. */
    private boolean useStaminaItems = false;

    public CryptidHostingRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
        // Local time - the queue compares against LocalDateTime.now(); a UTC
        // instant here would silently defer the first run by the UTC offset.
        reschedule(LocalDateTime.now());
    }

    @Override
    protected Object getDistinctKey() {
        return "cryptid_host";
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    /** Declared so the base class refreshes stamina before {@link #execute()}. */
    @Override
    protected boolean consumesStamina() {
        return true;
    }

    /**
     * Stops the game's native Alliance -> War -> Rally Auto-Join.
     *
     * <p>Copied from {@code BearTrapRoutine.disableAutojoinFlow} - same
     * coordinates, same unconditional tap sequence. It does not check whether
     * Auto-Join is already off first: if it is, the Stop button is simply
     * greyed out and the blind tap on it is a no-op, which is exactly what
     * BearTrapRoutine already relies on and is verified live for this task too
     * - confirmed against a real "Auto-Join time remaining: Locked" state
     * after running this sequence.
     */
    private void disableAutoJoin() {
        tapRandomPoint(ALLIANCE_BUTTON_TL, ALLIANCE_BUTTON_BR);
        sleepTask(3000L);

        ImageSearchResultData warButton = templateSearchHelper.locatePattern(
                TemplatesEnum.ALLIANCE_WAR_BUTTON,
                SearchConfig.builder().withThreshold(90).withMaxAttempts(5).build());
        if (warButton == null || !warButton.isFound()) {
            logWarning("CryptidHostingRoutine | Alliance War button not found; could not confirm Auto-Join is off.");
            return;
        }
        tapRandomPoint(warButton.getPoint(), warButton.getPoint(), 1, 1000);
        sleepTask(1000L);

        tapRandomPoint(AUTOJOIN_BUTTON_TL, AUTOJOIN_BUTTON_BR, 1, 1500);
        sleepTask(500L);

        tapRandomPoint(AUTOJOIN_STOP_BUTTON_TL, AUTOJOIN_STOP_BUTTON_BR, 1, 500);
        sleepTask(500L);

        // Leave the War/Rally panel the same way navigateToCryptid expects to
        // find things - back on World, not mid-menu. A fixed pressBack() count
        // was tried first and failed live: it did not consistently clear every
        // dialog layer (Auto-Join dialog stayed open after Stop in manual
        // testing until explicitly dismissed), which then made the very next
        // march-queue read misclassify every slot as UNKNOWN instead of IDLE.
        // ensureCorrectScreenLocation retries back-presses until it actually
        // detects Home/World rather than assuming a count.
        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);
    }

    private static final PointData ALLIANCE_BUTTON_TL = new PointData(493, 1187);
    private static final PointData ALLIANCE_BUTTON_BR = new PointData(561, 1240);
    private static final PointData AUTOJOIN_BUTTON_TL = new PointData(260, 1200);
    private static final PointData AUTOJOIN_BUTTON_BR = new PointData(450, 1240);
    private static final PointData AUTOJOIN_STOP_BUTTON_TL = new PointData(120, 1070);
    private static final PointData AUTOJOIN_STOP_BUTTON_BR = new PointData(240, 1110);

    /** Reads the operator's settings from the Rally panel's Host Rally tab. */
    private void loadSettings() {
        Integer runs = profile.getConfig(ConfigurationKeyEnum.CRYPTID_HOST_RUNS_INT, Integer.class);
        requestedRuns = runs != null && runs > 0 ? Math.min(runs, MAX_RUNS) : DEFAULT_RUNS;

        Integer flag = profile.getConfig(ConfigurationKeyEnum.CRYPTID_HOST_FLAG_INT, Integer.class);
        flagNumber = flag != null && flag > 0 ? flag : 0;

        useStaminaItems = Boolean.TRUE.equals(
                profile.getConfig(ConfigurationKeyEnum.CRYPTID_HOST_USE_STAMINA_ITEMS_BOOL, Boolean.class));
    }

    @Override
    protected void execute() {
        loadSettings();

        // Order of operations matters and was confirmed live: the game's own
        // native Auto-Join (Alliance -> War -> Rally, independent of anything
        // in this bot) will grab any troops the instant a march slot frees up
        // - including troops this task just recalled for its own use. It has
        // to be stopped BEFORE any recall or hosting, or it silently steals
        // the freed slot first. BearTrapRoutine.disableAutojoinFlow() already
        // establishes this exact ordering for the same reason.
        disableAutoJoin();

        int stamina = staminaHelper.getCurrentStamina();
        int horns = readHornCount();
        int affordableByStamina = stamina / STAMINA_PER_HOST;

        // Report the arithmetic explicitly - "why did it only do 1 run" should
        // be answerable from the log alone.
        logInfo(String.format(
                "CryptidHostingRoutine | requested=%d runs | stamina=%d (%d per host -> %d affordable)"
                        + " | horns=%s | cost for %d runs = %d stamina",
                requestedRuns, stamina, STAMINA_PER_HOST, affordableByStamina,
                horns < 0 ? "unread" : String.valueOf(horns),
                requestedRuns, requestedRuns * STAMINA_PER_HOST));

        int runs = Math.min(requestedRuns, affordableByStamina);
        if (horns >= 0) {
            runs = Math.min(runs, horns);
        }

        if (runs <= 0) {
            int needed = STAMINA_PER_HOST;
            logInfo("CryptidHostingRoutine | Not enough stamina for a single host; deferring until "
                    + needed + " is available.");
            // Hand the wait to the engine's stamina deferral rather than
            // guessing a retry time.
            deferForStamina(needed, needed,
                    LocalDateTime.now().plusMinutes(30),
                    LocalDateTime.now().plusMinutes(30));
            return;
        }

        logInfo("CryptidHostingRoutine | Hosting " + runs + " rally(ies) this run.");

        int hosted = 0;
        boolean waitingOnRecall = false;
        for (int i = 0; i < runs; i++) {
            MarchAvailability availability = ensureIdleMarchSlot();
            if (availability == MarchAvailability.RECALL_PENDING) {
                // A gatherer was just recalled. Waiting inline here would hold
                // the queue for however long the march takes to return - stop
                // this pass and let the reschedule below bring the task back
                // once it should be home.
                // matt/2026-08-09 (troop-slot economy): publish the real slot demand so Gather stands
                // down until this rally can actually run, rather than redeploying into the freed slot.
                TroopSlotPolicy.claim(profile, TpDailyTaskEnum.EVENT_CRYPTID_HOST, 1,
                        LocalDateTime.now().plusMinutes(15));
                waitingOnRecall = true;
                break;
            }
            if (availability == MarchAvailability.NONE) {
                logInfo("CryptidHostingRoutine | No idle march slot and nothing gathering to recall; stopping after "
                        + hosted + " host(s).");
                break;
            }
            HostOutcome outcome = hostOneRally();
            recordAttempt(outcome, hosted);
            if (outcome != HostOutcome.SUCCESS) {
                logWarning("CryptidHostingRoutine | Host attempt failed (" + outcome + "); stopping this run.");
                break;
            }
            hosted++;
            // matt/2026-08-09 (troop-slot economy): a rally is genuinely out holding this slot; publish
            // the demand so Gather won't grab the slot back before the muster completes.
            TroopSlotPolicy.claim(profile, TpDailyTaskEnum.EVENT_CRYPTID_HOST, 1,
                    LocalDateTime.now().plusMinutes(15));
        }

        logInfo("CryptidHostingRoutine | Hosted " + hosted + " of " + runs + " planned.");
        setRecurring(true);
        if (waitingOnRecall) {
            // Observed live: a recalled gatherer took under 3 minutes to land.
            // 5 gives margin without leaving stamina idle for long.
            logInfo("CryptidHostingRoutine | Waiting on a recalled march; checking back in 5 minutes.");
            reschedule(LocalDateTime.now().plusMinutes(5));
        } else {
            // matt/2026-08-09 (troop-slot economy): done hosting for this pass — release the slot claim
            // (also pulls Gather forward) so freed slots go back to gathering until the next pass.
            TroopSlotPolicy.release(profile, TpDailyTaskEnum.EVENT_CRYPTID_HOST);
            // Rally muster plus travel there and back; re-check a little after.
            reschedule(LocalDateTime.now().plusMinutes(hosted > 0 ? 10 : 30));
        }
    }

    private enum MarchAvailability {
        IDLE,
        RECALL_PENDING,
        NONE
    }

    private enum HostOutcome {
        SUCCESS,
        TARGET_NOT_FOUND,
        RALLY_BUTTON_MISSING,
        HOLD_BUTTON_MISSING,
        MARCH_QUEUE_FULL,
        NO_TROOPS,
        DEPLOY_NOT_FOUND,
        STAMINA_ITEMS_INSUFFICIENT,
        STAMINA_REFILL_FAILED,
        NAVIGATION_UNIMPLEMENTED
    }

    /**
     * One host attempt. Modelled on PolarTerrorHuntingRoutine's launch flow,
     * which is the hardened version of this sequence - it verifies the queue is
     * not full, that troops exist, and that the deploy actually took, rather
     * than assuming each tap landed.
     */
    private HostOutcome hostOneRally() {
        if (!navigateToCryptid()) {
            return HostOutcome.TARGET_NOT_FOUND;
        }

        ImageSearchResultData rally = templateSearchHelper.locatePattern(
                TemplatesEnum.RALLY_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (rally == null || !rally.isFound()) {
            return HostOutcome.RALLY_BUTTON_MISSING;
        }
        tapPoint(rally.getPoint());
        sleepTask(1000L);

        if (deploymentHelper.isMarchQueueFull()) {
            return HostOutcome.MARCH_QUEUE_FULL;
        }

        // Tick the shortest muster before opening the formation screen; the
        // picker lives on the Hold Rally dialog and persists between rallies.
        selectShortestRallyTime();

        ImageSearchResultData hold = templateSearchHelper.locatePattern(
                TemplatesEnum.RALLY_HOLD_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (hold == null || !hold.isFound()) {
            return HostOutcome.HOLD_BUTTON_MISSING;
        }
        tapPoint(hold.getPoint());
        sleepTask(1200L);

        // Leave the formation alone by default. Both alternatives are
        // destructive here: loading a flag swaps the entire formation including
        // heroes (flag 1 has none saved, so the first live run deployed
        // hero-less), and Equalize rebalances sliders that already arrive
        // maxed. The screen's own defaults are what we want.
        if (flagNumber > 0) {
            if (!marchHelper.selectFlag(flagNumber)) {
                logWarning("CryptidHostingRoutine | Flag " + flagNumber + " is locked; leaving the default formation.");
            }
            sleepTask(600L);
        }

        if (deploymentHelper.hasNoDeployableTroops()) {
            return HostOutcome.NO_TROOPS;
        }

        ImageSearchResultData deploy = templateSearchHelper.locatePattern(
                TemplatesEnum.DEPLOY_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (deploy == null || !deploy.isFound()) {
            return HostOutcome.DEPLOY_NOT_FOUND;
        }

        // A red cost means the stamina is not there. Pressing Deploy anyway
        // opens the game's own obtain-more dialog, which is where the engine
        // can spend Chief Stamina cans - so press it deliberately and refill
        // rather than treating red as a dead end.
        if (deploymentHelper.isDeployCostRed() && useStaminaItems) {
            logInfo("CryptidHostingRoutine | Deploy cost is red; opening the top-up dialog to spend stamina cans.");
            tapPoint(deploy.getPoint());
            sleepTask(1000L);

            StaminaTopUpResult refill = staminaHelper.refillFromOpenDialog(
                    STAMINA_PER_HOST, staminaItemReserve);
            if (!refill.successful()) {
                logWarning("CryptidHostingRoutine | Stamina refill ended with " + refill.status());
                return refill.confirmedItemShortage()
                        ? HostOutcome.STAMINA_ITEMS_INSUFFICIENT
                        : HostOutcome.STAMINA_REFILL_FAILED;
            }
            // The dialog replaced the screen, so re-find Deploy before tapping.
            deploy = templateSearchHelper.locatePattern(
                    TemplatesEnum.DEPLOY_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
            if (deploy == null || !deploy.isFound()) {
                return HostOutcome.DEPLOY_NOT_FOUND;
            }
        }

        tapPoint(deploy.getPoint());
        sleepTask(2000L);

        staminaHelper.subtractStamina(STAMINA_PER_HOST, true);
        return HostOutcome.SUCCESS;
    }

    /** Ticks the 3-minute muster option if it is not already selected. */
    private void selectShortestRallyTime() {
        int current = deploymentHelper.readRallySetTimeSeconds(-1);
        int wanted = CommonGameAreas.RALLY_SET_TIME_MINUTES[RALLY_MINUTES_INDEX] * 60;
        if (current == wanted) {
            return;
        }
        var box = CommonGameAreas.RALLY_SET_TIME_CHECKBOXES[RALLY_MINUTES_INDEX];
        tapRandomPoint(box.topLeft(), box.bottomRight());
        sleepTask(400L);
    }

    /**
     * Ensures at least one march slot is free, recalling a single gatherer if
     * not. Recalls one at a time rather than everything - the other gatherers
     * are still earning while this rally runs.
     *
     * <p>Verified live: the game's own Recall confirmation sits at the exact
     * coordinates {@link #MARCH_RECALL_CONFIRM_TOP_LEFT}/
     * {@link #MARCH_RECALL_CONFIRM_BOTTOM_RIGHT} copied from
     * {@code IntelligenceRoutine} - watched the dialog appear there and land a
     * blind tap correctly. A recalled march took under 3 minutes to land.
     */
    private MarchAvailability ensureIdleMarchSlot() {
        List<MarchSlotState> slots = marchHelper.readMarchQueue();
        if (slots.stream().anyMatch(MarchSlotState::isIdle)) {
            return MarchAvailability.IDLE;
        }

        MarchSlotState longestGather = slots.stream()
                .filter(MarchSlotState::isGather)
                .max(Comparator.comparing(s -> s.countdown() == null ? Duration.ZERO : s.countdown()))
                .orElse(null);
        if (longestGather == null) {
            return MarchAvailability.NONE;
        }

        // GatherRoutine already honours this timestamp and stands down while a
        // recent recall is still in transit - without writing it first, Gather
        // would simply redeploy the same troops into the slot this just freed.
        profile.setConfig(ConfigurationKeyEnum.GATHER_LAST_RECALL_TIME_STRING, LocalDateTime.now().toString());

        boolean recalled = recallGatherMarchByQueueFlow(longestGather.slot() - 1);
        if (!recalled) {
            logWarning("CryptidHostingRoutine | Could not locate a recall button for queue #"
                    + longestGather.slot() + ".");
            return MarchAvailability.NONE;
        }

        logInfo("CryptidHostingRoutine | Recalled gathering march #" + longestGather.slot()
                + " to free a slot for hosting.");
        return MarchAvailability.RECALL_PENDING;
    }

    /**
     * Copied from {@code IntelligenceRoutine.recallGatherMarchByQueueFlow} - it
     * is private there and there is no shared recall API on MarchHelper yet.
     * The row-region table and confirm coordinates are the same ones; both are
     * verified against the live wilderness panel, not guessed.
     */
    private boolean recallGatherMarchByQueueFlow(int queueIndex) {
        marchHelper.openLeftMenuCitySection(false);
        try {
            List<ImageSearchResultData> recallButtons = templateSearchHelper.locateAllPatterns(
                    TemplatesEnum.MARCHES_AREA_RECALL_BUTTON,
                    SearchConfig.builder()
                            .withArea(new AreaData(MARCH_QUEUE_REGIONS[0].topLeft(),
                                    MARCH_QUEUE_REGIONS[MARCH_QUEUE_REGIONS.length - 1].bottomRight()))
                            .withMaxAttempts(3)
                            .withDelay(3)
                            .withMaxResults(MARCH_QUEUE_REGIONS.length)
                            .build());

            if (recallButtons.isEmpty()) {
                return false;
            }

            int targetRowCenterY = (MARCH_QUEUE_REGIONS[queueIndex].topLeft().getY()
                    + MARCH_QUEUE_REGIONS[queueIndex].bottomRight().getY()) / 2;
            ImageSearchResultData bestRowButton = recallButtons.stream()
                    .min(Comparator.comparingInt(button -> Math.abs(button.getPoint().getY() - targetRowCenterY)))
                    .orElse(null);
            if (bestRowButton == null) {
                return false;
            }

            tapRandomPoint(bestRowButton.getPoint(), bestRowButton.getPoint(), 1, 200);
            tapRandomPoint(MARCH_RECALL_CONFIRM_TOP_LEFT, MARCH_RECALL_CONFIRM_BOTTOM_RIGHT, 1, 200);
            return true;
        } finally {
            marchHelper.closeLeftMenu();
        }
    }

    private record MarchQueueRegion(PointData topLeft, PointData bottomRight) {}

    private static final PointData MARCH_RECALL_CONFIRM_TOP_LEFT = new PointData(446, 780);
    private static final PointData MARCH_RECALL_CONFIRM_BOTTOM_RIGHT = new PointData(578, 800);

    private static final MarchQueueRegion[] MARCH_QUEUE_REGIONS = {
            new MarchQueueRegion(new PointData(10, 342), new PointData(435, 407)),
            new MarchQueueRegion(new PointData(10, 415), new PointData(435, 480)),
            new MarchQueueRegion(new PointData(10, 488), new PointData(435, 553)),
            new MarchQueueRegion(new PointData(10, 561), new PointData(435, 626)),
            new MarchQueueRegion(new PointData(10, 634), new PointData(435, 699)),
            new MarchQueueRegion(new PointData(10, 707), new PointData(435, 772)),
    };

    /**
     * Events -> Gina's Revenge -> Attack/Find a Cryptid.
     *
     * <p>Verified against the live game: pressing the event's bottom button
     * lands directly on the cryptid with its target dialog already open, so no
     * separate "tap the monster on the map" step is needed.
     *
     * <p>The tab strip does not remember its position, so it is swiped back to
     * the far left and then scanned rightwards - the same approach
     * NavigationHelper uses, and for the same reason: the strip keeps animating
     * after a swipe returns, so each search needs a settle first.
     */
    private boolean navigateToCryptid() {
        ImageSearchResultData events = templateSearchHelper.locatePattern(
                TemplatesEnum.HOME_EVENTS_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (events == null || !events.isFound()) {
            logWarning("CryptidHostingRoutine | Events button not found.");
            return false;
        }
        tapPoint(events.getPoint());
        sleepTask(2000L);

        if (!selectGinasRevengeTab()) {
            logWarning("CryptidHostingRoutine | Gina's Revenge tab not found - is the event still running?");
            return false;
        }

        // Prefer Attack: it means a cryptid is already on the map and costs no
        // Horn. Find spawns a fresh one and consumes a Horn, so it is the
        // fallback rather than the default.
        ImageSearchResultData attack = templateSearchHelper.locatePattern(
                TemplatesEnum.CRYPTID_ATTACK_BUTTON, SearchConfigConstants.DEFAULT_SINGLE);
        if (attack != null && attack.isFound()) {
            logInfo("CryptidHostingRoutine | Cryptid already on the map - attacking (no Horn spent).");
            tapPoint(attack.getPoint());
            sleepTask(3000L);
            // Attack alone lands directly on the target dialog with Rally
            // already visible - verified live, no further tap needed.
            return true;
        }

        ImageSearchResultData find = templateSearchHelper.locatePattern(
                TemplatesEnum.CRYPTID_FIND_BUTTON, SearchConfigConstants.DEFAULT_SINGLE);
        if (find != null && find.isFound()) {
            logInfo("CryptidHostingRoutine | No cryptid out - spending a Horn to find one.");
            tapPoint(find.getPoint());
            sleepTask(2500L);
            // Unlike Attack, Find does NOT open the target dialog. It pans the
            // camera to the newly spawned cryptid's map location (with a "We've
            // found traces..." banner) and leaves the camera centered there -
            // the dialog only opens once the cryptid itself is tapped, same as
            // tapping any other creature on the map. Verified live across
            // several frames: the model settles at a fixed screen position
            // after the pan, so a single centered tap reliably opens it.
            tapPoint(CRYPTID_MAP_CENTER);
            sleepTask(1500L);
            return true;
        }

        logWarning("CryptidHostingRoutine | Neither Attack nor Find button matched on the event panel.");
        return false;
    }

    /**
     * Where the camera settles the newly-spawned cryptid after Find. Measured
     * live: the model and its "Berserk Cryptid" label sat at this exact spot
     * across five consecutive frames, so the pan animation had already
     * finished by the time this task's own sleep would tap.
     */
    private static final PointData CRYPTID_MAP_CENTER = new PointData(360, 575);

    /** Swipes the event tab strip back to the start, then scans right for the tab. */
    private boolean selectGinasRevengeTab() {
        for (int reset = 0; reset < 3; reset++) {
            swipe(new PointData(80, 143), new PointData(600, 143));
            sleepTask(600L);
        }

        for (int scan = 0; scan < 8; scan++) {
            ImageSearchResultData tab = templateSearchHelper.locatePattern(
                    TemplatesEnum.GINAS_REVENGE_TAB, SearchConfigConstants.DEFAULT_SINGLE);
            if (tab != null && tab.isFound()) {
                tapPoint(tab.getPoint());
                sleepTask(1500L);
                return true;
            }
            swipe(new PointData(630, 143), new PointData(300, 143));
            // The strip keeps sliding after the gesture returns; searching too
            // soon misses a tab that is mid-animation.
            sleepTask(900L);
        }
        return false;
    }

    /**
     * Reads the Horn of the Cryptid count from Backpack -> Other.
     *
     * <p>Returns -1 when unread rather than 0: an unread count must not be
     * mistaken for "no horns left" and silently cancel the run. Not yet
     * implemented for the same reason as the navigation - the item's grid
     * position shifts as backpack contents change, so it needs a template
     * match against the horn icon rather than a fixed cell.
     */
    private int readHornCount() {
        return -1;
    }

    private void recordAttempt(HostOutcome outcome, int index) {
        String json = "{\"at\":\"" + LocalDateTime.now() + "\",\"attempt\":" + (index + 1)
                + ",\"outcome\":\"" + outcome + "\"}";
        Path dir = Paths.get(System.getProperty("user.dir"), "telemetry");
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve("cryptid-rallies.jsonl"),
                    (json + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logWarning("CryptidHostingRoutine | Could not record attempt: " + e.getMessage());
        }
    }
}
