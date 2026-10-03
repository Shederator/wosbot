package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.BearSessionCheckpoint;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ScheduleServiceBearRecoveryTest {

    @Test
    void nonResumableCheckpointFailsClosedWhenFinalizerWriteFails() {
        AccountDescriptor profile = new AccountDescriptor(
                41L, "Bear startup", "0", true, 100L, 30L);
        BearSessionCheckpoint.Checkpoint checkpoint = checkpoint();

        assertFalse(ScheduleService.handoffCheckpointForStartup(
                profile, checkpoint, false, (ignoredProfile, ignoredEnd) -> false));
        assertTrue(ScheduleService.handoffCheckpointForStartup(
                profile, checkpoint, false, (ignoredProfile, ignoredEnd) -> true));
    }

    @Test
    void resumableCheckpointDoesNotRequireFinalizerHandoff() {
        assertTrue(ScheduleService.handoffCheckpointForStartup(
                new AccountDescriptor(42L, "Bear resume", "0", true, 100L, 30L),
                checkpoint(),
                true,
                (ignoredProfile, ignoredEnd) -> false));
    }

    private static BearSessionCheckpoint.Checkpoint checkpoint() {
        return new BearSessionCheckpoint.Checkpoint(
                Instant.parse("2026-10-01T10:30:00Z"),
                "RECOVERING", "WORLD", "NONE", 4,
                Instant.parse("2026-10-01T10:00:00Z"),
                2, "RESTART_APP", Instant.parse("2026-10-01T10:01:00Z"));
    }
}
