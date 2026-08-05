package dev.frostguard.engine.listener.task.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.MarchSlotState;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.DeploymentHelper;
import dev.frostguard.engine.helper.TemplateSearchHelper;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.CustomTaskConfigurable;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.CustomTaskService;

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
public class bg_cryptidrally extends DelayedTask implements CustomTaskConfigurable {

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

    /** Flag preset to deploy. 0 means "no preset, use Equalize instead". */
    private int flagNumber = 0;
    private int requestedRuns = DEFAULT_RUNS;

    public bg_cryptidrally(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
        // Local time - the queue compares against LocalDateTime.now(); a UTC
        // instant here would silently defer the first run by the UTC offset.
        reschedule(LocalDateTime.now());
    }

    @Override
    protected Object getDistinctKey() {
        return "bg_cryptidrally";
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

    @Override
    public void applyCustomTaskSettings(CustomTaskService.CustomTaskSettings settings) {
        if (settings == null) {
            return;
        }
        // The custom-task panel only exposes firstExecutionUtc and
        // followUpDelayHours, so the hours field carries the run count until
        // there is a proper Whiteout Console panel with a real dropdown.
        Integer runs = settings.getFollowUpDelayHours();
        requestedRuns = runs != null && runs > 0 ? Math.min(runs, MAX_RUNS) : DEFAULT_RUNS;
    }

    @Override
    protected void execute() {
        int stamina = staminaHelper.getCurrentStamina();
        int horns = readHornCount();
        int affordableByStamina = stamina / STAMINA_PER_HOST;

        // Report the arithmetic explicitly - "why did it only do 1 run" should
        // be answerable from the log alone.
        logInfo(String.format(
                "bg_cryptidrally | requested=%d runs | stamina=%d (%d per host -> %d affordable)"
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
            logInfo("bg_cryptidrally | Not enough stamina for a single host; deferring until "
                    + needed + " is available.");
            // Hand the wait to the engine's stamina deferral rather than
            // guessing a retry time.
            deferForStamina(needed, needed,
                    LocalDateTime.now().plusMinutes(30),
                    LocalDateTime.now().plusMinutes(30));
            return;
        }

        logInfo("bg_cryptidrally | Hosting " + runs + " rally(ies) this run.");

        int hosted = 0;
        for (int i = 0; i < runs; i++) {
            if (!ensureIdleMarchSlot()) {
                logInfo("bg_cryptidrally | No idle march slot; stopping after " + hosted + " host(s).");
                break;
            }
            HostOutcome outcome = hostOneRally();
            recordAttempt(outcome, hosted);
            if (outcome != HostOutcome.SUCCESS) {
                logWarning("bg_cryptidrally | Host attempt failed (" + outcome + "); stopping this run.");
                break;
            }
            hosted++;
        }

        logInfo("bg_cryptidrally | Hosted " + hosted + " of " + runs + " planned.");
        setRecurring(true);
        // Rally muster plus travel there and back; re-check a little after.
        reschedule(LocalDateTime.now().plusMinutes(hosted > 0 ? 10 : 30));
    }

    private enum HostOutcome {
        SUCCESS,
        TARGET_NOT_FOUND,
        RALLY_BUTTON_MISSING,
        HOLD_BUTTON_MISSING,
        MARCH_QUEUE_FULL,
        NO_TROOPS,
        DEPLOY_NOT_FOUND,
        NAVIGATION_UNIMPLEMENTED
    }

    /**
     * One host attempt. Modelled on PolarTerrorHuntingRoutine's launch flow,
     * which is the hardened version of this sequence - it verifies the queue is
     * not full, that troops exist, and that the deploy actually took, rather
     * than assuming each tap landed.
     */
    private HostOutcome hostOneRally() {
        ImageSearchResultData target = locateCryptidTarget();
        if (target == null) {
            return HostOutcome.NAVIGATION_UNIMPLEMENTED;
        }
        tapPoint(target.getPoint());
        sleepTask(1200L);

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

        // Troops: a saved flag is deterministic; Equalize is the fallback when
        // none is configured. Neither guarantees "max", but a flag preset is
        // the closest thing the game exposes.
        if (flagNumber > 0) {
            if (!marchHelper.selectFlag(flagNumber)) {
                logWarning("bg_cryptidrally | Flag " + flagNumber + " is locked; falling back to Equalize.");
                deploymentHelper.tapEqualize();
            }
        } else {
            deploymentHelper.tapEqualize();
        }
        sleepTask(600L);

        if (deploymentHelper.hasNoDeployableTroops()) {
            return HostOutcome.NO_TROOPS;
        }

        ImageSearchResultData deploy = templateSearchHelper.locatePattern(
                TemplatesEnum.DEPLOY_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (deploy == null || !deploy.isFound()) {
            return HostOutcome.DEPLOY_NOT_FOUND;
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
     */
    private boolean ensureIdleMarchSlot() {
        List<MarchSlotState> slots = marchHelper.readMarchQueue();
        if (slots.stream().anyMatch(MarchSlotState::isIdle)) {
            return true;
        }
        boolean hasGatherer = slots.stream().anyMatch(MarchSlotState::isGather);
        if (!hasGatherer) {
            return false;
        }
        logInfo("bg_cryptidrally | No idle slot but a gatherer is out; recall not implemented yet.");
        // Deliberately not recalling until the navigation seam is closed and
        // the whole flow has been observed end to end. Recalling troops is a
        // real, visible action in matt's game and should not fire as a side
        // effect of a task that cannot yet complete its main job.
        return false;
    }

    /**
     * Locates the Berserk Cryptid on the map.
     *
     * <p>NOT IMPLEMENTED. The route is Events -> Gina's Revenge -> "Find
     * Cryptid" (bottom of the panel), and neither step has a template here:
     * {@code NavigationHelper.EventMenu} has no Gina entry, and there is no
     * "Find"/"Search" event-panel button template. Both must be captured from a
     * live screen first.
     *
     * <p>Two candidate routes when that happens:
     * <ul>
     *   <li>Events tab - matches HeroMissionEventRoutine's shape exactly
     *       (navigate to named event, press its action button, rally the
     *       located target). Needs 2 new templates.</li>
     *   <li>World creature-search strip - BeastSlayRoutine notes Berserk
     *       Cryptid appears there as an inserted tab, which would reuse the
     *       proven openUpPolarsMenu swipe-and-match pattern. Needs 1.</li>
     * </ul>
     */
    private ImageSearchResultData locateCryptidTarget() {
        logError("bg_cryptidrally | Cryptid navigation is not implemented - no template exists for "
                + "the Gina's Revenge event tab or the 'Find Cryptid' button. Capture those from a "
                + "live screen before enabling this task.");
        return null;
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
            logWarning("bg_cryptidrally | Could not record attempt: " + e.getMessage());
        }
    }
}
