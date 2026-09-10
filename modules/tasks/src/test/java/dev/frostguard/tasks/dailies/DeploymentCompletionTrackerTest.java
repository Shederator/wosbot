package dev.frostguard.tasks.dailies;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DeploymentCompletionTrackerTest {

    @Test
    void requiresTwoConsecutiveFreshFramesWithoutTheDeployButton() {
        DeploymentCompletionTracker tracker = new DeploymentCompletionTracker(2);

        assertFalse(tracker.observe(false), "one absent frame is not enough");
        assertFalse(tracker.observe(true), "a visible Deploy button resets the evidence");
        assertFalse(tracker.observe(false), "the confirmation count starts again after a reset");
        assertTrue(tracker.observe(false), "two consecutive absent frames confirm completion");
    }
}
