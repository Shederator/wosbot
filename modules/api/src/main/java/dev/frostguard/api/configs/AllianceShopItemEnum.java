package dev.frostguard.api.configs;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Catalogue of purchasable goods offered in the guild store.
 *
 * <p>Every entry records a display caption, a base coin cost, and an
 * {@link Availability} tag indicating when the item appears.
 * The enum additionally implements {@link PrioritizableItemData}
 * so that entries can participate in ranked configuration lists.</p>
 */
public enum AllianceShopItemEnum implements PrioritizableItemData {

    ADVANCED_TELEPORT  ("Advanced Teleport",   130_000,  Availability.BOTH),
    MARCH_RECALL       ("March Recall",        26_000,   Availability.BOTH),
    MYTHIC_HERO_SHARDS ("Mythic Hero Shards",  500_000,  Availability.WEEKLY),
    PET_CHEST          ("Pet Chest",           100_000,  Availability.WEEKLY),
    PET_FOOD           ("Pet Food",            66_650,   Availability.WEEKLY),
    TERRITORY_TELEPORT ("Territory Teleport",  67_000,   Availability.BOTH),
    TRANSFER_PASS      ("Transfer Pass",       500_000,  Availability.WEEKLY),
    VIP_XP_10          ("10 VIP XP",           670,      Availability.BOTH),
    VIP_XP_100         ("100 VIP XP",          6_700,    Availability.BOTH);

    /**
     * Describes which shop refresh cycle an item belongs to.
     */
    public enum Availability {
        DAILY, WEEKLY, BOTH;

        /** Whether this rotation covers the daily refresh. */
        public boolean includesDaily()  { return this == DAILY  || this == BOTH; }

        /** Whether this rotation covers the weekly refresh. */
        public boolean includesWeekly() { return this == WEEKLY || this == BOTH; }
    }

    /* ---- per-availability cached subsets ---- */

    private static final Map<Availability, List<AllianceShopItemEnum>> ITEMS_BY_AVAILABILITY;

    static {
        Map<Availability, List<AllianceShopItemEnum>> temp = new EnumMap<>(Availability.class);
        for (Availability avail : Availability.values()) {
            temp.put(avail, new ArrayList<>());
        }
        for (AllianceShopItemEnum entry : values()) {
            temp.get(entry.rotation).add(entry);
        }
        Map<Availability, List<AllianceShopItemEnum>> immutable = new EnumMap<>(Availability.class);
        for (var mapEntry : temp.entrySet()) {
            immutable.put(mapEntry.getKey(),
                    Collections.unmodifiableList(mapEntry.getValue()));
        }
        ITEMS_BY_AVAILABILITY = Collections.unmodifiableMap(immutable);
    }

    /** Sum of all base coin prices across every item in the catalogue. */
    private static final int TOTAL_CATALOGUE_COST;

    static {
        int sum = 0;
        for (AllianceShopItemEnum entry : values()) {
            sum += entry.coinPrice;
        }
        TOTAL_CATALOGUE_COST = sum;
    }

    private static final NumberFormat PRICE_FORMATTER =
            NumberFormat.getIntegerInstance(Locale.US);

    private final String itemName;
    private final int coinPrice;
    private final Availability rotation;

    AllianceShopItemEnum(String itemName, int coinPrice,
                         Availability rotation) {
        this.itemName  = itemName;
        this.coinPrice = coinPrice;
        this.rotation  = rotation;
    }

    public String itemName()            { return itemName; }
    public int coinPrice()              { return coinPrice; }
    public Availability availability()  { return rotation; }

    /** {@code true} when the item is exclusive to the weekly refresh. */
    public boolean isWeeklyOnly() {
        return rotation == Availability.WEEKLY;
    }

    /** {@code true} when the item appears during the daily refresh. */
    public boolean isDailyAvailable() {
        return rotation.includesDaily();
    }

    /** Returns the coin cost with locale-appropriate grouping separators. */
    public String formattedCost() {
        return PRICE_FORMATTER.format(coinPrice);
    }

    /**
     * Computes the cost as a percentage of the total catalogue value.
     *
     * @return a value between 0.0 and 100.0
     */
    public double costPercentage() {
        if (TOTAL_CATALOGUE_COST == 0) return 0.0;
        return (coinPrice * 100.0) / TOTAL_CATALOGUE_COST;
    }

    /**
     * Tests whether the given coin budget is sufficient to purchase
     * at least one unit of this item.
     *
     * @param availableCoins the operator's current coin balance
     * @return {@code true} when the balance covers the base price
     */
    public boolean isAffordable(int availableCoins) {
        return availableCoins >= coinPrice;
    }

    /** Provides every item that is stocked during the weekly cycle. */
    public static List<AllianceShopItemEnum> weeklyItems() {
        return ITEMS_BY_AVAILABILITY.getOrDefault(
                Availability.WEEKLY, Collections.emptyList());
    }

    /**
     * Retrieves all items belonging to the specified availability tier.
     *
     * @param tier the availability filter
     * @return an unmodifiable list of matching items
     */
    public static List<AllianceShopItemEnum> itemsByAvailability(
            Availability tier) {
        return ITEMS_BY_AVAILABILITY.getOrDefault(
                tier, Collections.emptyList());
    }

    /**
     * Returns the aggregate coin cost of every item in the catalogue.
     */
    public static int totalCatalogueCost() {
        return TOTAL_CATALOGUE_COST;
    }

    /* ---------- PrioritizableItemData ---------- */

    @Override
    public String configKey() {
        return name();
    }

    @Override
    public String label() {
        return itemName;
    }

    /* ---------- backward-compatible accessor shims ---------- */

    @Override
    public String getIdentifier()  { return configKey(); }
    @Override
    public String getDisplayName() { return itemName; }
    public int getBasePrice()      { return coinPrice; }
    public int baseCost()          { return coinPrice; }
    public Availability getTab()   { return rotation; }
    public Availability rotation() { return rotation; }

    @Override
    public String toString() {
        return itemName + " (" + formattedCost() + " coins)";
    }
}
