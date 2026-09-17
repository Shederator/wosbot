package dev.frostguard.tasks.combat;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Chooses a safe rally while favouring roomy, newer, less occupied cards. */
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
                .max(Comparator
                        .comparingLong(BearRallyCandidate::remainingCapacity)
                        .thenComparing(BearRallyCandidate::countdown)
                        .thenComparingInt(candidate -> -candidate.currentMembers())
                        .thenComparingInt(BearRallyCandidate::rowY));
    }
}
