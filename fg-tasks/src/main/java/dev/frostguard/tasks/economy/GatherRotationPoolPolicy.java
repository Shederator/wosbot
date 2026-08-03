package dev.frostguard.tasks.economy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import dev.frostguard.tasks.economy.GatherRoutine.GatherType;

final class GatherRotationPoolPolicy {

    private GatherRotationPoolPolicy() {
    }

    static Reconciliation initialize(List<GatherType> pool, List<GatherType> currentEnabled) {
        return new Reconciliation(pruneDisabled(pool, currentEnabled), List.of(), List.of());
    }

    static Reconciliation reconcile(List<GatherType> pool, List<GatherType> previousEnabled,
            List<GatherType> currentEnabled) {
        Set<GatherType> previous = new LinkedHashSet<>(previousEnabled);
        Set<GatherType> current = new LinkedHashSet<>(currentEnabled);

        List<GatherType> newlyEnabled = current.stream()
                .filter(type -> !previous.contains(type))
                .toList();
        List<GatherType> disabled = previous.stream()
                .filter(type -> !current.contains(type))
                .toList();

        List<GatherType> reconciledPool = pruneDisabled(pool, currentEnabled);
        for (GatherType type : newlyEnabled) {
            if (!reconciledPool.contains(type)) {
                reconciledPool.add(type);
            }
        }
        return new Reconciliation(reconciledPool, newlyEnabled, disabled);
    }

    static List<GatherType> pruneDisabled(List<GatherType> pool, List<GatherType> currentEnabled) {
        Set<GatherType> current = new LinkedHashSet<>(currentEnabled);
        Set<GatherType> retained = new LinkedHashSet<>();
        if (pool != null) {
            for (GatherType type : pool) {
                if (current.contains(type)) {
                    retained.add(type);
                }
            }
        }
        return new ArrayList<>(retained);
    }

    record Reconciliation(List<GatherType> pool, List<GatherType> newlyEnabled,
                          List<GatherType> disabled) {
    }
}
