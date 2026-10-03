package dev.frostguard.tasks.combat;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Predicate;

final class BearVerifiedActionExecutor<T> {

    // Inputs must remain close to the sampled game state. A slow classifier is a classifier defect,
    // not permission to tap from an old image; callers must recover instead of widening this bound.
    static final Duration MAXIMUM_AUTHORIZING_FRAME_AGE = Duration.ofSeconds(1);

    enum Outcome {
        CONFIRMED,
        NOT_CONFIRMED,
        STALE_AUTHORIZATION,
        /** The input was refused or illegal from the observed state; nothing was sent. */
        NOT_AUTHORIZED,
        INTERRUPTED
    }

    record TransitionTrace(
            String action,
            BearNavigationPolicy.Screen priorState,
            BearNavigationPolicy.Screen observedState,
            long frameSequence,
            long frameAgeMillis,
            String expectedPostcondition,
            BearNavigationPolicy.Screen actualNextState,
            int attempt,
            String retryOrRecoveryReason,
            Outcome terminalOutcome) {
    }

    @FunctionalInterface
    interface TransitionObserver {
        void observe(TransitionTrace trace);
    }

    @FunctionalInterface
    interface FrameAction<T> {
        void run(BearFrameStream.Snapshot<T> authorization);
    }

    private final BearFrameStream<T> stream;
    private final Duration transitionDeadline;
    private final TransitionObserver observer;
    private long consumedSequence;
    private boolean lastInputSent;
    private String lastRefusal = "";

    BearVerifiedActionExecutor(BearFrameStream<T> stream, Duration transitionDeadline) {
        this(stream, transitionDeadline, ignored -> { });
    }

    BearVerifiedActionExecutor(
            BearFrameStream<T> stream,
            Duration transitionDeadline,
            TransitionObserver observer) {
        this.stream = Objects.requireNonNull(stream, "stream");
        this.transitionDeadline = Objects.requireNonNull(transitionDeadline, "transitionDeadline");
        if (transitionDeadline.isZero() || transitionDeadline.isNegative()) {
            throw new IllegalArgumentException("transitionDeadline must be positive");
        }
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    Outcome tapWithOneVerifiedRetry(
            BearFrameStream.Snapshot<T> authorizingFrame,
            Runnable tap,
            Predicate<BearFrameStream.Snapshot<T>> destination,
            Predicate<BearFrameStream.Snapshot<T>> sameTarget) {
        Objects.requireNonNull(tap, "tap");
        return execute(
                "tap",
                authorizingFrame,
                ignored -> true,
                ignored -> tap.run(),
                "verified destination",
                destination,
                sameTarget,
                1);
    }

    Outcome execute(
            String action,
            BearFrameStream.Snapshot<T> authorizingFrame,
            Predicate<BearFrameStream.Snapshot<T>> legalSource,
            FrameAction<T> input,
            String expectedPostcondition,
            Predicate<BearFrameStream.Snapshot<T>> destination,
            Predicate<BearFrameStream.Snapshot<T>> sameTarget,
            int maximumRetries) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(legalSource, "legalSource");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(expectedPostcondition, "expectedPostcondition");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(sameTarget, "sameTarget");
        if (maximumRetries < 0) {
            throw new IllegalArgumentException("maximumRetries cannot be negative");
        }
        lastInputSent = false;
        lastRefusal = "";
        if (stream.interrupted()) {
            trace(action, authorizingFrame, expectedPostcondition, null, 0,
                    "interrupted-before-input", Outcome.INTERRUPTED);
            return Outcome.INTERRUPTED;
        }
        if (!stream.isCurrent(authorizingFrame, MAXIMUM_AUTHORIZING_FRAME_AGE)
                || authorizingFrame.sequence() <= consumedSequence) {
            trace(action, authorizingFrame, expectedPostcondition, null, 0,
                    authorizingFrame != null && authorizingFrame.sequence() < stream.latestSequence()
                            ? "superseded-authorization"
                            : "stale-or-consumed-authorization",
                    Outcome.STALE_AUTHORIZATION);
            return Outcome.STALE_AUTHORIZATION;
        }
        if (!legalSource.test(authorizingFrame)) {
            lastRefusal = "illegal-source-state";
            trace(action, authorizingFrame, expectedPostcondition, authorizingFrame, 0,
                    "illegal-source-state", Outcome.NOT_AUTHORIZED);
            return Outcome.NOT_AUTHORIZED;
        }

        BearFrameStream.Snapshot<T> authorization = authorizingFrame;
        for (int attempt = 1; attempt <= maximumRetries + 1; attempt++) {
            if (stream.interrupted()) {
                trace(action, authorization, expectedPostcondition, null, attempt,
                        "interrupted-before-input", Outcome.INTERRUPTED);
                return Outcome.INTERRUPTED;
            }
            if (!stream.isCurrent(authorization, MAXIMUM_AUTHORIZING_FRAME_AGE)
                    || authorization.sequence() <= consumedSequence
                    || !legalSource.test(authorization)) {
                // After an earlier attempt the input may already have taken effect.
                Outcome invalid = lastInputSent ? Outcome.NOT_CONFIRMED : Outcome.STALE_AUTHORIZATION;
                trace(action, authorization, expectedPostcondition, authorization, attempt,
                        "retry-authorization-invalid", invalid);
                return invalid;
            }
            long previouslyConsumed = consumedSequence;
            consumedSequence = authorization.sequence();
            try {
                input.run(authorization);
            } catch (BearInputRefusedException refused) {
                consumedSequence = previouslyConsumed;
                lastRefusal = refused.getMessage();
                Outcome outcome = lastInputSent ? Outcome.NOT_CONFIRMED : Outcome.NOT_AUTHORIZED;
                trace(action, authorization, expectedPostcondition, authorization, attempt,
                        "input-refused:" + refused.getMessage(), outcome);
                return outcome;
            } catch (RuntimeException ambiguous) {
                lastInputSent = true;
                trace(action, authorization, expectedPostcondition, null, attempt,
                        "input-delivery-ambiguous:" + ambiguous.getClass().getSimpleName(),
                        Outcome.NOT_CONFIRMED);
                throw ambiguous;
            }
            lastInputSent = true;
            PollResult result = poll(authorization.sequence(), destination);
            if (result.outcome == Outcome.CONFIRMED || result.outcome == Outcome.INTERRUPTED) {
                trace(action, authorization, expectedPostcondition, result.last, attempt,
                        result.outcome == Outcome.CONFIRMED ? "postcondition-confirmed" : "interrupted-after-input",
                        result.outcome);
                return result.outcome;
            }
            if (attempt > maximumRetries || result.last == null || !sameTarget.test(result.last)) {
                trace(action, authorization, expectedPostcondition, result.last, attempt,
                        result.last == null ? "postcondition-timeout-no-frame" : "target-changed-or-postcondition-missed",
                        Outcome.NOT_CONFIRMED);
                return Outcome.NOT_CONFIRMED;
            }
            trace(action, authorization, expectedPostcondition, result.last, attempt,
                    "same-target-reauthorized", null);
            authorization = result.last;
        }
        return Outcome.NOT_CONFIRMED;
    }

    /** Whether the most recent {@code execute} sent any device input. */
    boolean lastInputSent() {
        return lastInputSent;
    }

    /** Why the most recent {@code execute} refused its input, or an empty string. */
    String lastRefusal() {
        return lastRefusal;
    }

    private void trace(
            String action,
            BearFrameStream.Snapshot<T> source,
            String expectedPostcondition,
            BearFrameStream.Snapshot<T> actual,
            int attempt,
            String reason,
            Outcome terminal) {
        BearNavigationPolicy.Screen prior = source == null
                ? BearNavigationPolicy.Screen.UNKNOWN
                : source.screen();
        observer.observe(new TransitionTrace(
                action,
                prior,
                prior,
                source == null ? 0 : source.sequence(),
                source == null ? 0 : stream.age(source).toMillis(),
                expectedPostcondition,
                actual == null ? BearNavigationPolicy.Screen.UNKNOWN : actual.screen(),
                attempt,
                reason,
                terminal));
    }

    private PollResult poll(long earlierSequence, Predicate<BearFrameStream.Snapshot<T>> destination) {
        if (stream.interrupted()) {
            return new PollResult(Outcome.INTERRUPTED, null);
        }
        BearFrameStream.AwaitResult<T> result = stream.awaitAfterResult(
                earlierSequence, transitionDeadline, destination);
        if (stream.interrupted()) {
            return new PollResult(Outcome.INTERRUPTED, result.lastObserved());
        }
        return new PollResult(
                result.match().isPresent() ? Outcome.CONFIRMED : Outcome.NOT_CONFIRMED,
                result.lastObserved());
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
