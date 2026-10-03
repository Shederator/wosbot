package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class BearSessionCheckpointTest {

    @Test
    void petIntentSurvivesSerializationObservationAndSchedulerRecovery() {
        Instant end = Instant.parse("2026-10-03T01:00:00Z");
        var armed = new BearSessionCheckpoint.Checkpoint(end, "PREPARING", "PET_CONFIRMATION",
                "CONFIRM_PET_USE", 14, end.minusSeconds(2000), 1, "armed", end.minusSeconds(2000),
                Instant.EPOCH, Instant.EPOCH, "NONE", 0, "NONE", 0, "USE_ARMED");
        var restored = BearSessionCheckpoint.parse(BearSessionCheckpoint.serialize(armed)).orElseThrow();
        assertEquals(armed, restored);
        var next = new BearSessionCheckpoint.Checkpoint(end, "RECOVERING", "PET_BATTLE_SELECTED",
                "NONE", 15, end.minusSeconds(1990), 2, "retry", end.minusSeconds(1990));
        assertEquals("USE_ARMED", BearSessionCheckpoint.mergeRecoveryBudget(restored, next).petUseState());
        assertEquals("USE_ARMED", BearSessionCheckpoint.mergeTactical(restored, next, false).petUseState());
    }

    @Test
    void readsVersionTwoWithNoPetIntent() {
        var old = BearSessionCheckpoint.parse("2|2026-10-03T01:00:00Z|ACTIVE|WORLD|NONE|4|"
                + "2026-10-03T00:30:00Z|0|resume|2026-10-03T00:30:01Z|1970-01-01T00:00:00Z|"
                + "1970-01-01T00:00:00Z|NONE|0|NONE|0").orElseThrow();
        assertEquals("NONE", old.petUseState());
    }

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

    @Test
    void observationPreservesExactDeadlineAndCommittedJoinRecoveryPoint() {
        Instant end = Instant.parse("2026-10-01T10:30:00Z");
        Instant sent = Instant.parse("2026-10-01T10:05:00Z");
        Instant deadline = Instant.parse("2026-10-01T10:10:24Z");
        BearSessionCheckpoint.Checkpoint durable = new BearSessionCheckpoint.Checkpoint(
                end, "ACTIVE", "FORMATION", "DEPLOY_JOIN", 20, sent, 1, "join",
                sent, sent, deadline, "PLUS_COMMITTED", 1234L, "[ARK]Leader", 612);
        BearSessionCheckpoint.Checkpoint observation = new BearSessionCheckpoint.Checkpoint(
                end, "ACTIVE", "WAR_LIST", "OBSERVE", 21, sent.plusSeconds(1), 0,
                "ui", sent.plusSeconds(1));

        BearSessionCheckpoint.Checkpoint merged =
                BearSessionCheckpoint.mergeRecoveryBudget(durable, observation);

        assertEquals(sent, merged.ownRallySentAt());
        assertEquals(deadline, merged.ownRallyReturnDeadline());
        assertEquals("PLUS_COMMITTED", merged.joinSubstate());
        assertEquals(1234L, merged.listRowFingerprint());
        assertEquals("[ARK]Leader", merged.listLeader());
        assertEquals(612, merged.listRowY());
    }

    @Test
    void readsVersionOneCheckpointWithEmptyTacticalState() {
        BearSessionCheckpoint.Checkpoint checkpoint = BearSessionCheckpoint.parse(
                "1|2026-10-01T10:30:00Z|ACTIVE|WORLD|NONE|4|"
                        + "2026-10-01T10:00:00Z|2|restart|2026-10-01T10:00:01Z")
                .orElseThrow();

        assertEquals(Instant.EPOCH, checkpoint.ownRallyReturnDeadline());
        assertEquals("NONE", checkpoint.joinSubstate());
    }
}
