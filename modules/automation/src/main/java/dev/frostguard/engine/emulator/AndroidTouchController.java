package dev.frostguard.engine.emulator;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Single-owner touch protocol writer. It does not launch processes or change desktop focus. */
final class AndroidTouchController implements AutoCloseable {
    private final OutputStream output;
    private boolean pressed, closed;
    private int lastX, lastY;

    AndroidTouchController(OutputStream output) { this.output = Objects.requireNonNull(output); }

    boolean isSteering() { return pressed && !closed; }

    boolean canContinueSteering(int delta) {
        return isSteering() && Math.abs((long) delta) <= 720 && lastX + (long) delta >= 30
                && lastX + (long) delta <= 690;
    }

    void primeSteering(int hookX, int hookY) throws IOException, InterruptedException {
        if (closed) throw new IOException("Touch controller closed");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Touch cancelled");
        validate(hookX, hookY);
        // Live isolation showed that retained stationary contact avoided missed fresh drags.
        // Do not repeat startup time on an already-established pointer.
        if (!pressed) steer(hookX, hookY, 0, 150);
    }

    int steer(int hookX, int hookY, int delta, int durationMs) throws IOException, InterruptedException {
        if (closed) throw new IOException("Touch controller closed");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Touch cancelled");
        validate(hookX, hookY);
        if (durationMs < 1 || durationMs > 250 || Math.abs((long) delta) > 720) {
            throw new IllegalArgumentException("Invalid bounded touch gesture");
        }
        try {
            // Finger-to-hook gain can leave the pointer at an edge while the observed hook
            // is still movable. Reanchor before a same-direction request would be clipped.
            if (pressed && (lastX + delta < 30 || lastX + delta > 690)) endSteering();
            if (!pressed) {
                // Mark before writing: even a partially delivered DOWN requires a best-effort UP.
                lastX = hookX; lastY = hookY; pressed = true;
                send(0, lastX, lastY);
                // Provisional contact dwell for a slow-rendering game. Subsequent moves
                // keep the established pointer and do not repeat this startup delay.
                Thread.sleep(80);
                durationMs = Math.max(150, durationMs);
            }
            int from = lastX, y = lastY;
            int to = Math.max(30, Math.min(690, from + delta));
            int steps = Math.max(1, durationMs / 16);
            long started = System.nanoTime();
            for (int i = 1; i <= steps; i++) {
                long remaining = started + durationMs * 1_000_000L * i / steps - System.nanoTime();
                if (remaining > 0) Thread.sleep(remaining / 1_000_000, (int) (remaining % 1_000_000));
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Touch cancelled");
                send(2, from + (to - from) * i / steps, y);
            }
            return to - from;
        } catch (IOException | InterruptedException error) {
            try { endSteering(); } catch (IOException releaseError) { error.addSuppressed(releaseError); }
            throw error;
        }
    }

    void endSteering() throws IOException {
        if (pressed) {
            try { send(1, lastX, lastY); } finally { pressed = false; }
        }
    }

    private void send(int action, int x, int y) throws IOException {
        output.write(packet(action, x, y));
        lastX = x; lastY = y;
    }

    static byte[] packet(int action, int x, int y) {
        validate(x, y);
        if (action < 0 || action > 2) throw new IllegalArgumentException("Invalid touch action");
        return ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
                .put((byte) 2).put((byte) action).putLong(0).putInt(x).putInt(y)
                .putShort((short) 720).putShort((short) 1280)
                .putShort((short) (action == 1 ? 0 : 0xffff)).putInt(0).putInt(0).array();
    }

    private static void validate(int x, int y) {
        if (x < 0 || x >= 720 || y < 0 || y >= 1280) throw new IllegalArgumentException("Invalid touch coordinates");
    }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        endSteering();
    }
}
