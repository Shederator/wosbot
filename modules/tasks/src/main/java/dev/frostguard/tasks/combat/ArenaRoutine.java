package dev.frostguard.tasks.combat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ConfigData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.color.PixelStats;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.ocr.OcrEngine;

/**
 * Task responsible for managing arena challenges.
 *
 * <p>The routine only attacks weaker opponents. Optional profile-based filters
 * protect the profile alliance and apply a server preference using the
 * Character Information stored on the selected profile.</p>
 */
public class ArenaRoutine extends DelayedTask {

    // ========== Configuration Defaults ==========
    private static final String DEFAULT_ACTIVATION_TIME = "23:55"; // UTC
    private static final int DEFAULT_EXTRA_ATTEMPTS = 0;
    private static final boolean DEFAULT_REFRESH_WITH_GEMS = false;
    private static final AlliancePolicy DEFAULT_ALLIANCE_POLICY = AlliancePolicy.AVOID_PROFILE_ALLIANCE;

    // ========== Arena Coordinates ==========
    private static final PointData ARENA_SCORE_TOP_LEFT = new PointData(548, 1064);
    private static final PointData ARENA_SCORE_BOTTOM_RIGHT = new PointData(650, 1100);

    private static final PointData CHALLENGES_LEFT_TOP_LEFT = new PointData(405, 948);
    private static final PointData CHALLENGES_LEFT_BOTTOM_RIGHT = new PointData(438, 994);
    private static final PointData EXTRA_ATTEMPTS_BUTTON = new PointData(467, 965);

    private static final int OPPONENT_BASE_Y_FIRST_RUN = 380;
    private static final int OPPONENT_BASE_Y_NORMAL = 376;
    private static final int OPPONENT_Y_SPACING = 128;
    private static final int OPPONENT_CHALLENGE_BUTTON_X = 624;
    private static final int OPPONENT_CHALLENGE_BUTTON_HALF_WIDTH = 38;
    private static final int OPPONENT_CHALLENGE_BUTTON_HALF_HEIGHT = 38;

    private static final int OPPONENT_NAME_X = 150;
    private static final int OPPONENT_NAME_WIDTH = 260;
    private static final int OPPONENT_NAME_RELATIVE_Y = -70;
    private static final int OPPONENT_NAME_HEIGHT = 32;

    private static final int OPPONENT_SERVER_X = 145;
    private static final int OPPONENT_SERVER_WIDTH = 140;
    private static final int OPPONENT_SERVER_RELATIVE_Y = 12;
    private static final int OPPONENT_SERVER_HEIGHT = 30;

    private static final int POWER_TEXT_RELATIVE_X = 180;
    private static final int POWER_TEXT_WIDTH = 130;
    private static final int POWER_TEXT_SERVER_LAYOUT_Y_OFFSET = -32;
    private static final int POWER_TEXT_SERVER_LAYOUT_HEIGHT = 30;
    private static final int POWER_TEXT_COMPACT_LAYOUT_Y_OFFSET = 0;
    private static final int POWER_TEXT_COMPACT_LAYOUT_HEIGHT = 22;
    private static final Color ARENA_POWER_GREEN_TEXT = new Color(105, 230, 50);

    private static final int OPPONENT_SCORE_STAR_X = 296;
    private static final int OPPONENT_SCORE_STAR_WIDTH = 42;
    private static final int OPPONENT_SCORE_STAR_HEIGHT = 34;
    private static final int OPPONENT_SCORE_STAR_SERVER_LAYOUT_Y_OFFSET = -34;
    private static final int OPPONENT_SCORE_STAR_COMPACT_LAYOUT_Y_OFFSET = -4;
    private static final int MIN_STAR_YELLOW_PIXELS = 20;

    private static final PointData QUICK_DEPLOY_BUTTON = new PointData(180, 1200);
    private static final PointData BATTLE_START_BUTTON = new PointData(530, 1200);
    private static final PointData BATTLE_PAUSE_BUTTON = new PointData(60, 962);
    private static final PointData BATTLE_RETREAT_BUTTON = new PointData(252, 635);

    private static final AreaData BATTLE_RESULT_AREA = new AreaData(
            new PointData(150, 350),
            new PointData(570, 520));

    private static final PointData PURCHASE_PRICE_TOP_LEFT = new PointData(328, 840);
    private static final PointData PURCHASE_PRICE_BOTTOM_RIGHT = new PointData(433, 883);
    private static final PointData PURCHASE_COUNTER_SWIPE_START = new PointData(420, 733);
    private static final PointData PURCHASE_COUNTER_SWIPE_END = new PointData(40, 733);
    private static final PointData PURCHASE_CONFIRM_BUTTON = new PointData(360, 860);
    private static final PointData PURCHASE_COUNTER_INCREMENT_TOP_LEFT = new PointData(457, 713);
    private static final PointData PURCHASE_COUNTER_INCREMENT_BOTTOM_RIGHT = new PointData(499, 752);

    // ========== Arena Constants ==========
    private static final int INITIAL_ARENA_SCORE = 1000;
    private static final int MAX_REASONABLE_CHALLENGE_ATTEMPTS = 10;
    private static final int MAX_FREE_REFRESHES = 3;
    private static final int MAX_GEM_REFRESHES = 5;
    static final SearchConfig TRANSIENT_BUTTON_SEARCH = SearchConfig.builder()
            .withMaxAttempts(3)
            .withDelay(500)
            .build();
    private static final int[] ATTEMPT_PRICES = { 100, 200, 400, 600, 800 };
    private static final int MIN_COLORED_PIXELS_THRESHOLD = 10;
    private static final double GREEN_DOMINANCE_RATIO = 1.5;
    private static final double RED_DOMINANCE_RATIO = 1.5;
    private static final int COLOR_ANALYSIS_STEP_SIZE = 2;
    private static final int MAX_OPPONENTS = 5;
    private static final Pattern ALLIANCE_TAG_PATTERN = Pattern.compile("\\[([A-Za-z0-9]{1,4})]");
    private static final Pattern MALFORMED_ALLIANCE_TAG_PATTERN = Pattern.compile("\\[([A-Za-z0-9]{1,4})");
    private static final Pattern BRACKETLESS_ALLIANCE_TAG_PATTERN = Pattern.compile("^[\\[\\|Il1t]([A-Z0-9]{2,4})(?=[a-z\\]|Il1~\\-])");
    private static final Pattern BRACKETLESS_TRAILING_BRACKET_PATTERN = Pattern.compile("^[\\[\\|Il1t]?([A-Z0-9]{2,4})[^\\]]*]");
    private static final Pattern SERVER_PATTERN = Pattern.compile("(\\d{3,5})");
    private static final Pattern POWER_VALUE_PATTERN = Pattern.compile("([0-9]+[.,][0-9]+)([KMBkmb])");
    private static final int OCR_RETRY_DELAY_MS = 250;

    // ========== Configuration ==========
    private String activationTime;
    private int extraAttempts;
    private boolean refreshWithGems;
    private AlliancePolicy alliancePolicy;
    private ServerPolicy serverPolicy;
    private String profileAllianceTag;
    private String profileServer;

    // ========== Execution State ==========
    private int attempts;
    private boolean firstRun;
    private int freeRefreshCount;
    private int gemRefreshCount;
    private boolean extraAttemptsPurchased;
    private boolean noDailyChallengesDetected;
    private final Set<Integer> attemptedOpponentSlots = new HashSet<>();

    public ArenaRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected boolean acceptsInjections() {
        return false;
    }

    private void loadConfiguration() {
        String configuredTime = profile.getConfig(
                ConfigurationKeyEnum.ARENA_TASK_ACTIVATION_TIME_STRING, String.class);
        this.activationTime = configuredTime != null ? configuredTime : DEFAULT_ACTIVATION_TIME;

        Integer configuredAttempts = profile.getConfig(
                ConfigurationKeyEnum.ARENA_TASK_EXTRA_ATTEMPTS_INT, Integer.class);
        this.extraAttempts = configuredAttempts != null ? configuredAttempts : DEFAULT_EXTRA_ATTEMPTS;

        Boolean configuredRefresh = profile.getConfig(
                ConfigurationKeyEnum.ARENA_TASK_REFRESH_WITH_GEMS_BOOL, Boolean.class);
        this.refreshWithGems = configuredRefresh != null ? configuredRefresh : DEFAULT_REFRESH_WITH_GEMS;

        this.alliancePolicy = resolveAlliancePolicy();

        String configuredServerPolicy = profile.getConfig(
                ConfigurationKeyEnum.ARENA_TASK_SERVER_POLICY_STRING, String.class);
        this.serverPolicy = ServerPolicy.fromConfig(configuredServerPolicy);

        this.profileAllianceTag = normalizeAllianceTag(profile.getCharacterAllianceCode());
        this.profileServer = normalizeServer(profile.getCharacterServer());

        if (profileAllianceTag == null && alliancePolicy != AlliancePolicy.ANY) {
            logWarning("Arena alliance policy requires profile Alliance, but it is not set. Using Any alliance.");
            alliancePolicy = AlliancePolicy.ANY;
        }
        if (profileServer == null && serverPolicy != ServerPolicy.ANY) {
            logWarning("Arena server policy requires profile Server, but it is not set. Using Any server.");
            serverPolicy = ServerPolicy.ANY;
        }

        logDebug(String.format(
                "Configuration loaded - Time: %s, Extra attempts: %d, Refresh list with gems: %s, Alliance policy: %s, Profile alliance: %s, Server policy: %s, Profile server: %s",
                activationTime, extraAttempts, refreshWithGems, alliancePolicy.displayName,
                profileAllianceTag != null ? profileAllianceTag : "not set",
                serverPolicy.displayName,
                profileServer != null ? profileServer : "not set"));
    }

    private void resetExecutionState() {
        this.attempts = 0;
        this.firstRun = false;
        this.freeRefreshCount = 0;
        this.gemRefreshCount = 0;
        this.extraAttemptsPurchased = false;
        this.noDailyChallengesDetected = false;
        this.attemptedOpponentSlots.clear();
        logDebug("Execution state reset");
    }

    private boolean hasConfig(ConfigurationKeyEnum key) {
        return profile.getEntries().stream()
                .map(ConfigData::getSettingKey)
                .anyMatch(settingKey -> key.equals(settingKey) || key.name().equalsIgnoreCase(String.valueOf(settingKey)));
    }

    private AlliancePolicy resolveAlliancePolicy() {
        boolean hasPolicyConfig = hasConfig(ConfigurationKeyEnum.ARENA_TASK_ALLIANCE_POLICY_STRING);
        boolean hasLegacyConfig = hasConfig(ConfigurationKeyEnum.ARENA_TASK_PROTECT_ALLIANCE_BOOL);
        String configuredPolicy = hasPolicyConfig
                ? profile.getConfig(ConfigurationKeyEnum.ARENA_TASK_ALLIANCE_POLICY_STRING, String.class)
                : null;

        if (hasLegacyConfig) {
            Boolean legacyProtectAlliance = profile.getConfig(
                    ConfigurationKeyEnum.ARENA_TASK_PROTECT_ALLIANCE_BOOL, Boolean.class);
            if (Boolean.FALSE.equals(legacyProtectAlliance) && AlliancePolicy.isDefaultConfig(configuredPolicy)) {
                return AlliancePolicy.ANY;
            }
            if (!hasPolicyConfig) {
                return legacyProtectAlliance == null || legacyProtectAlliance
                        ? DEFAULT_ALLIANCE_POLICY
                        : AlliancePolicy.ANY;
            }
        }

        return hasPolicyConfig
                ? AlliancePolicy.fromConfig(configuredPolicy)
                : DEFAULT_ALLIANCE_POLICY;
    }

    @Override
    protected void execute() {
        loadConfiguration();
        resetExecutionState();

        if (!isValidTimeFormat(activationTime)) {
            logWarning("Invalid activation time format: " + activationTime +
                    ". Scheduling to 5 min before reset.");
            reschedule(GameTimeUtils.dailyResetTime().minusMinutes(5));
            return;
        }

        if (!isWithinExecutionWindow()) {
            return;
        }

        if (!navigateToArena()) {
            logWarning("Failed to navigate to arena.");
            rescheduleWithActivationHour();
            return;
        }

        firstRun = detectFirstRun();

        if (!openChallengeList()) {
            logWarning("Failed to open challenge list.");
            rescheduleWithActivationHour();
            return;
        }

        if (!readInitialAttempts()) {
            logWarning("Failed to read initial attempts.");
            rescheduleWithActivationHour();
            return;
        }

        processChallenges();

        rescheduleWithActivationHour();
    }

    private boolean isWithinExecutionWindow() {
        if (!isValidTimeFormat(activationTime)) {
            return true;
        }

        ZonedDateTime nowUtc = ZonedDateTime.now(ZoneId.of("UTC"));
        String[] timeParts = activationTime.split(":");
        int hour = Integer.parseInt(timeParts[0]);
        int minute = Integer.parseInt(timeParts[1]);

        ZonedDateTime scheduledTimeUtc = nowUtc.toLocalDate()
                .atTime(hour, minute)
                .atZone(ZoneId.of("UTC"));
        ZonedDateTime cutoffTimeUtc = nowUtc.toLocalDate()
                .atTime(23, 56)
                .atZone(ZoneId.of("UTC"));

        if (nowUtc.isBefore(scheduledTimeUtc)) {
            logDebug(String.format("Task triggered too early (current: %s UTC, scheduled: %s UTC). Rescheduling for scheduled time.",
                    nowUtc.format(DateTimeFormatter.ofPattern("HH:mm")), activationTime));
            rescheduleForToday();
            return false;
        }

        if (nowUtc.isAfter(cutoffTimeUtc)) {
            logDebug(String.format("Task triggered too late (current: %s UTC, cutoff: 23:55 UTC). Scheduling for tomorrow.",
                    nowUtc.format(DateTimeFormatter.ofPattern("HH:mm"))));
            rescheduleWithActivationHour();
            return false;
        }

        logDebug(String.format("Task running within window (current: %s UTC, window: %s - 23:55 UTC)",
                nowUtc.format(DateTimeFormatter.ofPattern("HH:mm")), activationTime));
        return true;
    }

    private boolean navigateToArena() {
        return navigationHelper.navigateToSidebarDestination(SidebarDestination.ARENA);
    }

    private boolean detectFirstRun() {
        logDebug("Checking if this is first arena run");

        OcrSettingsData configMap = OcrSettingsData.assembler()
                .stripBackground(true)
                .setTextColor(new Color(255, 255, 255))
                .charWhitelist("0123456789")
                .build();

        Integer arenaScore = readNumberValue(
                ARENA_SCORE_TOP_LEFT,
                ARENA_SCORE_BOTTOM_RIGHT,
                configMap);

        if (arenaScore == null) {
            logWarning("Failed to read arena score, assuming not first run");
            return false;
        }

        logInfo("Arena score: " + arenaScore);
        boolean isFirstRun = arenaScore == INITIAL_ARENA_SCORE;

        if (isFirstRun) {
            logInfo("First run detected (score = 1000)");
        }

        return isFirstRun;
    }

    private boolean openChallengeList() {
        logDebug("Opening challenge list");

        ImageSearchResultData challengeResult = templateSearchHelper.locatePattern(
                TemplatesEnum.ARENA_CHALLENGE_BUTTON,
                TRANSIENT_BUTTON_SEARCH);

        if (!challengeResult.isFound()) {
            logError("Challenge button not found.");
            return false;
        }

        tapInside(challengeResult);
        sleepTask(1000);

        return true;
    }

    private boolean readInitialAttempts() {
        logDebug("Reading initial challenge attempts");

        OcrSettingsData strictSettings = OcrSettingsData.assembler()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)

                .stripBackground(true)
                .setTextColor(new Color(91, 112, 147))
                .charWhitelist("0123456789")
                .build();

        Integer attemptsRead = readNumberValue(
                CHALLENGES_LEFT_TOP_LEFT,
                CHALLENGES_LEFT_BOTTOM_RIGHT,
                strictSettings);

        if (attemptsRead == null) {
            logDebug("Attempt count strict OCR did not produce a value. Retrying with relaxed settings.");

            OcrSettingsData relaxedSettings = OcrSettingsData.assembler()
                    .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)

                    .stripBackground(true)
                    .charWhitelist("0123456789")
                    .build();

            attemptsRead = readNumberValue(
                    CHALLENGES_LEFT_TOP_LEFT,
                    CHALLENGES_LEFT_BOTTOM_RIGHT,
                    relaxedSettings);
        }

        if (attemptsRead == null) {
            logWarning("Attempt count OCR failed. Assuming 5 attempts and checking each challenge button before attacking.");
            this.attempts = 5;
            return true;
        }

        if (attemptsRead < 0 || attemptsRead > MAX_REASONABLE_CHALLENGE_ATTEMPTS) {
            logWarning(String.format(
                    "Attempt count OCR produced implausible value %d. Assuming 5 attempts and checking each challenge button before attacking.",
                    attemptsRead));
            this.attempts = 5;
            return true;
        }

        this.attempts = attemptsRead;
        logInfo("Initial attempts available: " + attempts);
        return true;
    }

    private void processChallenges() {
        logInfo(String.format("Processing %d challenge attempts", attempts));

        while (true) {
            while (attempts > 0) {
                OpponentCandidate opponent = findEligibleOpponent();
                if (opponent != null) {
                    ChallengeOutcome outcome = challengeOpponent(opponent);
                    if (outcome == ChallengeOutcome.NO_ATTEMPTS) {
                        break;
                    }
                    if (outcome == ChallengeOutcome.DEFEAT) {
                        if (!shouldRefreshAfterDefeat(attempts)) {
                            logInfo(String.format(
                                    "Our attack lost to opponent %d. No attempts remain; skipping list refresh.",
                                    opponent.number()));
                            continue;
                        }
                        logInfo(String.format(
                                "Our attack lost to opponent %d. Refreshing list before using another attempt.",
                                opponent.number()));
                        if (tryRefreshOpponentList()) {
                            logInfo("List refreshed after defeat. Rescanning opponents.");
                            continue;
                        }
                        logInfo(String.format(
                                "No list refresh available after defeat. Continuing current list with tried opponent %d excluded. Attempts unused: %d",
                                opponent.number(),
                                attempts));
                        continue;
                    }
                    continue;
                }

                if (tryRefreshOpponentList()) {
                    logInfo("List refreshed. Rescanning opponents.");
                    continue;
                }

                logInfo(String.format("No eligible arena opponents after refresh checks. Stopping with %d attempts unused.", attempts));
                return;
            }

            if (extraAttemptsPurchased || extraAttempts <= 0) {
                break;
            }

            extraAttemptsPurchased = true;
            OpponentCandidate availableTarget = findEligibleOpponent();
            if (availableTarget == null) {
                logInfo("Extra attempts configured, but no eligible target is visible. Skipping extra attempt purchase.");
                break;
            }

            int attemptsBought = buyExtraAttempts();
            if (attemptsBought <= 0) {
                break;
            }

            attempts += attemptsBought;
            logInfo(String.format("Purchased %d extra attempts after confirming an eligible target. Total attempts: %d",
                    attemptsBought, attempts));
        }

        if (noDailyChallengesDetected && extraAttempts <= 0) {
            logInfo("No daily arena challenges are available and extra attempts are disabled.");
        } else {
            logInfo("Arena run finished after using available eligible attempts.");
        }
    }

    static boolean shouldRefreshAfterDefeat(int remainingAttempts) {
        return remainingAttempts > 0;
    }

    private OpponentCandidate findEligibleOpponent() {
        List<OpponentCandidate> opponents = scanOpponents();
        List<OpponentCandidate> eligible = opponents.stream()
                .filter(OpponentCandidate::eligible)
                .filter(candidate -> !attemptedOpponentSlots.contains(candidate.number()))
                .sorted(alliancePolicy.comparator(profileAllianceTag)
                        .thenComparing(serverPolicy.comparator(profileServer))
                        .thenComparing(candidate -> candidate.power().value(), ArenaRoutine::comparePowerValues)
                        .thenComparingInt(OpponentCandidate::number))
                .toList();

        if (eligible.isEmpty()) {
            long alreadyTried = opponents.stream()
                    .filter(OpponentCandidate::eligible)
                    .filter(candidate -> attemptedOpponentSlots.contains(candidate.number()))
                    .count();
            if (alreadyTried > 0) {
                logInfo(String.format(
                        "No eligible opponent found in current arena list. %d eligible opponent(s) were already tried in this list.",
                        alreadyTried));
            } else {
                logInfo("No eligible opponent found in current arena list.");
            }
            return null;
        }

        OpponentCandidate selected = eligible.get(0);
        logInfo(String.format("Selected opponent %d (%s)", selected.number(), selected.selectionSummary()));
        return selected;
    }

    private List<OpponentCandidate> scanOpponents() {
        int baseY = firstRun ? OPPONENT_BASE_Y_FIRST_RUN : OPPONENT_BASE_Y_NORMAL;
        List<OpponentCandidate> opponents = new ArrayList<>(MAX_OPPONENTS);

        for (int i = 0; i < MAX_OPPONENTS; i++) {
            int opponentY = baseY + (i * OPPONENT_Y_SPACING);
            int opponentNumber = i + 1;
            OpponentLayout layout = detectOpponentLayout(opponentY, opponentNumber);
            PowerRead power = readPower(opponentY, opponentNumber, layout);
            AllianceRead alliance = AllianceRead.notChecked();
            ServerRead server = ServerRead.notChecked();
            if (power.relation() == PowerRelation.WEAKER) {
                if (alliancePolicy != AlliancePolicy.ANY && profileAllianceTag != null) {
                    alliance = readAllianceTag(opponentY, opponentNumber);
                }
                if (serverPolicy != ServerPolicy.ANY && profileServer != null) {
                    server = readServer(opponentY, opponentNumber, layout);
                }
            }
            OpponentCandidate candidate = classifyOpponent(opponentNumber, opponentY, power, alliance, server);
            opponents.add(candidate);
            logInfo(String.format("Opponent %d scan: layout=%s power=%s alliance=%s server=%s decision=%s",
                    opponentNumber, layout.label, power.logValue(), alliance.logValue(), server.logValue(), candidate.decision()));
            sleepTask(150);
        }

        return opponents;
    }

    private OpponentCandidate classifyOpponent(int number, int opponentY, PowerRead power,
                                               AllianceRead alliance, ServerRead server) {
        if (power.relation() != PowerRelation.WEAKER) {
            return OpponentCandidate.skipped(number, opponentY, power, alliance, server, power.relation().skipReason);
        }

        if (alliancePolicy == AlliancePolicy.NEVER_PROFILE_ALLIANCE && profileAllianceTag != null) {
            if (alliance.status == AllianceStatus.UNREADABLE) {
                return OpponentCandidate.skipped(number, opponentY, power, alliance, server, "alliance unreadable");
            }
            if (profileAllianceTag.equals(alliance.tag)) {
                return OpponentCandidate.skipped(number, opponentY, power, alliance, server, "profile alliance");
            }
        }

        if (serverPolicy == ServerPolicy.NEVER_PROFILE_SERVER && profileServer != null) {
            if (server.status != ServerStatus.READ) {
                return OpponentCandidate.skipped(number, opponentY, power, alliance, server, "server not visible/readable");
            }
            if (profileServer.equals(server.value)) {
                return OpponentCandidate.skipped(number, opponentY, power, alliance, server, "profile server");
            }
        }

        return OpponentCandidate.eligible(number, opponentY, power, alliance, server);
    }

    private OpponentLayout detectOpponentLayout(int opponentY, int opponentNumber) {
        int serverStarTopY = opponentY + OPPONENT_SCORE_STAR_SERVER_LAYOUT_Y_OFFSET;
        int compactStarTopY = opponentY + OPPONENT_SCORE_STAR_COMPACT_LAYOUT_Y_OFFSET;
        BufferedImage image = captureFrameForPixelScan("arena layout probe");
        if (image == null) {
            logWarning(String.format("Opponent %d layout probe failed; assuming server layout.", opponentNumber));
            return OpponentLayout.UNKNOWN;
        }

        int serverLayoutYellow = countPixels(
                image,
                new PointData(OPPONENT_SCORE_STAR_X, serverStarTopY),
                new PointData(
                        OPPONENT_SCORE_STAR_X + OPPONENT_SCORE_STAR_WIDTH,
                        serverStarTopY + OPPONENT_SCORE_STAR_HEIGHT),
                GameColors::isArenaStarYellow);
        int compactLayoutYellow = countPixels(
                image,
                new PointData(OPPONENT_SCORE_STAR_X, compactStarTopY),
                new PointData(
                        OPPONENT_SCORE_STAR_X + OPPONENT_SCORE_STAR_WIDTH,
                        compactStarTopY + OPPONENT_SCORE_STAR_HEIGHT),
                GameColors::isArenaStarYellow);
        int minStarYellowPixels = scaleSampledThreshold(MIN_STAR_YELLOW_PIXELS);

        OpponentLayout layout;
        if (serverLayoutYellow >= minStarYellowPixels
                && serverLayoutYellow >= compactLayoutYellow) {
            layout = OpponentLayout.SERVER_ROW;
        } else if (compactLayoutYellow >= minStarYellowPixels) {
            layout = OpponentLayout.COMPACT;
        } else {
            layout = OpponentLayout.UNKNOWN;
        }

        logDebug(String.format("Opponent %d layout probe: %s (upper star=%d, lower star=%d)",
                opponentNumber, layout.label, serverLayoutYellow, compactLayoutYellow));
        return layout;
    }

    private PowerRead readPower(int opponentY, int opponentNumber, OpponentLayout layout) {
        PowerRead primary = readPowerRelationAt(
                opponentY,
                opponentNumber,
                layout.powerYOffset(),
                layout.powerHeight(),
                layout.label);
        if (primary.relation() != PowerRelation.UNKNOWN) {
            return enrichPowerWithOcr(
                    opponentY, opponentNumber, layout.powerYOffset(), layout.powerHeight(), layout.label, primary);
        }

        OpponentLayout fallbackLayout = layout == OpponentLayout.COMPACT
                ? OpponentLayout.SERVER_ROW
                : OpponentLayout.COMPACT;
        PowerRead fallback = readPowerRelationAt(
                opponentY,
                opponentNumber,
                fallbackLayout.powerYOffset(),
                fallbackLayout.powerHeight(),
                fallbackLayout.label + " fallback");
        if (fallback.relation() != PowerRelation.UNKNOWN) {
            return enrichPowerWithOcr(
                    opponentY, opponentNumber, fallbackLayout.powerYOffset(), fallbackLayout.powerHeight(),
                    fallbackLayout.label + " fallback", fallback);
        }
        return fallback;
    }

    private PowerRead readPowerRelationAt(int opponentY, int opponentNumber, int yOffset, int height, String label) {
        int powerY = opponentY + yOffset;
        logDebug(String.format("Analyzing opponent %d power (%s, y=%d)",
                opponentNumber, label, powerY));

        AreaData powerArea = powerTextArea(opponentY, yOffset, height);
        PointData topLeft = powerArea.topLeft();
        PointData bottomRight = powerArea.bottomRight();

        BufferedImage image = captureFrameForPixelScan("arena power read");
        if (image == null) {
            return PowerRead.withoutValue(PowerRelation.UNKNOWN);
        }

        int backgroundPixels = countPixels(image, topLeft, bottomRight, GameColors::isArenaBlueGrey);
        int greenPixels = countPixels(image, topLeft, bottomRight, GameColors::isArenaPowerGreen);
        int redPixels = countPixels(image, topLeft, bottomRight, GameColors::isArenaPowerRed);
        int totalColoredPixels = greenPixels + redPixels;
        int minColoredPixels = scaleSampledThreshold(MIN_COLORED_PIXELS_THRESHOLD);

        logDebug(String.format(
                "Opponent %d power colors (%s) - Background: %d, Green: %d, Red: %d, TotalColor: %d, Threshold: %d",
                opponentNumber, label, backgroundPixels, greenPixels, redPixels,
                totalColoredPixels, minColoredPixels));

        if (totalColoredPixels <= minColoredPixels) {
            return PowerRead.withoutValue(PowerRelation.UNKNOWN);
        }
        if (greenPixels > redPixels * GREEN_DOMINANCE_RATIO) {
            return PowerRead.withoutValue(PowerRelation.WEAKER);
        }
        if (redPixels > greenPixels * RED_DOMINANCE_RATIO) {
            return PowerRead.withoutValue(PowerRelation.STRONGER);
        }
        return PowerRead.withoutValue(PowerRelation.UNKNOWN);
    }

    private PowerRead enrichPowerWithOcr(int opponentY, int opponentNumber, int yOffset, int height,
                                         String label, PowerRead colorRead) {
        if (colorRead.relation() != PowerRelation.WEAKER) {
            return colorRead;
        }
        AreaData powerArea = powerTextArea(opponentY, yOffset, height);
        PointData topLeft = powerArea.topLeft();
        PointData bottomRight = powerArea.bottomRight();

        OcrSettingsData powerSettings = powerOcrSettings();

        String text = stringHelper.attemptRecognition(
                topLeft,
                bottomRight,
                3,
                150L,
                powerSettings,
                value -> parsePowerValue(value) != null,
                value -> value);
        Double powerValue = parsePowerValue(text);
        if (powerValue == null) {
            logDebug(String.format("Opponent %d power OCR (%s) did not produce a sortable value.", opponentNumber, label));
            return colorRead;
        }

        logDebug(String.format("Opponent %d power OCR (%s): '%s' -> %.0f",
                opponentNumber, label, text, powerValue));
        return new PowerRead(colorRead.relation(), powerValue, text);
    }

    static OcrSettingsData powerOcrSettings() {
        return OcrSettingsData.assembler()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
                .stripBackground(true)
                .setTextColor(ARENA_POWER_GREEN_TEXT)
                .charWhitelist("0123456789.,KMBkmb")
                .build();
    }

    static AreaData serverPowerTextArea(int opponentY) {
        return powerTextArea(opponentY, POWER_TEXT_SERVER_LAYOUT_Y_OFFSET, POWER_TEXT_SERVER_LAYOUT_HEIGHT);
    }

    private static AreaData powerTextArea(int opponentY, int yOffset, int height) {
        int powerY = opponentY + yOffset;
        return AreaData.of(
                POWER_TEXT_RELATIVE_X,
                powerY,
                POWER_TEXT_RELATIVE_X + POWER_TEXT_WIDTH,
                powerY + height);
    }

    static Double parsePowerValue(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim().replace(" ", "");
        Matcher matcher = POWER_VALUE_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return null;
        }

        String number = matcher.group(1);
        String unit = matcher.group(2).toUpperCase(Locale.ROOT);
        if (number.contains(",") && !number.contains(".")) {
            number = number.replace(",", ".");
        } else {
            number = number.replace(",", "");
        }

        try {
            double value = Double.parseDouble(number);
            return switch (unit) {
                case "K" -> value * 1_000D;
                case "M" -> value * 1_000_000D;
                case "B" -> value * 1_000_000_000D;
                default -> throw new IllegalStateException("Unsupported arena power unit: " + unit);
            };
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static int comparePowerValues(Double left, Double right) {
        if (left == null) {
            return right == null ? 0 : 1;
        }
        if (right == null) {
            return -1;
        }
        return Double.compare(left, right);
    }

    private AllianceRead readAllianceTag(int opponentY, int opponentNumber) {
        String text = readOpponentText(
                new PointData(OPPONENT_NAME_X, opponentY + OPPONENT_NAME_RELATIVE_Y),
                new PointData(OPPONENT_NAME_X + OPPONENT_NAME_WIDTH, opponentY + OPPONENT_NAME_RELATIVE_Y + OPPONENT_NAME_HEIGHT),
                "[]ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789",
                "alliance/name",
                opponentNumber);

        AllianceRead read = classifyAllianceText(text);
        if (read.status == AllianceStatus.UNREADABLE) {
            logDebug(String.format("Opponent %d alliance/name OCR inconclusive (%s). Retrying once.",
                    opponentNumber, read.detail()));
            sleepTask(OCR_RETRY_DELAY_MS);
            String retryText = readOpponentText(
                    new PointData(OPPONENT_NAME_X, opponentY + OPPONENT_NAME_RELATIVE_Y),
                    new PointData(OPPONENT_NAME_X + OPPONENT_NAME_WIDTH, opponentY + OPPONENT_NAME_RELATIVE_Y + OPPONENT_NAME_HEIGHT),
                    "[]ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789",
                    "alliance/name retry",
                    opponentNumber);
            read = classifyAllianceText(retryText);
            if (read.status == AllianceStatus.UNREADABLE) {
                logWarning(String.format("Opponent %d alliance/name OCR failed after retry (%s).",
                        opponentNumber, read.detail()));
            }
        }

        return read;
    }

    private AllianceRead classifyAllianceText(String text) {
        if (text == null || text.isBlank()) {
            return AllianceRead.unreadable("empty");
        }
        Matcher matcher = ALLIANCE_TAG_PATTERN.matcher(text);
        if (matcher.find()) {
            String tag = normalizeAllianceTag(matcher.group(1));
            return AllianceRead.read(resolveProfileAllianceIfClose(tag));
        }
        if (looksLikeProfileAlliance(text)) {
            return AllianceRead.read(profileAllianceTag);
        }
        Matcher malformedMatcher = MALFORMED_ALLIANCE_TAG_PATTERN.matcher(text);
        if (malformedMatcher.find()) {
            String tag = normalizeAllianceTag(malformedMatcher.group(1));
            if (matchesProfileAllianceFuzzy(tag)) {
                return AllianceRead.read(profileAllianceTag);
            }
            return AllianceRead.malformed(tag, "missing closing bracket");
        }
        String bracketlessTag = extractBracketlessAllianceTag(text);
        if (bracketlessTag != null) {
            if (matchesProfileAllianceFuzzy(bracketlessTag)) {
                return AllianceRead.read(profileAllianceTag);
            }
            return AllianceRead.malformed(bracketlessTag, "bracket-like prefix");
        }
        String trailingBracketTag = extractTrailingBracketAllianceTag(text);
        if (trailingBracketTag != null) {
            if (matchesProfileAllianceFuzzy(trailingBracketTag)) {
                return AllianceRead.read(profileAllianceTag);
            }
            return AllianceRead.malformed(trailingBracketTag, "bracket-like closing marker");
        }
        if (looksLikeGuard(text)) {
            return AllianceRead.guard();
        }
        if (text.contains("[") || text.contains("]")) {
            return AllianceRead.unreadable("bracket without tag");
        }

        return AllianceRead.noAlliance();
    }

    private ServerRead readServer(int opponentY, int opponentNumber, OpponentLayout layout) {
        if (!layout.serverVisible()) {
            return new ServerRead(null, ServerStatus.NOT_SHOWN);
        }

        String text = readOpponentText(
                new PointData(OPPONENT_SERVER_X, opponentY + OPPONENT_SERVER_RELATIVE_Y),
                new PointData(OPPONENT_SERVER_X + OPPONENT_SERVER_WIDTH, opponentY + OPPONENT_SERVER_RELATIVE_Y + OPPONENT_SERVER_HEIGHT),
                "#0123456789",
                new Color(255, 255, 255),
                "server",
                opponentNumber);

        if (text == null || text.isBlank()) {
            return new ServerRead(null, ServerStatus.NOT_SHOWN);
        }

        Matcher matcher = SERVER_PATTERN.matcher(text);
        if (matcher.find()) {
            return new ServerRead(normalizeServer(matcher.group(1)), ServerStatus.READ);
        }

        logDebug(String.format("Opponent %d server OCR failed. Retrying once.", opponentNumber));
        sleepTask(OCR_RETRY_DELAY_MS);
        String retryText = readOpponentText(
                new PointData(OPPONENT_SERVER_X, opponentY + OPPONENT_SERVER_RELATIVE_Y),
                new PointData(OPPONENT_SERVER_X + OPPONENT_SERVER_WIDTH, opponentY + OPPONENT_SERVER_RELATIVE_Y + OPPONENT_SERVER_HEIGHT),
                "#0123456789",
                new Color(255, 255, 255),
                "server retry",
                opponentNumber);
        Matcher retryMatcher = SERVER_PATTERN.matcher(retryText == null ? "" : retryText);
        if (retryMatcher.find()) {
            return new ServerRead(normalizeServer(retryMatcher.group(1)), ServerStatus.READ);
        }
        logWarning(String.format("Opponent %d server OCR failed after retry: '%s'", opponentNumber, retryText));
        return new ServerRead(null, ServerStatus.UNREADABLE);
    }

    private String readOpponentText(PointData topLeft, PointData bottomRight, String whitelist,
                                    String label, int opponentNumber) {
        return readOpponentText(topLeft, bottomRight, whitelist, null, label, opponentNumber);
    }

    private String readOpponentText(PointData topLeft, PointData bottomRight, String whitelist, Color textColor,
                                    String label, int opponentNumber) {
        OcrSettingsData settings = OcrSettingsData.assembler()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)

                .stripBackground(true)
                .setTextColor(textColor)
                .charWhitelist(whitelist)
                .build();

        String text = readStringValue(topLeft, bottomRight, settings);
        logDebug(String.format("Opponent %d %s OCR result: %s",
                opponentNumber, label, text == null ? "null" : "'" + text + "'"));
        return text == null ? null : text.trim();
    }

    private BufferedImage captureFrameForPixelScan(String context) {
        try {
            RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
            return dev.frostguard.vision.convert.ImageConverter.toBufferedImage(frame);
        } catch (Exception ex) {
            logError(String.format("%s color analysis failed: %s", context, ex.getMessage()));
            return null;
        }
    }

    private int countPixels(BufferedImage image, PointData topLeft, PointData bottomRight, IntPredicate matcher) {
        return PixelStats.count(image, new AreaData(topLeft, bottomRight), matcher);
    }

    private int scaleSampledThreshold(int sampledThreshold) {
        return sampledThreshold * COLOR_ANALYSIS_STEP_SIZE * COLOR_ANALYSIS_STEP_SIZE;
    }

    private boolean looksLikeProfileAlliance(String text) {
        if (profileAllianceTag == null || text == null || text.isBlank()) {
            return false;
        }
        String normalized = normalizeAllianceTag(text.replaceAll("[^A-Za-z0-9]", ""));
        if (normalized == null) {
            return false;
        }
        int prefixLength = Math.min(normalized.length(), Math.max(6, profileAllianceTag.length()));
        return normalized.substring(0, prefixLength).contains(profileAllianceTag);
    }

    private String resolveProfileAllianceIfClose(String tag) {
        return matchesProfileAllianceFuzzy(tag) ? profileAllianceTag : tag;
    }

    private boolean matchesProfileAllianceFuzzy(String tag) {
        if (profileAllianceTag == null || tag == null || tag.length() != profileAllianceTag.length()) {
            return false;
        }
        if (profileAllianceTag.equals(tag)) {
            return true;
        }
        int differences = 0;
        for (int i = 0; i < tag.length(); i++) {
            if (tag.charAt(i) != profileAllianceTag.charAt(i)) {
                differences++;
            }
        }
        return differences <= 1;
    }

    private String extractBracketlessAllianceTag(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String compact = text.replaceAll("\\s", "");
        Matcher matcher = BRACKETLESS_ALLIANCE_TAG_PATTERN.matcher(compact);
        if (!matcher.find()) {
            return null;
        }
        String tag = normalizeAllianceTag(matcher.group(1));
        return tag == null || tag.isBlank() ? null : tag;
    }

    private String extractTrailingBracketAllianceTag(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String compact = text.replaceAll("\\s", "");
        Matcher matcher = BRACKETLESS_TRAILING_BRACKET_PATTERN.matcher(compact);
        if (!matcher.find()) {
            return null;
        }
        String tag = normalizeAllianceTag(matcher.group(1));
        return tag == null || tag.isBlank() ? null : tag;
    }

    private boolean looksLikeGuard(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
        return normalized.contains("COMMONGUARD")
                || normalized.contains("VETERANGUARD")
                || normalized.equals("GUARD");
    }

    private ChallengeOutcome challengeOpponent(OpponentCandidate opponent) {
        logInfo(String.format("Challenging opponent %d (%s)", opponent.number(), opponent.selectionSummary()));

        if (!isChallengeButtonEnabled(opponent)) {
            logInfo(String.format(
                    "Opponent %d challenge button is disabled. No free attempts available; checking extra attempts.",
                    opponent.number()));
            attempts = 0;
            noDailyChallengesDetected = true;
            return ChallengeOutcome.NO_ATTEMPTS;
        }

        attemptedOpponentSlots.add(opponent.number());
        tapNear(new PointData(OPPONENT_CHALLENGE_BUTTON_X, opponent.opponentY()));
        sleepTask(2000);

        executeBattleSequence();
        attempts--;

        boolean victory = checkBattleResult();
        firstRun = false;
        if (victory) {
            StatisticsService.obtain().addToCounter(profile, "Arena Battles Won", 1);
            attemptedOpponentSlots.clear();
        } else {
            StatisticsService.obtain().addToCounter(profile, "Arena Battles Lost", 1);
        }
        sleepTask(1000);
        return victory ? ChallengeOutcome.VICTORY : ChallengeOutcome.DEFEAT;
    }

    private boolean isChallengeButtonEnabled(OpponentCandidate opponent) {
        PointData topLeft = new PointData(
                OPPONENT_CHALLENGE_BUTTON_X - OPPONENT_CHALLENGE_BUTTON_HALF_WIDTH,
                opponent.opponentY() - OPPONENT_CHALLENGE_BUTTON_HALF_HEIGHT);
        PointData bottomRight = new PointData(
                OPPONENT_CHALLENGE_BUTTON_X + OPPONENT_CHALLENGE_BUTTON_HALF_WIDTH,
                opponent.opponentY() + OPPONENT_CHALLENGE_BUTTON_HALF_HEIGHT);

        BufferedImage image = captureFrameForPixelScan("arena challenge button");
        if (image == null) {
            logWarning(String.format(
                    "Opponent %d challenge button color check failed. Treating button as disabled.",
                    opponent.number()));
            return false;
        }

        int saturatedBluePixels = countPixels(image, topLeft, bottomRight, GameColors::isArenaChallengeBlue);
        int greyPixels = countPixels(image, topLeft, bottomRight, GameColors::isArenaChallengeGrey);
        int darkPixels = countPixels(image, topLeft, bottomRight, GameColors::isArenaChallengeDark);
        logDebug(String.format(
                "Opponent %d challenge button colors - saturatedBlue: %d, grey: %d, dark: %d",
                opponent.number(), saturatedBluePixels, greyPixels, darkPixels));

        return saturatedBluePixels > scaleSampledThreshold(250) && saturatedBluePixels > greyPixels;
    }

    void executeBattleSequence() {
        logInfo("Executing battle sequence (with animation skip)");

        boolean useQuickDeploy = profile.getConfig(
                ConfigurationKeyEnum.ARENA_TASK_ATTACK_QUICK_DEPLOY_BOOL, Boolean.class);
        if (useQuickDeploy) {
            logInfo("Tapping Quick Deploy for the Arena attack.");
            tapNear(QUICK_DEPLOY_BUTTON);
            sleepTask(500);
        } else {
            logInfo("Arena attack Quick Deploy is disabled. Keeping the saved attack formation.");
        }

        tapNear(BATTLE_START_BUTTON);
        sleepTask(3000);

        tapNear(BATTLE_PAUSE_BUTTON);
        sleepTask(500);

        tapNear(BATTLE_RETREAT_BUTTON);
        sleepTask(1000);
    }

    private boolean checkBattleResult() {
        ImageSearchResultData victoryResult = templateSearchHelper.locatePattern(
                TemplatesEnum.ARENA_VICTORY,
                SearchConfig.builder()
                        .withArea(BATTLE_RESULT_AREA)
                        .withMaxAttempts(3)
                        .withDelay(250L)
                        .withThreshold(85)
                        .build());

        if (victoryResult.isFound()) {
            logInfo("Battle result: Victory");
            sleepTask(1000);
            pressBack();
            return true;
        }

        logInfo("Battle result: Defeat");
        sleepTask(1000);
        pressBack();
        return false;
    }

    private int buyExtraAttempts() {
        logDebug("Opening extra attempts purchase dialog");
        tapNear(EXTRA_ATTEMPTS_BUTTON);
        sleepTask(1000);

        ImageSearchResultData confirmResult = templateSearchHelper.locatePattern(
                TemplatesEnum.ARENA_GEMS_EXTRA_ATTEMPTS_BUTTON,
                SearchConfig.builder().build());

        if (!confirmResult.isFound()) {
            logInfo("No more extra attempts available for purchase");
            return 0;
        }

        logDebug("Resetting purchase counter to zero");
        swipe(PURCHASE_COUNTER_SWIPE_START, PURCHASE_COUNTER_SWIPE_END);
        sleepTask(300);

        int previousAttempts = detectCurrentAttemptPosition();
        if (previousAttempts < 0) {
            pressBack();
            return 0;
        }

        int remainingAttempts = calculateRemainingAttemptsToBuy(previousAttempts);
        if (remainingAttempts <= 0) {
            pressBack();
            return 0;
        }

        int expectedPrice = calculateTotalPrice(previousAttempts, remainingAttempts);
        logInfo(String.format("Buying %d attempts for %d gems (already have %d)",
                remainingAttempts, expectedPrice, previousAttempts));

        if (remainingAttempts > 1) {
            tapInside(
                    PURCHASE_COUNTER_INCREMENT_TOP_LEFT,
                    PURCHASE_COUNTER_INCREMENT_BOTTOM_RIGHT,
                    remainingAttempts - 1,
                    400);
            sleepTask(300);
        }

        tapNear(PURCHASE_CONFIRM_BUTTON);
        sleepTask(500);

        StatisticsService.obtain().addToCounter(profile, "Arena Gems Spent", expectedPrice);
        return remainingAttempts;
    }

    private int detectCurrentAttemptPosition() {
        logDebug("Detecting current attempt position via price");

        OcrSettingsData configMap = OcrSettingsData.assembler()
                .stripBackground(true)
                .setTextColor(new Color(255, 255, 255))
                .charWhitelist("0123456789")
                .build();

        Integer singleAttemptPrice = readNumberValue(
                PURCHASE_PRICE_TOP_LEFT,
                PURCHASE_PRICE_BOTTOM_RIGHT,
                configMap);

        if (singleAttemptPrice == null) {
            logWarning("Failed to read single attempt price");
            return -1;
        }

        for (int i = 0; i < ATTEMPT_PRICES.length; i++) {
            if (ATTEMPT_PRICES[i] == singleAttemptPrice) {
                logDebug(String.format("Detected position %d (price: %d gems)", i, singleAttemptPrice));
                return i;
            }
        }

        logWarning(String.format("Unexpected attempt price: %d gems", singleAttemptPrice));
        return -1;
    }

    private int calculateRemainingAttemptsToBuy(int previousAttempts) {
        if (previousAttempts >= extraAttempts) {
            logInfo(String.format("Already have %d attempts (wanted %d), no need to buy more",
                    previousAttempts, extraAttempts));
            return 0;
        }

        int canBuy = ATTEMPT_PRICES.length - previousAttempts;
        int wantToBuy = extraAttempts - previousAttempts;
        int toBuy = Math.min(canBuy, wantToBuy);

        if (toBuy <= 0) {
            logWarning("Cannot purchase any more attempts (max limit reached)");
            return 0;
        }

        return toBuy;
    }

    private int calculateTotalPrice(int startPosition, int count) {
        int totalPrice = 0;
        for (int i = startPosition; i < startPosition + count; i++) {
            totalPrice += ATTEMPT_PRICES[i];
        }
        return totalPrice;
    }

    private boolean tryRefreshOpponentList() {
        if (freeRefreshCount >= MAX_FREE_REFRESHES) {
            logInfo(String.format("Free list refresh limit reached (%d/%d)",
                    freeRefreshCount, MAX_FREE_REFRESHES));
        } else {
            ImageSearchResultData freeRefreshResult = templateSearchHelper.locatePattern(
                    TemplatesEnum.ARENA_FREE_REFRESH_BUTTON,
                    TRANSIENT_BUTTON_SEARCH);

            if (freeRefreshResult.isFound()) {
                freeRefreshCount++;
                logInfo(String.format("Using free refresh (%d/%d)", freeRefreshCount, MAX_FREE_REFRESHES));
                tapInside(freeRefreshResult);
                sleepTask(1000);
                attemptedOpponentSlots.clear();
                StatisticsService.obtain().addToCounter(profile, "Arena Refreshes", 1);
                return true;
            }
        }

        if (!refreshWithGems) {
            logDebug("Paid arena list refresh disabled in configuration");
            return false;
        }

        if (gemRefreshCount >= MAX_GEM_REFRESHES) {
            logInfo(String.format("Gem list refresh limit reached (%d/%d)",
                    gemRefreshCount, MAX_GEM_REFRESHES));
            return false;
        }

        ImageSearchResultData gemsRefreshResult = templateSearchHelper.locatePattern(
                TemplatesEnum.ARENA_GEMS_REFRESH_BUTTON,
                TRANSIENT_BUTTON_SEARCH);

        if (!gemsRefreshResult.isFound()) {
            logDebug("Gem list refresh button not found");
            return false;
        }

        logInfo(String.format("Opening gem list refresh (%d/%d)", gemRefreshCount + 1, MAX_GEM_REFRESHES));

        tapInside(gemsRefreshResult);
        sleepTask(500);

        ImageSearchResultData confirmResult = templateSearchHelper.locatePattern(
                TemplatesEnum.ARENA_GEMS_REFRESH_CONFIRM_BUTTON,
                SearchConfig.builder().build());

        if (confirmResult.isFound()) {
            gemRefreshCount++;
            logInfo(String.format("Confirming gem list refresh (%d/%d)", gemRefreshCount, MAX_GEM_REFRESHES));
            tapInside(confirmResult);
            sleepTask(1000);
            attemptedOpponentSlots.clear();
            StatisticsService.obtain().addToCounter(profile, "Arena Refreshes", 1);
            return true;
        }

        gemRefreshCount++;
        logInfo(String.format(
                "Gem list refresh confirmation not shown; assuming refresh was accepted (%d/%d)",
                gemRefreshCount, MAX_GEM_REFRESHES));
        sleepTask(1000);
        attemptedOpponentSlots.clear();
        StatisticsService.obtain().addToCounter(profile, "Arena Refreshes", 1);
        return true;
    }

    private boolean isValidTimeFormat(String time) {
        if (time == null || time.trim().isEmpty()) {
            return false;
        }

        try {
            String[] parts = time.split(":");
            if (parts.length != 2) {
                return false;
            }

            int hours = Integer.parseInt(parts[0]);
            int minutes = Integer.parseInt(parts[1]);

            return hours >= 0 && hours <= 23 && minutes >= 0 && minutes <= 55;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void rescheduleWithActivationHour() {
        if (!isValidTimeFormat(activationTime)) {
            logInfo("Rescheduling Arena task for 5 min before game reset time (invalid activation time)");
            reschedule(GameTimeUtils.dailyResetTime().minusMinutes(5));
            return;
        }

        try {
            String[] timeParts = activationTime.split(":");
            int hour = Integer.parseInt(timeParts[0]);
            int minute = Integer.parseInt(timeParts[1]);

            ZonedDateTime nowUtc = ZonedDateTime.now(ZoneId.of("UTC"));
            ZonedDateTime tomorrowActivationUtc = nowUtc.toLocalDate()
                    .plusDays(1)
                    .atTime(hour, minute)
                    .atZone(ZoneId.of("UTC"));

            ZonedDateTime localActivationTime = tomorrowActivationUtc.withZoneSameInstant(
                    ZoneId.systemDefault());

            logInfo(String.format("Rescheduling Arena task for %s UTC tomorrow (%s local)",
                    activationTime,
                    localActivationTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));

            reschedule(localActivationTime.toLocalDateTime());

        } catch (Exception e) {
            logError("Failed to reschedule with activation time: " + e.getMessage());
            logInfo("Falling back to 5 min before game reset time");
            reschedule(GameTimeUtils.dailyResetTime().minusMinutes(5));
        }
    }

    private void rescheduleForToday() {
        if (!isValidTimeFormat(activationTime)) {
            logInfo("Rescheduling Arena task for 5 min before game reset time (invalid activation time)");
            reschedule(GameTimeUtils.dailyResetTime().minusMinutes(5));
            return;
        }

        try {
            String[] timeParts = activationTime.split(":");
            int hour = Integer.parseInt(timeParts[0]);
            int minute = Integer.parseInt(timeParts[1]);

            ZonedDateTime nowUtc = ZonedDateTime.now(ZoneId.of("UTC"));
            ZonedDateTime todayActivationUtc = nowUtc.toLocalDate()
                    .atTime(hour, minute)
                    .atZone(ZoneId.of("UTC"));

            ZonedDateTime localActivationTime = todayActivationUtc.withZoneSameInstant(
                    ZoneId.systemDefault());

            logInfo(String.format("Rescheduling Arena task for %s UTC today (%s local)",
                    activationTime,
                    localActivationTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));

            reschedule(localActivationTime.toLocalDateTime());

        } catch (Exception e) {
            logError("Failed to reschedule for today's activation time: " + e.getMessage());
            logInfo("Falling back to 5 min before game reset time");
            reschedule(GameTimeUtils.dailyResetTime().minusMinutes(5));
        }
    }

    private String normalizeAllianceTag(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeServer(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        return digits.isBlank() ? null : digits;
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    public boolean provideDailyMissionProgress() {
        return true;
    }

    private enum PowerRelation {
        WEAKER("WEAKER", ""),
        STRONGER("STRONGER", "stronger power"),
        UNKNOWN("UNKNOWN", "power unreadable");

        private final String label;
        private final String skipReason;

        PowerRelation(String label, String skipReason) {
            this.label = label;
            this.skipReason = skipReason;
        }
    }

    private enum ChallengeOutcome {
        VICTORY,
        DEFEAT,
        NO_ATTEMPTS
    }

    private enum OpponentLayout {
        SERVER_ROW("server", POWER_TEXT_SERVER_LAYOUT_Y_OFFSET, POWER_TEXT_SERVER_LAYOUT_HEIGHT, true),
        COMPACT("compact", POWER_TEXT_COMPACT_LAYOUT_Y_OFFSET, POWER_TEXT_COMPACT_LAYOUT_HEIGHT, false),
        UNKNOWN("unknown", POWER_TEXT_SERVER_LAYOUT_Y_OFFSET, POWER_TEXT_SERVER_LAYOUT_HEIGHT, true);

        private final String label;
        private final int powerYOffset;
        private final int powerHeight;
        private final boolean serverVisible;

        OpponentLayout(String label, int powerYOffset, int powerHeight, boolean serverVisible) {
            this.label = label;
            this.powerYOffset = powerYOffset;
            this.powerHeight = powerHeight;
            this.serverVisible = serverVisible;
        }

        private int powerYOffset() {
            return powerYOffset;
        }

        private int powerHeight() {
            return powerHeight;
        }

        private boolean serverVisible() {
            return serverVisible;
        }
    }

    private record PowerRead(PowerRelation relation, Double value, String rawText) {
        private static PowerRead withoutValue(PowerRelation relation) {
            return new PowerRead(relation, null, null);
        }

        private String logValue() {
            if (value == null) {
                return relation.label;
            }
            return String.format(Locale.ROOT, "%s(%.0f)", relation.label, value);
        }
    }

    private enum AllianceStatus {
        READ,
        MALFORMED,
        NO_ALLIANCE,
        GUARD,
        UNREADABLE,
        NOT_CHECKED
    }

    private enum ServerStatus {
        READ,
        NOT_SHOWN,
        UNREADABLE,
        NOT_CHECKED
    }

    private enum AlliancePolicy {
        ANY("Any alliance"),
        AVOID_PROFILE_ALLIANCE("Avoid profile alliance"),
        NEVER_PROFILE_ALLIANCE("Never attack profile alliance");

        private final String displayName;

        AlliancePolicy(String displayName) {
            this.displayName = displayName;
        }

        private static AlliancePolicy fromConfig(String value) {
            if (value == null || value.isBlank()) {
                return DEFAULT_ALLIANCE_POLICY;
            }
            for (AlliancePolicy policy : values()) {
                if (policy.displayName.equalsIgnoreCase(value.trim()) || policy.name().equalsIgnoreCase(value.trim())) {
                    return policy;
                }
            }
            return DEFAULT_ALLIANCE_POLICY;
        }

        private static boolean isDefaultConfig(String value) {
            return value == null
                    || value.isBlank()
                    || DEFAULT_ALLIANCE_POLICY.displayName.equalsIgnoreCase(value.trim())
                    || DEFAULT_ALLIANCE_POLICY.name().equalsIgnoreCase(value.trim());
        }

        private Comparator<OpponentCandidate> comparator(String profileAllianceTag) {
            return switch (this) {
                case AVOID_PROFILE_ALLIANCE -> Comparator
                        .comparingInt((OpponentCandidate candidate) -> candidate.allianceRisk(profileAllianceTag));
                default -> (left, right) -> 0;
            };
        }
    }

    private enum ServerPolicy {
        ANY("Any server"),
        PREFER_PROFILE_SERVER("Prefer profile server"),
        AVOID_PROFILE_SERVER("Avoid profile server"),
        NEVER_PROFILE_SERVER("Never attack profile server");

        private final String displayName;

        ServerPolicy(String displayName) {
            this.displayName = displayName;
        }

        private static ServerPolicy fromConfig(String value) {
            if (value == null || value.isBlank()) {
                return ANY;
            }
            for (ServerPolicy policy : values()) {
                if (policy.displayName.equalsIgnoreCase(value.trim()) || policy.name().equalsIgnoreCase(value.trim())) {
                    return policy;
                }
            }
            return ANY;
        }

        private Comparator<OpponentCandidate> comparator(String profileServer) {
            return switch (this) {
                case PREFER_PROFILE_SERVER -> Comparator
                        .comparing((OpponentCandidate candidate) -> !candidate.matchesServer(profileServer));
                case AVOID_PROFILE_SERVER -> Comparator
                        .comparing((OpponentCandidate candidate) -> candidate.matchesServer(profileServer));
                default -> (left, right) -> 0;
            };
        }
    }

    private record AllianceRead(String tag, AllianceStatus status, String detail) {
        private static AllianceRead notChecked() {
            return new AllianceRead(null, AllianceStatus.NOT_CHECKED, null);
        }

        private static AllianceRead read(String tag) {
            return new AllianceRead(tag, AllianceStatus.READ, null);
        }

        private static AllianceRead malformed(String tag, String detail) {
            return new AllianceRead(tag, AllianceStatus.MALFORMED, detail);
        }

        private static AllianceRead noAlliance() {
            return new AllianceRead(null, AllianceStatus.NO_ALLIANCE, null);
        }

        private static AllianceRead guard() {
            return new AllianceRead(null, AllianceStatus.GUARD, null);
        }

        private static AllianceRead unreadable(String detail) {
            return new AllianceRead(null, AllianceStatus.UNREADABLE, detail);
        }

        private String logValue() {
            return switch (status) {
                case READ -> tag;
                case MALFORMED -> tag + " (malformed" + (detail == null ? "" : ": " + detail) + ")";
                case NO_ALLIANCE -> "no alliance";
                case GUARD -> "guard";
                case UNREADABLE -> "unreadable" + (detail == null ? "" : ": " + detail);
                case NOT_CHECKED -> "not checked";
            };
        }
    }

    private record ServerRead(String value, ServerStatus status) {
        private static ServerRead notChecked() {
            return new ServerRead(null, ServerStatus.NOT_CHECKED);
        }

        private String logValue() {
            return switch (status) {
                case READ -> value;
                case NOT_SHOWN -> "not shown";
                case UNREADABLE -> "unreadable";
                case NOT_CHECKED -> "not checked";
            };
        }
    }

    private record OpponentCandidate(int number, int opponentY, PowerRead power, AllianceRead alliance,
                                     ServerRead server, boolean eligible, String decision) {
        private static OpponentCandidate eligible(int number, int opponentY, PowerRead power,
                                                  AllianceRead alliance, ServerRead server) {
            return new OpponentCandidate(number, opponentY, power, alliance, server, true, "eligible");
        }

        private static OpponentCandidate skipped(int number, int opponentY, PowerRead power,
                                                 AllianceRead alliance, ServerRead server, String reason) {
            return new OpponentCandidate(number, opponentY, power, alliance, server, false, "skip:" + reason);
        }

        private boolean matchesServer(String profileServer) {
            return profileServer != null && server.status == ServerStatus.READ && profileServer.equals(server.value);
        }

        private boolean matchesAlliance(String profileAllianceTag) {
            return profileAllianceTag != null
                    && (alliance.status == AllianceStatus.READ || alliance.status == AllianceStatus.MALFORMED)
                    && profileAllianceTag.equals(alliance.tag);
        }

        private int allianceRisk(String profileAllianceTag) {
            if (matchesAlliance(profileAllianceTag)) {
                return 2;
            }
            if (alliance.status == AllianceStatus.UNREADABLE || alliance.status == AllianceStatus.NOT_CHECKED) {
                return 1;
            }
            return 0;
        }

        private String selectionSummary() {
            return String.format("power=%s alliance=%s server=%s",
                    power.logValue(), alliance.logValue(), server.logValue());
        }
    }
}
