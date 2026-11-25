package dev.frostguard.engine.emulator.capture.mumu;

import com.sun.jna.ptr.IntByReference;

/** Executes one bounded, single-contact gesture inside the native helper. */
final class NemuTouchGesture {
    static final int MAX_DURATION_MS = 10_000;
    private final NemuIpcLibrary library;
    private final int handle;
    private final int width;
    private final int height;

    NemuTouchGesture(NemuIpcLibrary library, int handle, int width, int height) {
        this.library = library;
        this.handle = handle;
        this.width = width;
        this.height = height;
    }

    int tap(int x, int y) throws InterruptedException {
        return swipe(x, y, x, y, 40);
    }

    int swipe(int x, int y, int endX, int endY, int durationMs) throws InterruptedException {
        validatePoint(x, y);
        validatePoint(endX, endY);
        if (durationMs <= 0 || durationMs > MAX_DURATION_MS) {
            throw new IllegalArgumentException("Native gesture duration must be between 1 and 10000 ms");
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Touch cancelled");
        IntByReference actualWidth = new IntByReference();
        IntByReference actualHeight = new IntByReference();
        int rc = library.nemu_capture_display(handle, 0, 0, actualWidth, actualHeight, (byte[]) null);
        if (rc != 0) return rc;
        if (actualWidth.getValue() != width || actualHeight.getValue() != height || width >= height) {
            return NemuHelperWorker.ERR_GEOMETRY_CHANGED;
        }
        int result = 0;
        try {
            result = down(x, y);
            int steps = Math.max(1, (durationMs + 15) / 16);
            long start = System.nanoTime();
            for (int step = 1; result == 0 && step <= steps; step++) {
                long due = start + (long) durationMs * 1_000_000 * step / steps;
                long remaining = due - System.nanoTime();
                if (remaining > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Touch cancelled");
                if (x != endX || y != endY) {
                    result = down(x + (endX - x) * step / steps, y + (endY - y) * step / steps);
                }
            }
        } finally {
            int release = library.nemu_input_event_touch_up(handle, 0);
            if (result == 0) result = release;
        }
        return result;
    }

    private int down(int x, int y) {
        // The SDK applies display rotation itself; rotating again drops/misplaces touches.
        return library.nemu_input_event_touch_down(handle, 0, x, y);
    }

    private void validatePoint(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IllegalArgumentException("Touch point is outside the display");
        }
    }
}
