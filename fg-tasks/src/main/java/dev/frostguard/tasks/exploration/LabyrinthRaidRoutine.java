package dev.frostguard.tasks.exploration;

import java.time.LocalDateTime;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;

/**
 * matt/2026-08-14: "the new labyrinth" -- a separate stage-based raid system layered on top of
 * the classic Land of Heroes / Cave of Monsters / Charm Mine zones ({@link DailyLabyrinthRoutine}
 * already owns those). Two zones live-confirmed: Research Center (Tech stats only) and Gear Forge
 * (Chief Gear stats only), each a straight numbered stage ladder (1-1, 1-2, ... 5-4, ...).
 *
 * <p>
 * Two very different buttons live at the exact same screen position depending on state:
 * <ul>
 * <li><b>Raid</b> (green) -- an INSTANT, FREE, no-battle catch-up claim for every stage at or
 * below your already-proven best result. Zero risk: confirmed live, tapping it just pops a "Raid
 * Rewards" popup with a Claim button -- no attempt spent, no chance of failure.</li>
 * <li><b>Challenge</b> (blue) -- a REAL battle attempt against the actual next uncleared stage,
 * spending one of a small daily pool (5 seen live). Matt's explicit call: this needs a real
 * stat/power comparison ("tail of the tape") before it's safe to automate -- deliberately NOT
 * built here. This routine only ever taps Raid, never Challenge.</li>
 * </ul>
 *
 * <p>
 * <b>Live-verified 2026-08-14</b>: Research Center Raid-claimed from Stage 1-1 straight through
 * to its proven best (4-2), landing cleanly on 4-3 with a Challenge button showing -- confirmed
 * the loop correctly stops there instead of touching Challenge.
 */
public class LabyrinthRaidRoutine extends DelayedTask {

    private static final int IDLE_RECHECK_HOURS = 24;
    private static final int PANEL_SETTLE_MS = 1200;
    private static final int ACTION_SETTLE_MS = 800;
    private static final int MAX_RAID_CLAIMS_PER_ZONE = 15;

    // -- Left-menu entry point (same panel Life Essence/Monument already use) --
    private static final PointData LEFT_MENU_SCROLL_START = new PointData(220, 700);
    private static final PointData LEFT_MENU_SCROLL_END = new PointData(220, 300);
    /** Whichever Labyrinth-zone row is currently showing in the collapsed panel -- tapping it
     *  opens the SAME "The Labyrinth" hub map regardless of which zone the row itself names. */
    private static final PointData LABYRINTH_ROW_ICON = new PointData(404, 836);

    // -- Labyrinth hub map (both zones visible at once) --
    private static final PointData RESEARCH_CENTER_BUILDING = new PointData(157, 700);
    private static final PointData GEAR_FORGE_BUILDING = new PointData(578, 780);
    private static final PointData HUB_BACK_ARROW = new PointData(44, 40);

    // -- Zone screen (Research Center / Gear Forge -- identical layout, different skin) --
    private static final PointData ZONE_CLOSE_X = new PointData(682, 40);
    private static final PointData ZONE_ACTION_BUTTON = new PointData(358, 1218);
    private static final PointData ZONE_ACTION_TEXT_TL = new PointData(280, 1195);
    private static final PointData ZONE_ACTION_TEXT_BR = new PointData(440, 1245);

    // -- Raid Rewards popup --
    private static final PointData RAID_CLAIM_BUTTON = new PointData(358, 861);

    private static final TesseractSettingsData BUTTON_TEXT_OCR_SETTINGS = TesseractSettingsData.assembler()
            .stripBackground(true)
            .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
            .pageAnalysis(TesseractSettingsData.PageAnalysis.SINGLE_LINE)
            .build();

    /** matt/2026-08-14: every coordinate above is hardcoded on purpose -- these are all STATIC
     *  UI elements (button positions never move), so there's no reason to burn an OCR/template
     *  pass finding them, same "hard-code what's static" call matt made on Gem Shop tonight. The
     *  ONE thing that genuinely varies is the button's TEXT (Raid vs Challenge), which is the only
     *  thing actually OCR'd. Every tap still goes through a small jitter box rather than the exact
     *  same pixel every time, matching this codebase's standing tap-jitter convention. */
    private static final int TAP_JITTER_PX = 6;

    private void tapWithJitter(PointData center) {
        PointData topLeft = new PointData(center.col() - TAP_JITTER_PX, center.row() - TAP_JITTER_PX);
        PointData bottomRight = new PointData(center.col() + TAP_JITTER_PX, center.row() + TAP_JITTER_PX);
        tapRandomPoint(topLeft, bottomRight);
    }

    public LabyrinthRaidRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    @Override
    public boolean provideDailyMissionProgress() {
        return true;
    }

    @Override
    protected void execute() {
        navigateToLabyrinthHub();

        int totalClaimed = 0;
        totalClaimed += raidZone("Research Center", RESEARCH_CENTER_BUILDING);
        totalClaimed += raidZone("Gear Forge", GEAR_FORGE_BUILDING);

        tapWithJitter(HUB_BACK_ARROW);
        sleepTask(ACTION_SETTLE_MS);

        StatisticsService.obtain().addToCounter(profile, "Labyrinth Raid Claims", totalClaimed);
        logInfo(logLine("Pass complete. Claimed " + totalClaimed + " total raid reward(s) across both zones. "
                + "Rechecking in " + IDLE_RECHECK_HOURS + " hours."));
        reschedule(LocalDateTime.now().plusHours(IDLE_RECHECK_HOURS));
    }

    private void navigateToLabyrinthHub() {
        marchHelper.openLeftMenuCitySection(true);
        sleepTask(500);
        swipe(LEFT_MENU_SCROLL_START, LEFT_MENU_SCROLL_END);
        sleepTask(700);
        tapWithJitter(LABYRINTH_ROW_ICON);
        sleepTask(PANEL_SETTLE_MS);
    }

    /**
     * Repeatedly taps Raid + Claim on one zone until either a Challenge button appears (the real
     * frontier stage -- deliberately left alone) or the safety cap is hit.
     *
     * @return number of Raid claims made in this zone this pass
     */
    private int raidZone(String zoneName, PointData buildingPoint) {
        tapWithJitter(buildingPoint);
        sleepTask(PANEL_SETTLE_MS);

        int claimed = 0;
        for (int i = 0; i < MAX_RAID_CLAIMS_PER_ZONE; i++) {
            String buttonText = stringHelper.attemptRecognition(
                    ZONE_ACTION_TEXT_TL, ZONE_ACTION_TEXT_BR,
                    3, 200L, BUTTON_TEXT_OCR_SETTINGS,
                    s -> s != null && !s.isBlank(),
                    s -> s);

            if (buttonText == null) {
                logWarning(logLine(zoneName + ": could not read the action button text -- stopping here "
                        + "rather than tapping blind."));
                break;
            }

            String normalized = buttonText.toLowerCase();
            if (normalized.contains("raid")) {
                tapWithJitter(ZONE_ACTION_BUTTON);
                sleepTask(ACTION_SETTLE_MS);
                tapWithJitter(RAID_CLAIM_BUTTON);
                sleepTask(ACTION_SETTLE_MS);
                claimed++;
                logInfo(logLine(zoneName + ": Raid-claimed (" + claimed + " this pass)."));
            } else if (normalized.contains("challenge")) {
                logInfo(logLine(zoneName + ": reached the real Challenge frontier (real battle, limited "
                        + "daily attempts) -- leaving this for the stat-comparison pass, not touching it."));
                break;
            } else {
                logWarning(logLine(zoneName + ": action button read as '" + buttonText + "' -- neither "
                        + "Raid nor Challenge recognized, stopping here rather than guessing."));
                break;
            }
        }

        if (claimed >= MAX_RAID_CLAIMS_PER_ZONE) {
            logWarning(logLine(zoneName + ": hit the safety cap (" + MAX_RAID_CLAIMS_PER_ZONE
                    + " claims) -- stopping this pass."));
        }

        tapWithJitter(ZONE_CLOSE_X);
        sleepTask(ACTION_SETTLE_MS);
        return claimed;
    }

    private String logLine(String note) {
        return "LabyrinthRaidRoutine | " + note;
    }
}
