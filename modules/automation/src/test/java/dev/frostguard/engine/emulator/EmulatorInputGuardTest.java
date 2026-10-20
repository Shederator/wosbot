package dev.frostguard.engine.emulator;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.engine.helper.NavigationHelper;
import dev.frostguard.engine.helper.TemplateSearchHelper;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmulatorInputGuardTest {
    private static final class Expired extends RuntimeException { }

    @Test
    void deadlineCrossedDuringRealNavigationBlocksItsSecondTap() throws Exception {
        var constructor = EmulatorController.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var controller = constructor.newInstance();
        var backend = new RecordingBackend();
        var backendField = EmulatorController.class.getDeclaredField("backend");
        backendField.setAccessible(true);
        backendField.set(controller, backend);
        var expired = new AtomicBoolean();
        var profile = new AccountDescriptor(1L);
        var navigation = new NavigationHelper(controller, "test", profile);
        var searcher = NavigationHelper.class.getDeclaredField("searcher");
        searcher.setAccessible(true);
        searcher.set(navigation, new TemplateSearchHelper(controller, "test", profile) {
            @Override public ImageSearchResultData locatePattern(TemplatesEnum template, SearchConfig config) {
                expired.set(true); // Deadline passes while recognizing the Alliance screen.
                return ImageSearchResultData.hit(100, 100, 100);
            }
        });
        controller.withInputGuard(() -> { if (expired.get()) throw new Expired(); }, () -> {
            assertThrows(Expired.class, () -> navigation.navigateToAllianceMenu(NavigationHelper.AllianceMenu.WAR));
            return null;
        });
        assertEquals(1, backend.taps, "The War tap must not dispatch after the deadline");
    }

    private static final class RecordingBackend extends EmulatorInstance {
        int taps;
        RecordingBackend() { super("unused"); }
        @Override protected void initBridge() { }
        @Override protected String getDeviceSerial(String index) { return "test"; }
        @Override public void launchEmulator(String index) { fail("No real device actions"); }
        @Override public void closeEmulator(String index) { fail("No real device actions"); }
        @Override public boolean isRunning(String index) { return true; }
        @Override boolean touchArea(String index, PointData a, PointData b) { taps++; return true; }
    }

    @Test
    void independentHelperAndBackCannotBypassExpiredScope() {
        var controller = EmulatorController.getInstance();
        var navigation = new NavigationHelper(controller, "test", new AccountDescriptor(1L));
        controller.withInputGuard(() -> { throw new Expired(); }, () -> {
            assertThrows(Expired.class, () -> navigation.navigateToAllianceMenu(
                    dev.frostguard.engine.helper.NavigationHelper.AllianceMenu.WAR));
            assertThrows(Expired.class, () -> controller.pressBack("test"));
            assertThrows(Expired.class, () -> controller.swipeScreen("test", new PointData(1, 1), new PointData(2, 2)));
            assertThrows(Expired.class, () -> controller.writeText("test", "test"));
            return null;
        });
    }

    @Test
    void nestedScopesRestoreOuterCheckAndDoNotLeakAfterFailure() {
        var controller = EmulatorController.getInstance();
        var checked = new AtomicBoolean();
        assertThrows(Expired.class, () -> controller.withInputGuard(() -> { checked.set(true); }, () -> {
            controller.withInputGuard(() -> { throw new Expired(); }, () -> {
                controller.pressBack("test");
                return null;
            });
            return null;
        }));
        assertTrue(checked.get());
        checked.set(false);
        controller.withInputGuard(() -> { throw new Expired(); }, () -> {
            assertThrows(Expired.class, () -> controller.pressBack("test"));
            return null;
        });
        assertFalse(checked.get(), "Previous task's guard must have been removed");
    }
}
