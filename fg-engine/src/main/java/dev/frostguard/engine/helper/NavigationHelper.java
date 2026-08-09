package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.error.HomeNotFoundException;
import dev.frostguard.engine.error.ProfileInReconnectStateException;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.nav.ButtonConstants;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.LoggingService;
import dev.frostguard.vision.logging.ProfileContextLogger;

// Screen location verification and cross-screen navigation for
// primary game views and auxiliary menus.
public class NavigationHelper {

    private static final PointData EVENT_TAB_SEARCH_TOP_LEFT = new PointData(0, 80);
    private static final PointData EVENT_TAB_SEARCH_BOTTOM_RIGHT = new PointData(720, 210);
    private static final PointData EVENT_TAB_RESET_FROM = new PointData(80, 120);
    private static final PointData EVENT_TAB_RESET_TO = new PointData(578, 130);
    private static final PointData EVENT_TAB_SCAN_FROM = new PointData(630, 143);
    private static final PointData EVENT_TAB_SCAN_TO = new PointData(400, 128);
    private static final int EVENT_TAB_RESET_SWIPES = 3;
    private static final int EVENT_TAB_SCAN_SWIPES = 7;
    private static final long EVENT_TAB_SETTLE_MS = 1000L;

    private final TemplateSearchHelper searcher;
    private final EmulatorController emu;
    private final String device;
    private final TapInteractionService taps;
    private final ProfileContextLogger log;
    private final String accountName;
    private final LoggingService logs;

    public NavigationHelper(EmulatorController emuManager, String emulatorNumber,
                            AccountDescriptor profile) {
        this.emu = emuManager;
        this.device = emulatorNumber;
        this.taps = TapInteractionService.forController(emuManager, emulatorNumber);
        this.searcher = new TemplateSearchHelper(emuManager, emulatorNumber, profile);
        this.log = new ProfileContextLogger(NavigationHelper.class, profile);
        this.accountName = profile.getName();
        this.logs = LoggingService.obtain();
    }

    // ── alliance menu ────────────────────────────────────────────────

    public boolean navigateToAllianceMenu(AllianceMenu menu) {
        taps.tapInside(ButtonConstants.BOTTOM_MENU_ALLIANCE_BUTTON);

        TemplatesEnum tpl;
        if (menu == AllianceMenu.WAR) tpl = TemplatesEnum.ALLIANCE_WAR_BUTTON;
        else if (menu == AllianceMenu.CHESTS) tpl = TemplatesEnum.ALLIANCE_CHEST_BUTTON;
        else if (menu == AllianceMenu.TERRITORY) tpl = TemplatesEnum.ALLIANCE_TERRITORY_BUTTON;
        else if (menu == AllianceMenu.SHOP) tpl = TemplatesEnum.ALLIANCE_SHOP_BUTTON;
        else if (menu == AllianceMenu.TECH) tpl = TemplatesEnum.ALLIANCE_TECH_BUTTON;
        else if (menu == AllianceMenu.HELP) tpl = TemplatesEnum.ALLIANCE_HELP_BUTTON;
        else tpl = TemplatesEnum.ALLIANCE_TRIUMPH_BUTTON;

        ImageSearchResultData hit = searcher.locatePattern(tpl,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!hit.isFound()) return false;
        taps.tapInside(hit, 1, 1000);
        return true;
    }

    // ── event menu ───────────────────────────────────────────────────

    public boolean navigateToEventMenu(EventMenu event) {
        broadcastInfo("Navigating to " + event.name());

        // open the events panel
        ImageSearchResultData evtBtn = searcher.locatePattern(
                TemplatesEnum.HOME_EVENTS_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!evtBtn.isFound()) {
            broadcastWarn("Events panel missed");
            return false;
        }
        taps.tapInside(evtBtn);
        interruptibleWait(2000);

        // clear existing selection
        taps.tapInside(new AreaData(new PointData(529, 27), new PointData(635, 63)), 5, 300);
        interruptibleWait(300);

        TemplatesEnum tpl = switch (event) {
            case HERO_MISSION -> TemplatesEnum.HERO_MISSION_EVENT_TAB;
            case MERCENARY -> TemplatesEnum.MERCENARY_EVENT_TAB;
            case ALLIANCE_CHAMPIONSHIP -> TemplatesEnum.ALLIANCE_CHAMPIONSHIP_TAB;
            case ALLIANCE_MOBILIZATION -> TemplatesEnum.ALLIANCE_MOBILIZATION_TAB;
            case TUNDRA_TRUCK -> TemplatesEnum.TUNDRA_TRUCK_TAB;
        };

        // The horizontal event strip continues moving after the swipe gesture returns. Searching during
        // that animation can miss even a 97% match, so settle fully and inspect only the header strip.
        ImageSearchResultData tab = locateEventTab(tpl);
        if (!tab.isFound()) {
            for (int i = 0; i < EVENT_TAB_RESET_SWIPES && !tab.isFound(); i++) {
                emu.swipeScreen(device, EVENT_TAB_RESET_FROM, EVENT_TAB_RESET_TO);
                interruptibleWait(EVENT_TAB_SETTLE_MS);
                tab = locateEventTab(tpl);
            }

            for (int i = 0; i < EVENT_TAB_SCAN_SWIPES && !tab.isFound(); i++) {
                emu.swipeScreen(device, EVENT_TAB_SCAN_FROM, EVENT_TAB_SCAN_TO);
                interruptibleWait(EVENT_TAB_SETTLE_MS);
                tab = locateEventTab(tpl);
            }
        }

        // fallback for mobilization unselected variant
        if (!tab.isFound() && event == EventMenu.ALLIANCE_MOBILIZATION) {
            broadcastDebug("Trying unselected mobilization tab");
            tab = searcher.locatePattern(TemplatesEnum.ALLIANCE_MOBILIZATION_UNSELECTED_TAB,
                    SearchConfigConstants.DEFAULT_SINGLE);
        }

        if (!tab.isFound()) {
            broadcastWarn("Tab not found: " + event);
            return false;
        }

        taps.tapInside(tab);
        interruptibleWait(1000);
        broadcastInfo("Reached " + event.name());
        return true;
    }

    private ImageSearchResultData locateEventTab(TemplatesEnum template) {
        return searcher.locatePattern(template,
                TemplateSearchHelper.SearchConfig.builder()
                        .withMaxAttempts(1)
                        .withThreshold(90)
                        .withCoordinates(EVENT_TAB_SEARCH_TOP_LEFT, EVENT_TAB_SEARCH_BOTTOM_RIGHT)
                        .build());
    }

    public void clearEventTabSelection() {
        taps.tapInside(new AreaData(new PointData(529, 27), new PointData(635, 63)), 5, 300);
        interruptibleWait(300);
    }

    // ── screen location ──────────────────────────────────────────────

    public void ensureCorrectScreenLocation(LaunchPoint target) {
        broadcastDebug("Locating screen - need " + target);
        int budget = 10;
        int pass = 1;
        while (pass <= budget) {
            // detect reconnect
            if (searcher.locatePattern(TemplatesEnum.GAME_HOME_RECONNECT,
                    SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
                throw new ProfileInReconnectStateException(accountName + " in reconnect state");
            }

            boolean atHome = searcher.locatePattern(TemplatesEnum.GAME_HOME_FURNACE,
                    SearchConfigConstants.DEFAULT_SINGLE).isFound();
            boolean atWorld = !atHome && searcher.locatePattern(TemplatesEnum.GAME_HOME_WORLD,
                    SearchConfigConstants.DEFAULT_SINGLE).isFound();

            // check if already at desired location
            if (target == LaunchPoint.ANY && (atHome || atWorld)) return;
            if (target == LaunchPoint.HOME && atHome) return;
            if (target == LaunchPoint.WORLD && atWorld) return;

            // try to navigate to desired location
            if (target == LaunchPoint.HOME && atWorld) {
                ImageSearchResultData w = searcher.locatePattern(TemplatesEnum.GAME_HOME_WORLD,
                        SearchConfigConstants.DEFAULT_SINGLE);
                if (w.isFound() && isStableScreenAnchorFlow(TemplatesEnum.GAME_HOME_WORLD)) {
                    taps.tapInside(w);
                    interruptibleWait(2000);
                    if (searcher.locatePattern(TemplatesEnum.GAME_HOME_FURNACE,
                            SearchConfigConstants.DEFAULT_SINGLE).isFound()) return;
                }
            } else if (target == LaunchPoint.WORLD && atHome) {
                ImageSearchResultData h = searcher.locatePattern(TemplatesEnum.GAME_HOME_FURNACE,
                        SearchConfigConstants.DEFAULT_SINGLE);
                if (h.isFound() && isStableScreenAnchorFlow(TemplatesEnum.GAME_HOME_FURNACE)) {
                    taps.tapInside(h);
                    interruptibleWait(2000);
                    if (searcher.locatePattern(TemplatesEnum.GAME_HOME_WORLD,
                            SearchConfigConstants.DEFAULT_SINGLE).isFound()) return;
                }
            }

            // unknown screen - go back
            if (!atHome && !atWorld) {
                broadcastDebug("Unknown screen - back (" + pass + "/" + budget + ")");
                emu.pressBack(device);
                interruptibleWait(300);
            }
            pass++;
        }
        throw new HomeNotFoundException("Home not found after " + budget + " attempts");
    }

    // Require one immediate re-check before tapping a home/world anchor to reduce transient mis-taps.
    private boolean isStableScreenAnchorFlow(TemplatesEnum anchorTemplate) {
        interruptibleWait(120);
        return searcher.locatePattern(anchorTemplate, SearchConfigConstants.DEFAULT_SINGLE).isFound();
    }

    // ── logging shortcuts ────────────────────────────────────────────

    private void broadcastInfo(String msg) {
        log.info(accountName + " - " + msg);
        logs.emit(TpMessageSeverityEnum.INFO, "NavigationHelper", accountName, msg);
    }

    private void broadcastWarn(String msg) {
        log.warn(accountName + " - " + msg);
        logs.emit(TpMessageSeverityEnum.WARNING, "NavigationHelper", accountName, msg);
    }

    private void broadcastDebug(String msg) {
        log.debug(accountName + " - " + msg);
        logs.emit(TpMessageSeverityEnum.DEBUG, "NavigationHelper", accountName, msg);
    }

    private void interruptibleWait(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private enum ScreenState { HOME, WORLD, RECONNECT, UNKNOWN }
    public enum AllianceMenu { WAR, CHESTS, TERRITORY, SHOP, TECH, HELP, TRIUMPH }
    public enum EventMenu { HERO_MISSION, MERCENARY, ALLIANCE_CHAMPIONSHIP, ALLIANCE_MOBILIZATION, TUNDRA_TRUCK }
}
