package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.AreaData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BearRallyCandidateSelectorTest {

    private static final Instant OBSERVED = Instant.parse("2026-09-17T14:00:00Z");

    @Test
    void rejectsOpenMemberSlotWhenTheWholeFormationDoesNotFit() {
        BearRallyCandidate candidate = candidate(
                300, true, BearRallyCandidate.JoinButton.GREEN,
                4, 15, 430_000, 500_000, 240);

        assertFalse(candidate.accepts(80_000));
        assertTrue(candidate.accepts(70_000));
    }

    @Test
    void greyButtonIsNeverJoinableEvenWhenMembersAndCapacityAreOpen() {
        BearRallyCandidate candidate = candidate(
                300, true, BearRallyCandidate.JoinButton.GREY,
                4, 15, 100_000, 500_000, 240);

        assertFalse(candidate.accepts(80_000));
        assertTrue(BearRallyCandidateSelector.selectBest(List.of(candidate), 80_000, Set.of()).isEmpty());
    }

    @Test
    void rejectsNonBearFullAndDepartedCandidates() {
        assertFalse(candidate(300, false, BearRallyCandidate.JoinButton.GREEN,
                4, 15, 100_000, 500_000, 240).accepts(80_000));
        assertFalse(candidate(300, true, BearRallyCandidate.JoinButton.GREEN,
                15, 15, 100_000, 500_000, 240).accepts(80_000));
        assertFalse(candidate(300, true, BearRallyCandidate.JoinButton.GREEN,
                4, 15, 100_000, 500_000, 0).accepts(80_000));
    }

    @Test
    void rejectsRalliesTooCloseToDepartureToFinishVerifiedDeployment() {
        assertFalse(candidate(300, true, BearRallyCandidate.JoinButton.GREEN,
                4, 15, 100_000, 500_000, 29).accepts(80_000));
        assertTrue(candidate(300, true, BearRallyCandidate.JoinButton.GREEN,
                4, 15, 100_000, 500_000, 30).accepts(80_000));
    }

    @Test
    void rechecksTheCountdownAfterFormationSelectionTimeHasElapsed() {
        BearRallyCandidate candidate = candidate(300, true, BearRallyCandidate.JoinButton.GREEN,
                4, 15, 100_000, 500_000, 35);

        assertTrue(candidate.accepts(80_000, OBSERVED.plusSeconds(5)));
        assertFalse(candidate.accepts(80_000, OBSERVED.plusSeconds(6)));
    }

    @Test
    void ranksCapacityThenCountdownThenOccupancyBeforeScreenPosition() {
        BearRallyCandidate cramped = candidate(200, true, BearRallyCandidate.JoinButton.GREEN,
                3, 15, 350_000, 500_000, 290);
        BearRallyCandidate ampleOld = candidate(300, true, BearRallyCandidate.JoinButton.GREEN,
                6, 15, 100_000, 500_000, 120);
        BearRallyCandidate ampleNew = candidate(500, true, BearRallyCandidate.JoinButton.GREEN,
                4, 15, 100_000, 500_000, 260);

        assertEquals(ampleNew, BearRallyCandidateSelector.selectBest(
                List.of(cramped, ampleOld, ampleNew), 100_000, Set.of()).orElseThrow());
    }

    @Test
    void rejectedRallyKeySurvivesChangingMemberAndTroopCounts() {
        BearRallyCandidate firstRead = candidate(300, true, BearRallyCandidate.JoinButton.GREEN,
                3, 15, 100_000, 500_000, 120);
        BearRallyCandidate laterRead = candidate(300, true, BearRallyCandidate.JoinButton.GREEN,
                5, 15, 180_000, 500_000, 120);

        assertEquals(firstRead.stableKey(), laterRead.stableKey());
    }

    @Test
    void excludesAJustFailedCandidateSoTheSameFormationCanTryTheNextOne() {
        BearRallyCandidate first = candidate(200, true, BearRallyCandidate.JoinButton.GREEN,
                3, 15, 100_000, 500_000, 290);
        BearRallyCandidate second = candidate(500, true, BearRallyCandidate.JoinButton.GREEN,
                4, 15, 100_000, 500_000, 260);

        assertEquals(second, BearRallyCandidateSelector.selectBest(
                List.of(first, second), 100_000, Set.of(first.stableKey())).orElseThrow());
    }

    @Test
    void reauthorizationRejectsAReplacementThatMovedIntoTheSameRow() {
        BearRallyCandidate selected = candidate(300, true, BearRallyCandidate.JoinButton.GREEN,
                3, 15, 100_000, 500_000, 120);
        BearRallyCandidate replacement = candidate(300, true, BearRallyCandidate.JoinButton.GREEN,
                2, 20, 100_000, 600_000, 180);

        assertTrue(BearRallyCandidateSelector.reauthorize(
                selected, List.of(replacement), 80_000, OBSERVED.plusSeconds(1),
                Duration.ofSeconds(2)).isEmpty());
    }

    @Test
    void reauthorizationRejectsSameSizedSameDeadlineRowReplacementByVisualIdentity() {
        BearRallyCandidate selected = candidateWithIdentity(300, 3, 100_000, 120, 0x1111L);
        BearRallyCandidate replacement = candidateWithIdentity(300, 3, 100_000, 120, 0x2222L);

        assertTrue(BearRallyCandidateSelector.reauthorize(
                selected, List.of(replacement), 80_000, OBSERVED.plusSeconds(1),
                Duration.ofSeconds(2)).isEmpty());
    }

    @Test
    void reauthorizationAcceptsTheSameFreshRallyAfterOccupancyChanges() {
        BearRallyCandidate selected = candidate(300, true, BearRallyCandidate.JoinButton.GREEN,
                3, 15, 100_000, 500_000, 120);
        BearRallyCandidate refreshed = candidate(300, true, BearRallyCandidate.JoinButton.GREEN,
                5, 15, 180_000, 500_000, 120);

        assertEquals(refreshed, BearRallyCandidateSelector.reauthorize(
                selected, List.of(refreshed), 80_000, OBSERVED.plusSeconds(1),
                Duration.ofSeconds(2)).orElseThrow());
    }

    @Test
    void reauthorizationRejectsAStaleOrNewlyFullCopyOfTheSameRally() {
        BearRallyCandidate selected = candidate(300, true, BearRallyCandidate.JoinButton.GREEN,
                3, 15, 100_000, 500_000, 120);
        BearRallyCandidate full = candidate(300, true, BearRallyCandidate.JoinButton.GREEN,
                15, 15, 500_000, 500_000, 120);

        assertTrue(BearRallyCandidateSelector.reauthorize(
                selected, List.of(full), 80_000, OBSERVED.plusSeconds(1),
                Duration.ofSeconds(2)).isEmpty());
        assertTrue(BearRallyCandidateSelector.reauthorize(
                selected, List.of(selected), 80_000, OBSERVED.plusSeconds(3),
                Duration.ofSeconds(2)).isEmpty());
    }

    private BearRallyCandidate candidate(
            int y,
            boolean bear,
            BearRallyCandidate.JoinButton button,
            int members,
            int maxMembers,
            long troops,
            long capacity,
            long countdownSeconds) {
        return new BearRallyCandidate(
                AreaData.of(580, y, 690, y + 50), y, bear, button,
                members, maxMembers, troops, capacity,
                Duration.ofSeconds(countdownSeconds), OBSERVED);
    }

    private BearRallyCandidate candidateWithIdentity(
            int y, int members, long troops, long countdownSeconds, long identity) {
        return new BearRallyCandidate(
                AreaData.of(580, y, 690, y + 50), y, true,
                BearRallyCandidate.JoinButton.GREEN,
                members, 15, troops, 500_000,
                Duration.ofSeconds(countdownSeconds), OBSERVED, identity);
    }
}
