package dev.frostguard.engine.ranking.capture;

import dev.frostguard.engine.emulator.EmulatorType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllianceRankingCaptureSupportTest {

    @Test
    void supportsOnlyMumuOnWindowsForNow() {
        assertTrue(AllianceRankingCaptureSupport.evaluate("Windows 11", EmulatorType.MUMU).supported());
        assertFalse(AllianceRankingCaptureSupport.evaluate("Windows 11", EmulatorType.MEMU).supported());
        assertFalse(AllianceRankingCaptureSupport.evaluate("Windows 11", EmulatorType.LDPLAYER).supported());
        assertFalse(AllianceRankingCaptureSupport.evaluate("Linux", EmulatorType.MUMU).supported());
    }

    @Test
    void unsupportedMessageNamesTheAvailableBackend() {
        String message = AllianceRankingCaptureSupport.evaluate("Windows 11", EmulatorType.LDPLAYER).message();

        assertTrue(message.contains("only available for MuMu Player on Windows"));
    }
}
