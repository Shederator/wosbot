package dev.frostguard.engine.error;

public final class StartupCaptureException extends RuntimeException {

    public StartupCaptureException(String message, Throwable cause) {
        super(message, cause);
    }
}
