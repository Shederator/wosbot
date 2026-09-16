package dev.frostguard.tasks.events;

/** Invalid persisted limits fail closed to a single cast, never to unlimited spending. */
final class FishingSessionPolicy {
    private FishingSessionPolicy() { }

    static int castLimit(Integer configured) {
        return configured != null && configured >= 1 && configured <= 10 ? configured : 1;
    }

    static int restartLimit(Integer configured) {
        return configured != null && configured >= 0 && configured <= 10 ? configured : 0;
    }

    static int specialCastLimit(Integer configured) {
        return configured != null && configured >= 0 && configured <= 10 ? configured : 0;
    }

    static int catchTarget(Integer configured, int capacity) {
        int target = configured != null && configured >= 1 && configured <= 100 ? configured : 20;
        return Math.min(target, Math.max(0, capacity));
    }

    static boolean shouldRestart(boolean enabled, int restartsUsed, int maxRestarts,
            FishingPhaseTracker.Phase phase, FishingDepthMonitor.Reading depth,
            FishingHaulTracker.Reading haul, long now, int target) {
        return enabled && restartsUsed < maxRestarts && phase == FishingPhaseTracker.Phase.ASCENDING
                && depth != null && haul != null && now >= depth.receivedNanos() && now >= haul.receivedNanos()
                && now - depth.receivedNanos() < 1_000_000_000L && now - haul.receivedNanos() < 1_000_000_000L
                // Leave enough surface distance to verify Pause before the tally becomes irreversible.
                && depth.depth().current() > 0 && depth.depth().current() <= 40
                && haul.caught() < catchTarget(target, haul.capacity());
    }

    static boolean verifiedHaul(String heading, String exit) {
        return heading != null && exit != null
                && heading.trim().equalsIgnoreCase("Haul")
                && exit.trim().equalsIgnoreCase("Exit");
    }
}
