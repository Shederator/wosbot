package dev.frostguard.tasks.combat;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * The single UI transition boundary for a Bear session. It owns the latest ordered observation;
 * callers cannot authorize input with an older snapshot, and every input must name a legal edge
 * with a newer-frame postcondition.
 */
final class BearUiStateMachine<T> {

    private static final Duration DEFAULT_TRANSITION_DEADLINE = Duration.ofSeconds(4);

    enum Phase {
        PREPARING,
        WAITING_FOR_ACTIVATION,
        ACTIVE,
        JOIN_ONLY,
        RECOVERING,
        CLEANING_UP,
        TERMINAL
    }

    enum TerminalReason {
        EVENT_ENDED,
        CANCELLED,
        RECOVERY_EXHAUSTED,
        OPERATOR_ACTION_REQUIRED
    }

    private final BearFrameStream<T> frames;
    private final BearVerifiedActionExecutor<T> actions;
    private final Consumer<String> diagnostics;
    private final BiConsumer<BearFrameStream.Snapshot<T>, Runnable> inputBoundary;
    private BearFrameStream.Snapshot<T> current;
    private Phase phase = Phase.PREPARING;
    private TerminalReason terminalReason;

    BearUiStateMachine(
            BearFrameStream<T> frames,
            Duration transitionDeadline,
            Consumer<String> diagnostics) {
        this(frames, transitionDeadline, diagnostics, (frame, input) -> input.run());
    }

    BearUiStateMachine(
            BearFrameStream<T> frames,
            Duration transitionDeadline,
            Consumer<String> diagnostics,
            BiConsumer<BearFrameStream.Snapshot<T>, Runnable> inputBoundary) {
        this.frames = Objects.requireNonNull(frames, "frames");
        this.inputBoundary = Objects.requireNonNull(inputBoundary, "inputBoundary");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.actions = new BearVerifiedActionExecutor<>(
                frames,
                transitionDeadline,
                trace -> this.diagnostics.accept(format(trace)));
    }

    BearFrameStream.Snapshot<T> observe() {
        current = frames.next();
        diagnostics.accept("observe phase=" + phase
                + " state=" + current.screen()
                + " frame=" + current.sequence()
                + " ageMs=" + frames.age(current).toMillis());
        return current;
    }

    Optional<BearFrameStream.Snapshot<T>> await(
            Duration deadline,
            Predicate<BearFrameStream.Snapshot<T>> postcondition,
            String reason) {
        long earlierSequence = current == null ? frames.latestSequence() : current.sequence();
        BearFrameStream.AwaitResult<T> result = frames.awaitAfterResult(
                earlierSequence,
                deadline == null ? DEFAULT_TRANSITION_DEADLINE : deadline,
                postcondition);
        Optional<BearFrameStream.Snapshot<T>> observed = result.match();
        if (result.lastObserved() != null) {
            current = result.lastObserved();
        }
        diagnostics.accept("wait phase=" + phase
                + " reason=" + reason
                + " fromFrame=" + earlierSequence
                + " result=" + observed.map(frame -> frame.screen().name()).orElse("TIMEOUT")
                + " nextFrame=" + (result.lastObserved() == null
                        ? 0L : result.lastObserved().sequence())
                + " actual=" + (result.lastObserved() == null
                        ? BearNavigationPolicy.Screen.UNKNOWN : result.lastObserved().screen()));
        return observed;
    }

    BearVerifiedActionExecutor.Outcome transition(
            BearUiAction action,
            BearVerifiedActionExecutor.FrameAction<T> input,
            Predicate<BearFrameStream.Snapshot<T>> sameTarget,
            int maximumRetries) {
        return transition(action, input, action.expectedPostcondition(),
                frame -> action.confirms(frame.screen()), sameTarget, maximumRetries);
    }

    BearVerifiedActionExecutor.Outcome transition(
            BearUiAction action,
            BearVerifiedActionExecutor.FrameAction<T> input,
            String expectedPostcondition,
            Predicate<BearFrameStream.Snapshot<T>> postcondition,
            Predicate<BearFrameStream.Snapshot<T>> sameTarget,
            int maximumRetries) {
        Objects.requireNonNull(action, "action");
        if (current == null
                || !frames.isCurrent(
                        current,
                        BearVerifiedActionExecutor.MAXIMUM_AUTHORIZING_FRAME_AGE)) {
            observe();
        }
        BearVerifiedActionExecutor.Outcome outcome = actions.execute(
                action.name(),
                current,
                frame -> action.legalFrom(frame.screen()),
                frame -> inputBoundary.accept(frame, () -> input.run(frame)),
                expectedPostcondition,
                postcondition,
                sameTarget,
                maximumRetries);
        if (outcome == BearVerifiedActionExecutor.Outcome.CONFIRMED) {
            // Retain the executor's newer postcondition frame. It may authorize the immediately
            // following edge, but only while it remains the stream's current fresh snapshot.
            current = frames.latest();
        }
        return outcome;
    }

    BearVerifiedActionExecutor.Outcome transition(
            BearUiAction action,
            BearVerifiedActionExecutor.FrameAction<T> input) {
        return transition(action, input, frame -> action.legalFrom(frame.screen()), 0);
    }

    BearFrameStream.Snapshot<T> current() {
        return current;
    }

    /** Whether the last transition sent device input; an unconfirmed input may have taken effect. */
    boolean lastInputSent() {
        return actions.lastInputSent();
    }

    String lastRefusal() {
        return actions.lastRefusal();
    }

    void phase(Phase next) {
        Objects.requireNonNull(next, "next");
        if (phase != next) {
            diagnostics.accept("phase " + phase + " -> " + next);
            phase = next;
        }
    }

    Phase phase() {
        return phase;
    }

    void terminate(TerminalReason reason) {
        terminalReason = Objects.requireNonNull(reason, "reason");
        phase(Phase.TERMINAL);
        diagnostics.accept("terminal reason=" + reason
                + " lastFrame=" + (current == null ? frames.latestSequence() : current.sequence())
                + " state=" + (current == null ? "UNOBSERVED" : current.screen()));
    }

    TerminalReason terminalReason() {
        return terminalReason;
    }

    private static String format(BearVerifiedActionExecutor.TransitionTrace trace) {
        return "transition action=" + trace.action()
                + " prior=" + trace.priorState()
                + " observed=" + trace.observedState()
                + " frame=" + trace.frameSequence()
                + " ageMs=" + trace.frameAgeMillis()
                + " expected=" + trace.expectedPostcondition()
                + " actual=" + trace.actualNextState()
                + " attempt=" + trace.attempt()
                + " reason=" + trace.retryOrRecoveryReason()
                + " terminal=" + (trace.terminalOutcome() == null ? "RETRY" : trace.terminalOutcome());
    }
}
