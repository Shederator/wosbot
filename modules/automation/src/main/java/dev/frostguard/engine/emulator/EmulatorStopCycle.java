package dev.frostguard.engine.emulator;

import java.time.Duration;

/** Coordinates a bounded vendor stop, independent observations, and one ADB fallback. */
public final class EmulatorStopCycle {

    static final Duration VENDOR_SETTLE = Duration.ofSeconds(10);
    static final Duration ADB_SETTLE = Duration.ofSeconds(15);
    private static final long POLL_MILLIS = 500;

    public enum Probe {
        RUNNING,
        STOPPED,
        UNKNOWN,
        UNSUPPORTED
    }

    public record Observation(Probe vendor, Probe adb, Probe host) {
        boolean confirmedStopped() {
            return vendor == Probe.STOPPED && adb == Probe.STOPPED
                    && (host == Probe.STOPPED || host == Probe.UNSUPPORTED);
        }

        String summary() {
            return "vendor=" + vendor + ", adb=" + adb + ", host=" + host;
        }
    }

    public record CommandOutcome(boolean accepted, String detail) {
        public static CommandOutcome failure(String detail) {
            return new CommandOutcome(false, detail);
        }
    }

    interface Driver {
        CommandOutcome requestVendorStop(String instanceId) throws InterruptedException;

        Observation observe(String instanceId) throws InterruptedException;

        CommandOutcome requestAdbPowerOff(String instanceId) throws InterruptedException;

        String captureAnomaly(String instanceId);
    }

    private EmulatorStopCycle() {
    }

    static EmulatorStopResult stop(String instanceId, Driver driver) {
        return stop(instanceId, driver, VENDOR_SETTLE, ADB_SETTLE);
    }

    static EmulatorStopResult stop(
            String instanceId, Driver driver, Duration vendorSettle, Duration adbSettle) {
        try {
            if (Thread.currentThread().isInterrupted()) {
                return new EmulatorStopResult(EmulatorStopResult.Status.INTERRUPTED,
                        EmulatorStopResult.Method.NONE, "interrupted before shutdown");
            }
            CommandOutcome vendorCommand = driver.requestVendorStop(instanceId);
            Observation afterVendor = waitForStopped(instanceId, driver, vendorSettle);
            if (afterVendor.confirmedStopped()) {
                return new EmulatorStopResult(EmulatorStopResult.Status.CONFIRMED_STOPPED,
                        EmulatorStopResult.Method.VENDOR,
                        "vendorCommand=" + vendorCommand.detail() + "; " + afterVendor.summary());
            }

            String diagnostic;
            try {
                diagnostic = driver.captureAnomaly(instanceId);
            } catch (RuntimeException failure) {
                diagnostic = "snapshot=failed:" + failure.getClass().getSimpleName();
            }

            boolean adbTargetVerified = afterVendor.adb() == Probe.RUNNING
                    && (afterVendor.host() == Probe.RUNNING
                            || afterVendor.host() == Probe.UNSUPPORTED);
            CommandOutcome adbCommand = adbTargetVerified
                    ? driver.requestAdbPowerOff(instanceId)
                    : CommandOutcome.failure("skipped: adb is " + afterVendor.adb()
                            + ", host is " + afterVendor.host());
            Observation afterAdb = waitForStopped(instanceId, driver, adbSettle);
            String evidence = "vendorCommand=" + vendorCommand.detail()
                    + "; afterVendor=" + afterVendor.summary()
                    + "; " + diagnostic
                    + "; adbCommand=" + adbCommand.detail()
                    + "; final=" + afterAdb.summary();
            return new EmulatorStopResult(
                    afterAdb.confirmedStopped()
                            ? EmulatorStopResult.Status.CONFIRMED_STOPPED
                            : EmulatorStopResult.Status.UNCONFIRMED,
                    !afterAdb.confirmedStopped() ? EmulatorStopResult.Method.NONE
                            : adbTargetVerified
                                    ? EmulatorStopResult.Method.ADB
                                    : EmulatorStopResult.Method.VENDOR,
                    evidence);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new EmulatorStopResult(EmulatorStopResult.Status.INTERRUPTED,
                    EmulatorStopResult.Method.NONE, "interrupted during shutdown");
        }
    }

    private static Observation waitForStopped(
            String instanceId, Driver driver, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Observation latest;
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
