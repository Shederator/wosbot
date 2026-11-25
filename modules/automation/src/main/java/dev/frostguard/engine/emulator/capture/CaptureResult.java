package dev.frostguard.engine.emulator.capture;

import dev.frostguard.api.domain.RawImageData;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable outcome of a screen capture observation.
 *
 * @param image             normalized raw image data (top-left origin, RGBA/RGB)
 * @param backend           identifier of the backend that captured the frame (e.g. "mumu_ipc", "adb")
 * @param sequence          monotonic sequence number of the observation
 * @param timestampNanos    host timestamp when the frame was acquired (System.nanoTime())
 * @param latency           measured duration of the capture operation
 * @param sessionGeneration generation of the active capture session (increments on reconnect / lease / fallback)
 * @param fresh             true if this observation was captured fresh on demand, false if retained/cached
 */
public record CaptureResult(
        RawImageData image,
        String backend,
        long sequence,
        long timestampNanos,
        Duration latency,
        long sessionGeneration,
        boolean fresh
) {
    public CaptureResult {
        Objects.requireNonNull(image, "image required");
        Objects.requireNonNull(backend, "backend required");
        Objects.requireNonNull(latency, "latency required");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be non-negative: " + sequence);
        }
        if (sessionGeneration < 0) {
            throw new IllegalArgumentException("sessionGeneration must be non-negative: " + sessionGeneration);
        }
    }

    public CaptureResult(RawImageData image, String backend, long sequence, long timestampNanos, Duration latency) {
        this(image, backend, sequence, timestampNanos, latency, 0L, true);
    }

}
