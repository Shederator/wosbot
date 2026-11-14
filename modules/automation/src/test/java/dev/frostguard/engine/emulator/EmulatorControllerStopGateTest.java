package dev.frostguard.engine.emulator;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmulatorControllerStopGateTest {

    @Test
    void refusesToRelaunchAnInstanceWhosePreviousStopWasUnconfirmed() throws Exception {
        Constructor<EmulatorController> constructor = EmulatorController.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        EmulatorController controller = constructor.newInstance();
        FakeBackend backend = new FakeBackend();
        Field backendField = EmulatorController.class.getDeclaredField("backend");
        backendField.setAccessible(true);
        backendField.set(controller, backend);

        controller.recordStopResult("1", new EmulatorStopResult(
                EmulatorStopResult.Status.UNCONFIRMED, EmulatorStopResult.Method.NONE,
                "MuMu manager reported stopped while host process was live"));

        assertThrows(IllegalStateException.class, () -> controller.launchEmulator("1"));
        assertEquals(0, backend.launches);
    }

    private static final class FakeBackend extends EmulatorInstance {
        private int launches;

        private FakeBackend() {
            super("unused");
        }

        @Override
        protected void initBridge() {
            // This test never talks to an Android device.
        }

        @Override
        protected String getDeviceSerial(String idx) {
            return "127.0.0.1:16416";
        }

        @Override
        public void launchEmulator(String idx) {
            launches++;
        }

        @Override
        public boolean isRunning(String idx) {
            return false;
        }

        @Override
        protected EmulatorStopCycle.CommandOutcome requestVendorStop(String idx) {
            throw new AssertionError("Stop should not be invoked");
        }

        @Override
        protected EmulatorStopCycle.Probe probeVendorState(String idx) {
            throw new AssertionError("Stop should not be invoked");
        }
    }
}
