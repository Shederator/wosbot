package dev.frostguard.api.domain;

/**
 * Mutable row representing an operator-assigned priority entry.
 * Used by ranked-list controls in the settings panel and
 * persisted alongside profile configuration data.
 *
 * <p>Serialisation uses a colon-delimited format. Both the legacy
 * three-field layout ({@code tag:rank:active}) and the current
 * four-field layout ({@code tag:label:rank:active}) are supported
 * during deserialisation.</p>
 */
public class PriorityItemData {

    private static final String FIELD_SEPARATOR = ":";
    private static final int THREE_FIELD_FORMAT = 3;
    private static final int FOUR_FIELD_FORMAT  = 4;

    private String key;
    private String caption;
    private int order;
    private boolean selected;

    /**
     * Creates a row where the tag and label share the same value.
     */
    public PriorityItemData(String label, int rank, boolean active) {
        this(label, label, rank, active);
    }

    /**
     * Creates a fully specified row.
     *
     * @param tag    persistence identifier
     * @param label  display caption
     * @param rank   numeric ordering position
     * @param active whether the item is currently enabled
     */
    public PriorityItemData(String tag, String label,
                            int rank, boolean active) {
        applyValues(tag, label, rank, active);
    }

    /* ---- tag (persistence key) ---- */

    public String getTag()            { return key; }
    public void setTag(String tag)    { key = tag; }

    /* ---- label (display caption) ---- */

    public String getLabel()              { return caption; }
    public void setLabel(String label)    { caption = label; }

    /* ---- rank (ordering position) ---- */

    public int getRank()            { return order; }
    public void setRank(int rank)   { order = rank; }

    /* ---- active state ---- */

    public boolean isActive()                 { return selected; }
    public void setActive(boolean active)     { selected = active; }

    /* ---- functional operations ---- */

    /**
     * Returns a new instance with the rank changed to the
     * supplied value. This instance is not modified.
     *
     * @param newRank the replacement rank
     * @return a fresh copy with the updated rank
     */
    public PriorityItemData withRank(int newRank) {
        return new PriorityItemData(key, caption, newRank, selected);
    }

    /**
     * Returns a new instance with the active flag toggled.
     * This instance is not modified.
     *
     * @param active the new active state
     * @return a fresh copy with the updated flag
     */
    public PriorityItemData withActive(boolean active) {
        return new PriorityItemData(key, caption, order, active);
    }

    /**
     * Increases the rank by one (lower priority).
     * Modifies this instance in place.
     */
    public void demote() {
        order++;
    }

    /**
     * Decreases the rank by one (higher priority), down to zero.
     * Modifies this instance in place.
     */
    public void promote() {
        if (order > 0) order--;
    }

    /**
     * Tests whether this item's tag matches the given identifier,
     * using a case-insensitive comparison.
     *
     * @param identifier the tag to test against
     * @return {@code true} on a case-insensitive match
     */
    public boolean matchesTag(String identifier) {
        if (identifier == null || key == null) return false;
        return key.equalsIgnoreCase(identifier);
    }


    public String getIdentifier()                 { return key; }
    public void setIdentifier(String id)          { key = id; }

    public String getName()                       { return caption; }
    public void setName(String name)              { caption = name; }

    public int getPriority()                      { return order; }
    public void setPriority(int priority)         { order = priority; }

    public boolean isEnabled()                    { return selected; }
    public void setEnabled(boolean enabled)       { selected = enabled; }

    /* ---- serialisation ---- */

    /**
     * Produces the colon-delimited persistence representation.
     */
    public String serialize() {
        return String.join(FIELD_SEPARATOR,
                String.valueOf(key),
                String.valueOf(caption),
                Integer.toString(order),
                Boolean.toString(selected));
    }

    /** Alias retained for backward compatibility. */
    public String toConfigString() {
        return serialize();
    }

    /* ---- deserialisation ---- */

    /**
     * Reconstructs a row from its colon-delimited text form.
     *
     * @param raw the serialised string
     * @return the reconstructed row, or {@code null} on invalid input
     */
    public static PriorityItemData deserialize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return interpretFields(raw.split(FIELD_SEPARATOR, -1));
    }

    /** Alias retained for backward compatibility. */
    public static PriorityItemData fromConfigString(String configString) {
        return deserialize(configString);
    }

    @Override
    public String toString() {
        String statusLabel = selected ? "Active" : "Inactive";
        return caption + " (Rank: " + order + ", " + statusLabel + ")";
    }

    /* ---- internal helpers ---- */

    private void applyValues(String tag, String label,
                             int rank, boolean active) {
        this.key      = tag;
        this.caption  = label;
        this.order    = rank;
        this.selected = active;
    }

    private static PriorityItemData interpretFields(String[] fields) {
        return switch (fields.length) {
            case THREE_FIELD_FORMAT ->
                    buildFromFields(fields[0], fields[0],
                            fields[1], fields[2]);
            case FOUR_FIELD_FORMAT ->
                    buildFromFields(fields[0], fields[1],
                            fields[2], fields[3]);
            default -> null;
        };
    }

    private static PriorityItemData buildFromFields(
            String tag, String label,
            String rankText, String activeText) {
        Integer rank = safeParseInt(rankText);
        if (rank == null) {
            return null;
        }
        return new PriorityItemData(tag, label, rank,
                Boolean.parseBoolean(activeText));
    }

    private static Integer safeParseInt(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
