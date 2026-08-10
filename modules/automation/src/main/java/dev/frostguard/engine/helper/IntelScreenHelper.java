package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.error.HomeNotFoundException;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.logging.ProfileContextLogger;
import net.sourceforge.tess4j.TesseractException;

import java.io.IOException;
import java.util.function.BooleanSupplier;

// Verifies the Intel panel is on-screen; navigates there when it is not.
public class IntelScreenHelper {

    private static final int MAX_NAV_PASSES = 3;

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

    public void ensureOnIntelScreen() {
        pause(500);
        log.info("Checking Intel screen");
        if (isIntelScreenActive()) { log.info("Already on Intel"); return; }

        log.warn("Not on Intel - routing via world view");
        nav.ensureCorrectScreenLocation(LaunchPoint.WORLD);

        for (int i = 1; i <= MAX_NAV_PASSES; i++) {
            ImageSearchResultData hit = tpl.locatePattern(TemplatesEnum.GAME_HOME_INTEL,
                    SearchConfigConstants.DEFAULT_SINGLE);
            if (!hit.isFound()) { log.debug("Intel button absent, pass " + i); pause(300); continue; }

            log.info("Tapping Intel button");
            taps.tapInside(hit);
            pause(1000);
            if (isIntelScreenActive()) { log.info("Intel reached"); return; }

            log.warn("Tap failed - backing out");
            emu.pressBack(dev);
            pause(500);
        }
        log.error("Intel unreachable after " + MAX_NAV_PASSES + " passes");
        throw new HomeNotFoundException("Failed to navigate to Intel screen");
    }

    public boolean isIntelScreenActive() {
        // Two quick probes with a short gap
        for (int i = 0; i < 2; i++) {
            if (screenMatchesIntel()) { log.debug("Intel confirmed, probe " + (i + 1)); return true; }
            if (i == 0) pause(300);
        }
        return false;
    }

    // Merged: checks both template variants and OCR fallback in one pass
    private boolean screenMatchesIntel() {
        BooleanSupplier[] checks = {
                () -> tpl.locatePattern(TemplatesEnum.INTEL_SCREEN_1, SearchConfigConstants.DEFAULT_SINGLE).isFound(),
                () -> tpl.locatePattern(TemplatesEnum.INTEL_SCREEN_2, SearchConfigConstants.DEFAULT_SINGLE).isFound(),
                this::ocrShowsIntel
        };
        for (BooleanSupplier check : checks) {
            if (check.getAsBoolean()) return true;
        }
        return false;
    }

    private boolean ocrShowsIntel() {
        try {
            String txt = emu.readText(dev, new PointData(85, 15), new PointData(171, 62));
            return txt != null && txt.toLowerCase().contains("intel");
        } catch (IOException | TesseractException e) {
            log.warn("OCR check failed: " + e.getMessage());
            return false;
        }
    }

    private static void pause(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
