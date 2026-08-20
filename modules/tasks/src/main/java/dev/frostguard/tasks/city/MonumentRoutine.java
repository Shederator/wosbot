package dev.frostguard.tasks.city;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.SizeData;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.vision.convert.RegexNumberParser;

/**
 * Task responsible for the Monument / "Explore the World" Atlas feature: claiming
 * ready milestone rewards and cracking open owned Scene Fragment Packs from the
 * shared Fragment Backpack.
 *
 * <p>
 * The original {@code LaunchPoint.HOME}-then-pan-search approach
 * (see the old header below) turned out to be unreliable in practice -- camera pan
 * position drifts run to run depending on whatever the prior task left it at, so the
 * badge was often not where the pan sweep expected. Real fix, live-verified on the
 * Testing profile before merging here: anchor off Lancer Camp instead (a fixed
 * building, always reachable the same way via the left-menu queue list), then a
 * single confirmed 300px right swipe brings Monument into view every time.
 * See {@link #findAndOpenBadgeViaLancer()}.
 *
 * <p>
 * Even that swipe still fell back into a full camera-pan sweep
 * whenever a template search for the reward badge came up empty right after landing
 * -- "it pans to the right of lancer, you see monument, then it just starts
 * scrolling around." The 8-direction pan-fallback (the actual "scrolling around")
 * is removed entirely. A first attempt to also drop the template search itself in
 * favor of a fixed-pixel tap was WRONG -- that coordinate was guessed from an old
 * debug frame instead of a confirmed one, and it mis-tapped the Archer Camp instead
 * of Monument. Fixed for real this time: {@link #MONUMENT_BADGE_TAP_POINT} is
 * verified against 3 real screenshots (shop-debug/monument_find.png,
 * monument_check2.png, monument_check3.png) all showing the scroll-with-a-feather
 * badge in the identical spot after this exact swipe, and confirmed the
 * reference frame live before this landed. No template search, no pan fallback.
 *
 * <p>
 * <b>Flow:</b>
 * <pre>
 * Home -> open left-menu City section -> tap Lancer row -> tap the camp building
 * -> wait 5s -> swipe right 300px -> tap the fixed badge point, VERIFY the badge
 * is no longer detectable (confirms something actually opened, instead of
 * assuming) -> claim any ready rows -> X close -> back arrow -> Tundra Albums hub
 * -> Fragment Backpack (bottom-right of the hub) -> open every owned pack (rescanning
 * from scratch after each open, since the panel reflows) -> close -> milestone chest
 * track -> back arrow -> Home, badge cleared.
 * </pre>
 *
 * <p>
 * <b>Fragment Backpack bug fixed 2026-08-14:</b> this used to fire one screen too
 * early, at a coordinate (470,1275) that doesn't correspond to a real button on the
 * screen the badge tap actually leads to -- it always missed, read garbled OCR text,
 * and silently skipped the whole backpack pass every single run. Moved to fire AFTER
 * the back-arrow (on the real Tundra Albums hub) at the hub's own Fragment Backpack
 * button (628,1197) -- live-verified hand-driven, screenshot-confirmed: opened 2 real
 * packs (General Album, Nature's Strength), rewards actually landed (fragment count
 * advanced 827/1347 -> 846/1347), panel reflow after each open behaved exactly as the
 * candidate-rescan logic below expects.
 *
 * <p>
 * <b>Alliance Trade deliberately NOT run automatically (by design):</b>
 * the request/send logic below is real and was live-verified working correctly (Ally
 * Requests skip owned:1 rows and only send genuine duplicates, confirmed against a
 * live panel) -- but the intent is to handle Alliance Trade manually for now, so
 * {@code execute()} no longer calls it. Left in place, unused, for a future re-enable
 * rather than deleted.
 *
 * <p>
 * <b>Known gaps (not built):</b> Ally Requests list is only scanned for rows already
 * visible on open (no deep-scroll dedup), matching the same scroll-list limitation
 * already known in ChatCaptureRoutine.
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

    // ========== Lancer-relative navigation to Monument  ==========
    // Same coordinates as TrainingRoutine.LANCER_AREA_VALUE / TRAINING_CAMP_TAP_MIN/MAX_VALUE --
    // the Lancer row in the left-menu City queue list, then the camp building itself.
    private static final PointData LANCER_AREA_TOP_LEFT = new PointData(161, 636);
    private static final PointData LANCER_AREA_BOTTOM_RIGHT = new PointData(289, 664);
    private static final PointData CAMP_TAP_TOP_LEFT = new PointData(310, 650);
    private static final PointData CAMP_TAP_BOTTOM_RIGHT = new PointData(450, 730);
    private static final int POST_LANCER_WAIT_MS = 5000;
    // Widened 300px -> 350px by design, paired with the real
    // template search below (not a coordinate change) -- the search finds the badge wherever it
    // actually lands, so this only needs to get Monument reliably into frame, not to a precise spot.
    private static final PointData SWIPE_RIGHT_START = new PointData(550, 700);
    private static final PointData SWIPE_RIGHT_END = new PointData(200, 700);
    private static final int SWIPE_DURATION_MS = 400;
    private static final int POST_SWIPE_WAIT_MS = 1000;

    // FOURTH pass -- every fixed-pixel guess so far (330,460 / then 471,550 / then
    // a 516,506-586,576 tolerant box measured off a screenshot) has been wrong at least once live.
    // Guessing a fifth coordinate off a fifth screenshot has the exact same failure mode as the
    // first four -- the camera's landing spot after the swipe isn't perfectly fixed run to run, so
    // no static point or box is ever going to be reliable here. Stopped guessing coordinates for
    // this entirely; see findAndOpenBadgeViaLancer() below, which now finds the badge with a real
    // template search every run and taps wherever it actually is.


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

    // ========== Puzzle-ready chain (Assemble Now -> congrats -> lore card) ==========
    // Real chain, hand-driven tap-by-tap on a genuine live 15/15
    // puzzle the same day (see chat transcript). Two different confidence levels here:
    // (1) ASSEMBLE_REGION_TL/BR, ASSEMBLE_NOW_BTN, ASSEMBLED_TAP_ANYWHERE, and
    //     LORE_CARD_CLOSE_X are all estimates RESCALED from a desktop capture of that
    //     walkthrough, NOT a native 720x1280 ADB frame -- the live puzzle got fully
    //     consumed assembling it during the walkthrough itself, so there was nothing left
    //     to crop a real template from. The reported symptom: "if you did grab it at that time,
    //     it's gone... we're gonna have to wait till we have another one going." These are
    //     first-pass numbers, not verified -- see handlePuzzleReadyChain()'s hard re-anchor
    //     below before anything downstream is trusted.
    // (2) MONUMENT_PUZZLE_OVERVIEW_FRAGMENT_BACKPACK_ICON (used inside handlePuzzleReadyChain)
    //     IS a real native ADB template, cropped live the same day -- normal confidence.
    // "it's clicking the red book, but it's missing the assemble now button in the
    // top right." The screen is finally captured --
    // ocr-debug/monument-puzzle-overview-no-assemble-2026-08-20T08-51-10 -- and everything he
    // described is on it: the "Assemble Now" button top-right, the six-sided blue reward hexagon
    // beside it, and the green-ticked album thumbnails along the bottom.
    //
    // The (450,220)-(660,300) region above was an explicit ESTIMATE (see the note above) that was
    // never checked against a real frame. Measured against the actual capture with the bundled
    // tesseract:
    //     (270,100)-(640,138) banner -> "All fragments found! Assemble to obtain"   clean
    //     (450,220)-(660,300) old    -> "m Assemble"                                marginal
    //     the button itself          -> "oe"     white-on-blue, dies in stripBackground
    // So gate on the banner. It sits on flat orange with dark text, reads perfectly, and states
    // outright that the puzzle is complete -- a better signal than the button's own label.
    private static final PointData PUZZLE_OVERVIEW_ASSEMBLE_REGION_TL = new PointData(270, 100);
    private static final PointData PUZZLE_OVERVIEW_ASSEMBLE_REGION_BR = new PointData(640, 138);
    /** Kept as a secondary read: it does contain "Assemble", just less reliably than the banner. */
    private static final PointData PUZZLE_OVERVIEW_ASSEMBLE_FALLBACK_TL = new PointData(450, 220);
    private static final PointData PUZZLE_OVERVIEW_ASSEMBLE_FALLBACK_BR = new PointData(660, 300);
    /** Button measured at x 485-632, y 258-315 on the live frame; (549,264) sat near its top edge. */
    private static final PointData PUZZLE_OVERVIEW_ASSEMBLE_NOW_BTN = new PointData(558, 286);
    /** The hexagonal blue commit button on the jigsaw screen -- see isAssembleConfirmHexPresent(). */
    private static final PointData PUZZLE_ASSEMBLE_CONFIRM_BTN = new PointData(654, 1193);
    private static final PointData ASSEMBLE_HEX_BOX_TL = new PointData(612, 1155);
    private static final PointData ASSEMBLE_HEX_BOX_BR = new PointData(697, 1232);
    private static final int ASSEMBLE_HEX_MIN_BLUE = 2200;
    private static final int ASSEMBLE_HEX_MIN_GREEN = 300;

    /** Live-verified today: a center-body tap closes the "Well done, you assembled the
     *  Puzzle!" congrats screen. */
    private static final PointData PUZZLE_ASSEMBLED_TAP_ANYWHERE = new PointData(350, 640);
    /** Live-verified today: unlike the congrats screen above, the lore card's own
     *  "Tap anywhere to close" text is unreliable -- two separate body taps at different
     *  points both failed to close it live; only its own X button worked. */
    private static final PointData PUZZLE_LORE_CARD_CLOSE_X = new PointData(645, 90);
    private static final int PUZZLE_ASSEMBLE_ANIM_SETTLE_MS = 1500;

    // ========== Fragment Pack detail (Enable) screen ==========
    /** Quantity defaults to the full owned count already -- one Enable tap consumes
     *  all of them (confirmed live twice: stacks of 2 fully consumed in one tap). */
    private static final PointData PACK_DETAIL_ENABLE_BTN = new PointData(358, 905);
    /**
     * The "Obtain more" Gold Key purchase dialog -- what a no-packs-left album's Obtain button opens.
     * Title box and close X both measured off the live capture at
     * ocr-debug/monument-my-requests-label-unrecognized-2026-08-20T01-22-33 (title reads
     * "Obtain more"; the X sits at 620-697 x 153-184).
     */
    private static final PointData OBTAIN_DIALOG_TITLE_TL = new PointData(150, 122);
    private static final PointData OBTAIN_DIALOG_TITLE_BR = new PointData(580, 180);
    private static final PointData OBTAIN_DIALOG_CLOSE_X = new PointData(658, 168);
    /** "Tap anywhere to close" reward-reveal screen -- tap near the text, not dead-center. */
    private static final PointData REWARD_REVEAL_TAP_ANYWHERE = new PointData(358, 1198);

    // ========== Alliance Trade panel ==========
    /**
     * The Alliance Trade panel's own title box ("Alliance Trade", centred in the wooden header).
     * Measured off a live frame, 2026-08-20: the text spans roughly x 258-465, y 133-163; the box
     * below is padded generously around that so a slightly different render still lands inside.
     */
    private static final PointData TRADE_PANEL_TITLE_TL = new PointData(150, 122);
    private static final PointData TRADE_PANEL_TITLE_BR = new PointData(580, 180);

    private static final PointData TRADE_CLOSE_X = new PointData(662, 155);
    private static final PointData MY_REQUESTS_REQUEST_BTN = new PointData(358, 370);
    // Measured off the first live capture of this panel: "Requests Left Today (3/3)"
    // spans roughly y 262-288, so the old 268 top edge sliced the caps off every glyph. Widened both
    // ways with padding.
    private static final PointData MY_REQUESTS_LEFT_TL = new PointData(195, 256);
    private static final PointData MY_REQUESTS_LEFT_BR = new PointData(575, 298);
    private static final PointData PIECE_PICKER_REQUEST_BTN = new PointData(543, 891);
    private static final PointData PIECE_PICKER_TIPS_CONFIRM = new PointData(358, 789);
    private static final int MAX_REQUEST_LOOPS = 5;

    // My Requests row has THREE distinct states, not the single "Request"
    // state the code above originally assumed -- live-verified hand-driven the same day:
    //   1. "Request" (centered button) -- no active request, free to ask.
    //   2. "Claim" (right-aligned, inside the row once an ally has fulfilled it) -- a real
    //      request/reward reveal ("Tap anywhere to close", then back to the panel).
    //   3. "Requesting..." (disabled-look, paired with a "Cancel" button) -- already pending,
    //      nothing to do this pass.
    // The button's own X position DIFFERS between "Request" (centered, ~358) and "Claim"
    // (right-aligned, ~574) -- so the state must be read via OCR first, then the matching
    // point tapped, rather than assuming one fixed position for both.
    // One wide region spanning both button positions cannot work, and the bundled
    // tesseract proves it on the real captured panel:
    //     (280,340)-(670,400)  wide, covers both states -> reads NOTHING
    //     (500,340)-(670,400)  the Claim button alone   -> reads "Claim"
    // Between the two button positions sit the ally avatar, the green progress chevrons and the
    // fulfilled puzzle-piece artwork; that imagery swamps a single-line OCR pass and the text is
    // lost entirely. (The earlier 620 right edge additionally sliced the Claim button in half.)
    // So read the two button positions as two separate tight regions instead, Claim first.
    private static final PointData MY_REQUESTS_CLAIM_LABEL_TL = new PointData(500, 340);
    private static final PointData MY_REQUESTS_CLAIM_LABEL_BR = new PointData(670, 400);
    /** The "Request" / "Requesting..." button renders centred (~358) rather than right-aligned. */
    private static final PointData MY_REQUESTS_REQUEST_LABEL_TL = new PointData(268, 335);
    private static final PointData MY_REQUESTS_REQUEST_LABEL_BR = new PointData(452, 405);

    /** The Claim button's own slab, measured live; used for the colour check, not for OCR. */
    private static final PointData CLAIM_BTN_BOX_TL = new PointData(515, 345);
    private static final PointData CLAIM_BTN_BOX_BR = new PointData(655, 392);
    /** Live sample of the real button is (37,183,86): g-r=146, g-b=97. These sit well under that. */
    private static final int CLAIM_GREEN_MIN_G = 140;
    private static final int CLAIM_GREEN_DOMINANCE = 60;
    /** The real button fills ~3400 px of that box; 800 is a wide margin under it. */
    private static final int CLAIM_GREEN_MIN_PIXELS = 800;
    private static final PointData MY_REQUESTS_CLAIM_BTN = new PointData(574, 356);
    /** Live-verified today: a center-body tap closes the post-Claim reward reveal
     *  ("Tap anywhere to close", avatars + reward icon) back to the Alliance Trade panel. */
    private static final PointData CLAIM_REWARD_TAP_ANYWHERE = new PointData(344, 895);

    // Caught live -- tapping "Request" doesn't always land directly on the
    // piece-detail popup PIECE_PICKER_REQUEST_BTN below assumes. It can first open the target
    // puzzle's own overview GRID with an animated hand/glove graphic pointing at whichever
    // empty slot the game auto-selected -- "this hand could be anywhere on this board... a
    // three column by four row grid" (the reported symptom). Tapping the pointed-at cell is what opens
    // the actual detail popup. No real template exists yet for that hand graphic (the puzzle
    // that showed it live today, "Friend of Nature", already had its request in flight by the
    // time this was written, so there's nothing left to crop a native frame from -- same
    // constraint as the Assemble Now button in the puzzle-ready chain above). Rather than guess
    // a grid-cell coordinate, processAllianceTradeRequests() below OCR-confirms the detail
    // popup's own Request button is actually present before tapping it, and stops safely (logs
    // + backs out) if it isn't, instead of risking one of the 3 daily requests on a blind tap.
    // Wire up a real ALLIANCE_TRADE_HAND_POINTER template + multi-scale search next time this
    // is caught live.

    private static final PointData ALLY_FIRST_ROW_SEND_BTN = new PointData(583, 712);
    private static final PointData ALLY_FIRST_ROW_OWNED_TL = new PointData(580, 665);
    private static final PointData ALLY_FIRST_ROW_OWNED_BR = new PointData(700, 695);
    private static final int ALLY_ROW_SPACING = 237;
    private static final int ALLY_MAX_VISIBLE_ROWS = 3;

    /**
     * Left edge of the right-hand featured-event icon rail (Events / Deals / Snowbusters and
     * friends) on this 720x1280 layout, measured off a live frame: the icons sit around x 630-700,
     * each with a red notification dot. Any MONUMENT_REWARD_BADGE match at or past this X is one of
     * those dots, not the Monument badge -- see processMonumentBadge() for the logged evidence.
     */
    private static final int EVENT_RAIL_MIN_X = 640;

    private static final int IDLE_RECHECK_MINUTES = 60;
    private static final int PANEL_SETTLE_MS = 1200;
    private static final int ACTION_SETTLE_MS = 900;
    /** The reward-reveal animation after Enable runs noticeably longer than a normal
     *  panel transition -- root cause of an earlier stuck-owned-count bug. */
    private static final int PACK_OPEN_SETTLE_MS = 1800;

    private static final OcrSettingsData PANEL_TITLE_OCR_SETTINGS = OcrSettingsData.assembler()
            .stripBackground(true)
            .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ ")
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .build();

    /**
     * The "Requests Left Today (3/3)" counter was being read with
     * OWNED_COUNT_OCR_SETTINGS, whose whitelist is "OwnedOWNED:0123456789 " -- no '(' and no '/'.
     * processAllianceTradeRequests() then parses it with a regex that requires a literal open
     * paren followed by digits and a slash -- both characters the whitelist forbids. Tesseract
     * cannot emit a glyph outside its whitelist, so that parse could never match and the Request
     * path always bailed out with "No My Requests left today (or couldn't read the counter)" no
     * matter how many requests were actually available. Give the counter a whitelist that can
     * actually spell what's on screen.
     */
    private static final OcrSettingsData REQUESTS_LEFT_OCR_SETTINGS = OcrSettingsData.assembler()
            .stripBackground(true)
            .charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789()/ ")
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .build();

    private static final OcrSettingsData OWNED_COUNT_OCR_SETTINGS = OcrSettingsData.assembler()
            .stripBackground(true)
            .charWhitelist("OwnedOWNED:0123456789 ")
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
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
        // A prior task (Resource Stockpile Scan, in particular) can
        // leave its own popup open when Monument's turn comes up. Clear it first,
        // unconditionally, before searching for anything.
        clearStrayPopups();

        if (!findAndOpenBadgeViaLancer()) {
            logInfo(logLine("No rewards-ready badge found right now. Rechecking in "
                    + IDLE_RECHECK_MINUTES + " minutes."));
            reschedule(LocalDateTime.now().plusMinutes(IDLE_RECHECK_MINUTES));
            return;
        }

        logInfo(logLine("Badge opened. Claiming any ready rows."));
        claimAllReadyRows();

        tapNear(MODAL_CLOSE_X);
        sleepTask(PANEL_SETTLE_MS);

        tapNear(ATLAS_BACK_ARROW);
        sleepTask(ACTION_SETTLE_MS);

        // Moved here (was called before the back-arrow, at a coordinate that
        // doesn't exist on that screen -- see class header). This is the real Tundra Albums
        // hub, where Fragment Backpack's actual button lives.
        logInfo(logLine("On Tundra Albums. Processing the shared Fragment Backpack."));
        processFragmentBackpack();

        logInfo(logLine("Checking the milestone chest track."));
        claimMilestoneChestsIfReady();

        // Alliance Trade Sends (giving pieces TO allies) deliberately not run
        // automatically -- by design, confirmed live: "there's a whole other part of
        // this where you could give other alliance members pieces, but it's extremely
        // complicated... not really appropriate at this time." processAllianceTradeSends() is
        // left in place, live-verified working, just not called here.
        //
        // My Requests (Claim + Request -- asking the alliance FOR a piece) is
        // now wired in, by design, a direct "build this whole thing" the same day he walked the
        // real Claim/Request/piece-picker flow live tap-by-tap. Entered via the Tundra Albums
        // hub's own always-present Alliance Trade button (not the floating city badge that led
        // here today -- that badge reverted back to the normal MONUMENT_REWARD_BADGE state the
        // moment its one pending trade got consumed, so there's no stable template for it to
        // gate on; the hub button needs no badge at all).
        // Observed live: this gate was OCR'ing BACKPACK_TITLE_TL/BR -- the
        // Fragment Backpack panel's own title box, a completely different panel -- to "confirm"
        // the Alliance Trade panel opened. Copy-paste bug: that region always reads blank here
        // ('null' every single run), so this pass never once actually proceeded past the gate.
        // No dedicated Alliance Trade title box was ever measured. Per the direct instruction
        // ("keep it simple -- worst case is a false positive and it exits out anyway, who cares"):
        // drop the broken OCR gate entirely and just proceed. ALBUMS_ALLIANCE_TRADE_BTN is a
        // reliable always-present hub button (not a floating badge that can be absent), so a tap
        // there is already good evidence -- if it somehow lands wrong, processAllianceTradeRequests
        // simply fails to find its own buttons and falls through harmlessly.
        // The gate came back, because "worst case is a harmless false positive"
        // turned out to be false. A live frame of what this tap ACTUALLY opens is now captured
        // (ocr-debug/monument-my-requests-label-unrecognized-2026-08-20T01-22-33): it is the
        // "Obtain more" GOLD KEY PURCHASE dialog, not Alliance Trade. That also finally explains the
        // string from the original report -- 'd to perform i epic pbuyy' is that panel's own line
        // "Can be used to perform 1 Epic Recruitment." plus its Buy button, sitting inside the label
        // region. ALBUMS_ALLIANCE_TRADE_BTN at (448,1197) is simply the wrong coordinate on this hub.
        //
        // And this is NOT harmless: MY_REQUESTS_CLAIM_BTN is (574,356), while that dialog's
        // "Buy 1,500 gems" button sits at about (578,391). A blind Claim tap lands within ~35px of
        // spending 1,500 gems. Nothing was spent only because the colour check happened to see 0
        // green pixels (the Buy button is gold) -- luck, not design. So gate it properly on the
        // panel's own title before any tapping happens, and dump the hub frame when the gate fails
        // so the real button coordinate can be measured instead of guessed.
        //
        // Note this path only matters in the scroll/quill badge state; the puzzle/swap badge opens
        // Alliance Trade directly and is already handled in processMonumentBadge().
        logInfo(logLine("Opening Alliance Trade for My Requests (Claim/Request only)."));
        String hubFrame = dumpDiagnosticFrame("albums-hub-before-alliance-trade-tap");
        tapNear(ALBUMS_ALLIANCE_TRADE_BTN);
        sleepTask(PANEL_SETTLE_MS);

        String tradeTitle = stringHelper.attemptRecognition(
                TRADE_PANEL_TITLE_TL, TRADE_PANEL_TITLE_BR,
                2, 150L, PANEL_TITLE_OCR_SETTINGS,
                s -> s != null && !s.isBlank(),
                s -> s.toLowerCase());
        if (tradeTitle != null && (tradeTitle.contains("alliance") || tradeTitle.contains("trade"))) {
            processAllianceTradeRequests();
            tapNear(TRADE_CLOSE_X);
            sleepTask(ACTION_SETTLE_MS);
        } else {
            logWarning(logLine("Tapping " + ALBUMS_ALLIANCE_TRADE_BTN + " on the Tundra Albums hub did NOT "
                    + "open Alliance Trade -- panel title read as '" + tradeTitle + "'. Live evidence says "
                    + "this coordinate opens the 'Obtain more' Gold Key purchase dialog, whose Buy button "
                    + "sits ~35px from where a blind Claim tap would land. Not touching it. Closing out. "
                    + "Hub frame before the tap: " + hubFrame + " | after: "
                    + dumpDiagnosticFrame("albums-alliance-trade-tap-wrong-panel")));
            tapNear(TRADE_CLOSE_X);
            sleepTask(ACTION_SETTLE_MS);
        }

        tapNear(ALBUMS_BACK_ARROW);
        sleepTask(ACTION_SETTLE_MS);

        StatisticsService.obtain().addToCounter(profile, "Monument Pass Completed", 1);
        logInfo(logLine("Monument pass complete. Rechecking in " + IDLE_RECHECK_MINUTES + " minutes."));
        reschedule(LocalDateTime.now().plusMinutes(IDLE_RECHECK_MINUTES));
    }

    // "moved right three hundred pixels...
    // then it just, like, went to the events tab." Root cause found from evidence, not guessed --
    // the ONLY post-tap check below was "is the old badge template no longer visible", which is a
    // bad proxy for "the tap actually opened something": a tap that misses the badge (scroll drift,
    // a slightly different swipe landing spot, whatever) also makes the old badge unmatchable, so
    // this check reported false success just as often as real success. On false success, execute()
    // cascades straight into tapNear(MODAL_CLOSE_X) at (662,157) -- which sits almost exactly on
    // the real Events-tab icon's screen position. That's the mechanism: missed tap -> false "opened"
    // -> blind tap at (662,157) -> Events tab opens. This was flagged as a known risk in this same
    // method's comment history before it was actually observed live.
    //
    // Fix: EVENTS_TAB_HALL_OF_CHIEFS / _DEFEAT_BEASTS / _BROTHERS_IN_ARMS / _HERO_RALLY /
    // _LUCKY_WHEEL are the Events panel's own tab-selector icons -- all of them render together
    // across the top of the Events panel regardless of which sub-tab is active (see
    // EventClaimRoutine), so any one of them matching is a reliable "we ended up in Events, not
    // Monument" signal, independent of whether the Monument badge template happens to still be
    // readable. Checked explicitly before treating the badge tap as a success; on a hit this backs
    // out with pressBack() (which already carries the quit-dialog guard) instead of ever tapping
    // MODAL_CLOSE_X on a screen that isn't the Atlas panel.
    private static final TemplatesEnum[] EVENTS_TAB_LANDING_SIGNS = {
            TemplatesEnum.EVENTS_TAB_HALL_OF_CHIEFS,
            TemplatesEnum.EVENTS_TAB_DEFEAT_BEASTS,
            TemplatesEnum.EVENTS_TAB_BROTHERS_IN_ARMS,
            TemplatesEnum.EVENTS_TAB_HERO_RALLY,
            TemplatesEnum.EVENTS_TAB_LUCKY_WHEEL,
    };

    /**
     * Navigates to Lancer Camp (a fixed, always-reachable building) then a single
     * confirmed 300px right swipe brings Monument's badge into view. rebuilt from
     * scratch (fourth pass): no fixed-pixel tap of any kind anymore. After the swipe, this does a
     * real template search for {@link TemplatesEnum#MONUMENT_REWARD_BADGE} and taps exactly where
     * it's actually found -- immune to whatever's making the camera's landing spot vary run to run,
     * since a guessed static point can never account for that but a live search always finds the
     * real position. Uses {@link SearchConfigConstants#MONUMENT_BADGE_SEARCH} (a deliberately loose
     * threshold -- see that constant's comment for why) and logs the real match score every time,
     * hit or miss, so the threshold can be tightened later against real evidence instead of another
     * guess. If the badge genuinely isn't found, this returns false and taps nothing at all -- no
     * fallback coordinate, ever. The tap is verified two ways afterward: the same template must no
     * longer be findable (something actually opened), and none of the Events panel's own tab icons
     * can be detected (in case a tap that did land somewhere still missed the badge specifically).
     */
    private boolean findAndOpenBadgeViaLancer() {
        marchHelper.openLeftMenuCitySection(true);
        sleepTask(500);

        tapInside(LANCER_AREA_TOP_LEFT, LANCER_AREA_BOTTOM_RIGHT, 1, 500);
        tapInside(CAMP_TAP_TOP_LEFT, CAMP_TAP_BOTTOM_RIGHT, 1, 300);
        sleepTask(POST_LANCER_WAIT_MS);

        swipe(SWIPE_RIGHT_START, SWIPE_RIGHT_END, SWIPE_DURATION_MS);
        sleepTask(POST_SWIPE_WAIT_MS);

        // Observed live: a completed Scene Fragment set shows a SEPARATE icon at this
        // same landing spot -- a spiral notebook with an orange puzzle-piece speech bubble -- distinct
        // from the scroll-with-a-feather MONUMENT_REWARD_BADGE. Template cropped from a live 720x1280
        // ADB frame, self-verified 1.0 match against its source. Gated first step by explicit
        // request: identify it, tap it, and stop here -- the Assemble/puzzle-solve/lore-card/Fragment-
        // Backpack chain after it is a separate, deliberately-untested-yet next step, not guessed now.
        // Observed live: threshold=30 (MONUMENT_BADGE_SEARCH)
        // was letting an unrelated building's badge (an Alliance-Tech-style scale/briefcase icon)
        // false-match this template at 35.965%/40.535% across two real runs, short-circuiting the
        // whole routine before it ever reached the real Monument tower or Claim All. See
        // MONUMENT_PUZZLE_READY_ICON_SEARCH's own comment for the full evidence.
        // "it pans to the right of lancer, you see monument, then it just starts
        // scrolling around." The 8-direction pan-fallback that used to live here is gone for good --
        // that's the actual "scrolling around" behavior flagged. This is a single search at the
        // landing spot, nothing more; on a miss it stops and reschedules (see below), it does not pan.
        //
        // Evidence: two consecutive live misses
        // logged real scores of 40.7 and 50.6 (threshold 65) -- both well below, and different from
        // each other on a supposedly-static template, which single-scale correlation is known to do
        // when the on-screen icon renders at a slightly different size than the template was captured
        // at. Switched to locatePatternMultiScale (already used elsewhere, e.g.
        // UpgradeBuildingsRoutine.tapAllianceHelp()) to test multiple scales per attempt instead of
        // exactly one -- if this raises the logged score meaningfully, that confirms scale was the
        // real problem; if it doesn't, that's real evidence pointing somewhere else next.
        // The badge has (at least) TWO states and only one was ever searched for.
        // Watched both live within four minutes of each other on the same tower: the gold puzzle
        // piece with the blue swap arrow (which opens Alliance Trade), and, once its Claim had been
        // collected, a scroll-and-quill -- the "scroll+feather" this file's comments have described
        // all along. Scored against the real frames, each template is decisive on its own state and
        // clearly negative on the other's (97-98% / 89.97% vs 42-45%), so they're separate icons,
        // not one icon rendering differently. Search both instead of walking a single threshold down
        // until noise matches, which is precisely how this ended up at threshold=30 tapping the
        // Events rail and empty snow.
        ImageSearchResultData badge = templateSearchHelper.locatePatternMultiScale(
                TemplatesEnum.MONUMENT_REWARD_BADGE, SearchConfigConstants.MONUMENT_BADGE_SEARCH);
        logInfo(logLine("Badge template search result (puzzle/swap state, multi-scale): " + badge));
        if (!badge.isFound()) {
            badge = templateSearchHelper.locatePatternMultiScale(
                    TemplatesEnum.MONUMENT_REWARD_BADGE_SCROLL, SearchConfigConstants.MONUMENT_BADGE_SEARCH);
            logInfo(logLine("Badge template search result (scroll/quill state, multi-scale): " + badge));
        }

        if (!badge.isFound()) {
            // This puzzle-ready search used to run BEFORE the badge search, and
            // overnight that cost six consecutive hourly passes. Once the tower switched to the
            // scroll/quill badge, MONUMENT_PUZZLE_READY_ICON -- a cluttered 115x115 crop of a brown
            // scroll -- began matching that badge and hijacking it into handlePuzzleReadyChain(),
            // which then failed every time on its own "Assemble Now" gate:
            //     01:26 HIT 72.779 -> no 'Assemble Now' (read: 'null')
            //     02:29 HIT 57.331 -> no 'Assemble Now' (read: 'WP neseraie')
            //     03:31 HIT 58.607, 04:34 HIT 74.692, 05:36 HIT 71.874, 06:38 -- same, every hour.
            // Note the scores swinging 41 -> 75 on a static icon: that template is unstable, which
            // is also how it missed at 41.344 in the one pass that DID take the badge route -- and
            // that pass claimed a reward and opened 2 packs. So the badge route works and this one
            // does not; the only reason the badge route ever ran was that unstable score dipping.
            // Search the badges first (both score 87-98% and are stable) and only fall back to
            // puzzle-ready when neither badge state is present.
            ImageSearchResultData puzzleReady = templateSearchHelper.locatePatternMultiScale(
                    TemplatesEnum.MONUMENT_PUZZLE_READY_ICON, SearchConfigConstants.MONUMENT_PUZZLE_READY_ICON_SEARCH);
            logInfo(logLine("No badge in either state; puzzle-ready icon search result (multi-scale): "
                    + puzzleReady));
            if (puzzleReady.isFound()) {
                logInfo(logLine("Puzzle-ready icon found at " + puzzleReady.getPoint()
                        + " -- tapping it and running the assemble/fragment-backpack chain."));
                tapNear(puzzleReady.getPoint());
                sleepTask(PANEL_SETTLE_MS);
                handlePuzzleReadyChain();
                return false;
            }

            logInfo(logLine("Badge not found this pass -- nothing to tap, not guessing a coordinate. "
                    + dumpDiagnosticFrame("badge-not-found")));
            return false;
        }

        // "you're clicking the monument, and you're clicking the fucking events tab
        // in the top right." The coordinates prove him exactly right. The right-hand UI rail carries
        // the featured-event icons (currently Events / Deals / Snowbusters) stacked around x 630-700,
        // and each one wears a small red notification dot. MONUMENT_BADGE_SEARCH runs a full-screen
        // multi-scale search at threshold=30, which is loose enough for one of those red dots to win
        // outright. Logged evidence across four consecutive runs, and it splits perfectly by X:
        //
        //   20:17  HIT @(515,316) score=45.068  -> reached Tundra Albums   (real Monument)
        //   23:29  HIT @(456,318) score=45.814  -> reached Tundra Albums   (real Monument)
        //   23:41  HIT @(699,417) score=50.013  -> landed on the wrong screen
        //   00:43  HIT @(693,417)               -> landed on the wrong screen
        //
        // Every good match is central; every bad one is out in the rail. The scores don't separate
        // them (45.0 vs 50.0 -- and the file's own notes record a genuine tap scoring 89.44), so no
        // threshold tweak can tell these apart without also throwing away real hits. Position can.
        // Monument is a building out in the city view and is never in that rail, so a match there is
        // a red notification dot, not the Monument badge. Reject it instead of tapping it.
        if (badge.getPoint().getX() >= EVENT_RAIL_MIN_X) {
            logWarning(logLine("Badge matched at " + badge.getPoint() + " -- that's inside the right-hand "
                    + "event rail (x >= " + EVENT_RAIL_MIN_X + "), where the Events/Deals/Snowbusters icons "
                    + "carry red notification dots that this template matches at threshold=30. Monument is a "
                    + "city building and is never there, so this is a false positive, not the badge. Not "
                    + "tapping it -- that tap is what opens the Events tab. "
                    + dumpDiagnosticFrame("badge-matched-in-event-rail")));
            return false;
        }

        tapNear(badge.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        // Observed live twice, two days apart -- "it just went to the events
        // tab"): the EVENTS_TAB_LANDING_SIGNS check below enumerated 5 SPECIFIC rotating event
        // banners (Hall of Chiefs, Defeat Beasts, Brothers in Arms, Hero Rally, Lucky Wheel).
        // Whiteout Survival's live event roster changes -- confirmed live via screenshot the
        // featured events right now are Events/Deals/Snowbusters, none of which are in that list
        // -- so this "did we land on Events" check can never fire once the seasonal events differ
        // from whatever 5 were hardcoded, no matter how badly it actually did land there. A
        // negative check enumerating every possible wrong screen is the wrong shape entirely; a
        // positive check for "did we actually land on Monument" doesn't care what's on Events.
        // MONUMENT_TUNDRA_ALBUMS_OPTION / MONUMENT_ATLAS_CLAIM_BUTTON / _CLAIM_ALL_BUTTON are all
        // Monument-Atlas-panel-specific UI, not tied to any rotating content -- any one present
        // means we're really on Monument; none present means we're not, whatever screen this is.
        boolean onMonumentPanel =
                templateSearchHelper.locatePattern(TemplatesEnum.MONUMENT_TUNDRA_ALBUMS_OPTION, SearchConfigConstants.QUICK_SEARCH).isFound()
                || templateSearchHelper.locatePattern(TemplatesEnum.MONUMENT_ATLAS_CLAIM_BUTTON, SearchConfigConstants.QUICK_SEARCH).isFound()
                || templateSearchHelper.locatePattern(TemplatesEnum.MONUMENT_ATLAS_CLAIM_ALL_BUTTON, SearchConfigConstants.QUICK_SEARCH).isFound();
        if (!onMonumentPanel) {
            // With the badge template finally correct (see 761450f), the very first
            // real badge tap -- at (367,537), the actual badge rather than noise -- landed straight on
            // the ALLIANCE TRADE panel, captured in
            // ocr-debug/monument-landed-off-monument-2026-08-20T00-56-15. Not the Monument panel, and
            // not a wrong screen either: the gold-puzzle-piece-with-blue-swap-arrow badge IS the
            // Alliance Trade badge, and tapping it is a direct shortcut. This whole routine assumed
            // badge -> Monument -> Tundra Albums -> Alliance Trade button, so the one screen this has
            // been asking for all night was being opened and then immediately backed out of as
            // "whatever screen this is".
            //
            // The panel's own coordinates were never the problem -- checked against that live frame,
            // every existing constant is right (close X 662,155 vs 662,157; Claim 574,356 inside the
            // real button at 583,367; ally Send 583,712 vs 583,710; row spacing 237 vs a measured
            // 238). They had simply never been reached. So: recognise the screen and do the work.
            String tradeTitle = stringHelper.attemptRecognition(
                    TRADE_PANEL_TITLE_TL, TRADE_PANEL_TITLE_BR,
                    2, 150L, PANEL_TITLE_OCR_SETTINGS,
                    s -> s != null && !s.isBlank(),
                    s -> s.toLowerCase());
            if (tradeTitle != null && (tradeTitle.contains("alliance") || tradeTitle.contains("trade"))) {
                logInfo(logLine("Badge opened Alliance Trade directly (title read as '" + tradeTitle
                        + "') -- that badge is the Alliance Trade badge, not a Monument-panel badge. "
                        + "Running the My Requests pass right here instead of backing out."));
                processAllianceTradeRequests();
                tapNear(TRADE_CLOSE_X);
                sleepTask(ACTION_SETTLE_MS);
                return false;
            }

            logWarning(logLine("Tapped the real matched badge at " + badge.getPoint()
                    + " but none of Monument's own panel signals (Tundra Albums option / Claim / Claim All) "
                    + "are present, and the panel title didn't read as Alliance Trade either (read: '"
                    + tradeTitle + "') -- didn't actually land on Monument, whatever screen this is. Backing "
                    + "out instead of cascading blind taps onto the wrong screen. Recovering toward Home. "
                    + dumpDiagnosticFrame("landed-off-monument")));
            recoverTowardHome();
            return false;
        }

        // A verification check must actually rule things OUT to mean anything -- MONUMENT_BADGE_SEARCH's
        // threshold=30 is tuned for finding the real badge pre-tap (including at the low point of its
        // own bounce animation), not for ruling it out on the DIFFERENT screen that opens post-tap. Real
        // logged evidence this was a false positive every single pass: see
        // SearchConfigConstants#MONUMENT_BADGE_STILL_THERE_CHECK's header.
        ImageSearchResultData badgeStillThere = templateSearchHelper.locatePatternMultiScale(
                TemplatesEnum.MONUMENT_REWARD_BADGE, SearchConfigConstants.MONUMENT_BADGE_STILL_THERE_CHECK);
        if (badgeStillThere.isFound()) {
            logWarning(logLine("Tapped the real matched badge at " + badge.getPoint()
                    + " but a badge match is still detectable afterward (" + badgeStillThere
                    + ") -- nothing opened. Stopping here instead of cascading into the rest of the chain blind."));
            return false;
        }

        return true;
    }

    /**
     * The assemble/congrats/lore-card/Fragment-Backpack chain that
     * {@link #findAndOpenBadgeViaLancer()} used to gate and stop before. See the class-level
     * "Puzzle-ready chain" constants comment above for which coordinates here are real
     * native templates vs first-pass rescaled estimates. Every estimated tap is followed by
     * a real check before the next step trusts it -- this never cascades blind.
     */
    private void handlePuzzleReadyChain() {
        // "Assemble Now" only renders once the puzzle genuinely has every fragment (15/15).
        // If the icon tap instead opened an in-progress puzzle, there's nothing to assemble --
        // bail out cleanly rather than tapping an estimated button that isn't there.
        String overviewText = stringHelper.attemptRecognition(
                PUZZLE_OVERVIEW_ASSEMBLE_REGION_TL, PUZZLE_OVERVIEW_ASSEMBLE_REGION_BR,
                2, 150L, PANEL_TITLE_OCR_SETTINGS,
                s -> s != null && !s.isBlank(),
                s -> s);
        if (overviewText == null || !overviewText.toLowerCase().contains("assemble")) {
            String fallback = stringHelper.attemptRecognition(
                    PUZZLE_OVERVIEW_ASSEMBLE_FALLBACK_TL, PUZZLE_OVERVIEW_ASSEMBLE_FALLBACK_BR,
                    2, 150L, PANEL_TITLE_OCR_SETTINGS,
                    s -> s != null && !s.isBlank(),
                    s -> s);
            logInfo(logLine("Assemble banner read as '" + overviewText + "'; button-area fallback read as '"
                    + fallback + "'."));
            if (fallback != null && fallback.toLowerCase().contains("assemble")) {
                overviewText = fallback;
            }
        }
        if (overviewText == null || !overviewText.toLowerCase().contains("assemble")) {
            // "it's clicking the red book, but it's missing the assemble now button
            // in the top right." This region was an ESTIMATE that has never once been checked against
            // a real capture of this screen -- it failed six times overnight reading 'null',
            // 'WP neseraie', 'WP ncceraie', 'WP reverie'. Dump the frame so the button's actual
            // position can be measured instead of estimated a second time.
            logInfo(logLine("Puzzle-ready icon opened, but no 'Assemble Now' text confirmed via OCR "
                    + "(read: '" + overviewText + "') in region " + PUZZLE_OVERVIEW_ASSEMBLE_REGION_TL
                    + "->" + PUZZLE_OVERVIEW_ASSEMBLE_REGION_BR + " -- either the puzzle isn't actually "
                    + "complete yet, or the estimated OCR region is off. Not tapping blind; recovering "
                    + "toward Home. " + dumpDiagnosticFrame("puzzle-overview-no-assemble")));
            recoverTowardHome();
            return;
        }

        logInfo(logLine("'Assemble Now' confirmed via OCR -- tapping."));
        tapNear(PUZZLE_OVERVIEW_ASSEMBLE_NOW_BTN);
        sleepTask(PUZZLE_ASSEMBLE_ANIM_SETTLE_MS);

        // Describing this from memory before it was ever captured: "there should be
        // [a] six sided blue [button] with a puzzle in it with a green checkbox maybe in the bottom
        // right." Exactly right. Tapping Assemble Now does not finish anything -- it opens an
        // interactive jigsaw screen with the pieces scattered on a wooden board, and the assembly is
        // only committed by a hexagonal blue button carrying a 4-piece puzzle and a green tick, at
        // (654,1193). The chain never tapped it: it went straight to a blind "tap anywhere" at
        // (350,640), which on that screen just prods the board, so the puzzle was never assembled
        // and the run ended at "Fragment Backpack icon not found".
        if (isAssembleConfirmHexPresent()) {
            logInfo(logLine("Assemble confirm hexagon present -- tapping it to commit the assembly."));
            tapNear(PUZZLE_ASSEMBLE_CONFIRM_BTN);
            sleepTask(PUZZLE_ASSEMBLE_ANIM_SETTLE_MS);
        } else {
            logInfo(logLine("No assemble-confirm hexagon on screen after tapping Assemble Now -- either "
                    + "this album commits without the jigsaw step, or the tap didn't land. Continuing to "
                    + "the congrats/lore-card sequence, which re-anchors on a real template below."));
        }

        tapNear(PUZZLE_ASSEMBLED_TAP_ANYWHERE);
        sleepTask(ACTION_SETTLE_MS);

        tapNear(PUZZLE_LORE_CARD_CLOSE_X);
        sleepTask(PANEL_SETTLE_MS);

        // Real re-anchor: don't trust the three estimated taps above blindly. Confirm we
        // actually landed back on a puzzle overview screen (real native template) before
        // touching Fragment Backpack at all.
        ImageSearchResultData backpackIcon = templateSearchHelper.locatePattern(
                TemplatesEnum.MONUMENT_PUZZLE_OVERVIEW_FRAGMENT_BACKPACK_ICON, SearchConfigConstants.DEFAULT_SINGLE);
        if (!backpackIcon.isFound()) {
            logWarning(logLine("Fragment Backpack icon not found after the assemble/congrats/lore-card "
                    + "sequence -- one of the estimated taps likely missed. Recovering toward Home "
                    + "instead of continuing blind."));
            recoverTowardHome();
            return;
        }

        logInfo(logLine("Puzzle overview confirmed (Fragment Backpack icon found at " + backpackIcon.getPoint()
                + "). Processing the shared Fragment Backpack."));
        processFragmentBackpack(backpackIcon.getPoint());

        // Two levels deep here (puzzle overview -> Tundra Albums hub -> City/Home), vs one
        // level for the normal MONUMENT_REWARD_BADGE flow -- confirmed live today (back arrow
        // from the puzzle overview landed on Tundra Albums, a second back arrow from there
        // landed on City). ALBUMS_BACK_ARROW's coordinate matches both screens closely enough
        // (both top-left back arrows render in the same spot across this shared skin).
        tapNear(ALBUMS_BACK_ARROW);
        sleepTask(ACTION_SETTLE_MS);
        tapNear(ALBUMS_BACK_ARROW);
        sleepTask(ACTION_SETTLE_MS);

        StatisticsService.obtain().addToCounter(profile, "Monument Puzzle Assembled", 1);
        logInfo(logLine("Puzzle-ready chain complete."));
    }

    /**
     * Color sanity check for a template match, by design ("it's a
     * big green claim button... how accurate do you have to be?"). Averages the RGB pixels inside
     * the matched region straight from a live emulator frame and requires green to genuinely
     * dominate red and blue -- not just edge them out. Measured live margins make this an easy
     * call: the real button averages roughly (82,179,100) -- green beats red by ~97 and blue by
     * ~79 -- while the disabled lookalike that fooled the shape match averages roughly
     * (122,124,126), i.e. red/green/blue within 4 of each other, nowhere close to green-dominant.
     * GREEN_DOMINANCE_MARGIN=40 sits well under the real button's ~79-97 margins and well over the
     * lookalike's ~2, a real gap either side rather than a fragile guess.
     *
     * <p>Only handles the common 32bpp (RGBA_8888) capture format this emulator normally returns;
     * on any other format this can't verify color and returns true (fail-open to the template
     * match result alone, i.e. today's prior behavior) rather than silently blocking every claim.
     */
    /**
     * Every Monument fix so far has been a guess at coordinates, because when an
     * OCR gate fails all it reports is the garbled text -- never WHAT SCREEN it was looking at.
     * "read as 'd to perform i epic pbuyy'" says the region holds prose instead of a button label,
     * but not whether the Alliance Trade panel failed to open, opened on a different default tab,
     * or opened behind a first-run Tips dialog. Those need different fixes and the log cannot tell
     * them apart. So on failure, write the actual frame to disk; the next run leaves real evidence
     * to calibrate against instead of another round of guessing.
     *
     * <p>Deliberately best-effort: a diagnostic must never be able to break the routine it is
     * diagnosing, so every failure here is swallowed and reported inline in the caller's log line.
     */
    private String dumpDiagnosticFrame(String tag) {
        try {
            RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
            if (frame == null) {
                return "(diagnostic frame unavailable: capture returned null)";
            }
            java.io.File dir = new java.io.File("C:/Bearguard/ocr-debug");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return "(diagnostic frame not saved: could not create " + dir + ")";
            }
            String stamp = LocalDateTime.now().toString().replace(':', '-').replace('.', '-');
            java.io.File out = new java.io.File(dir, "monument-" + tag + "-" + stamp + ".png");
            javax.imageio.ImageIO.write(
                    dev.frostguard.vision.convert.ImageConverter.toBufferedImage(frame), "png", out);
            return "Diagnostic frame saved: " + out.getAbsolutePath();
        } catch (Exception e) {
            return "(diagnostic frame not saved: " + e.getMessage() + ")";
        }
    }

    private boolean isRegionPredominantlyGreen(ImageSearchResultData match) {
        RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
        if (frame == null || frame.getBpp() != 32) {
            return true;
        }

        SizeData size = match.getTemplateSize();
        int w = size != null ? size.getWidth() : 40;
        int h = size != null ? size.getHeight() : 20;
        int x0 = Math.max(0, match.getPoint().getX() - w / 2);
        int y0 = Math.max(0, match.getPoint().getY() - h / 2);
        int x1 = Math.min(frame.getWidth(), x0 + w);
        int y1 = Math.min(frame.getHeight(), y0 + h);

        byte[] px = frame.getFrameBytes();
        int stride = frame.getWidth() * 4;
        long r = 0, g = 0, b = 0;
        int count = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int offset = y * stride + x * 4;
                if (offset + 2 >= px.length) continue;
                r += px[offset] & 0xFF;
                g += px[offset + 1] & 0xFF;
                b += px[offset + 2] & 0xFF;
                count++;
            }
        }
        if (count == 0) return true;

        double avgR = (double) r / count;
        double avgG = (double) g / count;
        double avgB = (double) b / count;
        boolean isGreen = avgG - avgR >= GREEN_DOMINANCE_MARGIN && avgG - avgB >= GREEN_DOMINANCE_MARGIN;
        logInfo(logLine(String.format(
                "Color check at %s: avgRGB=(%.0f,%.0f,%.0f) -- %s",
                match.getPoint(), avgR, avgG, avgB, isGreen ? "green, trusting the match" : "NOT green, rejecting")));
        return isGreen;
    }

    /**
     * Stop trying to OCR the Claim button. The bundled tesseract reads "Claim"
     * cleanly off a raw crop of it, but through the app's own pipeline -- PANEL_TITLE_OCR_SETTINGS
     * has stripBackground(true) -- the same button comes back as 's' or as nothing at all, twice
     * live. White text on a saturated green fill does not survive that preprocessing, and the panel
     * title ("Alliance Trade", white on flat orange) reading fine through the identical settings is
     * what makes the difference easy to miss.
     *
     * <p>The button doesn't need reading. It's a big solid green slab, and this was already established as
     * much about the other one in this file: "it's a big green claim button... how accurate do you
     * have to be?" Sampled straight off the live panel, the separation is not close:
     * <pre>
     *   Claim button   (37,183,86)  x3406 px   green dominant
     *   Send button    (79,165,252)            blue, different row anyway
     *   centre chevrons(36,227,59)  x265 px    G=227 vs 183, far off, and a tiny area
     * </pre>
     * So count pixels near the Claim green inside the button's own box. Present means claimable.
     *
     * <p>Fails CLOSED (returns false) if the frame is missing or isn't the usual 32bpp format --
     * an unread frame must not be reported as a claimable button, since the caller taps on a true.
     */
    private boolean isClaimButtonPresent() {
        RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
        if (frame == null || frame.getBpp() != 32) {
            logInfo(logLine("Claim-button colour check skipped (frame unavailable or not 32bpp) -- "
                    + "treating the row as not claimable rather than tapping on a guess."));
            return false;
        }

        byte[] px = frame.getFrameBytes();
        int stride = frame.getWidth() * 4;
        int matched = 0;
        for (int y = CLAIM_BTN_BOX_TL.getY(); y < CLAIM_BTN_BOX_BR.getY() && y < frame.getHeight(); y++) {
            for (int x = CLAIM_BTN_BOX_TL.getX(); x < CLAIM_BTN_BOX_BR.getX() && x < frame.getWidth(); x++) {
                int offset = y * stride + x * 4;
                if (offset + 2 >= px.length) continue;
                int c0 = px[offset] & 0xFF, g = px[offset + 1] & 0xFF, c2 = px[offset + 2] & 0xFF;
                // Deliberately channel-order agnostic. Green is the middle byte in both RGBA and
                // BGRA, but the outer two swap between them, so testing "green dominates the other
                // two" holds either way where testing a literal (37,183,86) would silently fail on
                // a BGRA frame. Checked against every colour measured on the real panel:
                //   Claim  (37,183,86)   g-r=146 g-b=97   -> passes
                //   tan bg (239,203,153) g-r=-36          -> fails
                //   white  (254,254,254) g-r=0            -> fails
                //   Send   (79,165,252)  g-b=-87          -> fails
                if (g >= CLAIM_GREEN_MIN_G
                        && g - c0 >= CLAIM_GREEN_DOMINANCE
                        && g - c2 >= CLAIM_GREEN_DOMINANCE) {
                    matched++;
                }
            }
        }
        boolean present = matched >= CLAIM_GREEN_MIN_PIXELS;
        logInfo(logLine("Claim-button colour check: " + matched + " green pixels in "
                + CLAIM_BTN_BOX_TL + "->" + CLAIM_BTN_BOX_BR + " (need " + CLAIM_GREEN_MIN_PIXELS
                + ") -- " + (present ? "Claim is present" : "no Claim button")));
        return present;
    }

    /**
     * The hexagonal blue "commit the assembly" button on the jigsaw screen: a 4-piece puzzle glyph
     * with a green tick, measured at x 616-693, y 1158-1228 on a live frame.
     *
     * <p>Detected on TWO signals together, because neither alone separates it cleanly. Measured
     * across four real captured screens:
     * <pre>
     *                        blue-hex px   green-tick px
     *   jigsaw screen            2768           409      <- the button
     *   puzzle overview             0           192      (album thumbnails carry green ticks)
     *   city map                 1538             0      (the mail/scales icons are blue)
     *   alliance trade panel        -             0
     * </pre>
     * Blue alone would fire on the city map at 1538; green alone would fire on the puzzle overview
     * at 192. Requiring both leaves a wide gap on either side and makes each threshold non-critical.
     *
     * <p>Fails closed on a missing or non-32bpp frame, since the caller taps on a true.
     */
    private boolean isAssembleConfirmHexPresent() {
        RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
        if (frame == null || frame.getBpp() != 32) {
            return false;
        }

        byte[] px = frame.getFrameBytes();
        int stride = frame.getWidth() * 4;
        int blue = 0, green = 0;
        for (int y = ASSEMBLE_HEX_BOX_TL.getY(); y < ASSEMBLE_HEX_BOX_BR.getY() && y < frame.getHeight(); y++) {
            for (int x = ASSEMBLE_HEX_BOX_TL.getX(); x < ASSEMBLE_HEX_BOX_BR.getX() && x < frame.getWidth(); x++) {
                int offset = y * stride + x * 4;
                if (offset + 2 >= px.length) continue;
                int c0 = px[offset] & 0xFF, g = px[offset + 1] & 0xFF, c2 = px[offset + 2] & 0xFF;
                // Green is the middle byte in both RGBA and BGRA, so "green dominates both others"
                // is channel-order safe. The hexagon body is the opposite case -- green sits BETWEEN
                // the other two -- which is equally order-safe to test.
                if (g >= 150 && g - c0 >= 50 && g - c2 >= 50) {
                    green++;
                } else {
                    int hi = Math.max(c0, c2), lo = Math.min(c0, c2);
                    if (hi > 150 && hi - lo > 40 && hi - g > 20) {
                        blue++;
                    }
                }
            }
        }
        boolean present = blue >= ASSEMBLE_HEX_MIN_BLUE && green >= ASSEMBLE_HEX_MIN_GREEN;
        logInfo(logLine("Assemble-confirm hexagon check: " + blue + " blue px (need "
                + ASSEMBLE_HEX_MIN_BLUE + ") and " + green + " green-tick px (need "
                + ASSEMBLE_HEX_MIN_GREEN + ") -- " + (present ? "present" : "absent")));
        return present;
    }

    private String logLine(String note) {
        return "MonumentRoutine | " + note;
    }

    /**
     * Confirmed live that Resource Stockpile Scan's "Overview"
     * panel does NOT close on a back press -- it's a game-rendered modal, not a
     * native Android view, so the system back key is simply ignored by it. A
     * different task later left a DIFFERENT panel open ("Resource &amp; Speedup
     * Summary") proving one hardcoded close spot will never keep up with however
     * many other tasks can leave something open -- rebuilt as a real loop: repeat
     * (press back several times, tap every known stray-panel close spot) and
     * re-check via search after each round, stopping the moment the badge or a
     * clean Home screen is confirmed.
     *
     * <p>
     * Root-caused live, by watching the actual screen after a run --
     * this game's own back-button behavior on the City/Home view is to ZOOM OUT to
     * the World strategic map, not to close nothing-there / exit. With nothing open
     * (the overwhelmingly common case), the blind {@code pressBack()} x3 x4-rounds
     * batch below zoomed the camera all the way out to World every single time --
     * confirmed by a live screenshot immediately after a run landing squarely on the
     * World map. From World, Monument's badge/building templates (Home-only) can
     * never match again, so it got permanently stuck until some unrelated task
     * happened to navigate back to Home for its own purposes. This is exactly what
     * described as the app "freaking out" / "almost exiting."
     * <p>
     * Fix: stop reinventing Home-recovery with raw back-presses. The framework
     * already has {@link dev.frostguard.engine.helper.NavigationHelper#ensureCorrectScreenLocation}
     * for exactly this -- it tells Home and World apart by template, taps the
     * correct zoom icon to get back to Home instead of guessing with more back
     * presses, and only falls back to a cautious single back-press-and-recheck loop
     * when the screen is genuinely unrecognized. Still try the known stray-panel
     * close spots first (real game-rendered modals that DON'T respond to back at
     * all), then hand recovery to the framework instead of a local back-press loop.
     */
    private void clearStrayPopups() {
        if (isScreenClear()) {
            logInfo(logLine("Screen already clear, no clearing needed."));
            return;
        }

        for (PointData closeSpot : KNOWN_STRAY_PANEL_CLOSE_SPOTS) {
            tapNear(closeSpot);
            sleepTask(300);
            if (isScreenClear()) {
                logInfo(logLine("Screen confirmed clear after a stray-panel close tap."));
                return;
            }
        }

        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);

        if (isScreenClear()) {
            logInfo(logLine("Screen confirmed clear after ensureCorrectScreenLocation(HOME)."));
        } else {
            logInfo(logLine("Home confirmed by ensureCorrectScreenLocation, but neither the reward badge "
                    + "nor the Monument building anchor matched -- proceeding anyway, the badge search "
                    + "right after this will catch a genuinely blocked screen."));
        }
    }

    private boolean isScreenClear() {
        return templateSearchHelper.locatePattern(
                TemplatesEnum.MONUMENT_REWARD_BADGE, SearchConfigConstants.QUICK_SEARCH).isFound()
                || templateSearchHelper.locatePattern(
                TemplatesEnum.MONUMENT_BUILDING_ANCHOR, SearchConfigConstants.QUICK_SEARCH).isFound();
    }

    // The camera-pan fallback that used to live here (findBadgeWithPanFallback(),
    // an 8-direction, up-to-16-tap sweep) is removed entirely -- "it pans to the right of lancer,
    // you see monument, then it just starts scrolling around." Removed for good; on a miss the
    // routine now just stops and reschedules (see findAndOpenBadgeViaLancer() above) instead of
    // panning around OR guessing at an unverified fixed-pixel coordinate.

    // Real fix by design after the threshold-tuning approach above
    // (kept, still a real improvement) drew a fair "why fight a fragile number instead of the
    // obvious signal" pushback: the individual "Claim" button is solid green; the disabled
    // lookalike that fooled the shape-based template match is NOT (measured live: real button
    // RGB avg ~(82,179,100), disabled lookalike ~(122,124,126) -- essentially grey, R/G/B all
    // within 4 of each other). Rather than rely on template-match score alone, every match is now
    // also color-verified against the live screen before it's trusted -- a shape match on a grey
    // button no longer gets tapped just because its score happened to clear a threshold.
    private static final int GREEN_DOMINANCE_MARGIN = 40;

    private void claimAllReadyRows() {
        for (int i = 0; i < MAX_CLAIM_LOOPS; i++) {
            ImageSearchResultData claimBtn = templateSearchHelper.locatePattern(
                    TemplatesEnum.MONUMENT_ATLAS_CLAIM_BUTTON, SearchConfigConstants.MONUMENT_ATLAS_CLAIM_BUTTON_SEARCH);
            if (!claimBtn.isFound()) {
                logInfo(logLine("No more Claim buttons visible (" + i + " claimed)."));
                break;
            }
            if (!isRegionPredominantlyGreen(claimBtn)) {
                logInfo(logLine("Claim button shape-matched at " + claimBtn.getPoint()
                        + " but the region isn't actually green -- almost certainly the disabled "
                        + "lookalike button, not a real ready claim. Stopping instead of tapping it."));
                break;
            }
            tapNear(claimBtn.getPoint());
            sleepTask(ACTION_SETTLE_MS);
            if (i == MAX_CLAIM_LOOPS - 1) {
                logWarning(logLine("Hit the claim-loop safety cap (" + MAX_CLAIM_LOOPS + ")."));
            }
        }

        // Observed live: navigation into this panel is fixed, but the routine never
        // tapped the bottom "Claim All" button at all -- the loop above only ever scans individual
        // per-row Claim buttons visible in the CURRENT scroll position, so a ready reward scrolled
        // out of view was silently left unclaimed. Claim All batches every currently-claimable
        // reward regardless of scroll position, so it's tapped once here as a real second pass, not
        // a fallback -- template search (real screenshot, tight crop), matching this routine's own
        // hard-learned lesson from the badge-tap saga: no fixed-pixel guessing.
        ImageSearchResultData claimAllBtn = templateSearchHelper.locatePattern(
                TemplatesEnum.MONUMENT_ATLAS_CLAIM_ALL_BUTTON, SearchConfigConstants.QUICK_SEARCH);
        if (claimAllBtn.isFound()) {
            logInfo(logLine("Claim All button found -- tapping it to sweep any remaining ready rewards."));
            tapNear(claimAllBtn.getPoint());
            sleepTask(ACTION_SETTLE_MS);
        } else {
            logInfo(logLine("No Claim All button visible (nothing left to batch-claim)."));
        }
    }

    // Caught live -- the Tundra Albums hub has its own fragment-count milestone
    // chest track at the top (separate from the per-category Atlas rewards handled above) that this
    // routine's own header comment had flagged as a known, unhandled gap. Live-verified by hand: the
    // currently-claimable chest is visually lit/glowing; tapping it opens a real "Rewards" popup
    // (confirmed: 100 diamonds + 2 mystery chests on a live claim), and the whole row scrolls left
    // afterward as the next threshold becomes the new rightmost slot. Only one calibration pass was
    // possible tonight, so this scans a few plausible slot positions along the row rather than
    // trusting a single fixed point -- the row's exact scroll offset at any given moment isn't fully
    // characterized yet.
    private static final PointData[] MILESTONE_CHEST_CANDIDATES = {
            new PointData(245, 178),
            new PointData(340, 178),
            new PointData(428, 178),
    };
    private static final PointData MILESTONE_REWARDS_TAP_ANYWHERE = new PointData(360, 1198);
    private static final int MAX_MILESTONE_CHEST_CLAIMS = 6;

    private void claimMilestoneChestsIfReady() {
        for (int claimed = 0; claimed < MAX_MILESTONE_CHEST_CLAIMS; claimed++) {
            boolean claimedThisPass = false;
            for (PointData candidate : MILESTONE_CHEST_CANDIDATES) {
                tapNear(candidate);
                sleepTask(600);

                String popupTitle = stringHelper.attemptRecognition(
                        new PointData(200, 260), new PointData(520, 340),
                        2, 150L, PANEL_TITLE_OCR_SETTINGS,
                        s -> s != null && !s.isBlank(),
                        s -> s);
                if (popupTitle != null && popupTitle.toLowerCase().contains("reward")) {
                    logInfo(logLine("Milestone chest ready at " + candidate + " -- claimed. Rewards: '"
                            + popupTitle + "'."));
                    tapNear(MILESTONE_REWARDS_TAP_ANYWHERE);
                    sleepTask(ACTION_SETTLE_MS);
                    claimedThisPass = true;
                    break;
                }
            }
            if (!claimedThisPass) {
                if (claimed == 0) {
                    logInfo(logLine("No milestone chest currently ready."));
                } else {
                    logInfo(logLine("Milestone chest track exhausted after " + claimed + " claim(s)."));
                }
                return;
            }
        }
        logWarning(logLine("Hit the milestone-chest safety cap (" + MAX_MILESTONE_CHEST_CLAIMS + ")."));
    }

    // Caught live -- ALBUMS_FRAGMENT_BACKPACK_BTN was documented as the Tundra
    // Albums hub's own button, but this method used to fire BEFORE the back-arrow tap, on a
    // screen where that coordinate doesn't correspond to a real button at all -- 0 rows ever
    // opened, with no error, because the panel-title check below just correctly declined every
    // time. : fixed by moving the call to after the back-arrow (see execute()) so
    // this now genuinely runs on the Tundra Albums hub, where ALBUMS_FRAGMENT_BACKPACK_BTN is
    // the real button -- live-verified hand-driven, screenshot-confirmed.

    // Live-verified by hand, full real clear-out (General Album -> Daybreak Island
    // x3 -> The Labyrinth, 5 packs total): the fixed-row model above was wrong on two counts.
    // (1) A row with multiple pack types side by side (e.g. Daybreak Island showing 3 colors at
    // once) RE-CENTERS its remaining icons after each one is opened -- tapping a fixed per-slot X
    // stops matching reality after the first tap in that row. (2) Once every pack in a row is gone,
    // that row collapses to an empty "No such Scene Fragment Pack owned" placeholder and the NEXT
    // category compacts upward into where the row above used to be -- so a fixed per-row Y doesn't
    // hold either, and previously-hidden categories (Rekindled Flames, Song of Heroes) can scroll
    // into view that BACKPACK_MAX_ROWS never accounted for. Real fix: don't trust any fixed slot.
    // Read the owned-count badge under each of the positions actually observed live across that
    // clear-out, tap whichever one genuinely shows a count, and rescan from scratch after every
    // single open (since everything can reflow) instead of marching through fixed rows.
    // Real live log evidence, not a guess -- the 3-across row below
    // (220/360/490, 548) assumed an old side-by-side icon layout. A live screenshot showed the
    // actual current layout is single-column (General Album stacked directly above Tundra
    // Alliance, both centered around x=360), and the run log confirmed the failure mode exactly:
    // OCR read a bogus "2" at the wrong (220, 548) candidate -- empty wood panel, nothing there --
    // tapped it, and the Fragment Backpack panel never came back because nothing was actually hit.
    // Observed live: a real run reported "No more owned packs found. Opened 0
    // total." while the Fragment Backpack icon itself was showing a real "15" badge -- General
    // Album was genuinely empty ("No such Scene Fragment Pack owned") that pass, and its own
    // placeholder block pushed Rekindled Flames and Divine Weapons further down than any existing
    // candidate reached (confirmed live: real icon centers at (355,590) and (355,800), owned-count
    // badges at (355,635) and (355,880) -- none of the 4 candidates below land close enough for
    // either). Added two more candidates at the real measured positions, and widened the badge
    // read box (18 -> 32 half-height) so it isn't relying on hitting the exact pixel again next
    // time this panel reflows by a slightly different amount.
    private static final PointData[] BACKPACK_ICON_CANDIDATES = {
            new PointData(360, 280),  // row 1 icon (General Album, confirmed by live screenshot)
            new PointData(360, 545),  // row 2 icon (Tundra Alliance, confirmed by live screenshot)
            new PointData(360, 587),  // legacy candidate, kept for a placeholder-shifted layout
            new PointData(360, 765),  // 3rd visual row, single icon
            new PointData(355, 590),  // General-Album-empty-shifted row 2 (Rekindled Flames), live-verified
            new PointData(355, 800),  // General-Album-empty-shifted row 3 (Divine Weapons), live-verified
    };
    /** Owned-count badge sits just under each candidate icon; read box is centered on that offset. */
    private static final int BACKPACK_BADGE_Y_OFFSET = 62;
    private static final int BACKPACK_BADGE_HALF_WIDTH = 45;
    private static final int BACKPACK_BADGE_HALF_HEIGHT = 32;
    private static final int BACKPACK_MAX_TOTAL_OPENS = 40;

    // Observed live: findAnyOwnedPackIcon() false-positived on candidate (360,587) --
    // the OCR "owned count" read at that offset actually landed on the unrelated Labyrinth hub's own
    // milestone-chest track digits, not a real pack. The bot tapped it, tapped where Enable should be,
    // tapped where a reward-reveal close should be -- all blind, all on the wrong screen -- and ended
    // up stuck on a completely different "Rewards ... Tap anywhere to exit" chest-reveal screen (a
    // different UI skin REWARD_REVEAL_TAP_ANYWHERE doesn't clear) for 7+ minutes until manually
    // quit. Two real fixes: (1) a hard wall-clock time budget on the whole pass, so an unrecognized
    // screen can never again silently eat minutes; (2) active recovery (repeated back-presses, which
    // already carry the quit-game-dialog safety net) instead of one blind close-tap that assumes
    // we're still on the screen it expects.
    private static final long BACKPACK_PASS_TIME_BUDGET_MS = 90_000;

    private void processFragmentBackpack() {
        processFragmentBackpack(ALBUMS_FRAGMENT_BACKPACK_BTN);
    }

    /**
     * Extracted to accept the open-button location, so
     * {@link #handlePuzzleReadyChain()} can reuse this same hardened loop from the puzzle
     * overview screen's own Fragment Backpack icon (found via real template search) instead
     * of the Tundra Albums hub's fixed {@link #ALBUMS_FRAGMENT_BACKPACK_BTN} -- two different
     * screens, same shared Fragment Backpack panel underneath.
     */
    private void processFragmentBackpack(PointData openButton) {
        long deadline = System.currentTimeMillis() + BACKPACK_PASS_TIME_BUDGET_MS;

        tapNear(openButton);
        sleepTask(PANEL_SETTLE_MS);

        // Observed live: this came back null the same way the Alliance Trade
        // gate did earlier tonight -- right region this time (BACKPACK_TITLE_TL/BR is genuinely
        // this panel's own title box), but only one OCR pass right after PANEL_SETTLE_MS with no
        // second attempt if the panel just hadn't finished rendering yet. Per the's direct
        // instruction to stop over-gating on exact keyword matches ("worst case is a false
        // positive and it exits out anyway, who cares"): retry once after a longer settle before
        // giving up, and accept ANY non-blank read as confirmation instead of requiring the
        // literal word "fragment".
        String panelTitle = stringHelper.attemptRecognition(
                BACKPACK_TITLE_TL, BACKPACK_TITLE_BR,
                2, 150L, PANEL_TITLE_OCR_SETTINGS,
                s -> s != null && !s.isBlank(),
                s -> s);
        if (panelTitle == null) {
            sleepTask(PANEL_SETTLE_MS);
            panelTitle = stringHelper.attemptRecognition(
                    BACKPACK_TITLE_TL, BACKPACK_TITLE_BR,
                    2, 150L, PANEL_TITLE_OCR_SETTINGS,
                    s -> s != null && !s.isBlank(),
                    s -> s);
        }
        if (panelTitle == null) {
            logWarning(logLine("Fragment Backpack panel not confirmed after tapping "
                    + openButton + " (read: '" + panelTitle
                    + "') -- skipping the backpack pass this run rather than guessing blindly on the "
                    + "wrong screen. " + dumpDiagnosticFrame("backpack-title-null")));
            recoverTowardHome();
            return;
        }

        int opened = 0;
        while (opened < BACKPACK_MAX_TOTAL_OPENS) {
            if (System.currentTimeMillis() > deadline) {
                logWarning(logLine("Fragment Backpack pass exceeded its " + (BACKPACK_PASS_TIME_BUDGET_MS / 1000)
                        + "s time budget -- something is stuck on a screen this code doesn't recognize. "
                        + "Aborting and recovering rather than hanging. Opened " + opened + " total."));
                recoverTowardHome();
                return;
            }

            if (!waitForFragmentBackpackPanel()) {
                logWarning(logLine("Fragment Backpack panel didn't come back after the last pack open "
                        + "-- likely tapped something that wasn't actually a pack. Recovering instead of "
                        + "assuming the normal close tap still applies. Opened " + opened + " total."));
                recoverTowardHome();
                return;
            }

            PointData target = findAnyOwnedPackIcon();
            if (target == null) {
                logInfo(logLine("No more owned packs found. Opened " + opened + " total."));
                break;
            }

            opened++;
            logInfo(logLine("Opening pack " + opened + " at " + target + "."));
            tapNear(target);
            sleepTask(ACTION_SETTLE_MS);

            // FindAnyOwnedPackIcon() reads an owned-count badge under each candidate
            // icon, but when an album has no packs the panel shows "No such Scene Fragment Pack owned"
            // with an OBTAIN button roughly where that badge would be (see
            // labyrinth-debug/monument/state_current.png). The badge whitelist is
            // "OwnedOWNED:0123456789 ", so an Obtain button can read as a number and the candidate
            // gets returned as if it were a real pack. Tapping it opens the "Obtain more" GOLD KEY
            // PURCHASE dialog -- confirmed live at 01:22, and that stray dialog is what the Alliance
            // Trade step then landed on and OCR'd as 'd to perform i epic pbuyy'.
            //
            // Nothing could be spent (the Buy button needs its own confirm, and PACK_DETAIL_ENABLE_BTN
            // at (358,905) is well clear of it), but prodding a purchase screen once per pass is not
            // acceptable behaviour. Check the panel title before the Enable tap: the real pack detail
            // sits under "Fragment Backpack", the purchase dialog announces itself as "Obtain more".
            String detailTitle = stringHelper.attemptRecognition(
                    OBTAIN_DIALOG_TITLE_TL, OBTAIN_DIALOG_TITLE_BR,
                    2, 150L, PANEL_TITLE_OCR_SETTINGS,
                    s -> s != null && !s.isBlank(),
                    s -> s.toLowerCase());
            if (detailTitle != null && detailTitle.contains("obtain")) {
                logInfo(logLine("Candidate " + target + " was not a pack -- it opened the '" + detailTitle.trim()
                        + "' purchase dialog, so that album has no packs left and its Obtain button was "
                        + "misread as an owned count. Closing it and ending the backpack pass. Opened "
                        + (opened - 1) + " real pack(s)."));
                tapNear(OBTAIN_DIALOG_CLOSE_X);
                sleepTask(ACTION_SETTLE_MS);
                opened--;
                break;
            }

            tapNear(PACK_DETAIL_ENABLE_BTN);
            sleepTask(PACK_OPEN_SETTLE_MS);

            // The reward-reveal screen has two visual variants -- a quick single-icon
            // flash for small stacks, and a slower multi-piece grid intro for bigger ones -- and the
            // grid variant is still mid-animation (not yet tappable) right when a single "tap anywhere"
            // would have landed before. Tap twice with a settle between; harmless no-op on the fast
            // variant since it's already closed by the second tap, required for the slow one.
            tapNear(REWARD_REVEAL_TAP_ANYWHERE);
            sleepTask(PACK_OPEN_SETTLE_MS);
            tapNear(REWARD_REVEAL_TAP_ANYWHERE);
            sleepTask(PACK_OPEN_SETTLE_MS);
        }

        if (opened >= BACKPACK_MAX_TOTAL_OPENS) {
            logWarning(logLine("Hit the total-opens safety cap (" + BACKPACK_MAX_TOTAL_OPENS + ")."));
        }

        tapNear(BACKPACK_CLOSE_X);
        sleepTask(ACTION_SETTLE_MS);
    }

    /** Active recovery back toward Home when the Fragment Backpack flow lands somewhere unrecognized --
     *  several back-presses (each carrying the shared quit-game-dialog safety net) rather than a single
     *  blind tap at a coordinate that assumed a screen state that turned out to be wrong. */
    private void recoverTowardHome() {
        for (int i = 0; i < 4 && !isScreenClear(); i++) {
            pressBack();
            sleepTask(500);
        }
        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
    }

    /** Scans every known icon position for a real owned-count badge and returns the first one found,
     *  or null if nothing owned is visible anywhere on the current panel state. */
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

    private static final PointData PIECE_PICKER_REQUEST_BTN_LABEL_TL = new PointData(480, 865);
    private static final PointData PIECE_PICKER_REQUEST_BTN_LABEL_BR = new PointData(610, 915);

    /**
     * Rebuilt around the real My Requests row's three states (see the class-
     * level "Alliance Trade panel" constants comment for the live-verified detail). Reads the
     * button label via OCR every loop instead of assuming it's always "Request" -- a Claim is
     * claimed, a Requesting row is skipped, and only a genuine Request tap spends one of the
     * 3 daily requests.
     */
    /** Reads one button-label region, lower-cased, or null when nothing legible is there. */
    private String readButtonLabel(PointData tl, PointData br) {
        return stringHelper.attemptRecognition(
                tl, br, 2, 150L, PANEL_TITLE_OCR_SETTINGS,
                s -> s != null && !s.isBlank(),
                s -> s.toLowerCase());
    }

    private void processAllianceTradeRequests() {
        for (int i = 0; i < MAX_REQUEST_LOOPS; i++) {
            // Claim is decided by colour, not OCR -- see isClaimButtonPresent(). It's checked first
            // because it's the state that has something to collect.
            if (isClaimButtonPresent()) {
                logInfo(logLine("My Requests row is claimable -- tapping Claim."));
                tapNear(MY_REQUESTS_CLAIM_BTN);
                sleepTask(ACTION_SETTLE_MS);
                tapNear(CLAIM_REWARD_TAP_ANYWHERE);
                sleepTask(ACTION_SETTLE_MS);
                continue; // re-read the row -- a fresh Request button should be there now.
            }

            // No Claim slab, so the row is in the centred Request / Requesting... state. That
            // button sits on flat tan, which the OCR pipeline handles fine.
            String label = readButtonLabel(MY_REQUESTS_REQUEST_LABEL_TL, MY_REQUESTS_REQUEST_LABEL_BR);

            if (label == null || label.isBlank()) {
                logInfo(logLine("No Claim button, and the centred Request button was unreadable -- "
                        + "moving on rather than guessing. "
                        + dumpDiagnosticFrame("my-requests-label-null")));
                return;
            }

            if (label.contains("requesting")) {
                logInfo(logLine("My Requests already has a pending Requesting... row -- nothing to do."));
                return;
            }

            if (!label.contains("request")) {
                logInfo(logLine("My Requests button label read as '" + label
                        + "' -- not a recognized state. Moving on rather than guessing. "
                        + dumpDiagnosticFrame("my-requests-label-unrecognized")));
                return;
            }

            String leftText = stringHelper.attemptRecognition(
                    MY_REQUESTS_LEFT_TL, MY_REQUESTS_LEFT_BR,
                    2, 150L, REQUESTS_LEFT_OCR_SETTINGS,
                    s -> s != null && !s.isBlank(),
                    s -> s);
            Integer requestsLeft = leftText == null ? null : RegexNumberParser.extractByPattern(
                    leftText, Pattern.compile("\\((\\d+)\\s*/"));
            if (requestsLeft == null || requestsLeft <= 0) {
                // Same discipline as everywhere else tonight -- report the raw text
                // and the screen, never just "couldn't read it". A null here is ambiguous between a
                // genuinely exhausted counter, an OCR miss, and the post-Claim reward reveal not
                // having closed back to the panel yet, and those need different fixes.
                logInfo(logLine("Not filing a new request: counter parsed as " + requestsLeft
                        + " from raw text '" + (leftText == null ? "<null>" : leftText.replace("\n", " ").trim())
                        + "' (region " + MY_REQUESTS_LEFT_TL + "->" + MY_REQUESTS_LEFT_BR + "). "
                        + dumpDiagnosticFrame("my-requests-counter-unparsed")));
                return;
            }

            tapNear(MY_REQUESTS_REQUEST_BTN);
            sleepTask(PANEL_SETTLE_MS);

            // Live-verified that this can land on the target puzzle's own grid
            // with an animated hand pointing at an arbitrary cell, instead of going straight to
            // the detail popup PIECE_PICKER_REQUEST_BTN below assumes -- see the class-level
            // comment above these constants. No real template exists yet for that hand graphic,
            // so rather than guess a grid-cell coordinate, confirm the detail popup's own
            // Request/Obtain button text is actually present before tapping it.
            String detailLabel = stringHelper.attemptRecognition(
                    PIECE_PICKER_REQUEST_BTN_LABEL_TL, PIECE_PICKER_REQUEST_BTN_LABEL_BR,
                    2, 150L, PANEL_TITLE_OCR_SETTINGS,
                    s -> s != null && !s.isBlank(),
                    s -> s.toLowerCase());
            if (detailLabel == null || !detailLabel.contains("request")) {
                logWarning(logLine("Tapped My Requests' Request button but the piece-detail popup's own "
                        + "Request button wasn't confirmed via OCR (read: '" + detailLabel + "') -- likely "
                        + "landed on the hand-pointer grid screen instead (known gap, no template yet). "
                        + "Not tapping a guessed grid cell. Backing out instead of spending a daily request blind."));
                recoverTowardHome();
                return;
            }

            tapNear(PIECE_PICKER_REQUEST_BTN);
            sleepTask(ACTION_SETTLE_MS);
            // The "Confirm daily requests remaining" Tips dialog only appears the first
            // time per session -- harmless no-op tap if it's not there.
            tapNear(PIECE_PICKER_TIPS_CONFIRM);
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

            // Only send when a duplicate is actually owned (>=2) --
            // "Owned: 1" means it's their only copy, leave it alone.
            if (owned != null && owned >= 2) {
                logInfo(logLine("Ally Requests row " + row + ": owned " + owned + ", sending."));
                tapNear(sendBtn);
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
