package dev.frostguard.tasks.events;

import java.util.regex.Pattern;

/** Capacity is observed, never inferred from steering or object disappearance. */
final class FishingHaulTracker {
    record Reading(int caught, int capacity, long sequence, long receivedNanos, boolean full) {
        int freeSlots() { return capacity - caught; }
    }

    private static final Pattern FRACTION = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");
    private int capacity = -1, confirmations, previousCaught = -1, fullConfirmations;
    private long sequence = -1, receivedNanos = -1;

    Reading accept(String text, long frameSequence, long frameNanos) {
        if (text == null || frameSequence <= sequence || frameNanos <= receivedNanos) return null;
        var matcher = FRACTION.matcher(text.trim());
        if (!matcher.matches()) return null;
        try {
            int count = Integer.parseInt(matcher.group(1)), limit = Integer.parseInt(matcher.group(2));
            if (limit < 1 || limit > 100 || count < 0 || count > limit) return null;
            if (capacity != limit) {
                if (confirmations >= 2) return null;
                capacity = limit;
                confirmations = 0;
                previousCaught = -1;
                fullConfirmations = 0;
            }
            if (count < previousCaught) return null;
            sequence = frameSequence;
            receivedNanos = frameNanos;
            previousCaught = count;
            confirmations++;
            fullConfirmations = count == limit ? fullConfirmations + 1 : 0;
            return confirmations < 2 ? null
                    : new Reading(count, limit, frameSequence, frameNanos, fullConfirmations >= 2);
        } catch (NumberFormatException invalid) { return null; }
    }
}
