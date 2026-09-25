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
    void takesTheTopmostGreenRallyThatFitsTheFormation() {
        BearRallyCandidate cramped = candidate(200, true, BearRallyCandidate.JoinButton.GREEN,
                3, 15, 350_000, 500_000, 290);
        BearRallyCandidate ampleOld = candidate(300, true, BearRallyCandidate.JoinButton.GREEN,
                6, 15, 100_000, 500_000, 120);
        BearRallyCandidate ampleNew = candidate(500, true, BearRallyCandidate.JoinButton.GREEN,
                4, 15, 100_000, 500_000, 260);

        assertEquals(cramped, BearRallyCandidateSelector.selectBest(
                List.of(cramped, ampleOld, ampleNew), 100_000, Set.of()).orElseThrow());
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
}
