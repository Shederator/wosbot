package dev.frostguard.tasks.combat;

import dev.frostguard.engine.error.ADBConnectionException;
import dev.frostguard.engine.error.HomeNotFoundException;
import java.time.Duration;
import java.time.LocalDateTime;

/** Owns one invocation's fixed event window; interaction helpers remain outside this policy. */
final class BearEventSession {
    static final int MAX_CONSECUTIVE_FAILURES = 3;
    static final long RETRY_MILLIS = 5_000;
    enum Outcome { ENDED, RECOVERY_FAILED }
    static final class WindowEnded extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    interface Driver {
        LocalDateTime now();
        void checkCancellation();
        void prepare();
        void recover();
        void participate(long secondsRemaining);
        void waitMillis(long millis);
        void failed(int attempt, RuntimeException failure);
    }

    private BearEventSession() { }

    static Outcome run(LocalDateTime activation, LocalDateTime end, Driver driver) {
        boolean prepared = false;
        boolean recovery = false;
        int failures = 0;
        try {
            while (driver.now().isBefore(end)) {
                driver.checkCancellation();
                if (failures >= MAX_CONSECUTIVE_FAILURES) {
                    // Keep the task's exclusive queue slot, but send no more input this window.
                    waitWithin(end, driver, RETRY_MILLIS);
                    continue;
                }
                try {
                    if (recovery) {
                        driver.recover();
                        recovery = false;
                    }
                    if (!driver.now().isBefore(end)) break;
                    if (!prepared) {
                        driver.prepare();
                        prepared = true;
                    }
                    driver.checkCancellation();
                    if (!driver.now().isBefore(end)) break;
                    if (driver.now().isBefore(activation)) {
                        waitWithin(activation, driver, RETRY_MILLIS);
                        continue;
                    }
                    driver.participate(Duration.between(driver.now(), end).getSeconds());
                    failures = 0;
                    waitWithin(end, driver, 1_000);
                } catch (HomeNotFoundException | ADBConnectionException failure) {
                    driver.checkCancellation();
                    failures++;
                    recovery = true;
                    driver.failed(failures, failure);
                    waitWithin(end, driver, RETRY_MILLIS);
                }
            }
        } catch (WindowEnded endedDuringInteraction) {
            // The routine checks the same deadline at each existing input/sleep boundary.
        }
        driver.checkCancellation();
        return failures > 0 ? Outcome.RECOVERY_FAILED : Outcome.ENDED;
    }

    private static void waitWithin(LocalDateTime deadline, Driver driver, long maximumMillis) {
        long remaining = Duration.between(driver.now(), deadline).toMillis();
        if (remaining > 0) driver.waitMillis(Math.min(remaining, maximumMillis));
    }
}
