package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.emulator.EmulatorInstance;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.nav.SidebarRowLookup;
import dev.frostguard.engine.nav.SidebarSection;

class SidebarRecoveryAdversarialTest {

    private static void put(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static RawImageData frame(boolean daily) {
        byte[] pixels = new byte[720 * 1280 * 4];
        if (daily) {
            paint(pixels, CommonGameAreas.LEFT_MENU_CLOSE, 255);
            paint(pixels, CommonGameAreas.sidebarTabSample(SidebarSection.DAILY), 170);
        }
        return RawImageData.capture(pixels, 720, 1280, 4);
    }

    private static void paint(byte[] pixels, AreaData area, int value) {
        for (int y = area.topLeft().getY(); y <= area.bottomRight().getY(); y++) {
            for (int x = area.topLeft().getX(); x <= area.bottomRight().getX(); x++) {
                for (int channel = 0; channel < 3; channel++) {
                    pixels[(y * 720 + x) * 4 + channel] = (byte) value;
                }
            }
        }
    }

    private static class Backend extends EmulatorInstance {
        private RawImageData current = frame(false);
        private boolean interruptOnSwipe;
        private boolean preserveSectionOnSwipe;

        private Backend() {
            super("unused-review-backend");
        }

        @Override
        protected String getDeviceSerial(String index) {
            throw new AssertionError("No live device");
        }

        @Override
        public void launchEmulator(String index) {
            throw new AssertionError("No live device");
        }

        @Override
        public void closeEmulator(String index) {
            throw new AssertionError("No live device");
        }

        @Override
        public boolean isRunning(String index) {
            return false;
        }

        @Override
        public RawImageData captureScreenshot(String index) {
            return current;
        }

        @Override
        public void swipe(String index, PointData from, PointData to, int duration) {
            if (!preserveSectionOnSwipe) {
                current = frame(false);
            }
            if (interruptOnSwipe) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static class Rig {
        private final Backend backend;
        private final EmulatorController emu;
        private final AccountDescriptor account =
                new AccountDescriptor(999L, "Synthetic Review", "review", true, 0L, 0L);
        private final SidebarNavigator nav;
        private final AtomicInteger taps = new AtomicInteger();

        private Rig() throws Exception {
            dev.frostguard.vision.match.OpenCvPatternLocator.loadNativeLibrary();
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            backend = (Backend) ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(Backend.class);
            backend.current = frame(false);
            Constructor<EmulatorController> constructor = EmulatorController.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            emu = constructor.newInstance();
            put(emu, "backend", backend);
            nav = new SidebarNavigator(emu, "review", account);
            put(nav, "taps", new TapInteractionService(point -> taps.incrementAndGet(), null, millis -> {}));
            put(nav, "transitionWaiter", (SidebarNavigator.Waiter) millis -> true);
        }
    }

    @Test
    void sectionThatAppearsDuringFreshRootCheckMustNotReceiveSecondTrigger() throws Exception {
        Rig rig = new Rig();
        AtomicInteger reads = new AtomicInteger();
        put(rig.nav, "screenStates", (SidebarNavigator.ScreenStateReader) () -> {
            if (reads.incrementAndGet() == 2) {
                rig.backend.current = frame(true);
                return new SidebarNavigator.ScreenState(
                        java.util.Optional.of(SidebarSection.DAILY), false);
            }
            return new SidebarNavigator.ScreenState(java.util.Optional.empty(), true);
        });

        assertTrue(rig.nav.openSection(SidebarSection.DAILY));
        assertEquals(1, rig.taps.get(), "Already visible Daily panel must suppress retry");
    }

    @Test
    void lostSectionDuringScanMustNotBecomeDestinationAbsent() throws Exception {
        Rig rig = new Rig();
        rig.backend.current = frame(true);

        assertEquals(SidebarRowLookup.Status.SIDEBAR_UNAVAILABLE,
                rig.nav.findRowWithStatus(SidebarDestination.LIGHTHOUSE_INTEL).status());
    }

    @Test
    void interruptedScanMustNotBecomeDestinationAbsent() throws Exception {
        Rig rig = new Rig();
        rig.backend.current = frame(true);
        rig.backend.interruptOnSwipe = true;
        try {
            assertEquals(SidebarRowLookup.Status.SIDEBAR_UNAVAILABLE,
                    rig.nav.findRowWithStatus(SidebarDestination.LIGHTHOUSE_INTEL).status());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void completedValidScanCanStillReportDestinationAbsent() throws Exception {
        Rig rig = new Rig();
        rig.backend.current = frame(true);
        rig.backend.preserveSectionOnSwipe = true;

        assertEquals(SidebarRowLookup.Status.DESTINATION_ABSENT,
                rig.nav.findRowWithStatus(SidebarDestination.LIGHTHOUSE_INTEL).status());
    }

    @Test
    void initialUnknownNonRootStateNeverTaps() throws Exception {
        Rig rig = new Rig();
        put(rig.nav, "screenStates", (SidebarNavigator.ScreenStateReader) () ->
                new SidebarNavigator.ScreenState(java.util.Optional.empty(), false));

        assertFalse(rig.nav.openSection(SidebarSection.DAILY));
        assertEquals(0, rig.taps.get());
    }

    @Test
    void retryExhaustionStopsAfterTwoVerifiedTriggers() throws Exception {
        Rig rig = new Rig();
        put(rig.nav, "screenStates", (SidebarNavigator.ScreenStateReader) () ->
                new SidebarNavigator.ScreenState(java.util.Optional.empty(), true));

        assertFalse(rig.nav.openSection(SidebarSection.DAILY));
        assertEquals(SidebarNavigator.MAX_TRIGGER_TAPS, rig.taps.get());
    }

    @Test
    void retryIsRefusedWhenTheFreshFrameIsNoLongerARootScreen() throws Exception {
        Rig rig = new Rig();
        AtomicInteger reads = new AtomicInteger();
        put(rig.nav, "screenStates", (SidebarNavigator.ScreenStateReader) () ->
                new SidebarNavigator.ScreenState(
                        java.util.Optional.empty(), reads.getAndIncrement() == 0));

        assertFalse(rig.nav.openSection(SidebarSection.DAILY));
        assertEquals(1, rig.taps.get());
    }
}
