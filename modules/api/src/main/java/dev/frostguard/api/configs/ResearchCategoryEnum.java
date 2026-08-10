package dev.frostguard.api.configs;

/**
 * Research tech-tree categories eligible for priority-ordered automation.
 */
public enum ResearchCategoryEnum implements PrioritizableItemData {

    GROWTH("growth", "Growth"),
    ECONOMY("economy", "Economy"),
    BATTLE("battle", "Battle");

    private final String key;
    private final String caption;

    ResearchCategoryEnum(String key, String caption) {
        this.key = key;
        this.caption = caption;
    }

    @Override
    public String configKey() {
        return key;
    }

    @Override
    public String label() {
        return caption;
    }

    public static ResearchCategoryEnum fromKey(String identifier) {
        for (ResearchCategoryEnum category : values()) {
            if (category.matchesKey(identifier)) {
                return category;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return caption;
    }
}
