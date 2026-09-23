package dev.frostguard.tasks.combat;

import java.util.Objects;
import java.util.function.Predicate;

final class BearVerifiedActionExecutor<T> {

    enum Outcome {
        CONFIRMED,
        NOT_CONFIRMED,
        INTERRUPTED
    }

    private final BearFrameStream<T> stream;
    private final int framesPerAttempt;

    BearVerifiedActionExecutor(BearFrameStream<T> stream, int framesPerAttempt) {
        this.stream = Objects.requireNonNull(stream, "stream");
        if (framesPerAttempt < 1) {
            throw new IllegalArgumentException("framesPerAttempt must be positive");
        }
        this.framesPerAttempt = framesPerAttempt;
    }

    Outcome tapWithOneVerifiedRetry(
            BearFrameStream.Snapshot<T> authorizingFrame,
            Runnable tap,
            Predicate<BearFrameStream.Snapshot<T>> destination,
            Predicate<BearFrameStream.Snapshot<T>> sameTarget) {
        if (stream.interrupted()) {
            return Outcome.INTERRUPTED;
        }
        tap.run();
        PollResult first = poll(authorizingFrame.sequence(), destination);
        if (first.outcome == Outcome.CONFIRMED || first.outcome == Outcome.INTERRUPTED) {
            return first.outcome;
        }
        if (first.last == null || !sameTarget.test(first.last) || stream.interrupted()) {
            return Outcome.NOT_CONFIRMED;
        }

        tap.run();
        return poll(first.last.sequence(), destination).outcome;
    }

    private PollResult poll(long earlierSequence, Predicate<BearFrameStream.Snapshot<T>> destination) {
        BearFrameStream.Snapshot<T> last = null;
        for (int i = 0; i < framesPerAttempt; i++) {
            if (stream.interrupted()) {
                return new PollResult(Outcome.INTERRUPTED, last);
            }
            BearFrameStream.Snapshot<T> frame = stream.next();
            if (stream.interrupted()) {
                return new PollResult(Outcome.INTERRUPTED, frame);
            }
            if (frame.sequence() <= earlierSequence) {
                continue;
            }
            last = frame;
            if (destination.test(frame)) {
                return new PollResult(Outcome.CONFIRMED, frame);
            }
        }
        return new PollResult(Outcome.NOT_CONFIRMED, last);
    }

    private final class PollResult {
        private final Outcome outcome;
        private final BearFrameStream.Snapshot<T> last;

        private PollResult(Outcome outcome, BearFrameStream.Snapshot<T> last) {
            this.outcome = outcome;
            this.last = last;
        }
    }
}
