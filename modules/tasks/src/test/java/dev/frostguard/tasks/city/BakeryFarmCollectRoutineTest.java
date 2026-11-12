package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BakeryFarmCollectRoutineTest {

    @BeforeAll
    static void createTestWorkspace() throws IOException {
        Files.createDirectories(WorkspacePaths.current().root());
    }

    @Test
    void schedulesReliableResultsAfterTheNextReset() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 12, 10, 0);
        LocalDateTime reset = LocalDateTime.of(2026, 11, 13, 2, 0);

        assertEquals(BakeryFarmCollectProgress.COMPLETED_UNVERIFIED,
                BakeryFarmCollectRoutine.completionProgress(0));
        assertEquals(BakeryFarmCollectProgress.COMPLETED_SUCCESS,
                BakeryFarmCollectRoutine.completionProgress(1));
        assertEquals(reset.plusMinutes(1), BakeryFarmCollectRoutine.nextRun(
                BakeryFarmCollectProgress.COMPLETED_SUCCESS, now, reset));
        assertEquals(reset.plusMinutes(1), BakeryFarmCollectRoutine.nextRun(
                BakeryFarmCollectProgress.COMPLETED_UNVERIFIED, now, reset));
    }

    @Test
    void retriesThreeUnknownVisitsBeforeDeferringTheFourth() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 12, 10, 0);
        LocalDateTime reset = LocalDateTime.of(2026, 11, 13, 2, 0);

        assertEquals(BakeryFarmCollectProgress.FAILED_RETRY,
                BakeryFarmCollectRoutine.failureProgress(1, 0));
        assertEquals(BakeryFarmCollectProgress.FAILED_RETRY,
                BakeryFarmCollectRoutine.failureProgress(3, 0));
        assertEquals(now.plusMinutes(5), BakeryFarmCollectRoutine.nextRun(
                BakeryFarmCollectProgress.FAILED_RETRY, now, reset));
        assertEquals(BakeryFarmCollectProgress.FAILED_RESCHEDULED,
                BakeryFarmCollectRoutine.failureProgress(4, 0));
        assertEquals(reset.plusMinutes(1), BakeryFarmCollectRoutine.nextRun(
                BakeryFarmCollectProgress.FAILED_RESCHEDULED, now, reset));
    }

    @Test
    void preservesPartialProgressWhenTheFourthVisitFails() {
        assertEquals(BakeryFarmCollectProgress.PARTIAL_RESCHEDULED,
                BakeryFarmCollectRoutine.failureProgress(4, 1));
    }

    @Test
    void reliableVisitReschedulesExactlyOnceAfterReset() {
        ScriptedRoutine routine = new ScriptedRoutine(List.of(completeVisit()));
        LocalDateTime reset = dev.frostguard.vision.convert.GameTimeUtils.dailyResetTime();

        routine.execute();

        assertEquals(BakeryFarmCollectProgress.COMPLETED_SUCCESS, routine.progress());
        assertEquals(1, routine.rescheduleCalls);
        assertEquals(reset.plusMinutes(1), routine.rescheduledAt);
    }

    @Test
    void fourthUnknownVisitReschedulesOnceAsFailedForTheDay() {
        BakeryFarmCollectFlow.VisitResult failed = failedVisit();
        ScriptedRoutine routine = new ScriptedRoutine(List.of(failed, failed, failed, failed));

        routine.execute();
        routine.execute();
        routine.execute();
        routine.execute();

        assertEquals(BakeryFarmCollectProgress.FAILED_RESCHEDULED, routine.progress());
        assertEquals(4, routine.rescheduleCalls);
        assertEquals(dev.frostguard.vision.convert.GameTimeUtils.dailyResetTime().plusMinutes(1),
                routine.rescheduledAt);
    }

    private static BakeryFarmCollectFlow.VisitResult completeVisit() {
        return new BakeryFarmCollectFlow.VisitResult(
                BakeryFarmCollectFlow.TargetResult.confirmed(),
                BakeryFarmCollectFlow.TargetResult.absent(),
                BakeryFarmCollectFlow.FailureReason.NONE);
    }

    private static BakeryFarmCollectFlow.VisitResult failedVisit() {
        return new BakeryFarmCollectFlow.VisitResult(
                BakeryFarmCollectFlow.TargetResult.failed(BakeryFarmCollectFlow.FailureReason.NAVIGATION),
                null,
                BakeryFarmCollectFlow.FailureReason.NAVIGATION);
    }

    private static final class ScriptedRoutine extends BakeryFarmCollectRoutine {
        private static final AccountDescriptor PROFILE = new AccountDescriptor(
                999L, "Synthetic Bakery Farm", "review", true, 0L, 0L);

        private final ArrayDeque<BakeryFarmCollectFlow.VisitResult> results;
        private int rescheduleCalls;
        private LocalDateTime rescheduledAt;

        private ScriptedRoutine(List<BakeryFarmCollectFlow.VisitResult> results) {
            super(PROFILE, TpDailyTaskEnum.BAKERY_FARM_COLLECT);
            this.results = new ArrayDeque<>(results);
        }

        @Override
        protected BakeryFarmCollectFlow.VisitResult runVisit() {
            return results.removeFirst();
        }

        @Override
        protected String captureFailureSnapshot(BakeryFarmCollectFlow.FailureReason reason) {
            return "snapshot=synthetic";
        }

        @Override
        protected void recordStatistics(BakeryFarmCollectFlow.VisitResult visit) {
            // Pure scheduling test; production statistics are covered by the visit summary.
        }

        @Override
        public void reschedule(LocalDateTime scheduledTime) {
            rescheduleCalls++;
            rescheduledAt = scheduledTime;
        }
    }
}
