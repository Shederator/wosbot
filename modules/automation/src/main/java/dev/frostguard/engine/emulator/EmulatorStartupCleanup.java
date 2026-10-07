package dev.frostguard.engine.emulator;

import java.time.Duration;

/** Best-effort cleanup of an ADB-live emulator whose vendor reports stopped or cannot answer. */
final class EmulatorStartupCleanup {

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(15);
    private static final long POLL_MILLIS = 500;

    enum Status {
        ALREADY_RUNNING,
        NO_LIVE_ADB_DEVICE,
        CLEANED,
        UNCONFIRMED,
        INTERRUPTED
    }

    record Result(Status status, String evidence) {
    }

    interface Driver {
        EmulatorStopCycle.Observation observe(String instanceId) throws InterruptedException;

        EmulatorStopCycle.CommandOutcome requestAdbPowerOff(String instanceId)
                throws InterruptedException;

        String captureAnomaly(String instanceId);
    }

    private EmulatorStartupCleanup() {
    }

    static Result clean(String instanceId, Driver driver) {
        return clean(instanceId, driver, SHUTDOWN_TIMEOUT);
    }

    static Result clean(String instanceId, Driver driver, Duration shutdownTimeout) {
        try {
            if (Thread.currentThread().isInterrupted()) {
                return new Result(Status.INTERRUPTED, "interrupted before startup cleanup");
            }

            EmulatorStopCycle.Observation initial = driver.observe(instanceId);
            if (initial.vendor() == EmulatorStopCycle.Probe.RUNNING) {
                return new Result(Status.ALREADY_RUNNING, initial.summary());
            }
            if (initial.adb() != EmulatorStopCycle.Probe.RUNNING
                    || (initial.vendor() != EmulatorStopCycle.Probe.STOPPED
                            && initial.vendor() != EmulatorStopCycle.Probe.UNKNOWN)) {
                return new Result(Status.NO_LIVE_ADB_DEVICE, initial.summary());
            }

            EmulatorStopCycle.CommandOutcome command = driver.requestAdbPowerOff(instanceId);
            EmulatorStopCycle.Observation finalState = waitForStopped(instanceId, driver, shutdownTimeout);
            boolean confirmed = finalState.confirmedStopped();
            String evidence = "initial=" + initial.summary()
                    + "; adbPowerOff=" + command.detail()
                    + "; final=" + finalState.summary();
            if (confirmed) {
                return new Result(Status.CLEANED, evidence);
            }

            try {
                evidence += "; " + driver.captureAnomaly(instanceId);
            } catch (RuntimeException failure) {
                evidence += "; snapshot=failed:" + failure.getClass().getSimpleName();
            }
            return new Result(Status.UNCONFIRMED, evidence);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new Result(Status.INTERRUPTED, "interrupted during startup cleanup");
        }
    }

    private static EmulatorStopCycle.Observation waitForStopped(
            String instanceId, Driver driver, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        EmulatorStopCycle.Observation latest;
        do {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            latest = driver.observe(instanceId);
            if (latest.confirmedStopped() || System.nanoTime() >= deadline) {
                return latest;
            }
            long remainingMillis = Math.max(1L, (deadline - System.nanoTime()) / 1_000_000L);
            Thread.sleep(Math.min(POLL_MILLIS, remainingMillis));
        } while (true);
    }
}
