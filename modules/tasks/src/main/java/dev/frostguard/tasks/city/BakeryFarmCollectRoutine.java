package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.engine.helper.BuildingRewardDetector;
import dev.frostguard.engine.helper.NavigationHelper.BuildingDestination;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.tasks.diagnostics.TaskControlSignals;
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;
import dev.frostguard.vision.convert.GameTimeUtils;

import java.time.LocalDateTime;

/** Collects the independent Farm reward and the Bakery-grouped building reward. */
public class BakeryFarmCollectRoutine extends DelayedTask {

    static final long MAX_VISIT_DURATION_MS = 2 * 60 * 1000L;
    static final long REWARD_ACTION_SETTLE_MS = 1_000L;
    static final long REWARD_OVERLAY_EXIT_SETTLE_MS = 500L;
    static final int MAX_FAILED_VISITS = 4;
    static final long RETRY_DELAY_MINUTES = 5L;
    static final long RESET_SETTLE_DELAY_MINUTES = 1L;

    private BakeryFarmCollectProgress progress = BakeryFarmCollectProgress.READY;
    private int failedVisitsThisCycle;
    private int confirmedCollectionsThisCycle;
    private LocalDateTime cycleResumeAt;
    private long executionDeadlineMillis;

    public BakeryFarmCollectRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    protected boolean acceptsInjections() {
        return false;
    }

    @Override
    protected void execute() {
        resetCycleAfterScheduledReset(LocalDateTime.now());
        executionDeadlineMillis = System.currentTimeMillis() + MAX_VISIT_DURATION_MS;
        BakeryFarmCollectFlow.VisitResult visit = failedVisit(BakeryFarmCollectFlow.FailureReason.UNEXPECTED);
        RuntimeException controlSignal = null;

        try {
            visit = runVisit();
        } catch (RuntimeException failure) {
            try {
                TaskControlSignals.rethrowControlSignal(failure);
            } catch (RuntimeException signal) {
                controlSignal = signal;
            }
            logWarning("Bakery and Farm visit failed unexpectedly: "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        } finally {
            finishVisit(visit, controlSignal);
        }

        if (controlSignal != null) {
            throw controlSignal;
        }
    }

    BakeryFarmCollectProgress progress() {
        return progress;
    }

    static BakeryFarmCollectProgress completionProgress(int confirmedCollectionsThisCycle) {
        return confirmedCollectionsThisCycle > 0
                ? BakeryFarmCollectProgress.COMPLETED_SUCCESS
                : BakeryFarmCollectProgress.COMPLETED_UNVERIFIED;
    }

    static BakeryFarmCollectProgress failureProgress(int failedVisitsThisCycle,
                                                      int confirmedCollectionsThisCycle) {
        if (failedVisitsThisCycle < MAX_FAILED_VISITS) {
            return BakeryFarmCollectProgress.FAILED_RETRY;
        }
        return confirmedCollectionsThisCycle > 0
                ? BakeryFarmCollectProgress.PARTIAL_RESCHEDULED
                : BakeryFarmCollectProgress.FAILED_RESCHEDULED;
    }

    static LocalDateTime nextRun(BakeryFarmCollectProgress progress, LocalDateTime now,
                                 LocalDateTime dailyReset) {
        return switch (progress) {
            case COMPLETED_SUCCESS, COMPLETED_UNVERIFIED, PARTIAL_RESCHEDULED, FAILED_RESCHEDULED ->
                    dailyReset.plusMinutes(RESET_SETTLE_DELAY_MINUTES);
            case FAILED_RETRY, READY, INACTIVE -> now.plusMinutes(RETRY_DELAY_MINUTES);
        };
    }

    private void resetCycleAfterScheduledReset(LocalDateTime now) {
        if (cycleResumeAt != null && !now.isBefore(cycleResumeAt)) {
            failedVisitsThisCycle = 0;
            confirmedCollectionsThisCycle = 0;
            cycleResumeAt = null;
            progress = BakeryFarmCollectProgress.READY;
        }
    }

    private void finishVisit(BakeryFarmCollectFlow.VisitResult visit, RuntimeException controlSignal) {
        confirmedCollectionsThisCycle += visit.confirmedCollections();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime dailyReset = GameTimeUtils.dailyResetTime();
        LocalDateTime next;
        String snapshot = null;

        if (visit.completedReliably()) {
            failedVisitsThisCycle = 0;
            progress = completionProgress(confirmedCollectionsThisCycle);
            next = nextRun(progress, now, dailyReset);
            cycleResumeAt = next;
        } else {
            failedVisitsThisCycle++;
            progress = failureProgress(failedVisitsThisCycle, confirmedCollectionsThisCycle);
            next = nextRun(progress, now, dailyReset);
            if (progress == BakeryFarmCollectProgress.PARTIAL_RESCHEDULED
                    || progress == BakeryFarmCollectProgress.FAILED_RESCHEDULED) {
                cycleResumeAt = next;
            }
            if (controlSignal == null) {
                snapshot = captureFailureSnapshot(visit.failureReason());
            }
        }

        reschedule(next);
        recordStatistics(visit);
        String result = "Farm confirmed=" + visit.confirmedFarmCollections()
                + ", Bakery confirmed=" + visit.confirmedBakeryCollections()
                + ", unconfirmed actions=" + (visit.hasUnconfirmedAction() ? 1 : 0)
                + ", failures=" + (visit.completedReliably() ? 0 : 1)
                + ", short retries=" + (progress == BakeryFarmCollectProgress.FAILED_RETRY ? 1 : 0);
        String reason = visit.completedReliably() ? "reliable scan cycle completed"
                : "failure=" + visit.failureReason();
        String snapshotSuffix = snapshot == null ? "" : "; " + snapshot;
        logInfo("Bakery and Farm visit " + progress + "; " + reason + "; " + result
                + ". Rescheduled once for " + next.format(DATETIME_FORMATTER) + snapshotSuffix + ".");
    }

    protected BakeryFarmCollectFlow.VisitResult runVisit() {
        return BakeryFarmCollectFlow.execute(new BakeryFarmActions());
    }

    protected String captureFailureSnapshot(BakeryFarmCollectFlow.FailureReason reason) {
        return TaskDiagnosticSnapshots.capture(emuManager, EMULATOR_NUMBER,
                "bakeryfarm", "terminal-" + reason.name().toLowerCase());
    }

    protected void recordStatistics(BakeryFarmCollectFlow.VisitResult visit) {
        StatisticsService statistics = StatisticsService.obtain();
        if (visit.confirmedFarmCollections() > 0) {
            statistics.addToCounter(profile, "Bakery and Farm Farm collections", visit.confirmedFarmCollections());
        }
        if (visit.confirmedBakeryCollections() > 0) {
            statistics.addToCounter(profile, "Bakery and Farm Bakery collections", visit.confirmedBakeryCollections());
        }
        if (visit.hasUnconfirmedAction()) {
            statistics.addToCounter(profile, "Bakery and Farm unconfirmed actions", 1);
        }
        if (!visit.completedReliably()) {
            statistics.addToCounter(profile, "Bakery and Farm failed actions", 1);
        }
        if (progress == BakeryFarmCollectProgress.FAILED_RETRY) {
            statistics.addToCounter(profile, "Bakery and Farm short retries", 1);
        }
    }

    private BakeryFarmCollectFlow.VisitResult failedVisit(BakeryFarmCollectFlow.FailureReason reason) {
        return new BakeryFarmCollectFlow.VisitResult(
                BakeryFarmCollectFlow.TargetResult.failed(reason), null, reason);
    }

    private final class BakeryFarmActions implements BakeryFarmCollectFlow.Actions {

        @Override
        public boolean timedOut() {
            return System.currentTimeMillis() >= executionDeadlineMillis;
        }

        @Override
        public BakeryFarmCollectFlow.TargetResult visit(BuildingDestination destination) {
            if (timedOut()) {
                return BakeryFarmCollectFlow.TargetResult.failed(BakeryFarmCollectFlow.FailureReason.TIMEOUT);
            }
            if (!navigationHelper.navigateToBuilding(destination)) {
                return BakeryFarmCollectFlow.TargetResult.failed(BakeryFarmCollectFlow.FailureReason.NAVIGATION);
            }
            if (timedOut()) {
                return BakeryFarmCollectFlow.TargetResult.failed(BakeryFarmCollectFlow.FailureReason.TIMEOUT);
            }

            ImageSearchResultData reward = BuildingRewardDetector.detect(destination,
                    templateSearchHelper::locatePattern);
            if (reward == null) {
                return BakeryFarmCollectFlow.TargetResult.failed(BakeryFarmCollectFlow.FailureReason.DETECTION);
            }
            if (!reward.isFound()) {
                logInfo(destination.displayName() + " reward icon is absent.");
                return BakeryFarmCollectFlow.TargetResult.absent();
            }
            if (!tapInside(reward)) {
                return BakeryFarmCollectFlow.TargetResult.failed(BakeryFarmCollectFlow.FailureReason.ACTION);
            }

            sleepTask(REWARD_ACTION_SETTLE_MS);
            if (timedOut()) {
                return BakeryFarmCollectFlow.TargetResult.failed(BakeryFarmCollectFlow.FailureReason.TIMEOUT);
            }
            ImageSearchResultData afterClaim = BuildingRewardDetector.detect(destination,
                    templateSearchHelper::locatePattern);
            if (afterClaim == null) {
                return BakeryFarmCollectFlow.TargetResult.failed(BakeryFarmCollectFlow.FailureReason.DETECTION);
            }
            if (afterClaim.isFound()) {
                return BakeryFarmCollectFlow.TargetResult.failed(BakeryFarmCollectFlow.FailureReason.ACTION);
            }

            tapInside(CommonGameAreas.BUILDING_DAILY_REWARD_OVERLAY_EXIT);
            sleepTask(REWARD_OVERLAY_EXIT_SETTLE_MS);
            logInfo(destination.displayName() + " reward icon disappeared after the collection tap.");
            return BakeryFarmCollectFlow.TargetResult.confirmed();
        }
    }
}
