package dev.frostguard.tasks.exploration;

import dev.frostguard.tasks.exploration.LabyrinthChallengeDecisionPolicy.TroopType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the pure "tail of the tape" decision logic extracted from LabyrinthRaidRoutine's
 * live-verified Research Center / Gear Forge Challenge flow -- see that class and
 * LabyrinthChallengeDecisionPolicy's header for the live-tested lessons this encodes.
 */
class LabyrinthChallengeDecisionPolicyTest {

    @Test
    void presetForStaysAtAModerateSixtyPercentLeanForEveryType() {
        assertArrayEquals(new int[] { 60, 20, 20 }, LabyrinthChallengeDecisionPolicy.presetFor(TroopType.INFANTRY));
        assertArrayEquals(new int[] { 20, 60, 20 }, LabyrinthChallengeDecisionPolicy.presetFor(TroopType.LANCER));
        assertArrayEquals(new int[] { 20, 20, 60 }, LabyrinthChallengeDecisionPolicy.presetFor(TroopType.MARKSMAN));
    }

    @Test
    void leanOfIdentifiesTheHighestPercentage() {
        assertEquals(TroopType.INFANTRY, LabyrinthChallengeDecisionPolicy.leanOf(new int[] { 60, 20, 20 }));
        assertEquals(TroopType.LANCER, LabyrinthChallengeDecisionPolicy.leanOf(new int[] { 20, 60, 20 }));
        assertEquals(TroopType.MARKSMAN, LabyrinthChallengeDecisionPolicy.leanOf(new int[] { 20, 20, 60 }));
    }

    @Test
    void leanOfBreaksTiesTowardInfantryThenLancer() {
        // Tie-break order matches the original live-verified implementation: Infantry wins ties
        // with anything, Lancer wins ties with Marksman.
        assertEquals(TroopType.INFANTRY, LabyrinthChallengeDecisionPolicy.leanOf(new int[] { 40, 40, 20 }));
        assertEquals(TroopType.LANCER, LabyrinthChallengeDecisionPolicy.leanOf(new int[] { 20, 40, 40 }));
    }

    @Test
    void orderedLeanCandidatesTriesThePrimaryFirstThenTheOtherTwo() {
        TroopType[] order = LabyrinthChallengeDecisionPolicy.orderedLeanCandidates(TroopType.INFANTRY);

        assertEquals(3, order.length);
        assertEquals(TroopType.INFANTRY, order[0]);
        // The remaining two, in some order, must be exactly Lancer and Marksman -- never a repeat
        // of Infantry (the live-tested lesson: never a more extreme version of the same lean).
        assertTrue((order[1] == TroopType.LANCER && order[2] == TroopType.MARKSMAN)
                || (order[1] == TroopType.MARKSMAN && order[2] == TroopType.LANCER));
    }

    @Test
    void strongestTroopTypePicksTheHighestAverage() {
        // Infantry: 10 samples avg'd over 2 readings = 5.0; Lancer: 20/2 = 10.0; Marksman: 6/2 = 3.0
        double[] sums = { 10.0, 20.0, 6.0 };
        int[] counts = { 2, 2, 2 };

        assertEquals(TroopType.LANCER, LabyrinthChallengeDecisionPolicy.strongestTroopType(sums, counts, 2));
    }

    @Test
    void strongestTroopTypeExcludesAnUnderReadTypeWithoutDisqualifyingTheWholeRead() {
        // Marksman only got 1 readable row (below the minimum of 2) -- excluded from consideration,
        // but Infantry/Lancer (both fully readable) still produce a real answer.
        double[] sums = { 10.0, 20.0, 99.0 };
        int[] counts = { 2, 2, 1 };

        assertEquals(TroopType.LANCER, LabyrinthChallengeDecisionPolicy.strongestTroopType(sums, counts, 2));
    }

    @Test
    void strongestTroopTypeReturnsNullOnlyWhenEveryTypeIsUnderRead() {
        double[] sums = { 10.0, 20.0, 6.0 };
        int[] counts = { 1, 0, 1 };

        assertNull(LabyrinthChallengeDecisionPolicy.strongestTroopType(sums, counts, 2));
    }

    @Test
    void summarizeMarksAnUnderReadTypeAsUnreadable() {
        double[] sums = { 10.0, 20.0, 6.0 };
        int[] counts = { 2, 2, 1 };

        String summary = LabyrinthChallengeDecisionPolicy.summarize(sums, counts, 2);

        assertTrue(summary.contains("MARKSMAN=unreadable"));
        assertTrue(summary.contains("INFANTRY=5.0%"));
        assertTrue(summary.contains("LANCER=10.0%"));
    }
}
