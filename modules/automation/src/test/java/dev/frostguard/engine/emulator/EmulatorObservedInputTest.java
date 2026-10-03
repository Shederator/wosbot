package dev.frostguard.engine.emulator;

import com.android.ddmlib.IDevice;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.error.ADBConnectionException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmulatorObservedInputTest {
    private static final PointData POINT = new PointData(100, 100);

    @Test
    void ambiguousTapSwipeAndBackAreNeverRetriedOrRecoveredByTransport() {
        assertSingleAttempt(driver -> driver.touchArea("1", POINT, POINT));
        assertSingleAttempt(driver -> driver.swipe("1", POINT, POINT));
        assertSingleAttempt(driver -> driver.pressBackButton("1"));
    }

    private void assertSingleAttempt(Consumer<Driver> input) {
        Driver driver = new Driver();
        driver.failAfterDelivery = true;
        AtomicInteger authorizations = new AtomicInteger();
        assertThrows(RuntimeException.class, () -> driver.withSingleAttemptInput(
                "1", authorizations::incrementAndGet, () -> input.accept(driver)));
        assertEquals(1, driver.commands);
        assertEquals(1, authorizations.get());
        assertEquals(0, driver.legacyRetries);
    }

    @Test
    void authorizationIsCheckedAfterDiscoveryAndImmediatelyBeforeInput() {
        Driver driver = new Driver();
        assertThrows(IllegalStateException.class, () -> driver.withSingleAttemptInput("1", () -> {
            assertEquals(1, driver.discoveries);
            throw new IllegalStateException("frame expired during lookup");
        }, () -> driver.pressBackButton("1")));
        assertEquals(0, driver.commands);
        assertEquals(0, driver.legacyRetries);
    }

    @Test
    void refusesASecondPhysicalCommandFromOneObservationAndReleasesScope() {
        Driver driver = new Driver();
        assertThrows(ADBConnectionException.class, () -> driver.withSingleAttemptInput("1", () -> {}, () -> {
            driver.pressBackButton("1");
            driver.pressBackButton("1");
        }));
        assertEquals(1, driver.commands);
        driver.pressBackButton("1");
        assertEquals(1, driver.legacyRetries, "unrelated legacy callers retain their retry policy");
    }

    @Test
    void refusesOfflineOrWrongDeviceWithoutConnectingOrRetrying() {
        Driver driver = new Driver();
        driver.online = false;
        assertThrows(ADBConnectionException.class, () -> driver.withSingleAttemptInput(
                "1", () -> {}, () -> driver.pressBackButton("1")));
        driver.online = true;
        assertThrows(ADBConnectionException.class, () -> driver.withSingleAttemptInput(
                "1", () -> {}, () -> driver.pressBackButton("2")));
        assertEquals(0, driver.commands);
        assertEquals(0, driver.legacyRetries);
    }

    private static final class Driver extends EmulatorInstance {
        int commands;
        int discoveries;
        int legacyRetries;
        boolean online = true;
        boolean failAfterDelivery;

        Driver() { super(""); }
        @Override protected void initBridge() { }
        @Override protected String getDeviceSerial(String idx) { return "emulator-5556"; }
        @Override public void launchEmulator(String idx) { fail("unexpected emulator launch"); }
        @Override public void closeEmulator(String idx) { fail("unexpected emulator close"); }
        @Override public boolean isRunning(String idx) { return true; }
        @Override public void restartAdb() { fail("unexpected bridge restart"); }
        @Override protected IDevice findObservedInputDevice(String idx) {
            discoveries++;
            return device();
        }
        @Override protected <T> T withRetries(String idx, Function<IDevice, T> action, String tag) {
            legacyRetries++;
            return action.apply(device());
        }
        private IDevice device() {
            return (IDevice) Proxy.newProxyInstance(IDevice.class.getClassLoader(), new Class<?>[]{IDevice.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("isOnline")) return online;
                        if (method.getName().equals("executeShellCommand")) {
                            commands++;
                            if (failAfterDelivery) throw new java.io.IOException("response lost after delivery");
                            return null;
                        }
                        throw new AssertionError("Unexpected device call: " + method.getName());
                    });
        }
    }
}
