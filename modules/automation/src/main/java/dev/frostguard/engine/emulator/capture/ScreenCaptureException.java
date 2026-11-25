package dev.frostguard.engine.emulator.capture;

import java.io.IOException;

/**
 * Thrown when a screen capture backend fails to acquire or process a frame.
 */
public class ScreenCaptureException extends IOException {

    private final String backend;

    public ScreenCaptureException(String backend, String message) {
        super(backend + ": " + message);
        this.backend = backend;
    }

    public ScreenCaptureException(String backend, String message, Throwable cause) {
        super(backend + ": " + message, cause);
        this.backend = backend;
    }

    public String getBackend() {
        return backend;
    }
}
