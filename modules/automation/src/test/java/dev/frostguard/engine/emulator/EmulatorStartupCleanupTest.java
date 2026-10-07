package dev.frostguard.engine.emulator;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmulatorStartupCleanupTest {

    private static final String INSTANCE_ID = "1";

    @Test
    void leavesInstanceAloneWhenVendorReportsItRunning() {
        RecordingDriver driver = new RecordingDriver(observation(
                EmulatorStopCycle.Probe.RUNNING,
                EmulatorStopCycle.Probe.RUNNING,
                EmulatorStopCycle.Probe.RUNNING));

        EmulatorStartupCleanup.Result result = clean(driver);

        assertEquals(EmulatorStartupCleanup.Status.ALREADY_RUNNING, result.status());
        assertEquals(List.of("observe:1"), driver.events);
    }

    @Test
    void skipsPowerOffWhenAdbDoesNotConfirmADevice() {
        for (EmulatorStopCycle.Probe adbState : List.of(
                EmulatorStopCycle.Probe.STOPPED,
                EmulatorStopCycle.Probe.UNKNOWN,
                EmulatorStopCycle.Probe.UNSUPPORTED)) {
            RecordingDriver driver = new RecordingDriver(observation(
                    EmulatorStopCycle.Probe.STOPPED, adbState, EmulatorStopCycle.Probe.STOPPED));

            EmulatorStartupCleanup.Result result = clean(driver);

            assertEquals(EmulatorStartupCleanup.Status.NO_LIVE_ADB_DEVICE, result.status(),
                    "adb state " + adbState);
            assertEquals(List.of("observe:1"), driver.events, "adb state " + adbState);
        }
    }

    @Test
    void powersOffAndVerifiesAnAdbLiveInstanceWhenVendorReportsStopped() {
        RecordingDriver driver = new RecordingDriver(
                observation(EmulatorStopCycle.Probe.STOPPED,
                        EmulatorStopCycle.Probe.RUNNING, EmulatorStopCycle.Probe.RUNNING),
                observation(EmulatorStopCycle.Probe.STOPPED,
                        EmulatorStopCycle.Probe.STOPPED, EmulatorStopCycle.Probe.STOPPED));

        EmulatorStartupCleanup.Result result = clean(driver);

        assertEquals(EmulatorStartupCleanup.Status.CLEANED, result.status());
        assertEquals(List.of("observe:1", "adb:1", "observe:1"), driver.events);
        assertTrue(result.evidence().contains("initial=vendor=STOPPED, adb=RUNNING"));
        assertTrue(result.evidence().contains("adbPowerOff=accepted"));
    }

    @Test
    void powersOffAdbLiveInstanceWhenVendorCannotAnswer() {
        RecordingDriver driver = new RecordingDriver(
                observation(EmulatorStopCycle.Probe.UNKNOWN,
                        EmulatorStopCycle.Probe.RUNNING, EmulatorStopCycle.Probe.UNSUPPORTED),
                observation(EmulatorStopCycle.Probe.STOPPED,
                        EmulatorStopCycle.Probe.STOPPED, EmulatorStopCycle.Probe.UNSUPPORTED));

        EmulatorStartupCleanup.Result result = clean(driver);

        assertEquals(EmulatorStartupCleanup.Status.CLEANED, result.status());
        assertEquals(1, driver.events.stream().filter(event -> event.equals("adb:1")).count());
    }

    @Test
    void capturesDiagnosticsAndReportsUnconfirmedShutdownWhenDeviceRemainsLive() {
        RecordingDriver driver = new RecordingDriver(
                observation(EmulatorStopCycle.Probe.STOPPED,
                        EmulatorStopCycle.Probe.RUNNING, EmulatorStopCycle.Probe.RUNNING),
                observation(EmulatorStopCycle.Probe.UNKNOWN,
                        EmulatorStopCycle.Probe.RUNNING, EmulatorStopCycle.Probe.RUNNING));

        EmulatorStartupCleanup.Result result = EmulatorStartupCleanup.clean(
                INSTANCE_ID, driver, Duration.ZERO);

        assertEquals(EmulatorStartupCleanup.Status.UNCONFIRMED, result.status());
        assertEquals(List.of("observe:1", "adb:1", "observe:1", "capture:1"), driver.events);
        assertTrue(result.evidence().contains("snapshot=logs/snapshot/emulator-startup/frame.png"));
    }

    @Test
    void doesNotMistakeOfflineAdbForConfirmedPowerOff() {
        RecordingDriver driver = new RecordingDriver(
                observation(EmulatorStopCycle.Probe.STOPPED,
                        EmulatorStopCycle.Probe.RUNNING, EmulatorStopCycle.Probe.UNSUPPORTED),
                observation(EmulatorStopCycle.Probe.STOPPED,
                        EmulatorStopCycle.Probe.UNKNOWN, EmulatorStopCycle.Probe.UNSUPPORTED));

        EmulatorStartupCleanup.Result result = EmulatorStartupCleanup.clean(
                INSTANCE_ID, driver, Duration.ZERO);

        assertEquals(EmulatorStartupCleanup.Status.UNCONFIRMED, result.status());
        assertTrue(result.evidence().contains("adb=UNKNOWN"));
    }

    private static EmulatorStartupCleanup.Result clean(RecordingDriver driver) {
        return EmulatorStartupCleanup.clean(INSTANCE_ID, driver, Duration.ZERO);
    }

    private static EmulatorStopCycle.Observation observation(
            EmulatorStopCycle.Probe vendor,
            EmulatorStopCycle.Probe adb,
            EmulatorStopCycle.Probe host) {
        return new EmulatorStopCycle.Observation(vendor, adb, host);
    }

    private static final class RecordingDriver implements EmulatorStartupCleanup.Driver {
        private final List<String> events = new ArrayList<>();
        private final Queue<EmulatorStopCycle.Observation> observations = new ArrayDeque<>();

        private RecordingDriver(EmulatorStopCycle.Observation... observations) {
            this.observations.addAll(List.of(observations));
        }

        @Override
        public EmulatorStopCycle.Observation observe(String instanceId) {
            events.add("observe:" + instanceId);
            return observations.remove();
        }

        @Override
        public EmulatorStopCycle.CommandOutcome requestAdbPowerOff(String instanceId) {
            events.add("adb:" + instanceId);
            return new EmulatorStopCycle.CommandOutcome(true, "accepted");
        }

        @Override
        public String captureAnomaly(String instanceId) {
            events.add("capture:" + instanceId);
            return "snapshot=logs/snapshot/emulator-startup/frame.png";
        }
    }
}
