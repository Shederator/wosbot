package dev.frostguard.engine.nav;

import java.util.Locale;
import java.util.Optional;

/** Ordered tabs in the Shop footer, with conservative OCR markers measured from the game. */
public enum ShopTab {
    MYSTERY_SHOP("Mystery Shop", "Mystery"),
    NOMADIC_MERCHANT("Nomadic Merchant", "eee"),
    ARENA_SHOP("Arena Shop", "Arena"),
    VIP_SHOP("VIP Shop", "VIP"),
    ALLIANCE_CHAMPIONSHIP_SHOP("Alliance Championship Shop", "Championship"),
    LABYRINTH_SHOP("Labyrinth Shop", "Labyrinth"),
    STATE_OF_POWER_SHOP("State of Power Shop", "State"),
    FOUNDRY_SHOP("Foundry Shop", "Foundry"),
    CANYON_SHOP("Canyon Shop", "Canyon"),
    SKIN_SHOP("Skin Shop", "Skin"),
    GEM_SHOP("Gem Shop", "Gem");

    private final String displayName;
    private final String ocrMarker;

    ShopTab(String displayName, String ocrMarker) {
        this.displayName = displayName;
        this.ocrMarker = ocrMarker;
    }

    public String displayName() {
        return displayName;
    }

    public String ocrMarker() {
        return ocrMarker;
    }

    public int position() {
        return ordinal();
    }

    /** Returns a tab only when exactly one configured marker occurs in the OCR output. */
    public static Optional<ShopTab> fromOcr(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return Optional.empty();
        }

        String normalized = rawText.toLowerCase(Locale.ROOT);
        ShopTab matched = null;
        for (ShopTab tab : values()) {
            if (!normalized.contains(tab.ocrMarker.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (matched != null) {
                return Optional.empty();
            }
            matched = tab;
        }
        return Optional.ofNullable(matched);
    }
}
