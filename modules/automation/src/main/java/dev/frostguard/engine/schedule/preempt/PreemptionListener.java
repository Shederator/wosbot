package dev.frostguard.engine.schedule.preempt;

import dev.frostguard.api.domain.AccountDescriptor;

import java.util.Objects;
import java.util.function.BiPredicate;

/**
 * Observer that fires when a preemption rule activates for a
 * monitored profile, allowing the scheduler to interrupt the
 * running task and substitute a higher-priority one.
 *
 * <p>Implementations may optionally narrow which rules they
 * care about via {@link #monitors(PreemptionRule)}.
 */
public interface PreemptionListener {

    /**
     * Invoked when a preemption rule has been triggered.
     *
     * @param profile the account whose current task is being displaced
     * @param rule    the rule that fired
     */
    void onPreemption(AccountDescriptor profile, PreemptionRule rule);

    /**
     * Opt-in filter. Return {@code false} to suppress notifications
     * about rules this listener does not handle. The scheduler calls
     * this before {@link #onPreemption}.
     */
    default boolean monitors(PreemptionRule rule) {
        return true;
    }

    /**
     * Builds a listener from a lambda or method reference, accepting
     * all rules by default.
     */
    static PreemptionListener create(
            java.util.function.BiConsumer<AccountDescriptor, PreemptionRule> handler) {
        Objects.requireNonNull(handler, "handler");
        return handler::accept;
    }

    /**
     * Returns a filtered view that only forwards events matching
     * the predicate. Both the profile and rule are available for
     * the decision.
     */
    default PreemptionListener filtered(
            BiPredicate<AccountDescriptor, PreemptionRule> predicate) {
        PreemptionListener upstream = this;
        return new PreemptionListener() {
            @Override
            public void onPreemption(AccountDescriptor profile, PreemptionRule rule) {
                if (predicate.test(profile, rule)) {
                    upstream.onPreemption(profile, rule);
                }
            }

            @Override
            public boolean monitors(PreemptionRule rule) {
                return upstream.monitors(rule);
            }
        };
    }
}
