package dev.frostguard.engine.helper;

import java.awt.image.BufferedImage;
import java.util.Optional;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.nav.SidebarFrameClassifier;
import dev.frostguard.engine.nav.SidebarSection;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.logging.ProfileContextLogger;

/** State-verified navigation through the City, Wilderness, and Daily sidebar. */
public final class SidebarNavigator {

    private static final int SECTION_SETTLE_MS = 400;
    private static final int SCROLL_SETTLE_MS = 500;
    private static final int DESTINATION_SETTLE_MS = 2_000;
    private static final int RESET_SWIPES = 3;
    private static final int ROW_ICON_THRESHOLD = 88;
    private static final int GO_LEFT_OFFSET = 337;
    private static final int GO_RIGHT_OFFSET = 383;
    private static final int GO_HALF_HEIGHT = 25;

    private final EmulatorController emu;
    private final String device;
    private final TapInteractionService taps;
    private final TemplateSearchHelper searcher;
    private final ProfileContextLogger log;

    public SidebarNavigator(EmulatorController emu, String device, AccountDescriptor profile) {
        this.emu = emu;
        this.device = device;
        this.taps = TapInteractionService.forController(emu, device);
        this.searcher = new TemplateSearchHelper(emu, device, profile);
        this.log = new ProfileContextLogger(SidebarNavigator.class, profile);
    }

    public boolean openSection(SidebarSection target) {
        return openSection(target, false);
    }

    public boolean navigateTo(SidebarDestination destination) {
        if (!openSectionAtTop(destination.section())) {
            return false;
        }
        for (int swipe = 0; swipe <= destination.scanSwipes(); swipe++) {
            ImageSearchResultData rowIcon = locateRowIcon(destination);
            if (rowIcon != null && rowIcon.isFound()) {
                AreaData go = goButtonFor(rowIcon);
                log.info("Sidebar destination found: " + destination + " icon=" + rowIcon
                        + " go=" + go);
                taps.tapInside(go, 1, DESTINATION_SETTLE_MS);
                if (selectedSection().isEmpty()) {
                    return true;
                }
                log.warn("Sidebar stayed open after tapping destination " + destination);
                return false;
            }

            if (swipe < destination.scanSwipes()) {
                emu.swipeScreen(device, CommonGameAreas.SIDEBAR_SCROLL_FROM,
                        CommonGameAreas.SIDEBAR_SCROLL_TO, 400);
                interruptibleWait(SCROLL_SETTLE_MS);
                if (selectedSection().orElse(null) != destination.section()) {
                    log.warn("Sidebar state changed while scanning for " + destination);
                    return false;
                }
            }
        }

        log.warn("Sidebar destination not found within bounded scan: " + destination
                + " swipes=" + destination.scanSwipes());
        return false;
    }

    public boolean close() {
        if (selectedSection().isEmpty()) {
            return true;
        }
        taps.tapInside(CommonGameAreas.LEFT_MENU_CLOSE, 1, SECTION_SETTLE_MS);
        boolean closed = selectedSection().isEmpty();
        if (!closed) {
            log.warn("Sidebar close control did not close the panel");
        }
        return closed;
    }

    public boolean openSectionAtTop(SidebarSection section) {
        return openSection(section, true);
    }

    static NextOpenAction nextOpenAction(Optional<SidebarSection> current, SidebarSection target,
                                         boolean resetRequested, boolean resetComplete) {
        if (current.isEmpty()) {
            return NextOpenAction.OPEN_PANEL;
        }
        if (current.get() != target) {
            return NextOpenAction.SELECT_SECTION;
        }
        if (resetRequested && !resetComplete) {
            return NextOpenAction.RESET_TO_TOP;
        }
        return NextOpenAction.DONE;
    }

    private boolean openSection(SidebarSection target, boolean resetRequested) {
        Optional<SidebarSection> current = selectedSection();
        boolean resetComplete = false;

        while (true) {
            NextOpenAction action = nextOpenAction(current, target, resetRequested, resetComplete);
            switch (action) {
                case OPEN_PANEL -> {
                    if (!isRootScreen()) {
                        log.warn("Refusing to tap the sidebar trigger without a Home or World anchor");
                        return false;
                    }
                    log.debug("Sidebar closed; opening it once before selecting " + target);
                    taps.tapInside(CommonGameAreas.LEFT_MENU_TRIGGER, 1, SECTION_SETTLE_MS);
                    current = selectedSection();
                    if (current.isEmpty()) {
                        log.warn("Sidebar did not open after one verified trigger tap");
                        return false;
                    }
                }
                case SELECT_SECTION -> {
                    taps.tapInside(CommonGameAreas.sidebarTab(target), 1, SECTION_SETTLE_MS);
                    current = selectedSection();
                    if (current.orElse(null) != target) {
                        log.warn("Sidebar section selection failed: requested=" + target
                                + " observed=" + current.map(Enum::name).orElse("closed/unknown"));
                        return false;
                    }
                    log.debug("Sidebar section selected: " + target);
                }
                case RESET_TO_TOP -> {
                    if (!resetToTop(target)) {
                        return false;
                    }
                    resetComplete = true;
                    current = Optional.of(target);
                }
                case DONE -> {
                    log.debug("Sidebar section ready: " + target
                            + (resetRequested ? " at top" : " without scroll reset"));
                    return true;
                }
            }
        }
    }

    static AreaData goButtonFor(ImageSearchResultData rowIcon) {
        PointData center = rowIcon.getPoint();
        if (center == null) {
            throw new IllegalArgumentException("A located row icon is required");
        }
        int top = Math.max(CommonGameAreas.SIDEBAR_CONTENT.topLeft().getY(), center.getY() - GO_HALF_HEIGHT);
        int bottom = Math.min(CommonGameAreas.SIDEBAR_CONTENT.bottomRight().getY(), center.getY() + GO_HALF_HEIGHT);
        return new AreaData(
                new PointData(center.getX() + GO_LEFT_OFFSET, top),
                new PointData(center.getX() + GO_RIGHT_OFFSET, bottom));
    }

    private ImageSearchResultData locateRowIcon(SidebarDestination destination) {
        return searcher.locatePattern(destination.rowIcon(),
                TemplateSearchHelper.SearchConfig.builder()
                        .withMaxAttempts(1)
                        .withThreshold(ROW_ICON_THRESHOLD)
                        .withArea(CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN)
                        .build());
    }

    private boolean resetToTop(SidebarSection expected) {
        for (int i = 0; i < RESET_SWIPES; i++) {
            emu.swipeScreen(device, CommonGameAreas.SIDEBAR_RESET_FROM,
                    CommonGameAreas.SIDEBAR_RESET_TO, 300);
            interruptibleWait(SCROLL_SETTLE_MS);
        }
        if (selectedSection().orElse(null) != expected) {
            log.warn("Sidebar state changed while resetting " + expected + " to the top");
            return false;
        }
        return true;
    }

    private boolean isRootScreen() {
        return searcher.locatePattern(TemplatesEnum.GAME_HOME_FURNACE,
                SearchConfigConstants.DEFAULT_SINGLE).isFound()
                || searcher.locatePattern(TemplatesEnum.GAME_HOME_WORLD,
                        SearchConfigConstants.DEFAULT_SINGLE).isFound();
    }

    private Optional<SidebarSection> selectedSection() {
        BufferedImage frame = ImageConverter.toBufferedImage(emu.captureScreen(device));
        return SidebarFrameClassifier.selectedSection(frame);
    }

    private void interruptibleWait(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    enum NextOpenAction {
        OPEN_PANEL,
        SELECT_SECTION,
        RESET_TO_TOP,
        DONE
    }
}
