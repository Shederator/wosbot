package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.input.TapJitterPolicy;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.color.PixelStats;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.CompactGameNumberParser;
import dev.frostguard.vision.logging.ProfileContextLogger;
import dev.frostguard.vision.convert.RegexNumberParser;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;
import dev.frostguard.vision.ocr.OcrEngine;

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
    private static final Duration SET_TIME_SELECTION_TIMEOUT = Duration.ofMillis(900);

    private final EmulatorController emu;
    private final String device;
    private final TapInteractionService taps;
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
        this.taps = TapInteractionService.forController(emuManager, emulatorNumber);
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
        return readScreen(maxPlausibleStaminaCost, integerReader, durationReader);
    }

    /**
     * Reads the first stable formation state after Attack. All template checks share one frame.
     */
    public DeploymentFormationRead readFormationScreen() {
        TemplateSearchHelper.Frame frame = templates.captureFrame();
        ImageSearchResultData queueFull = frame.locatePattern(
                TemplatesEnum.RALLY_MARCH_QUEUE_FULL,
                search(CommonGameAreas.RALLY_MARCH_QUEUE_FULL_AREA, 1, 85));
        ImageSearchResultData deploy = frame.locatePattern(
                TemplatesEnum.DEPLOY_BUTTON, search(1, 90));
        ImageSearchResultData equalize = frame.locatePattern(
                TemplatesEnum.RALLY_EQUALIZE_BUTTON,
                search(CommonGameAreas.RALLY_BOTTOM_BUTTON_BAR, 1, 90));
        return new DeploymentFormationRead(queueFull.isFound(), deploy, equalize);
    }

    /**
     * Reads every safety and numeric signal needed immediately before tapping Deploy from one frame.
     */
    public DeploymentPreflightRead readPreflightScreen(int maxPlausibleStaminaCost) {
        TemplateSearchHelper.Frame frame = templates.captureFrame();
        DeploymentScreenRead deployment = readScreen(
                maxPlausibleStaminaCost,
                new ResilientOcrExecutor<>(frame),
                new ResilientOcrExecutor<>(frame));
        boolean noTroops = hasNoDeployableTroops(frame);
        boolean redCost = isDeployCostRed(frame);
        ImageSearchResultData deploy = frame.locatePattern(
                TemplatesEnum.DEPLOY_BUTTON, search(1, 90));
        return new DeploymentPreflightRead(deployment, noTroops, redCost, deploy);
    }

    /** Reads all known outcomes after tapping Deploy from one fresh frame. */
    public DeploymentPostTapRead readPostTapScreen() {
        TemplateSearchHelper.Frame frame = templates.captureFrame();
        ImageSearchResultData queueFull = frame.locatePattern(
                TemplatesEnum.RALLY_MARCH_QUEUE_FULL,
                search(CommonGameAreas.RALLY_MARCH_QUEUE_FULL_AREA, 1, 85));
        ImageSearchResultData confirmation = frame.locatePattern(
                TemplatesEnum.DEPLOY_CONFIRMATION_DIALOG, search(1, 90));
        ImageSearchResultData sameTarget = frame.locatePattern(
                TemplatesEnum.TROOPS_ALREADY_MARCHING,
                search(CommonGameAreas.SAME_TARGET_DIALOG_AREA, 1, 90));
        ImageSearchResultData deploy = frame.locatePattern(
                TemplatesEnum.DEPLOY_BUTTON, search(1, 90));
        return new DeploymentPostTapRead(
                queueFull.isFound(), confirmation, sameTarget.isFound(), deploy);
    }

    /** Closes a queue-full popup already proven by the current frame. */
    public void dismissMarchQueueFullPopup() {
        taps.tapNear(CommonGameAreas.RALLY_MARCH_QUEUE_FULL_CLOSE,
                TapJitterPolicy.DEFAULT_POINT_JITTER_RADIUS);
    }

    private DeploymentScreenRead readScreen(
            int maxPlausibleStaminaCost,
            ResilientOcrExecutor<Integer> integers,
            ResilientOcrExecutor<Duration> durations) {
        if (maxPlausibleStaminaCost < 1) {
            throw new IllegalArgumentException("Maximum plausible stamina cost must be positive");
        }

        long travelSeconds = readTravelTimeSeconds(durations);

        Integer readCost = integers.attemptRecognition(
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
        return readTravelTimeSeconds(durationReader);
    }

    /** Reads the selected saved formation's troop count from the deployment-screen fraction. */
    public long readSelectedTroopCount() {
        try {
            TemplateSearchHelper.Frame frame = templates.captureFrame();
            String text = frame.extractText(
                    CommonOCRSettings.RALLY_TROOP_COUNT_SETTINGS,
                    CommonGameAreas.RALLY_SELECTED_TROOPS_OCR_AREA.topLeft(),
                    CommonGameAreas.RALLY_SELECTED_TROOPS_OCR_AREA.bottomRight());
            long count = parseSelectedTroopCount(text);
            if (count < 0) {
                log.warn("Selected formation troop count unreadable: " + text);
            }
            return count;
        } catch (Exception ex) {
            log.warn("Selected formation troop-count OCR failed: " + ex.getMessage());
            return -1;
        }
    }

    static long parseSelectedTroopCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1;
        }
        String numerator = raw.split("/", 2)[0].replace(" ", "");
        return CompactGameNumberParser.parse(numerator);
    }

    private long readTravelTimeSeconds(ResilientOcrExecutor<Duration> durations) {
        Duration travel = durations.attemptRecognition(
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
            int minutes = selectedRallySetTimeMinutes(image);
            if (minutes > 0) {
                log.info("Rally set time: " + minutes + " min ticked");
                return minutes * 60;
            }
            log.warn("Rally set time: no ticked option found; assuming " + defaultSeconds + "s");
        } catch (Exception ex) {
            log.warn("Rally set time: checkbox scan failed: " + ex.getMessage());
        }
        return defaultSeconds;
    }

    /**
     * Selects an exact rally preparation time and accepts it only after a fresh frame shows the
     * green tick on that option. There is deliberately no blind second tap: a delayed first tap
     * cannot toggle the option again after an interruption.
     */
    public boolean selectRallySetTimeMinutes(int minutes) {
        return selectRallySetTimeMinutes(
                minutes, CommonGameAreas.RALLY_SET_TIME_MINUTES, CommonGameAreas.RALLY_SET_TIME_CHECKBOXES);
    }

    /** Selects the two-option 5/10 minute timer shown by Bear Hunt. */
    public boolean selectBearRallySetTimeMinutes(int minutes) {
        return selectRallySetTimeMinutes(
                minutes,
                CommonGameAreas.BEAR_RALLY_SET_TIME_MINUTES,
                CommonGameAreas.BEAR_RALLY_SET_TIME_CHECKBOXES);
    }

    private boolean selectRallySetTimeMinutes(int minutes, int[] options, AreaData[] checkboxes) {
        int index = rallySetTimeIndex(minutes, options);
        if (index < 0) {
            log.warn("Unsupported rally set time: " + minutes + " min");
            return false;
        }
        if (selectedRallySetTimeMinutes(captureImage(), options, checkboxes) == minutes) {
            return true;
        }
        if (Thread.currentThread().isInterrupted()) {
            return false;
        }

        taps.tapInside(checkboxes[index]);
        long deadline = System.nanoTime() + SET_TIME_SELECTION_TIMEOUT.toNanos();
        do {
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            if (selectedRallySetTimeMinutes(captureImage(), options, checkboxes) == minutes) {
                log.info("Rally set time confirmed at " + minutes + " min");
                return true;
            }
        } while (System.nanoTime() < deadline);

        log.warn("Rally set time did not confirm at " + minutes + " min");
        return false;
    }

    static int selectedRallySetTimeMinutes(BufferedImage image) {
        return selectedRallySetTimeMinutes(
                image, CommonGameAreas.RALLY_SET_TIME_MINUTES, CommonGameAreas.RALLY_SET_TIME_CHECKBOXES);
    }

    static int selectedBearRallySetTimeMinutes(BufferedImage image) {
        return selectedRallySetTimeMinutes(
                image,
                CommonGameAreas.BEAR_RALLY_SET_TIME_MINUTES,
                CommonGameAreas.BEAR_RALLY_SET_TIME_CHECKBOXES);
    }

    private static int selectedRallySetTimeMinutes(
            BufferedImage image, int[] options, AreaData[] checkboxes) {
        if (image == null) {
            return -1;
        }
        for (int i = 0; i < options.length; i++) {
            int tickPixels = PixelStats.count(image, checkboxes[i],
                    GameColors::isVividGreen);
            if (tickPixels >= SET_TIME_TICK_PIXEL_MIN) {
                return options[i];
            }
        }
        return -1;
    }

    private static int rallySetTimeIndex(int minutes, int[] options) {
        for (int i = 0; i < options.length; i++) {
            if (options[i] == minutes) {
                return i;
            }
        }
        return -1;
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

    private boolean isDeployCostRed(TemplateSearchHelper.Frame frame) {
        try {
            int redPixels = PixelStats.count(frame.bufferedImage(),
                    CommonGameAreas.SPENT_STAMINA_OCR_AREA, GameColors::isBlockedRed);
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

    private boolean hasNoDeployableTroops(TemplateSearchHelper.Frame frame) {
        ImageSearchResultData trainButton = frame.locatePattern(
                TemplatesEnum.RALLY_TROOP_TRAINING_BUTTON,
                search(CommonGameAreas.RALLY_TROOP_TRAINING_AREA, 1, 85));
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
        taps.tapNear(CommonGameAreas.RALLY_MARCH_QUEUE_FULL_CLOSE, TapJitterPolicy.DEFAULT_POINT_JITTER_RADIUS);
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
        taps.tapInside(equalize);
        return true;
    }

    private BufferedImage captureImage() {
        RawImageData frame = emu.captureScreen(device);
        return dev.frostguard.vision.convert.ImageConverter.toBufferedImage(frame);
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

    private static TemplateSearchHelper.SearchConfig search(int attempts, int threshold) {
        return TemplateSearchHelper.SearchConfig.builder()
                .withMaxAttempts(attempts)
                .withDelay(200)
                .withThreshold(threshold)
                .build();
    }
}
