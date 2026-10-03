package dev.frostguard.engine.emulator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class DeviceReleaseGuardTest {

    @Test
    void anyLiveOwnerVetoesReleaseOfItsEmulatorOnly() {
        AtomicBoolean bearOwns = new AtomicBoolean(true);
        BooleanSupplier bear = bearOwns::get;
        BooleanSupplier sibling = () -> false;
        DeviceReleaseGuard.register("guard-test-1", bear);
        DeviceReleaseGuard.register("guard-test-1", sibling);
        try {
            assertTrue(DeviceReleaseGuard.isProtected("guard-test-1"));
            assertFalse(DeviceReleaseGuard.isProtected("guard-test-2"));

            bearOwns.set(false);
            assertFalse(DeviceReleaseGuard.isProtected("guard-test-1"),
                    "protection follows the owner's live state");
        } finally {
            DeviceReleaseGuard.unregister("guard-test-1", bear);
            DeviceReleaseGuard.unregister("guard-test-1", sibling);
        }
    }

    @Test
    void unregisteredOwnerNoLongerProtects() {
        BooleanSupplier owner = () -> true;
        DeviceReleaseGuard.register("guard-test-3", owner);
        DeviceReleaseGuard.unregister("guard-test-3", owner);

        assertFalse(DeviceReleaseGuard.isProtected("guard-test-3"));
    }

    @Test
    void ownerFailureIsTreatedAsProtected() {
        BooleanSupplier broken = () -> {
            throw new IllegalStateException("profile store unavailable");
        };
        DeviceReleaseGuard.register("guard-test-4", broken);
        try {
            assertTrue(DeviceReleaseGuard.isProtected("guard-test-4"),
                    "an unknown ownership state must not release a device");
        } finally {
            DeviceReleaseGuard.unregister("guard-test-4", broken);
        }
    }
}
