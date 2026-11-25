package dev.frostguard.api.configs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CaptureMethodEnumTest {
    @Test
    void missingOrUnknownPreferencePreservesAdb() {
        assertEquals(CaptureMethodEnum.ADB, CaptureMethodEnum.parse(null));
        assertEquals(CaptureMethodEnum.ADB, CaptureMethodEnum.parse(""));
        assertEquals(CaptureMethodEnum.ADB, CaptureMethodEnum.parse("unsupported"));
        assertEquals(CaptureMethodEnum.ADB, CaptureMethodEnum.parse(
                ConfigurationKeyEnum.SCREEN_CAPTURE_METHOD_STRING.getDefaultValue()));
    }

    @Test
    void restoresExplicitNativeOptInWithoutDependingOnDisplayLabel() {
        assertEquals(CaptureMethodEnum.MUMU_NEMU_IPC, CaptureMethodEnum.parse(" mumu_nemu_ipc "));
        assertEquals(CaptureMethodEnum.ADB, CaptureMethodEnum.parse("MuMu IPC (experimental)"));
    }
}
