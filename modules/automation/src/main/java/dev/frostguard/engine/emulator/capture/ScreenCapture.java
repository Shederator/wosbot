package dev.frostguard.engine.emulator.capture;

/**
 * Shared contract for emulator screen capture backends.
 * Implementations supply normalized frames to the automation engine.
 */
public interface ScreenCapture extends AutoCloseable {

    /**
     * Captures a screenshot from the specified emulator instance.
     *
     * @param instance identifier of the emulator instance (e.g. "0")
     * @return capture result containing normalized raw image and metadata
     * @throws ScreenCaptureException if the capture operation fails
     * @throws InterruptedException   if the calling thread was interrupted
     */
    CaptureResult capture(String instance) throws ScreenCaptureException, InterruptedException;

    /**
     * Identifies the capture backend (e.g. "mumu_ipc", "adb", "scrcpy").
     */
    String backendName();

    /**
     * Checks if this backend is available and ready to capture for the given instance.
     *
     * @param instance identifier of the emulator instance
     * @return true if available, false otherwise
     */
    boolean isAvailable(String instance);

    @Override
    default void close() {}
}
