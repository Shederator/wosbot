package dev.frostguard.tasks.lifecycle;

import dev.frostguard.engine.schedule.LaunchPoint;


import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.DelayedTask;

/**
 * A lightweight task context specifically created for running pending
 * Injections
 * when the TaskQueue is idle. This task does not execute game logic itself;
 * instead, it acts as a host environment to provide logging, preemption checks,
 * and context for InjectionRules.
 */
public class InjectionRoutine extends DelayedTask {

    public InjectionRoutine(AccountDescriptor profile) {
        // We use INITIALIZE simply to satisfy the superclass constructor.
        // This task is never passed to TaskQueue#executeTask() so it won't affect DB
        // state.
        super(profile, TpDailyTaskEnum.INITIALIZE);
        this.taskName = "Idle Injection";
    }

    @Override
    protected void execute() {
        // Intentionally empty. This task is passed directly to the injection rule,
        // and its execute() method is never called by the TaskQueue.
    }
}
