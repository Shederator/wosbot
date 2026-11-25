package dev.frostguard.engine.emulator.capture.mumu;

import dev.frostguard.engine.emulator.capture.CaptureResult;
import dev.frostguard.engine.emulator.capture.ScreenCapture;
import dev.frostguard.engine.emulator.capture.ScreenCaptureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Composite screen capture for MuMu Player.
 * Attempts high-performance native Nemu IPC capture first and transparently falls back
 * to exact-device ADB capture if native IPC is unavailable or encounters a failure.
 */
public class MuMuNemuScreenCapture implements ScreenCapture {

    private static final Logger LOG = LoggerFactory.getLogger(MuMuNemuScreenCapture.class);

    private final ScreenCapture primary;
    private final ScreenCapture fallback;
    private final Object lifecycleLock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong sessionGeneration = new AtomicLong(1);
    private ScreenCapture activeSource;
    private String activeInstance;
    private Long activeSourceGeneration;
    private volatile boolean closed;

    public MuMuNemuScreenCapture(Path mumuRoot, String targetInstance, ScreenCapture adbFallback) {
        this(new MuMuNemuProcessCapture(mumuRoot, targetInstance), adbFallback);
    }

    MuMuNemuScreenCapture(ScreenCapture primary, ScreenCapture fallback) {
        this.primary = Objects.requireNonNull(primary, "primary required");
        this.fallback = Objects.requireNonNull(fallback, "fallback required");
        activeSource = primary;
    }

    @Override
    public CaptureResult capture(String instance) throws ScreenCaptureException, InterruptedException {
        requireOpen();
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Capture cancelled before acquisition");
        }

        CaptureResult rawResult = null;
        ScreenCapture source = primary;
        if (primary.isAvailable(instance)) {
            try {
                rawResult = primary.capture(instance);
            } catch (ScreenCaptureException sce) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Capture cancelled during primary capture");
                }
                requireOpen();
                LOG.warn("Primary capture [{}] failed for instance {}; falling back to [{}]: {}",
                        primary.backendName(), instance, fallback.backendName(), sce.getMessage());
            }
        }

        if (rawResult == null) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Capture cancelled before fallback");
            }
            requireOpen();
            source = fallback;
            rawResult = fallback.capture(instance);
        }

        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Capture cancelled after frame acquisition");
        }

        // Publication and close share only this short state lock, never capture I/O.
        synchronized (lifecycleLock) {
            requireOpen();
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Capture cancelled before publication");
            }
            if (source != activeSource
                    || activeInstance != null && !activeInstance.equals(instance)
                    || activeSourceGeneration != null && activeSourceGeneration != rawResult.sessionGeneration()) {
                sessionGeneration.incrementAndGet();
            }
            activeSource = source;
            activeInstance = instance;
            activeSourceGeneration = rawResult.sessionGeneration();
            return new CaptureResult(
                    rawResult.image(),
                    rawResult.backend(),
                    sequence.incrementAndGet(),
                    rawResult.timestampNanos(),
                    rawResult.latency(),
                    sessionGeneration.get(),
                    rawResult.fresh()
            );
        }
    }

    private void requireOpen() throws ScreenCaptureException {
        if (closed) {
            throw new ScreenCaptureException(backendName(), "ScreenCapture is closed");
        }
    }

    @Override
    public String backendName() {
        return "mumu_composite";
    }

    @Override
    public boolean isAvailable(String instance) {
        if (closed) {
            return false;
        }
        return primary.isAvailable(instance) || fallback.isAvailable(instance);
    }

    public ScreenCapture getPrimary() {
        return primary;
    }

    public long currentSessionGeneration() {
        return sessionGeneration.get();
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            sessionGeneration.incrementAndGet();
        }
        try {
            primary.close();
        } catch (Exception e) {
            LOG.debug("Error closing primary capture backend: {}", e.getMessage());
        }
        try {
            fallback.close();
        } catch (Exception e) {
            LOG.debug("Error closing fallback capture backend: {}", e.getMessage());
        }
    }
}
