package dev.frostguard.tasks.alliance;

import dev.frostguard.api.configs.TpDailyTaskEnum;

import java.util.List;
import java.util.Optional;
import java.util.Set;

final class AutojoinActivationPolicy {

    static final int LOOKAHEAD_TASK_COUNT = 5;

    private static final Set<TpDailyTaskEnum> AUTOJOIN_RESET_TASKS = Set.of(
            TpDailyTaskEnum.INTEL,
            TpDailyTaskEnum.BEAR_TRAP);

    private AutojoinActivationPolicy() {
    }

    static Optional<TpDailyTaskEnum> findUpcomingResetTask(List<TpDailyTaskEnum> queuedTasks) {
        return queuedTasks.stream()
                .limit(LOOKAHEAD_TASK_COUNT)
                .filter(AUTOJOIN_RESET_TASKS::contains)
                .findFirst();
    }
}
