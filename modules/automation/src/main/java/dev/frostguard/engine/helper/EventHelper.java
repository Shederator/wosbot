package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.vision.logging.ProfileContextLogger;

// Checks whether event-specific UI badges are visible on the current screen.
public class EventHelper {

    private final ProfileContextLogger log;
    private final TemplateSearchHelper tpl;

    public EventHelper(EmulatorController emuManager, String emulatorNumber, AccountDescriptor profile) {
        this.tpl = new TemplateSearchHelper(emuManager, emulatorNumber, profile);
        this.log = new ProfileContextLogger(EventHelper.class, profile);
    }

    public boolean isBearRunning() {
        return badgeVisible(TemplatesEnum.BEAR_HUNT_IS_RUNNING);
    }

    // Single-method probe — no intermediate record or wrapper needed
    private boolean badgeVisible(TemplatesEnum badge) {
        log.debug("Looking for " + badge.name());
        ImageSearchResultData hit = tpl.locatePattern(badge, SearchConfigConstants.SINGLE_WITH_RETRIES);
        boolean found = hit.isFound();
        log.debug(badge.name() + (found ? " visible" : " absent"));
        return found;
    }
}
