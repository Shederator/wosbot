package dev.frostguard.engine.listener.task.impl;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.TemplateSearchHelper;
import dev.frostguard.engine.schedule.CustomTaskConfigurable;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.CustomTaskService;

/**
 * Custom shield automation that runs once at a fixed UTC start time and then
 * schedules exactly one follow-up execution.
 */
public class shield extends DelayedTask implements CustomTaskConfigurable {
    private static final LocalDateTime DEFAULT_FIRST_EXECUTION_UTC = LocalDateTime.of(2026, 6, 20, 9, 0);
    private static final Duration DEFAULT_FOLLOW_UP_DELAY = Duration.ofHours(8);
    private static final DateTimeFormatter UTC_INPUT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private boolean followUpPending = false;
    private LocalDateTime firstExecutionUtc = DEFAULT_FIRST_EXECUTION_UTC;
    private Duration followUpDelay = DEFAULT_FOLLOW_UP_DELAY;

    public shield(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
        initializeSchedule();
    }

    @Override
    protected Object getDistinctKey() {
        return "shield";
    }

    @Override
    public void applyCustomTaskSettings(CustomTaskService.CustomTaskSettings settings) {
        if (settings == null) {
            return;
        }
        if (settings.getFirstExecutionUtc() != null && !settings.getFirstExecutionUtc().isBlank()) {
            firstExecutionUtc = LocalDateTime.parse(settings.getFirstExecutionUtc(), UTC_INPUT_FORMATTER);
        } else {
            firstExecutionUtc = DEFAULT_FIRST_EXECUTION_UTC;
        }
        Integer followUpDelayHours = settings.getFollowUpDelayHours();
        followUpDelay = followUpDelayHours != null && followUpDelayHours > 0
                ? Duration.ofHours(followUpDelayHours)
                : DEFAULT_FOLLOW_UP_DELAY;
        if (!followUpPending) {
            initializeSchedule();
        }
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    @Override
    public void run() {
        if (!isExecutionDue()) {
            LocalDateTime nextExecution = getNextPlannedExecution();
            if (nextExecution != null) {
                reschedule(nextExecution);
                setRecurring(true);
                logInfo("Shield task waiting until " + nextExecution.format(DATETIME_FORMATTER) + " UTC.");
            } else {
                setRecurring(false);
                logInfo("Shield task window already completed.");
            }
            return;
        }
        super.run();
    }

    @Override
    public void setLastExecutionTime(LocalDateTime lastExecutionTime) {
        super.setLastExecutionTime(lastExecutionTime);
        if (lastExecutionTime != null && getScheduled() != null && lastExecutionTime.isBefore(getScheduled())) {
            followUpPending = true;
        }
    }

    @Override
    protected void execute() {
        logInfo("Starting task: 'shield'");

        ImageSearchResultData cityHit = templateSearchHelper.locatePattern(
                TemplatesEnum.SHIELD_MY_CITY,
                TemplateSearchHelper.SearchConfig.builder()
                        .withCoordinates(new PointData(280, 600), new PointData(420, 650))
                        .withThreshold(80)
                        .build());
        if (cityHit == null || !cityHit.isFound()) {
            logInfo("Shield city anchor not found; stopping.");
            return;
        }

        tapNear(cityHit.getPoint());
        tapInside(new PointData(380, 1035), new PointData(540, 1070));
        sleepTask(200L);
        tapInside(new PointData(150, 200), new PointData(620, 300));
        sleepTask(200L);
        tapInside(new PointData(515, 400), new PointData(650, 440));

        if (!followUpPending) {
            LocalDateTime followUpExecution = nowUtc().plus(followUpDelay);
            followUpPending = true;
            setRecurring(true);
            reschedule(followUpExecution);
            logInfo("First shield execution complete. Follow-up scheduled for "
                    + followUpExecution.format(DATETIME_FORMATTER) + " UTC.");
        } else {
            followUpPending = false;
            setRecurring(false);
            clearSchedule();
            CustomTaskService.getInstance().disableTask(getDistinctKey().toString());
            logInfo("Second shield execution complete. Disabling recurrence.");
        }
        logInfo("Generated task complete.");
    }

    private void initializeSchedule() {
        LocalDateTime nowUtc = nowUtc();
        if (nowUtc.isBefore(firstExecutionUtc)) {
            reschedule(firstExecutionUtc);
        } else {
            reschedule(nowUtc);
        }
    }

    private boolean isExecutionDue() {
        LocalDateTime nextExecution = getNextPlannedExecution();
        return nextExecution != null && !nowUtc().isBefore(nextExecution);
    }

    private LocalDateTime getNextPlannedExecution() {
        if (!followUpPending) {
            LocalDateTime scheduled = getScheduled();
            if (scheduled != null && scheduled.isAfter(firstExecutionUtc)) {
                return scheduled;
            }
            return firstExecutionUtc;
        }
        return getScheduled();
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
