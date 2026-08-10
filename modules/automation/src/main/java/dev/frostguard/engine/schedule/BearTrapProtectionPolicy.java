package dev.frostguard.engine.schedule;

import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_EVENT_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_SCHEDULE_DATETIME_STRING;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_BLOCK_RALLIES_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_ENABLED_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_TIMER_1_PAUSE_ALL_TASKS_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_BLOCK_RALLIES_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_ENABLED_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_PAUSE_ALL_TASKS_BOOL;
import static dev.frostguard.api.configs.ConfigurationKeyEnum.BEAR_TRAP_TIMER_2_SCHEDULE_DATETIME_STRING;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.helper.BearTrapHelper;
import dev.frostguard.engine.helper.TimeWindowHelper;

/**
 * Resolves the two independent Bear Trap protection windows.
 *
 * <p>The protection window starts ten minutes before activation and ends
 * thirty minutes after activation. Each configured anchor repeats every
 * forty-eight hours, matching the Bear Trap event cadence.</p>
 */
public final class BearTrapProtectionPolicy {

    public static final int DEFAULT_LEAD_TIME_MINUTES = 10;
    public static final int ACTIVE_WINDOW_MINUTES = 30;
    public static final int RELEASE_BUFFER_SECONDS = 5;

    private BearTrapProtectionPolicy() {
    }

    public static Decision evaluateTask(AccountDescriptor profile, TpDailyTaskEnum task) {
        return evaluateTask(profile, task, Clock.systemUTC());
    }

    static Decision evaluateTask(AccountDescriptor profile, TpDailyTaskEnum task, Clock clock) {
        if (profile == null || task == null || isProtectionExempt(task)) {
            return Decision.allowed();
        }

        return evaluate(profile, task.canStartRally(), true, clock);
    }

    public static Decision evaluateRallyStart(AccountDescriptor profile) {
        return evaluateRallyStart(profile, Clock.systemUTC());
    }

    static Decision evaluateRallyStart(AccountDescriptor profile, Clock clock) {
        return evaluate(profile, true, false, clock);
    }

    public static boolean isFullPauseActive(AccountDescriptor profile) {
        return evaluate(profile, false, true, Clock.systemUTC()).blocked();
    }

    private static boolean isProtectionExempt(TpDailyTaskEnum task) {
        return task == TpDailyTaskEnum.BEAR_TRAP
                || task == TpDailyTaskEnum.INITIALIZE
                || task == TpDailyTaskEnum.SKIP_TUTORIAL
                || task == TpDailyTaskEnum.CREATE_CHARACTER;
    }

    private static Decision evaluate(
            AccountDescriptor profile,
            boolean rallyStartingAction,
            boolean includeFullPause,
            Clock clock) {
        if (profile == null) {
            return Decision.allowed();
        }

        List<ActiveProtection> blockers = activeProtections(profile, clock).stream()
                .filter(active -> (includeFullPause && active.pauseAllTasks())
                        || (rallyStartingAction && (active.blockRallies() || active.pauseAllTasks())))
                .toList();
        if (blockers.isEmpty()) {
            return Decision.allowed();
        }

        Instant releaseAt = blockers.stream()
                .map(ActiveProtection::releaseAt)
                .max(Instant::compareTo)
                .orElseThrow()
                .plusSeconds(RELEASE_BUFFER_SECONDS);
        boolean fullPause = includeFullPause && blockers.stream().anyMatch(ActiveProtection::pauseAllTasks);
        String traps = blockers.stream()
                .map(ActiveProtection::source)
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");

        return new Decision(true, fullPause ? BlockReason.ALL_TASKS : BlockReason.RALLY_START, traps, releaseAt);
    }

    private static List<ActiveProtection> activeProtections(AccountDescriptor profile, Clock clock) {
        List<ActiveProtection> result = new ArrayList<>(2);
        BearTrapVisualProtection.releaseAt(profile.getId(), clock)
                .ifPresent(releaseAt -> result.add(new ActiveProtection("icon", true, false, releaseAt)));
        addIfActive(result, profile, clock, new TrapSettings(
                1,
                BEAR_TRAP_TIMER_1_ENABLED_BOOL,
                BEAR_TRAP_SCHEDULE_DATETIME_STRING,
                BEAR_TRAP_TIMER_1_BLOCK_RALLIES_BOOL,
                BEAR_TRAP_TIMER_1_PAUSE_ALL_TASKS_BOOL));
        addIfActive(result, profile, clock, new TrapSettings(
                2,
                BEAR_TRAP_TIMER_2_ENABLED_BOOL,
                BEAR_TRAP_TIMER_2_SCHEDULE_DATETIME_STRING,
                BEAR_TRAP_TIMER_2_BLOCK_RALLIES_BOOL,
                BEAR_TRAP_TIMER_2_PAUSE_ALL_TASKS_BOOL));
        return result;
    }

    private static void addIfActive(
            List<ActiveProtection> result,
            AccountDescriptor profile,
            Clock clock,
            TrapSettings settings) {
        if (!isTimerEnabled(profile, settings)) {
            return;
        }

        LocalDateTime anchor = profile.getConfig(settings.scheduleKey(), LocalDateTime.class);
        if (anchor == null) {
            return;
        }

        BearTrapHelper.WindowResult window = BearTrapHelper.calculateWindow(
                anchor.toInstant(ZoneOffset.UTC),
                protectionLeadTimeMinutes(profile),
                ACTIVE_WINDOW_MINUTES,
                2,
                clock);
        if (window.getState() != TimeWindowHelper.WindowState.INSIDE) {
            return;
        }

        result.add(new ActiveProtection(
                Integer.toString(settings.trapNumber()),
                Boolean.TRUE.equals(profile.getConfig(settings.blockRalliesKey(), Boolean.class)),
                Boolean.TRUE.equals(profile.getConfig(settings.pauseAllTasksKey(), Boolean.class)),
                window.getCurrentWindowEnd()));
    }

    private static int protectionLeadTimeMinutes(AccountDescriptor profile) {
        Integer configured = profile.getConfig(
                ConfigurationKeyEnum.BEAR_TRAP_PREPARATION_TIME_INT,
                Integer.class);
        return configured != null && configured >= 0 ? configured : DEFAULT_LEAD_TIME_MINUTES;
    }

    private static boolean isTimerEnabled(AccountDescriptor profile, TrapSettings settings) {
        boolean explicitlyConfigured = profile.getEntries().stream()
                .anyMatch(entry -> settings.enabledKey().equals(entry.getSettingKey()));
        if (explicitlyConfigured) {
            return Boolean.TRUE.equals(profile.getConfig(settings.enabledKey(), Boolean.class));
        }

        // Before the independent timer switches existed, Bear automation itself
        // activated the single configured window. Preserve that behavior once
        // for timer 1; the UI writes the new switch on the next profile load.
        return settings.trapNumber() == 1
                && Boolean.TRUE.equals(profile.getConfig(BEAR_TRAP_EVENT_BOOL, Boolean.class));
    }

    public enum BlockReason {
        RALLY_START,
        ALL_TASKS
    }

    public record Decision(boolean blocked, BlockReason reason, String trapNumbers, Instant releaseAt) {
        static Decision allowed() {
            return new Decision(false, null, "", null);
        }
    }

    private record TrapSettings(
            int trapNumber,
            ConfigurationKeyEnum enabledKey,
            ConfigurationKeyEnum scheduleKey,
            ConfigurationKeyEnum blockRalliesKey,
            ConfigurationKeyEnum pauseAllTasksKey) {
    }

    private record ActiveProtection(
            String source,
            boolean blockRallies,
            boolean pauseAllTasks,
            Instant releaseAt) {
    }
}
