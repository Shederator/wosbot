package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.error.HomeNotFoundException;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.color.PixelStats;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.logging.ProfileContextLogger;

import java.awt.image.BufferedImage;

/** Verifies the Intel panel and reaches it through the Wilderness shortcut. */
public class IntelScreenHelper {

    private static final int MAX_NAV_PASSES = 3;
    private static final int INTEL_AVAILABLE_GREEN_MIN = 150;
    private static final long MISSION_RETURN_SETTLE_MILLIS = 1_000L;
    // isIntelScreenActive() confirms the Intel chrome, which draws before the markers do. Every
    // caller then scans for markers, so returning the moment the chrome appears had them all reading
    // an empty map: on 30 August Intel entered the screen, found nothing, and rescheduled itself
    // seven hours out with Fire Beasts standing on it. The wait belongs here, once, rather than at
    // each of the five call sites.
    private static final long MARKER_LAYER_SETTLE_MILLIS = 2_000L;
    private static final AreaData WORLD_INTEL_BUTTON_AREA = AreaData.of(615, 800, 715, 930);
    private static final TemplateSearchHelper.SearchConfig WORLD_INTEL_BUTTON_SEARCH =
            TemplateSearchHelper.SearchConfig.builder()
                    .withMaxAttempts(2)
                    .withDelay(250)
                    .withThreshold(88)
                    .withArea(WORLD_INTEL_BUTTON_AREA)
                    .build();

    private final EmulatorController emu;
    private final String dev;
    private final TapInteractionService taps;
    private final TemplateSearchHelper tpl;
    private final NavigationHelper nav;
    private final ProfileContextLogger log;

    public IntelScreenHelper(EmulatorController emuManager, String emulatorNumber,
                             TemplateSearchHelper templateSearchHelper,
                             NavigationHelper navigationHelper, AccountDescriptor profile) {
        this.emu = emuManager;
        this.dev = emulatorNumber;
        this.taps = TapInteractionService.forController(emuManager, emulatorNumber);
        this.tpl = templateSearchHelper;
        this.nav = navigationHelper;
        this.log = new ProfileContextLogger(IntelScreenHelper.class, profile);
    }

    /** Uses Daily only as an OCR-free availability gate, then enters Intel from Wilderness. */
    public boolean enterIntelFromDailyIfAvailable() {
        nav.ensureCorrectScreenLocation(LaunchPoint.ANY);
        ImageSearchResultData lighthouseRow = nav.findSidebarDestinationRow(
                SidebarDestination.LIGHTHOUSE_INTEL);
        if (lighthouseRow == null || !lighthouseRow.isFound()) {
            log.info("Lighthouse Intel row icon is absent after the bounded Daily scan; "
                    + "the completed row may be hidden, so Intel is unavailable.");
            if (!nav.closeSidebar()) {
                throw new HomeNotFoundException(
                        "Failed to close Daily after the Lighthouse Intel row was absent");
            }
            return false;
        }

        ImageSearchResultData availablePattern = tpl.locatePattern(TemplatesEnum.INTEL_GAIN_AVAILABLE,
                dailyIntelGainSearch(lighthouseRow));
        boolean available = false;
        if (availablePattern != null && availablePattern.isFound()) {
            BufferedImage frame = ImageConverter.toBufferedImage(emu.captureScreen(dev));
            int greenPixels = availableGreenPixels(frame, availablePattern);
            available = greenPixels >= INTEL_AVAILABLE_GREEN_MIN;
            log.info("Daily Intel availability evidence: greenGainPattern=true, greenPixels=" + greenPixels
                    + ", available=" + available);
        } else {
            log.info("Daily Intel availability evidence: greenGainPattern=false, available=false");
        }

        if (!nav.closeSidebar()) {
            throw new HomeNotFoundException("Failed to close Daily sidebar after Intel availability check");
        }
        if (!available) {
            return false;
        }

        enterIntelFromWilderness();
        return true;
    }

    /** Returns from a mission's Wilderness end state without routing through City or Daily. */
    public void ensureOnIntelScreen() {
        pause(300);
        if (!isIntelScreenActive()) {
            enterIntelFromWilderness();
        }
    }

    /** Re-enters Intel after a mission transition known to finish in Wilderness. */
    public void returnToIntelFromWilderness() {
        pause(MISSION_RETURN_SETTLE_MILLIS);
        enterIntelFromWilderness();
    }

    /** Continues a previously started Intel cycle after Daily no longer reports new missions. */
    public void resumeIntelCycleFromWilderness() {
        enterIntelFromWilderness();
    }

    private void enterIntelFromWilderness() {
        nav.ensureCorrectScreenLocation(LaunchPoint.WORLD);
        for (int pass = 1; pass <= MAX_NAV_PASSES; pass++) {
            ImageSearchResultData button = tpl.locatePattern(TemplatesEnum.GAME_HOME_INTEL,
                    WORLD_INTEL_BUTTON_SEARCH);
            if (button == null || !button.isFound()) {
                log.warn("Wilderness Intel shortcut absent, pass " + pass);
                pause(350);
                continue;
            }

            log.info("Opening Intel from the Wilderness shortcut");
            taps.tapInside(button);
            pause(800);
            if (isIntelScreenActive()) {
                pause(MARKER_LAYER_SETTLE_MILLIS);
                return;
            }
            log.warn("Wilderness Intel shortcut did not open the Intel map, pass " + pass);
            pause(400);
        }
        throw new HomeNotFoundException("Failed to open Intel from the Wilderness shortcut");
    }

    static AreaData availabilityAreaFor(ImageSearchResultData gainPattern) {
        PointData center = gainPattern.getPoint();
        if (center == null) {
            throw new IllegalArgumentException("A located Intel Gain pattern is required");
        }
        AreaData matchedArea = gainPattern.getMatchedArea();
        return matchedArea != null ? matchedArea : AreaData.of(
                center.getX() - 50, center.getY() - 18,
                center.getX() + 49, center.getY() + 17);
    }

    static int availableGreenPixels(BufferedImage frame, ImageSearchResultData gainPattern) {
        return PixelStats.count(frame, availabilityAreaFor(gainPattern), GameColors::isVividGreen);
    }

    static AreaData intelGainRowArea(ImageSearchResultData lighthouseRow) {
        PointData center = lighthouseRow.getPoint();
        if (center == null) {
            throw new IllegalArgumentException("A located Lighthouse Intel row icon is required");
        }
        return AreaData.of(80, Math.max(300, center.getY() - 8),
                360, Math.min(880, center.getY() + 45));
    }

    private static TemplateSearchHelper.SearchConfig dailyIntelGainSearch(
            ImageSearchResultData lighthouseRow) {
        return TemplateSearchHelper.SearchConfig.builder()
                .withMaxAttempts(2)
                .withDelay(250)
                .withThreshold(88)
                .withArea(intelGainRowArea(lighthouseRow))
                .build();
    }

    public boolean isIntelScreenActive() {
        for (int i = 0; i < 2; i++) {
            if (tpl.locatePattern(TemplatesEnum.INTEL_SCREEN_1,
                    SearchConfigConstants.DEFAULT_SINGLE).isFound()
                    || tpl.locatePattern(TemplatesEnum.INTEL_SCREEN_2,
                            SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
                log.debug("Intel confirmed, probe " + (i + 1));
                return true;
            }
            if (i == 0) {
                pause(300);
            }
        }
        return false;
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
