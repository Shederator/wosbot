package dev.frostguard.tasks.pets;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StaminaService;
import dev.frostguard.vision.color.PixelStats;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.RegexNumberParser;

/**
 * Unified Pet Skills task that processes all enabled pet skills in a single
 * execution.
 * 
 * <p>
 * This task:
 * <ul>
 * <li>Opens the Pets menu</li>
 * <li>Processes each enabled pet skill (Stamina, Food, Treasure,
 * Gathering)</li>
 * <li>Uses skills if available (off cooldown)</li>
 * <li>Reads cooldown timers for each skill</li>
 * <li>Reschedules to the earliest cooldown time among all skills</li>
 * </ul>
 * 
 * <p>
 * <b>Skill Types:</b>
 * <ul>
 * <li>Stamina: Adds stamina based on skill level (35 + (level-1)*5)</li>
 * <li>Food: Increases food production</li>
 * <li>Treasure: Provides resource rewards</li>
 * <li>Gathering: Increases gathering speed</li>
 * </ul>
 * 
 * <p>
 * <b>Execution Flow:</b>
 * <ol>
 * <li>Load configuration to determine which skills are enabled</li>
 * <li>Validate at least one skill is enabled</li>
 * <li>Open Pets menu (with retry logic)</li>
 * <li>For each enabled skill: tap icon, check state, use if available, read
 * cooldown</li>
 * <li>Reschedule to earliest cooldown or fallback time</li>
 * </ol>
 * 
 * <p>
 * <b>Rescheduling Logic:</b>
 * <ul>
 * <li>If any cooldown is successfully read: reschedule to earliest
 * cooldown</li>
 * <li>If all OCR fails: reschedule in 5 minutes as fallback</li>
 * <li>If no skills enabled: reschedule to game reset</li>
 * </ul>
 */
public class PetSkillsRoutine extends DelayedTask {

    // ========== Pet Skills Menu Coordinates ==========
    // Skill icon regions (for tapping on the pets menu screen)
    private static final PointData STAMINA_SKILL_TOP_LEFT = new PointData(240, 260);
    private static final PointData STAMINA_SKILL_BOTTOM_RIGHT = new PointData(320, 350);
    private static final PointData GATHERING_SKILL_TOP_LEFT = new PointData(380, 260);
    private static final PointData GATHERING_SKILL_BOTTOM_RIGHT = new PointData(460, 350);
    private static final PointData FOOD_SKILL_TOP_LEFT = new PointData(540, 260);
    private static final PointData FOOD_SKILL_BOTTOM_RIGHT = new PointData(620, 350);
    private static final PointData TREASURE_SKILL_TOP_LEFT = new PointData(240, 410);
    private static final PointData TREASURE_SKILL_BOTTOM_RIGHT = new PointData(320, 490);

    // ========== Skill Details UI (overlay on pets menu) ==========
    private static final AreaData TREASURE_COOLDOWN_OCR_AREA = new AreaData(
            new PointData(231, 428),
            new PointData(330, 470));
    static final AreaData GATHERING_COOLDOWN_OCR_AREA = new AreaData(
            new PointData(379, 282),
            new PointData(477, 338));
    private static final AreaData FOOD_COOLDOWN_OCR_AREA = new AreaData(
            new PointData(522, 288),
            new PointData(626, 318));
    static final AreaData STAMINA_COOLDOWN_OCR_AREA = new AreaData(
            new PointData(229, 285),
            new PointData(334, 320));
    private static final PointData SKILL_LEVEL_OCR_TOP_LEFT = new PointData(276, 779);
    private static final PointData SKILL_LEVEL_OCR_BOTTOM_RIGHT = new PointData(363, 811);

    // ========== Retry Constants ==========
    private static final int FALLBACK_RESCHEDULE_MINUTES = 5;
    private static final int SKILL_LEVEL_OCR_MAX_RETRIES = 3;
    private static final int OCR_RETRY_DELAY_MS = 200;
    private static final int GATHERING_ACTIVE_RECHECK_MINUTES = 1;
    private static final int GATHERING_MAX_LEVEL_ADJUSTMENTS = 20;
    private static final int GATHERING_SAME_TARGET_ATTEMPTS = 2;
    private static final Duration GATHERING_DEPLOYMENT_GUARD = Duration.ofMinutes(15);
    // Saved frame: learned tiles have more than 6,000 distinctive pixels; empty slots have none.
    private static final int MIN_SKILL_TILE_DISTINCTIVE_PIXELS = 400;

    // ========== Stamina Calculation Constants ==========
    private static final int STAMINA_BASE_VALUE = 35;
    private static final int STAMINA_PER_LEVEL = 5;
    private static final int STAMINA_FALLBACK_VALUE = 35; // Level 1 equivalent

    private static final OcrSettingsData COOLDOWN_OCR_SETTINGS = CommonOCRSettings.RED_DURATION_SETTINGS;

    private static final OcrSettingsData SKILL_LEVEL_OCR_SETTINGS = OcrSettingsData.assembler()
            .charWhitelist("0123456789")
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .stripBackground(true)
            .setTextColor(new Color(69, 88, 110))
            .build();

    private static final OcrSettingsData GATHERING_SKILL_OCR_SETTINGS = OcrSettingsData.assembler()
            .charWhitelist("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")
            .stripBackground(true)
            .setTextColor(new Color(0, 187, 0))
            .build();

    // ========== Configuration (loaded in loadConfiguration()) ==========
    private boolean staminaEnabled;
    private boolean foodEnabled;
    private boolean treasureEnabled;
    private boolean gatheringEnabled;

    // ========== Execution State (reset each execution) ==========
    private int navigationAttempts;
    private LocalDateTime earliestCooldown;

    /**
     * Constructs a new PetSkillsRoutine.
     *
     * @param profile the profile this task belongs to
     * @param tpTask  the task type enum
     */
    public PetSkillsRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    /**
     * Loads task configuration from the profile.
     * This must be called from execute() to ensure configuration is current.
     * 
     * <p>
     * Loads individual skill enable flags:
     * <ul>
     * <li>Stamina skill enabled/disabled</li>
     * <li>Food skill enabled/disabled</li>
     * <li>Treasure skill enabled/disabled</li>
     * <li>Gathering skill enabled/disabled</li>
     * </ul>
     * 
     * <p>
     * All flags default to false if not configured.
     */
    private void loadConfiguration() {
        this.staminaEnabled = getConfigBoolean(ConfigurationKeyEnum.PET_SKILL_STAMINA_BOOL, false);
        this.foodEnabled = getConfigBoolean(ConfigurationKeyEnum.PET_SKILL_FOOD_BOOL, false);
        this.treasureEnabled = getConfigBoolean(ConfigurationKeyEnum.PET_SKILL_TREASURE_BOOL, false);
        this.gatheringEnabled = getConfigBoolean(ConfigurationKeyEnum.PET_SKILL_GATHERING_BOOL, false);

        logDebug(String.format("Configuration loaded - Stamina: %s, Food: %s, Treasure: %s, Gathering: %s",
                staminaEnabled, foodEnabled, treasureEnabled, gatheringEnabled));
    }

    /**
     * Helper method to safely retrieve boolean configuration values.
     * 
     * @param key          the configuration key to retrieve
     * @param defaultValue the default value if configuration is not set
     * @return the configured boolean value or default if not set
     */
    private boolean getConfigBoolean(ConfigurationKeyEnum key, boolean defaultValue) {
        Boolean value = profile.getConfig(key, Boolean.class);
        return (value != null) ? value : defaultValue;
    }

    /**
     * Resets execution-specific state before each run.
     * 
     * <p>
     * Resets:
     * <ul>
     * <li>Navigation attempt counter</li>
     * <li>Earliest cooldown tracker</li>
     * </ul>
     */
    private void resetExecutionState() {
        this.navigationAttempts = 0;
        this.earliestCooldown = null;
        logDebug("Execution state reset");
    }

    /**
     * Main execution method for the Pet Skills task.
     * 
     * <p>
     * Flow:
     * <ol>
     * <li>Load current configuration</li>
     * <li>Reset execution state</li>
     * <li>Build list of enabled skills</li>
     * <li>Validate at least one skill is enabled</li>
     * <li>Open Pets menu</li>
     * <li>Process all enabled skills</li>
     * <li>Close Pets menu</li>
     * <li>Reschedule based on cooldowns</li>
     * </ol>
     * 
     * <p>
     * Rescheduling:
     * <ul>
     * <li>If no skills enabled: reschedule to game reset</li>
     * <li>If menu open fails: reschedule in 5 minutes</li>
     * <li>If cooldowns read: reschedule to earliest</li>
     * <li>If no cooldowns read: reschedule in 5 minutes</li>
     * </ul>
     */
    @Override
    protected void execute() {
        loadConfiguration();
        resetExecutionState();

        List<PetSkill> enabledSkills = buildEnabledSkillsList();

        if (enabledSkills.isEmpty()) {
            handleNoSkillsEnabled();
            return;
        }

        logInfo(String.format("Pet Skills for %d skill(s).", enabledSkills.size()));

        if (!openPetsMenu()) {
            handleMenuOpenFailure();
            return;
        }

        processAllSkills(enabledSkills);
        closePetsMenu();
        finalizeRescheduling();
    }

    /**
     * Handles the case where no pet skills are enabled.
     * Reschedules the task to retry at game reset.
     */
    private void handleNoSkillsEnabled() {
        logInfo("No pet skills enabled. Rescheduling to retry at reset.");
        reschedule(GameTimeUtils.dailyResetTime());
    }

    /**
     * Handles failure to open the Pets menu.
     * Reschedules the task to retry in a few minutes.
     */
    private void handleMenuOpenFailure() {
        logWarning("Pets feature is unavailable on the confirmed Home screen. Retrying after game reset.");
        reschedule(GameTimeUtils.dailyResetTime());
    }

    /**
     * Builds a list of enabled pet skills based on current configuration.
     * 
     * @return list of PetSkill enums that are enabled, may be empty
     */
    private List<PetSkill> buildEnabledSkillsList() {
        List<PetSkill> skills = new ArrayList<>();

        if (staminaEnabled) {
            skills.add(PetSkill.STAMINA);
        }
        if (foodEnabled) {
            skills.add(PetSkill.FOOD);
        }
        if (treasureEnabled) {
            skills.add(PetSkill.TREASURE);
        }
        if (gatheringEnabled) {
            skills.add(PetSkill.GATHERING);
        }

        logDebug("Enabled skills: " + skills);
        return skills;
    }

    /**
     * Opens the Pets menu by searching for and tapping the Pets button.
     * 
     * <p>
     * Includes retry logic up to MAX_NAVIGATION_ATTEMPTS.
     * 
     * @return true if menu opened successfully, false after max retries
     */
    private boolean openPetsMenu() {
        logDebug("Opening Pets menu");
        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);

        ImageSearchResultData petsButton = templateSearchHelper.locatePattern(
                TemplatesEnum.GAME_HOME_PETS,
                SearchConfigConstants.DEFAULT_SINGLE);

        if (!petsButton.isFound()) {
            navigationAttempts++;

            if (navigationAttempts >= 3) { // Max navigation attempts
                logError("Could not find Pets menu after 3 attempts.");
                return false;
            }

            logWarning("Pets button not found (attempt " + navigationAttempts + "/3).");
            return false;
        }

        logInfo("Pets button found. Opening menu.");
        tapInside(petsButton);
        sleepTask(1000); // Wait for Pets menu to load

        return true;
    }

    /**
     * Processes all enabled pet skills sequentially.
     * 
     * <p>
     * For each skill:
     * <ul>
     * <li>Taps skill icon to show details overlay</li>
     * <li>Checks if skill is learned and unlocked</li>
     * <li>Uses skill if available</li>
     * <li>Reads and tracks cooldown timer</li>
     * </ul>
     * 
     * @param enabledSkills list of skills to process
     */
    private void processAllSkills(List<PetSkill> enabledSkills) {
        for (PetSkill skill : enabledSkills) {
            logInfo("Processing " + skill.name() + " skill.");
            processSkill(skill);
        }
    }

    /**
     * Processes a single pet skill.
     * 
     * <p>
     * Flow:
     * <ol>
     * <li>Taps skill icon to show details overlay</li>
     * <li>Checks if skill is learned (returns early if not)</li>
     * <li>Checks if skill is locked (returns early if locked)</li>
     * <li>For gathering skill: checks if already Active and proceeds with
     * deployment</li>
     * <li>Attempts to use skill if Use button is visible</li>
     * <li>Reads cooldown timer and tracks earliest cooldown</li>
     * </ol>
     * 
     * <p>
     * Note: All skill details appear as overlays on the same pets menu screen.
     * No explicit navigation back is needed between skills.
     * 
     * @param skill the skill to process
     */
    private void processSkill(PetSkill skill) {
        if (!isSkillPresent(skill)) {
            logInfo(skill.name() + " skill is not available. Skipping.");
            return;
        }

        tapSkillIcon(skill);

        if (!isSkillLearned(skill)) {
            return;
        }

        if (isSkillLocked(skill)) {
            return;
        }

        if (skill == PetSkill.GATHERING) {
            processGatheringSkill();
            return;
        }

        boolean skillUsed = tryUseSkill(skill);
        if (skillUsed) {
            logInfo(skill.name() + " skill used successfully.");
        } else {
            logDebug(skill.name() + " skill is on cooldown.");
        }

        readAndTrackCooldown(skill);
    }

    private void processGatheringSkill() {
        if (isGatheringSkillActive()) {
            if (hasRecentGatheringDeployment()) {
                logInfo("Gathering skill is still Active after its bonus march. Waiting for cooldown state.");
                scheduleGatheringActiveRecheck();
                return;
            }

            logInfo("Gathering skill is Active without a recent bonus march. Retrying deployment.");
            handleGatheringDeployment(deployGatheringSkillMarch());
            return;
        }

        boolean skillUsed = tryUseSkill(PetSkill.GATHERING);
        if (skillUsed) {
            logInfo("GATHERING skill used successfully.");
            handleGatheringDeployment(deployGatheringSkillMarch());
            return;
        }

        clearGatheringDeploymentMarker();
        logDebug("GATHERING skill is on cooldown.");
        readAndTrackCooldown(PetSkill.GATHERING);
    }

    private void handleGatheringDeployment(GatheringDeployResult result) {
        if (result == GatheringDeployResult.DEPLOYED) {
            recordGatheringDeployment();
            logInfo("Gathering bonus march deployed. Waiting for the Active state to clear.");
        } else {
            logWarning("Gathering bonus march not deployed: " + result.logValue + ". Retrying while the skill is Active.");
        }
        scheduleGatheringActiveRecheck();
    }

    private void scheduleGatheringActiveRecheck() {
        updateEarliestCooldown(LocalDateTime.now().plusMinutes(GATHERING_ACTIVE_RECHECK_MINUTES));
    }

    private boolean hasRecentGatheringDeployment() {
        String value = profile.getConfig(
                ConfigurationKeyEnum.PET_SKILL_GATHERING_LAST_DEPLOYED_AT_STRING,
                String.class);
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return isRecentGatheringDeployment(LocalDateTime.parse(value), LocalDateTime.now());
        } catch (RuntimeException ex) {
            logWarning("Ignoring malformed Gathering deployment marker: " + value);
            clearGatheringDeploymentMarker();
            return false;
        }
    }

    static boolean isRecentGatheringDeployment(LocalDateTime deployedAt, LocalDateTime now) {
        return deployedAt != null
                && now != null
                && !deployedAt.isAfter(now)
                && deployedAt.plus(GATHERING_DEPLOYMENT_GUARD).isAfter(now);
    }

    private void recordGatheringDeployment() {
        profile.setConfig(
                ConfigurationKeyEnum.PET_SKILL_GATHERING_LAST_DEPLOYED_AT_STRING,
                LocalDateTime.now().toString());
        shouldUpdateConfig = true;
    }

    private void clearGatheringDeploymentMarker() {
        String value = profile.getConfig(
                ConfigurationKeyEnum.PET_SKILL_GATHERING_LAST_DEPLOYED_AT_STRING,
                String.class);
        if (value != null && !value.isBlank()) {
            profile.setConfig(ConfigurationKeyEnum.PET_SKILL_GATHERING_LAST_DEPLOYED_AT_STRING, "");
            shouldUpdateConfig = true;
        }
    }

    private boolean isSkillPresent(PetSkill skill) {
        try {
            BufferedImage image = dev.frostguard.vision.convert.ImageConverter.toBufferedImage(
                    emuManager.captureScreen(EMULATOR_NUMBER));
            return hasDistinctiveSkillTilePixels(image, skill.area);
        } catch (Exception ex) {
            logWarning("Could not verify " + skill.name() + " skill tile. Continuing conservatively.");
            return true;
        }
    }

    static boolean hasDistinctiveSkillTilePixels(BufferedImage image, AreaData area) {
        int distinctivePixels = PixelStats.count(image, area, rgb -> {
            int red = (rgb >> 16) & 0xFF;
            int green = (rgb >> 8) & 0xFF;
            int blue = rgb & 0xFF;
            int brightest = Math.max(red, Math.max(green, blue));
            int darkest = Math.min(red, Math.min(green, blue));
            return brightest < 175 || brightest - darkest >= 45;
        });
        return distinctivePixels >= MIN_SKILL_TILE_DISTINCTIVE_PIXELS;
    }

    /**
     * Taps the skill icon to display its details overlay.
     * 
     * @param skill the skill whose icon to tap
     */
    private void tapSkillIcon(PetSkill skill) {
        tapInside(skill.getTopLeft(), skill.getBottomRight());
        sleepTask(300); // Wait for details overlay to appear
    }

    /**
     * Checks if a skill is learned by looking for the info/skills indicator.
     * 
     * @param skill the skill to check
     * @return true if skill is learned, false otherwise
     */
    private boolean isSkillLearned(PetSkill skill) {
        ImageSearchResultData infoSkill = templateSearchHelper.locatePattern(
                TemplatesEnum.PETS_INFO_SKILLS,
                SearchConfigConstants.QUICK_SEARCH);

        if (!infoSkill.isFound()) {
            logInfo(skill.name() + " skill not learned yet. Skipping.");
            return false;
        }

        return true;
    }

    /**
     * Checks if a skill is locked (requires unlocking).
     * 
     * @param skill the skill to check
     * @return true if skill is locked, false if unlocked
     */
    private boolean isSkillLocked(PetSkill skill) {
        ImageSearchResultData unlockText = templateSearchHelper.locatePattern(
                TemplatesEnum.PETS_UNLOCK_TEXT,
                SearchConfigConstants.QUICK_SEARCH);

        if (unlockText.isFound()) {
            logInfo(skill.name() + " skill is locked. Skipping.");
            return true;
        }

        return false;
    }

    /**
     * Attempts to use a skill if the Use button is visible.
     * 
     * <p>
     * If the Use button is not found, the skill is assumed to be on cooldown.
     * 
     * <p>
     * Special handling for Stamina skill: adds stamina to profile after use.
     * 
     * @param skill the skill to use
     * @return true if skill was used, false if on cooldown
     */
    private boolean tryUseSkill(PetSkill skill) {
        ImageSearchResultData useButton = templateSearchHelper.locatePattern(
                TemplatesEnum.PETS_SKILL_USE,
                SearchConfigConstants.QUICK_SEARCH);

        if (!useButton.isFound()) {
            return false;
        }

        logDebug("Use button found. Using skill.");
        tapInside(
                useButton.getPoint(),
                useButton.getPoint(),
                3, // Number of taps
                100); // Delay between taps in ms

        sleepTask(1500); // Wait for skill use animation

        if (skill == PetSkill.STAMINA) {
            addStaminaBySkillLevel();
        }

        return true;
    }

    /**
     * Reads the cooldown timer for a skill and tracks the earliest cooldown.
     * 
     * <p>
     * Uses OCR to read the cooldown display and convert to Duration.
     * Updates the earliestCooldown field if this cooldown is sooner.
     * 
     * <p>
     * If OCR fails, logs a warning and continues without updating cooldown.
     * 
     * @param skill the skill whose cooldown to read
     */
    private void readAndTrackCooldown(PetSkill skill) {
        Duration cooldownDuration;

        switch (skill) {
            case STAMINA:
                cooldownDuration = readSkillCooldown(STAMINA_COOLDOWN_OCR_AREA);
                break;

            case FOOD:
                cooldownDuration = readSkillCooldown(FOOD_COOLDOWN_OCR_AREA);
                break;

            case TREASURE:
                cooldownDuration = readSkillCooldown(TREASURE_COOLDOWN_OCR_AREA);
                break;

            case GATHERING:
                cooldownDuration = readSkillCooldown(
                        GATHERING_COOLDOWN_OCR_AREA,
                        CommonOCRSettings.RED_MULTILINE_DURATION_SETTINGS);
                break;

            default:
                cooldownDuration = null;
        }

        if (cooldownDuration == null) {
            logWarning("Failed to read cooldown for " + skill.name() + ". Using 5 minute fallback cooldown.");
            cooldownDuration = Duration.ofMinutes(5);
        }

        LocalDateTime cooldownEnd = LocalDateTime.now().plus(cooldownDuration);

        logInfo(String.format("%s skill cooldown until: %s (in %s)",
                skill.name(),
                cooldownEnd.format(DATETIME_FORMATTER),
                GameTimeUtils.formatCountdown(cooldownEnd)));

        updateEarliestCooldown(cooldownEnd);
    }

    /**
     * Reads the cooldown duration from the UI using OCR for a specific skill area.
     * 
     * @param area The area containing the cooldown text
     * @return Duration representing the cooldown time, or null if OCR fails
     */
    private Duration readSkillCooldown(AreaData area) {
        return readSkillCooldown(area, COOLDOWN_OCR_SETTINGS);
    }

    private Duration readSkillCooldown(AreaData area, OcrSettingsData settings) {
        return durationHelper.attemptRecognition(
                area.topLeft(),
                area.bottomRight(),
                5, // Max retries
                200L, // Retry delay in ms
                settings,
                GameTimeUtils::isAcceptedFormat,
                GameTimeUtils::parseDuration);
    }

    /**
     * Checks if the gathering skill is currently in "Active" state.
     * 
     * <p>
     * The gathering skill displays green "Active" text when it's currently deployed
     * in the gathering process. This method uses OCR to detect this state.
     * 
     * @return true if gathering skill shows "Active", false otherwise
     */
    private boolean isGatheringSkillActive() {
        String text = stringHelper.attemptRecognition(
                GATHERING_COOLDOWN_OCR_AREA.topLeft(),
                GATHERING_COOLDOWN_OCR_AREA.bottomRight(),
                3, // Max retries
                200L, // Retry delay in ms
                GATHERING_SKILL_OCR_SETTINGS,
                s -> !s.isEmpty(), // Accept any text
                s -> s.trim().toUpperCase()); // Return raw text

        if (text == null || text.isEmpty()) {
            logDebug("Could not read gathering skill status - OCR returned null or empty");
            return false;
        }

        boolean isActive = text.contains("ACTIVE");

        if (isActive) {
            logDebug("Gathering skill is Active: " + text);
        }

        return isActive;
    }

    /**
     * Updates the earliest cooldown tracker if the provided cooldown is sooner.
     * 
     * @param cooldownEnd the cooldown end time to compare
     */
    private void updateEarliestCooldown(LocalDateTime cooldownEnd) {
        if (earliestCooldown == null || cooldownEnd.isBefore(earliestCooldown)) {
            earliestCooldown = cooldownEnd;
            logDebug("Updated earliest cooldown: " + earliestCooldown.format(DATETIME_FORMATTER));
        }
    }

    /**
     * Adds stamina based on the skill level displayed in the UI.
     * 
     * <p>
     * Formula: 35 + (level - 1) * 5
     * 
     * <p>
     * If OCR fails to read the skill level, uses a fallback value of 35 (level 1
     * equivalent).
     */
    private void addStaminaBySkillLevel() {
        Integer level = readSkillLevel();

        int staminaToAdd;
        if (level == null) {
            staminaToAdd = STAMINA_FALLBACK_VALUE;
            logWarning("Failed to read Stamina skill level. Using fallback value: " + staminaToAdd);
        } else {
            staminaToAdd = calculateStaminaForLevel(level);
            logInfo(String.format("Stamina skill level: %d. Added %d stamina.", level, staminaToAdd));
        }

        StaminaService.getServices().addExternalStamina(profile.getId(), staminaToAdd);
    }

    /**
     * Reads the skill level from the UI using OCR.
     * 
     * @return the skill level as an Integer, or null if OCR fails
     */
    private Integer readSkillLevel() {
        return integerHelper.attemptRecognition(
                SKILL_LEVEL_OCR_TOP_LEFT,
                SKILL_LEVEL_OCR_BOTTOM_RIGHT,
                SKILL_LEVEL_OCR_MAX_RETRIES,
                OCR_RETRY_DELAY_MS,
                SKILL_LEVEL_OCR_SETTINGS,
                text -> RegexNumberParser.conformsTo(text, Pattern.compile(".*?(\\d+).*")),
                text -> RegexNumberParser.extractByPattern(text, Pattern.compile(".*?(\\d+).*")));
    }

    /**
     * Calculates stamina amount for a given skill level.
     * 
     * <p>
     * Formula: 35 + (level - 1) * 5
     * 
     * @param level the skill level (must be >= 1)
     * @return the stamina amount for that level
     */
    private int calculateStaminaForLevel(int level) {
        return STAMINA_BASE_VALUE + (level - 1) * STAMINA_PER_LEVEL;
    }

    /**
     * Closes the Pets menu by tapping the back button.
     */
    private void closePetsMenu() {
        logDebug("Closing Pets menu");
        pressBack();
        sleepTask(500); // Wait for menu to close
    }

    /**
     * Finalizes rescheduling based on earliest cooldown among all skills.
     * 
     * <p>
     * Rescheduling logic:
     * <ul>
     * <li>If any cooldown was successfully read: reschedule to earliest
     * cooldown</li>
     * <li>If all OCR failed: reschedule in FALLBACK_RESCHEDULE_MINUTES as
     * fallback</li>
     * </ul>
     */
    private void finalizeRescheduling() {
        if (earliestCooldown != null) {
            logInfo("Rescheduling Pet Skills task for: " +
                    earliestCooldown.format(DATETIME_FORMATTER));
            reschedule(earliestCooldown);
        } else {
            logWarning("No cooldown parsed for any enabled skill. Rescheduling in " +
                    FALLBACK_RESCHEDULE_MINUTES + " minutes.");
            reschedule(LocalDateTime.now().plusMinutes(FALLBACK_RESCHEDULE_MINUTES));
        }
    }

    /**
     * Specifies that this task can start from any screen location.
     * The task will handle navigation to the pets menu internally.
     * 
     * @return LaunchPoint.ANY
     */
    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.ANY;
    }

    private GatheringDeployResult deployGatheringSkillMarch() {
        logInfo("Deploying gathering skill march...");

        GatheringDeployResult result = GatheringDeployResult.BLOCKED;
        try {
            pressBack();
            sleepTask(500);
            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);
            sleepTask(500);

            if (!marchHelper.checkMarchesAvailable()) {
                return GatheringDeployResult.NO_IDLE_MARCH;
            }

            logInfo("Idle march found, proceeding with deployment");
            sleepTask(500);

            GatheringResourceType resourceType = getConfiguredGatheringResource();
            for (int attempt = 1; attempt <= GATHERING_SAME_TARGET_ATTEMPTS; attempt++) {
                if (!openResourceSearchMenu()) {
                    return GatheringDeployResult.BLOCKED;
                }
                result = deployGatheringMarch(resourceType);
                if (result != GatheringDeployResult.SAME_TARGET) {
                    return result;
                }
                if (attempt < GATHERING_SAME_TARGET_ATTEMPTS) {
                    logInfo(String.format(
                            "Gathering target already has an incoming march. Searching another node (%d/%d).",
                            attempt,
                            GATHERING_SAME_TARGET_ATTEMPTS));
                }
            }
            return result;
        } catch (Exception e) {
            logWarning("Error deploying gathering skill march: " + e.getMessage());
            return GatheringDeployResult.BLOCKED;
        } finally {
            reopenPetsMenuAfterGatheringDeployment();
        }
    }

    private void reopenPetsMenuAfterGatheringDeployment() {
        try {
            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);
            logDebug("Reopening pets menu after gathering deployment attempt");
            if (!openPetsMenu()) {
                logWarning("Could not reopen Pets menu after gathering deployment attempt.");
            }
        } catch (RuntimeException ex) {
            logWarning("Could not restore Pets menu after gathering deployment attempt: " + ex.getMessage());
        }
    }

    /**
     * Gets the configured resource type for gathering skill.
     * 
     * @return the resource type to gather, defaults to MEAT
     */
    private GatheringResourceType getConfiguredGatheringResource() {
        String resourceConfig = profile.getConfig(
                ConfigurationKeyEnum.PET_SKILL_GATHERING_RESOURCE_STRING,
                String.class);

        if (resourceConfig == null) {
            return GatheringResourceType.MEAT;
        }

        try {
            return GatheringResourceType.valueOf(resourceConfig.toUpperCase());
        } catch (IllegalArgumentException e) {
            logWarning("Invalid gathering resource configuration: " + resourceConfig + ", using MEAT");
            return GatheringResourceType.MEAT;
        }
    }

    /**
     * Opens the resource search menu by tapping the search button area.
     * 
     * @return true if menu opened successfully, false otherwise
     */
    private boolean openResourceSearchMenu() {
        logDebug("Opening resource search menu");

        tapInside(new PointData(25, 850), new PointData(67, 898));
        sleepTask(2000); // Wait for search menu to open

        // Swipe left to find resource tiles tab
        swipe(new PointData(678, 913), new PointData(40, 913));
        sleepTask(500); // Wait for swipe animation

        return true;
    }

    /**
     * Deploys a gathering march for the specified resource type.
     * 
     * <p>
     * Flow:
     * <ul>
     * <li>Select resource tile by searching for and tapping the tile template</li>
     * <li>Set level to highest available (with OCR-based adjustment)</li>
     * <li>Execute search</li>
     * <li>Find and tap gather button on map</li>
     * <li>Deploy march</li>
     * </ul>
     * 
     * @param resourceType the resource to gather
     * @return the verified deployment result
     */
    private GatheringDeployResult deployGatheringMarch(GatheringResourceType resourceType) {
        logInfo("Deploying gathering march for: " + resourceType.name());

        try {
            if (!selectResourceTile(resourceType)) {
                logWarning("Failed to select resource tile");
                return GatheringDeployResult.BLOCKED;
            }
            sleepTask(500);

            Integer selectedLevel = selectHighestAvailableLevel();
            if (selectedLevel == null) {
                logWarning("Failed to set resource level");
                return GatheringDeployResult.BLOCKED;
            }
            sleepTask(500);

            if (!executeResourceSearch()) {
                logWarning("Failed to execute resource search");
                return GatheringDeployResult.BLOCKED;
            }
            sleepTask(500);

            ImageSearchResultData gatherButton = templateSearchHelper.locatePattern(
                    TemplatesEnum.GAME_HOME_SHORTCUTS_FARM_GATHER,
                    SearchConfigConstants.SINGLE_WITH_RETRIES);

            if (!gatherButton.isFound()) {
                logWarning(String.format("No %s node found at highest available level %d.",
                        resourceType.name(), selectedLevel));
                return GatheringDeployResult.NO_NODE;
            }

            logDebug("Tapping gather button");
            tapInside(gatherButton);
            sleepTask(1000);

            if (deploymentHelper.hasNoDeployableTroops()) {
                pressBack();
                return GatheringDeployResult.NO_TROOPS;
            }

            ImageSearchResultData deployButton = templateSearchHelper.locatePattern(
                    TemplatesEnum.GATHER_DEPLOY_BUTTON,
                    SearchConfigConstants.SINGLE_WITH_RETRIES);

            if (!deployButton.isFound()) {
                GatheringDeployResult missingDeployResult = deploymentHelper.hasNoDeployableTroops()
                        ? GatheringDeployResult.NO_TROOPS
                        : GatheringDeployResult.BLOCKED;
                logWarning("Gather deploy button not found: " + missingDeployResult.logValue);
                pressBack();
                return missingDeployResult;
            }

            logInfo("Deploying gather march");
            tapInside(deployButton);
            sleepTask(1000);

            if (deploymentHelper.isMarchQueueFull()) {
                pressBack();
                return GatheringDeployResult.MARCH_QUEUE_FULL;
            }

            if (deploymentHelper.isSameTargetDialog()) {
                pressBack();
                pressBack();
                return GatheringDeployResult.SAME_TARGET;
            }

            ImageSearchResultData deployStillVisible = templateSearchHelper.locatePattern(
                    TemplatesEnum.GATHER_DEPLOY_BUTTON,
                    SearchConfigConstants.QUICK_SEARCH);
            if (deployStillVisible.isFound()) {
                logWarning("Gather deploy button remained visible after pressing it.");
                pressBack();
                return GatheringDeployResult.BLOCKED;
            }

            logInfo(String.format("%s level %d bonus march deployed successfully.",
                    resourceType.name(), selectedLevel));
            return GatheringDeployResult.DEPLOYED;

        } catch (Exception e) {
            logWarning("Error deploying gathering march: " + e.getMessage());
            return GatheringDeployResult.BLOCKED;
        }
    }

    /**
     * Selects the resource tile by searching for and tapping the tile template.
     * Swipes through resource tabs until the tile is found.
     * 
     * @param resourceType the resource to select
     * @return true if tile was found and selected, false otherwise
     */
    private boolean selectResourceTile(GatheringResourceType resourceType) {
        logDebug(String.format("Searching for %s tile", resourceType.name()));

        final int MAX_SWIPE_ATTEMPTS = 4;

        for (int attempt = 0; attempt < MAX_SWIPE_ATTEMPTS; attempt++) {
            // Get the appropriate tile template based on resource type
            TemplatesEnum tileTemplate = getResourceTileTemplate(resourceType);

            ImageSearchResultData tile = templateSearchHelper.locatePattern(
                    tileTemplate,
                    SearchConfigConstants.SINGLE_WITH_RETRIES);

            if (tile.isFound()) {
                logInfo(String.format("%s tile found", resourceType.name()));
                tapInside(tile);
                sleepTask(500); // Wait for tile selection
                return true;
            }

            if (attempt < MAX_SWIPE_ATTEMPTS - 1) {
                logDebug(String.format("Tile not found, swiping (attempt %d/%d)",
                        attempt + 1, MAX_SWIPE_ATTEMPTS));
                swipe(new PointData(678, 913), new PointData(40, 913));
                sleepTask(500); // Wait for swipe animation
            }
        }

        logError(String.format("%s tile not found after %d attempts",
                resourceType.name(), MAX_SWIPE_ATTEMPTS));
        return false;
    }

    /**
     * Gets the tile template for a resource type.
     * 
     * @param resourceType the resource type
     * @return the appropriate TemplatesEnum tile template
     */
    private TemplatesEnum getResourceTileTemplate(GatheringResourceType resourceType) {
        switch (resourceType) {
            case MEAT:
                return TemplatesEnum.GAME_HOME_SHORTCUTS_FARM_MEAT;
            case WOOD:
                return TemplatesEnum.GAME_HOME_SHORTCUTS_FARM_WOOD;
            case COAL:
                return TemplatesEnum.GAME_HOME_SHORTCUTS_FARM_COAL;
            case IRON:
                return TemplatesEnum.GAME_HOME_SHORTCUTS_FARM_IRON;
            default:
                return TemplatesEnum.GAME_HOME_SHORTCUTS_FARM_MEAT;
        }
    }

    /**
     * Sets the resource level to the highest available.
     * 
     * <p>
     * Reads the current level and increments one step at a time until the UI stops
     * advancing. The game UI therefore remains authoritative when State Age changes
     * the highest unlocked resource level.
     * 
     * @return the verified highest available level, or null if it could not be read
     */
    private Integer selectHighestAvailableLevel() {
        logInfo("Selecting highest available resource level");

        final PointData LEVEL_INCREMENT_BUTTON_TOP_LEFT = new PointData(470, 1040);
        final PointData LEVEL_INCREMENT_BUTTON_BOTTOM_RIGHT = new PointData(500, 1066);
        final PointData LEVEL_LOCK_BUTTON = new PointData(183, 1140);
        Integer currentLevel = readCurrentGatheringLevel();

        if (currentLevel == null) {
            logDebug("Initial level OCR failed. Resetting selector to level 1.");
            resetLevelToOne();
            currentLevel = readCurrentGatheringLevel();
            if (currentLevel == null) {
                return null;
            }
        }

        for (int attempt = 0; attempt < GATHERING_MAX_LEVEL_ADJUSTMENTS; attempt++) {
            tapInside(LEVEL_INCREMENT_BUTTON_TOP_LEFT, LEVEL_INCREMENT_BUTTON_BOTTOM_RIGHT);
            sleepTask(200);

            Integer nextLevel = readCurrentGatheringLevel();
            if (nextLevel == null) {
                logWarning("Could not verify resource level after incrementing.");
                return null;
            }
            if (nextLevel <= currentLevel) {
                ensureLevelLocked(LEVEL_LOCK_BUTTON);
                logInfo("Highest available resource level: " + currentLevel);
                return currentLevel;
            }
            currentLevel = nextLevel;
        }

        logWarning("Resource level kept increasing past the bounded adjustment limit.");
        return null;
    }

    /**
     * Reads the current gathering level from the display via OCR.
     * 
     * @return the current level as an Integer, or null if OCR fails
     */
    private Integer readCurrentGatheringLevel() {
        AreaData levelArea = new AreaData(
                new PointData(78, 991),
                new PointData(474, 1028));

        OcrSettingsData configs = OcrSettingsData.assembler()
                .charWhitelist("0123456789")
                .stripBackground(true)
                .setTextColor(new Color(255, 255, 255))
                .build();

        Integer level = integerHelper.attemptRecognition(
                levelArea.topLeft(),
                levelArea.bottomRight(),
                3, // Max retries
                200L, // Retry delay
                configs,
                text -> RegexNumberParser.conformsTo(text, Pattern.compile(".*?(\\d+).*")),
                text -> RegexNumberParser.extractByPattern(text, Pattern.compile(".*?(\\d+).*")));

        if (level != null) {
            logDebug("Current level detected: " + level);
        } else {
            logWarning("Failed to read current level via OCR");
        }

        return level;
    }

    /**
     * Resets the level slider to level 1.
     */
    private void resetLevelToOne() {
        logDebug("Resetting level slider to 1");
        swipe(new PointData(40, 1052), new PointData(435, 1052));
        sleepTask(300); // Wait for slider animation
    }

    /**
     * Ensures the level lock checkbox is checked.
     * 
     * @param levelLockButton the point of the lock button
     */
    private void ensureLevelLocked(PointData levelLockButton) {
        ImageSearchResultData tick = templateSearchHelper.locatePattern(
                TemplatesEnum.GAME_HOME_SHORTCUTS_FARM_TICK,
                SearchConfigConstants.SINGLE_WITH_RETRIES);

        if (!tick.isFound()) {
            logDebug("Level not locked, tapping lock button");
            tapNear(levelLockButton);
            sleepTask(300); // Wait for checkbox animation
        }
    }

    /**
     * Executes the resource search.
     * 
     * @return true if search executed successfully, false otherwise
     */
    private boolean executeResourceSearch() {
        logInfo("Executing resource search");

        tapInside(new PointData(301, 1200), new PointData(412, 1229));
        sleepTask(3000); // Wait for search to complete and map to load

        return true;
    }

    private enum GatheringDeployResult {
        DEPLOYED("deployed"),
        NO_IDLE_MARCH("no idle march slot"),
        NO_TROOPS("no deployable troops"),
        NO_NODE("no node found"),
        SAME_TARGET("target already has an incoming march"),
        MARCH_QUEUE_FULL("march queue became full"),
        BLOCKED("deployment screen was blocked or could not be verified");

        private final String logValue;

        GatheringDeployResult(String logValue) {
            this.logValue = logValue;
        }
    }

    /**
     * Enum representing gathering resource types for the pet skill.
     */
    public enum GatheringResourceType {
        MEAT(0),
        WOOD(1),
        COAL(2),
        IRON(3);

        private final int tabIndex;

        GatheringResourceType(int tabIndex) {
            this.tabIndex = tabIndex;
        }

        public int getTabIndex() {
            return tabIndex;
        }
    }

    /**
     * Enum representing the four pet skills with their screen coordinates.
     * 
     * <p>
     * Each skill has a defined region on the pets menu screen that can be tapped
     * to display the skill's details overlay.
     */
    public enum PetSkill {
        /** Stamina skill - adds stamina to the profile */
        STAMINA(STAMINA_SKILL_TOP_LEFT, STAMINA_SKILL_BOTTOM_RIGHT),

        /** Gathering skill - increases gathering speed */
        GATHERING(GATHERING_SKILL_TOP_LEFT, GATHERING_SKILL_BOTTOM_RIGHT),

        /** Food skill - increases food production */
        FOOD(FOOD_SKILL_TOP_LEFT, FOOD_SKILL_BOTTOM_RIGHT),

        /** Treasure skill - provides resource rewards */
        TREASURE(TREASURE_SKILL_TOP_LEFT, TREASURE_SKILL_BOTTOM_RIGHT);

        private final AreaData area;

        /**
         * Constructs a PetSkill with screen coordinates.
         * 
         * @param topLeft     top-left corner of the skill icon region
         * @param bottomRight bottom-right corner of the skill icon region
         */
        PetSkill(PointData topLeft, PointData bottomRight) {
            this.area = new AreaData(topLeft, bottomRight);
        }

        /**
         * Gets the top-left corner of the skill icon region.
         * 
         * @return PointData representing top-left coordinate
         */
        public PointData getTopLeft() {
            return area.topLeft();
        }

        /**
         * Gets the bottom-right corner of the skill icon region.
         * 
         * @return PointData representing bottom-right coordinate
         */
        public PointData getBottomRight() {
            return area.bottomRight();
        }
    }
}
