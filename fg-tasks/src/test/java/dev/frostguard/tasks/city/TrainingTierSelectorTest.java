package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.TemplatesEnum;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.frostguard.api.configs.TemplatesEnum.TRAINING_INFANTRY_T10;
import static dev.frostguard.api.configs.TemplatesEnum.TRAINING_INFANTRY_T8;
import static dev.frostguard.api.configs.TemplatesEnum.TRAINING_INFANTRY_T9;
import static dev.frostguard.tasks.city.TrainingTierSelector.TierState.LOCKED;
import static dev.frostguard.tasks.city.TrainingTierSelector.TierState.NOT_VISIBLE;
import static dev.frostguard.tasks.city.TrainingTierSelector.TierState.UNLOCKED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingTierSelectorTest {

    private static final List<TemplatesEnum> DESCENDING_TIERS = List.of(
            TRAINING_INFANTRY_T10,
            TRAINING_INFANTRY_T9,
            TRAINING_INFANTRY_T8);

    @Test
    void skipsVisibleLockedTiersAndSelectsHighestUnlockedTier() {
        Map<TemplatesEnum, TrainingTierSelector.TierState> states = new EnumMap<>(TemplatesEnum.class);
        states.put(TRAINING_INFANTRY_T10, LOCKED);
        states.put(TRAINING_INFANTRY_T9, LOCKED);
        states.put(TRAINING_INFANTRY_T8, UNLOCKED);

        Optional<TemplatesEnum> selected = TrainingTierSelector.findHighestUnlocked(
                DESCENDING_TIERS,
                tier -> states.getOrDefault(tier, NOT_VISIBLE));

        assertEquals(Optional.of(TRAINING_INFANTRY_T8), selected);
    }

    @Test
    void stopsProbingAfterFirstUnlockedTier() {
        List<TemplatesEnum> probed = new ArrayList<>();

        Optional<TemplatesEnum> selected = TrainingTierSelector.findHighestUnlocked(
                DESCENDING_TIERS,
                tier -> {
                    probed.add(tier);
                    return tier == TRAINING_INFANTRY_T9 ? UNLOCKED : LOCKED;
                });

        assertEquals(Optional.of(TRAINING_INFANTRY_T9), selected);
        assertEquals(List.of(TRAINING_INFANTRY_T10, TRAINING_INFANTRY_T9), probed);
    }

    @Test
    void returnsEmptyWhenNoTierIsConfirmedUnlocked() {
        Optional<TemplatesEnum> selected = TrainingTierSelector.findHighestUnlocked(
                DESCENDING_TIERS,
                tier -> tier == TRAINING_INFANTRY_T10 ? LOCKED : NOT_VISIBLE);

        assertTrue(selected.isEmpty());
    }
}
