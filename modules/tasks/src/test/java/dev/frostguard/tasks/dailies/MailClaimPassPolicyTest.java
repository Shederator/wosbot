package dev.frostguard.tasks.dailies;

import dev.frostguard.api.domain.PointData;
import java.util.List;
import org.junit.jupiter.api.Test;

import static dev.frostguard.tasks.dailies.MailClaimPassPolicy.Decision;
import static dev.frostguard.tasks.dailies.MailClaimPassPolicy.Evidence;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MailClaimPassPolicyTest {

    @Test
    void stopsAfterPersistentUnreadReportMakesNoProgress() {
        Evidence before = evidence(point(685, 250));
        Evidence after = evidence(point(686, 248));

        assertEquals(Decision.STOP_NO_PROGRESS, MailClaimPassPolicy.decide(before, after, 1, 3));
    }

    @Test
    void completesWhenUnreadIndicatorsDisappear() {
        Evidence before = evidence(point(685, 250), point(685, 395));

        assertEquals(Decision.COMPLETE, MailClaimPassPolicy.decide(before, evidence(), 1, 3));
    }

    @Test
    void completesWhenNoUnreadIndicatorsWerePresent() {
        assertEquals(Decision.COMPLETE, MailClaimPassPolicy.decide(evidence(), evidence(), 1, 3));
    }

    @Test
    void retriesWhenClaimingChangesVisibleUnreadEvidence() {
        Evidence before = evidence(point(685, 250), point(685, 395));
        Evidence after = evidence(point(685, 395));

        assertEquals(Decision.RETRY_AFTER_PROGRESS, MailClaimPassPolicy.decide(before, after, 1, 3));
    }

    @Test
    void stopsAtHardPassBudgetEvenWhileEvidenceChanges() {
        Evidence before = evidence(point(685, 250), point(685, 395));
        Evidence after = evidence(point(685, 395));

        assertEquals(Decision.STOP_BUDGET_EXHAUSTED, MailClaimPassPolicy.decide(before, after, 3, 3));
    }

    @Test
    void stopsWhenUnreadEvidenceAppearsWithoutPriorClaimEvidence() {
        assertEquals(
                Decision.STOP_NO_PROGRESS,
                MailClaimPassPolicy.decide(evidence(), evidence(point(685, 250)), 1, 3));
    }

    private static Evidence evidence(PointData... points) {
        return new Evidence(List.of(points));
    }

    private static PointData point(int x, int y) {
        return new PointData(x, y);
    }
}
