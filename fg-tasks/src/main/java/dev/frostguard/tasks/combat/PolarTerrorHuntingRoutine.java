package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.MarchSlotState;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.data.entity.DailyTask;
import dev.frostguard.data.repository.DailyTaskRepository;
import dev.frostguard.engine.helper.MarchSlotAvailabilityEstimator;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.TaskManagementService;
import dev.frostguard.vision.convert.GameTimeUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class PolarTerrorHuntingRoutine extends DelayedTask {

private static final int DEFAULT_STAMINA_RESERVE = 130;
private static final int MIN_POLAR_LEVEL = 1;
private static final int MAX_POLAR_LEVEL_LIMIT = 8;
// Enough taps to cross the selector's whole range in one go, in either direction.
private static final int LEVEL_SELECTOR_SWEEP_TAPS = MAX_POLAR_LEVEL_LIMIT;
// The selector minimum differs per profile (older accounts cannot go below level 4), so the level is
// steered by reading it back rather than by counting taps from an assumed floor.
private static final int LEVEL_SELECTOR_MAX_ADJUSTMENTS = 3;
private static final PointData LEVEL_MINUS_BUTTON = new PointData(70, 1054);
private static final PointData LEVEL_PLUS_BUTTON = new PointData(486, 1054);

// A polar rally costs at most 25 stamina; heroes such as Gina only lower it.
private static final int MAX_POLAR_RALLY_STAMINA_COST = 25;
// The in-game default; the real value is read off the Hold-a-rally dialog per rally.
private static final int DEFAULT_RALLY_SET_TIME_SECONDS = 180;
private static final int RALLY_RETURN_SAFETY_SECONDS = 5;

// Every slot is busy with a countdown that says nothing about the return (rally, attack): look again
// shortly. If no slot announces a return at all (encamped, reinforcing, locked), back off much further.
private static final int UNKNOWN_MARCH_RETRY_MINUTES = 5;
private static final int STATIONED_MARCH_RETRY_MINUTES = 60;
private static final int LOWER_BOUND_RECHECK_BUFFER_MINUTES = 1;
private static final int GATHER_RETURN_BUFFER_MINUTES = 5;
private static final int MIN_RETRY_SECONDS = 30;
private static final MarchSlotAvailabilityEstimator.Settings MARCH_SLOT_ESTIMATE_SETTINGS =
        new MarchSlotAvailabilityEstimator.Settings(
                Duration.ofMinutes(LOWER_BOUND_RECHECK_BUFFER_MINUTES),
                Duration.ofMinutes(GATHER_RETURN_BUFFER_MINUTES),
                Duration.ofMinutes(UNKNOWN_MARCH_RETRY_MINUTES),
                Duration.ofMinutes(STATIONED_MARCH_RETRY_MINUTES));
// Another player beat us to the target: search again in-run, and only give up on the whole wake-up
// once several fresh targets in a row turn out to be contested.
private static final int TARGET_TAKEN_MAX_RETRIES = 3;
private static final int TARGET_TAKEN_RETRY_MINUTES = 1;
private static final int RALLY_FAILURE_RETRY_MINUTES = 5;
// Holds the march slot when the travel time could not be read.
private static final int UNKNOWN_TRAVEL_HOLD_MINUTES = 10;

// Loaded from STAMINA_RESERVE_INT in hydrateConfiguration(): the reserve kept back
// for Intel/Rally (minStaminaLevel) and the level to regen back up to (refreshStaminaLevel).
private int refreshStaminaLevel = DEFAULT_STAMINA_RESERVE + 50;

private int minStaminaLevel = DEFAULT_STAMINA_RESERVE;

private final DailyTaskRepository iDailyTaskRepository = DailyTaskRepository.getRepository();

private final TaskManagementService taskManagementService = TaskManagementService.shared();

private static final Map<Long, List<LocalDateTime>> activeDeployments = new ConcurrentHashMap<>();

private int polarTerrorLevel;

// When set, the configured level is ignored and the selector is driven to whatever ceiling the
// server currently offers, so a newly unlocked polar level needs no config change.
private boolean huntHighestLevel;

// Read off the Hold-a-rally dialog of the rally currently being launched.
private int rallySetTimeSeconds = DEFAULT_RALLY_SET_TIME_SECONDS;

private boolean limitedHunting;

private int maxMarches;

private boolean useStaminaItems;

private int staminaItemReserve;

public PolarTerrorHuntingRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

private enum RallyLaunchOutcome {
        SUCCESS,
        SEARCH_FAILED,
        RALLY_BUTTON_MISSING,
        HOLD_RALLY_MISSING,
        FLAG_NOT_AVAILABLE,
        DEPLOY_NOT_FOUND,
        DEPLOY_NOT_CONFIRMED,
        TARGET_ALREADY_TAKEN,
        MARCH_QUEUE_FULL,
        NO_TROOPS_AVAILABLE,
        STAMINA_ITEMS_DISABLED,
        STAMINA_REFILL_FAILED
    }

private record RallyLaunchResult(RallyLaunchOutcome outcome, String detail) {
        boolean success() {
            return outcome == RallyLaunchOutcome.SUCCESS;
        }
    }

@Override
    protected void execute() {

        hydrateConfiguration();

        if (eventHelper.isBearRunning()) {
            LocalDateTime rescheduleTo = LocalDateTime.now().plusMinutes(30);
            logInfo(routineLogPolarTerrorHuntingLine("Bear Hunt is running, planning next run for " + rescheduleTo));
            reschedule(rescheduleTo);
            return;
        }

        if (profile.getConfig(ConfigurationKeyEnum.INTEL_BOOL, Boolean.class)
                && profile.getConfig(ConfigurationKeyEnum.POLAR_TERROR_ENABLED_BOOL, Boolean.class)
                && taskManagementService.lookupTaskState(profile.getId(), TpDailyTaskEnum.INTEL.getId()).isScheduled()) {

            DailyTask intel = iDailyTaskRepository.findByAccountIdAndTaskType(profile.getId(), TpDailyTaskEnum.INTEL);
            if (ChronoUnit.MINUTES.between(LocalDateTime.now(), intel.getScheduledAt()) < 5) {
                reschedule(LocalDateTime.now().plusMinutes(5));
                logWarning(routineLogPolarTerrorHuntingLine("Intel task is scheduled to run soon. Planning next run Polar Hunt to run 5 min after intel."));
                return;
            }
        }

        logInfo(routineLogPolarTerrorHuntingLine(String.format(
                "Configuration: level=%s mode=%s marches=%d staminaReserve=%d useStaminaItems=%s itemReserve=%d",
                huntHighestLevel ? "highest available" : Integer.toString(polarTerrorLevel),
                limitedHunting ? "Limited (10)" : "Unlimited",
                maxMarches,
                minStaminaLevel,
                useStaminaItems,
                staminaItemReserve)));

        if (limitedHunting && !polarsRemainingFlow(polarTerrorLevel)) {
            return;
        }

        int deployedCount = resolveActiveDeploymentsCount(profile.getId());

        while (deployedCount < maxMarches) {

            if (!staminaCouldSupportRally())
                return;

            List<MarchSlotState> marchQueue = marchHelper.readMarchQueue();
            if (marchQueue.stream().noneMatch(MarchSlotState::isIdle)) {
                LocalDateTime retryAt = resolveNoSlotRetry(marchQueue);
                logInfo(routineLogPolarTerrorHuntingLine(String.format(
                        "Zero marches available after %d rallies. Planning next run at %s.",
                        deployedCount,
                        GameTimeUtils.formatCountdown(retryAt))));
                reschedule(retryAt);
                return;
            }

            if (limitedHunting && !polarsRemainingFlow(polarTerrorLevel)) {
                return;
            }

            if (!ensureStaminaForRally())
                return;

            String flagConfigKey = "POLAR_TERROR_MARCH_" + (deployedCount + 1) + "_FLAG_STRING";
            ConfigurationKeyEnum enumKey = ConfigurationKeyEnum.valueOf(flagConfigKey);
            String flagString = profile.getConfig(enumKey, String.class);

            boolean useFlag = false;
            int currentFlagNumber = 0;

            if (flagString != null && !flagString.trim().equals("No Flag")) {
                try {
                    currentFlagNumber = Integer.parseInt(flagString.trim());
                    useFlag = true;
                } catch (NumberFormatException e) {
                    logWarning(routineLogPolarTerrorHuntingLine("Invalid flag number in config: " + flagString + " for march " + (deployedCount + 1)));
                }
            }

            logInfo(routineLogPolarTerrorHuntingLine("Launching rally " + (deployedCount + 1) + " of " + maxMarches +
                    (useFlag ? " with flag #" + currentFlagNumber : " (Equalize fallback)")));

            RallyLaunchResult result = launchSingleRallyFlow(polarTerrorLevel, useFlag, currentFlagNumber);

            // Cancelling costs no stamina and the search hands out a different creature, so the
            // cheapest answer to a contested target is to look again right away.
            for (int attempt = 1; attempt <= TARGET_TAKEN_MAX_RETRIES
                    && result.outcome() == RallyLaunchOutcome.TARGET_ALREADY_TAKEN; attempt++) {
                logInfo(routineLogPolarTerrorHuntingLine(String.format(
                        "Target was taken by another player; searching for a different polar terror (retry %d/%d)",
                        attempt, TARGET_TAKEN_MAX_RETRIES)));
                sleepTask(1000);
                result = launchSingleRallyFlow(polarTerrorLevel, useFlag, currentFlagNumber);
            }

            if (result.outcome() == RallyLaunchOutcome.TARGET_ALREADY_TAKEN) {
                logInfo(routineLogPolarTerrorHuntingLine(String.format(
                        "Every target was contested after %d retries. Searching again in %d min.",
                        TARGET_TAKEN_MAX_RETRIES, TARGET_TAKEN_RETRY_MINUTES)));
                reschedule(LocalDateTime.now().plusMinutes(TARGET_TAKEN_RETRY_MINUTES));
                return;
            }

            if (result.outcome() == RallyLaunchOutcome.MARCH_QUEUE_FULL) {
                logInfo(routineLogPolarTerrorHuntingLine("March queue popup detected after Rally. Waiting for a march slot to return."));
                reschedule(LocalDateTime.now().plusMinutes(UNKNOWN_MARCH_RETRY_MINUTES));
                return;
            }

            if (!result.success()) {
                logInfo(routineLogPolarTerrorHuntingLine(String.format(
                        "Rally attempt %d/%d result=%s detail=%s. Planning next run in %d minutes.",
                        deployedCount + 1,
                        maxMarches,
                        result.outcome(),
                        result.detail(),
                        RALLY_FAILURE_RETRY_MINUTES)));
                reschedule(LocalDateTime.now().plusMinutes(RALLY_FAILURE_RETRY_MINUTES));
                return;
            }

            deployedCount++;
            logInfo(routineLogPolarTerrorHuntingLine("Rally #" + deployedCount + " deployed finished cleanly. Current stamina: "
                    + staminaHelper.getCurrentStamina()));
            sleepTask(1500);
        }

        LocalDateTime nextRun = resolveEarliestReturn(profile.getId());
        if (nextRun == null || nextRun.isBefore(LocalDateTime.now())) {
            nextRun = LocalDateTime.now().plusMinutes(15);
        }

        // Waking when the march returns but the next rally is still unaffordable burns a profile
        // switch for nothing. Items can close that gap on the spot, regeneration cannot.
        if (!useStaminaItems && staminaHelper.getCurrentStamina() < requiredStaminaForRally()) {
            LocalDateTime staminaReadyAt = LocalDateTime.now().plusMinutes(
                    staminaHelper.staminaRegenerationTime(staminaHelper.getCurrentStamina(), refreshStaminaLevel));
            if (staminaReadyAt.isAfter(nextRun)) {
                logInfo(routineLogPolarTerrorHuntingLine(String.format(
                        "Stamina runs out before the march returns; delaying the next run from %s to %s",
                        GameTimeUtils.formatCountdown(nextRun),
                        GameTimeUtils.formatCountdown(staminaReadyAt))));
                nextRun = staminaReadyAt;
            }
        }

        reschedule(nextRun);
        logInfo(routineLogPolarTerrorHuntingLine(String.format(
                "Successfully deployed all %d configured rallies. Planning next run at %s.",
                maxMarches,
                GameTimeUtils.formatCountdown(nextRun))));
    }

@Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

@Override
    protected boolean consumesStamina() {
        return true;
    }

private String routineLogPolarTerrorHuntingLine(String note) {
        return "PolarTerrorHuntingRoutine | " + note;
    }

private static int resolveActiveDeploymentsCount(long profileId) {
        List<LocalDateTime> deployments = activeDeployments.get(profileId);
        if (deployments == null)
            return 0;

        deployments.removeIf(time -> LocalDateTime.now().isAfter(time));
        return deployments.size();
    }

// Earliest future return among this profile's active rallies; that is the soonest a march slot frees
// up and the routine can redeploy. Null when nothing is deployed.
private static LocalDateTime resolveEarliestReturn(long profileId) {
        List<LocalDateTime> deployments = activeDeployments.get(profileId);
        if (deployments == null)
            return null;

        deployments.removeIf(time -> LocalDateTime.now().isAfter(time));
        return deployments.stream().min(LocalDateTime::compareTo).orElse(null);
    }

private static void registerDeploymentFlow(long profileId, LocalDateTime returnTime) {
        activeDeployments.computeIfAbsent(profileId, k -> new CopyOnWriteArrayList<>())
                .add(returnTime);
    }

private RallyLaunchResult launchSingleRallyFlow(int polarLevel, boolean useFlag, int flagNumber) {
        navigationHelper.ensureCorrectScreenLocation(getRequiredStartLocation());

        logInfo(routineLogPolarTerrorHuntingLine(String.format("Opening Polar Terror search: level=%s",
                huntHighestLevel ? "highest available" : Integer.toString(polarLevel))));
        if (!openUpPolarsMenu(polarLevel)) {
            logError(routineLogPolarTerrorHuntingLine("Could not open polars menu."));
            return fail(RallyLaunchOutcome.SEARCH_FAILED, "Polar tab/search result was not verified");
        }

        logInfo(routineLogPolarTerrorHuntingLine("Opening rally dialog"));
        RallyLaunchResult rallyDialog = openUpRallyMenu();
        if (!rallyDialog.success()) {
            logError(routineLogPolarTerrorHuntingLine("Could not open rally menu: " + rallyDialog.detail()));
            return rallyDialog;
        }

        if (useFlag) {
            logInfo(routineLogPolarTerrorHuntingLine("Formation setup: selecting flag #" + flagNumber));
            if (!marchHelper.selectFlag(flagNumber)) {
                String detail = "Configured flag #" + flagNumber + " is locked on this profile";
                logWarning(routineLogPolarTerrorHuntingLine("Formation setup: " + detail));
                return fail(RallyLaunchOutcome.FLAG_NOT_AVAILABLE, detail);
            }
            logInfo(routineLogPolarTerrorHuntingLine("Formation setup: flag #" + flagNumber + " confirmed"));
        } else {
            logInfo(routineLogPolarTerrorHuntingLine("Formation setup: no flag configured, using Equalize"));
            deploymentHelper.tapEqualize();
            sleepTask(500);
        }

        if (deploymentHelper.hasNoDeployableTroops()) {
            return fail(RallyLaunchOutcome.NO_TROOPS_AVAILABLE,
                    "The formation screen offers Troop Training, so there are no troops to send");
        }

        long travelTimeSeconds = staminaHelper.parseTravelTime();
        int rallyStaminaCost = staminaHelper.readDeployCost(MAX_POLAR_RALLY_STAMINA_COST);
        logInfo(routineLogPolarTerrorHuntingLine(String.format(
                "Deployment read: travelSeconds=%d staminaCost=%d", travelTimeSeconds, rallyStaminaCost)));

        ImageSearchResultData deploy = templateSearchHelper.locatePattern(
                TemplatesEnum.DEPLOY_BUTTON,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!deploy.isFound()) {
            String detail = "Deploy button not found after formation setup; known causes: no troops, flag locked, autojoin consumed the march slot, or unexpected formation screen";
            logWarning(routineLogPolarTerrorHuntingLine("Deployment decision: " + detail));
            return fail(RallyLaunchOutcome.DEPLOY_NOT_FOUND, detail);
        }

        if (deploymentHelper.isDeployCostRed()) {
            if (!useStaminaItems) {
                String detail = "Deploy stamina cost is red and stamina item use is disabled";
                logWarning(routineLogPolarTerrorHuntingLine("Stamina gate: " + detail));
                return fail(RallyLaunchOutcome.STAMINA_ITEMS_DISABLED, detail);
            }

            logInfo(routineLogPolarTerrorHuntingLine("Stamina gate: pressing Deploy to open the obtain-more dialog"));
            tapPoint(deploy.getPoint());
            sleepTask(1000);

            if (!staminaHelper.refillFromOpenDialog(rallyStaminaCost, staminaItemReserve)) {
                return fail(RallyLaunchOutcome.STAMINA_REFILL_FAILED,
                        "Could not refill stamina from the obtain-more dialog");
            }

            deploy = templateSearchHelper.locatePattern(
                    TemplatesEnum.DEPLOY_BUTTON,
                    SearchConfigConstants.SINGLE_WITH_RETRIES);
            if (!deploy.isFound()) {
                return fail(RallyLaunchOutcome.STAMINA_REFILL_FAILED, "Deploy button not restored after stamina refill");
            }
        }

        logInfo(routineLogPolarTerrorHuntingLine(String.format(
                "Deployment decision: Deploy found at %s score=%.2f; pressing",
                deploy.getPoint(), deploy.getMatchScore())));
        tapPoint(deploy.getPoint());
        sleepTask(2000);

        if (deploymentHelper.isSameTargetDialog()) {
            leaveSameTargetDialog();
            return fail(RallyLaunchOutcome.TARGET_ALREADY_TAKEN,
                    "Another player's troops are already marching toward this target; the rally was cancelled");
        }

        deploy = templateSearchHelper.locatePattern(
                TemplatesEnum.DEPLOY_BUTTON,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (deploy.isFound()) {
            logWarning(routineLogPolarTerrorHuntingLine(String.format(
                    "Deployment result: Deploy still visible at %s score=%.2f; rally not confirmed",
                    deploy.getPoint(), deploy.getMatchScore())));
            return fail(RallyLaunchOutcome.DEPLOY_NOT_CONFIRMED,
                    "Deploy button remained visible after pressing; likely blocked by stamina, troops, march slot, or formation validation");
        }

        ImageSearchResultData worldAnchor = templateSearchHelper.locatePattern(
                TemplatesEnum.GAME_HOME_WORLD,
                SearchConfigConstants.RESILIENT);
        if (!worldAnchor.isFound()) {
            logWarning(routineLogPolarTerrorHuntingLine(
                    "Deployment result: Deploy disappeared but World anchor was not verified; not treating this as a confirmed rally"));
            return fail(RallyLaunchOutcome.DEPLOY_NOT_CONFIRMED,
                    "Deploy disappeared, but the routine could not verify that the game returned to World");
        }

        logInfo(routineLogPolarTerrorHuntingLine("Deployment result: World verified after Deploy; treating rally as started"));

        staminaHelper.subtractStamina(rallyStaminaCost, true);

        if (travelTimeSeconds > 0) {
            long returnTimeSeconds = rallySetTimeSeconds + travelTimeSeconds * 2 + RALLY_RETURN_SAFETY_SECONDS;
            LocalDateTime returnTime = LocalDateTime.now().plusSeconds(returnTimeSeconds);
            registerDeploymentFlow(profile.getId(), returnTime);
            logInfo(routineLogPolarTerrorHuntingLine(String.format(
                    "Rally scheduled: returnAt=%s setSeconds=%d travelSeconds=%d staminaCost=%d",
                    GameTimeUtils.formatCountdown(returnTime),
                    rallySetTimeSeconds,
                    travelTimeSeconds,
                    rallyStaminaCost)));
        } else {
            logWarning(routineLogPolarTerrorHuntingLine("Could not parse travel time via OCR. Holding slot for "
                    + UNKNOWN_TRAVEL_HOLD_MINUTES + " minutes."));
            registerDeploymentFlow(profile.getId(), LocalDateTime.now().plusMinutes(UNKNOWN_TRAVEL_HOLD_MINUTES));
        }

        return ok("Rally deployed and World anchor verified");
    }

// Backing out of the confirmation returns to the formation screen, from where one more back press
// reaches World. Neither stamina nor a march slot has been spent at that point.
private void leaveSameTargetDialog() {
        pressBack();
        sleepTask(800);
        pressBack();
        sleepTask(800);

        ImageSearchResultData worldAnchor = templateSearchHelper.locatePattern(
                TemplatesEnum.GAME_HOME_WORLD,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!worldAnchor.isFound()) {
            logWarning(routineLogPolarTerrorHuntingLine(
                    "World anchor not verified after cancelling; the next run re-navigates"));
        }
    }

// Picks the next useful wake-up when every march slot is busy. Rallies this routine launched itself
// carry exact return times; the panel contributes exact returning countdowns or conservative recheck
// hints for gather, rally, attack, and stationed rows.
private LocalDateTime resolveNoSlotRetry(List<MarchSlotState> marchQueue) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime earliest = resolveEarliestReturn(profile.getId());

        LocalDateTime panelEstimate = MarchSlotAvailabilityEstimator
                .estimateEarliestCheckAt(marchQueue, now, MARCH_SLOT_ESTIMATE_SETTINGS)
                .orElse(null);
        if (panelEstimate != null && (earliest == null || panelEstimate.isBefore(earliest))) {
            earliest = panelEstimate;
        }

        if (earliest == null) {
            logInfo(routineLogPolarTerrorHuntingLine(
                    "March queue has no usable wake-up estimate; rechecking in "
                            + STATIONED_MARCH_RETRY_MINUTES + " min"));
            earliest = now.plusMinutes(STATIONED_MARCH_RETRY_MINUTES);
        }

        LocalDateTime floor = now.plusSeconds(MIN_RETRY_SECONDS);
        return earliest.isBefore(floor) ? floor : earliest;
    }

private int requiredStaminaForRally() {
        return minStaminaLevel + MAX_POLAR_RALLY_STAMINA_COST;
    }

// Regeneration is waited out up to the refresh level rather than the bare minimum, so one long nap
// covers two rallies instead of waking for each.
private void rescheduleForStaminaRegen() {
        int current = staminaHelper.getCurrentStamina();
        int waitMinutes = staminaHelper.staminaRegenerationTime(current, refreshStaminaLevel);
        LocalDateTime retryAt = LocalDateTime.now().plusMinutes(waitMinutes);
        logInfo(routineLogPolarTerrorHuntingLine(String.format(
                "Stamina gate: waiting %d min for regeneration from %d to %d",
                waitMinutes, current, refreshStaminaLevel)));
        reschedule(retryAt);
    }

// Cheap in-memory guard before any navigation: when items cannot bridge the gap, opening the march
// panel only to discover the rally is unaffordable wastes a wake-up and a profile switch.
private boolean staminaCouldSupportRally() {
        if (useStaminaItems || staminaHelper.getCurrentStamina() >= requiredStaminaForRally())
            return true;

        rescheduleForStaminaRegen();
        return false;
    }

// A rally must not push stamina below the configured reserve, which is held back for Intel and
// rallies. With stamina items enabled the shortfall is topped up from the profile's Obtain-more
// dialog instead of waiting out the regeneration, so reserve and item usage stop contradicting.
private boolean ensureStaminaForRally() {
        int required = requiredStaminaForRally();
        int current = staminaHelper.getCurrentStamina();
        if (current >= required)
            return true;

        logInfo(routineLogPolarTerrorHuntingLine(String.format(
                "Stamina gate: current=%d required=%d (reserve %d + rally %d) useItems=%s",
                current, required, minStaminaLevel, MAX_POLAR_RALLY_STAMINA_COST, useStaminaItems)));

        if (useStaminaItems && staminaHelper.topUpFromProfile(required, staminaItemReserve))
            return true;

        rescheduleForStaminaRegen();
        return false;
    }

private void hydrateConfiguration() {
        Integer configuredLevel = profile.getConfig(ConfigurationKeyEnum.POLAR_TERROR_LEVEL_INT, Integer.class);
        this.polarTerrorLevel = configuredLevel != null ? configuredLevel : MIN_POLAR_LEVEL;

        String mode = profile.getConfig(ConfigurationKeyEnum.POLAR_TERROR_MODE_STRING, String.class);
        this.limitedHunting = "Limited (10)".equals(mode);

        Integer configuredMarches = profile.getConfig(ConfigurationKeyEnum.POLAR_TERROR_MARCHES_INT, Integer.class);
        this.maxMarches = configuredMarches != null ? configuredMarches : 1;

        Integer configReserve = profile.getConfig(ConfigurationKeyEnum.STAMINA_RESERVE_INT, Integer.class);
        this.minStaminaLevel = (configReserve != null) ? configReserve : DEFAULT_STAMINA_RESERVE;
        this.refreshStaminaLevel = this.minStaminaLevel + 50;

        Boolean configuredUseItems = profile.getConfig(ConfigurationKeyEnum.POLAR_TERROR_USE_STAMINA_ITEMS_BOOL, Boolean.class);
        this.useStaminaItems = configuredUseItems != null && configuredUseItems;

        Integer configuredItemReserve = profile.getConfig(ConfigurationKeyEnum.POLAR_TERROR_STAMINA_ITEM_RESERVE_INT, Integer.class);
        this.staminaItemReserve = configuredItemReserve != null ? Math.max(0, configuredItemReserve) : 0;

        Boolean configuredHighestLevel = profile.getConfig(ConfigurationKeyEnum.POLAR_TERROR_HIGHEST_LEVEL_BOOL, Boolean.class);
        this.huntHighestLevel = configuredHighestLevel != null && configuredHighestLevel;

        this.maxMarches = Math.min(6, Math.max(1, this.maxMarches));

        if (huntHighestLevel)
            return;

        if (this.polarTerrorLevel < MIN_POLAR_LEVEL) {
            logWarning(routineLogPolarTerrorHuntingLine("Configured Polar Terror level " + this.polarTerrorLevel
                    + " is below " + MIN_POLAR_LEVEL + ". Using " + MIN_POLAR_LEVEL + "."));
            this.polarTerrorLevel = MIN_POLAR_LEVEL;
        }
        if (this.polarTerrorLevel > MAX_POLAR_LEVEL_LIMIT) {
            logWarning(routineLogPolarTerrorHuntingLine("Configured Polar Terror level " + this.polarTerrorLevel
                    + " is above " + MAX_POLAR_LEVEL_LIMIT + ". Using " + MAX_POLAR_LEVEL_LIMIT + "."));
            this.polarTerrorLevel = MAX_POLAR_LEVEL_LIMIT;
        }
    }

private boolean polarsRemainingFlow(int polarLevel) {
        if (!openUpPolarsMenu(polarLevel)) {
            return false;
        }

        ImageSearchResultData magnifyingGlass = templateSearchHelper.locatePattern(
                TemplatesEnum.POLAR_TERROR_TAB_MAGNIFYING_GLASS_ICON,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
        sleepTask(500);

        if (!magnifyingGlass.isFound()) {
            return false;
        }

        tapPoint(magnifyingGlass.getPoint());
        sleepTask(2000);

        ImageSearchResultData specialRewardsCompleted = templateSearchHelper.locatePattern(
                TemplatesEnum.POLAR_TERROR_TAB_SPECIAL_REWARDS,
                SearchConfigConstants.QUICK_SEARCH);
        for (int i = 0; i < 5 && !specialRewardsCompleted.isFound(); i++) {
            specialRewardsCompleted = templateSearchHelper.locatePattern(
                    TemplatesEnum.POLAR_TERROR_TAB_SPECIAL_REWARDS,
                    SearchConfigConstants.QUICK_SEARCH);
            if (specialRewardsCompleted.isFound()) {
                logWarning(routineLogPolarTerrorHuntingLine("Zero special rewards left, meaning there's no hunts left for today. Planning next run task for reset"));
                reschedule(GameTimeUtils.dailyResetTime().plusMinutes(30));
                return false;
            }
            swipe(new PointData(363, 1088), new PointData(363, 1030));
            sleepTask(300);
        }

        pressBack();
        sleepTask(500);
        return true;
    }

private boolean openUpPolarsMenu(int polarLevel) {

        ImageSearchResultData polarTerror = ImageSearchResultData.miss();
        for (int attempt = 1; attempt <= 2 && !polarTerror.isFound(); attempt++) {
            navigationHelper.ensureCorrectScreenLocation(getRequiredStartLocation());
            logInfo(routineLogPolarTerrorHuntingLine(String.format(
                    "Search panel attempt %d/2: opening world search area", attempt)));
            tapRandomPoint(new PointData(25, 850), new PointData(67, 898));
            sleepTask(1200);

            swipe(new PointData(40, 913), new PointData(678, 913));
            sleepTask(500);

            polarTerror = templateSearchHelper.locatePattern(
                    TemplatesEnum.POLAR_TERROR_SEARCH_ICON,
                    SearchConfigConstants.SINGLE_WITH_RETRIES);
            for (int i = 0; i < 3 && !polarTerror.isFound(); i++) {
                swipe(new PointData(40, 913), new PointData(678, 913));
                sleepTask(500);
                polarTerror = templateSearchHelper.locatePattern(
                        TemplatesEnum.POLAR_TERROR_SEARCH_ICON,
                        SearchConfigConstants.SINGLE_WITH_RETRIES);
            }

            if (!polarTerror.isFound()) {
                logWarning(routineLogPolarTerrorHuntingLine(String.format(
                        "Search panel attempt %d/2: Polar Terror tab not verified", attempt)));
                pressBack();
                sleepTask(500);
            }
        }

        if (!polarTerror.isFound()) {
            logWarning(routineLogPolarTerrorHuntingLine("Polar Terror tab not found after swiping search tabs"));
            return false;
        }

        tapPoint(polarTerror.getPoint());
        sleepTask(500);
        if (polarLevel != -1) {
            if (huntHighestLevel) {
                selectHighestPolarLevel();
            } else {
                if (polarLevel < MIN_POLAR_LEVEL || polarLevel > MAX_POLAR_LEVEL_LIMIT) {
                    logError(routineLogPolarTerrorHuntingLine(String.format("Invalid Polar Terror level configured: %d. Must be between %d and %d.",
                            polarLevel, MIN_POLAR_LEVEL, MAX_POLAR_LEVEL_LIMIT)));
                    return false;
                }
                selectPolarLevel(polarLevel);
            }
        }

        tapRandomPoint(new PointData(301, 1200), new PointData(412, 1229));
        sleepTask(1500);
        return true;
    }

private RallyLaunchResult openUpRallyMenu() {

        ImageSearchResultData rallyButton = templateSearchHelper.locatePattern(
                TemplatesEnum.RALLY_BUTTON,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
        sleepTask(500);

        if (!rallyButton.isFound()) {
            return fail(RallyLaunchOutcome.RALLY_BUTTON_MISSING,
                    "Rally button was not found after Polar Terror search; known causes: no target found, locked level, or wrong result screen");
        }

        logInfo(routineLogPolarTerrorHuntingLine(String.format(
                "Rally button found at %s score=%.2f",
                rallyButton.getPoint(), rallyButton.getMatchScore())));
        tapPoint(rallyButton.getPoint());
        sleepTask(1000);

        if (deploymentHelper.isMarchQueueFull()) {
            sleepTask(500);
            return fail(RallyLaunchOutcome.MARCH_QUEUE_FULL,
                    "March Queue popup opened after pressing Rally; no march slot available for a new rally");
        }

        ImageSearchResultData holdRallyButton = templateSearchHelper.locatePattern(
                TemplatesEnum.RALLY_HOLD_BUTTON,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!holdRallyButton.isFound()) {
            logWarning(routineLogPolarTerrorHuntingLine("Hold Rally button not found after pressing Rally"));
            return fail(RallyLaunchOutcome.HOLD_RALLY_MISSING,
                    "Hold Rally button was not found after pressing Rally");
        }

        rallySetTimeSeconds = deploymentHelper.readRallySetTimeSeconds(DEFAULT_RALLY_SET_TIME_SECONDS);
        tapPoint(holdRallyButton.getPoint());
        sleepTask(1000);
        return ok("Hold Rally opened formation screen");
    }

// Drives the selector against its ceiling instead of trusting a hard-coded maximum, so a server that
// unlocks a new polar level needs no config change. A level that stops rising marks the top.
private void selectHighestPolarLevel() {
        Integer currentLevel = readPolarLevelSelection();
        if (currentLevel == null) {
            logWarning(routineLogPolarTerrorHuntingLine(
                    "Level selector: OCR unreadable; cannot determine the highest level, keeping the current selection"));
            return;
        }

        for (int attempt = 1; attempt <= LEVEL_SELECTOR_MAX_ADJUSTMENTS; attempt++) {
            tapRandomPoint(LEVEL_PLUS_BUTTON, LEVEL_PLUS_BUTTON, LEVEL_SELECTOR_SWEEP_TAPS, 40);
            sleepTask(500);

            Integer updatedLevel = readPolarLevelSelection();
            if (updatedLevel == null) {
                logWarning(routineLogPolarTerrorHuntingLine(
                        "Level selector: post-sweep OCR unreadable; continuing at level " + currentLevel));
                return;
            }
            if (updatedLevel.equals(currentLevel)) {
                logInfo(routineLogPolarTerrorHuntingLine("Level selector: highest selectable level is " + currentLevel));
                polarTerrorLevel = currentLevel;
                return;
            }
            currentLevel = updatedLevel;
        }

        logWarning(routineLogPolarTerrorHuntingLine(String.format(
                "Level selector: level still rising after %d sweeps; continuing at %d",
                LEVEL_SELECTOR_MAX_ADJUSTMENTS, currentLevel)));
        polarTerrorLevel = currentLevel;
    }

// Steers the selector by reading the level back after every adjustment. The lowest selectable level
// is not always 1 - older profiles start at 4 - so counting taps from an assumed floor would land on
// the wrong level. A level that refuses to move marks the end of the selector's range.
private void selectPolarLevel(int polarLevel) {
        Integer currentLevel = readPolarLevelSelection();
        if (currentLevel == null) {
            logWarning(routineLogPolarTerrorHuntingLine(
                    "Level selector: OCR unreadable; resetting to the selector minimum before retrying"));
            tapRandomPoint(LEVEL_MINUS_BUTTON, LEVEL_MINUS_BUTTON, LEVEL_SELECTOR_SWEEP_TAPS, 40);
            sleepTask(500);
            currentLevel = readPolarLevelSelection();
        }
        if (currentLevel == null) {
            logWarning(routineLogPolarTerrorHuntingLine(
                    "Level selector: still unreadable; hunting at the selector minimum, which is the safest target"));
            return;
        }

        for (int attempt = 1; attempt <= LEVEL_SELECTOR_MAX_ADJUSTMENTS && currentLevel != polarLevel; attempt++) {
            int delta = polarLevel - currentLevel;
            PointData button = delta > 0 ? LEVEL_PLUS_BUTTON : LEVEL_MINUS_BUTTON;
            logInfo(routineLogPolarTerrorHuntingLine(String.format(
                    "Level selector: current=%d target=%d; adjusting with %d %s tap(s)",
                    currentLevel, polarLevel, Math.abs(delta), delta > 0 ? "plus" : "minus")));
            tapRandomPoint(button, button, Math.abs(delta), 60);
            sleepTask(500);

            Integer updatedLevel = readPolarLevelSelection();
            if (updatedLevel == null) {
                logWarning(routineLogPolarTerrorHuntingLine(
                        "Level selector: post-adjustment OCR unreadable; continuing with the selected UI state"));
                return;
            }
            if (updatedLevel.equals(currentLevel)) {
                logWarning(routineLogPolarTerrorHuntingLine(String.format(
                        "Level selector: stuck at %d while aiming for %d; this profile cannot select that level",
                        currentLevel, polarLevel)));
                return;
            }
            currentLevel = updatedLevel;
        }

        if (currentLevel == polarLevel) {
            logInfo(routineLogPolarTerrorHuntingLine("Level selector: confirmed level " + polarLevel));
        } else {
            logWarning(routineLogPolarTerrorHuntingLine(String.format(
                    "Level selector: settled at %d instead of %d after %d adjustments",
                    currentLevel, polarLevel, LEVEL_SELECTOR_MAX_ADJUSTMENTS)));
        }
    }

private Integer readPolarLevelSelection() {
        return integerHelper.attemptRecognition(
                CommonGameAreas.POLAR_LEVEL_DISPLAY.topLeft(),
                CommonGameAreas.POLAR_LEVEL_DISPLAY.bottomRight(),
                2,
                100L,
                CommonOCRSettings.POLAR_LEVEL_SETTINGS,
                text -> text != null && text.matches(".*\\d+.*"),
                text -> {
                    String digits = text.replaceAll("\\D+", "");
                    return digits.isBlank() ? null : Integer.parseInt(digits);
                });
    }

private RallyLaunchResult ok(String detail) {
        return new RallyLaunchResult(RallyLaunchOutcome.SUCCESS, detail);
    }

private RallyLaunchResult fail(RallyLaunchOutcome outcome, String detail) {
        return new RallyLaunchResult(outcome, detail);
    }
}
