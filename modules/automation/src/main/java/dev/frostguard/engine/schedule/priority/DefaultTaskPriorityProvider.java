package dev.frostguard.engine.schedule.priority;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.engine.schedule.DelayedTask;

import java.util.Map;

/**
 * Standard priority provider that assigns fixed scores to well-known
 * task types and honours user-specified overrides for custom tasks.
 */
public class DefaultTaskPriorityProvider implements TaskPriorityProvider {

    // Lookup table for built-in task priorities (descending urgency)
    private static final Map<TpDailyTaskEnum, Integer> BUILTIN_SCORES = Map.ofEntries(
            Map.entry(TpDailyTaskEnum.INITIALIZE,     1000),
            // matt, 2026-08-08: the timer sweep sits directly below Initialize and above every
            // activity, so the bot has read what is actually due before it does anything at all.
            Map.entry(TpDailyTaskEnum.TIMER_SWEEP,     980),
            Map.entry(TpDailyTaskEnum.SKIP_TUTORIAL,   950),
            Map.entry(TpDailyTaskEnum.BEAR_TRAP,       900),
            Map.entry(TpDailyTaskEnum.ARENA,           800),
            // Troop-slot economy ordering: when several troop-consuming tasks are runnable at once,
            // break ties consistently. Bear Trap (900, above) has a hard deadline and always wins;
            // then Cryptid hosting, Intel, Polar Terror, and finally Gather, which yields its slots
            // to any of them via the TroopSlotPolicy claim ledger.
            Map.entry(TpDailyTaskEnum.EVENT_CRYPTID_HOST, 700),
            Map.entry(TpDailyTaskEnum.INTEL,             600),
            Map.entry(TpDailyTaskEnum.BEAST_HUNTING,     550),
            Map.entry(TpDailyTaskEnum.EVENT_POLAR_TERROR, 500),
            Map.entry(TpDailyTaskEnum.GATHER_RESOURCES,  100)
    );

    @Override
    public int getPriority(DelayedTask task) {
        TpDailyTaskEnum kind = task.getTpTask();

        // Check the static lookup first
        Integer builtinScore = BUILTIN_SCORES.get(kind);
        if (builtinScore != null) return builtinScore;

        // Dummy-task priority comes from the profile config
        if (kind == TpDailyTaskEnum.DUMMY_TASK) {
            Integer cfgPriority = task.getProfile().getConfig(
                    ConfigurationKeyEnum.DUMMY_TASK_PRIORITY_INT, Integer.class);
            return cfgPriority != null ? cfgPriority : 100;
        }

        // User-specified custom-task priority
        Integer custom = task.getCustomPriority();
        return custom != null ? custom : 0;
    }
}
