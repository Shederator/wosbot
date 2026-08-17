package dev.frostguard.app.panel.profile;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class ConfigAux {

    private enum Cell {
        KEY,
        STORED_VALUE
    }

    private final EnumMap<Cell, String> cells = new EnumMap<>(Cell.class);

    public ConfigAux() {
        this(null, null);
    }

    public ConfigAux(String name, String value) {
        cells.put(Cell.KEY, tidy(name));
        cells.put(Cell.STORED_VALUE, Objects.toString(value, ""));
    }

    public String getName() { return read(Cell.KEY); }

    public void setName(String name) { cells.put(Cell.KEY, tidy(name)); }

    public String getValue() { return read(Cell.STORED_VALUE); }

    public void setValue(String value) { cells.put(Cell.STORED_VALUE, Objects.toString(value, "")); }

    public boolean hasName(String expectedName) { return tidy(expectedName).equalsIgnoreCase(getName()); }

    public boolean hasValue() { return !getValue().isBlank(); }

    public Optional<Integer> asInteger() {
        try {
            return Optional.of(Integer.parseInt(getValue()));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    public Optional<Boolean> asBoolean() {
        String raw = getValue();
        return switch (raw.toLowerCase()) {
            case "true", "false" -> Optional.of(Boolean.parseBoolean(raw));
            default -> Optional.empty();
        };
    }

    public void applyTo(Map<String, String> destination) {
        if (destination != null && !getName().isBlank()) {
            destination.put(getName(), getValue());
        }
    }

    public ConfigAux copy() { return new ConfigAux(getName(), getValue()); }

    public void clear() {
        cells.replaceAll((slot, ignored) -> "");
    }

    private String read(Cell cell) { return cells.getOrDefault(cell, ""); }

    private static String tidy(String text) { return Objects.toString(text, "").trim(); }

    @Override
    public String toString() {
        return "profile-config{%s -> %s}".formatted(getName(), getValue());
    }
}
