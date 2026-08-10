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
    private static final Map<TpDailyTaskEnum, Integer> BUILTIN_SCORES = Map.of(
            TpDailyTaskEnum.INITIALIZE,     1000,
            TpDailyTaskEnum.SKIP_TUTORIAL,   950,
            TpDailyTaskEnum.BEAR_TRAP,       900,
            TpDailyTaskEnum.ARENA,           800
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
