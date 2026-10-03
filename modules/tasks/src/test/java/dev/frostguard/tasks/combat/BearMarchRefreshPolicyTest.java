package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.frostguard.api.domain.MarchSlotState;
import dev.frostguard.api.domain.MarchSlotStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class BearMarchRefreshPolicyTest {

    @Test
    void schedulesTrackedSpecialReturnInsteadOfPollingTheSidebar() {
        Instant observedAt = Instant.EPOCH;

        assertEquals(Duration.ofMinutes(5).minusSeconds(2),
                BearMarchRefreshPolicy.nextDelay(
                        List.of(), true, observedAt.plus(Duration.ofMinutes(5)), observedAt));
    }

    @Test
    void refreshesUnknownOccupiedMarchesConservatively() {
        assertEquals(Duration.ofSeconds(5),
                BearMarchRefreshPolicy.nextDelay(
                        List.of(MarchSlotState.of(1, MarchSlotStatus.BUSY_UNKNOWN)),
                        false, null, Instant.EPOCH));
    }

    @Test
    void leavesAnAllIdleSnapshotStableWhileJoinActionsUpdateIt() {
        assertEquals(Duration.ofSeconds(30),
                BearMarchRefreshPolicy.nextDelay(
                        List.of(MarchSlotState.of(1, MarchSlotStatus.IDLE)),
                        false, null, Instant.EPOCH));
    }
}
