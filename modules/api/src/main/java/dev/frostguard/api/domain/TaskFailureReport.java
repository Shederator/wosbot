package dev.frostguard.api.domain;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One explicitly reported failed task attempt. Tasks that catch and reschedule
 * their own failures can submit this after an attempt; ordinary thrown
 * exceptions are submitted centrally by the queue.
 */
public record TaskFailureReport(
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
        LocalDateTime retryAt,
        int escalationThreshold) {

    public TaskFailureReport {
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
        Objects.requireNonNull(retryAt, "retryAt");
        if (escalationThreshold < 1) {
            throw new IllegalArgumentException("escalationThreshold must be positive");
        }
    }

    private static String requireText(String value, String name) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
