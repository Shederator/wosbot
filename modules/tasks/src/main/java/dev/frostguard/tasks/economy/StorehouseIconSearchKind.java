package dev.frostguard.tasks.economy;

import java.util.Locale;

/** Selects a Storehouse icon search. The live task uses {@link #COLOR}. */
public enum StorehouseIconSearchKind {
    COLOR,
    TEMPLATE,
    CHEST3;

    public StorehouseIconSearch open(byte[] encodedPng) {
        return switch (this) {
            case COLOR -> new ColorStorehouseSearch();
            case TEMPLATE -> new TemplateStorehouseSearch(encodedPng);
            case CHEST3 -> TemplateStorehouseSearch.bubbleCrop(encodedPng);
        };
    }

    public static StorehouseIconSearchKind parse(String text) {
        String normalized = text.toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("--search must be color, template, or chest3");
        }
    }
}
