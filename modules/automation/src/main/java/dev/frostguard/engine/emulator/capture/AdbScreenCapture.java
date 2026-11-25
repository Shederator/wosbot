package dev.frostguard.engine.emulator.capture;

import dev.frostguard.api.domain.RawImageData;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Standard cross-platform screen capture backed by ADB screencap.
 */
public class AdbScreenCapture implements ScreenCapture {

    public static final String BACKEND_NAME = "adb";

    private final Function<String, RawImageData> captureFunction;
    private final Function<String, Boolean> availabilityCheck;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong sessionGeneration = new AtomicLong(1);

    public AdbScreenCapture(Function<String, RawImageData> captureFunction) {
        this(captureFunction, instance -> true);
    }

    public AdbScreenCapture(Function<String, RawImageData> captureFunction,
                            Function<String, Boolean> availabilityCheck) {
        this.captureFunction = Objects.requireNonNull(captureFunction, "captureFunction required");
        this.availabilityCheck = Objects.requireNonNull(availabilityCheck, "availabilityCheck required");
    }

    @Override
    public CaptureResult capture(String instance) throws ScreenCaptureException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Capture cancelled before ADB acquisition");
        }
        long start = System.nanoTime();
        try {
            RawImageData image = captureFunction.apply(instance);
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Capture cancelled during ADB screencap");
            }
            if (image == null || !image.isValid()) {
                throw new ScreenCaptureException(BACKEND_NAME, "ADB capture returned null or invalid frame for instance " + instance);
            }
            long end = System.nanoTime();
            return new CaptureResult(
                    image,
                    BACKEND_NAME,
                    sequence.incrementAndGet(),
                    end,
                    Duration.ofNanos(end - start),
                    sessionGeneration.get(),
                    true
            );
        } catch (Exception e) {
            if (e instanceof InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Capture cancelled during ADB acquisition: " + e.getMessage());
            }
            if (e instanceof ScreenCaptureException sce) {
                throw sce;
            }
            throw new ScreenCaptureException(BACKEND_NAME, "ADB capture failed for instance " + instance + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String backendName() {
        return BACKEND_NAME;
    }

    @Override
    public boolean isAvailable(String instance) {
        try {
            return availabilityCheck.apply(instance);
        } catch (Exception e) {
            return false;
        }
    }
}
