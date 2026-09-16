package dev.frostguard.tasks.events;

/** Two agreeing distinct frames; malformed entry-frame text never authorizes bait. */
final class FishingBaitConfirmation {
    private long lastSequence = -1;
    private String candidate;

    int accept(String text, long sequence) {
        if (sequence <= lastSequence) return -1;
        lastSequence = sequence;
        int observed = FishingMinigameRoutine.parseBait(text);
        String normalized = observed >= 0 ? text.replaceAll("\\s+", "") : null;
        boolean confirmed = normalized != null && normalized.equals(candidate);
        candidate = normalized;
        return confirmed ? observed : -1;
    }
}
