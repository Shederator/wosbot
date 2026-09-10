package dev.frostguard.tasks.dailies;

/** Confirms deployment only after a bounded run of fresh frames without the Deploy button. */
final class DeploymentCompletionTracker {

    private final int requiredAbsences;
    private int consecutiveAbsences;
    private boolean retryClaimed;

    DeploymentCompletionTracker(int requiredAbsences) {
        if (requiredAbsences < 1) {
            throw new IllegalArgumentException("Required absences must be positive");
        }
        this.requiredAbsences = requiredAbsences;
    }

    boolean observe(boolean deployButtonVisible) {
        if (deployButtonVisible) {
            consecutiveAbsences = 0;
            return false;
        }
        return ++consecutiveAbsences >= requiredAbsences;
    }

    boolean claimRetry(boolean deployButtonVisible) {
        if (!deployButtonVisible || retryClaimed) {
            return false;
        }
        retryClaimed = true;
        reset();
        return true;
    }

    void reset() {
        consecutiveAbsences = 0;
    }
}
