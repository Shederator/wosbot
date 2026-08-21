package dev.frostguard.engine.error;

import java.util.Objects;

public record ActionRequiredContext(
        String signature,
        String title,
        String expectedState,
        String observedState,
        String lastAction,
        String retryOrFallback) {

    public ActionRequiredContext {
        signature = requireText(signature, "signature");
        title = requireText(title, "title");
        expectedState = normalize(expectedState);
        observedState = normalize(observedState);
        lastAction = normalize(lastAction);
        retryOrFallback = normalize(retryOrFallback);
    }

    private static String requireText(String value, String field) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
