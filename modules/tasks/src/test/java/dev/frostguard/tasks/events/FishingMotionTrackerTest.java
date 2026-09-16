package dev.frostguard.tasks.events;

import java.util.Collections;
import java.util.List;
import dev.frostguard.api.domain.AreaData;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FishingMotionTrackerTest {
    @Test
    void catchCandidatesRequireRepeatedCurrentObservationsNotPredictedGhosts() {
        var tracker = new FishingMotionTracker();
        for (int sample = 0; sample < 3; sample++) {
            tracker.update(List.of(AreaData.of(300, 600 + sample * 20, 380, 640 + sample * 20)),
                    1000 + sample * 50, 0);
            assertEquals(sample == 2 ? 1 : 0, tracker.catchOpportunities(1000 + sample * 50, 0).size());
        }
        tracker.update(List.of(), 1200, 0);
        assertTrue(tracker.catchOpportunities(1200, 0).isEmpty());
    }
    @Test
    void motionEvidenceRequiresThreeRepeatedlyMatchedObjectsAndExpiresImmediatelyWhenUnobserved() {
        var tracker = new FishingMotionTracker();
        for (int sample = 0; sample < 3; sample++) {
            var objects = new java.util.ArrayList<AreaData>();
            for (int object = 0; object < 3; object++) {
                objects.add(AreaData.of(100 + object * 180, 300 + object * 150 + sample * 20,
                        140 + object * 180, 330 + object * 150 + sample * 20));
            }
            tracker.update(objects, 1000 + sample * 50, 0);
            if (sample < 2) assertNull(tracker.relativeVerticalVelocity());
        }
        assertTrue(tracker.relativeVerticalVelocity() > 0.1);
        tracker.update(List.of(), 1150, 0);
        assertNull(tracker.relativeVerticalVelocity(), "Predicted unmatched tracks are not fresh phase evidence");
    }

    @Test
    void refusesOverloadedForegroundRatherThanSilentlyDroppingHazards() {
        var tracker = new FishingMotionTracker();
        assertThrows(IllegalStateException.class, () -> tracker.update(
                Collections.nCopies(257, AreaData.of(100, 300, 140, 320)), 1000, 0));
    }

    @Test
    void expiresOldTracksAndKeepsFreshUnmatchedThreatsBriefly() {
        var tracker = new FishingMotionTracker();
        assertEquals(1, tracker.update(List.of(AreaData.of(100, 300, 140, 320)), 1000, 0).size());
        assertEquals(1, tracker.update(List.of(), 1200, 0).size());
        assertTrue(tracker.update(List.of(), 1500, 0).isEmpty());
    }
}
