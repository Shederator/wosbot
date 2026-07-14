package dev.frostguard.engine.schedule;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.service.TaskManagementService;

public final class GatherQueuePolicy {

    // Changed by pernerch | Date: 2026-07-02 | Why: only treat future high-priority tasks as blocking gather when they are actually imminent.
    private static final int PENDING_TASK_LOOKAHEAD_MINUTES = 1;
    // Changed by pernerch | Date: 2026-07-02 | Why: prioritize Bear Trap/Intel so gather can defer when needed.
    private static final Set<TpDailyTaskEnum> HIGH_PRIORITY_MARCH_TASKS = Set.of(
            TpDailyTaskEnum.BEAR_TRAP,
            TpDailyTaskEnum.INTEL
    );

    private GatherQueuePolicy() {
    }

    public static int resolveActiveQueueLimit(int configuredLimit) {
        if (configuredLimit <= 0) {
            return 1;
        }
        return configuredLimit;
    }

    public static boolean allowMarchDeployment(Collection<String> activeMarches, String resourceName) {
        if (activeMarches == null || resourceName == null || resourceName.isBlank()) {
            return true;
        }
        // Changed by pernerch | Date: 2026-07-02 | Why: block duplicate resource marches to avoid unnecessary recalls/wait.
        return activeMarches.stream().noneMatch(existing -> resourceName.equalsIgnoreCase(existing));
    }

    public static boolean shouldDeferGatherForPendingTasks(Collection<TpDailyTaskEnum> pendingTasks) {
        if (pendingTasks == null || pendingTasks.isEmpty()) {
            return false;
        }
        return pendingTasks.stream()
                .filter(task -> task != null)
                .anyMatch(HIGH_PRIORITY_MARCH_TASKS::contains);
    }

    public static boolean shouldRecallDuplicateGatherMarches(Collection<TpDailyTaskEnum> pendingTasks) {
        return shouldDeferGatherForPendingTasks(pendingTasks);
    }

    public static boolean hasPendingHigherPriorityMarchTask(AccountDescriptor profile) {
        return !getPendingHigherPriorityMarchTasks(profile).isEmpty();
    }

    public static List<TpDailyTaskEnum> getPendingHigherPriorityMarchTasks(AccountDescriptor profile) {
        if (profile == null || profile.getId() == null) {
            return List.of();
        }
        List<TpDailyTaskEnum> pendingTasks = Arrays.stream(TpDailyTaskEnum.values())
                .filter(HIGH_PRIORITY_MARCH_TASKS::contains)
                .filter(task -> isTaskPending(profile, task))
                .collect(Collectors.toList());
        return pendingTasks;
    }

    private static boolean isTaskPending(AccountDescriptor profile, TpDailyTaskEnum task) {
        if (profile == null || task == null) {
            return false;
        }
        var state = TaskManagementService.shared().lookupTaskState(profile.getId(), task.getId());
        if (state == null) {
            return false;
        }
        if (state.isExecuting()) {
            return true;
        }
        if (!state.isScheduled()) {
            return false;
        }

        LocalDateTime nextExecutionTime = state.getNextExecutionTime();
        if (nextExecutionTime == null) {
            return true;
        }

        return !nextExecutionTime.isAfter(LocalDateTime.now().plusMinutes(PENDING_TASK_LOOKAHEAD_MINUTES));
    }
}
