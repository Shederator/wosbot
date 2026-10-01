package dev.frostguard.tasks.combat;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Ranks safe rallies by usable capacity, time to act, occupancy, then stable screen position. */
final class BearRallyCandidateSelector {

    private BearRallyCandidateSelector() {
    }

    static Optional<BearRallyCandidate> selectBest(
            List<BearRallyCandidate> candidates,
            long formationTroops,
            Set<String> excludedKeys) {
        return candidates.stream()
                .filter(candidate -> !excludedKeys.contains(candidate.stableKey()))
                .filter(candidate -> candidate.accepts(formationTroops))
                .min(Comparator
                        .comparingLong(BearRallyCandidate::remainingCapacity).reversed()
                        .thenComparing(BearRallyCandidate::countdown, Comparator.reverseOrder())
                        .thenComparingInt(BearRallyCandidate::currentMembers)
                        .thenComparingInt(BearRallyCandidate::rowY));
    }

    /**
     * Re-authorizes the selected semantic rally identity from a second immutable frame. Position
     * alone is insufficient because the War list can reorder between scan and tap.
     */
    static Optional<BearRallyCandidate> reauthorize(
            BearRallyCandidate selected,
            List<BearRallyCandidate> freshCandidates,
            long formationTroops,
            Instant evaluatedAt,
            Duration maximumAge) {
        String selectedKey = selected.stableKey();
        return freshCandidates.stream()
                .filter(candidate -> candidate.stableKey().equals(selectedKey))
                .filter(candidate -> candidate.accepts(formationTroops, evaluatedAt))
                .filter(candidate -> !evaluatedAt.isAfter(candidate.observedAt().plus(maximumAge)))
                .findFirst();
    }
}
