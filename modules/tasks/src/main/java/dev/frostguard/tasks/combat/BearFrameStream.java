package dev.frostguard.tasks.combat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class BearFrameStream<T> {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    record Snapshot<T>(
            long sequence,
            Instant capturedAt,
            T frame,
            BearNavigationPolicy.Screen screen) {

        boolean isFresh(Clock clock, Duration maximumAge) {
            return !capturedAt.plus(maximumAge).isBefore(clock.instant());
        }

        Duration age(Clock clock) {
            Duration age = Duration.between(capturedAt, clock.instant());
            return age.isNegative() ? Duration.ZERO : age;
        }
    }

    record AwaitResult<T>(Optional<Snapshot<T>> match, Snapshot<T> lastObserved) {
    }

    record Captured<T>(T frame, Instant capturedAt) {
        Captured {
            Objects.requireNonNull(frame, "frame");
            Objects.requireNonNull(capturedAt, "capturedAt");
        }
    }

    @FunctionalInterface
    interface TimestampedSource<T> {
        Captured<T> get();
    }

    @FunctionalInterface
    interface CaptureRecovery {
        boolean recover(RuntimeException failure, int failedAttempt);
    }

    private final TimestampedSource<T> source;
    private final Function<T, BearNavigationPolicy.Screen> classifier;
    private final BooleanSupplier interrupted;
    private final CaptureRecovery recovery;
    private final int captureAttempts;
    private final Clock clock;
    private long sequence;
    private Snapshot<T> latest;

    BearFrameStream(
            Supplier<T> source,
            Function<T, BearNavigationPolicy.Screen> classifier,
            BooleanSupplier interrupted) {
        this(source, classifier, interrupted, (failure, attempt) -> false, 1, Clock.systemUTC());
    }

    BearFrameStream(
            Supplier<T> source,
            Function<T, BearNavigationPolicy.Screen> classifier,
            BooleanSupplier interrupted,
            CaptureRecovery recovery,
            int captureAttempts,
            Clock clock) {
        this(() -> new Captured<>(Objects.requireNonNull(source, "source").get(), clock.instant()),
                classifier, interrupted, recovery, captureAttempts, clock, true);
    }

    static <T> BearFrameStream<T> fromTimestampedSource(
            TimestampedSource<T> source,
            Function<T, BearNavigationPolicy.Screen> classifier,
            BooleanSupplier interrupted,
            CaptureRecovery recovery,
            int captureAttempts,
            Clock clock) {
        return new BearFrameStream<>(source, classifier, interrupted, recovery,
                captureAttempts, clock, true);
    }

    private BearFrameStream(
            TimestampedSource<T> source,
            Function<T, BearNavigationPolicy.Screen> classifier,
            BooleanSupplier interrupted,
            CaptureRecovery recovery,
            int captureAttempts,
            Clock clock,
            boolean timestamped) {
        this.source = Objects.requireNonNull(source, "source");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.interrupted = Objects.requireNonNull(interrupted, "interrupted");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        if (captureAttempts < 1) {
            throw new IllegalArgumentException("captureAttempts must be positive");
        }
        this.captureAttempts = captureAttempts;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Snapshot<T> next() {
        Captured<T> captured = capture();
        T frame = captured.frame();
        latest = new Snapshot<>(++sequence, captured.capturedAt(), frame, classifier.apply(frame));
        return latest;
    }

    Snapshot<T> nextUnclassified() {
        Captured<T> captured = capture();
        latest = new Snapshot<>(++sequence, captured.capturedAt(), captured.frame(),
                BearNavigationPolicy.Screen.UNKNOWN);
        return latest;
    }

    Optional<Snapshot<T>> awaitAfter(
            long earlierSequence,
            Duration timeout,
            Predicate<Snapshot<T>> predicate) {
        return awaitAfterResult(earlierSequence, timeout, predicate).match();
    }

    AwaitResult<T> awaitAfterResult(
            long earlierSequence,
            Duration timeout,
            Predicate<Snapshot<T>> predicate) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Snapshot<T> lastObserved = null;
        while (System.nanoTime() < deadline && !interrupted.getAsBoolean()) {
            Snapshot<T> frame = next();
            lastObserved = frame;
            if (frame.sequence() > earlierSequence && predicate.test(frame)) {
                return new AwaitResult<>(Optional.of(frame), frame);
            }
            pauseBetweenCaptures();
        }
        return new AwaitResult<>(Optional.empty(), lastObserved);
    }

    boolean interrupted() {
        return interrupted.getAsBoolean();
    }

    boolean isFresh(Snapshot<T> snapshot, Duration maximumAge) {
        return snapshot != null && snapshot.isFresh(clock, maximumAge);
    }

    boolean isCurrent(Snapshot<T> snapshot, Duration maximumAge) {
        return snapshot != null
                && snapshot.sequence() == sequence
                && snapshot.isFresh(clock, maximumAge);
    }

    Duration age(Snapshot<T> snapshot) {
        return snapshot == null ? Duration.ZERO : snapshot.age(clock);
    }

    long latestSequence() {
        return sequence;
    }

    Snapshot<T> latest() {
        return latest;
    }

    private Captured<T> capture() {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= captureAttempts; attempt++) {
            if (interrupted.getAsBoolean()) {
                throw new CaptureInterruptedException();
            }
            try {
                return Objects.requireNonNull(source.get(), "captured frame");
            } catch (RuntimeException failure) {
                lastFailure = failure;
                if (attempt == captureAttempts || !recovery.recover(failure, attempt)) {
                    throw failure;
                }
            }
        }
        throw Objects.requireNonNull(lastFailure, "capture failure");
    }

    private void pauseBetweenCaptures() {
        try {
            Thread.sleep(POLL_INTERVAL.toMillis());
        } catch (InterruptedException interruptedFailure) {
            Thread.currentThread().interrupt();
            throw new CaptureInterruptedException();
        }
    }

    static final class CaptureInterruptedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
