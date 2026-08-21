package dev.frostguard.engine.helper;

import java.awt.image.BufferedImage;
import java.util.Optional;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.nav.SidebarFrameClassifier;
import dev.frostguard.engine.nav.SidebarRowAction;
import dev.frostguard.engine.nav.SidebarSection;
import dev.frostguard.engine.nav.SidebarViewportChangeDetector;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.logging.ProfileContextLogger;

/** State-verified navigation through the City, Wilderness, and Daily sidebar. */
public final class SidebarNavigator {

    private static final int SECTION_SETTLE_MS = 400;
    static final int SCROLL_SETTLE_MS = 2_000;
    static final int SCROLL_DISTANCE_PX = 120;
    private static final int SCROLL_DURATION_MS = 500;
    private static final int MAX_BOUNDARY_STEPS = 10;
    private static final int DESTINATION_SETTLE_MS = 2_000;
    private static final int ROW_ICON_THRESHOLD = 88;
    private static final int ROW_ACTION_THRESHOLD = 88;
    private static final int GO_LEFT_OFFSET = 315;
    private static final int GO_RIGHT_OFFSET = 394;
    private static final int GO_HALF_HEIGHT = 30;

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
        return openSectionInternal(target);
    }

    public boolean navigateTo(SidebarDestination destination) {
        ImageSearchResultData locatedRow = findRow(destination);
        if (locatedRow == null || !locatedRow.isFound()) {
            return false;
        }

        RawImageData actionFrame = emu.captureScreen(device);
        ImageSearchResultData rowIcon = locateRowIcon(destination, actionFrame);
        if (!rowIcon.isFound()) {
            log.info("Sidebar row moved before its action could be verified: " + destination);
            return false;
        }

        AreaData actionArea = rowActionAreaFor(rowIcon);
        ImageSearchResultData action = locateRowAction(destination, actionArea, actionFrame);
        if (action == null || !action.isFound()) {
            log.info("Sidebar row found without an expected action: " + destination
                    + " icon=" + rowIcon + " actionArea=" + actionArea);
            return false;
        }

        log.info("Sidebar destination found: " + destination + " icon=" + rowIcon
                + " action=" + action);
        taps.tapInside(action, 1, DESTINATION_SETTLE_MS);
        if (selectedSection().isEmpty()) {
            return true;
        }
        log.warn("Sidebar stayed open after tapping destination " + destination);
        return false;
    }

    /** Finds a row from its left icon in the current viewport and after every settled scroll. */
    public ImageSearchResultData findRow(SidebarDestination destination) {
        if (!openSection(destination.section())) {
            return ImageSearchResultData.miss();
        }

        RawImageData frame = emu.captureScreen(device);
        ImageSearchResultData current = locateRowIcon(destination, frame);
        if (current.isFound()) {
            return current;
        }

        ScrollScanResult bottomScan = scan(destination, ScrollDirection.TOWARD_BOTTOM, frame);
        if (bottomScan.rowIcon().isFound()) {
            return bottomScan.rowIcon();
        }
        log.info("Sidebar destination unavailable after bounded icon scan: " + destination
                + " bottomBoundary=" + bottomScan.boundaryReached());
        return ImageSearchResultData.miss();
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

    private boolean openSectionInternal(SidebarSection target) {
        Optional<SidebarSection> current = selectedSection();

        if (current.isEmpty()) {
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

        if (current.orElse(null) != target) {
            taps.tapInside(CommonGameAreas.sidebarTab(target), 1, SECTION_SETTLE_MS);
            current = selectedSection();
            if (current.orElse(null) != target) {
                log.warn("Sidebar section selection failed: requested=" + target
                        + " observed=" + current.map(Enum::name).orElse("closed/unknown"));
                return false;
            }
        }

        log.debug("Sidebar section ready without assuming a scroll origin: " + target);
        return true;
    }

    static AreaData rowActionAreaFor(ImageSearchResultData rowIcon) {
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

    static AreaData goButtonFor(ImageSearchResultData rowIcon) {
        return rowActionAreaFor(rowIcon);
    }

    private ScrollScanResult scan(SidebarDestination destination, ScrollDirection direction,
                                  RawImageData initialFrame) {
        RawImageData before = initialFrame;
        for (int step = 1; step <= MAX_BOUNDARY_STEPS; step++) {
            RawImageData after = scrollAndCapture(direction);
            if (after == null || selectedSection(after).orElse(null) != destination.section()) {
                log.warn("Sidebar state changed while scanning for " + destination
                        + " direction=" + direction + " step=" + step);
                return new ScrollScanResult(ImageSearchResultData.miss(), before, false);
            }

            ImageSearchResultData rowIcon = locateRowIcon(destination, after);
            boolean changed = SidebarViewportChangeDetector.materiallyChanged(
                    ImageConverter.toBufferedImage(before), ImageConverter.toBufferedImage(after));
            log.debug("Sidebar icon scan: destination=" + destination + " direction=" + direction
                    + " step=" + step + "/" + MAX_BOUNDARY_STEPS
                    + " changed=" + changed + " found=" + rowIcon.isFound());
            if (rowIcon.isFound()) {
                return new ScrollScanResult(rowIcon, after, false);
            }
            if (!changed) {
                return new ScrollScanResult(ImageSearchResultData.miss(), after, true);
            }
            before = after;
        }
        return new ScrollScanResult(ImageSearchResultData.miss(), before, false);
    }

    private RawImageData scrollAndCapture(ScrollDirection direction) {
        emu.swipeScreen(device, direction.from(), direction.to(), SCROLL_DURATION_MS);
        if (!interruptibleWait(SCROLL_SETTLE_MS)) {
            return null;
        }
        return emu.captureScreen(device);
    }

    private ImageSearchResultData locateRowIcon(SidebarDestination destination, RawImageData frame) {
        return emu.locatePattern(device, frame, destination.rowIcon(),
                CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN.topLeft(),
                CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN.bottomRight(), ROW_ICON_THRESHOLD);
    }

    private ImageSearchResultData locateRowAction(SidebarDestination destination, AreaData area,
                                                  RawImageData frame) {
        for (SidebarRowAction action : destination.actions()) {
            for (TemplatesEnum template : action.templates()) {
                ImageSearchResultData result = emu.locatePattern(device, frame, template,
                        area.topLeft(), area.bottomRight(), ROW_ACTION_THRESHOLD);
                if (result.isFound()) {
                    return result;
                }
            }
        }
        return ImageSearchResultData.miss();
    }

    private boolean isRootScreen() {
        return searcher.locatePattern(TemplatesEnum.GAME_HOME_FURNACE,
                SearchConfigConstants.DEFAULT_SINGLE).isFound()
                || searcher.locatePattern(TemplatesEnum.GAME_HOME_WORLD,
                        SearchConfigConstants.DEFAULT_SINGLE).isFound();
    }

    private Optional<SidebarSection> selectedSection() {
        return selectedSection(emu.captureScreen(device));
    }

    private Optional<SidebarSection> selectedSection(RawImageData frame) {
        return SidebarFrameClassifier.selectedSection(ImageConverter.toBufferedImage(frame));
    }

    private boolean interruptibleWait(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private record ScrollScanResult(ImageSearchResultData rowIcon, RawImageData frame,
                                    boolean boundaryReached) {}

    private enum ScrollDirection {
        TOWARD_BOTTOM(CommonGameAreas.SIDEBAR_SCROLL_TOWARD_BOTTOM_FROM,
                CommonGameAreas.SIDEBAR_SCROLL_TOWARD_BOTTOM_TO);

        private final PointData from;
        private final PointData to;

        ScrollDirection(PointData from, PointData to) {
            this.from = from;
            this.to = to;
        }

        PointData from() {
            return from;
        }

        PointData to() {
            return to;
        }
    }
}
