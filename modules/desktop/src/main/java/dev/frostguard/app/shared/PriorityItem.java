package dev.frostguard.app.shared;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.Optional;

public final class PriorityItem {

    private static final String FIELD_SEPARATOR = ":";
    private static final int LEGACY_FIELD_COUNT = 3;
    private static final int CURRENT_FIELD_COUNT = 4;

    private final StringProperty identifier = new SimpleStringProperty();
    private final StringProperty name = new SimpleStringProperty();
    private final IntegerProperty priority = new SimpleIntegerProperty();
    private final BooleanProperty enabled = new SimpleBooleanProperty();

    public PriorityItem(String name, int priority, boolean enabled) {
        this(name, name, priority, enabled);
    }

    public PriorityItem(String identifier, String name, int priority, boolean enabled) {
        setIdentifier(identifier);
        setName(name);
        setPriority(priority);
        setEnabled(enabled);
    }

    public String getIdentifier() {
        return identifier.get();
    }

    public void setIdentifier(String identifier) {
        this.identifier.set(normalize(identifier));
    }

    public StringProperty identifierProperty() {
        return identifier;
    }

    public String getName() {
        return name.get();
    }

    public void setName(String name) {
        this.name.set(normalize(name));
    }

    public StringProperty nameProperty() {
        return name;
    }

    public int getPriority() {
        return priority.get();
    }

    public void setPriority(int priority) {
        this.priority.set(Math.max(0, priority));
    }

    public IntegerProperty priorityProperty() {
        return priority;
    }

    public boolean isEnabled() {
        return enabled.get();
    }

    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
    }

    public BooleanProperty enabledProperty() {
        return enabled;
    }

    public PriorityItem withPriority(int newPriority) {
        return new PriorityItem(getIdentifier(), getName(), newPriority, isEnabled());
    }

    public String toConfigString() {
        return String.join(
            FIELD_SEPARATOR,
            getIdentifier(),
            getName(),
            String.valueOf(getPriority()),
            String.valueOf(isEnabled())
        );
    }

    public static PriorityItem fromConfigString(String configString) {
        return parse(configString).orElse(null);
    }

    public static Optional<PriorityItem> parse(String configString) {
        if (configString == null || configString.isBlank()) {
            return Optional.empty();
        }

        String[] fields = configString.trim().split(FIELD_SEPARATOR, -1);
        return switch (fields.length) {
            case LEGACY_FIELD_COUNT -> parseLegacy(fields);
            case CURRENT_FIELD_COUNT -> parseCurrent(fields);
            default -> Optional.empty();
        };
    }

    private static Optional<PriorityItem> parseLegacy(String[] fields) {
        return parsePriority(fields[1])
            .map(priority -> new PriorityItem(fields[0], fields[0], priority, Boolean.parseBoolean(fields[2])));
    }

    private static Optional<PriorityItem> parseCurrent(String[] fields) {
        return parsePriority(fields[2])
            .map(priority -> new PriorityItem(fields[0], fields[1], priority, Boolean.parseBoolean(fields[3])));
    }

    private static Optional<Integer> parsePriority(String rawPriority) {
        try {
            return Optional.of(Integer.parseInt(rawPriority));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public String toString() {
        String state = isEnabled() ? "enabled" : "disabled";
        return "%s [priority %d, %s]".formatted(getName(), getPriority(), state);
    }
}
