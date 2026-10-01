package dev.frostguard.tasks.economy;

import java.util.Locale;

/** Selects a Storehouse icon search. The live task uses {@link #TEMPLATE}. */
public enum StorehouseIconSearchKind {
    COLOR,
    TEMPLATE;

    public StorehouseIconSearch open(byte[] encodedPng) {
        return switch (this) {
            case COLOR -> new ColorStorehouseSearch();
            case TEMPLATE -> new TemplateStorehouseSearch(encodedPng);
        };
    }

    public static StorehouseIconSearchKind parse(String text) {
        try {
            return valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("--search must be color or template");
        }
    }
}
