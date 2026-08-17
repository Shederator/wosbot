package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.TemplatesEnum;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

final class TrainingTierSelector {

    enum TierState {
        NOT_VISIBLE,
        LOCKED,
        UNLOCKED
    }

    private TrainingTierSelector() {}

    static Optional<TemplatesEnum> findHighestUnlocked(
            List<TemplatesEnum> descendingTiers,
            Function<TemplatesEnum, TierState> probe) {
        for (TemplatesEnum tier : descendingTiers) {
            if (probe.apply(tier) == TierState.UNLOCKED) {
                return Optional.of(tier);
            }
        }
        return Optional.empty();
    }
}
