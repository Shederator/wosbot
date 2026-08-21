package dev.frostguard.api.domain;

import java.time.LocalDateTime;

public record ActionRequiredIncidentData(
        String id,
        Long profileId,
        String profileName,
        String taskKey,
        String taskName,
        String signature,
        String title,
        String cause,
        String expectedState,
        String observedState,
        String lastAction,
        String retryOrFallback,
        String resourceOutcome,
        String logExcerpt,
        LocalDateTime retryAt,
        LocalDateTime firstSeenAt,
        LocalDateTime lastSeenAt,
        int occurrenceCount,
        LocalDateTime acknowledgedAt,
        LocalDateTime recoveredAt) {

    public ActionRequiredIncidentState state() {
        return recoveredAt == null
                ? ActionRequiredIncidentState.ACTIVE
                : ActionRequiredIncidentState.RECOVERED;
    }

    public boolean isUnread() {
        return state() == ActionRequiredIncidentState.ACTIVE && acknowledgedAt == null;
    }
}
