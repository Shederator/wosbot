package dev.frostguard.tasks.combat;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Chooses the topmost safe rally so a fast-changing list is acted on before it shifts. */
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
                .min(Comparator.comparingInt(BearRallyCandidate::rowY));
    }
}
