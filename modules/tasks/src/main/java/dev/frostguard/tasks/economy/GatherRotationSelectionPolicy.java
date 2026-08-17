package dev.frostguard.tasks.economy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.frostguard.tasks.economy.GatherRoutine.GatherType;

final class GatherRotationSelectionPolicy {

    private GatherRotationSelectionPolicy() {
    }

    static Refill refill(List<GatherType> enabledTypes, List<GatherType> activeTypes,
            Set<GatherType> unavailableTypes) {
        Set<GatherType> active = new LinkedHashSet<>(activeTypes);
        Set<GatherType> unavailable = new LinkedHashSet<>(unavailableTypes);

        List<GatherType> missing = enabledTypes.stream()
                .filter(type -> !active.contains(type))
                .filter(type -> !unavailable.contains(type))
                .toList();
        if (!missing.isEmpty()) {
            return new Refill(missing, RefillMode.MISSING_TYPES);
        }

        boolean allEnabledTypesActive = enabledTypes.stream().allMatch(active::contains);
        if (!allEnabledTypesActive) {
            return new Refill(List.of(), RefillMode.UNAVAILABLE_MISSING_TYPES);
        }

        List<GatherType> duplicates = enabledTypes.stream()
                .filter(type -> !unavailable.contains(type))
                .toList();
        return new Refill(duplicates, RefillMode.DUPLICATES);
    }

    enum RefillMode {
        MISSING_TYPES,
        DUPLICATES,
        UNAVAILABLE_MISSING_TYPES
    }

    record Refill(List<GatherType> candidates, RefillMode mode) {
    }
}
