package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.frostguard.engine.service.StaminaService;

class StaminaHelperProfileReadTest {

    @Test
    void slowProfileOpenStillReachesOcrAndPersistsTheReading() {
        StaminaService service = StaminaService.getServices();
        RecordingProfileUi ui = new RecordingProfileUi(42);
        ui.profileOpenDelayMs = 1_050L;
        StaminaHelper helper = new StaminaHelper(service, 9301L, ui);

        helper.updateStaminaFromProfile();

        assertEquals(1, ui.dialogOpenCount);
        assertEquals(1, ui.ocrCount);
        assertEquals(5, ui.ocrAttempts);
        assertEquals(200L, ui.ocrRetryMs);
        assertEquals(1, ui.cleanupCount);
        assertEquals(42, service.findCurrentStamina(9301L).orElseThrow());
    }

    @Test
    void exhaustedOcrLeavesStaminaUnknownAndStillCleansUp() {
        StaminaService service = StaminaService.getServices();
        RecordingProfileUi ui = new RecordingProfileUi(null);
        StaminaHelper helper = new StaminaHelper(service, 9302L, ui);

        helper.updateStaminaFromProfile();

        assertTrue(service.findCurrentStamina(9302L).isEmpty());
        assertEquals(1, ui.cleanupCount);
    }

    @Test
    void readExceptionLeavesStaminaUnknownAndStillCleansUp() {
        StaminaService service = StaminaService.getServices();
        RecordingProfileUi ui = new RecordingProfileUi(null);
        ui.failRead = true;
        StaminaHelper helper = new StaminaHelper(service, 9303L, ui);

        helper.updateStaminaFromProfile();

        assertTrue(service.findCurrentStamina(9303L).isEmpty());
        assertEquals(1, ui.cleanupCount);
    }

    private static final class RecordingProfileUi implements StaminaHelper.ProfileStaminaUi {
        private final Integer reading;
        private long profileOpenDelayMs;
        private boolean failRead;
        private int dialogOpenCount;
        private int ocrCount;
        private int ocrAttempts;
        private long ocrRetryMs;
        private int cleanupCount;

        private RecordingProfileUi(Integer reading) {
            this.reading = reading;
        }

        @Override
        public void openProfile(int settleMs) throws InterruptedException {
            if (profileOpenDelayMs > 0) {
                Thread.sleep(profileOpenDelayMs);
            }
        }

        @Override
        public void openStaminaDialog(int settleMs) {
            dialogOpenCount++;
        }

        @Override
        public Integer readStamina(int attempts, long retryMs) {
            ocrCount++;
            ocrAttempts = attempts;
            ocrRetryMs = retryMs;
            if (failRead) {
                throw new IllegalStateException("simulated OCR failure");
            }
            return reading;
        }

        @Override
        public void cleanup() {
            cleanupCount++;
        }
    }
}
