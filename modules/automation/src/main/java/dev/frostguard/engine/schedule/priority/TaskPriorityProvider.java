package dev.frostguard.engine.schedule.priority;

import dev.frostguard.engine.schedule.DelayedTask;

import java.util.Comparator;

/**
 * Strategy that assigns a numeric urgency score to a queued
 * task. Higher values indicate the task should execute sooner.
 * The scheduler uses this score to decide preemption outcomes
 * and queue ordering.
 */
@FunctionalInterface
public interface TaskPriorityProvider {

    /**
     * Computes the urgency score for the given task.
     *
     * @param task the candidate to evaluate
     * @return a non-negative score; higher means more urgent
     */
    int getPriority(DelayedTask task);

    /**
     * Derives a comparator from this provider that sorts tasks
     * in descending urgency order (most urgent first).
     */
    default Comparator<DelayedTask> descending() {
        return (a, b) -> Integer.compare(getPriority(b), getPriority(a));
    }
}
