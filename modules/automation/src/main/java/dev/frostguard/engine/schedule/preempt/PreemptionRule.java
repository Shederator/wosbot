package dev.frostguard.engine.schedule.preempt;

import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.RawImageData;

/**
 * Defines a condition that, when detected, should interrupt the
 * currently executing task and replace it with a higher-priority one.
 * Rules are evaluated periodically by {@code GlobalMonitorService}
 * using a shared screenshot.
 */
public interface PreemptionRule {

    /**
     * Tests whether the preemption trigger is present on screen.
     *
     * @param controller the emulator controller (pattern matching, no
     *                   fresh capture)
     * @param profile    the monitored profile
     * @param frame      pre-captured screenshot
     * @return {@code true} to trigger preemption
     */
    boolean shouldPreempt(EmulatorController controller, AccountDescriptor profile, RawImageData frame);

    /** The task type that should be enqueued when this rule fires. */
    TpDailyTaskEnum getTaskToExecute();

    /** Descriptive name for logging purposes. */
    String getRuleName();
}
