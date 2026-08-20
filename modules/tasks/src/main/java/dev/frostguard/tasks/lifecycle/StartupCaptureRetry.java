package dev.frostguard.tasks.lifecycle;

import dev.frostguard.engine.error.StartupCaptureException;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

final class StartupCaptureRetry {

    static final int MAX_ATTEMPTS = 3;
    static final long RETRY_DELAY_MS = 500;

    private StartupCaptureRetry() {
    }

    static <T> T capture(
            CaptureContext context,
            Supplier<T> capture,
            Consumer<String> warningLog,
            LongConsumer sleeper) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(capture);
        Objects.requireNonNull(warningLog);
        Objects.requireNonNull(sleeper);

        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                T frame = capture.get();
                if (frame == null) {
                    throw new IllegalStateException("capture returned no frame");
                }
                if (attempt > 1) {
                    warningLog.accept(context.prefix(attempt)
                            + "; outcome=fresh frame captured; retryDecision=continue startup inspection");
                }
                return frame;
            } catch (RuntimeException failure) {
                lastFailure = failure;
                String decision = attempt < MAX_ATTEMPTS
                        ? "retry fresh frame after " + RETRY_DELAY_MS + " ms"
                        : "delegate to bounded task-failure reschedule";
                warningLog.accept(context.prefix(attempt)
                        + "; cause=" + causeChain(failure)
                        + "; retryDecision=" + decision);
                if (attempt < MAX_ATTEMPTS) {
                    sleeper.accept(RETRY_DELAY_MS);
                }
            }
        }

        throw new StartupCaptureException(
                context.prefix(MAX_ATTEMPTS)
                        + "; cause=" + causeChain(lastFailure)
                        + "; retryDecision=bounded task-failure reschedule after capture retry limit",
                lastFailure);
    }

    private static String causeChain(Throwable failure) {
        StringBuilder detail = new StringBuilder();
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 4) {
            if (!detail.isEmpty()) {
                detail.append(" <- ");
            }
            detail.append(current.getClass().getSimpleName())
                    .append(": ")
                    .append(Objects.requireNonNullElse(current.getMessage(), "no message"));
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return detail.toString().replaceAll("[\\r\\n]+", " ");
    }

    record CaptureContext(
            String emulator,
            String serial,
            String inspection,
            String lastVerifiedState) {

        CaptureContext {
            emulator = readable(emulator, "unknown");
            serial = readable(serial, "unavailable");
            inspection = readable(inspection, "startup screen");
            lastVerifiedState = readable(lastVerifiedState, "none");
        }

        String prefix(int attempt) {
            return "Startup screen capture failed"
                    + "; emulator=" + emulator
                    + "; serial=" + serial
                    + "; inspection=" + inspection
                    + "; captureAttempt=" + attempt + "/" + MAX_ATTEMPTS
                    + "; lastVerifiedState=" + lastVerifiedState;
        }

        private static String readable(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }
}
