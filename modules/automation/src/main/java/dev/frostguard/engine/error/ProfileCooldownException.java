package dev.frostguard.engine.error;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Signals that a task exhausted its immediate recovery and the whole profile
 * must yield its emulator slot until a known retry time.
 */
public class ProfileCooldownException extends RuntimeException {

    private static final long serialVersionUID = 1642644436067593448L;

    private final LocalDateTime retryAt;
    private final ActionRequiredContext actionRequiredContext;

    public ProfileCooldownException(String message, LocalDateTime retryAt) {
        this(message, retryAt, null);
    }

    public ProfileCooldownException(String message, LocalDateTime retryAt,
            ActionRequiredContext actionRequiredContext) {
        super(message);
        this.retryAt = Objects.requireNonNull(retryAt, "retryAt");
        this.actionRequiredContext = actionRequiredContext;
    }

    public LocalDateTime getRetryAt() {
        return retryAt;
    }

    public Optional<ActionRequiredContext> getActionRequiredContext() {
        return Optional.ofNullable(actionRequiredContext);
    }
}
