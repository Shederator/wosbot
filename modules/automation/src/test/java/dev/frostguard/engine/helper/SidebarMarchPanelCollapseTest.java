package dev.frostguard.engine.helper;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.emulator.EmulatorInstance;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.SidebarSection;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidebarMarchPanelCollapseTest {
    private static final AreaData MARCH_HEADER = AreaData.of(5, 210, 245, 265);

    @BeforeAll
    static void loadVision() throws Exception {
        OpenCvPatternLocator.loadNativeLibrary();
    }

    @Test
    void expandedFourMarchPanelIsCollapsedBeforeTheSidebarTrigger() throws Exception {
        Rig rig = new Rig();
        assertTrue(rig.navigator.openSection(SidebarSection.WILDERNESS));
        assertEquals(List.of("collapse", "sidebar"), rig.actions);
        assertEquals(0, rig.unsafeTaps);
    }

    @Test
    void delayedCollapseWaitsForTwoClearRootObservationsWithoutAnotherTap() throws Exception {
        Rig rig = new Rig();
        rig.collapseDelayReads = 3;
        assertTrue(rig.navigator.openSection(SidebarSection.WILDERNESS));
        assertEquals(List.of("collapse", "sidebar"), rig.actions);
        assertEquals(4, rig.waits, "Three obstructed reads must be followed by two clear reads");
        assertEquals(0, rig.unsafeTaps);
    }

    @Test
    void persistentExpandedPanelNeverReceivesSidebarOrQueueManagementTaps() throws Exception {
        Rig rig = new Rig();
        rig.collapseDelayReads = Integer.MAX_VALUE;
        assertFalse(rig.navigator.openSection(SidebarSection.WILDERNESS));
        assertEquals(List.of("collapse"), rig.actions);
        assertEquals(0, rig.unsafeTaps);
        assertTrue(rig.waits < 10);
    }

    @Test
    void unknownPageAfterCollapseStopsBeforeTheSidebarTrigger() throws Exception {
        Rig rig = new Rig();
        rig.loseRootAfterCollapse = true;
        assertFalse(rig.navigator.openSection(SidebarSection.WILDERNESS));
        assertEquals(List.of("collapse"), rig.actions);
        assertEquals(0, rig.unsafeTaps);
    }

    @Test
    void cancelledCollapseDoesNotTapTheTriggerOrRepeatCollapse() throws Exception {
        Rig rig = new Rig();
        rig.cancelWait = true;
        rig.collapseDelayReads = 3;
        assertFalse(rig.navigator.openSection(SidebarSection.WILDERNESS));
        assertEquals(List.of("collapse"), rig.actions);
        assertEquals(0, rig.unsafeTaps);
    }

    @Test
    void alreadyCollapsedWorldAndAlreadyOpenSidebarNeedNoCollapseTap() throws Exception {
        Rig collapsed = new Rig();
        collapsed.backend.current = collapsed.clearRoot;
        assertTrue(collapsed.navigator.openSection(SidebarSection.WILDERNESS));
        assertEquals(List.of("sidebar"), collapsed.actions);

        Rig open = new Rig();
        open.backend.current = sectionFrame();
        assertTrue(open.navigator.openSection(SidebarSection.WILDERNESS));
        assertTrue(open.actions.isEmpty());
    }

    @Test
    void unknownNonRootPageAndAnAlreadyCancelledTaskNeverReceiveInput() throws Exception {
        Rig unknown = new Rig();
        unknown.backend.current = RawImageData.capture(new byte[720 * 1280 * 4], 720, 1280, 32);
        assertFalse(unknown.navigator.openSection(SidebarSection.WILDERNESS));
        assertTrue(unknown.actions.isEmpty());

        Rig cancelled = new Rig();
        try {
            Thread.currentThread().interrupt();
            assertFalse(cancelled.navigator.openSection(SidebarSection.WILDERNESS));
            assertTrue(cancelled.actions.isEmpty());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void reappearingMarchPanelDoesNotCauseAnotherCollapseOrObstructedTrigger() throws Exception {
        Rig rig = new Rig();
        rig.reopenMarchPanelOnTrigger = true;
        assertFalse(rig.navigator.openSection(SidebarSection.WILDERNESS));
        assertEquals(List.of("collapse", "sidebar"), rig.actions);
        assertEquals(0, rig.unsafeTaps);
    }

    @Test
    void sidebarAppearingDuringCollapseIsReusedWithoutATriggerTap() throws Exception {
        Rig rig = new Rig();
        rig.sidebarOpensOnCollapse = true;
        assertTrue(rig.navigator.openSection(SidebarSection.WILDERNESS));
        assertEquals(List.of("collapse"), rig.actions);
    }

    @Test
    void syntheticFifthGatheringRowStillUsesOnlyTheHeaderCollapseControl() throws Exception {
        Rig rig = new Rig();
        byte[] pixels = rig.expandedRoot.getFrameBytes().clone();
        for (int y = 0; y < 56; y++) {
            System.arraycopy(rig.expandedRoot.getFrameBytes(), ((444 + y) * 720) * 4,
                    pixels, ((504 + y) * 720) * 4, 245 * 4);
        }
        rig.backend.current = RawImageData.capture(pixels, 720, 1280, 32);
        assertTrue(rig.navigator.openSection(SidebarSection.WILDERNESS));
        assertEquals(List.of("collapse", "sidebar"), rig.actions);
        assertEquals(0, rig.unsafeTaps);
    }

    private static final class Backend extends EmulatorInstance {
        RawImageData current;
        RawImageData afterCollapse;
        int pendingReads;

        Backend() { super("synthetic"); }
        @Override protected void initBridge() { }
        @Override protected String getDeviceSerial(String idx) { throw new AssertionError("No real device"); }
        @Override public void launchEmulator(String idx) { throw new AssertionError("No launch"); }
        @Override public void closeEmulator(String idx) { throw new AssertionError("No shutdown"); }
        @Override public boolean isRunning(String idx) { throw new AssertionError("No lifecycle probe"); }
        @Override public RawImageData captureScreenshot(String idx) {
            if (afterCollapse != null && pendingReads-- <= 0) {
                current = afterCollapse;
                afterCollapse = null;
            }
            return current;
        }
    }

    private static final class Rig {
        final Backend backend = new Backend();
        final List<String> actions = new ArrayList<>();
        final SidebarNavigator navigator;
        final RawImageData expandedRoot;
        final RawImageData clearRoot;
        int unsafeTaps;
        int waits;
        int collapseDelayReads;
        boolean loseRootAfterCollapse;
        boolean cancelWait;
        boolean collapsed;
        boolean reopenMarchPanelOnTrigger;
        boolean sidebarOpensOnCollapse;

        Rig() throws Exception {
            BufferedImage expanded;
            try (var stream = getClass().getResourceAsStream("/navigation/march-panel/four-gathering-expanded.png")) {
                expanded = ImageIO.read(Objects.requireNonNull(stream));
            }
            expandedRoot = rgba(expanded);
            backend.current = expandedRoot;
            // Synthetic post-collapse state keeps the real World anchor while removing only the panel.
            BufferedImage clear = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
            clear.setRGB(0, 1140, 720, 140, expanded.getRGB(0, 1140, 720, 140, null, 0, 720), 0, 720);
            clearRoot = rgba(clear);

            var constructor = EmulatorController.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            EmulatorController emulator = constructor.newInstance();
            put(emulator, "backend", backend);
            Field profileLabels = EmulatorController.class.getDeclaredField("dev2profile");
            profileLabels.setAccessible(true);
            @SuppressWarnings("unchecked")
            var labels = (java.util.Map<String, String>) profileLabels.get(emulator);
            labels.put("synthetic", "Synthetic march panel");
            navigator = new SidebarNavigator(emulator, "synthetic",
                    new AccountDescriptor(999L, "Synthetic march panel", "0", false, 0L, 0L));
            put(navigator, "taps", new TapInteractionService(this::tap, null, millis -> {}));
            put(navigator, "transitionWaiter", (SidebarNavigator.Waiter) millis -> {
                waits++;
                return !cancelWait;
            });
        }

        private void tap(PointData point) {
            if (contains(MARCH_HEADER, point)) {
                actions.add("collapse");
                collapsed = true;
                backend.afterCollapse = loseRootAfterCollapse
                        ? RawImageData.capture(new byte[720 * 1280 * 4], 720, 1280, 32)
                        : sidebarOpensOnCollapse ? sectionFrame() : clearRoot;
                backend.pendingReads = collapseDelayReads;
            } else if (contains(CommonGameAreas.LEFT_MENU_TRIGGER, point)) {
                if (!collapsed && backend.current != clearRoot) unsafeTaps++;
                actions.add("sidebar");
                if (unsafeTaps == 0) backend.current = reopenMarchPanelOnTrigger ? expandedRoot : sectionFrame();
            } else {
                unsafeTaps++;
            }
        }
    }

    private static void put(Object target, String field, Object value) throws Exception {
        Field declared = target.getClass().getDeclaredField(field);
        declared.setAccessible(true);
        declared.set(target, value);
    }

    private static boolean contains(AreaData area, PointData point) {
        return point.getX() >= area.topLeft().getX() && point.getX() <= area.bottomRight().getX()
                && point.getY() >= area.topLeft().getY() && point.getY() <= area.bottomRight().getY();
    }

    private static RawImageData sectionFrame() {
        BufferedImage frame = new BufferedImage(720, 1280, BufferedImage.TYPE_INT_RGB);
        var graphics = frame.createGraphics();
        for (AreaData area : new AreaData[] { CommonGameAreas.LEFT_MENU_CLOSE,
                CommonGameAreas.sidebarTabSample(SidebarSection.WILDERNESS) }) {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(area.topLeft().getX(), area.topLeft().getY(),
                    area.bottomRight().getX() - area.topLeft().getX() + 1,
                    area.bottomRight().getY() - area.topLeft().getY() + 1);
        }
        graphics.dispose();
        return rgba(frame);
    }

    private static RawImageData rgba(BufferedImage frame) {
        byte[] bytes = new byte[frame.getWidth() * frame.getHeight() * 4];
        for (int y = 0, index = 0; y < frame.getHeight(); y++) {
            for (int x = 0; x < frame.getWidth(); x++, index += 4) {
                int pixel = frame.getRGB(x, y);
                bytes[index] = (byte) (pixel >> 16);
                bytes[index + 1] = (byte) (pixel >> 8);
                bytes[index + 2] = (byte) pixel;
                bytes[index + 3] = (byte) 255;
            }
        }
        return RawImageData.capture(bytes, frame.getWidth(), frame.getHeight(), 32);
    }
}
