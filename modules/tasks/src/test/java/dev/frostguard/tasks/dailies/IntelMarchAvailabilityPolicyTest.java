package dev.frostguard.tasks.dailies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.MarchSlotState;
import dev.frostguard.api.domain.MarchSlotStatus;

class IntelMarchAvailabilityPolicyTest {

    @Test
    void anyIdleSlotMakesIntelRunnable() {
        var decision = IntelMarchAvailabilityPolicy.assess(List.of(
                new MarchSlotState(1, MarchSlotStatus.RETURNING, Duration.ofMinutes(2)),
                MarchSlotState.of(2, MarchSlotStatus.IDLE)));

        assertTrue(decision.available());
        assertEquals(1, decision.idleCount());
        assertEquals(Duration.ZERO, decision.retryDelay());
    }

    @Test
    void earliestExactReturningCountdownControlsRetry() {
        var decision = IntelMarchAvailabilityPolicy.assess(List.of(
                new MarchSlotState(1, MarchSlotStatus.RETURNING, Duration.ofMinutes(3)),
                new MarchSlotState(2, MarchSlotStatus.RETURNING, Duration.ofMinutes(1))));

        assertFalse(decision.available());
        assertTrue(decision.exactRelease());
        assertEquals(Duration.ofMinutes(1), decision.retryDelay());
    }

    @Test
    void gatherAndUnknownTimersRemainLowerBounds() {
        var decision = IntelMarchAvailabilityPolicy.assess(List.of(
                new MarchSlotState(1, MarchSlotStatus.GATHERING, Duration.ofSeconds(20)),
                new MarchSlotState(2, MarchSlotStatus.BUSY_UNKNOWN, Duration.ofSeconds(10))));

        assertFalse(decision.available());
        assertFalse(decision.exactRelease());
        assertEquals(Duration.ofMinutes(5), decision.retryDelay());
    }

    @Test
    void unreadableQueueFailsClosed() {
        var decision = IntelMarchAvailabilityPolicy.assess(List.of());

        assertFalse(decision.available());
        assertEquals(Duration.ofMinutes(5), decision.retryDelay());
    }

    @Test
    void earliestKnownFutureReleaseWins() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 20, 12, 0);

        assertEquals(now.plusMinutes(2), IntelMarchAvailabilityPolicy.resolveNextRelease(
                now,
                now.plusMinutes(4),
                List.of(now.plusMinutes(2))));
    }

    @Test
    void expiredReleasesAreIgnored() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 20, 12, 0);

        assertEquals(now.plusMinutes(3), IntelMarchAvailabilityPolicy.resolveNextRelease(
                now,
                now.minusSeconds(1),
                List.of(now.plusMinutes(3))));
    }

    @Test
    void expiredEarlierBeastReturnDoesNotHideLaterOutstandingMarch() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 20, 12, 0);

        assertEquals(now.plusMinutes(1), IntelMarchAvailabilityPolicy.resolveNextRelease(
                now,
                now.plusMinutes(5),
                List.of(now.minusSeconds(10), now.plusMinutes(1), now.plusMinutes(3))));
    }

    @Test
    void missingReleaseInformationUsesBoundedFallback() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 20, 12, 0);

        assertEquals(now.plusMinutes(5), IntelMarchAvailabilityPolicy.resolveNextRelease(now, null, List.of()));
    }
}
