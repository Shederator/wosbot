package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.FormationSlots;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.MarchResourceType;
import dev.frostguard.api.domain.MarchSlotState;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.input.TapJitterPolicy;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.nav.RallyFlagCoordinates;
import dev.frostguard.engine.nav.SidebarSection;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.color.PixelStats;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.logging.ProfileContextLogger;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;
import dev.frostguard.vision.ocr.OcrEngine;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

// Handles march-slot availability checks, rally flag interaction,
// and left-panel menu toggling for deployment workflows.
public class MarchHelper {

    private static final int SLOT_COUNT = 6;
    // A padlock matches its own icon at 98-100%; an unlocked slot never exceeds ~37%. Half a slot of
    // tolerance absorbs the tile drift without ever reaching a neighbouring slot (~74px apart).
    private static final double LOCKED_FLAG_THRESHOLD = 85;
    private static final int FLAG_SLOT_TOLERANCE_PX = 35;
    // The horizontal strip accepts dragging from a formation tile, not from its blue gaps. Slot 8
    // is fully visible at x=582 in the initial view.
    private static final PointData FORMATION_SCROLL_INITIAL_FROM = new PointData(582, 120);
    private static final PointData FORMATION_SCROLL_INITIAL_TO = new PointData(182, 120);
    private static final int FORMATION_SCROLL_DURATION_MS = 600;
    private static final long FORMATION_SCROLL_SETTLE_MS = 800;
    // "Idle" measures ~145 white pixels, a countdown ~255-285, so the gap is wide. Orange "Unlock"
    // (~260) and red "Unavailable" (~565) never overlap white; stationed rows have no status line.
    private static final int COLOUR_PRESENT_MIN = 60;
    // Gather icons sit on a green disc (~1000-1200 green pixels); every other icon has none.
    // The returning icon self-matches at 100%; the next closest icon reaches only ~68%.
    private static final double RETURNING_ICON_THRESHOLD = 85;
    private static final double STATUS_TEXT_THRESHOLD = 90;
    private static final double ACTIVITY_ICON_THRESHOLD = 85;
    // A non-gather row icon still contributes enough non-background colour to prove the row is not idle.
    private static final int ICON_PRESENT_MIN = 500;

    private final EmulatorController emu;
    private final String device;
    private final TapInteractionService taps;
    private final ResilientOcrExecutor<String> ocrStrings;
    private final ProfileContextLogger log;
    private final SidebarNavigator sidebar;

    public MarchHelper(EmulatorController emuManager, String emulatorNumber,
                       ResilientOcrExecutor<String> stringHelper, AccountDescriptor profile) {
        this.emu = emuManager;
        this.device = emulatorNumber;
        this.taps = TapInteractionService.forController(emuManager, emulatorNumber);
        this.ocrStrings = stringHelper;
        this.log = new ProfileContextLogger(MarchHelper.class, profile);
        this.sidebar = new SidebarNavigator(emuManager, emulatorNumber, profile);
    }

    public boolean checkMarchesAvailable() {
        boolean anyIdle = readMarchQueue().stream().anyMatch(MarchSlotState::isIdle);
        if (!anyIdle) {
            log.info("No idle march slot");
        }
        return anyIdle;
    }

    // Reads every March Queue row from a single screenshot. Text is deliberately avoided: the status
    // line is classified by colour (white "Idle", orange "Unlock", red "Unavailable", nothing at all
    // for stationed troops) and the activity by its icon. Only the countdown needs OCR.
    public List<MarchSlotState> readMarchQueue() {
        openLeftMenuCitySection(false);
        try {
            return readVisibleMarchQueue();
        } finally {
            dismissLeftPanel();
        }
    }

    /**
     * Reads the March Queue using the deliberate single-pass panel interaction. This is intended
     * for flows that already normalize their world state and do not need legacy multi-tap recovery.
     */
    public List<MarchSlotState> readMarchQueueSinglePass() {
        openLeftMenuCitySectionOnce(false);
        try {
            return readVisibleMarchQueue();
        } finally {
            dismissLeftPanel();
        }
    }

    /**
     * Reads the already-open wilderness March Queue panel without changing its state. This is for
     * workflows such as Intel recall that must inspect rows and then interact with the same panel.
     */
    public List<MarchSlotState> readVisibleMarchQueue() {
        try {
            RawImageData frame = emu.captureScreen(device);
            BufferedImage image = dev.frostguard.vision.convert.ImageConverter.toBufferedImage(frame);

            List<MarchSlotState> slots = new ArrayList<>(SLOT_COUNT);
            for (int index = 0; index < SLOT_COUNT; index++) {
                slots.add(readSlot(frame, image, index));
            }
            log.info("March queue: " + slots.stream()
                    .map(slot -> "#" + slot.slot() + "=" + slot.status()
                            + "/" + slot.activityType()
                            + "/" + slot.movementPhase()
                            + (slot.resourceType() == null ? "" : "/" + slot.resourceType())
                            + (slot.countdown() == null ? "" : "(" + slot.countdown() + ")")
                            + (slot.evidence() == null ? "" : "{" + slot.evidence() + "}"))
                    .collect(Collectors.joining(" ")));
            return slots;
        } catch (Exception ex) {
            log.error("March queue read error: " + ex.getMessage());
            return List.of();
        }
    }

    private MarchSlotState readSlot(RawImageData frame, BufferedImage image, int index) {
        int slot = index + 1;
        AreaData status = CommonGameAreas.MARCH_QUEUE_STATUS[index];
        AreaData title = CommonGameAreas.MARCH_QUEUE_TITLE[index];
        AreaData icon = CommonGameAreas.MARCH_QUEUE_ICON[index];

        int orange = PixelStats.count(image, status, GameColors::isActionOrange);
        int red = PixelStats.count(image, status, GameColors::isBlockedRed);
        int white = PixelStats.count(image, status, GameColors::isLabelWhite);
        int gatherGreen = PixelStats.count(image, icon, GameColors::isVividGreen);
        int iconColour = PixelStats.count(image, icon, pixel -> GameColors.isLabelWhite(pixel)
                || GameColors.isVividGreen(pixel)
                || GameColors.isActionOrange(pixel)
                || GameColors.isBlockedRed(pixel)
                || GameColors.isMarchQueueIconBlue(pixel));
        boolean returning = emu.locatePattern(device, frame, TemplatesEnum.MARCH_QUEUE_RETURNING_ICON,
                icon.topLeft(), icon.bottomRight(), RETURNING_ICON_THRESHOLD).isFound();
        boolean rally = matchesActivityIcon(frame, icon, TemplatesEnum.MARCH_QUEUE_RALLY_ICON);
        boolean attackIcon = matchesActivityIcon(frame, icon, TemplatesEnum.MARCH_QUEUE_ATTACK_ICON);
        boolean encampment = matchesActivityIcon(frame, icon, TemplatesEnum.MARCH_QUEUE_ENCAMPMENT_ICON);
        boolean reinforcement = matchesActivityIcon(frame, icon, TemplatesEnum.MARCH_QUEUE_REINFORCEMENT_ICON);
        boolean garrisoned = matchesActivityIcon(frame, icon, TemplatesEnum.MARCH_QUEUE_GARRISONED_ICON);
        boolean slotFlag = emu.locatePattern(device, frame, TemplatesEnum.MARCH_QUEUE_SLOT_FLAG_ICON,
                icon.topLeft(), icon.bottomRight(), RETURNING_ICON_THRESHOLD).isFound();
        boolean idleText = matchesStatus(frame, status, TemplatesEnum.MARCH_QUEUE_STATUS_IDLE);
        boolean unlockText = matchesStatus(frame, status, TemplatesEnum.MARCH_QUEUE_STATUS_UNLOCK);
        boolean unavailableText = matchesStatus(frame, status, TemplatesEnum.MARCH_QUEUE_STATUS_UNAVAILABLE);
        boolean goToText = matchesTitle(frame, title, TemplatesEnum.MARCH_QUEUE_TEXT_GO_TO);
        boolean gatheringText = matchesTitle(frame, title, TemplatesEnum.MARCH_QUEUE_TEXT_GATHERING);
        boolean attackText = matchesTitle(frame, title, TemplatesEnum.MARCH_QUEUE_TEXT_ATTACK);
        boolean terminalStatus = idleText || unlockText || unavailableText
                || orange >= COLOUR_PRESENT_MIN || red >= COLOUR_PRESENT_MIN;
        Duration countdown = !terminalStatus && white >= COLOUR_PRESENT_MIN ? readCountdown(index) : null;
        MarchResourceType resourceType = detectGatherResource(frame, icon);
        boolean activityIconPresent = iconColour >= ICON_PRESENT_MIN && !slotFlag;

        return MarchQueueSlotClassifier.classify(new MarchQueueSlotClassifier.Signals(
                slot, orange, red, white, gatherGreen, returning, rally, attackIcon, encampment, reinforcement,
                garrisoned,
                idleText, unlockText, unavailableText, goToText, gatheringText, attackText,
                activityIconPresent, countdown, resourceType));
    }

    private boolean matchesStatus(RawImageData frame, AreaData status, TemplatesEnum template) {
        return emu.locatePattern(device, frame, template, status.topLeft(), status.bottomRight(),
                STATUS_TEXT_THRESHOLD).isFound();
    }

    private boolean matchesTitle(RawImageData frame, AreaData title, TemplatesEnum template) {
        return emu.locatePattern(device, frame, template, title.topLeft(), title.bottomRight(),
                STATUS_TEXT_THRESHOLD).isFound();
    }

    private MarchResourceType detectGatherResource(RawImageData frame, AreaData icon) {
        if (matchesResource(frame, icon, TemplatesEnum.MARCH_QUEUE_MEAT_ICON)) {
            return MarchResourceType.MEAT;
        }
        if (matchesResource(frame, icon, TemplatesEnum.MARCH_QUEUE_WOOD_ICON)) {
            return MarchResourceType.WOOD;
        }
        if (matchesResource(frame, icon, TemplatesEnum.MARCH_QUEUE_COAL_ICON)) {
            return MarchResourceType.COAL;
        }
        if (matchesResource(frame, icon, TemplatesEnum.MARCH_QUEUE_IRON_ICON)) {
            return MarchResourceType.IRON;
        }
        return MarchResourceType.UNKNOWN;
    }

    private boolean matchesResource(RawImageData frame, AreaData icon, TemplatesEnum template) {
        return emu.locatePattern(device, frame, template, icon.topLeft(), icon.bottomRight(), 80).isFound();
    }

    private boolean matchesActivityIcon(RawImageData frame, AreaData icon, TemplatesEnum template) {
        return emu.locatePattern(device, frame, template, icon.topLeft(), icon.bottomRight(),
                ACTIVITY_ICON_THRESHOLD).isFound();
    }

    private Duration readCountdown(int index) {
        AreaData timer = CommonGameAreas.MARCH_QUEUE_TIMER[index];
        String text = ocrStrings.attemptRecognition(timer.topLeft(), timer.bottomRight(),
                2, 150L, CommonOCRSettings.MARCH_QUEUE_TIMER_SETTINGS,
                GameTimeUtils::isAcceptedFormat, value -> value);
        return text == null ? null : GameTimeUtils.parseDuration(text);
    }

    // A slot is inspected before it is tapped: padlock evidence rejects locked slots, while the
    // measured white-flag signal distinguishes a saved formation from an empty visible tile.
    public boolean selectFlag(Integer flagNumber) {
        if (flagNumber == null) {
            log.debug("No formation configured - skipping selection");
            return true;
        }
        if (!FormationSlots.supports(flagNumber)) {
            log.warn("Formation #" + flagNumber + " is unsupported; supported range is "
                    + FormationSlots.MIN + "-" + FormationSlots.MAX);
            return false;
        }
        FormationFrame frame = flagNumber <= 8 ? captureFormationFrame(flagNumber) : moveToRightFormationEnd();
        if (frame == null) {
            return false;
        }
        FormationSlotStateClassifier.State state = inspectFormationSlot(flagNumber, frame);
        if (state != FormationSlotStateClassifier.State.SAVED) {
            log.warn("Formation #" + flagNumber + " is " + state.name().toLowerCase().replace('_', ' ')
                    + " - not selecting it");
            return false;
        }
        log.debug("Selecting formation #" + flagNumber);
        // Flag slots are narrow fixed positions — keep the jitter tightly bounded.
        taps.tapNear(RallyFlagCoordinates.pointForFlag(flagNumber), TapJitterPolicy.DEFAULT_POINT_JITTER_RADIUS);
        interruptibleWait(300);
        return true;
    }

    // Locating every padlock across the strip and mapping each to its nearest slot is immune to the
    // few pixels of tile drift; a per-slot window would leave a 58px template barely any room to slide.
    private FormationFrame captureFormationFrame(int flagNumber) {
        try {
            RawImageData raw = emu.captureScreen(device);
            return new FormationFrame(raw, dev.frostguard.vision.convert.ImageConverter.toBufferedImage(raw));
        } catch (Exception ex) {
            log.warn("Could not inspect formation #" + flagNumber + ": " + ex.getMessage());
            return null;
        }
    }

    private FormationFrame moveToRightFormationEnd() {
        FormationFrame initial = captureFormationFrame(10);
        if (initial == null) {
            return null;
        }
        emu.swipeScreen(device, FORMATION_SCROLL_INITIAL_FROM, FORMATION_SCROLL_INITIAL_TO,
                FORMATION_SCROLL_DURATION_MS);
        interruptibleWait(FORMATION_SCROLL_SETTLE_MS);
        FormationFrame right = captureFormationFrame(10);
        if (right == null || !FormationBarFrameComparator.moved(
                initial.image(), right.image(), CommonGameAreas.RALLY_FLAG_BAR)) {
            log.warn("Formation bar did not move to the right-end view - not selecting a high slot");
            return null;
        }

        return right;
    }

    private FormationSlotStateClassifier.State inspectFormationSlot(int flagNumber, FormationFrame frame) {

        int slotX = RallyFlagCoordinates.pointForFlag(flagNumber).getX();
        List<ImageSearchResultData> padlocks = emu.locateAllPatterns(device, frame.raw(),
                TemplatesEnum.RALLY_LOCKED_FLAG_SLOT,
                CommonGameAreas.RALLY_FLAG_BAR.topLeft(),
                CommonGameAreas.RALLY_FLAG_BAR.bottomRight(),
                LOCKED_FLAG_THRESHOLD, FormationSlots.MAX);

        // The multi-hit matcher logs nothing of its own, so record what it saw.
        log.debug("Formation bar: " + padlocks.size() + " padlock(s) located while checking #" + flagNumber);

        boolean padlocked = false;
        for (ImageSearchResultData padlock : padlocks) {
            if (Math.abs(padlock.getPoint().getX() - slotX) <= FLAG_SLOT_TOLERANCE_PX) {
                log.info("Formation #" + flagNumber + " padlocked at " + padlock.getPoint()
                        + " score=" + padlock.getMatchScore());
                padlocked = true;
                break;
            }
        }
        int whitePixels = PixelStats.count(frame.image(), RallyFlagCoordinates.areaForFlag(flagNumber),
                GameColors::isLabelWhite);
        FormationSlotStateClassifier.State state = FormationSlotStateClassifier.classify(padlocked, whitePixels);
        log.debug("Formation bar: checking #" + flagNumber + " state=" + state
                + " whitePixels=" + whitePixels + " padlocks=" + padlocks.size());
        return state;
    }

    private record FormationFrame(RawImageData raw, BufferedImage image) {
    }

    public void openLeftMenuCitySection(boolean cityTab) {
        log.debug("Left menu — " + (cityTab ? "city" : "wilderness"));
        if (!sidebar.openSectionAtTop(cityTab ? SidebarSection.CITY : SidebarSection.WILDERNESS)) {
            throw new IllegalStateException("Could not open the requested sidebar section");
        }
    }

    // Closes the left panel only after the selected tab proves that it is open.
    public void closeLeftMenu() {
        dismissLeftPanel();
    }

    /**
     * Compatibility entry point for flows that own the panel lifecycle and guarantee a matching
     * {@link #closeLeftMenu()} in a finally block. State verification is identical to the normal path.
     */
    public void openLeftMenuCitySectionOnce(boolean cityTab) {
        log.debug("Left menu single-pass - " + (cityTab ? "city" : "wilderness"));
        openLeftMenuCitySection(cityTab);
    }

    private void dismissLeftPanel() {
        log.debug("Closing left menu");
        sidebar.close();
    }

    private void interruptibleWait(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
