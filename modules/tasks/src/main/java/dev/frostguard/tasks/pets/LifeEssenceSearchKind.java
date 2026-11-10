package dev.frostguard.tasks.pets;

import java.util.Locale;

/** Selects a leaf search. The live task uses {@link #COLOR}. */
public enum LifeEssenceSearchKind {
    COLOR,
    TEMPLATE,
    BLOB;

    public LifeEssenceLeafSearch open(byte[] encodedPng) {
        return switch (this) {
            case COLOR -> new ColorLifeEssenceSearch();
            case TEMPLATE -> new TemplateLifeEssenceSearch(encodedPng);
            case BLOB -> new BlobLifeEssenceSearch();
        };
    }

    public static LifeEssenceSearchKind parse(String text) {
        try {
            return valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("--search must be color, template or blob");
        }
    }
}
