package dev.frostguard.engine.error;

/**
 * Signals that an initialization attempt ended without establishing a safe
 * profile session and has already selected its retry behavior.
 */
public class InitializationFailedException extends RuntimeException {

    public InitializationFailedException(String message) {
        super(message);
    }
}
