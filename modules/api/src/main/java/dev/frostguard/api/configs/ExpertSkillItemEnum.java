package dev.frostguard.api.configs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Enumerates expert hero skill slots that are eligible for
 * automated priority-based training.
 *
 * <p>Each constant references one of the six recognised
 * {@link Expert} heroes together with a one-based slot position.
 * Display captions and persistence tokens are computed from those
 * two attributes rather than stored as redundant fields.</p>
 */
public enum ExpertSkillItemEnum implements PrioritizableItemData {

    /* Agnes */
    AGNES_SLOT_1 (Expert.AGNES,   1),
    AGNES_SLOT_2 (Expert.AGNES,   2),
    AGNES_SLOT_3 (Expert.AGNES,   3),
    AGNES_SLOT_4 (Expert.AGNES,   4),

    /* Baldur */
    BALDUR_SLOT_1(Expert.BALDUR,  1),
    BALDUR_SLOT_2(Expert.BALDUR,  2),
    BALDUR_SLOT_3(Expert.BALDUR,  3),
    BALDUR_SLOT_4(Expert.BALDUR,  4),

    /* Cyrille */
    CYRILLE_SLOT_1(Expert.CYRILLE, 1),
    CYRILLE_SLOT_2(Expert.CYRILLE, 2),
    CYRILLE_SLOT_3(Expert.CYRILLE, 3),
    CYRILLE_SLOT_4(Expert.CYRILLE, 4),

    /* Fabian */
    FABIAN_SLOT_1(Expert.FABIAN,  1),
    FABIAN_SLOT_2(Expert.FABIAN,  2),
    FABIAN_SLOT_3(Expert.FABIAN,  3),
    FABIAN_SLOT_4(Expert.FABIAN,  4),

    /* Holger */
    HOLGER_SLOT_1(Expert.HOLGER,  1),
    HOLGER_SLOT_2(Expert.HOLGER,  2),
    HOLGER_SLOT_3(Expert.HOLGER,  3),
    HOLGER_SLOT_4(Expert.HOLGER,  4),

    /* Romulus */
    ROMULUS_SLOT_1(Expert.ROMULUS, 1),
    ROMULUS_SLOT_2(Expert.ROMULUS, 2),
    ROMULUS_SLOT_3(Expert.ROMULUS, 3),
    ROMULUS_SLOT_4(Expert.ROMULUS, 4);

    /**
     * The six expert heroes whose skills can be trained.
     */
    public enum Expert {
        AGNES, BALDUR, CYRILLE, FABIAN, HOLGER, ROMULUS;

        /**
         * Produces a title-cased representation of the hero name.
         *
         * @return e.g. "Agnes", "Romulus"
         */
        public String displayName() {
            String lower = name().toLowerCase();
            return Character.toUpperCase(lower.charAt(0))
                    + lower.substring(1);
        }

        /** Total number of skill slots available per hero. */
        public static final int SLOTS_PER_EXPERT = 4;
    }

    /** Pre-indexed mapping from each expert to their skill slots. */
    private static final Map<Expert, List<ExpertSkillItemEnum>> BY_EXPERT;

    /** Total count of all trainable skill slots across all experts. */
    public static final int TOTAL_SKILL_COUNT;

    static {
        Map<Expert, List<ExpertSkillItemEnum>> temp = new EnumMap<>(Expert.class);
        for (Expert hero : Expert.values()) {
            temp.put(hero, new ArrayList<>(Expert.SLOTS_PER_EXPERT));
        }
        for (ExpertSkillItemEnum slot : values()) {
            temp.get(slot.expert).add(slot);
        }
        Map<Expert, List<ExpertSkillItemEnum>> immutable = new EnumMap<>(Expert.class);
        for (var entry : temp.entrySet()) {
            immutable.put(entry.getKey(),
                    Collections.unmodifiableList(entry.getValue()));
        }
        BY_EXPERT = Collections.unmodifiableMap(immutable);
        TOTAL_SKILL_COUNT = values().length;
    }

    private final Expert expert;
    private final int slotIndex;

    ExpertSkillItemEnum(Expert expert, int slotIndex) {
        this.expert    = expert;
        this.slotIndex = slotIndex;
    }

    /** The hero this skill slot belongs to. */
    public Expert expert()   { return expert; }

    /** One-based position within the hero's skill set. */
    public int slotIndex()   { return slotIndex; }

    /**
     * Tests whether this is the first skill slot of its hero.
     *
     * @return {@code true} when slotIndex is 1
     */
    public boolean isPrimarySlot() {
        return slotIndex == 1;
    }

    /**
     * Tests whether this slot belongs to the specified hero.
     *
     * @param hero the hero to check
     * @return {@code true} on match
     */
    public boolean belongsTo(Expert hero) {
        return this.expert == hero;
    }

    /**
     * Collects all four skill slots for the specified hero.
     * Uses a pre-built index for O(1) lookup.
     *
     * @param target the hero whose slots are requested
     * @return an unmodifiable list ordered by slot position
     */
    public static List<ExpertSkillItemEnum> ofExpert(Expert target) {
        return BY_EXPERT.getOrDefault(target, Collections.emptyList());
    }

    /**
     * Retrieves a specific slot for a given expert and position.
     *
     * @param hero the target expert
     * @param slot the one-based slot index (1–4)
     * @return the matching enum constant, or {@code null} when not found
     */
    public static ExpertSkillItemEnum ofExpertSlot(Expert hero, int slot) {
        List<ExpertSkillItemEnum> slots = ofExpert(hero);
        for (ExpertSkillItemEnum candidate : slots) {
            if (candidate.slotIndex == slot) {
                return candidate;
            }
        }
        return null;
    }

    /* ---------- PrioritizableItemData ---------- */

    @Override
    public String configKey() {
        return expert.name().toLowerCase() + "_skill_" + slotIndex;
    }

    @Override
    public String label() {
        return expert.displayName() + " - Skill " + slotIndex;
    }

    /* ---------- backward-compatible accessor shims ---------- */

    @Override
    public String getIdentifier()  { return configKey(); }

    @Override
    public String getDisplayName() { return label(); }

    @Override
    public String toString() {
        return label();
    }
}
