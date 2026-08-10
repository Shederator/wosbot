package dev.frostguard.engine.error;

import dev.frostguard.engine.schedule.preempt.PreemptionRule;

/**
 * Signals that a running task must yield immediately because
 * a higher-priority rule has been triggered by the global
 * monitor. Catch sites should clean up and let the scheduler
 * dispatch the replacement task.
 */
public class TaskPreemptedException extends RuntimeException {

    private final String justification;
    private final PreemptionRule trigger;

    private TaskPreemptedException(String justification, PreemptionRule trigger) {
        super(formatMessage(justification, trigger));
        this.justification = justification;
        this.trigger       = trigger;
    }

    /** Creates an exception with only a textual reason. */
    public static TaskPreemptedException because(String reason) {
        return new TaskPreemptedException(reason, null);
    }

    /** Creates an exception tied to a specific preemption rule. */
    public static TaskPreemptedException fromRule(String reason, PreemptionRule rule) {
        return new TaskPreemptedException(reason, rule);
    }

    /** Backwards-compatible constructor for existing call sites. */
    public TaskPreemptedException(String reasoning) {
        this(reasoning, null);
    }

    public String getReasoning()      { return justification; }
    public PreemptionRule getTrigger() { return trigger; }

    @Override
    public String toString() {
        return trigger != null
                ? "TaskPreempted[" + trigger.getClass().getSimpleName() + ": " + justification + "]"
                : "TaskPreempted[" + justification + "]";
    }

    private static String formatMessage(String reason, PreemptionRule rule) {
        return rule != null
                ? "Preempted by " + rule.getClass().getSimpleName() + ": " + reason
                : "Task preempted: " + reason;
    }
}
