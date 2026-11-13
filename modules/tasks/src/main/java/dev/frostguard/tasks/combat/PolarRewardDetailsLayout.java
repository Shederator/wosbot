package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.PolarTerrorExpertBonusState;

final class PolarRewardDetailsLayout {

    enum Mapping {
        NO_EXPERT_BONUS,
        CONFIRMED_EXPERT_BONUS
    }

    record Resolution(PolarTerrorExpertBonusState effectiveState, Mapping mapping, boolean persistExistence) {
    }

    private PolarRewardDetailsLayout() {
    }

    static Resolution resolve(PolarTerrorExpertBonusState configuredState, boolean expertBonusDetected) {
        if (configuredState == PolarTerrorExpertBonusState.NOT_DEFINED) {
            return expertBonusDetected
                    ? new Resolution(PolarTerrorExpertBonusState.EXIST, Mapping.CONFIRMED_EXPERT_BONUS, true)
                    : new Resolution(PolarTerrorExpertBonusState.NOT_CONFIRMED, Mapping.NO_EXPERT_BONUS, false);
        }
        return configuredState == PolarTerrorExpertBonusState.EXIST
                ? new Resolution(configuredState, Mapping.CONFIRMED_EXPERT_BONUS, false)
                : new Resolution(configuredState, Mapping.NO_EXPERT_BONUS, false);
    }
}
