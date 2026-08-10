package dev.frostguard.engine.schedule.preempt;

import dev.frostguard.engine.error.TaskPreemptedException;

/**
 * Thread-safe flag that allows the scheduler to signal a running task
 * that it has been preempted.  The task polls this token periodically
 * via {@link #check()}.
 */
public class PreemptionToken {

    private volatile PreemptionRule activeRule;

    /**
     * Arms the token with the triggering rule.  Only the first
     * invocation has any effect — subsequent triggers are silently
     * discarded.
     */
    public synchronized void trigger(PreemptionRule rule) {
        if (activeRule == null) this.activeRule = rule;
    }

    /**
     * Polls the token and throws if preemption has been signalled.
     *
     * @throws TaskPreemptedException when the token is armed
     */
    public void check() {
        PreemptionRule snapshot = activeRule;
        if (snapshot != null) throw new TaskPreemptedException(snapshot.getRuleName());
    }

    /** Non-throwing probe — returns {@code true} when the token is armed. */
    public boolean isTriggered() { return activeRule != null; }

    /** Disarms the token so it can be safely discarded. */
    public synchronized void clear() { activeRule = null; }
}
