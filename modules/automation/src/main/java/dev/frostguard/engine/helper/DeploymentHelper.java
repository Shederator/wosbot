package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
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
    // The odds warning measures ~1790 red pixels across its band and the band is otherwise empty, so
    // the bar sits high: the loudest incidental red text found in 440 stored frames (a rally toast on
    // the city view, not this screen) reached 287, and this stays clear of it.
    static final int ODDS_WARNING_PIXEL_MIN = 600;

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

    /** What the deploy screen's red odds line says, if it says anything. */
    public enum OddsWarning {
        /** No red line: the game is not warning about this march. */
        NONE,
        /** "You are not likely to prevail" -- the odds are against it, but it can still win. */
        UNLIKELY,
        /** "...almost certain to fail" -- the game saying this march is lost. Never send it. */
        CERTAIN_FAILURE,
        /**
         * The odds line could not be checked at all -- the screen capture or the pixel scan threw.
         *
         * <p>Distinct from {@link #CERTAIN_FAILURE} on purpose. Both refuse to send the march, but
         * this one carries no claim about the beast: it means we did not get to look. Treating a
         * dropped frame as a verdict would abandon Fire Beasts for the whole run on one bad
         * capture, and treating it as NONE would deploy into a warning nobody read.
         */
        UNREADABLE
    }

    /**
     * Reads the odds line and says which of the two warnings it is.
     *
     * <p>The pixel count says whether a red line is there at all; it cannot tell the two apart,
     * because both are red text in the same band. Only one of them is survivable, so the line is
     * read: "not likely to prevail" is odds and callers may still deploy, while the stronger
     * "almost certain to fail" is the game telling us the troops are gone. Anything red that does
     * not read as the survivable phrase is treated as the fatal one -- a march is worth more than a
     * retry.
     */
    public OddsWarning readOddsWarning() {
        int redPixels;
        try {
            redPixels = PixelStats.count(captureImage(), CommonGameAreas.DEPLOY_ODDS_WARNING_AREA,
                    GameColors::isBlockedRed);
        } catch (Exception ex) {
            // Fail closed. This used to return NONE, which let a march the game may have been
            // calling almost certain to fail deploy anyway on a dropped frame -- the opposite of
            // what the classifyOddsLine contract below promises, where an unreadable red line is
            // deliberately treated as fatal because the cost of being wrong one way is a wasted
            // retry and the other way is an army.
            log.warn("Deploy odds warning check failed, treating as unreadable: " + ex.getMessage());
            return OddsWarning.UNREADABLE;
        }
        if (redPixels < ODDS_WARNING_PIXEL_MIN) {
            log.debug("Deploy odds line: redPixels=" + redPixels + " -> NONE");
            return OddsWarning.NONE;
        }
        String line = readOddsLineText();
        OddsWarning verdict = classifyOddsLine(line);
        log.info("Deploy odds line: redPixels=" + redPixels + " text=\"" + line + "\" -> " + verdict);
        return verdict;
    }

    /**
     * Which warning a red odds line is, by its words.
     *
     * <p>Only "you are not likely to prevail" is survivable. Everything else red -- the stronger
     * "almost certain to fail", or a line OCR could not read -- is treated as fatal, because the
     * cost of being wrong one way is a wasted retry and the other way is an army.
     */
    static OddsWarning classifyOddsLine(String line) {
        String normalised = line == null ? "" : line.toLowerCase()
                .replaceAll("[^a-z ]", " ").replaceAll("\s+", " ").trim();
        return normalised.contains("prevail") && !normalised.contains("fail")
                ? OddsWarning.UNLIKELY
                : OddsWarning.CERTAIN_FAILURE;
    }

    private String readOddsLineText() {
        try {
            RawImageData frame = emu.captureScreen(device);
            return OcrEngine.recognizeText(frame,
                    CommonGameAreas.DEPLOY_ODDS_TEXT_AREA.topLeft(),
                    CommonGameAreas.DEPLOY_ODDS_TEXT_AREA.bottomRight(),
                    CommonOCRSettings.DEPLOY_ODDS_SETTINGS).trim();
        } catch (Exception ex) {
            log.warn("Deploy odds line OCR failed: " + ex.getMessage());
            return "";
        }
    }

    /**
     * True when the game prints its red "You are not likely to prevail" line above the troop rows,
     * which is it saying this march loses. Red text is the only red thing in that band, so this is a
     * pixel count rather than OCR, like every other answer this screen gives.
     */
    public boolean isUnlikelyToPrevail() {
        try {
            int redPixels = PixelStats.count(captureImage(), CommonGameAreas.DEPLOY_ODDS_WARNING_AREA,
                    GameColors::isBlockedRed);
            boolean warned = redPixels >= ODDS_WARNING_PIXEL_MIN;
            log.debug("Deploy odds warning check: redPixels=" + redPixels + " result=" + warned);
            return warned;
        } catch (Exception ex) {
            log.warn("Deploy odds warning check failed: " + ex.getMessage());
            return false;
        }
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

    // Calibrated live on the 3-troop-type beast deployment screen (720x1280); that 3-row layout is
    // specific to this screen.
    private static final dev.frostguard.api.domain.PointData[] TROOP_SLIDER_LEFT = {
            new dev.frostguard.api.domain.PointData(225, 730),
            new dev.frostguard.api.domain.PointData(225, 870),
            new dev.frostguard.api.domain.PointData(225, 1010),
    };
    private static final dev.frostguard.api.domain.PointData[] TROOP_SLIDER_RIGHT = {
            new dev.frostguard.api.domain.PointData(640, 730),
            new dev.frostguard.api.domain.PointData(640, 870),
            new dev.frostguard.api.domain.PointData(640, 1010),
    };

    /**
     * Drags every troop-type slider on the current deployment screen to its maximum.
     *
     * <p>Equalize spreads a march thinly and evenly across the three troop types, and the game
     * answers that with its red "You are not likely to prevail" line -- a verdict on the lineup we
     * just chose rather than on the target. Beast and Fire Beast deployments send the whole army
     * instead.
     */
    public void maxAllTroopSliders() {
        for (int i = 0; i < TROOP_SLIDER_LEFT.length; i++) {
            emu.swipeScreen(device, TROOP_SLIDER_LEFT[i], TROOP_SLIDER_RIGHT[i]);
        }
        log.info("Dragged all troop sliders to max.");
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
}
