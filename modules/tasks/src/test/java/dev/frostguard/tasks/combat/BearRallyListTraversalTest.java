package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.AreaData;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BearRallyListTraversalTest {

    @Test
    void armingLiveJoinDoesNotConsumeItsOwnCandidateButRestartStillDoes() {
        var traversal = new BearRallyListTraversal();
        var first = row(300, 11, "A");
        var second = row(600, 22, "B");
        var rows = List.of(first, second);
        traversal.checkpointWritten("JOIN_ARMED", first);
        assertEquals(first, traversal.authorizeCandidate(first, rows).orElseThrow());
        traversal.checkpointWritten("NONE", null); // Dispatch refused; retain prior frontier.
        assertEquals(first, traversal.currentTopmostJoinable(rows).orElseThrow());
        var restarted = new BearRallyListTraversal();
        restarted.restoreCompleted(first); // An armed transaction after a crash is ambiguous.
        assertEquals(second, restarted.currentTopmostJoinable(rows).orElseThrow());
        traversal.checkpointWritten("ROW_COMPLETED", first);
        assertEquals(second, traversal.currentTopmostJoinable(rows).orElseThrow());
    }

    private static final Instant START = Instant.parse("2026-10-01T16:00:00Z");

    @Test
    void advancesStrictlyBelowCompletedRowEvenWhenRowRemains() {
        BearRallyListTraversal traversal = new BearRallyListTraversal();
        var first = row(300, 11, "A");
        var second = row(600, 22, "B");

        assertEquals(first, traversal.decide(observation(0, false, first, second)).row());
        traversal.completed(first);
        assertEquals(second, traversal.decide(observation(1, false, first, second)).row());
    }

    @Test
    void usesIdentityToContinueBelowMovedCompletedRow() {
        BearRallyListTraversal traversal = new BearRallyListTraversal();
        var first = row(300, 11, "A");
        traversal.completed(first);
        var movedFirst = row(450, 11, "A");
        var next = row(700, 22, "B");

        assertEquals(next, traversal.decide(observation(1, false, movedFirst, next)).row());
    }

    @Test
    void departedFrontierWithCollapsedSuccessorRequiresANewTopPass() {
        BearRallyListTraversal traversal = new BearRallyListTraversal();
        var departed = row(600, 11, "A");
        traversal.completed(departed);
        var collapsedSuccessor = row(300, 22, "B");

        assertEquals(BearRallyListTraversal.Kind.REOPEN_FROM_TOP,
                traversal.decide(observation(1, false, collapsedSuccessor)).kind());
        assertEquals(collapsedSuccessor,
                traversal.decide(observation(2, false, collapsedSuccessor)).row());
    }

    @Test
    void finalAuthorizationUsesCurrentTopmostRowRatherThanPreviouslyObservedIdentity() {
        BearRallyListTraversal traversal = new BearRallyListTraversal();
        var originallyTop = row(300, 11, "A");
        assertEquals(originallyTop, traversal.decide(
                observation(0, false, originallyTop, row(600, 22, "B"))).row());

        var newTop = row(250, 33, "C");
        var movedOriginal = row(700, 11, "A");

        assertEquals(newTop, traversal.currentTopmostJoinable(
                List.of(newTop, movedOriginal)).orElseThrow());
    }

    @Test
    void waitsThreeSecondsBeforeRequestingVerifiedScroll() {
        BearRallyListTraversal traversal = new BearRallyListTraversal();
        var only = row(500, 11, "A");
        traversal.completed(only);

        assertEquals(BearRallyListTraversal.Kind.WAIT_FOR_VISIBLE_PLUS,
                traversal.decide(observation(0, false, only)).kind());
        assertEquals(BearRallyListTraversal.Kind.WAIT_FOR_VISIBLE_PLUS,
                traversal.decide(observation(2, false, only)).kind());
        assertEquals(BearRallyListTraversal.Kind.SCROLL,
                traversal.decide(observation(3, false, only)).kind());
    }

    @Test
    void scrollsPastAViewportWithRowsButNoGreenPlus() {
        BearRallyListTraversal traversal = new BearRallyListTraversal();
        var greyFirst = row(300, 11, "A", false);
        var greyLast = row(700, 22, "B", false);

        assertEquals(BearRallyListTraversal.Kind.WAIT_FOR_VISIBLE_PLUS,
                traversal.decide(observation(0, false, greyFirst, greyLast)).kind());
        assertEquals(BearRallyListTraversal.Kind.SCROLL,
                traversal.decide(observation(3, false, greyFirst, greyLast)).kind());
    }

    @Test
    void requiresCropAndLeaderToProveScrollOverlap() {
        BearRallyListTraversal traversal = new BearRallyListTraversal();
        var anchor = row(800, 11, "A");
        traversal.completed(anchor);
        traversal.decide(observation(0, false, anchor));
        traversal.decide(observation(3, false, anchor));

        assertEquals(BearRallyListTraversal.Kind.OVERLAP_NOT_PROVEN,
                traversal.decide(observation(4, false, row(300, 11, "different"))).kind());
    }

    @Test
    void verifiedOverlapContinuesBelowAnchor() {
        BearRallyListTraversal traversal = new BearRallyListTraversal();
        var anchor = row(800, 11, "A");
        traversal.completed(anchor);
        traversal.decide(observation(0, false, anchor));
        traversal.decide(observation(3, false, anchor));
        var movedAnchor = row(300, 11, "A");
        var next = row(650, 22, "B");

        assertEquals(next, traversal.decide(observation(4, false, movedAnchor, next)).row());
    }

    @Test
    void bottomRequiresWarScreenReopenBeforeNewTopPass() {
        BearRallyListTraversal traversal = new BearRallyListTraversal();
        var last = row(800, 11, "A");
        traversal.completed(last);
        traversal.decide(observation(0, true, last));
        assertEquals(BearRallyListTraversal.Kind.REOPEN_FROM_TOP,
                traversal.decide(observation(3, true, last)).kind());
        traversal.reopenedAtTop();
        assertEquals(last, traversal.decide(observation(4, false, last)).row());
    }

    @Test
    void absoluteBottomRequiresBothImmediateAndSettledFramesToRemainUnchanged() {
        var row = row(600, 11, "A").control();
        var moved = row(300, 11, "A").control();

        assertEquals(true, BearRallyListTraversal.stableAbsoluteBottom(
                List.of(row), List.of(row), List.of(row)));
        assertEquals(false, BearRallyListTraversal.stableAbsoluteBottom(
                List.of(row), List.of(row), List.of(moved)),
                "an immediate pre-animation frame must not prove bottom");
    }

    private static BearRallyListTraversal.Row row(int y, long hash, String leader) {
        return row(y, hash, leader, true);
    }

    @Test
    void duplicateCompletedFrontierCannotAuthorizeTheUniqueRowBetweenCopies() {
        var traversal = new BearRallyListTraversal();
        var anchor = row(200, 11, "A");
        var candidate = row(500, 22, "B");
        var duplicate = row(800, 11, "A");
        traversal.completed(anchor);
        assertEquals(java.util.Optional.empty(), traversal.authorizeCandidate(candidate,
                List.of(anchor, candidate, duplicate)));
        assertEquals(BearRallyListTraversal.Kind.OVERLAP_NOT_PROVEN,
                traversal.decide(observation(4, false, anchor, candidate, duplicate)).kind());
    }

    @Test
    void duplicateScrollAnchorCannotProveOverlapOrAbsoluteBottom() {
        var traversal = new BearRallyListTraversal();
        var anchor = row(200, 11, "A");
        var duplicate = row(800, 11, "A");
        traversal.scrollAuthorized(anchor);
        assertEquals(BearRallyListTraversal.Kind.OVERLAP_NOT_PROVEN,
                traversal.decide(observation(4, false, anchor, duplicate)).kind());
        var rows = List.of(anchor.control(), duplicate.control());
        assertEquals(false, BearRallyListTraversal.stableAbsoluteBottom(rows, rows, rows));
    }

    @Test
    void freshAuthorizationRejectsDuplicateNamesAndChangedTopmostOrder() {
        var traversal = new BearRallyListTraversal();
        var candidate = row(300, 11, "A");
        assertEquals(java.util.Optional.empty(), traversal.authorizeCandidate(candidate,
                List.of(candidate, row(700, 11, "A"))));
        assertEquals(java.util.Optional.empty(), traversal.authorizeCandidate(candidate,
                List.of(row(200, 22, "B"), candidate)));
        var moved = row(320, 11, "A");
        assertEquals(java.util.Optional.of(moved), traversal.authorizeCandidate(candidate,
                List.of(moved, row(700, 22, "B"))));
        assertEquals(java.util.Optional.empty(), traversal.authorizeCandidate(candidate,
                List.of(row(300, 11, "", true))));
    }

    private static BearRallyListTraversal.Row row(
            int y, long hash, String leader, boolean joinable) {
        return new BearRallyListTraversal.Row(new BearRallyScanner.RallyRow(
                joinable ? AreaData.of(600, y, 680, y + 40) : null,
                y,
                hash,
                leader));
    }

    private static BearRallyListTraversal.Observation observation(
            long seconds, boolean bottom, BearRallyListTraversal.Row... rows) {
        return new BearRallyListTraversal.Observation(
                START.plusSeconds(seconds), List.of(rows), bottom);
    }
}
