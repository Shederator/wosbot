package dev.frostguard.tasks.combat;

import dev.frostguard.engine.error.HomeNotFoundException;
import dev.frostguard.engine.error.StopExecutionException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BearEventSessionTest {
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 5, 15, 0);

    @Test
    void preparationFailureRetriesWithinOriginalWindow() {
        Fake driver = new Fake();
        driver.prepareFailures = 1;
        assertEquals(BearEventSession.Outcome.ENDED,
                BearEventSession.run(START, START.plusSeconds(12), driver));
        assertEquals(2, driver.preparations);
        assertEquals(1, driver.recoveries);
        assertEquals(7, driver.cycles);
        assertEquals(START.plusSeconds(12), driver.time);
    }

    @Test
    void activeFailureDoesNotRepeatPreparation() {
        Fake driver = new Fake();
        driver.activeFailures = 1;
        BearEventSession.run(START, START.plusSeconds(12), driver);
        assertEquals(1, driver.preparations);
        assertEquals(1, driver.recoveries);
        assertEquals(START.plusSeconds(12), driver.time);
    }

    @Test
    void exhaustedRecoveryHoldsWindowWithoutMoreInput() {
        Fake driver = new Fake();
        driver.prepareFailures = 100;
        assertEquals(BearEventSession.Outcome.RECOVERY_FAILED,
                BearEventSession.run(START, START.plusSeconds(30), driver));
        assertEquals(3, driver.preparations);
        assertEquals(2, driver.recoveries);
        assertEquals(0, driver.cycles);
        assertEquals(START.plusSeconds(30), driver.time);
    }

    @Test
    void expiryDuringRecoveryAdmitsNoActiveCycle() {
        Fake driver = new Fake();
        driver.prepareFailures = 1;
        driver.recoverySeconds = 20;
        assertEquals(BearEventSession.Outcome.RECOVERY_FAILED,
                BearEventSession.run(START, START.plusSeconds(10), driver));
        assertEquals(1, driver.preparations);
        assertEquals(0, driver.cycles);
    }

    @Test
    void cancellationIsNotRetriedOrSwallowed() {
        Fake driver = new Fake();
        driver.cancelAt = START.plusSeconds(5);
        driver.prepareFailures = 100;
        assertThrows(StopExecutionException.class,
                () -> BearEventSession.run(START, START.plusMinutes(30), driver));
        assertEquals(1, driver.preparations);
        assertEquals(0, driver.recoveries);
    }

    @Test
    void noParticipationBeforeActivationOrAfterEnd() {
        Fake driver = new Fake();
        BearEventSession.run(START.plusSeconds(10), START.plusSeconds(12), driver);
        assertEquals(2, driver.cycles);
        assertEquals(START.plusSeconds(12), driver.time);
    }

    private static final class Fake implements BearEventSession.Driver {
        LocalDateTime time = START;
        LocalDateTime cancelAt = START.plusDays(1);
        int prepareFailures, activeFailures, recoverySeconds, preparations, recoveries, cycles;
        public LocalDateTime now() { return time; }
        public void checkCancellation() {
            if (!time.isBefore(cancelAt)) throw StopExecutionException.userCancelled();
        }
        public void prepare() {
            preparations++;
            if (prepareFailures-- > 0) throw new HomeNotFoundException("preparation");
        }
        public void recover() { recoveries++; time = time.plusSeconds(recoverySeconds); }
        public void participate(long remaining) {
            assertTrue(remaining > 0);
            if (activeFailures-- > 0) throw new HomeNotFoundException("active");
            cycles++;
        }
        public void waitMillis(long millis) { time = time.plusNanos(millis * 1_000_000); }
        public void failed(int attempt, RuntimeException failure) { }
    }
}
