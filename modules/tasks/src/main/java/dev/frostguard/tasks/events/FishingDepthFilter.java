package dev.frostguard.tasks.events;

/** Rejects implausible OCR jumps; repeated frames cannot authorize further bait. */
final class FishingDepthFilter {
    // Provisional safety envelope, deliberately above the roughly 5–8m/s observed in practice.
    // Deep real-cast speeds still require validation. Rejection preserves bait rather than guessing.
    private static final double MAX_METRES_PER_SECOND = 20;
    private FishingDepthMonitor.Depth previous;
    private FishingDepthMonitor.Depth initialCandidate;
    private long candidateNanos;
    private long seenSequence = -1, previousNanos;
    private int peak, fullLineReadings;
    private boolean exhausted;
    private int sufficientReadings;
    private boolean sufficient;

    FishingDepthMonitor.Depth accept(FishingDepthMonitor.Depth depth, long sequence, long receivedNanos) {
        if (depth == null || sequence <= seenSequence || receivedNanos < 0
                || depth.maximum() <= 0 || depth.maximum() > 10000
                || depth.current() < 0 || depth.current() > depth.maximum()) return null;
        seenSequence = sequence;
        if (previous == null) {
            if (initialCandidate != null && receivedNanos <= candidateNanos) return null;
            if (initialCandidate == null || depth.maximum() != initialCandidate.maximum()) {
                initialCandidate = depth;
                candidateNanos = receivedNanos;
                return null;
            }
            // A single truncated maximum must not pin the entire session to that value.
            // Keep the earlier plausible candidate when the confirming current depth jumps.
            double allowedJump = 3 + MAX_METRES_PER_SECOND * (receivedNanos - candidateNanos) / 1_000_000_000.0;
            if (Math.abs(depth.current() - initialCandidate.current()) > allowedJump) return null;
        }
        if (previous != null) {
            if (depth.maximum() != previous.maximum() || receivedNanos <= previousNanos) return null;
            double elapsedSeconds = (receivedNanos - previousNanos) / 1_000_000_000.0;
            double allowedJump = 3 + MAX_METRES_PER_SECOND * elapsedSeconds;
            if (Math.abs(depth.current() - previous.current()) > allowedJump) return null;
        }
        previous = depth;
        previousNanos = receivedNanos;
        peak = Math.max(peak, depth.current());
        fullLineReadings = depth.current() == depth.maximum() ? fullLineReadings + 1 : 0;
        exhausted |= fullLineReadings >= 2;
        sufficientReadings = depth.current() * 5L >= depth.maximum() * 4L ? sufficientReadings + 1 : 0;
        sufficient |= sufficientReadings >= 2;
        return depth;
    }

    int peak() { return peak; }
    boolean lineExhausted() { return exhausted; }
    boolean sufficientDescent() { return sufficient; }
}
