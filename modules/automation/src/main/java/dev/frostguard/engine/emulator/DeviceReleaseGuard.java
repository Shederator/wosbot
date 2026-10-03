package dev.frostguard.engine.emulator;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lets an owner of protected device work, such as an active Bear event, veto automatic release
 * of its emulator: closing, relaunching, backgrounding, or handing its slot to another profile.
 * Ownership is evaluated live on every query, so it never outlives the owner's own state.
 * User-initiated stop, close, and reboot paths deliberately do not consult this guard.
 */
public final class DeviceReleaseGuard {

    private static final Logger LOG = LoggerFactory.getLogger(DeviceReleaseGuard.class);
    private static final Map<String, Set<BooleanSupplier>> OWNERS = new ConcurrentHashMap<>();

    private DeviceReleaseGuard() {
    }

    public static void register(String emulatorNumber, BooleanSupplier protectedNow) {
        if (emulatorNumber == null || protectedNow == null) return;
        OWNERS.computeIfAbsent(emulatorNumber, ignored -> new CopyOnWriteArraySet<>()).add(protectedNow);
    }

    public static void unregister(String emulatorNumber, BooleanSupplier protectedNow) {
        if (emulatorNumber == null || protectedNow == null) return;
        OWNERS.computeIfPresent(emulatorNumber, (ignored, owners) -> {
            owners.remove(protectedNow);
            return owners.isEmpty() ? null : owners;
        });
    }

    public static boolean isProtected(String emulatorNumber) {
        if (emulatorNumber == null) return false;
        Set<BooleanSupplier> owners = OWNERS.get(emulatorNumber);
        if (owners == null) return false;
        for (BooleanSupplier owner : owners) {
            try {
                if (owner.getAsBoolean()) return true;
            } catch (RuntimeException failure) {
                LOG.warn("Device {} ownership could not be evaluated; keeping it protected: {}",
                        emulatorNumber, failure.getMessage());
                return true;
            }
        }
        return false;
    }
}
