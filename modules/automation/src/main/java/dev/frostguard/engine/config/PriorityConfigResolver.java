package dev.frostguard.engine.config;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.PriorityItemData;
import dev.frostguard.api.domain.AccountDescriptor;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/*
 * Reads pipe-delimited priority strings from profile configs and exposes
 * sorted/filtered views.  All entry points are static; no instance state.
 */
public final class PriorityConfigResolver {

    private PriorityConfigResolver() {}

    private static final Comparator<PriorityItemData> BY_RANK =
            Comparator.comparingInt(PriorityItemData::getPriority);

    // ---- list queries ----

    public static List<PriorityItemData> activeRankings(AccountDescriptor acct, ConfigurationKeyEnum key) {
        return parse(acct, key).filter(PriorityItemData::isEnabled).sorted(BY_RANK).toList();
    }

    public static List<PriorityItemData> allRankings(AccountDescriptor acct, ConfigurationKeyEnum key) {
        return parse(acct, key).sorted(BY_RANK).toList();
    }

    // ---- single-item queries ----

    public static boolean isOptionActive(AccountDescriptor acct, ConfigurationKeyEnum key, String name) {
        return activeRankings(acct, key).stream().anyMatch(e -> e.getName().equalsIgnoreCase(name));
    }

    public static int rankOf(AccountDescriptor acct, ConfigurationKeyEnum key, String name) {
        return findByName(acct, key, name).map(PriorityItemData::getPriority).orElse(-1);
    }

    public static Optional<PriorityItemData> findByName(AccountDescriptor acct,
                                                         ConfigurationKeyEnum key, String name) {
        return allRankings(acct, key).stream()
                .filter(e -> e.getName().equalsIgnoreCase(name))
                .findFirst();
    }

    // ---- internal ----

    private static Stream<PriorityItemData> parse(AccountDescriptor acct, ConfigurationKeyEnum key) {
        String raw = acct.getConfig(key, String.class);
        if (raw == null || raw.isBlank()) return Stream.empty();
        return Stream.of(raw.split("\\|"))
                .map(PriorityItemData::fromConfigString)
                .filter(item -> item != null);
    }
}
