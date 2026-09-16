package dev.frostguard.tasks.events;

/** Direction changes need repeated depth evidence; indicator hue never authorizes steering. */
final class FishingPhaseTracker {
    enum Phase { UNKNOWN, DESCENDING, ASCENDING }
    private Phase phase = Phase.UNKNOWN;
    private int previous = -1, maximum = -1, increases, decreases, peak;
    private long sequence = -1;
    private long hookSequence = -1;
    private int descentHookY = -1;
    private int lastHookY = -1;
    private boolean hookTransition;

    Phase observe(FishingDepthMonitor.Depth depth, long frameSequence, Double relativeVerticalVelocity) {
        if (depth == null || frameSequence <= sequence) return phase;
        sequence = frameSequence;
        if (maximum >= 0 && maximum != depth.maximum()) return phase;
        maximum = depth.maximum();
        peak = Math.max(peak, depth.current());
        if (previous >= 0) {
            int delta = depth.current() - previous;
            boolean measuredMotion = relativeVerticalVelocity != null && Double.isFinite(relativeVerticalVelocity);
            if (delta > 0) {
                boolean descentEvidence = measuredMotion ? relativeVerticalVelocity < -0.05
                        : descentHookY >= 0 && !hookTransition;
                increases = descentEvidence ? increases + 1 : 0;
                decreases = 0;
            } else if (delta < 0) {
                // Caught sprites corrupt foreground motion during return. A verified
                // large transition into the lower hook band supplies independent evidence.
                boolean returnEvidence = measuredMotion && relativeVerticalVelocity > 0.05
                        || hookTransition && lastHookY >= 600;
                decreases = returnEvidence ? decreases + 1 : 0;
                increases = 0;
            }
            if (phase != Phase.ASCENDING && increases >= 2) phase = Phase.DESCENDING;
            if (decreases >= 2) phase = Phase.ASCENDING;
        }
        previous = depth.current();
        return phase;
    }

    Phase phase() { return phase; }
    void observeHook(int y, long frameSequence) {
        if (y < 0 || y >= 1280 || frameSequence <= hookSequence) return;
        hookSequence = frameSequence;
        lastHookY = y;
        if (descentHookY < 0) {
            // Countdown and resumed return frames do not establish the steady descent band.
            if (y <= 300) descentHookY = y;
            return;
        }
        // A live turnaround moves the attachment down hundreds of pixels before reliable return OCR.
        if (y - descentHookY > 60) hookTransition = true;
        else descentHookY = Math.min(descentHookY, y);
    }
    boolean descentAuthorized() { return phase == Phase.DESCENDING && descentHookY >= 0 && !hookTransition; }
    boolean canPrimeContact(boolean freshDepth) {
        return freshDepth && descentHookY >= 0 && !hookTransition && phase != Phase.ASCENDING;
    }
    boolean canSteer(boolean freshDepth, Double relativeVerticalVelocity) {
        return descentAuthorized() && (freshDepth || relativeVerticalVelocity != null
                && Double.isFinite(relativeVerticalVelocity) && relativeVerticalVelocity < -0.05);
    }
    int peak() { return peak; }
}
