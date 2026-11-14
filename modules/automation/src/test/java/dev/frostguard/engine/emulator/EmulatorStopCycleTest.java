package dev.frostguard.engine.emulator;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmulatorStopCycleTest {

    private static final String INSTANCE_ID = "1";

    @Test
    void confirmsVendorStopFromIndependentStoppedEvidence() {
        RecordingDriver driver = new RecordingDriver(
                observation(EmulatorStopCycle.Probe.STOPPED,
                        EmulatorStopCycle.Probe.STOPPED,
                        EmulatorStopCycle.Probe.STOPPED));

        EmulatorStopResult result = stop(driver);

        assertEquals(EmulatorStopResult.Status.CONFIRMED_STOPPED, result.status());
        assertEquals(EmulatorStopResult.Method.VENDOR, result.method());
        assertEquals(List.of("vendor:1", "observe:1"), driver.events);
        assertTrue(result.evidence().contains("vendor=STOPPED"));
        assertTrue(result.evidence().contains("adb=STOPPED"));
        assertTrue(result.evidence().contains("host=STOPPED"));
    }

    @Test
    void capturesAnomalyBeforeOneAdbFallbackWhenVendorClaimContradictsLiveEvidence() {
        RecordingDriver driver = new RecordingDriver(
                observation(EmulatorStopCycle.Probe.STOPPED,
                        EmulatorStopCycle.Probe.RUNNING,
                        EmulatorStopCycle.Probe.RUNNING),
                observation(EmulatorStopCycle.Probe.STOPPED,
                        EmulatorStopCycle.Probe.STOPPED,
                        EmulatorStopCycle.Probe.STOPPED));

        EmulatorStopResult result = stop(driver);

        assertEquals(EmulatorStopResult.Status.CONFIRMED_STOPPED, result.status());
        assertEquals(EmulatorStopResult.Method.ADB, result.method());
        assertEquals(List.of("vendor:1", "observe:1", "capture:1", "adb:1", "observe:1"),
                driver.events);
        assertTrue(result.evidence().contains("snapshot=logs/snapshot/emulator-stop/frame.png"));
        assertTrue(result.evidence().contains("adbCommand=accepted"));
    }

    @Test
    void fallsBackToAdbWhenVendorCommandFails() {
        RecordingDriver driver = new RecordingDriver(
                EmulatorStopCycle.CommandOutcome.failure("vendor command timed out"),
                observation(EmulatorStopCycle.Probe.RUNNING,
                        EmulatorStopCycle.Probe.RUNNING,
                        EmulatorStopCycle.Probe.RUNNING),
                observation(EmulatorStopCycle.Probe.STOPPED,
                        EmulatorStopCycle.Probe.STOPPED,
                        EmulatorStopCycle.Probe.STOPPED));

        EmulatorStopResult result = stop(driver);

        assertEquals(EmulatorStopResult.Status.CONFIRMED_STOPPED, result.status());
        assertEquals(EmulatorStopResult.Method.ADB, result.method());
        assertEquals(List.of("vendor:1", "observe:1", "capture:1", "adb:1", "observe:1"),
                driver.events);
        assertTrue(result.evidence().contains("vendor command timed out"));
    }

    @Test
    void doesNotIssuePowerOffWhenAdbIsOfflineOrUnknown() {
        for (EmulatorStopCycle.Probe adbState : List.of(
                EmulatorStopCycle.Probe.STOPPED,
                EmulatorStopCycle.Probe.UNKNOWN,
                EmulatorStopCycle.Probe.UNSUPPORTED)) {
            RecordingDriver driver = new RecordingDriver(
                    EmulatorStopCycle.CommandOutcome.failure("vendor stop failed"),
                    observation(EmulatorStopCycle.Probe.RUNNING, adbState,
                            EmulatorStopCycle.Probe.RUNNING),
                    observation(EmulatorStopCycle.Probe.UNKNOWN, adbState,
                            EmulatorStopCycle.Probe.RUNNING));

            EmulatorStopResult result = stop(driver);

            assertEquals(EmulatorStopResult.Status.UNCONFIRMED, result.status(),
                    "adb state " + adbState);
            assertEquals(EmulatorStopResult.Method.NONE, result.method(),
                    "adb state " + adbState);
            assertFalse(driver.events.stream().anyMatch(event -> event.startsWith("adb:")),
                    "adb state " + adbState);
            assertEquals(2, driver.events.stream().filter(event -> event.equals("observe:1")).count());
            assertTrue(result.evidence().contains("skipped: adb is " + adbState));
        }
    }

    @Test
    void doesNotPowerOffAnAdbPortWithoutHostEvidenceForTheRequestedVm() {
        for (EmulatorStopCycle.Probe hostState : List.of(
                EmulatorStopCycle.Probe.STOPPED, EmulatorStopCycle.Probe.UNKNOWN)) {
            RecordingDriver driver = new RecordingDriver(
                    observation(EmulatorStopCycle.Probe.STOPPED,
                            EmulatorStopCycle.Probe.RUNNING, hostState),
                    observation(EmulatorStopCycle.Probe.STOPPED,
                            EmulatorStopCycle.Probe.RUNNING, hostState));

            EmulatorStopResult result = stop(driver);

            assertEquals(EmulatorStopResult.Status.UNCONFIRMED, result.status());
            assertFalse(driver.events.stream().anyMatch(event -> event.startsWith("adb:")));
            assertTrue(result.evidence().contains("host is " + hostState));
        }
    }

    @Test
    void leavesFinalUnknownOutcomeUnconfirmedAfterAdbRequest() {
        RecordingDriver driver = new RecordingDriver(
                EmulatorStopCycle.CommandOutcome.failure("vendor refused stop"),
                observation(EmulatorStopCycle.Probe.RUNNING,
                        EmulatorStopCycle.Probe.RUNNING,
                        EmulatorStopCycle.Probe.RUNNING),
                observation(EmulatorStopCycle.Probe.UNKNOWN,
                        EmulatorStopCycle.Probe.UNKNOWN,
                        EmulatorStopCycle.Probe.UNKNOWN));

        EmulatorStopResult result = stop(driver);

        assertEquals(EmulatorStopResult.Status.UNCONFIRMED, result.status());
        assertEquals(EmulatorStopResult.Method.NONE, result.method());
        assertEquals(1, driver.events.stream().filter(event -> event.equals("adb:1")).count());
        assertTrue(result.evidence().contains("final=vendor=UNKNOWN, adb=UNKNOWN, host=UNKNOWN"));
    }

    @Test
    void interruptionIsReportedAndThreadInterruptIsRestored() {
        RecordingDriver driver = new RecordingDriver();
        driver.interruptOnObserve = true;

        EmulatorStopResult result;
        try {
            result = stop(driver);
            assertEquals(EmulatorStopResult.Status.INTERRUPTED, result.status());
            assertEquals(EmulatorStopResult.Method.NONE, result.method());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(List.of("vendor:1", "observe:1"), driver.events);
        } finally {
            Thread.interrupted();
        }
    }

    private static EmulatorStopResult stop(RecordingDriver driver) {
        return EmulatorStopCycle.stop(INSTANCE_ID, driver, Duration.ZERO, Duration.ZERO);
    }

    private static EmulatorStopCycle.Observation observation(
            EmulatorStopCycle.Probe vendor,
            EmulatorStopCycle.Probe adb,
            EmulatorStopCycle.Probe host) {
        return new EmulatorStopCycle.Observation(vendor, adb, host);
    }

    private static final class RecordingDriver implements EmulatorStopCycle.Driver {
        private final List<String> events = new ArrayList<>();
        private final Queue<EmulatorStopCycle.Observation> observations = new ArrayDeque<>();
        private final Queue<EmulatorStopCycle.CommandOutcome> vendorOutcomes = new ArrayDeque<>();
        private boolean interruptOnObserve;

        private RecordingDriver(EmulatorStopCycle.Observation... observations) {
            vendorOutcomes.add(new EmulatorStopCycle.CommandOutcome(true, "accepted"));
            this.observations.addAll(List.of(observations));
        }

        private RecordingDriver(
                EmulatorStopCycle.CommandOutcome vendorOutcome,
                EmulatorStopCycle.Observation... observations) {
            this.vendorOutcomes.add(vendorOutcome);
            this.observations.addAll(List.of(observations));
        }

        @Override
        public EmulatorStopCycle.CommandOutcome requestVendorStop(String instanceId) {
            events.add("vendor:" + instanceId);
            return vendorOutcomes.remove();
        }

        @Override
        public EmulatorStopCycle.Observation observe(String instanceId) throws InterruptedException {
            events.add("observe:" + instanceId);
            if (interruptOnObserve) {
                throw new InterruptedException("test interruption");
            }
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
            return "snapshot=logs/snapshot/emulator-stop/frame.png";
        }
    }

}
