package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.engine.error.HomeNotFoundException;
import dev.frostguard.engine.error.ADBConnectionException;
import dev.frostguard.engine.service.ProfileService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BearEventRoutineTest {
    private static final LocalDateTime ACTIVATION = LocalDateTime.of(2026, 10, 5, 15, 0);
    private static final LocalDateTime END = ACTIVATION.plusMinutes(30);

    @BeforeAll static void workspace() throws Exception {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
    }

    @Test void startupFailureDoesNotFinalizeOrGrantAnotherThirtyMinutes() {
        Fake routine = new Fake();
        AtomicInteger starts = new AtomicInteger();
        routine.start(() -> {
            assertEquals(0, routine.restorations);
            assertNotEquals(nextEvent(), routine.getScheduled());
            if (starts.incrementAndGet() == 1) throw new HomeNotFoundException("startup");
        });
        assertEquals(2, starts.get());
        assertEquals(1, routine.recoveries);
        assertEquals(1, routine.restorations);
        assertEquals(END, routine.time);
        assertEquals(nextEvent(), routine.getScheduled());
        assertTrue(routine.joins > 0);
    }

    @Test void exhaustedRecoveryHasAnUnsuccessfulOutcomeAndActualNextEventSchedule() {
        Fake routine = new Fake();
        routine.time = END.minusSeconds(30);
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> routine.start(() -> {
            attempts.incrementAndGet();
            assertEquals(0, routine.restorations);
            throw new HomeNotFoundException("startup");
        }));
        assertEquals(3, attempts.get());
        assertEquals(0, routine.joins);
        assertEquals(1, routine.restorations);
        assertEquals(END, routine.time);
        assertEquals(nextEvent(), routine.getScheduled());
    }

    @Test void helperCannotContinueToInputAfterDeadline() {
        Fake routine = new Fake();
        routine.expireInsideJoin = true;
        routine.start(() -> { });
        assertEquals(0, routine.inputsAfterWait);
        assertEquals(END, routine.time);
        assertEquals(nextEvent(), routine.getScheduled());
    }

    @Test void recoveredNavigationDoesNotRepeatPetUse() {
        Fake routine = new Fake();
        routine.failFirstLocation = true;
        routine.start(() -> { });
        assertEquals(1, routine.pets);
        assertEquals(1, routine.recoveries);
        assertEquals(1, routine.restorations);
    }

    @Test void manualRunOutsideWindowDoesNotStartPreparation() {
        Fake routine = new Fake();
        routine.time = END.plusMinutes(1);
        routine.start(() -> fail("An expired event must not start preparation"));
        assertEquals(0, routine.joins);
        assertEquals(0, routine.restorations);
        assertEquals(nextEvent(), routine.getScheduled());
    }

    @Test void ambiguousOwnDeploymentIsNotRepeatedAfterRecoveryAndJoiningContinues() {
        Fake routine = new Fake();
        routine.time = ACTIVATION;
        routine.enableOwnRally();
        routine.start(() -> { });
        assertEquals(1, routine.ownAttempts);
        assertEquals(1, routine.recoveries);
        assertTrue(routine.joins > 0);
        assertEquals(nextEvent(), routine.getScheduled());
    }

    @Test void confirmedOwnRallySurvivesJoinFailureWithoutBlockingFurtherJoining() {
        Fake routine = new Fake();
        routine.time = ACTIVATION;
        routine.enableOwnRally();
        routine.confirmOwn = true;
        routine.failFirstJoin = true;
        routine.start(() -> { });
        assertEquals(1, routine.ownAttempts);
        assertEquals(1, routine.recoveries);
        assertTrue(routine.joins > 1);
        assertEquals(1, routine.restorations);
    }

    private static LocalDateTime nextEvent() {
        return LocalDateTime.ofInstant(ACTIVATION.plusDays(2).minusMinutes(10)
                .toInstant(ZoneOffset.UTC), ZoneId.systemDefault());
    }

    private static final class Fake extends BearTrapRoutine {
        LocalDateTime time = END.minusSeconds(10);
        int restorations, recoveries, joins, pets, locations, inputsAfterWait, ownAttempts;
        boolean expireInsideJoin, failFirstLocation, confirmOwn, failFirstJoin;
        Fake() { super(profile(), TpDailyTaskEnum.BEAR_TRAP); }
        static AccountDescriptor profile() {
            var p = new AccountDescriptor(null, "Bear event test " + java.util.UUID.randomUUID(),
                    "test", true, 1L, 30L);
            assertTrue(ProfileService.obtain().createAccount(p));
            p.setConfig(ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING, "05-10-2026 15:00");
            p.setConfig(ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT, 10);
            p.setConfig(ConfigurationKeyEnum.BEAR_TRAP_CALL_RALLY_BOOL, false);
            p.setConfig(ConfigurationKeyEnum.BEAR_TRAP_ACTIVE_PETS_BOOL, true);
            return p;
        }
        void start(Runnable initial) { executeWithPreparation(initial); }
        void enableOwnRally() { profile.setConfig(ConfigurationKeyEnum.BEAR_TRAP_CALL_RALLY_BOOL, true); }
        @Override LocalDateTime eventNow() { return time; }
        @Override void enablePetsFlow() { pets++; }
        @Override boolean reachBearTrap(int trap) { return !(failFirstLocation && ++locations == 1); }
        @Override void recoverEventLocation() { recoveries++; }
        @Override long beginOwnRally() {
            ownAttempts++;
            if (confirmOwn) return 30;
            // Simulate transport failure after Deploy was admitted, not a confirmed failure to launch.
            try {
                var attempted = BearTrapRoutine.class.getDeclaredField("ownDeploymentAttempted");
                attempted.setAccessible(true);
                attempted.setBoolean(this, true);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            throw new ADBConnectionException("deployment outcome unknown");
        }
        @Override void requeueDisabledTasksFlow() { restorations++; }
        @Override void handleJoinRallies2() {
            assertEquals(0, restorations);
            joins++;
            if (failFirstJoin && joins == 1) throw new HomeNotFoundException("joining");
            if (expireInsideJoin) {
                time = END;
                checkPreemption();
                inputsAfterWait++;
            }
        }
        @Override protected void sleepTask(long millis) {
            checkPreemption();
            time = time.plusNanos(millis * 1_000_000);
            checkPreemption();
        }
    }
}
