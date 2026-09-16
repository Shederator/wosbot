package dev.frostguard.tasks.events;

/** Learns bounded finger-to-hook gain only from fresh observations after completed input. */
final class FishingSteeringFeedback {
    private static final long RESPONSE_TIMEOUT_NANOS = 650_000_000L;
    private static final long OVERALL_TIMEOUT_NANOS = 1_300_000_000L;
    private static final long SETTLING_NANOS = 120_000_000L;
    private double gain = 1.2;
    private double freshGain = 1.2;
    private boolean establishedContact;
    private long firstMovementNanos;
    private long observedSequence;
    private long observedNanos;
    private int origin, drag;
    private long sentSequence;
    private long sentNanos;
    private boolean pending;
    private boolean timedOut;
    private int observedX;
    private long lastMovementNanos;

    int dragTo(int hookX, int targetX) {
        return dragTo(hookX, targetX, true);
    }

    int dragTo(int hookX, int targetX, boolean establishedContact) {
        return (int) Math.round(Math.max(-250, Math.min(250,
                (targetX - hookX) / (establishedContact ? gain : freshGain))));
    }

    void sent(int hookX, int fingerDelta, long sequence) {
        sent(hookX, fingerDelta, sequence, System.nanoTime());
    }

    void sent(int hookX, int fingerDelta, long sequence, long completedNanos) {
        sent(hookX, fingerDelta, sequence, completedNanos, true);
    }

    void sent(int hookX, int fingerDelta, long sequence, long completedNanos, boolean establishedContact) {
        origin = hookX;
        drag = fingerDelta;
        sentSequence = sequence;
        sentNanos = completedNanos;
        observedX = hookX;
        lastMovementNanos = completedNanos;
        firstMovementNanos = 0;
        observedSequence = sequence;
        observedNanos = completedNanos;
        this.establishedContact = establishedContact;
        pending = true;
        timedOut = false;
    }

    boolean observe(int hookX, long sequence) {
        return observe(hookX, sequence, System.nanoTime());
    }

    boolean waitingForResponse(long nowNanos) {
        long phaseStarted = firstMovementNanos == 0 ? sentNanos : firstMovementNanos;
        if (pending && (nowNanos - phaseStarted >= RESPONSE_TIMEOUT_NANOS
                || nowNanos - sentNanos >= OVERALL_TIMEOUT_NANOS)) {
            pending = false; timedOut = true;
        }
        return pending;
    }

    boolean takeTimedOut() {
        boolean result = timedOut;
        timedOut = false;
        return result;
    }

    boolean observe(int hookX, long sequence, long frameNanos) {
        if (sequence <= observedSequence || frameNanos <= observedNanos) return false;
        observedSequence = sequence; observedNanos = frameNanos;
        if (!waitingForResponse(frameNanos) || sequence <= sentSequence || frameNanos <= sentNanos) return false;
        int movement = hookX - origin;
        if (Math.abs(drag) < 15 || Math.abs(movement) < 8 || movement * drag <= 0
                || hookX < 0 || hookX >= 720) return false;
        double measured = (double) movement / drag;
        if (firstMovementNanos == 0) firstMovementNanos = frameNanos;
        if (Math.abs(hookX - observedX) > 6) {
            observedX = hookX;
            lastMovementNanos = frameNanos;
            return false;
        }
        // A partially observed drag must not authorize a reversing correction or teach its gain.
        if (frameNanos - lastMovementNanos < SETTLING_NANOS) return false;
        pending = false;
        // Settled movement unlocks replanning even when clipped or unusually small.
        // Only an unclipped, plausible response can teach finger-to-hook gain.
        if (hookX >= 45 && hookX <= 675 && measured >= 0.5 && measured <= 2.5) {
            if (establishedContact) gain = Math.max(0.75, Math.min(1.75, 0.7 * gain + 0.3 * measured));
            else freshGain = Math.max(0.75, Math.min(1.75, 0.7 * freshGain + 0.3 * measured));
        }
        return true;
    }
}
