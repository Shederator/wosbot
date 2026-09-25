package dev.frostguard.tasks.combat;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class BearFrameStream<T> {

    record Snapshot<T>(long sequence, T frame, BearNavigationPolicy.Screen screen) {}

    private final Supplier<T> source;
    private final Function<T, BearNavigationPolicy.Screen> classifier;
    private final BooleanSupplier interrupted;
    private long sequence;

    BearFrameStream(
            Supplier<T> source,
            Function<T, BearNavigationPolicy.Screen> classifier,
            BooleanSupplier interrupted) {
        this.source = Objects.requireNonNull(source, "source");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.interrupted = Objects.requireNonNull(interrupted, "interrupted");
    }

    Snapshot<T> next() {
        T frame = Objects.requireNonNull(source.get(), "captured frame");
        return new Snapshot<>(++sequence, frame, classifier.apply(frame));
    }

    Snapshot<T> nextUnclassified() {
        T frame = Objects.requireNonNull(source.get(), "captured frame");
        return new Snapshot<>(++sequence, frame, BearNavigationPolicy.Screen.UNKNOWN);
    }

    Optional<Snapshot<T>> awaitAfter(
            long earlierSequence,
            Duration timeout,
            Predicate<Snapshot<T>> predicate) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && !interrupted.getAsBoolean()) {
            Snapshot<T> frame = next();
            if (frame.sequence() > earlierSequence && predicate.test(frame)) {
                return Optional.of(frame);
            }
        }
        return Optional.empty();
    }

    boolean interrupted() {
        return interrupted.getAsBoolean();
    }
}
