package dev.frostguard.engine.schedule.preempt;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.service.LoggingService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Monitors the rally indicator on screen and triggers the manual rally
 * join task whenever the visible rally count increases.  Tracks per-profile
 * deployment slots and enforces session-level join limits.
 */
public class ManualRallyJoinPreemptionRule implements PreemptionRule {

    private static final Logger logger = LoggerFactory.getLogger(ManualRallyJoinPreemptionRule.class);

    // Per-profile tracking maps
    private final Map<Long, Integer> previousCounts       = new ConcurrentHashMap<>();
    private final Map<Long, Integer> consecutiveMisses    = new ConcurrentHashMap<>();
    private static final Map<Long, List<LocalDateTime>> deployedMarches = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> sessionJoinTotals           = new ConcurrentHashMap<>();

    // Region on screen containing both the rally indicator icon and its count
    private static final PointData SCAN_ORIGIN = new PointData(606, 485);
    private static final PointData SCAN_EXTENT = new PointData(718, 605);

    // Number templates in order 1..10
    private static final TemplatesEnum[] DIGIT_TEMPLATES = {
            TemplatesEnum.NUMBER_1,  TemplatesEnum.NUMBER_2,
            TemplatesEnum.NUMBER_3,  TemplatesEnum.NUMBER_4,
            TemplatesEnum.NUMBER_5,  TemplatesEnum.NUMBER_6,
            TemplatesEnum.NUMBER_7,  TemplatesEnum.NUMBER_8,
            TemplatesEnum.NUMBER_9,  TemplatesEnum.NUMBER_10
    };

    // ---- static API for external callers -----------------------------------

    /** Records a successful rally join for the session counter. */
    public static void incrementSessionJoinedCount(long profileId) {
        int updated = sessionJoinTotals.merge(profileId, 1, Integer::sum);
        logger.info("Session rally joins for profile {} now at {}", profileId, updated);
    }

    /** Registers a deployed march with its expected return time. */
    public static void registerDeployment(long profileId, LocalDateTime returnTime) {
        deployedMarches.computeIfAbsent(profileId, k -> new CopyOnWriteArrayList<>())
                .add(returnTime);
    }

    /** Returns the number of currently active (non-expired) deployments. */
    public static int getActiveDeploymentsCount(long profileId) {
        List<LocalDateTime> marches = deployedMarches.get(profileId);
        if (marches == null) return 0;
        marches.removeIf(t -> LocalDateTime.now().isAfter(t));
        return marches.size();
    }

    // ---- PreemptionRule implementation --------------------------------------

    @Override
    public boolean shouldPreempt(EmulatorController controller,
                                 AccountDescriptor profile,
                                 RawImageData screenshot) {
        if (!isFeatureEnabled(profile)) {
            clearTrackingState(profile.getId());
            return false;
        }

        try {
            if (areAllMarchSlotsBusy(profile, controller, screenshot)) return false;

            int detectedCount = detectRallyCount(controller, profile, screenshot);
            if (detectedCount < 0) return false;

            return evaluateCountChange(profile, detectedCount);
        } catch (Exception ex) {
            logger.error("Rally preemption error [{}]", profile.getName(), ex);
            emitLog(TpMessageSeverityEnum.ERROR, profile, "Error: " + ex.getMessage());
            return false;
        }
    }

    @Override
    public TpDailyTaskEnum getTaskToExecute() { return TpDailyTaskEnum.EVENT_BERSERK_CRYPTID; }

    @Override
    public String getRuleName() { return "ManualRallyJoin"; }

    // ---- internal logic ----------------------------------------------------

    private boolean isFeatureEnabled(AccountDescriptor profile) {
        Boolean flag = profile.getConfig(ConfigurationKeyEnum.RALLY_ENABLED_BOOL, Boolean.class);
        return Boolean.TRUE.equals(flag);
    }

    private void clearTrackingState(Long profileId) {
        previousCounts.remove(profileId);
        consecutiveMisses.remove(profileId);
    }

    private boolean areAllMarchSlotsBusy(AccountDescriptor profile,
                                         EmulatorController controller,
                                         RawImageData screenshot) {
        int maxMarches = profile.getConfig(ConfigurationKeyEnum.RALLY_MARCHES_INT, Integer.class);
        List<LocalDateTime> marches = deployedMarches.get(profile.getId());
        if (marches == null) return false;

        // Purge expired deployments
        boolean anyExpired = marches.removeIf(t -> {
            if (LocalDateTime.now().isAfter(t)) {
                logger.info("Deployed march returned. Slot restored to available pool.");
                emitLog(TpMessageSeverityEnum.INFO, profile,
                        "Deployed march returned. Slot restored to available pool.");
                return true;
            }
            return false;
        });
        if (anyExpired) clearTrackingState(profile.getId());

        if (marches.size() >= maxMarches) {
            // All slots occupied — but still keep tracking indicator visibility
            // so we don't get a stale previousCount when a slot opens up
            updateIndicatorTracking(controller, profile, screenshot);
            return true;
        }
        return false;
    }

    private void updateIndicatorTracking(EmulatorController controller,
                                         AccountDescriptor profile,
                                         RawImageData screenshot) {
        ImageSearchResultData indicator = controller.locatePattern(
                profile.getEmulatorNumber(), screenshot,
                TemplatesEnum.RALLY_INDICATOR,
                SCAN_ORIGIN, SCAN_EXTENT,
                SearchConfigConstants.DEFAULT_SINGLE.getThreshold());

        if (!indicator.isFound()) {
            int misses = consecutiveMisses.merge(profile.getId(), 1, Integer::sum);
            if (misses >= 3) previousCounts.remove(profile.getId());
        } else {
            consecutiveMisses.remove(profile.getId());
        }
    }

    private int detectRallyCount(EmulatorController controller,
                                  AccountDescriptor profile,
                                  RawImageData screenshot) {
        // First verify the rally indicator is present
        ImageSearchResultData indicator = controller.locatePattern(
                profile.getEmulatorNumber(), screenshot,
                TemplatesEnum.RALLY_INDICATOR,
                SCAN_ORIGIN, SCAN_EXTENT,
                SearchConfigConstants.DEFAULT_SINGLE.getThreshold());

        if (!indicator.isFound()) {
            int misses = consecutiveMisses.merge(profile.getId(), 1, Integer::sum);
            if (misses >= 3) previousCounts.remove(profile.getId());
            return -1;
        }

        consecutiveMisses.remove(profile.getId());

        // Scan digit templates to determine the displayed count
        int bestDigit = -1;
        double bestConfidence = 0.88d;

        for (int digit = 0; digit < DIGIT_TEMPLATES.length; digit++) {
            ImageSearchResultData digitResult = controller.locatePattern(
                    profile.getEmulatorNumber(), screenshot,
                    DIGIT_TEMPLATES[digit],
                    SCAN_ORIGIN, SCAN_EXTENT, 0.88d);

            if (digitResult.isFound() && digitResult.getMatchPercentage() > bestConfidence) {
                bestDigit = digit + 1;
                bestConfidence = digitResult.getMatchPercentage();
            }
        }
        return bestDigit;
    }

    private boolean evaluateCountChange(AccountDescriptor profile, int currentCount) {
        Long pid = profile.getId();
        Integer lastCount = previousCounts.get(pid);

        // First observation — store and trigger
        if (lastCount == null) {
            previousCounts.put(pid, currentCount);
            logger.info("Rally count first observed: {}", currentCount);
            emitLog(TpMessageSeverityEnum.INFO, profile,
                    "Rally count first observed: " + currentCount);
            return true;
        }

        // Count increased — check session limit then trigger
        if (currentCount > lastCount) {
            previousCounts.put(pid, currentCount);

            if (isSessionLimitReached(profile)) return false;

            logger.info("Rally count rose: {} → {}", lastCount, currentCount);
            emitLog(TpMessageSeverityEnum.INFO, profile,
                    "Rally count rose: " + lastCount + " → " + currentCount);
            return true;
        }

        // Count decreased — update silently
        if (currentCount < lastCount) {
            previousCounts.put(pid, currentCount);
            logger.debug("Rally count dropped: {} → {}", lastCount, currentCount);
            emitLog(TpMessageSeverityEnum.DEBUG, profile,
                    "Rally count dropped: " + lastCount + " → " + currentCount);
        }

        return false;
    }

    private boolean isSessionLimitReached(AccountDescriptor profile) {
        String modeStr = profile.getConfig(ConfigurationKeyEnum.RALLY_MODE_STRING, String.class);
        int limit = parseSessionLimit(modeStr);
        if (limit < 0) return false;

        int joined = sessionJoinTotals.getOrDefault(profile.getId(), 0);
        if (joined >= limit) {
            logger.info("Session rally limit reached ({}). Skipping.", limit);
            emitLog(TpMessageSeverityEnum.INFO, profile,
                    "Session rally limit reached (" + limit + "). Skipping.");
            return true;
        }
        return false;
    }

    private int parseSessionLimit(String modeString) {
        if (modeString == null || !modeString.toLowerCase().contains("limited")) return -1;
        try {
            return Integer.parseInt(modeString.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void emitLog(TpMessageSeverityEnum severity, AccountDescriptor profile, String msg) {
        LoggingService.obtain().emit(severity, getRuleName(), profile.getName(), msg);
    }
}
