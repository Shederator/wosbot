package dev.frostguard.tasks.events;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FishingSteeringFeedbackTest {
    @Test
    void freshContactCalibrationDoesNotContaminateHeldContactGain() {
        var feedback = new FishingSteeringFeedback();
        int held = feedback.dragTo(350, 500, true);
        feedback.sent(350, 100, 1, 1_000_000_000L, false);
        assertFalse(feedback.observe(430, 2, 1_100_000_000L));
        assertTrue(feedback.observe(430, 3, 1_250_000_000L));
        assertEquals(held, feedback.dragTo(350, 500, true));
        assertTrue(feedback.dragTo(350, 500, false) > held);
    }

    @Test
    void repeatedAndBackdatedObservationsCannotConfirmSettling() {
        var feedback = new FishingSteeringFeedback();
        feedback.sent(350, 100, 1, 1_000_000_000L);
        assertFalse(feedback.observe(450, 2, 1_100_000_000L));
        assertFalse(feedback.observe(450, 2, 1_300_000_000L));
        assertFalse(feedback.observe(450, 3, 1_050_000_000L));
        assertTrue(feedback.waitingForResponse(1_350_000_000L));
    }
    @Test
    void recordedHeldMovementCanSettleAfterTheInitialResponseDeadline() {
        var feedback = new FishingSteeringFeedback();
        long completed = 1_000_000_000L;
        feedback.sent(366, -80, 149, completed);
        int[] positions = {359, 371, 326, 273, 246, 246, 239, 244};
        long[] receiptOffsets = {16_806_500L, 54_094_200L, 119_775_500L, 205_666_900L,
                247_263_800L, 319_513_000L, 458_637_800L, 660_300_000L};
        for (int i = 0; i < positions.length - 1; i++) {
            assertFalse(feedback.observe(positions[i], 150 + i, completed + receiptOffsets[i]));
        }
        assertTrue(feedback.observe(244, 158, completed + receiptOffsets[7]),
                "A real 120px response must not be called absent while its last wobble settles");
        assertFalse(feedback.takeTimedOut());
    }

    @Test
    void continuedMotionCannotExtendTheOverallDeadlineIndefinitely() {
        var feedback = new FishingSteeringFeedback();
        feedback.sent(350, 100, 1, 1_000_000_000L);
        assertFalse(feedback.observe(370, 2, 1_600_000_000L));
        assertTrue(feedback.waitingForResponse(1_700_000_000L));
        assertFalse(feedback.observe(390, 3, 1_900_000_000L));
        assertFalse(feedback.observe(420, 4, 2_200_000_000L));
        assertFalse(feedback.waitingForResponse(2_300_000_000L));
        assertTrue(feedback.takeTimedOut());
    }
    @Test
    void settledWallClippingUnlocksReplanningWithoutTeachingFalseGain() {
        var feedback = new FishingSteeringFeedback();
        int initial = feedback.dragTo(350, 500);
        feedback.sent(600, 100, 10, 1_000_000_000L);
        assertFalse(feedback.observe(692, 11, 1_100_000_000L));
        assertTrue(feedback.observe(692, 12, 1_250_000_000L),
                "A settled physical response must not waste the entire timeout at the wall");
        assertFalse(feedback.waitingForResponse(1_300_000_000L));
        assertEquals(initial, feedback.dragTo(350, 500), "Clipped movement is not calibration evidence");
    }

    @Test
    void smallButSettledResponseUnlocksReplanningWithoutTeachingFalseGain() {
        var feedback = new FishingSteeringFeedback();
        int initial = feedback.dragTo(350, 500);
        feedback.sent(350, 100, 10, 1_000_000_000L);
        assertFalse(feedback.observe(375, 11, 1_100_000_000L));
        assertTrue(feedback.observe(375, 12, 1_250_000_000L));
        assertEquals(initial, feedback.dragTo(350, 500));
    }

    @Test
    void reportsAnUnacknowledgedTimeoutExactlyOnceForPointerRecovery() {
        var feedback = new FishingSteeringFeedback();
        feedback.sent(350, 100, 10, 1_000_000_000L);
        assertTrue(feedback.waitingForResponse(1_100_000_000L));
        assertFalse(feedback.takeTimedOut());
        assertFalse(feedback.waitingForResponse(1_700_000_000L));
        assertTrue(feedback.takeTimedOut());
        assertFalse(feedback.takeTimedOut());
        feedback.sent(350, 100, 11, 2_000_000_000L);
        assertFalse(feedback.observe(500, 12, 2_100_000_000L));
        assertTrue(feedback.observe(500, 13, 2_250_000_000L));
        assertFalse(feedback.takeTimedOut());
    }

    @Test
    void ignoredAndStaleInputsDoNotTeachFalseMovementGain() {
        var feedback = new FishingSteeringFeedback();
        int initial = feedback.dragTo(350, 500);
        feedback.sent(350, 100, 10);
        assertFalse(feedback.observe(480, 10));
        assertFalse(feedback.observe(350, 11));
        assertEquals(initial, feedback.dragTo(350, 500));
        feedback.sent(350, 100, 12);
        assertFalse(feedback.observe(500, 13));
        assertTrue(feedback.observe(500, 14, System.nanoTime() + 150_000_000L));
        assertTrue(feedback.dragTo(350, 500) < initial);
    }

    @Test
    void boundsDragAndDoesNotCalibrateUsingWallClipping() {
        var feedback = new FishingSteeringFeedback();
        assertEquals(250, feedback.dragTo(0, 10000));
        feedback.sent(600, 100, 1);
        assertFalse(feedback.observe(690, 2));
    }

    @Test
    void waitsForDelayedMovementInsteadOfAcknowledgingTheFirstUnchangedFrame() {
        var feedback = new FishingSteeringFeedback();
        feedback.sent(350, 100, 10, 1_000_000_000L);
        assertTrue(feedback.waitingForResponse(1_050_000_000L));
        assertFalse(feedback.observe(350, 11, 1_080_000_000L));
        assertTrue(feedback.waitingForResponse(1_100_000_000L));
        assertFalse(feedback.observe(430, 14, 1_200_000_000L));
        assertFalse(feedback.observe(500, 15, 1_250_000_000L));
        assertTrue(feedback.waitingForResponse(1_300_000_000L));
        assertTrue(feedback.observe(500, 16, 1_400_000_000L));
        assertFalse(feedback.waitingForResponse(1_410_000_000L));
    }

    @Test
    void ignoresPreInputFrameTimeAndBoundsWaitingForAnUnresponsiveHook() {
        var feedback = new FishingSteeringFeedback();
        feedback.sent(350, 100, 10, 1_000_000_000L);
        assertFalse(feedback.observe(500, 11, 999_000_000L));
        assertTrue(feedback.waitingForResponse(1_100_000_000L));
        assertFalse(feedback.waitingForResponse(1_700_000_000L));
        assertFalse(feedback.observe(500, 12, 1_710_000_000L));
    }
}
