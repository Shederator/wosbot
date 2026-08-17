package dev.frostguard.engine.listener.task.impl;

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
import dev.frostguard.vision.convert.RegexNumberParser;

/**
 * Bearguard demo task: navigates to the Lancer Camp from wherever the account
 * currently is, swipes right to find Monument, taps it, then runs the full
 * post-badge-tap Monument chain -- claim rewards, Fragment Backpack, Tundra
 * Albums milestone chests, Alliance Trade. The claim/backpack/albums/trade
 * logic below is ported straight from MonumentRoutine.java (already
 * live-verified there) -- this task's only original contribution is the
 * Lancer-relative navigation to actually reach the badge, which
 * MonumentRoutine's own pan-search never reliably did.
 *
 * <p>getRequiredStartLocation() = HOME is what does the "get me to my city,
 * not the world map" part -- the engine's own screen-verification runs BEFORE
 * execute() and forces the account onto the City/Home view no matter where it
 * started.
 *
 * <p>Runs once per manual trigger, then disables itself.
 */
public class bg_gotolancer extends DelayedTask {

    // ========== Lancer-relative Monument navigation (matt/2026-08-14) ==========

    // Same coordinates as TrainingRoutine.LANCER_AREA_VALUE -- the Lancer row
    // in the left-menu City queue list.
    private static final PointData LANCER_AREA_TOP_LEFT = new PointData(161, 636);
    private static final PointData LANCER_AREA_BOTTOM_RIGHT = new PointData(289, 664);

    // Same coordinates as TrainingRoutine.TRAINING_CAMP_TAP_MIN/MAX_VALUE --
    // taps the camp building itself once the camera has centered on it.
    private static final PointData CAMP_TAP_TOP_LEFT = new PointData(310, 650);
    private static final PointData CAMP_TAP_BOTTOM_RIGHT = new PointData(450, 730);

    // After confirming Lancer Camp, wait 5s then swipe right (drag finger
    // right-to-left, which pans the camera view rightward) to bring Monument
    // into view. 300px live-confirmed by matt as landing correctly.
    private static final int POST_LANCER_WAIT_MS = 5000;
    private static final PointData SWIPE_RIGHT_START = new PointData(550, 700);
    private static final PointData SWIPE_RIGHT_END = new PointData(250, 700);
    private static final int SWIPE_RIGHT_DISTANCE_PX = SWIPE_RIGHT_START.getX() - SWIPE_RIGHT_END.getX();
    private static final int SWIPE_DURATION_MS = 400;

    // matt/2026-08-14: hardcoded pixel-box guesses for the Monument badge missed twice and
    // one miss cascaded into a blind close-X tap opening the real Events tab. Replaced with
    // the template search MonumentRoutine.java already uses (MONUMENT_REWARD_BADGE) plus a
    // post-tap verify -- see execute() below.
    private static final int POST_SWIPE_WAIT_MS = 1000;

    // ========== Everything below is ported from MonumentRoutine.java (already
    // live-verified there 2026-08-12) -- the full post-badge-tap chain. ==========

    private static final PointData[] KNOWN_STRAY_PANEL_CLOSE_SPOTS = {
            new PointData(690, 358),
            new PointData(665, 258),
    };

    private static final PointData MODAL_CLOSE_X = new PointData(662, 157);
    private static final PointData ATLAS_BACK_ARROW = new PointData(41, 52);
    private static final int MAX_CLAIM_LOOPS = 10;

    private static final PointData ALBUMS_BACK_ARROW = new PointData(41, 52);
    private static final PointData ALBUMS_FRAGMENT_BACKPACK_BTN = new PointData(626, 1197);
    private static final PointData ALBUMS_ALLIANCE_TRADE_BTN = new PointData(448, 1197);

    private static final PointData BACKPACK_CLOSE_X = new PointData(662, 138);
    private static final PointData BACKPACK_TITLE_TL = new PointData(215, 105);
    private static final PointData BACKPACK_TITLE_BR = new PointData(505, 145);
    private static final PointData ATLAS_GRID_FRAGMENT_BACKPACK_BTN = new PointData(470, 1275);

    private static final PointData[] BACKPACK_ICON_CANDIDATES = {
            new PointData(360, 280),
            new PointData(220, 548), new PointData(360, 548), new PointData(490, 548),
            new PointData(360, 587),
            new PointData(360, 765),
    };
    private static final int BACKPACK_BADGE_Y_OFFSET = 62;
    private static final int BACKPACK_BADGE_HALF_WIDTH = 45;
    private static final int BACKPACK_BADGE_HALF_HEIGHT = 18;
    private static final int BACKPACK_MAX_TOTAL_OPENS = 40;

    private static final PointData PACK_DETAIL_ENABLE_BTN = new PointData(358, 905);
    private static final PointData REWARD_REVEAL_TAP_ANYWHERE = new PointData(358, 1198);

    private static final PointData[] MILESTONE_CHEST_CANDIDATES = {
            new PointData(245, 178),
            new PointData(340, 178),
            new PointData(428, 178),
    };
    private static final PointData MILESTONE_REWARDS_TAP_ANYWHERE = new PointData(360, 1198);
    private static final int MAX_MILESTONE_CHEST_CLAIMS = 6;

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

    private static final int PANEL_SETTLE_MS = 1200;
    private static final int ACTION_SETTLE_MS = 900;
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

    public bg_gotolancer(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
        reschedule(LocalDateTime.now());
    }

    @Override
    protected Object getDistinctKey() {
        return "bg_gotolancer";
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    protected void execute() {
        logInfo("bg_gotolancer | On City view. Opening left menu and selecting Lancer.");

        marchHelper.openLeftMenuCitySection(true);
        sleepTask(500);

        tapRandomPoint(LANCER_AREA_TOP_LEFT, LANCER_AREA_BOTTOM_RIGHT, 1, 500);
        tapRandomPoint(CAMP_TAP_TOP_LEFT, CAMP_TAP_BOTTOM_RIGHT, 1, 300);

        logInfo("bg_gotolancer | On Lancer Camp. Waiting " + POST_LANCER_WAIT_MS
                + "ms, then swiping right " + SWIPE_RIGHT_DISTANCE_PX + "px to bring Monument into view.");
        sleepTask(POST_LANCER_WAIT_MS);

        swipe(SWIPE_RIGHT_START, SWIPE_RIGHT_END, SWIPE_DURATION_MS);
        sleepTask(POST_SWIPE_WAIT_MS);

        // matt/2026-08-14: replaced the hardcoded pixel-box guess (which missed and then
        // let a blind close-X tap cascade into opening the real Events tab) with the same
        // template search MonumentRoutine.java already uses to find this exact badge. Then
        // VERIFY the tap actually did something -- if the badge is still detectable
        // afterward, nothing opened, and this stops here rather than touching anything else.
        ImageSearchResultData badge = templateSearchHelper.locatePattern(
                TemplatesEnum.MONUMENT_REWARD_BADGE, SearchConfigConstants.RESILIENT);
        if (!badge.isFound()) {
            logWarning("bg_gotolancer | Monument reward badge not found on screen after the swipe -- "
                    + "stopping here rather than guessing blind. Disabling self.");
            setRecurring(false);
            return;
        }

        tapPoint(badge.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        ImageSearchResultData badgeStillThere = templateSearchHelper.locatePattern(
                TemplatesEnum.MONUMENT_REWARD_BADGE, SearchConfigConstants.QUICK_SEARCH);
        if (badgeStillThere.isFound()) {
            logWarning("bg_gotolancer | Tapped the badge at " + badge.getPoint()
                    + " but it's still detectable on screen afterward -- nothing opened. "
                    + "Stopping here instead of cascading into the rest of the chain blind. Disabling self.");
            setRecurring(false);
            return;
        }

        logInfo("bg_gotolancer | Monument badge opened (confirmed: badge no longer detectable). "
                + "Running the full claim/backpack/albums/trade chain.");

        logInfo("bg_gotolancer | Claiming any ready rows.");
        claimAllReadyRows();

        tapPoint(MODAL_CLOSE_X);
        sleepTask(PANEL_SETTLE_MS);

        tapPoint(ATLAS_BACK_ARROW);
        sleepTask(ACTION_SETTLE_MS);

        // matt/2026-08-14: this was wrong -- called before the back-arrow tap, at
        // ATLAS_GRID_FRAGMENT_BACKPACK_BTN (470,1275), a coordinate that doesn't correspond
        // to a real button on the screen the Monument badge actually leads to. Live-checked
        // by hand: the real "Fragment Backpack" panel (title confirmed, 6 owned packs
        // visible) is reached from THIS screen -- the real Tundra Albums hub, after the
        // back-arrow -- via the button at (628,1197), matching the ALBUMS_FRAGMENT_BACKPACK_BTN
        // constant that was defined but never actually used. Moved the call here and pointed
        // it at the right button.
        logInfo("bg_gotolancer | On Tundra Albums. Processing the shared Fragment Backpack.");
        processFragmentBackpack();

        logInfo("bg_gotolancer | Checking the milestone chest track.");
        claimMilestoneChestsIfReady();

        logInfo("bg_gotolancer | Processing Alliance Trade.");
        tapPoint(ALBUMS_ALLIANCE_TRADE_BTN);
        sleepTask(PANEL_SETTLE_MS);
        processAllianceTradeRequests();
        processAllianceTradeSends();
        tapPoint(TRADE_CLOSE_X);
        sleepTask(ACTION_SETTLE_MS);

        tapPoint(ALBUMS_BACK_ARROW);
        sleepTask(ACTION_SETTLE_MS);

        logInfo("bg_gotolancer | Full chain complete. Disabling self (one-shot demo).");
        setRecurring(false);
    }

    private void claimAllReadyRows() {
        for (int i = 0; i < MAX_CLAIM_LOOPS; i++) {
            var claimBtn = templateSearchHelper.locatePattern(
                    TemplatesEnum.MONUMENT_ATLAS_CLAIM_BUTTON, dev.frostguard.engine.nav.SearchConfigConstants.QUICK_SEARCH);
            if (!claimBtn.isFound()) {
                logInfo("bg_gotolancer | No more Claim buttons visible (" + i + " claimed).");
                return;
            }
            tapPoint(claimBtn.getPoint());
            sleepTask(ACTION_SETTLE_MS);
            if (i == MAX_CLAIM_LOOPS - 1) {
                logWarning("bg_gotolancer | Hit the claim-loop safety cap (" + MAX_CLAIM_LOOPS + ").");
            }
        }
    }

    private void claimMilestoneChestsIfReady() {
        for (int claimed = 0; claimed < MAX_MILESTONE_CHEST_CLAIMS; claimed++) {
            boolean claimedThisPass = false;
            for (PointData candidate : MILESTONE_CHEST_CANDIDATES) {
                tapPoint(candidate);
                sleepTask(600);

                String popupTitle = stringHelper.attemptRecognition(
                        new PointData(200, 260), new PointData(520, 340),
                        2, 150L, PANEL_TITLE_OCR_SETTINGS,
                        s -> s != null && !s.isBlank(),
                        s -> s);
                if (popupTitle != null && popupTitle.toLowerCase().contains("reward")) {
                    logInfo("bg_gotolancer | Milestone chest ready at " + candidate + " -- claimed. Rewards: '"
                            + popupTitle + "'.");
                    tapPoint(MILESTONE_REWARDS_TAP_ANYWHERE);
                    sleepTask(ACTION_SETTLE_MS);
                    claimedThisPass = true;
                    break;
                }
            }
            if (!claimedThisPass) {
                if (claimed == 0) {
                    logInfo("bg_gotolancer | No milestone chest currently ready.");
                } else {
                    logInfo("bg_gotolancer | Milestone chest track exhausted after " + claimed + " claim(s).");
                }
                return;
            }
        }
        logWarning("bg_gotolancer | Hit the milestone-chest safety cap (" + MAX_MILESTONE_CHEST_CLAIMS + ").");
    }

    private void processFragmentBackpack() {
        tapPoint(ALBUMS_FRAGMENT_BACKPACK_BTN);
        sleepTask(PANEL_SETTLE_MS);

        String panelTitle = stringHelper.attemptRecognition(
                BACKPACK_TITLE_TL, BACKPACK_TITLE_BR,
                2, 150L, PANEL_TITLE_OCR_SETTINGS,
                s -> s != null && !s.isBlank(),
                s -> s);
        if (panelTitle == null || !panelTitle.toLowerCase().contains("fragment")) {
            logWarning("bg_gotolancer | Fragment Backpack panel not confirmed after tapping "
                    + ATLAS_GRID_FRAGMENT_BACKPACK_BTN + " (read: '" + panelTitle
                    + "') -- skipping the backpack pass this run rather than guessing blindly on the wrong screen.");
            return;
        }

        int opened = 0;
        while (opened < BACKPACK_MAX_TOTAL_OPENS) {
            if (!waitForFragmentBackpackPanel()) {
                logWarning("bg_gotolancer | Fragment Backpack panel didn't come back after the last pack open "
                        + "-- stopping rather than reading a stale screen. Opened " + opened + " total.");
                break;
            }

            PointData target = findAnyOwnedPackIcon();
            if (target == null) {
                logInfo("bg_gotolancer | No more owned packs found. Opened " + opened + " total.");
                break;
            }

            opened++;
            logInfo("bg_gotolancer | Opening pack " + opened + " at " + target + ".");
            tapPoint(target);
            sleepTask(ACTION_SETTLE_MS);
            tapPoint(PACK_DETAIL_ENABLE_BTN);
            sleepTask(PACK_OPEN_SETTLE_MS);

            tapPoint(REWARD_REVEAL_TAP_ANYWHERE);
            sleepTask(PACK_OPEN_SETTLE_MS);
            tapPoint(REWARD_REVEAL_TAP_ANYWHERE);
            sleepTask(PACK_OPEN_SETTLE_MS);
        }

        if (opened >= BACKPACK_MAX_TOTAL_OPENS) {
            logWarning("bg_gotolancer | Hit the total-opens safety cap (" + BACKPACK_MAX_TOTAL_OPENS + ").");
        }

        tapPoint(BACKPACK_CLOSE_X);
        sleepTask(ACTION_SETTLE_MS);
    }

    private PointData findAnyOwnedPackIcon() {
        for (PointData candidate : BACKPACK_ICON_CANDIDATES) {
            PointData badgeTl = new PointData(candidate.getX() - BACKPACK_BADGE_HALF_WIDTH,
                    candidate.getY() + BACKPACK_BADGE_Y_OFFSET - BACKPACK_BADGE_HALF_HEIGHT);
            PointData badgeBr = new PointData(candidate.getX() + BACKPACK_BADGE_HALF_WIDTH,
                    candidate.getY() + BACKPACK_BADGE_Y_OFFSET + BACKPACK_BADGE_HALF_HEIGHT);
            Integer owned = readNumberValue(badgeTl, badgeBr, OWNED_COUNT_OCR_SETTINGS);
            if (owned != null && owned > 0) {
                return candidate;
            }
        }
        return null;
    }

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
                logInfo("bg_gotolancer | No My Requests left today (or couldn't read the counter). Moving on.");
                return;
            }

            tapPoint(MY_REQUESTS_REQUEST_BTN);
            sleepTask(PANEL_SETTLE_MS);
            tapPoint(PIECE_PICKER_REQUEST_BTN);
            sleepTask(ACTION_SETTLE_MS);
            tapPoint(PIECE_PICKER_TIPS_CONFIRM);
            sleepTask(ACTION_SETTLE_MS);
        }
        logWarning("bg_gotolancer | Hit the request-loop safety cap (" + MAX_REQUEST_LOOPS + ").");
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

            if (owned != null && owned >= 2) {
                logInfo("bg_gotolancer | Ally Requests row " + row + ": owned " + owned + ", sending.");
                tapPoint(sendBtn);
                sleepTask(ACTION_SETTLE_MS);
            } else {
                logInfo("bg_gotolancer | Ally Requests row " + row + ": owned " + owned + ", skipping.");
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
