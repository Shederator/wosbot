package dev.frostguard.api.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Structured input for a durable operator-action incident. Callers should use
 * a stable signature that identifies the failure condition, not one occurrence.
 */
public record ActionRequiredIncidentReport(
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
        LocalDateTime retryAt) {

    public ActionRequiredIncidentReport {
        Objects.requireNonNull(profileId, "profileId");
        profileName = requireText(profileName, "profileName");
        taskKey = requireText(taskKey, "taskKey");
        taskName = requireText(taskName, "taskName");
        signature = requireText(signature, "signature");
        title = requireText(title, "title");
        cause = requireText(cause, "cause");
        expectedState = normalize(expectedState);
        observedState = normalize(observedState);
        lastAction = normalize(lastAction);
        retryOrFallback = normalize(retryOrFallback);
        resourceOutcome = normalize(resourceOutcome);
    }

    private static String requireText(String value, String name) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
