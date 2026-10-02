package dev.frostguard.tasks.events;

import java.util.Locale;

enum JourneyOfLightEventStatus {
    ACTIVE,
    ENDED,
    UNKNOWN;

    static JourneyOfLightEventStatus classify(String statusText, String queueText) {
        String normalizedStatus = statusText == null ? "" : statusText.toLowerCase(Locale.ROOT);
        String normalizedQueues = queueText == null ? "" : queueText.toLowerCase(Locale.ROOT);
        if (normalizedStatus.contains("collect") || normalizedQueues.contains("collect")) {
            return ENDED;
        }

        boolean eventTitleVisible = normalizedStatus.contains("journey") && normalizedStatus.contains("light");
        boolean queueControlsVisible = normalizedQueues.contains("employ")
                && normalizedQueues.contains("expedition");
        if (eventTitleVisible && queueControlsVisible) {
            return ACTIVE;
        }
        return UNKNOWN;
    }
}
