package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.frostguard.data.access.DataStore;
import dev.frostguard.data.repository.TaskFailureStreakRepository;

class HeroMissionVisitBudgetTest {
    private java.nio.file.Path database;
    private DataStore store;
    private TaskFailureStreakRepository streaks;

    @BeforeEach
    void setUp() throws Exception {
        database = Files.createTempFile("hero-mission-visit-budget-", ".db");
        store = DataStore.openIsolated(Map.of(
                "jakarta.persistence.jdbc.url", "jdbc:sqlite:" + database,
                "hibernate.hbm2ddl.auto", "create-drop"));
        streaks = new TaskFailureStreakRepository(store);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
        Files.deleteIfExists(database);
    }

    @Test
    void capsFailuresUntilUtcResetThenReinitializesAndClearsOnSuccess() {
        Clock beforeReset = Clock.fixed(Instant.parse("2026-10-01T23:59:00Z"), ZoneId.of("Europe/Paris"));
        HeroMissionVisitBudget budget = new HeroMissionVisitBudget(streaks, 77L, beforeReset);

        assertEquals(1, budget.recordFailure(HeroMissionVisitBudget.FailureKind.PROGRESS).attempt());
        assertEquals(2, budget.recordFailure(HeroMissionVisitBudget.FailureKind.PROGRESS).attempt());
        HeroMissionVisitBudget.Decision exhausted = budget.recordFailure(
                HeroMissionVisitBudget.FailureKind.PROGRESS);
        assertEquals(HeroMissionVisitBudget.MAX_VISITS, exhausted.attempt());
        assertTrue(exhausted.exhausted());
        assertTrue(budget.exhausted(HeroMissionVisitBudget.FailureKind.PROGRESS));

        HeroMissionVisitBudget afterReset = new HeroMissionVisitBudget(
                streaks, 77L, Clock.fixed(Instant.parse("2026-10-02T00:01:00Z"), ZoneId.of("Europe/Paris")));
        assertFalse(afterReset.exhausted(HeroMissionVisitBudget.FailureKind.PROGRESS));
        assertEquals(1, afterReset.recordFailure(HeroMissionVisitBudget.FailureKind.PROGRESS).attempt());
        assertFalse(afterReset.exhausted(HeroMissionVisitBudget.FailureKind.PROGRESS));

        afterReset.succeeded(HeroMissionVisitBudget.FailureKind.PROGRESS);
        assertFalse(afterReset.exhausted(HeroMissionVisitBudget.FailureKind.PROGRESS));
        assertEquals(1, afterReset.recordFailure(HeroMissionVisitBudget.FailureKind.PROGRESS).attempt());
    }

    @Test
    void resetsFailureBeforeUtcMidnightDuringDstFallback() {
        ZoneId paris = ZoneId.of("Europe/Paris");
        HeroMissionVisitBudget beforeReset = new HeroMissionVisitBudget(streaks, 77L,
                Clock.fixed(Instant.parse("2026-10-24T23:59:00Z"), paris));
        beforeReset.recordFailure(HeroMissionVisitBudget.FailureKind.PROGRESS);
        beforeReset.recordFailure(HeroMissionVisitBudget.FailureKind.PROGRESS);
        assertTrue(beforeReset.recordFailure(HeroMissionVisitBudget.FailureKind.PROGRESS).exhausted());

        HeroMissionVisitBudget afterReset = new HeroMissionVisitBudget(streaks, 77L,
                Clock.fixed(Instant.parse("2026-10-25T00:01:00Z"), paris));

        assertFalse(afterReset.exhausted(HeroMissionVisitBudget.FailureKind.PROGRESS));
        assertEquals(1, afterReset.recordFailure(HeroMissionVisitBudget.FailureKind.PROGRESS).attempt());
    }

    @Test
    void keepsFailureCountAcrossRepeatedLocalHourDuringDstFallback() {
        ZoneId paris = ZoneId.of("Europe/Paris");
        Instant beforeFallback = Instant.parse("2026-10-25T00:59:00Z");
        Instant afterFallback = Instant.parse("2026-10-25T01:01:00Z");
        assertEquals(
                java.time.LocalDateTime.of(2026, 10, 25, 2, 59),
                beforeFallback.atZone(paris).toLocalDateTime());
        assertEquals(
                java.time.LocalDateTime.of(2026, 10, 25, 2, 1),
                afterFallback.atZone(paris).toLocalDateTime());

        HeroMissionVisitBudget before = new HeroMissionVisitBudget(
                streaks, 77L, Clock.fixed(beforeFallback, paris));
        assertEquals(1, before.recordFailure(HeroMissionVisitBudget.FailureKind.PROGRESS).attempt());

        HeroMissionVisitBudget after = new HeroMissionVisitBudget(
                streaks, 77L, Clock.fixed(afterFallback, paris));

        assertFalse(after.exhausted(HeroMissionVisitBudget.FailureKind.PROGRESS));
        assertEquals(2, after.recordFailure(HeroMissionVisitBudget.FailureKind.PROGRESS).attempt());
    }
}
