package dev.frostguard.engine.schedule;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.AccountDescriptor;

/**
 * Scheduler-owned lock for one configured Bear Trap session.
 *
 * <p>The event deadline is captured before the Bear task starts and never follows later profile
 * or task-schedule mutations. A task failure therefore cannot unlock unrelated work. The lease is
 * released only after the captured event deadline (plus the protection buffer) or an explicit
 * queue stop.</p>
 */
public final class BearTrapSessionLease {

    private static final ConcurrentMap<Long, Lease> LEASES_BY_PROFILE = new ConcurrentHashMap<>();

    private BearTrapSessionLease() {
    }

    public static Optional<Lease> acquireForBearExecution(AccountDescriptor profile) {
        return acquireForBearExecution(profile, Clock.systemUTC());
    }

    static Optional<Lease> acquireForBearExecution(AccountDescriptor profile, Clock clock) {
        if (profile == null || profile.getId() == null) {
            return Optional.empty();
        }

        Optional<BearTrapParticipationSchedule.ActiveSession> activeSession =
                BearTrapParticipationSchedule.resolveActiveSession(profile, clock);
        if (activeSession.isEmpty()) {
            Optional<Lease> current = active(profile.getId(), clock);
            if (current.isPresent()) {
                return current;
            }
            if (!Boolean.TRUE.equals(profile.getConfig(ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL, Boolean.class))) {
                // Revoked participation must not regain the event from its checkpoint; its due
                // cleanup finalization runs without a lease.
                return Optional.empty();
            }
            return BearSessionCheckpoint.load(profile)
                    .filter(checkpoint -> isAlive(checkpoint.eventEnd(), clock.instant()))
                    .map(checkpoint -> restoreFromCheckpoint(profile, checkpoint, clock));
        }

        BearTrapParticipationSchedule.ActiveSession session = activeSession.get();
        Lease candidate = new Lease(
                profile.getId(),
                session.trapNumber(),
                clock.instant(),
                session.eventEnd());
        Lease acquired = LEASES_BY_PROFILE.compute(profile.getId(), (profileId, current) -> {
            if (isAlive(current, clock.instant())) {
                return current;
            }
            return candidate;
        });
        return Optional.of(acquired);
    }

    private static Lease restoreFromCheckpoint(
            AccountDescriptor profile,
            BearSessionCheckpoint.Checkpoint checkpoint,
            Clock clock) {
        Integer configuredTrap = profile.getConfig(
                dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_NUMBER_INT,
                Integer.class);
        Lease candidate = new Lease(
                profile.getId(),
                configuredTrap == null ? 1 : configuredTrap,
                clock.instant(),
                checkpoint.eventEnd());
        return LEASES_BY_PROFILE.compute(profile.getId(), (profileId, current) ->
                isAlive(current, clock.instant()) ? current : candidate);
    }

    public static Optional<Lease> active(Long profileId) {
        return active(profileId, Clock.systemUTC());
    }

    static Optional<Lease> active(Long profileId, Clock clock) {
        if (profileId == null) {
            return Optional.empty();
        }
        Lease lease = LEASES_BY_PROFILE.get(profileId);
        if (!isAlive(lease, clock.instant())) {
            if (lease != null) {
                LEASES_BY_PROFILE.remove(profileId, lease);
            }
            return Optional.empty();
        }
        return Optional.of(lease);
    }

    /** Explicit operator/scheduler cancellation boundary. Routine cleanup must never call this. */
    public static void releaseForQueueStop(Long profileId) {
        if (profileId != null) {
            LEASES_BY_PROFILE.remove(profileId);
        }
    }

    static void clearForTests() {
        LEASES_BY_PROFILE.clear();
    }

    private static boolean isAlive(Lease lease, Instant now) {
        return lease != null && isAlive(lease.eventEnd(), now);
    }

    private static boolean isAlive(Instant eventEnd, Instant now) {
        return eventEnd != null && now.isBefore(eventEnd.plusSeconds(
                BearTrapProtectionPolicy.RELEASE_BUFFER_SECONDS));
    }

    public record Lease(long profileId, int trapNumber, Instant acquiredAt, Instant eventEnd) {
    }
}
