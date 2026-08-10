package dev.frostguard.engine.schedule;

import dev.frostguard.engine.schedule.preempt.PreemptionRule;
import dev.frostguard.engine.schedule.preempt.PreemptionToken;

// Wraps a running DelayedTask with its associated preemption token,
// providing a clean boundary between task execution and preemption.
public class ExecutionContext {

    private final DelayedTask boundTask;
    private final PreemptionToken cancellationToken;

    public ExecutionContext(DelayedTask task) {
        this.boundTask = task;
        this.cancellationToken = new PreemptionToken();
        task.attachToken(cancellationToken);
    }

    // Triggers preemption using the supplied rule.
    public void preempt(PreemptionRule rule) {
        cancellationToken.trigger(rule);
    }

    // Returns the task bound to this context.
    public DelayedTask getTask() {
        return boundTask;
    }

    // Disarms the preemption token after task completion.
    public void clear() {
        cancellationToken.clear();
    }
}
