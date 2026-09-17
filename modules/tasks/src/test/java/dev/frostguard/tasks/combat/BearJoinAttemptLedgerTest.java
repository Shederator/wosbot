package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.PointData;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BearJoinAttemptLedgerTest {

    @Test
    void doesNotRetryRejectedRallyRowDuringItsPreparationWindow() {
        BearJoinAttemptLedger ledger = new BearJoinAttemptLedger(Duration.ofMinutes(5));
        PointData row = new PointData(610, 412);
        Instant rejectedAt = Instant.parse("2026-08-17T20:00:00Z");

        ledger.reject(row, rejectedAt);

        assertFalse(ledger.canAttempt(new PointData(612, 414), rejectedAt.plusSeconds(30)));
        assertFalse(ledger.canAttempt(row, rejectedAt.plusSeconds(299)));
    }

    @Test
    void permitsFreshAttemptAfterCooldownExpires() {
        BearJoinAttemptLedger ledger = new BearJoinAttemptLedger(Duration.ofMinutes(5));
        PointData row = new PointData(610, 412);
        Instant rejectedAt = Instant.parse("2026-08-17T20:00:00Z");
        ledger.reject(row, rejectedAt);

        assertTrue(ledger.canAttempt(row, rejectedAt.plusSeconds(300)));
    }

    @Test
    void doesNotBlockAnotherVisibleRow() {
        BearJoinAttemptLedger ledger = new BearJoinAttemptLedger(Duration.ofMinutes(5));
        Instant now = Instant.parse("2026-08-17T20:00:00Z");
        ledger.reject(new PointData(610, 412), now);

        assertTrue(ledger.canAttempt(new PointData(610, 520), now));
    }
}
