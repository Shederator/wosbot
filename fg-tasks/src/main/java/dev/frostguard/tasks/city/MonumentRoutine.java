package dev.frostguard.tasks.city;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.vision.convert.RegexNumberParser;

/**
 * Task responsible for the Monument / "Explore the World" Atlas feature: claiming
 * ready milestone rewards, cracking open owned Scene Fragment Packs from the shared
 * Fragment Backpack, and running the daily Alliance Trade puzzle-piece requests/sends.
 *
 * <p>
 * matt/2026-08-12. Several navigation designs were tried and abandoned this session
 * (a "deterministic camera reset" that turned out not to be deterministic, an
 * anchor-search-and-pan chain built on a misdiagnosis) before finding the real root
 * cause: this routine required {@code LaunchPoint.WORLD} (the zoomed-out strategic
 * map, where every player's city is a small generic icon) instead of
 * {@code LaunchPoint.HOME} (the close-up view where Furnace/Lancer Camp/Monument
 * actually render as buildings) -- the one every other building task in this
 * codebase already uses. Once that's fixed, Monument's reward badge is directly
 * visible on screen with NO panning or camera anchoring needed at all -- confirmed by
 * hand, tap by tap, screenshot after every single step, until the badge was
 * genuinely gone. This is the flow that walkthrough proved, nothing more:
 *
 * <pre>
 * World/Home (badge visible, no panning) -> tap badge -> quest-list popup for
 * whichever Atlas-family category currently has something ready (Labyrinth,
 * Explore the World, etc. -- shares the same badge and popup skin) -> claim any
 * ready rows -> X close -> reveals that category's Atlas grid -> Fragment Backpack
 * (shared across ALL categories, confirmed live: the same panel lists Labyrinth,
 * Rekindled Flames, Song of Heroes, etc. as separate rows) -> open every owned pack
 * -> close -> back arrow -> Tundra Albums hub -> Alliance Trade -> close -> back
 * arrow -> World/Home, badge cleared.
 * </pre>
 *
 * <p>
 * <b>Live-verified 2026-08-12, by hand, every step:</b> badge tap, Claim on a real
 * ready row (Charm Mine 2-10 -> advanced to 4-10), X close revealing the Atlas grid,
 * Fragment Backpack showing a real owned pack ("The Labyrinth", 2 owned), opening it
 * (Enable, quantity already at max), the reward-reveal screen, tap-anywhere-to-close,
 * the pack fully disappearing from the backpack list afterward, back arrow to Tundra
 * Albums (progress advanced 797/1347 -> 808/1347 across two live tests), back arrow
 * to World/Home -- badge confirmed gone. The automated version below is this exact
 * sequence with search-based confirmation at each step instead of blind taps.
 *
 * <p>
 * <b>NOT live-verified</b> (deliberately, to avoid spending matt's limited daily
 * Alliance Trade requests/sends while testing): the Alliance Trade request/send
 * logic. Built from matt's screenshots at the same resolution. Flagged honestly, not
 * assumed correct.
 *
 * <p>
 * <b>Known gaps (not built):</b> the Tundra Albums hub's own top milestone-chest
 * progress track ("sometimes they wiggle, click to open") is not handled. Alliance
 * Trade's Ally Requests list is only scanned for rows already visible on open (no
 * deep-scroll dedup), matching the same scroll-list limitation already known in
 * ChatCaptureRoutine.
 */
public class MonumentRoutine extends DelayedTask {

    // ========== Stray-popup clearing (game-rendered modals ignore Android back) ==========
    /** Every close-X position observed so far across different tasks' leftover
     *  panels (Resource Stockpile Scan's "Overview", the "Resource &amp; Speedup
     *  Summary" panel) -- tapped every round alongside back presses. Not exhaustive
     *  by design; the round-and-recheck loop in clearStrayPopups() is what actually
     *  makes this generic, this list just gives it a head start on known cases. */
    private static final PointData[] KNOWN_STRAY_PANEL_CLOSE_SPOTS = {
            new PointData(690, 358),
            new PointData(665, 258),
    };

    // ========== Quest-list modal + Atlas grid (shared skin across categories) ==========
    private static final PointData MODAL_CLOSE_X = new PointData(662, 157);
    private static final PointData ATLAS_BACK_ARROW = new PointData(41, 52);
    private static final int MAX_CLAIM_LOOPS = 10;

    // ========== Tundra Albums hub ==========
    private static final PointData ALBUMS_BACK_ARROW = new PointData(41, 52);
    private static final PointData ALBUMS_FRAGMENT_BACKPACK_BTN = new PointData(626, 1197);
    private static final PointData ALBUMS_ALLIANCE_TRADE_BTN = new PointData(448, 1197);

    // ========== Fragment Backpack panel (shared across all Atlas categories) ==========
    private static final PointData BACKPACK_CLOSE_X = new PointData(662, 138);
    private static final PointData BACKPACK_TITLE_TL = new PointData(215, 105);
    private static final PointData BACKPACK_TITLE_BR = new PointData(505, 145);
    /** First pack row icon tap point. Rows below repeat at ~ROW_SPACING. */
    private static final PointData BACKPACK_FIRST_ROW_ICON = new PointData(358, 284);
    private static final PointData BACKPACK_FIRST_ROW_OWNED_TL = new PointData(320, 315);
    private static final PointData BACKPACK_FIRST_ROW_OWNED_BR = new PointData(400, 345);
    private static final int BACKPACK_ROW_SPACING = 227;
    private static final int BACKPACK_MAX_ROWS = 4;
    private static final int BACKPACK_MAX_OPENS_PER_ROW = 20;

    // ========== Fragment Pack detail (Enable) screen ==========
    /** Quantity defaults to the full owned count already -- one Enable tap consumes
     *  all of them (confirmed live twice: stacks of 2 fully consumed in one tap). */
    private static final PointData PACK_DETAIL_ENABLE_BTN = new PointData(358, 905);
    /** "Tap anywhere to close" reward-reveal screen -- tap near the text, not dead-center. */
    private static final PointData REWARD_REVEAL_TAP_ANYWHERE = new PointData(358, 1198);

    // ========== Alliance Trade panel ==========
    private static final PointData TRADE_CLOSE_X = new PointData(662, 155);
    private static final PointData MY_REQUESTS_REQUEST_BTN = new PointData(358, 370);
    private static final PointData MY_REQUESTS_LEFT_TL = new PointData(200, 268);
    private static final PointData MY_REQUESTS_LEFT_BR = new PointData(560, 300);
    private static final PointData PIECE_PICKER_REQUEST_BTN = new PointData(543, 891);
    private static final PointData PIECE_PICKER_TIPS_CONFIRM = new PointData(358, 789);
    private static final int MAX_REQUEST_LOOPS = 5;

    private static final PointData ALLY_FIRST_ROW_SEND_BTN = new PointData(583, 712);
    private static final PointData ALLY_FIRST_ROW_OWNED_TL = new PointData(580, 665);
    private static final PointData ALLY_FIRST_ROW_OWNED_BR = new PointData(700, 695);
    private static final int ALLY_ROW_SPACING = 237;
    private static final int ALLY_MAX_VISIBLE_ROWS = 3;

    private static final int IDLE_RECHECK_MINUTES = 60;
    private static final int PANEL_SETTLE_MS = 1200;
    private static final int ACTION_SETTLE_MS = 900;
    /** The reward-reveal animation after Enable runs noticeably longer than a normal
     *  panel transition -- matt/2026-08-12, root cause of an earlier stuck-owned-count bug. */
    private static final int PACK_OPEN_SETTLE_MS = 1800;

    private static final TesseractSettingsData PANEL_TITLE_OCR_SETTINGS = TesseractSettingsData.assembler()
            .stripBackground(true)
            .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ ")
            .pageAnalysis(TesseractSettingsData.PageAnalysis.SINGLE_LINE)
            .build();

    private static final TesseractSettingsData OWNED_COUNT_OCR_SETTINGS = TesseractSettingsData.assembler()
            .stripBackground(true)
            .charWhitelist("OwnedOWNED:0123456789 ")
            .pageAnalysis(TesseractSettingsData.PageAnalysis.SINGLE_LINE)
            .build();

    public MonumentRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    public boolean provideDailyMissionProgress() {
        return true;
    }

    @Override
    protected void execute() {
        // matt/2026-08-12: a prior task (Resource Stockpile Scan, in particular) can
        // leave its own popup open when Monument's turn comes up. Clear it first,
        // unconditionally, before searching for anything.
        clearStrayPopups();

        ImageSearchResultData badge = templateSearchHelper.locatePattern(
                TemplatesEnum.MONUMENT_REWARD_BADGE, SearchConfigConstants.RESILIENT);
        if (!badge.isFound()) {
            logInfo(logLine("No rewards-ready badge on the Home screen right now. Rechecking in "
                    + IDLE_RECHECK_MINUTES + " minutes."));
            reschedule(LocalDateTime.now().plusMinutes(IDLE_RECHECK_MINUTES));
            return;
        }

        tapPoint(badge.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        logInfo(logLine("Badge opened. Claiming any ready rows."));
        claimAllReadyRows();

        tapPoint(MODAL_CLOSE_X);
        sleepTask(PANEL_SETTLE_MS);

        logInfo(logLine("Processing the shared Fragment Backpack."));
        processFragmentBackpack();

        tapPoint(ATLAS_BACK_ARROW);
        sleepTask(ACTION_SETTLE_MS);

        logInfo(logLine("On Tundra Albums. Processing Alliance Trade."));
        tapPoint(ALBUMS_ALLIANCE_TRADE_BTN);
        sleepTask(PANEL_SETTLE_MS);
        processAllianceTradeRequests();
        processAllianceTradeSends();
        tapPoint(TRADE_CLOSE_X);
        sleepTask(ACTION_SETTLE_MS);

        tapPoint(ALBUMS_BACK_ARROW);
        sleepTask(ACTION_SETTLE_MS);

        StatisticsService.obtain().addToCounter(profile, "Monument Pass Completed", 1);
        logInfo(logLine("Monument pass complete. Rechecking in " + IDLE_RECHECK_MINUTES + " minutes."));
        reschedule(LocalDateTime.now().plusMinutes(IDLE_RECHECK_MINUTES));
    }

    private String logLine(String note) {
        return "MonumentRoutine | " + note;
    }

    /**
     * matt/2026-08-12: confirmed live that Resource Stockpile Scan's "Overview"
     * panel does NOT close on a back press -- it's a game-rendered modal, not a
     * native Android view, so the system back key is simply ignored by it. A
     * different task later left a DIFFERENT panel open ("Resource &amp; Speedup
     * Summary") proving one hardcoded close spot will never keep up with however
     * many other tasks can leave something open -- rebuilt as a real loop: repeat
     * (press back several times, tap every known stray-panel close spot) and
     * re-check via search after each round, stopping the moment the badge or a
     * clean Home screen is confirmed.
     */
    private void clearStrayPopups() {
        for (int round = 0; round < 4; round++) {
            for (PointData closeSpot : KNOWN_STRAY_PANEL_CLOSE_SPOTS) {
                tapPoint(closeSpot);
                sleepTask(300);
            }
            for (int i = 0; i < 3; i++) {
                pressBack();
                sleepTask(300);
            }

            boolean clear = templateSearchHelper.locatePattern(
                    TemplatesEnum.MONUMENT_REWARD_BADGE, SearchConfigConstants.QUICK_SEARCH).isFound()
                    || templateSearchHelper.locatePattern(
                    TemplatesEnum.MONUMENT_BUILDING_ANCHOR, SearchConfigConstants.QUICK_SEARCH).isFound();
            if (clear) {
                logInfo(logLine("Screen confirmed clear after " + (round + 1) + " clearing round(s)."));
                return;
            }
        }
        logInfo(logLine("Still couldn't confirm a clear Home view after repeated clearing rounds -- "
                + "proceeding anyway, the badge search right after this will catch a genuinely blocked screen."));
    }

    private void claimAllReadyRows() {
        for (int i = 0; i < MAX_CLAIM_LOOPS; i++) {
            ImageSearchResultData claimBtn = templateSearchHelper.locatePattern(
                    TemplatesEnum.MONUMENT_ATLAS_CLAIM_BUTTON, SearchConfigConstants.QUICK_SEARCH);
            if (!claimBtn.isFound()) {
                logInfo(logLine("No more Claim buttons visible (" + i + " claimed)."));
                return;
            }
            tapPoint(claimBtn.getPoint());
            sleepTask(ACTION_SETTLE_MS);
            if (i == MAX_CLAIM_LOOPS - 1) {
                logWarning(logLine("Hit the claim-loop safety cap (" + MAX_CLAIM_LOOPS + ")."));
            }
        }
    }

    private void processFragmentBackpack() {
        tapPoint(ALBUMS_FRAGMENT_BACKPACK_BTN);
        sleepTask(PANEL_SETTLE_MS);

        for (int row = 0; row < BACKPACK_MAX_ROWS; row++) {
            int rowOffset = row * BACKPACK_ROW_SPACING;
            PointData iconPoint = new PointData(BACKPACK_FIRST_ROW_ICON.getX(),
                    BACKPACK_FIRST_ROW_ICON.getY() + rowOffset);
            PointData ownedTl = new PointData(BACKPACK_FIRST_ROW_OWNED_TL.getX(),
                    BACKPACK_FIRST_ROW_OWNED_TL.getY() + rowOffset);
            PointData ownedBr = new PointData(BACKPACK_FIRST_ROW_OWNED_BR.getX(),
                    BACKPACK_FIRST_ROW_OWNED_BR.getY() + rowOffset);

            openAllOwnedPacksInRow(row, iconPoint, ownedTl, ownedBr);
        }

        tapPoint(BACKPACK_CLOSE_X);
        sleepTask(ACTION_SETTLE_MS);
    }

    private void openAllOwnedPacksInRow(int rowIndex, PointData iconPoint, PointData ownedTl, PointData ownedBr) {
        Integer previousOwned = null;
        for (int opens = 0; opens < BACKPACK_MAX_OPENS_PER_ROW; opens++) {
            if (!waitForFragmentBackpackPanel()) {
                logWarning(logLine("Backpack row " + rowIndex + ": panel didn't come back after "
                        + "the last pack open -- bailing on this row rather than reading a stale screen."));
                return;
            }

            Integer owned = readNumberValue(ownedTl, ownedBr, OWNED_COUNT_OCR_SETTINGS);
            if (owned == null || owned <= 0) {
                if (opens == 0) {
                    logInfo(logLine("Backpack row " + rowIndex + ": nothing owned, skipping."));
                } else {
                    logInfo(logLine("Backpack row " + rowIndex + ": exhausted after " + opens + " opens."));
                }
                return;
            }
            if (previousOwned != null && owned.equals(previousOwned)) {
                logWarning(logLine("Backpack row " + rowIndex + ": owned count stuck at " + owned
                        + " even after confirming the panel was back -- bailing on this row rather than "
                        + "spinning to the safety cap."));
                return;
            }
            previousOwned = owned;

            logInfo(logLine("Backpack row " + rowIndex + ": opening a pack (owned " + owned + ")."));
            tapPoint(iconPoint);
            sleepTask(ACTION_SETTLE_MS);
            tapPoint(PACK_DETAIL_ENABLE_BTN);
            sleepTask(PACK_OPEN_SETTLE_MS);
            tapPoint(REWARD_REVEAL_TAP_ANYWHERE);
            sleepTask(PACK_OPEN_SETTLE_MS);
        }
        logWarning(logLine("Backpack row " + rowIndex + " hit the safety cap ("
                + BACKPACK_MAX_OPENS_PER_ROW + " opens)."));
    }

    /** Waits (with extra retries) for the Fragment Backpack title to actually be back
     *  on screen after a pack-open cycle, instead of assuming a fixed sleep was enough. */
    private boolean waitForFragmentBackpackPanel() {
        for (int attempt = 0; attempt < 3; attempt++) {
            String title = stringHelper.attemptRecognition(
                    BACKPACK_TITLE_TL, BACKPACK_TITLE_BR,
                    2, 150L, PANEL_TITLE_OCR_SETTINGS,
                    s -> s != null && !s.isBlank(),
                    s -> s);
            if (title != null && title.toLowerCase().contains("fragment")) {
                return true;
            }
            sleepTask(PACK_OPEN_SETTLE_MS);
        }
        return false;
    }

    private void processAllianceTradeRequests() {
        for (int i = 0; i < MAX_REQUEST_LOOPS; i++) {
            String leftText = readStringValueSafe(MY_REQUESTS_LEFT_TL, MY_REQUESTS_LEFT_BR);
            Integer requestsLeft = leftText == null ? null : RegexNumberParser.extractByPattern(
                    leftText, Pattern.compile("\\((\\d+)\\s*/"));
            if (requestsLeft == null || requestsLeft <= 0) {
                logInfo(logLine("No My Requests left today (or couldn't read the counter). Moving on."));
                return;
            }

            tapPoint(MY_REQUESTS_REQUEST_BTN);
            sleepTask(PANEL_SETTLE_MS);
            tapPoint(PIECE_PICKER_REQUEST_BTN);
            sleepTask(ACTION_SETTLE_MS);
            // The "Confirm daily requests remaining" Tips dialog only appears the first
            // time per session -- harmless no-op tap if it's not there.
            tapPoint(PIECE_PICKER_TIPS_CONFIRM);
            sleepTask(ACTION_SETTLE_MS);
        }
        logWarning(logLine("Hit the request-loop safety cap (" + MAX_REQUEST_LOOPS + ")."));
    }

    private void processAllianceTradeSends() {
        for (int row = 0; row < ALLY_MAX_VISIBLE_ROWS; row++) {
            int rowOffset = row * ALLY_ROW_SPACING;
            PointData sendBtn = new PointData(ALLY_FIRST_ROW_SEND_BTN.getX(),
                    ALLY_FIRST_ROW_SEND_BTN.getY() + rowOffset);
            PointData ownedTl = new PointData(ALLY_FIRST_ROW_OWNED_TL.getX(),
                    ALLY_FIRST_ROW_OWNED_TL.getY() + rowOffset);
            PointData ownedBr = new PointData(ALLY_FIRST_ROW_OWNED_BR.getX(),
                    ALLY_FIRST_ROW_OWNED_BR.getY() + rowOffset);

            String ownedText = readStringValueSafe(ownedTl, ownedBr);
            Integer owned = ownedText == null ? null : RegexNumberParser.extractByPattern(
                    ownedText, Pattern.compile("(\\d+)"));

            // matt, 2026-08-12: only send when a duplicate is actually owned (>=2) --
            // "Owned: 1" means it's their only copy, leave it alone.
            if (owned != null && owned >= 2) {
                logInfo(logLine("Ally Requests row " + row + ": owned " + owned + ", sending."));
                tapPoint(sendBtn);
                sleepTask(ACTION_SETTLE_MS);
            } else {
                logInfo(logLine("Ally Requests row " + row + ": owned " + owned + ", skipping."));
            }
        }
    }

    private String readStringValueSafe(PointData tl, PointData br) {
        return stringHelper.attemptRecognition(
                tl, br, 2, 150L, OWNED_COUNT_OCR_SETTINGS,
                s -> s != null && !s.isBlank(),
                s -> s);
    }
}
