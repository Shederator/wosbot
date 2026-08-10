package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.logging.ProfileContextLogger;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Manages alliance-panel UI interactions — currently limited to
 * toggling the rally auto-join setting, but structured for future
 * expansion of alliance-related automations.
 */
public final class AllianceHelper {

    /** Outcome of a multi-step UI workflow. */
    public enum WorkflowOutcome {
        COMPLETED,
        ELEMENT_NOT_FOUND,
        NAVIGATION_FAILED
    }

    /** Each phase of the auto-join toggle procedure. */
    private enum TogglePhase {
        OPEN_ALLIANCE_PANEL,
        LOCATE_WAR_BUTTON,
        SELECT_RALLY_TAB,
        OPEN_AUTOJOIN_MENU,
        SWITCH_TOGGLE,
        RETURN_HOME
    }

    private static final Map<TogglePhase, String> PHASE_LABELS;
    static {
        PHASE_LABELS = new EnumMap<>(TogglePhase.class);
        PHASE_LABELS.put(TogglePhase.OPEN_ALLIANCE_PANEL, "Opening alliance panel");
        PHASE_LABELS.put(TogglePhase.LOCATE_WAR_BUTTON,   "Locating war button");
        PHASE_LABELS.put(TogglePhase.SELECT_RALLY_TAB,    "Selecting rally tab");
        PHASE_LABELS.put(TogglePhase.OPEN_AUTOJOIN_MENU,  "Opening auto-join config");
        PHASE_LABELS.put(TogglePhase.SWITCH_TOGGLE,       "Disabling auto-join");
        PHASE_LABELS.put(TogglePhase.RETURN_HOME,         "Navigating back to home");
    }

    private final EmulatorController     emu;
    private final String                 slot;
    private final TapInteractionService  taps;
    private final TemplateSearchHelper   tplSearch;
    private final NavigationHelper       nav;
    private final ProfileContextLogger   log;

    public AllianceHelper(
            EmulatorController emuManager,
            String emulatorNumber,
            TemplateSearchHelper templateSearchHelper,
            NavigationHelper navigationHelper,
            AccountDescriptor profile) {
        this.emu       = emuManager;
        this.slot      = emulatorNumber;
        this.taps      = TapInteractionService.forController(emuManager, emulatorNumber);
        this.tplSearch = templateSearchHelper;
        this.nav       = navigationHelper;
        this.log       = new ProfileContextLogger(AllianceHelper.class, profile);
    }

    /**
     * Disables the rally auto-join toggle through the alliance war
     * panel, returning the workflow outcome.
     */
    public boolean disableAutoJoin() {
        return executeToggleWorkflow() == WorkflowOutcome.COMPLETED;
    }

    private WorkflowOutcome executeToggleWorkflow() {
        nav.ensureCorrectScreenLocation(LaunchPoint.ANY);

        emitPhase(TogglePhase.OPEN_ALLIANCE_PANEL);
        tap(CommonGameAreas.BOTTOM_MENU_ALLIANCE_BUTTON);
        pause(3000);

        emitPhase(TogglePhase.LOCATE_WAR_BUTTON);
        ImageSearchResultData warHit = tplSearch.locatePattern(
                TemplatesEnum.ALLIANCE_WAR_BUTTON,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!warHit.isFound()) {
            log.error("War button not detected on screen");
            return WorkflowOutcome.ELEMENT_NOT_FOUND;
        }
        taps.tapInside(warHit);
        pause(500);

        emitPhase(TogglePhase.SELECT_RALLY_TAB);
        tap(CommonGameAreas.ALLIANCE_WAR_RALLY_TAB);
        pause(500);

        emitPhase(TogglePhase.OPEN_AUTOJOIN_MENU);
        tap(CommonGameAreas.ALLIANCE_AUTOJOIN_MENU_BUTTON);
        pause(1000);

        emitPhase(TogglePhase.SWITCH_TOGGLE);
        tap(CommonGameAreas.ALLIANCE_AUTOJOIN_DISABLE_BUTTON);
        pause(300);

        emitPhase(TogglePhase.RETURN_HOME);
        for (int i = 3; i > 0; i--) {
            emu.pressBack(slot);
            pause(300);
        }

        log.info("Auto-join disable workflow finished");
        return WorkflowOutcome.COMPLETED;
    }

    private void emitPhase(TogglePhase phase) {
        log.debug(PHASE_LABELS.getOrDefault(phase, phase.name()));
    }

    private void tap(AreaData region) {
        taps.tapInside(region);
    }

    private static void pause(long ms) {
        try { TimeUnit.MILLISECONDS.sleep(ms); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
