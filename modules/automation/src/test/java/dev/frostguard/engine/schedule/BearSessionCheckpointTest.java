package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class BearSessionCheckpointTest {

    @Test
    void uiObservationCannotResetSchedulerRecoveryBudget() {
        Instant eventEnd = Instant.parse("2026-10-01T10:30:00Z");
        BearSessionCheckpoint.Checkpoint recovery = new BearSessionCheckpoint.Checkpoint(
                eventEnd, "RECOVERING", "DEVICE_OFFLINE", "capture", 0, Instant.EPOCH,
                2, "REBIND_DEVICE", Instant.parse("2026-10-01T10:01:00Z"));
        BearSessionCheckpoint.Checkpoint observation = new BearSessionCheckpoint.Checkpoint(
                eventEnd, "ACTIVE", "WORLD", "OPEN_WAR_LIST", 17,
                Instant.parse("2026-10-01T10:02:00Z"), 0, "ui-transition",
                Instant.parse("2026-10-01T10:02:01Z"));

        BearSessionCheckpoint.Checkpoint merged =
                BearSessionCheckpoint.mergeRecoveryBudget(recovery, observation);

        assertEquals(2, merged.recoveryAttempts());
        assertEquals("REBIND_DEVICE", merged.reason());
        assertEquals(17, merged.frameSequence());
        assertEquals("OPEN_WAR_LIST", merged.action());
    }

    @Test
    void roundTripsExplainableRecoveryPositionWithoutSecrets() {
        Instant end = Instant.parse("2026-10-01T10:30:00Z");
        BearSessionCheckpoint.Checkpoint checkpoint = new BearSessionCheckpoint.Checkpoint(
                end,
                "ACTIVE",
                "WAR_LIST",
                "OPEN_JOIN_FORMATION",
                81,
                Instant.parse("2026-10-01T10:12:00Z"),
                2,
                "candidate departed",
                Instant.parse("2026-10-01T10:12:01Z"));

        String encoded = BearSessionCheckpoint.serialize(checkpoint);

        assertEquals(checkpoint, BearSessionCheckpoint.parse(encoded).orElseThrow());
    }
}
