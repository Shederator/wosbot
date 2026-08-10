package dev.frostguard.engine.emulator;

// Receives updates when a thread's position in the emulator slot queue changes.
@FunctionalInterface
public interface QueuePositionListener {

    void onQueuePositionChanged(Thread blockedThread, int placement);

    static QueuePositionListener silent() {
        return (t, p) -> {};
    }

    default QueuePositionListener deduplicated() {
        final int[] prev = {-1};
        return (t, p) -> { if (p != prev[0]) { prev[0] = p; this.onQueuePositionChanged(t, p); } };
    }
}
