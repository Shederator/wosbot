package dev.frostguard.api.configs;

import java.util.Comparator;

/**
 * Shared contract for any enumerated item that participates in
 * operator-controlled priority ordering within the settings UI.
 *
 * <p>Conforming types expose a stable storage key and a rendered
 * caption. Optionally they may declare a default relative weight
 * or signal seasonal unavailability so the panel can suppress them
 * dynamically.</p>
 *
 * @see AllianceShopItemEnum
 * @see ExpertSkillItemEnum
 */
public interface PrioritizableItemData {

    /**
     * A storage-safe token persisted alongside profile configuration.
     * This value must never change between software releases.
     */
    String configKey();

    /**
     * Readable caption rendered in the operator's settings panel.
     */
    String label();

    /**
     * Relative ordering hint applied when no explicit sequence
     * has been defined. Smaller numbers surface earlier in lists.
     *
     * @return zero by default, meaning no inherent preference
     */
    default int sortWeight() {
        return 0;
    }

    /**
     * Controls visibility of this entry. Override to conditionally
     * hide items tied to limited-time game seasons or disabled
     * feature gates.
     *
     * @return {@code true} unless overridden
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Checks whether this item's config key matches the supplied
     * identifier string, using a case-insensitive comparison.
     *
     * @param identifier the token to compare against
     * @return {@code true} when keys match ignoring case
     */
    default boolean matchesKey(String identifier) {
        if (identifier == null) return false;
        return configKey().equalsIgnoreCase(identifier);
    }

    /**
     * Returns a concise diagnostic string combining key and label.
     *
     * @return formatted string in the form "key → label"
     */
    default String toSummary() {
        return configKey() + " → " + label();
    }

    /* ---------- backward-compatible accessor shims ---------- */

    default String getIdentifier() {
        return configKey();
    }

    default String getDisplayName() {
        return label();
    }

    /**
     * Ordering function that first considers {@link #sortWeight()}
     * and falls back to a locale-independent caption comparison.
     */
    static int compareByWeight(PrioritizableItemData first,
                               PrioritizableItemData second) {
        int delta = Integer.compare(first.sortWeight(), second.sortWeight());
        return delta != 0
                ? delta
                : first.label().compareToIgnoreCase(second.label());
    }

    /**
     * Pre-built comparator instance suitable for stream operations
     * and collection sorting.
     */
    Comparator<PrioritizableItemData> WEIGHT_ORDER =
            PrioritizableItemData::compareByWeight;

    /**
     * Comparator that orders items alphabetically by label,
     * ignoring case differences.
     */
    Comparator<PrioritizableItemData> LABEL_ORDER =
            (a, b) -> a.label().compareToIgnoreCase(b.label());
}
