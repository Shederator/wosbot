package dev.frostguard.app.shared;

import java.util.Objects;

public record ValidationResult<T>(T value, String message) {

    public ValidationResult {
        if (value == null && (message == null || message.isBlank())) {
            throw new IllegalArgumentException("An invalid result requires a message");
        }
    }

    public static <T> ValidationResult<T> valid(T value) {
        return new ValidationResult<>(Objects.requireNonNull(value), "");
    }

    public static <T> ValidationResult<T> invalid(String message) {
        return new ValidationResult<>(null, Objects.requireNonNull(message));
    }

    public boolean isValid() {
        return value != null;
    }
}
