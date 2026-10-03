package dev.frostguard.tasks.combat;

import dev.frostguard.engine.schedule.BearTrapSessionLease;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The event window a Bear session works against. An acquired lease is authoritative: the editable
 * schedule may change during the event, but it must not shorten, move, or cancel the window the
 * scheduler already granted.
 */
record BearEventWindow(Instant activation, Instant end, boolean leaseOwned) {

    static final Duration EVENT_DURATION = Duration.ofMinutes(30);

    BearEventWindow {
        Objects.requireNonNull(activation, "activation");
        Objects.requireNonNull(end, "end");
    }

    static Optional<BearEventWindow> resolve(
            Optional<BearTrapSessionLease.Lease> lease,
            Supplier<Optional<BearEventWindow>> configuredInsideWindow) {
        if (lease.isPresent()) {
            Instant end = lease.get().eventEnd();
            return Optional.of(new BearEventWindow(end.minus(EVENT_DURATION), end, true));
        }
        return configuredInsideWindow.get();
    }
}
