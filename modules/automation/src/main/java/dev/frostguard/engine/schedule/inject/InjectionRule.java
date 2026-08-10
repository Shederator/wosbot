package dev.frostguard.engine.schedule.inject;

import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.schedule.DelayedTask;

/**
 * Contract for lightweight, cooperative actions that can be slotted
 * into a running task's idle intervals.  The {@code GlobalMonitorService}
 * evaluates these rules periodically and queues qualifying ones for
 * execution during the next {@code sleepTask()} window.
 */
public interface InjectionRule {

    /**
     * Evaluates whether the injection condition holds, using a
     * pre-captured screenshot shared across all rules in the current
     * monitoring cycle.
     *
     * @param controller the emulator controller (template matching only,
     *                   no fresh capture)
     * @param profile    the monitored profile
     * @param frame      shared screenshot from the current monitoring cycle
     * @return {@code true} to queue the injection for execution
     */
    boolean shouldInject(EmulatorController controller, AccountDescriptor profile, RawImageData frame);

    /**
     * Performs the injection action on the main worker thread during a
     * task's sleep cycle.
     *
     * @param controller  the emulator controller for device interaction
     * @param profile     the active profile
     * @param activeTask  the task currently occupying the sleep window
     */
    void executeInjection(EmulatorController controller, AccountDescriptor profile, DelayedTask activeTask);

    /** Returns a descriptive label for logging and diagnostics. */
    String getRuleName();
}
