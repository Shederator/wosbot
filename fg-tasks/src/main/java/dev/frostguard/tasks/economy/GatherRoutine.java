package dev.frostguard.tasks.economy;

import java.awt.Color;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;
import dev.frostguard.data.entity.DailyTask;
import dev.frostguard.data.repository.DailyTaskRepository;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.MarchActivityType;
import dev.frostguard.api.domain.MarchMovementPhase;
import dev.frostguard.api.domain.MarchResourceType;
import dev.frostguard.api.domain.MarchSlotAvailability;
import dev.frostguard.api.domain.MarchSlotState;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.engine.helper.MarchSlotAvailabilityEstimator;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.GatherQueuePolicy;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.service.StatisticsService;

/**
 * Optimized GatherRoutine: Manages persistent resource rotation, fairness, and
 * efficient queue utilization.
 */
public class GatherRoutine extends DelayedTask {

    // ========== Constants & Config Keys ==========
    private static final int DEFAULT_QUEUES = 6;
    private static final int DEFAULT_LEVEL = 5;
    private static final boolean DEFAULT_REMOVE_HEROES = false;
    private static final boolean DEFAULT_INTEL_SMART = false;
    private static final int PENDING_HIGH_PRIORITY_RETRY_MINUTES = 5;
    // pernerch/2026-07-02: lookahead for dual-event detection (Intel + Bear within this window → defer both)
    private static final int DUAL_EVENT_LOOKAHEAD_MINUTES = 15;
    // pernerch/2026-07-02: initial margin added after max march return time before re-deploying
    private static final int TROOP_RETURN_MARGIN_MINUTES = 2;
    // pernerch/2026-07-02: retry interval when recalled troops are still marching home
    private static final int TROOP_RETURN_RETRY_MINUTES = 1;
    // pernerch/2026-07-02: Bear Trap active duration (30 min) used to estimate end time for defer calculation
    private static final int BEAR_TRAP_DURATION_MINUTES = 30;
    private static final int LOWER_BOUND_RECHECK_BUFFER_MINUTES = 1;
    private static final int GATHER_RETURN_BUFFER_MINUTES = 5;
    private static final int UNKNOWN_MARCH_RETRY_MINUTES = 5;
    private static final int STATIONED_MARCH_RETRY_MINUTES = 60;
    private static final MarchSlotAvailabilityEstimator.Settings MARCH_SLOT_ESTIMATE_SETTINGS =
            new MarchSlotAvailabilityEstimator.Settings(
                    Duration.ofMinutes(LOWER_BOUND_RECHECK_BUFFER_MINUTES),
                    Duration.ofMinutes(GATHER_RETURN_BUFFER_MINUTES),
                    Duration.ofMinutes(UNKNOWN_MARCH_RETRY_MINUTES),
                    Duration.ofMinutes(STATIONED_MARCH_RETRY_MINUTES));

    // Region Constants (UI)
    private static final MarchQueueRegion[] MARCH_QUEUES = {
            new MarchQueueRegion(new PointData(10, 342), new PointData(435, 407)),
            new MarchQueueRegion(new PointData(10, 415), new PointData(435, 480)),
            new MarchQueueRegion(new PointData(10, 488), new PointData(435, 553)),
            new MarchQueueRegion(new PointData(10, 561), new PointData(435, 626)),
            new MarchQueueRegion(new PointData(10, 634), new PointData(435, 699)),
            new MarchQueueRegion(new PointData(10, 707), new PointData(435, 772)),
    };

    // PointData Constants (UI)
    private static final PointData SEARCH_BTN_TL = new PointData(25, 850);
    private static final PointData SEARCH_BTN_BR = new PointData(67, 898);
    private static final PointData RES_TAB_SWIPE_START = new PointData(678, 913);
    private static final PointData RES_TAB_SWIPE_END = new PointData(40, 913);
    private static final PointData LEVEL_DISPLAY_TL = new PointData(78, 991);
    private static final PointData LEVEL_DISPLAY_BR = new PointData(474, 1028);
    private static final PointData LEVEL_SLIDER_START = new PointData(435, 1052);
    private static final PointData LEVEL_SLIDER_END = new PointData(40, 1052);
    private static final PointData LEVEL_INC_TL = new PointData(470, 1040);
    private static final PointData LEVEL_INC_BR = new PointData(500, 1066);
    private static final PointData LEVEL_DEC_TL = new PointData(50, 1040);
    private static final PointData LEVEL_DEC_BR = new PointData(85, 1066);
    private static final PointData ONLY_FULL_RESOURCES_TOGGLE = new PointData(183, 1140);
    private static final AreaData ONLY_FULL_RESOURCES_TICK_AREA =
            new AreaData(new PointData(145, 1110), new PointData(225, 1170));
    private static final PointData SEARCH_EXEC_TL = new PointData(301, 1200);
    private static final PointData SEARCH_EXEC_BR = new PointData(412, 1229);
    private static final PointData RECALL_CONFIRM_TL = new PointData(446, 780);
    private static final PointData RECALL_CONFIRM_BR = new PointData(578, 800);

    private final DailyTaskRepository dailyTaskRepository = DailyTaskRepository.getRepository();

    // ========== State & Configuration ==========
    private int activeQueues;
    private boolean removeHeroes;
    private boolean intelSmart;
    private boolean intelRecall;
    private boolean intelEnabled;
    private boolean gatherSpeed;
    private boolean autoJoinEnabled;
    private boolean onlyFullResources;
    private boolean downgradeLevelOnMissingNode;

    private List<GatherType> enabledTypes;
    private List<GatherType> rotationPool;
    private LocalDateTime earliestReschedule;
    private ResilientOcrExecutor<LocalDateTime> textHelper;
    // pernerch/2026-07-02: stored per-profile task instance (one GatherRoutine per profile).
    // Records when gather troops were recalled by Intel or Bear Trap so we can wait for them
    // to return before re-deploying. Also persisted to profile config for crash recovery.
    private LocalDateTime lastRecallTime;

    public GatherRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    // ================= EXECUTE =================

    @Override
    protected void execute() {
        loadConfig();

        if (enabledTypes.isEmpty()) {
            logInfo("No gather types enabled. Disabling task.");
            setRecurring(false);
            return;
        }

        // pernerch/2026-07-02: after an Intel/Bear recall, wait until troops are home before re-deploying.
        // Checks the per-profile recall timestamp and extends by 1 minute if troops are still out.
        if (checkTroopReturnPending())
            return;

        // pernerch/2026-07-02: replaces blind 35-min reschedule with smart dual-event (Intel+Bear)
        // awareness, actual march recall, and defer calculation based on real event end times.
        if (checkHighPriorityEventConflict())
            return;
        if (checkGatherSpeedWait())
            return;
        // 1. Read the shared March Queue model and derive gather-specific state from it.
        GatherMarchSnapshot marchSnapshot = readGatherMarchSnapshot();
        List<GatherType> activeMarches = new ArrayList<>(marchSnapshot.activeTypes());
        int activeGatherCount = marchSnapshot.activeGatherCount();
        int idleSlotCount = marchSnapshot.idleSlotCount();
        logInfo(String.format("Active gather marches: %d / %d; idle physical march slots: %d",
                activeGatherCount, activeQueues, idleSlotCount));

        // Changed by pernerch | Date: 2026-07-02 | Why: continuously self-heal gather state
        // by recalling duplicate gather marches when active gathers exceed configured queue limit.
        int recalledOverflow = recallDuplicateOverflowGatherMarchesFlow();
        if (recalledOverflow > 0) {
            logInfo(String.format(
                "Corrected gather overflow by recalling %d duplicate march(es). Re-scanning active marches.",
                recalledOverflow));
            sleepTask(500);
            earliestReschedule = null;
            marchSnapshot = readGatherMarchSnapshot();
            activeMarches = new ArrayList<>(marchSnapshot.activeTypes());
            activeGatherCount = marchSnapshot.activeGatherCount();
            idleSlotCount = marchSnapshot.idleSlotCount();
            logInfo(String.format("Active gather marches after correction: %d / %d; idle physical march slots: %d",
                    activeGatherCount, activeQueues, idleSlotCount));
        }

        // Changed by pernerch | Date: 2026-07-02 | Why: when higher-priority tasks are pending,
        // defer based on real active-march timing instead of a blind fixed delay.
        List<TpDailyTaskEnum> pendingHigherPriorityTasks = GatherQueuePolicy.getPendingHigherPriorityMarchTasks(profile);
        if (!pendingHigherPriorityTasks.isEmpty()) {
            if (pendingHigherPriorityTasks.contains(TpDailyTaskEnum.INTEL) && triggerPendingIntelNowFlow()) {
                return;
            }

            if (activeGatherCount > 0) {
                LocalDateTime next = earliestReschedule != null
                        ? earliestReschedule
                        : marchSnapshot.nextCheckAt() != null ? marchSnapshot.nextCheckAt() : LocalDateTime.now().plusMinutes(5);
                logInfo(String.format(
                "Deferring gather deployment because higher-priority march task(s) are pending: %s. " +
                    "%d gather march(es) are outside; next return at %s.",
                pendingHigherPriorityTasks,
                        activeGatherCount,
                        GameTimeUtils.formatCountdown(next)));
                reschedule(next);
            } else {
            LocalDateTime retryAt = LocalDateTime.now().plusMinutes(PENDING_HIGH_PRIORITY_RETRY_MINUTES);
            logInfo(String.format(
                "Deferring gather deployment because higher-priority march task(s) are pending: %s. " +
                    "No active gather marches are outside; retrying in %d minutes at %s to avoid noisy rechecks.",
                pendingHigherPriorityTasks,
                PENDING_HIGH_PRIORITY_RETRY_MINUTES,
                GameTimeUtils.formatCountdown(retryAt)));
            reschedule(retryAt);
            }
            return;
        }

        if (idleSlotCount <= 0 && activeGatherCount < activeQueues) {
            if (!autoJoinEnabled) {
                int recalledBlockedMarches = recallBlockedMarchesWhenAutojoinOffFlow();
                if (recalledBlockedMarches > 0) {
                    LocalDateTime retryAt = LocalDateTime.now().plusMinutes(1);
                    logInfo(String.format(
                            "Autojoin is disabled and all physical march slots are blocked. Recalled %d march(es); rechecking gather in 1 minute at %s.",
                            recalledBlockedMarches,
                            GameTimeUtils.formatCountdown(retryAt)));
                    reschedule(retryAt);
                    return;
                }
            }

            LocalDateTime retryAt = marchSnapshot.nextCheckAt() != null
                    ? marchSnapshot.nextCheckAt()
                    : LocalDateTime.now().plusMinutes(UNKNOWN_MARCH_RETRY_MINUTES);
            logInfo(String.format(
                    "No idle physical march slot is available for gather. Rechecking at %s.",
                    GameTimeUtils.formatCountdown(retryAt)));
            reschedule(retryAt);
            return;
        }

        if (activeGatherCount >= activeQueues && earliestReschedule == null) {
            // Only fall back to 5-min polling if the next check is near or unknown.
            LocalDateTime nextGatherCheck = marchSnapshot.earliestGatherCheckAt();
            LocalDateTime retryAt;
            if (nextGatherCheck != null && ChronoUnit.MINUTES.between(LocalDateTime.now(), nextGatherCheck) > 10) {
                retryAt = nextGatherCheck.plusMinutes(TROOP_RETURN_MARGIN_MINUTES);
                logInfo(String.format(
                        "All configured gather queues are currently active%s. " +
                        "Scheduling next gather check at %s.",
                        autoJoinEnabled ? " (autojoin enabled)" : " (autojoin disabled)",
                        GameTimeUtils.formatCountdown(retryAt)));
            } else {
                retryAt = LocalDateTime.now().plusMinutes(5);
                logInfo(String.format(
                        "All configured gather queues are currently active%s. " +
                        "Next check is near or unknown - retrying in 5 minutes at %s.",
                        autoJoinEnabled ? " (autojoin enabled)" : " (autojoin disabled)",
                        GameTimeUtils.formatCountdown(retryAt)));
            }
            reschedule(retryAt);
            return;
        }

        // 2. Fill Queues (Persistent Rotation)
        GatherFillResult fillResult = fillQueues(activeGatherCount, idleSlotCount, activeMarches);

        // 3. Save & Finalize
        finalizeReschedule(fillResult);
    }

    // ================= CONFIGURATION =================

    private void loadConfig() {
        // Changed by pernerch | Date: 2026-07-02 | Why: centralize queue limit via policy for consistent hard-cap behavior.
        this.activeQueues = GatherQueuePolicy.resolveActiveQueueLimit(
                get(ConfigurationKeyEnum.GATHER_ACTIVE_MARCH_QUEUE_INT, DEFAULT_QUEUES));
        this.removeHeroes = get(ConfigurationKeyEnum.GATHER_REMOVE_HEROS_BOOL, DEFAULT_REMOVE_HEROES);
        this.intelSmart = get(ConfigurationKeyEnum.INTEL_SMART_PROCESSING_BOOL, DEFAULT_INTEL_SMART);
        this.intelRecall = get(ConfigurationKeyEnum.INTEL_RECALL_GATHER_TROOPS_BOOL, false);
        this.intelEnabled = get(ConfigurationKeyEnum.INTEL_BOOL, false);
        this.gatherSpeed = get(ConfigurationKeyEnum.GATHER_SPEED_BOOL, false);
        this.autoJoinEnabled = get(ConfigurationKeyEnum.ALLIANCE_AUTOJOIN_BOOL, false);
        this.onlyFullResources = get(ConfigurationKeyEnum.GATHER_ONLY_FULL_RESOURCES_BOOL, false);
        this.downgradeLevelOnMissingNode = get(ConfigurationKeyEnum.GATHER_DOWNGRADE_LEVEL_BOOL, true);

        this.enabledTypes = Arrays.stream(GatherType.values())
                .filter(this::isTypeEnabled)
                .collect(Collectors.toList());

        loadRotationPool();
        if (rotationPool != null) {
            rotationPool.retainAll(enabledTypes);
            saveRotationPool(); // Ensure consistent state
        }

        this.textHelper = new ResilientOcrExecutor<>(provider);
        this.earliestReschedule = null;
        // pernerch/2026-07-02: restore recall timestamp from profile config so it survives task restarts.
        String recallTimeStr = profile.getConfig(ConfigurationKeyEnum.GATHER_LAST_RECALL_TIME_STRING, String.class);
        if (recallTimeStr != null && !recallTimeStr.isEmpty()) {
            try { this.lastRecallTime = LocalDateTime.parse(recallTimeStr); }
            catch (Exception ignored) { this.lastRecallTime = null; }
        }
    }

    private boolean isTypeEnabled(GatherType type) {
        return get(type.enabledKey, false);
    }

    @SuppressWarnings("unchecked")
    private <T> T get(ConfigurationKeyEnum key, T defaultValue) {
        T val = profile.getConfig(key, (Class<T>) defaultValue.getClass());
        return val != null ? val : defaultValue;
    }

    // ================= ROTATION LOGIC =================

    private GatherFillResult fillQueues(int currentActive, int idleSlotCount, List<GatherType> activeMarches) {
        int freeSlots = Math.min(activeQueues - currentActive, idleSlotCount);
        int deployed = 0;
        int noNode = 0;
        int blocked = 0;
        logInfo(String.format("Gather fill: active=%d/%d idleSlots=%d freeSlots=%d pool=%s",
                currentActive, activeQueues, idleSlotCount, freeSlots, rotationPool));

        // Remove types already marching from the current pool for initial fairness
        if (rotationPool.removeAll(activeMarches)) {
            logDebug("Removed active gather types from pool: " + activeMarches);
            saveRotationPool();
        }

        // If pool is empty after removing active marches but there are free slots,
        // allow duplicate types so we can fill all available march queues
        if (rotationPool.isEmpty() && freeSlots > 0) {
            logDebug("Gather pool empty after removing active types; allowing duplicates for remaining slots.");
            rotationPool = new ArrayList<>(enabledTypes);
        }

        if (freeSlots <= 0) {
            saveRotationPool();
            return new GatherFillResult(0, 0, 0, false);
        }

        // Shuffle loaded pool for randomness in this run
        Collections.shuffle(rotationPool);

        int remaining = freeSlots;
        int safetyLoop = 0;
        Set<GatherType> unavailableThisRun = new HashSet<>();

        while (remaining > 0 && safetyLoop++ < 10) {

            // Refill if empty
            if (rotationPool.isEmpty()) {
                logDebug("Gather pool empty. Resetting.");
                rotationPool = new ArrayList<>(enabledTypes);
                rotationPool.removeAll(unavailableThisRun);
                // Don't remove active marches on refill â€” duplicates are needed
                // to fill remaining slots when activeQueues > enabledTypes.size()
                Collections.shuffle(rotationPool);
            }

            // Try ALL pool items â€” don't limit to remaining, so if one type fails
            // we still try others. The inner loop stops when slots are full.
            List<GatherType> batch = rotationPool.stream()
                    .filter(type -> !unavailableThisRun.contains(type))
                    .collect(Collectors.toCollection(ArrayList::new));

            if (batch.isEmpty())
                break;

            boolean progress = false;
            for (GatherType type : batch) {
                if (remaining <= 0 || currentActive >= activeQueues)
                    break;

                GatherDeployResult deployResult = deploy(type);
                if (deployResult == GatherDeployResult.DEPLOYED) {
                    currentActive++;
                    remaining--;
                    deployed++;
                    rotationPool.remove(type);
                    progress = true;
                    StatisticsService.obtain().addToCounter(profile, "Gather Marches Deployed", 1);
                    activeMarches.add(type); // Add to avoid re-picking if we loop
                } else {
                    if (deployResult == GatherDeployResult.NO_TROOPS_AVAILABLE) {
                        remaining = 0;
                        saveRotationPool();
                        return new GatherFillResult(deployed, noNode, blocked, true);
                    }
                    // Remove failed type from pool to avoid retrying it endlessly
                    rotationPool.remove(type);
                    if (deployResult == GatherDeployResult.NO_NODE_FOUND) {
                        noNode++;
                        unavailableThisRun.add(type);
                    }
                    if (deployResult == GatherDeployResult.BLOCKED) {
                        blocked++;
                    }
                }
            }

            if (!progress || currentActive >= activeQueues)
                break;
        }

        saveRotationPool();
        return new GatherFillResult(deployed, noNode, blocked, false);
    }

    private void loadRotationPool() {
        String saved = profile.getConfig(ConfigurationKeyEnum.GATHER_ROTATION_POOL, String.class);
        logDebug("Loaded gather pool config: '" + saved + "'");
        if (saved == null || saved.isEmpty()) {
            rotationPool = new ArrayList<>(enabledTypes);
            logDebug("Gather pool config empty. Resetting to full: " + rotationPool);
            return;
        }
        try {
            rotationPool = Arrays.stream(saved.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(GatherType::valueOf)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            rotationPool = new ArrayList<>(enabledTypes);
            logDebug("Could not parse gather pool config; resetting: " + e.getMessage());
        }
    }

    private void saveRotationPool() {
        if (rotationPool == null)
            return;
        String val = rotationPool.stream().map(Enum::name).collect(Collectors.joining(","));
        logDebug("Saving gather pool config: '" + val + "'");
        profile.setConfig(ConfigurationKeyEnum.GATHER_ROTATION_POOL, val);
        setShouldUpdateConfig(true);
    }

    // ================= SCAN & CHECKS =================

    private GatherMarchSnapshot readGatherMarchSnapshot() {
        List<MarchSlotState> slots = marchHelper.readMarchQueue();
        List<GatherType> activeTypes = slots.stream()
                .filter(slot -> slot.activityType() == MarchActivityType.GATHER)
                .map(slot -> toGatherType(slot.resourceType()))
                .filter(type -> type != null)
                .collect(Collectors.toCollection(ArrayList::new));

        int activeGatherCount = (int) slots.stream()
                .filter(slot -> slot.activityType() == MarchActivityType.GATHER)
                .count();
        int idleSlotCount = (int) slots.stream()
                .filter(slot -> slot.availability() == MarchSlotAvailability.IDLE)
                .count();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime nextCheckAt = MarchSlotAvailabilityEstimator
                .estimateEarliestCheckAt(slots, now, MARCH_SLOT_ESTIMATE_SETTINGS)
                .orElse(null);
        LocalDateTime earliestGatherCheckAt = slots.stream()
                .filter(slot -> slot.activityType() == MarchActivityType.GATHER)
                .map(slot -> MarchSlotAvailabilityEstimator
                        .estimateNextCheck(slot, MARCH_SLOT_ESTIMATE_SETTINGS)
                        .map(now::plus)
                        .orElse(null))
                .filter(time -> time != null)
                .min(Comparator.naturalOrder())
                .orElse(null);

        return new GatherMarchSnapshot(slots, activeTypes, activeGatherCount, idleSlotCount,
                nextCheckAt, earliestGatherCheckAt);
    }

    private GatherType toGatherType(MarchResourceType resourceType) {
        if (resourceType == null) {
            return null;
        }
        return switch (resourceType) {
            case MEAT -> GatherType.MEAT;
            case WOOD -> GatherType.WOOD;
            case COAL -> GatherType.COAL;
            case IRON -> GatherType.IRON;
            case UNKNOWN -> null;
        };
    }

    // Changed by pernerch | Date: 2026-07-02 | Why: when autojoin is disabled and all gather
    // slots are blocked, recall already-recallable marches before falling back to a fixed wait.
    private int recallBlockedMarchesWhenAutojoinOffFlow() {
        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);
        sleepTask(250);
        marchHelper.openLeftMenuCitySection(false);

        try {
            PointData limit = new PointData(415,
                    MARCH_QUEUES[MARCH_QUEUES.length - 1].bottomRight.getY());

            List<ImageSearchResultData> recallButtons = templateSearchHelper.locateAllPatterns(
                    TemplatesEnum.MARCHES_AREA_RECALL_BUTTON,
                    SearchConfig.builder()
                            .withArea(new AreaData(MARCH_QUEUES[0].topLeft, limit))
                            .withMaxAttempts(3)
                            .withMaxResults(MARCH_QUEUES.length)
                            .withDelay(3)
                            .build());

            if (recallButtons == null || recallButtons.isEmpty()) {
                return 0;
            }

            recallButtons.sort(Comparator.comparingInt(button -> button.getPoint().getY()));
            int recalled = 0;

            for (ImageSearchResultData recallButton : recallButtons) {
                if (recallButton == null || !recallButton.isFound()) {
                    continue;
                }

                tapRandomPoint(recallButton.getPoint(), recallButton.getPoint(), 1, 200);
                tapRandomPoint(RECALL_CONFIRM_TL, RECALL_CONFIRM_BR, 1, 200);
                sleepTask(400);
                recalled++;
            }

            return recalled;
        } finally {
            marchHelper.closeLeftMenu();
        }
    }

    // Changed by pernerch | Date: 2026-07-02 | Why: enforce configured gather queue size by
    // recalling duplicate long-running marches whenever active gather count overflows.
    private int recallDuplicateOverflowGatherMarchesFlow() {
        List<ActiveGatherMarchCandidate> candidates = collectActiveGatherMarchCandidatesFlow();
        int overflow = candidates.size() - activeQueues;
        if (overflow <= 0) {
            return 0;
        }

        // Changed by pernerch | Date: 2026-07-02 | Why: always honor configured gather types first;
        // marches on disabled resource types are recalled before duplicate-type cleanup.
        List<ActiveGatherMarchCandidate> disabledTypeCandidates = candidates.stream()
                .filter(candidate -> !enabledTypes.contains(candidate.type()))
                .sorted(Comparator.comparing(ActiveGatherMarchCandidate::returnTime).reversed())
                .collect(Collectors.toCollection(ArrayList::new));

        List<ActiveGatherMarchCandidate> duplicateCandidates = candidates.stream()
                .collect(Collectors.groupingBy(ActiveGatherMarchCandidate::type))
                .values()
                .stream()
                .filter(group -> group.size() > 1)
                .flatMap(List::stream)
                .sorted(Comparator.comparing(ActiveGatherMarchCandidate::returnTime).reversed())
                .collect(Collectors.toCollection(ArrayList::new));

        // Changed by pernerch | Date: 2026-07-02 | Why: if overflow remains after disabled/duplicate
        // cleanup, recall longest-return marches to guarantee configured queue cap.
        List<ActiveGatherMarchCandidate> fallbackCandidates = candidates.stream()
                .sorted(Comparator.comparing(ActiveGatherMarchCandidate::returnTime).reversed())
                .collect(Collectors.toCollection(ArrayList::new));

        int recalled = 0;
        Set<Integer> recalledQueues = new HashSet<>();

        while (overflow > 0) {
            ActiveGatherMarchCandidate candidate = null;
            RecallReason recallReason = null;

            while (candidate == null && !disabledTypeCandidates.isEmpty()) {
                ActiveGatherMarchCandidate next = disabledTypeCandidates.remove(0);
                if (!recalledQueues.contains(next.queueIndex())) {
                    candidate = next;
                    recallReason = RecallReason.DISABLED_TYPE;
                }
            }

            while (candidate == null && !duplicateCandidates.isEmpty()) {
                ActiveGatherMarchCandidate next = duplicateCandidates.remove(0);
                if (!recalledQueues.contains(next.queueIndex())) {
                    candidate = next;
                    recallReason = RecallReason.DUPLICATE_TYPE;
                }
            }

            while (candidate == null && !fallbackCandidates.isEmpty()) {
                ActiveGatherMarchCandidate next = fallbackCandidates.remove(0);
                if (!recalledQueues.contains(next.queueIndex())) {
                    candidate = next;
                    recallReason = RecallReason.OVERFLOW_FALLBACK;
                }
            }

            if (candidate == null) {
                break;
            }

            if (recallGatherMarchByQueueFlow(candidate, recallReason)) {
                recalled++;
                overflow--;
                recalledQueues.add(candidate.queueIndex());
            }
        }

        return recalled;
    }

    // Changed by pernerch | Date: 2026-07-02 | Why: build a typed snapshot of active gather
    // marches (type, queue row, return time) to support deterministic overflow correction.
    private List<ActiveGatherMarchCandidate> collectActiveGatherMarchCandidatesFlow() {
        List<ActiveGatherMarchCandidate> candidates = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (MarchSlotState slot : marchHelper.readMarchQueue()) {
            if (slot.activityType() != MarchActivityType.GATHER) {
                continue;
            }

            GatherType type = toGatherType(slot.resourceType());
            if (type == null) {
                logDebug("Skipping active gather row with unknown resource type: #" + slot.slot()
                        + " evidence=" + slot.evidence());
                continue;
            }

            LocalDateTime returnTime = MarchSlotAvailabilityEstimator
                    .estimateNextCheck(slot, MARCH_SLOT_ESTIMATE_SETTINGS)
                    .map(now::plus)
                    .orElse(now.plusMinutes(UNKNOWN_MARCH_RETRY_MINUTES));
            candidates.add(new ActiveGatherMarchCandidate(type, slot.slot() - 1, returnTime));
        }
        return candidates;
    }

    // Changed by pernerch | Date: 2026-07-02 | Why: target a specific gather row for recall
    // so overflow cleanup removes the intended duplicate march.
    private boolean recallGatherMarchByQueueFlow(ActiveGatherMarchCandidate candidate, RecallReason reason) {
        int queueIndex = candidate.queueIndex();
        // Changed by pernerch | Date: 2026-07-02 | Why: enforce world context before opening
        // march list to avoid recall misses caused by transient non-world UI states.
        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);
        sleepTask(250);
        marchHelper.openLeftMenuCitySection(false);
        try {
            PointData limit = new PointData(415,
                    MARCH_QUEUES[MARCH_QUEUES.length - 1].bottomRight.getY());

            List<ImageSearchResultData> recallButtons = templateSearchHelper.locateAllPatterns(
                    TemplatesEnum.MARCHES_AREA_RECALL_BUTTON,
                    SearchConfig.builder()
                            .withArea(new AreaData(MARCH_QUEUES[0].topLeft, limit))
                            .withMaxAttempts(3)
                            .withMaxResults(MARCH_QUEUES.length)
                            .withDelay(3)
                            .build());

            if (recallButtons.isEmpty()) {
                return false;
            }

            int targetCenterY = (MARCH_QUEUES[queueIndex].topLeft.getY() + MARCH_QUEUES[queueIndex].bottomRight.getY()) / 2;
            ImageSearchResultData targetButton = recallButtons.stream()
                    .min(Comparator.comparingInt(button -> Math.abs(button.getPoint().getY() - targetCenterY)))
                    .orElse(null);

            if (targetButton == null) {
                return false;
            }

            tapRandomPoint(targetButton.getPoint(), targetButton.getPoint(), 1, 200);
            tapRandomPoint(RECALL_CONFIRM_TL, RECALL_CONFIRM_BR, 1, 200);
            // Changed by pernerch | Date: 2026-07-02 | Why: emit a deterministic recall reason
            // so operators can verify why each gather march was recalled.
            logInfo(String.format(
                    "Gather overflow recall | reason=%s | queue=#%d | type=%s | return=%s",
                    reason.logValue,
                    queueIndex + 1,
                    candidate.type(),
                    GameTimeUtils.formatCountdown(candidate.returnTime())));
            return true;
        } finally {
            marchHelper.closeLeftMenu();
        }
    }

    // ================= DEPLOYMENT PIPELINE =================

    private GatherDeployResult deploy(GatherType type) {
        int targetLevel = get(type.levelKey, DEFAULT_LEVEL);
        int minLevel = downgradeLevelOnMissingNode ? 1 : targetLevel;

        for (int level = targetLevel; level >= minLevel; level--) {
            logInfo(String.format("Gather deploy attempt: type=%s level=%d onlyFull=%s", type, level, onlyFullResources));

            if (!openSearchMenu()) {
                return retryLater(GatherDeployResult.BLOCKED);
            }
            if (!selectTile(type)) {
                return retryLater(GatherDeployResult.BLOCKED);
            }
            if (!setLevel(level)) {
                return retryLater(GatherDeployResult.BLOCKED);
            }
            setOnlyFullResourcesSearch(onlyFullResources);
            if (!executeSearch()) {
                return retryLater(GatherDeployResult.BLOCKED);
            }

            GatherDeployResult result = deployMarchAction(type, level);
            if (result == GatherDeployResult.DEPLOYED) {
                return result;
            }
            if (result != GatherDeployResult.NO_NODE_FOUND || level == minLevel) {
                return retryLater(result);
            }

            logInfo(String.format("No %s node found at level %d; trying level %d.", type, level, level - 1));
            pressBack();
            sleepTask(500);
        }

        return GatherDeployResult.NO_NODE_FOUND;
    }

    private boolean openSearchMenu() {
        tapRandomPoint(SEARCH_BTN_TL, SEARCH_BTN_BR);
        sleepTask(2000);
        swipe(RES_TAB_SWIPE_START, RES_TAB_SWIPE_END);
        sleepTask(500);
        return true;
    }

    private boolean selectTile(GatherType type) {
        for (int i = 0; i < 4; i++) {
            ImageSearchResultData tile = templateSearchHelper.locatePattern(type.tile, SearchConfig.builder().build());
            if (tile.isFound()) {
                tapPoint(tile.getPoint());
                sleepTask(500);
                return true;
            }
            if (i < 3) {
                swipe(RES_TAB_SWIPE_START, RES_TAB_SWIPE_END);
                sleepTask(500);
            }
        }
        return false;
    }

    private boolean setLevel(int target) {
        Integer current = readLevel();
        if (current != null && current == target)
            return true;

        if (current == null) {
            resetLevelToOne();
            if (target > 1)
                tapRandomPoint(LEVEL_INC_TL, LEVEL_INC_BR, target - 1, 150);
        } else {
            if (current < target)
                tapRandomPoint(LEVEL_INC_TL, LEVEL_INC_BR, target - current, 150);
            else
                tapRandomPoint(LEVEL_DEC_TL, LEVEL_DEC_BR, current - target, 150);
        }
        return true;
    }

    private void setOnlyFullResourcesSearch(boolean desired) {
        boolean current = templateSearchHelper
                .locatePattern(TemplatesEnum.GAME_HOME_SHORTCUTS_FARM_TICK,
                        SearchConfig.builder()
                                .withArea(ONLY_FULL_RESOURCES_TICK_AREA)
                                .withMaxAttempts(2)
                                .build())
                .isFound();
        if (current != desired) {
            logDebug("Setting only-full-resources search to " + desired);
            tapPoint(ONLY_FULL_RESOURCES_TOGGLE);
            sleepTask(300);
        }
    }

    private boolean executeSearch() {
        tapRandomPoint(SEARCH_EXEC_TL, SEARCH_EXEC_BR);
        sleepTask(3000);
        return true;
    }

    private GatherDeployResult deployMarchAction(GatherType type, int level) {
        ImageSearchResultData btn = templateSearchHelper.locatePattern(TemplatesEnum.GAME_HOME_SHORTCUTS_FARM_GATHER,
                SearchConfig.builder().build());
        if (!btn.isFound()) {
            logInfo(String.format("No gather node found: type=%s level=%d onlyFull=%s",
                    type, level, onlyFullResources));
            return GatherDeployResult.NO_NODE_FOUND;
        }

        tapPoint(btn.getPoint());
        sleepTask(1000);

        ImageSearchResultData hero = templateSearchHelper.locatePattern(type.preferredHero,
                SearchConfig.builder().withCoordinates(new PointData(51, 231), new PointData(295, 649)).build());

        if (!hero.isFound()) {
            logDebug("Preferred hero not found for " + type + ". Proceeding with default march.");
        }

        if (removeHeroes)
            removeDefaultHeroes();

        if (deploymentHelper.hasNoDeployableTroops()) {
            logInfo("No deployable troops found on gather formation screen.");
            return GatherDeployResult.NO_TROOPS_AVAILABLE;
        }

        ImageSearchResultData deploy = templateSearchHelper.locatePattern(TemplatesEnum.GATHER_DEPLOY_BUTTON,
                SearchConfig.builder().build());
        if (!deploy.isFound()) {
            if (deploymentHelper.hasNoDeployableTroops()) {
                logInfo("Gather deploy button is absent because no deployable troops are available.");
                return GatherDeployResult.NO_TROOPS_AVAILABLE;
            }
            return GatherDeployResult.BLOCKED;
        }

        tapPoint(deploy.getPoint());
        sleepTask(1000);

        if (deploymentHelper.isMarchQueueFull()) {
            return GatherDeployResult.BLOCKED;
        }

        if (templateSearchHelper.locatePattern(TemplatesEnum.TROOPS_ALREADY_MARCHING, SearchConfig.builder().build())
                .isFound()) {
            pressBack();
            pressBack();
            return GatherDeployResult.BLOCKED;
        }
        return GatherDeployResult.DEPLOYED;
    }

    // ================= HELPERS (UI/OCR) =================

    private Integer readLevel() {
        TesseractSettingsData s = TesseractSettingsData.assembler().charWhitelist("0123456789")
                .stripBackground(true).setTextColor(new Color(255, 255, 255)).build();
        return readNumberValue(LEVEL_DISPLAY_TL, LEVEL_DISPLAY_BR, s);
    }

    private void removeDefaultHeroes() {
        List<ImageSearchResultData> btns = templateSearchHelper.locateAllPatterns(
                TemplatesEnum.RALLY_REMOVE_HERO_BUTTON,
                SearchConfig.builder().withThreshold(90).withMaxResults(3).build());

        if (btns.isEmpty())
            return;
        btns.sort(Comparator.comparingInt(r -> r.getPoint().getX()));

        for (int i = 1; i < btns.size(); i++) {
            tapPoint(btns.get(i).getPoint());
            sleepTask(300);
        }
    }

    private void resetLevelToOne() {
        swipe(LEVEL_SLIDER_START, LEVEL_SLIDER_END);
        sleepTask(300);
    }

    private GatherDeployResult retryLater(GatherDeployResult result) {
        pressBack(); // Safety back
        return result;
    }

    // ================= SCHEDULING & CONFLICTS =================

    private void updateReschedule(LocalDateTime t) {
        if (earliestReschedule == null || t.isBefore(earliestReschedule))
            earliestReschedule = t;
    }

    private void finalizeReschedule(GatherFillResult fillResult) {
        if (earliestReschedule != null) {
            reschedule(earliestReschedule);
            return;
        }
        LocalDateTime nextGatherCheck = resolveEarliestGatherRedeployTime();
        if (nextGatherCheck != null) {
            LocalDateTime scheduleAt = nextGatherCheck.plusMinutes(TROOP_RETURN_MARGIN_MINUTES);
            String reason = fillResult.stoppedForNoTroops()
                    ? "No deployable troops remain"
                    : "All gather slots filled";
            logInfo(String.format(
                    "Gather fill finished: deployed=%d noNode=%d blocked=%d noTroops=%s. %s. Next gather check at %s (return-aware + %d min margin).",
                    fillResult.deployed(),
                    fillResult.noNode(),
                    fillResult.blocked(),
                    fillResult.stoppedForNoTroops(),
                    reason,
                    GameTimeUtils.formatCountdown(scheduleAt), TROOP_RETURN_MARGIN_MINUTES));
            reschedule(scheduleAt);
        } else {
            reschedule(LocalDateTime.now().plusMinutes(5));
        }
    }

    private LocalDateTime resolveEarliestGatherRedeployTime() {
        try {
            LocalDateTime now = LocalDateTime.now();
            return marchHelper.readMarchQueue().stream()
                    .map(slot -> estimateGatherRedeployTime(slot, now))
                    .filter(time -> time != null)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
        } catch (Exception e) {
            logDebug("Could not resolve next gather redeploy time: " + e.getMessage());
            return null;
        }
    }

    private LocalDateTime estimateGatherRedeployTime(MarchSlotState slot, LocalDateTime now) {
        if (slot == null || now == null) {
            return null;
        }
        if (slot.movementPhase() == MarchMovementPhase.RETURNING && slot.countdown() != null) {
            return now.plus(slot.countdown());
        }
        if (slot.activityType() == MarchActivityType.GATHER
                && slot.movementPhase() == MarchMovementPhase.WORKING) {
            return MarchSlotAvailabilityEstimator
                    .estimateNextCheck(slot, MARCH_SLOT_ESTIMATE_SETTINGS)
                    .map(now::plus)
                    .orElse(null);
        }
        return null;
    }

    // pernerch/2026-07-02: replaces blind checkIntelConflict(). Handles:
    // - Intel (full recall): recall all gather troops, defer past Intel end
    // - Intel (smart): only defer, no full recall
    // - Bear Trap (with recall+rally): recall all gather troops, defer past Bear end
    // - Dual-event (Intel+Bear both within 15 min): defer past BOTH to avoid pointless round-trips
    private boolean checkHighPriorityEventConflict() {
        boolean intelNeedsFullRecall = intelEnabled && intelRecall && !intelSmart;
        boolean intelNeedsSmartDefer = intelEnabled && (intelRecall || intelSmart);
        boolean intelPendingSoon     = intelNeedsSmartDefer
                                       && isEventPendingWithin(TpDailyTaskEnum.INTEL, DUAL_EVENT_LOOKAHEAD_MINUTES);

        boolean bearNeedsRecall  = isBearTrapRecallRequired();
        boolean bearPendingSoon  = bearNeedsRecall
                                   && isEventPendingWithin(TpDailyTaskEnum.BEAR_TRAP, DUAL_EVENT_LOOKAHEAD_MINUTES);

        if (!intelPendingSoon && !bearPendingSoon) return false;

        // Recall gather marches if required by the relevant event
        if (intelNeedsFullRecall && intelPendingSoon) {
            logInfo("Intel (full-recall mode) pending within " + DUAL_EVENT_LOOKAHEAD_MINUTES
                + " min. Recalling all gather marches.");
            recallAllGatherMarchesAndTrack();
        } else if (intelPendingSoon) {
            logInfo("Intel (smart mode) pending within " + DUAL_EVENT_LOOKAHEAD_MINUTES
                + " min. Deferring gather without full recall (duplicates only).");
        }
        if (bearPendingSoon) {
            logInfo("Bear Trap (recall+rally mode) pending within " + DUAL_EVENT_LOOKAHEAD_MINUTES
                + " min. Recalling all gather marches.");
            recallAllGatherMarchesAndTrack();
        }

        // Compute defer time past all pending high-priority events
        LocalDateTime deferUntil = computeDeferTimeAfterHighPriorityEvents(intelPendingSoon, bearPendingSoon);

        if (intelPendingSoon && bearPendingSoon) {
            logInfo(String.format(
                "Intel AND Bear Trap both pending within %d min. Deferring gather until after both events at %s.",
                DUAL_EVENT_LOOKAHEAD_MINUTES, GameTimeUtils.formatCountdown(deferUntil)));
        } else if (intelPendingSoon) {
            logInfo(String.format("Intel pending within %d min. Deferring gather until %s.",
                DUAL_EVENT_LOOKAHEAD_MINUTES, GameTimeUtils.formatCountdown(deferUntil)));
        } else {
            logInfo(String.format("Bear Trap pending within %d min. Deferring gather until %s.",
                DUAL_EVENT_LOOKAHEAD_MINUTES, GameTimeUtils.formatCountdown(deferUntil)));
        }
        reschedule(deferUntil);
        return true;
    }

    // pernerch/2026-07-02: true when Bear Trap is configured to consume ALL gather marches.
    // Bear with own rally only (no joiners) leaves gather marches free, so no recall needed.
    private boolean isBearTrapRecallRequired() {
        boolean bearEnabled  = get(ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, false);
        if (!bearEnabled) return false;
        boolean recallTroops = get(ConfigurationKeyEnum.BEAR_TRAP_RECALL_TROOPS_BOOL, false);
        boolean ownRally     = get(ConfigurationKeyEnum.BEAR_TRAP_CALL_RALLY_BOOL, false);
        boolean joinRally    = get(ConfigurationKeyEnum.BEAR_TRAP_JOIN_RALLY_BOOL, false);
        // Recall only needed when bear takes all march slots: recallTroops=true AND (own rally OR join rally)
        return recallTroops && (ownRally || joinRally);
    }

    // pernerch/2026-07-02: checks if the given task is scheduled within the next N minutes.
    private boolean isEventPendingWithin(TpDailyTaskEnum task, int minutes) {
        try {
            DailyTask t = dailyTaskRepository.findByAccountIdAndTaskType(profile.getId(), task);
            if (t == null || t.getScheduledAt() == null) return false;
            long minutesUntil = ChronoUnit.MINUTES.between(LocalDateTime.now(), t.getScheduledAt());
            return minutesUntil >= 0 && minutesUntil < minutes;
        } catch (Exception e) {
            return false;
        }
    }

    // pernerch/2026-07-02: recalls all active gather marches and records the recall timestamp.
    // Timestamp is stored both as instance field (fast) and in profile config (survives restart).
    private void recallAllGatherMarchesAndTrack() {
        // Record BEFORE recalling so the return margin counts from now
        this.lastRecallTime = LocalDateTime.now();
        profile.setConfig(ConfigurationKeyEnum.GATHER_LAST_RECALL_TIME_STRING, lastRecallTime.toString());
        setShouldUpdateConfig(true);
        logInfo("Gather march recall for high-priority event. Recall time recorded: "
            + lastRecallTime.format(DATETIME_FORMATTER));
        List<ActiveGatherMarchCandidate> candidates = collectActiveGatherMarchCandidatesFlow();
        if (candidates.isEmpty()) {
            logInfo("No active gather marches found to recall.");
            return;
        }
        for (ActiveGatherMarchCandidate c : candidates) {
            recallGatherMarchByQueueFlow(c, RecallReason.HIGH_PRIORITY_EVENT);
            sleepTask(300);
        }
        logInfo("Recalled " + candidates.size() + " gather march(es) for high-priority event.");
    }

    // pernerch/2026-07-02: calculates when to resume gather after all pending high-priority events end.
    // For Intel: scheduled start + 15 min (typical Intel duration). For Bear: scheduled start + 30 min.
    // Final time gets the troop-return margin added so troops have time to walk home.
    private LocalDateTime computeDeferTimeAfterHighPriorityEvents(boolean intelPending, boolean bearPending) {
        LocalDateTime deferUntil = LocalDateTime.now();
        if (intelPending) {
            try {
                DailyTask intel = dailyTaskRepository.findByAccountIdAndTaskType(profile.getId(), TpDailyTaskEnum.INTEL);
                if (intel != null && intel.getScheduledAt() != null) {
                    LocalDateTime intelEnd = intel.getScheduledAt().plusMinutes(15);
                    if (intelEnd.isAfter(deferUntil)) deferUntil = intelEnd;
                }
            } catch (Exception ignored) {}
        }
        if (bearPending) {
            try {
                DailyTask bear = dailyTaskRepository.findByAccountIdAndTaskType(profile.getId(), TpDailyTaskEnum.BEAR_TRAP);
                if (bear != null && bear.getScheduledAt() != null) {
                    LocalDateTime bearEnd = bear.getScheduledAt().plusMinutes(BEAR_TRAP_DURATION_MINUTES);
                    if (bearEnd.isAfter(deferUntil)) deferUntil = bearEnd;
                }
            } catch (Exception ignored) {}
        }
        return deferUntil.plusMinutes(TROOP_RETURN_MARGIN_MINUTES);
    }

    // pernerch/2026-07-02: after an Intel or Bear recall, waits for troops to return home before
    // re-deploying. Uses option B: start with TROOP_RETURN_MARGIN_MINUTES, then +1 min per check
    // until collectActiveGatherMarchCandidatesFlow() reports zero active gather marches.
    private boolean checkTroopReturnPending() {
        if (lastRecallTime == null) return false;
        // Expire recall state after 2 hours to prevent permanent blocking from stale data
        if (ChronoUnit.MINUTES.between(lastRecallTime, LocalDateTime.now()) > 120) {
            clearRecallState();
            return false;
        }
        List<ActiveGatherMarchCandidate> active = collectActiveGatherMarchCandidatesFlow();
        if (active.isEmpty()) {
            logInfo("All recalled gather troops have returned home. Clearing recall state and proceeding with fresh deployment.");
            clearRecallState();
            return false; // troops home, proceed with normal execute
        }
        LocalDateTime retryAt = LocalDateTime.now().plusMinutes(TROOP_RETURN_RETRY_MINUTES);
        logInfo(String.format(
            "Recalled gather troops still returning (%d march(es) active). Rechecking in %d min at %s.",
            active.size(), TROOP_RETURN_RETRY_MINUTES, GameTimeUtils.formatCountdown(retryAt)));
        reschedule(retryAt);
        return true;
    }

    private void clearRecallState() {
        this.lastRecallTime = null;
        profile.setConfig(ConfigurationKeyEnum.GATHER_LAST_RECALL_TIME_STRING, "");
        setShouldUpdateConfig(true);
    }

    // Changed by pernerch | Date: 2026-07-02 | Why: when Gather is blocked by a pending Intel
    // task, force Intel immediately so it can use free marches now or reschedule itself on low stamina.
    private boolean triggerPendingIntelNowFlow() {
        TaskQueue queue = scheduleService.getCoordinator().getQueue(profile.getId());
        if (queue == null) {
            logWarning("Intel is pending but no active queue was available to force Intel immediately.");
            return false;
        }

        logInfo("Intel is pending. Forcing Intel now so marches are either used immediately or freed until Intel can run again.");
        queue.runNow(TpDailyTaskEnum.INTEL, true);
        return true;
    }

    private boolean checkGatherSpeedWait() {
        if (!gatherSpeed)
            return false;
        try {
            DailyTask t = dailyTaskRepository.findByAccountIdAndTaskType(profile.getId(), TpDailyTaskEnum.GATHER_BOOST);
            if (t == null)
                return false;
            long m = ChronoUnit.MINUTES.between(LocalDateTime.now(), t.getScheduledAt());
            if (m > 0 && m < 5) {
                reschedule(LocalDateTime.now().plusMinutes(2));
                return true;
            }
        } catch (Exception e) {
        }
        return false;
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    @Override
    public boolean provideDailyMissionProgress() {
        return true;
    }

    // ================= INNER CLASSES =================

    public enum GatherType {
        MEAT(TemplatesEnum.GAME_HOME_SHORTCUTS_FARM_MEAT,
                TemplatesEnum.GATHER_MEAT_HERO,
                ConfigurationKeyEnum.GATHER_MEAT_BOOL, ConfigurationKeyEnum.GATHER_MEAT_LEVEL_INT),
        WOOD(TemplatesEnum.GAME_HOME_SHORTCUTS_FARM_WOOD,
                TemplatesEnum.GATHER_WOOD_HERO,
                ConfigurationKeyEnum.GATHER_WOOD_BOOL, ConfigurationKeyEnum.GATHER_WOOD_LEVEL_INT),
        COAL(TemplatesEnum.GAME_HOME_SHORTCUTS_FARM_COAL,
                TemplatesEnum.GATHER_COAL_HERO,
                ConfigurationKeyEnum.GATHER_COAL_BOOL, ConfigurationKeyEnum.GATHER_COAL_LEVEL_INT),
        IRON(TemplatesEnum.GAME_HOME_SHORTCUTS_FARM_IRON,
                TemplatesEnum.GATHER_IRON_HERO,
                ConfigurationKeyEnum.GATHER_IRON_BOOL, ConfigurationKeyEnum.GATHER_IRON_LEVEL_INT);

        final TemplatesEnum tile, preferredHero;
        final ConfigurationKeyEnum enabledKey, levelKey;

        GatherType(TemplatesEnum tile, TemplatesEnum preferredHero,
                ConfigurationKeyEnum enabledKey, ConfigurationKeyEnum levelKey) {
            this.tile = tile;
            this.preferredHero = preferredHero;
            this.enabledKey = enabledKey;
            this.levelKey = levelKey;
        }
    }

    private static class MarchQueueRegion {
        final PointData topLeft, bottomRight;

        MarchQueueRegion(PointData topLeft, PointData bottomRight) {
            this.topLeft = topLeft;
            this.bottomRight = bottomRight;
        }
    }

    private record GatherMarchSnapshot(List<MarchSlotState> slots, List<GatherType> activeTypes,
                                       int activeGatherCount, int idleSlotCount,
                                       LocalDateTime nextCheckAt, LocalDateTime earliestGatherCheckAt) {
    }

    private record ActiveGatherMarchCandidate(GatherType type, int queueIndex, LocalDateTime returnTime) {
    }

    private record GatherFillResult(int deployed, int noNode, int blocked, boolean stoppedForNoTroops) {
    }

    private enum GatherDeployResult {
        DEPLOYED,
        NO_NODE_FOUND,
        NO_TROOPS_AVAILABLE,
        BLOCKED
    }

    private enum RecallReason {
        DISABLED_TYPE("disabled-type"),
        DUPLICATE_TYPE("duplicate-type"),
        OVERFLOW_FALLBACK("overflow-fallback"),
        // pernerch/2026-07-02: march recalled because Intel (full-recall) or Bear Trap (recall+rally) is imminent
        HIGH_PRIORITY_EVENT("high-priority-event");

        private final String logValue;

        RecallReason(String logValue) {
            this.logValue = logValue;
        }
    }
}
