package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.color.PixelStats;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.logging.ProfileContextLogger;
import dev.frostguard.vision.convert.RegexNumberParser;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;
import dev.frostguard.vision.ocr.TesseractOcrProvider;

import java.awt.image.BufferedImage;
import java.time.Duration;

/**
 * Reads the deployment screen every marching routine shares.
 *
 * <p>Beast hunts, rallies, intel missions and mercenary marches all end on the same screen, and all
 * of them can be blocked there for the same reasons: no deployable troops, no free march queue,
 * another player already marching at the target, or not enough stamina. Each answer is a colour or a
 * template, never a sentence, so none of this needs OCR.
 */
public class DeploymentHelper {

    public static final int MAX_ATTACK_STAMINA_COST = 10;
    public static final int MAX_RALLY_STAMINA_COST = 25;

    // A red cost measures ~440 matching pixels, a white one exactly 0, so the bar can sit low.
    private static final int COST_RED_PIXEL_MIN = 10;
    // The ticked preparation option shows ~390 green pixels; the three others show none.
    private static final int SET_TIME_TICK_PIXEL_MIN = 50;

    private final EmulatorController emu;
    private final String device;
    private final TemplateSearchHelper templates;
    private final ResilientOcrExecutor<Integer> integerReader;
    private final ResilientOcrExecutor<Duration> durationReader;
    private final ProfileContextLogger log;

    public DeploymentHelper(EmulatorController emuManager, String emulatorNumber,
                            TemplateSearchHelper templateSearchHelper,
                            ResilientOcrExecutor<Integer> integerReader,
                            ResilientOcrExecutor<Duration> durationReader,
                            AccountDescriptor profile) {
        this.emu = emuManager;
        this.device = emulatorNumber;
        this.templates = templateSearchHelper;
        this.integerReader = integerReader;
        this.durationReader = durationReader;
        this.log = new ProfileContextLogger(DeploymentHelper.class, profile);
    }

    /**
     * Reads the values shown after lineup selection. The screen value is authoritative because hero
     * bonuses can reduce the action's nominal stamina cost.
     */
    public DeploymentScreenRead readScreen(int maxPlausibleStaminaCost) {
        if (maxPlausibleStaminaCost < 1) {
            throw new IllegalArgumentException("Maximum plausible stamina cost must be positive");
        }

        long travelSeconds = readTravelTimeSeconds();

        Integer readCost = integerReader.attemptRecognition(
                CommonGameAreas.SPENT_STAMINA_OCR_AREA,
                3, 100L,
                CommonOCRSettings.SPENT_STAMINA_SETTINGS,
                txt -> RegexNumberParser.conformsTo(txt, CommonOCRSettings.NUMBER_PATTERN),
                txt -> RegexNumberParser.extractByPattern(txt, CommonOCRSettings.NUMBER_PATTERN));
        boolean fallback = readCost == null || readCost < 1 || readCost > maxPlausibleStaminaCost;
        int staminaCost = fallback ? maxPlausibleStaminaCost : readCost;

        if (fallback) {
            log.warn("Deployment stamina cost "
                    + (readCost == null ? "unreadable" : readCost)
                    + " is out of range [1.." + maxPlausibleStaminaCost + "]; assuming "
                    + maxPlausibleStaminaCost);
        }
        log.info("Deployment screen: travelSeconds=" + travelSeconds
                + " staminaCost=" + staminaCost
                + " staminaFallback=" + fallback);
        return new DeploymentScreenRead(travelSeconds, staminaCost, fallback);
    }

    public long readTravelTimeSeconds() {
        Duration travel = durationReader.attemptRecognition(
                CommonGameAreas.TRAVEL_TIME_OCR_AREA,
                3, 100L,
                CommonOCRSettings.TRAVEL_TIME_SETTINGS,
                GameTimeUtils::isAcceptedFormat,
                GameTimeUtils::parseDuration);
        long travelSeconds = travel == null ? 0 : travel.getSeconds();
        if (travel == null) {
            log.warn("Deployment travel-time OCR failed");
        }
        return travelSeconds;
    }

    /**
     * Preparation time of the rally about to be held, in seconds. The dialog remembers whatever the
     * player last picked, so the ticked option is read rather than assumed.
     *
     * @param defaultSeconds used when the dialog cannot be read; the rally is not failed over it
     */
    public int readRallySetTimeSeconds(int defaultSeconds) {
        try {
            BufferedImage image = captureImage();
            for (int i = 0; i < CommonGameAreas.RALLY_SET_TIME_MINUTES.length; i++) {
                int tickPixels = PixelStats.count(image, CommonGameAreas.RALLY_SET_TIME_CHECKBOXES[i],
                        GameColors::isVividGreen);
                if (tickPixels >= SET_TIME_TICK_PIXEL_MIN) {
                    int minutes = CommonGameAreas.RALLY_SET_TIME_MINUTES[i];
                    log.info("Rally set time: " + minutes + " min ticked (tickPixels=" + tickPixels + ")");
                    return minutes * 60;
                }
            }
            log.warn("Rally set time: no ticked option found; assuming " + defaultSeconds + "s");
        } catch (Exception ex) {
            log.warn("Rally set time: checkbox scan failed: " + ex.getMessage());
        }
        return defaultSeconds;
    }

    /** True when the deploy cost is drawn in red, which is the game saying the stamina is not there. */
    public boolean isDeployCostRed() {
        try {
            int redPixels = PixelStats.count(captureImage(), CommonGameAreas.SPENT_STAMINA_OCR_AREA,
                    GameColors::isBlockedRed);
            boolean red = redPixels >= COST_RED_PIXEL_MIN;
            log.debug("Deploy cost red check: redPixels=" + redPixels + " result=" + red);
            return red;
        } catch (Exception ex) {
            log.warn("Deploy cost red check failed: " + ex.getMessage());
            return false;
        }
    }

    /** The formation screen offers to train troops instead of deploying them: there are none to send. */
    public boolean hasNoDeployableTroops() {
        ImageSearchResultData trainButton = templates.locatePattern(
                TemplatesEnum.RALLY_TROOP_TRAINING_BUTTON,
                search(CommonGameAreas.RALLY_TROOP_TRAINING_AREA, 2, 85));
        if (trainButton.isFound()) {
            log.warn("No deployable troops: Troop Training button at " + trainButton.getPoint()
                    + " score=" + trainButton.getMatchScore());
            return true;
        }
        return false;
    }

    /** A popup after pressing Rally means every march queue is occupied. Closes it when present. */
    public boolean isMarchQueueFull() {
        ImageSearchResultData popup = templates.locatePattern(
                TemplatesEnum.RALLY_MARCH_QUEUE_FULL,
                search(CommonGameAreas.RALLY_MARCH_QUEUE_FULL_AREA, 2, 85));
        if (!popup.isFound()) {
            return false;
        }
        log.warn("March queue full popup at " + popup.getPoint() + " score=" + popup.getMatchScore());
        emu.touchPoint(device, CommonGameAreas.RALLY_MARCH_QUEUE_FULL_CLOSE);
        return true;
    }

    /**
     * The "Other Troops are marching toward the same target" confirmation. Deploying anyway wastes the
     * march, so callers back out; two back presses leave the dialog and then the formation screen.
     */
    public boolean isSameTargetDialog() {
        ImageSearchResultData dialog = templates.locatePattern(
                TemplatesEnum.TROOPS_ALREADY_MARCHING,
                search(CommonGameAreas.SAME_TARGET_DIALOG_AREA, 2, 90));
        if (dialog.isFound()) {
            log.warn("Same-target confirmation at " + dialog.getPoint() + " score=" + dialog.getMatchScore());
            return true;
        }
        return false;
    }

    /** Equalises the troop sliders. Its x shifts with the Balance button, so it is matched, not tapped blind. */
    public boolean tapEqualize() {
        ImageSearchResultData equalize = templates.locatePattern(
                TemplatesEnum.RALLY_EQUALIZE_BUTTON,
                search(CommonGameAreas.RALLY_BOTTOM_BUTTON_BAR, 3, 90));
        if (!equalize.isFound()) {
            log.warn("Equalize button not found in the bottom button bar");
            return false;
        }
        emu.touchPoint(device, equalize.getPoint());
        return true;
    }

    private BufferedImage captureImage() {
        RawImageData frame = emu.captureScreen(device);
        return TesseractOcrProvider.toBufferedImage(frame);
    }

    private static TemplateSearchHelper.SearchConfig search(
            dev.frostguard.api.domain.AreaData area, int attempts, int threshold) {
        return TemplateSearchHelper.SearchConfig.builder()
                .withMaxAttempts(attempts)
                .withDelay(200)
                .withThreshold(threshold)
                .withArea(area)
                .build();
    }
}
