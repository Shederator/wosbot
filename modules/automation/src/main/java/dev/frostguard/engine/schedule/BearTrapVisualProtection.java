package dev.frostguard.engine.schedule;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Keeps rally protection alive between periodic Bear icon probes. */
public final class BearTrapVisualProtection {

    private static final long DETECTION_HOLD_SECONDS = 35L;
    private static final ConcurrentMap<Long, Instant> RELEASE_BY_PROFILE = new ConcurrentHashMap<>();

    private BearTrapVisualProtection() {
    }

    public static void markDetected(Long profileId, Clock clock) {
        if (profileId != null) {
            RELEASE_BY_PROFILE.put(profileId, Instant.now(clock).plusSeconds(DETECTION_HOLD_SECONDS));
        }
    }

    public static Optional<Instant> releaseAt(Long profileId, Clock clock) {
        if (profileId == null) {
            return Optional.empty();
        }
        Instant releaseAt = RELEASE_BY_PROFILE.get(profileId);
        if (releaseAt == null) {
            return Optional.empty();
        }
        if (!releaseAt.isAfter(Instant.now(clock))) {
            RELEASE_BY_PROFILE.remove(profileId, releaseAt);
            return Optional.empty();
        }
        return Optional.of(releaseAt);
    }

    static void clearForTests() {
        RELEASE_BY_PROFILE.clear();
    }
}
