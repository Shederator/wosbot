package dev.frostguard.tasks.economy;

import java.awt.Color;
import java.io.IOException;
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
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.data.entity.DailyTask;
import dev.frostguard.data.repository.DailyTaskRepository;
import dev.frostguard.data.repository.DailyTaskRepository;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.GatherQueuePolicy;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.service.StatisticsService;
import net.sourceforge.tess4j.TesseractException;

/**
 * Optimized GatherRoutine: Manages persistent resource rotation, fairness, and
 * efficient queue utilization.
 */
public class GatherRoutine extends DelayedTask {

    // ========== Constants & Config Keys ==========
    private static final int DEFAULT_QUEUES = 6;
    private static final int DEFAULT_LEVEL = 5;
    private static final int MAX_GATHER_LEVEL = 9;
    private static final int REGION_SAFE_MAX_LEVEL = 8;
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

    // Region Constants (UI)
    private static final MarchQueueRegion[] MARCH_QUEUES = {
            new MarchQueueRegion(new PointData(10, 342), new PointData(435, 407), new PointData(152, 378)),
            new MarchQueueRegion(new PointData(10, 415), new PointData(435, 480), new PointData(152, 451)),
            new MarchQueueRegion(new PointData(10, 488), new PointData(435, 553), new PointData(152, 524)),
            new MarchQueueRegion(new PointData(10, 561), new PointData(435, 626), new PointData(152, 597)),
            new MarchQueueRegion(new PointData(10, 634), new PointData(435, 699), new PointData(152, 670)),
            new MarchQueueRegion(new PointData(10, 707), new PointData(435, 772), new PointData(152, 743)),
    };
    private static final int TIME_TEXT_WIDTH = 140;
    private static final int TIME_TEXT_HEIGHT = 19;

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
    private static final PointData LEVEL_LOCK_BTN = new PointData(183, 1140);
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
    private boolean downgradeLevelIfNoNode;

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
        // 1. Scan Active Marches
        List<GatherType> activeMarches = scanActiveMarches();
        int activeCount = countOccupiedMarchSlotsFlow();
        logInfo(String.format("Active Marches: %d / %d", activeCount, activeQueues));

        // Changed by pernerch | Date: 2026-07-02 | Why: continuously self-heal gather state
        // by recalling duplicate gather marches when active gathers exceed configured queue limit.
        int recalledOverflow = recallDuplicateOverflowGatherMarchesFlow();
        if (recalledOverflow > 0) {
            logInfo(String.format(
                "Corrected gather overflow by recalling %d duplicate march(es). Re-scanning active marches.",
                recalledOverflow));
            sleepTask(500);
            earliestReschedule = null;
            activeMarches = scanActiveMarches();
            activeCount = countOccupiedMarchSlotsFlow();
            logInfo(String.format("Active Marches after correction: %d / %d", activeCount, activeQueues));
        }

        // Changed by pernerch | Date: 2026-07-02 | Why: when higher-priority tasks are pending,
        // defer based on real active-march timing instead of a blind fixed delay.
        List<TpDailyTaskEnum> pendingHigherPriorityTasks = GatherQueuePolicy.getPendingHigherPriorityMarchTasks(profile);
        if (!pendingHigherPriorityTasks.isEmpty()) {
            if (pendingHigherPriorityTasks.contains(TpDailyTaskEnum.INTEL) && triggerPendingIntelNowFlow()) {
                return;
            }

            if (activeCount > 0) {
                LocalDateTime next = earliestReschedule != null ? earliestReschedule : LocalDateTime.now().plusMinutes(5);
                logInfo(String.format(
                "Deferring gather deployment because higher-priority march task(s) are pending: %s. " +
                    "%d gather march(es) are outside; next return at %s.",
                pendingHigherPriorityTasks,
                        activeCount,
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

        if (activeCount >= activeQueues && earliestReschedule == null) {
            if (!autoJoinEnabled) {
                int recalledBlockedMarches = recallBlockedMarchesWhenAutojoinOffFlow();
                if (recalledBlockedMarches > 0) {
                    LocalDateTime retryAt = LocalDateTime.now().plusMinutes(1);
                    logInfo(String.format(
                            "Autojoin is disabled and all gather slots are blocked. Recalled %d march(es); rechecking gather in 1 minute at %s.",
                            recalledBlockedMarches,
                            GameTimeUtils.formatCountdown(retryAt)));
                    reschedule(retryAt);
                    return;
                }
            }

            // pernerch/2026-07-02: read the actual march return times so gather wakes up exactly
            // when a slot becomes free, not on a blind 5-minute ticker.
            // Only fall back to 5-min polling if the return is ≤10 min away (near-term uncertainty).
            LocalDateTime latestReturn = resolveLatestMarchReturnTime();
            LocalDateTime retryAt;
            if (latestReturn != null && ChronoUnit.MINUTES.between(LocalDateTime.now(), latestReturn) > 10) {
                retryAt = latestReturn.plusMinutes(TROOP_RETURN_MARGIN_MINUTES);
                logInfo(String.format(
                        "All configured march slots are currently occupied outside gather%s. " +
                        "Scheduling next gather check at march return time %s.",
                        autoJoinEnabled ? " (autojoin enabled)" : " (autojoin disabled)",
                        GameTimeUtils.formatCountdown(retryAt)));
            } else {
                retryAt = LocalDateTime.now().plusMinutes(5);
                logInfo(String.format(
                        "All configured march slots are currently occupied outside gather%s. " +
                        "Return is near or unknown - retrying in 5 minutes at %s.",
                        autoJoinEnabled ? " (autojoin enabled)" : " (autojoin disabled)",
                        GameTimeUtils.formatCountdown(retryAt)));
            }
            reschedule(retryAt);
            return;
        }

        // 2. Fill Queues (Persistent Rotation)
        fillQueues(activeCount, activeMarches);

        // 3. Save & Finalize
        finalizeReschedule();
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
        this.downgradeLevelIfNoNode = get(ConfigurationKeyEnum.GATHER_DOWNGRADE_LEVEL_BOOL, true);

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

    private void fillQueues(int currentActive, List<GatherType> activeMarches) {
        int freeSlots = activeQueues - currentActive;
        logInfo(String.format("Free slots: %d. Pool: %s", freeSlots, rotationPool));

        // Remove types already marching from the current pool for initial fairness
        if (rotationPool.removeAll(activeMarches)) {
            logInfo("Removed active marches from pool: " + activeMarches);
            saveRotationPool();
        }

        // If pool is empty after removing active marches but there are free slots,
        // allow duplicate types so we can fill all available march queues
        if (rotationPool.isEmpty() && freeSlots > 0) {
            logInfo("Pool empty after removing active marches. Allowing duplicates for remaining slots.");
            rotationPool = new ArrayList<>(enabledTypes);
        }

        if (freeSlots <= 0) {
            saveRotationPool();
            return;
        }

        // Shuffle loaded pool for randomness in this run
        Collections.shuffle(rotationPool);

        int remaining = freeSlots;
        int safetyLoop = 0;

        while (remaining > 0 && safetyLoop++ < 10) {

            // Refill if empty
            if (rotationPool.isEmpty()) {
                logInfo("Pool empty. Resetting.");
                rotationPool = new ArrayList<>(enabledTypes);
                // Don't remove active marches on refill â€” duplicates are needed
                // to fill remaining slots when activeQueues > enabledTypes.size()
                Collections.shuffle(rotationPool);
            }

            // Try ALL pool items â€” don't limit to remaining, so if one type fails
            // we still try others. The inner loop stops when slots are full.
            List<GatherType> batch = new ArrayList<>(rotationPool);

            if (batch.isEmpty())
                break;

            boolean progress = false;
            for (GatherType type : batch) {
                if (remaining <= 0 || currentActive >= activeQueues)
                    break;

                if (deploy(type)) {
                    currentActive++;
                    remaining--;
                    rotationPool.remove(type);
                    progress = true;
                    logInfo(String.format("Deployed %s. Removed from pool.", type));
                    StatisticsService.obtain().addToCounter(profile, "Gather Marches Deployed", 1);
                    activeMarches.add(type); // Add to avoid re-picking if we loop
                } else {
                    // Remove failed type from pool to avoid retrying it endlessly
                    rotationPool.remove(type);
                    logInfo(String.format("Failed to deploy %s. Skipping.", type));
                }
            }

            if (!progress || currentActive >= activeQueues)
                break;
        }

        saveRotationPool();
    }

    private void loadRotationPool() {
        String saved = profile.getConfig(ConfigurationKeyEnum.GATHER_ROTATION_POOL, String.class);
        logInfo("DEBUG: Loaded pool config: '" + saved + "'");
        if (saved == null || saved.isEmpty()) {
            rotationPool = new ArrayList<>(enabledTypes);
            logInfo("DEBUG: Pool config empty/null. Resetting to full: " + rotationPool);
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
            logInfo("DEBUG: Error parsing pool. Resetting: " + e.getMessage());
        }
    }

    private void saveRotationPool() {
        if (rotationPool == null)
            return;
        String val = rotationPool.stream().map(Enum::name).collect(Collectors.joining(","));
        logInfo("DEBUG: Saving pool config: '" + val + "'");
        profile.setConfig(ConfigurationKeyEnum.GATHER_ROTATION_POOL, val);
        setShouldUpdateConfig(true);
    }

    // ================= SCAN & CHECKS =================

    private List<GatherType> scanActiveMarches() {
        List<GatherType> active = new ArrayList<>();
        if (enabledTypes == null || enabledTypes.isEmpty()) {
            return active;
        }

        // Detect gather types directly from currently active march rows, so free slots
        // can be assigned to the remaining resource types not yet outside.
        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);
        sleepTask(250);
        marchHelper.openLeftMenuCitySection(false);
        try {
            for (GatherType type : enabledTypes) {
                if (!checkActiveMarches(type).isEmpty()) {
                    active.add(type);
                }
            }
        } finally {
            marchHelper.closeLeftMenu();
        }

        if (!active.isEmpty()) {
            logInfo("Detected active gather resource types: " + active);
        }

        return active;
    }

    // Changed by pernerch | Date: 2026-07-02 | Why: count occupied marches via the march-slot OCR
    // grid instead of searching shortcut templates in the left menu, so gather startup matches the
    // original slot-based behavior again.
    private int countOccupiedMarchSlotsFlow() {
        marchHelper.openLeftMenuCitySection(false);
        int occupied = 0;

        try {
            for (int i = 0; i < CommonGameAreas.MARCH_SLOTS_TOP_LEFT.length; i++) {
                PointData topLeft = CommonGameAreas.MARCH_SLOTS_TOP_LEFT[i];
                PointData bottomRight = CommonGameAreas.MARCH_SLOTS_BOTTOM_RIGHT[i];
                String text = emuManager.readText(EMULATOR_NUMBER, topLeft, bottomRight);
                if (text == null || !text.toLowerCase().contains("idle")) {
                    occupied++;
                }
            }
        } catch (IOException | TesseractException e) {
            logDebug("Could not fully read occupied march slots: " + e.getMessage());
        } finally {
            marchHelper.closeLeftMenu();
        }

        return occupied;
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
        marchHelper.openLeftMenuCitySection(false);

        try {
            PointData limit = new PointData(415,
                    MARCH_QUEUES[MARCH_QUEUES.length - 1].bottomRight.getY());

            for (GatherType type : GatherType.values()) {
                List<ImageSearchResultData> results = templateSearchHelper.locateAllPatterns(
                        type.template,
                        SearchConfig.builder()
                                .withArea(new AreaData(MARCH_QUEUES[0].topLeft, limit))
                                .withMaxAttempts(3)
                                .withMaxResults(MARCH_QUEUES.length)
                                .withDelay(3)
                                .build());

                for (ImageSearchResultData result : results) {
                    int queueIndex = findQueueIndex(result.getPoint());
                    if (queueIndex < 0) {
                        continue;
                    }

                    LocalDateTime returnTime = readReturnTime(queueIndex);
                    if (returnTime == null) {
                        returnTime = LocalDateTime.now().plusMinutes(5);
                    }

                    candidates.add(new ActiveGatherMarchCandidate(type, queueIndex, returnTime));
                }
            }

            return candidates;
        } finally {
            marchHelper.closeLeftMenu();
        }
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

    private List<ActiveMarchResult> checkActiveMarches(GatherType type) {
        PointData limit = new PointData(415,
                MARCH_QUEUES[MARCH_QUEUES.length - 1].bottomRight.getY());

        // Fix: Use searchTemplates (plural) to find ALL matches of this type
        List<ImageSearchResultData> results = templateSearchHelper.locateAllPatterns(
                type.template,
                SearchConfig.builder()
                        .withArea(new AreaData(MARCH_QUEUES[0].topLeft, limit))
                        .withMaxAttempts(3)
                        .withMaxResults(MARCH_QUEUES.length)
                        .withDelay(3).build());

        List<ActiveMarchResult> marchResults = new ArrayList<>();

        if (results.isEmpty()) {
            return marchResults; // Empty list = no marches of this type
        }

        for (ImageSearchResultData res : results) {
            int qIdx = findQueueIndex(res.getPoint());
            if (qIdx != -1) {
                LocalDateTime time = readReturnTime(qIdx);
                // If time read fails, we still count it as active with a fallback time
                LocalDateTime returnTime = (time != null) ? time.plusMinutes(2) : LocalDateTime.now().plusMinutes(5);
                marchResults.add(ActiveMarchResult.active(returnTime));
            } else {
                // Found the icon but couldn't map to a queue... treat as active anyway to be
                // safe?
                // For now, if we can't map it to a queue line, we might ignore it or treat as
                // error.
                // Safer to count it as active with default time to avoid over-deploying
                marchResults.add(ActiveMarchResult.error());
            }
        }

        return marchResults;
    }

    // ================= DEPLOYMENT PIPELINE =================

    private boolean deploy(GatherType type) {
        logInfo("Deploying " + type);

        if (!openSearchMenu())
            return retryLater();
        if (!selectTile(type))
            return retryLater();

        int configuredLevel = clampGatherLevel(get(type.levelKey, DEFAULT_LEVEL));
        List<Integer> levelAttempts = buildLevelAttempts(configuredLevel);

        for (int i = 0; i < levelAttempts.size(); i++) {
            int level = levelAttempts.get(i);
            logInfo(String.format("Gather level attempt %d/%d for %s: level %d",
                    i + 1,
                    levelAttempts.size(),
                    type,
                    level));

            if (!setLevel(level)) {
                return retryLater();
            }

            if (!executeSearch()) {
                return retryLater();
            }

            DeployResult result = deployMarchAction(type);
            if (result == DeployResult.SUCCESS) {
                return true;
            }

            if (result != DeployResult.NODE_NOT_FOUND || i == levelAttempts.size() - 1) {
                return retryLater();
            }

            logInfo(String.format(
                    "No node found for %s at level %d. Retrying with next fallback level.",
                    type,
                    level));
        }

        return retryLater();
    }

    private int clampGatherLevel(int level) {
        return Math.max(1, Math.min(MAX_GATHER_LEVEL, level));
    }

    private List<Integer> buildLevelAttempts(int configuredLevel) {
        List<Integer> attempts = new ArrayList<>();
        attempts.add(configuredLevel);

        if (configuredLevel == MAX_GATHER_LEVEL) {
            attempts.add(REGION_SAFE_MAX_LEVEL);
        }

        if (downgradeLevelIfNoNode) {
            int last = attempts.get(attempts.size() - 1);
            if (last > 1) {
                attempts.add(last - 1);
            }
        }

        List<Integer> uniqueAttempts = new ArrayList<>();
        for (Integer attempt : attempts) {
            if (!uniqueAttempts.contains(attempt)) {
                uniqueAttempts.add(attempt);
            }
        }
        return uniqueAttempts;
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
        ensureLevelLocked();
        return true;
    }

    private boolean executeSearch() {
        tapRandomPoint(SEARCH_EXEC_TL, SEARCH_EXEC_BR);
        sleepTask(3000);
        return true;
    }

    private DeployResult deployMarchAction(GatherType type) {
        ImageSearchResultData btn = templateSearchHelper.locatePattern(TemplatesEnum.GAME_HOME_SHORTCUTS_FARM_GATHER,
                SearchConfig.builder().build());
        if (!btn.isFound())
            return DeployResult.NODE_NOT_FOUND;

        tapPoint(btn.getPoint());
        sleepTask(1000);

        ImageSearchResultData hero = templateSearchHelper.locatePattern(type.preferredHero,
                SearchConfig.builder().withCoordinates(new PointData(51, 231), new PointData(295, 649)).build());

        if (!hero.isFound()) {
            logInfo("Preferred hero not found for " + type + ". Proceeding with default march.");
        }

        if (removeHeroes)
            removeDefaultHeroes();

        ImageSearchResultData deploy = templateSearchHelper.locatePattern(TemplatesEnum.GATHER_DEPLOY_BUTTON,
                SearchConfig.builder().build());
        if (!deploy.isFound())
            return DeployResult.FAILED;

        tapPoint(deploy.getPoint());
        sleepTask(1000);

        if (templateSearchHelper.locatePattern(TemplatesEnum.TROOPS_ALREADY_MARCHING, SearchConfig.builder().build())
                .isFound()) {
            pressBack();
            pressBack();
            return DeployResult.FAILED;
        }
        return DeployResult.SUCCESS;
    }

    // ================= HELPERS (UI/OCR) =================

    private int findQueueIndex(PointData p) {
        int max = MARCH_QUEUES.length;
        for (int i = 0; i < max; i++) {
            MarchQueueRegion r = MARCH_QUEUES[i];
            if (p.getX() >= r.topLeft.getX() && p.getX() <= r.bottomRight.getX() &&
                    p.getY() >= r.topLeft.getY() && p.getY() <= r.bottomRight.getY())
                return i;
        }
        return -1;
    }

    private LocalDateTime readReturnTime(int idx) {
        MarchQueueRegion r = MARCH_QUEUES[idx];
        TesseractSettingsData s = TesseractSettingsData.assembler()
                .pageAnalysis(TesseractSettingsData.PageAnalysis.SINGLE_LINE)
                .recognitionEngine(TesseractSettingsData.RecognitionEngine.LSTM_ONLY)
                .stripBackground(true)
                .setTextColor(new Color(255, 255, 255))
                .charWhitelist("0123456789:").build();

        return textHelper.attemptRecognition(r.timeTextStart,
                new PointData(r.timeTextStart.getX() + TIME_TEXT_WIDTH, r.timeTextStart.getY() + TIME_TEXT_HEIGHT),
                3, 200L, s, GameTimeUtils::isAcceptedFormat, text -> LocalDateTime.now().plus(GameTimeUtils.parseDuration(text)));
    }

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

    private void ensureLevelLocked() {
        if (!templateSearchHelper
                .locatePattern(TemplatesEnum.GAME_HOME_SHORTCUTS_FARM_TICK, SearchConfig.builder().build())
                .isFound()) {
            tapPoint(LEVEL_LOCK_BTN);
            sleepTask(300);
        }
    }

    private boolean retryLater() {
        pressBack(); // Safety back
        return false;
    }

    // ================= SCHEDULING & CONFLICTS =================

    private void updateReschedule(LocalDateTime t) {
        if (earliestReschedule == null || t.isBefore(earliestReschedule))
            earliestReschedule = t;
    }

    private void finalizeReschedule() {
        if (earliestReschedule != null) {
            reschedule(earliestReschedule);
            return;
        }
        // pernerch/2026-07-02: schedule next run based on when the deployed marches will actually return,
        // not a fixed 5-minute blind wait. Reads OCR return times from active gather march rows.
        LocalDateTime maxReturnTime = resolveLatestMarchReturnTime();
        if (maxReturnTime != null) {
            LocalDateTime scheduleAt = maxReturnTime.plusMinutes(TROOP_RETURN_MARGIN_MINUTES);
            logInfo(String.format("All gather slots filled. Next gather check at %s (latest march return + %d min margin).",
                GameTimeUtils.formatCountdown(scheduleAt), TROOP_RETURN_MARGIN_MINUTES));
            reschedule(scheduleAt);
        } else {
            reschedule(LocalDateTime.now().plusMinutes(5));
        }
    }

    // pernerch/2026-07-02: reads active gather march candidates and returns the latest return time,
    // used to schedule the next gather run after all slots are filled.
    private LocalDateTime resolveLatestMarchReturnTime() {
        try {
            List<ActiveGatherMarchCandidate> candidates = collectActiveGatherMarchCandidatesFlow();
            return candidates.stream()
                .map(ActiveGatherMarchCandidate::returnTime)
                .max(Comparator.naturalOrder())
                .orElse(null);
        } catch (Exception e) {
            logDebug("Could not resolve latest march return time: " + e.getMessage());
            return null;
        }
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
        MEAT(TemplatesEnum.GAME_HOME_SHORTCUTS_MEAT, TemplatesEnum.GAME_HOME_SHORTCUTS_FARM_MEAT,
                TemplatesEnum.GATHER_MEAT_HERO,
                ConfigurationKeyEnum.GATHER_MEAT_BOOL, ConfigurationKeyEnum.GATHER_MEAT_LEVEL_INT),
        WOOD(TemplatesEnum.GAME_HOME_SHORTCUTS_WOOD, TemplatesEnum.GAME_HOME_SHORTCUTS_FARM_WOOD,
                TemplatesEnum.GATHER_WOOD_HERO,
                ConfigurationKeyEnum.GATHER_WOOD_BOOL, ConfigurationKeyEnum.GATHER_WOOD_LEVEL_INT),
        COAL(TemplatesEnum.GAME_HOME_SHORTCUTS_COAL, TemplatesEnum.GAME_HOME_SHORTCUTS_FARM_COAL,
                TemplatesEnum.GATHER_COAL_HERO,
                ConfigurationKeyEnum.GATHER_COAL_BOOL, ConfigurationKeyEnum.GATHER_COAL_LEVEL_INT),
        IRON(TemplatesEnum.GAME_HOME_SHORTCUTS_IRON, TemplatesEnum.GAME_HOME_SHORTCUTS_FARM_IRON,
                TemplatesEnum.GATHER_IRON_HERO,
                ConfigurationKeyEnum.GATHER_IRON_BOOL, ConfigurationKeyEnum.GATHER_IRON_LEVEL_INT);

        final TemplatesEnum template, tile, preferredHero;
        final ConfigurationKeyEnum enabledKey, levelKey;

        GatherType(TemplatesEnum template, TemplatesEnum tile, TemplatesEnum preferredHero,
                ConfigurationKeyEnum enabledKey, ConfigurationKeyEnum levelKey) {
            this.template = template;
            this.tile = tile;
            this.preferredHero = preferredHero;
            this.enabledKey = enabledKey;
            this.levelKey = levelKey;
        }
    }

    private static class MarchQueueRegion {
        final PointData topLeft, bottomRight, timeTextStart;

        MarchQueueRegion(PointData topLeft, PointData bottomRight, PointData timeTextStart) {
            this.topLeft = topLeft;
            this.bottomRight = bottomRight;
            this.timeTextStart = timeTextStart;
        }
    }

    private static class ActiveMarchResult {
        final boolean active;
        final LocalDateTime returnTime;

        private ActiveMarchResult(boolean active, LocalDateTime returnTime) {
            this.active = active;
            this.returnTime = returnTime;
        }

        static ActiveMarchResult active(LocalDateTime t) {
            return new ActiveMarchResult(true, t);
        }

        static ActiveMarchResult error() {
            return new ActiveMarchResult(true, LocalDateTime.now().plusMinutes(5));
        }

        boolean isActive() {
            return active;
        }

        LocalDateTime getReturnTime() {
            return returnTime;
        }
    }

    private record ActiveGatherMarchCandidate(GatherType type, int queueIndex, LocalDateTime returnTime) {
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

    private enum DeployResult {
        SUCCESS,
        NODE_NOT_FOUND,
        FAILED
    }
}
