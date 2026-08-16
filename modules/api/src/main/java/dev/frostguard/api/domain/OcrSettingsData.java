package dev.frostguard.api.domain;

import java.awt.Color;
import java.util.Objects;

/**
 * Encapsulates the full set of parameters passed to the OCR recognition engine.
 * Instances are assembled exclusively through the {@link Configurator} fluent builder.
 *
 * <p>Pre-configured factories like {@link #forNumberRecognition()}
 * and {@link #forTextBlock()} cover the most common use cases.</p>
 */
public class OcrSettingsData {

    /**
     * Generic layout hints to inform the OCR engine about the expected
     * structure of the text to be recognized.
     */
    public enum TextLayout {
        SINGLE_LINE,
        SINGLE_WORD,
        TEXT_BLOCK,
        SPARSE,
        AUTO
    }

    /* ---- immutable configuration fields ---- */

    private final TextLayout textLayout;
    private final boolean isolateForeground;
    private final Color targetColor;
    private final boolean diagnosticMode;
    private final String allowedGlyphs;

    /* ---- private: construction via Configurator only ---- */

    private OcrSettingsData(Configurator cfg) {
        this.textLayout        = cfg.textLayout;
        this.isolateForeground = cfg.isolateForeground;
        this.targetColor       = cfg.targetColor;
        this.diagnosticMode    = cfg.diagnosticMode;
        this.allowedGlyphs    = cfg.allowedGlyphs;
    }

    /* ---- pre-configured presets ---- */

    /**
     * Settings optimised for recognising numeric strings
     * (digits, commas, and decimal points only).
     */
    public static OcrSettingsData forNumberRecognition() {
        return configurator()
                .textLayout(TextLayout.SINGLE_LINE)
                .allowedGlyphs("0123456789,.")
                .build();
    }

    /**
     * Settings suitable for reading a uniform block of mixed text.
     */
    public static OcrSettingsData forTextBlock() {
        return configurator()
                .textLayout(TextLayout.TEXT_BLOCK)
                .build();
    }

    /**
     * Settings for recognising isolated single words, such as
     * button labels or short status indicators.
     */
    public static OcrSettingsData forSingleWord() {
        return configurator()
                .textLayout(TextLayout.SINGLE_WORD)
                .build();
    }

    /**
     * Settings for reading white text on dark game backgrounds,
     * with foreground isolation enabled.
     */
    public static OcrSettingsData forWhiteTextOnDark() {
        return configurator()
                .textLayout(TextLayout.SINGLE_LINE)
                .isolateForeground(true)
                .targetColor(Color.WHITE)
                .build();
    }

    /**
     * Returns {@code true} when this settings instance uses all
     * default (unset) values — no layout hint, no
     * foreground isolation, and no glyph filter.
     */
    public boolean isDefaultConfiguration() {
        return textLayout == null
                && !isolateForeground
                && targetColor == null
                && !diagnosticMode
                && (allowedGlyphs == null || allowedGlyphs.isEmpty());
    }

    /**
     * Creates a new {@link Configurator} pre-populated with this
     * instance's values, allowing incremental modification.
     *
     * @return a mutable configurator seeded with current settings
     */
    public Configurator toConfigurator() {
        return new Configurator()
                .textLayout(this.textLayout)
                .isolateForeground(this.isolateForeground)
                .targetColor(this.targetColor)
                .diagnosticMode(this.diagnosticMode)
                .allowedGlyphs(this.allowedGlyphs);
    }

    /* ---- primary accessors ---- */

    /** Expected layout of the text. */
    public TextLayout textLayout()               { return textLayout; }

    /** Whether background removal is applied before recognition. */
    public boolean isolateForeground()           { return isolateForeground; }

    /** Hint colour used during foreground isolation. */
    public Color targetColor()                   { return targetColor; }

    /** Whether verbose debug output is enabled. */
    public boolean diagnosticMode()              { return diagnosticMode; }

    /** Restricted character set for recognition (whitelist). */
    public String allowedGlyphs()                { return allowedGlyphs; }

    /* ---- presence checks ---- */

    public boolean hasTextLayout() { return textLayout != null; }

    public boolean hasGlyphFilter() {
        return allowedGlyphs != null && !allowedGlyphs.isEmpty();
    }

    /* ---------- backward-compatible accessor shims ---------- */

    public boolean shouldStripBackground()  { return isolateForeground; }
    public Color getForegroundHint()        { return targetColor; }
    public boolean isVerbose()              { return diagnosticMode; }
    public String getCharWhitelist()        { return allowedGlyphs; }
    public boolean hasCharWhitelist()       { return hasGlyphFilter(); }
    public boolean isRemoveBackground()     { return isolateForeground; }
    public Color getTextColor()             { return targetColor; }
    public boolean isDebug()                { return diagnosticMode; }
    public String getAllowedChars()         { return allowedGlyphs; }
    public boolean hasAllowedChars()        { return hasGlyphFilter(); }
    /* ---- factory entry points ---- */

    /** Creates a fresh configurator for building settings instances. */
    public static Configurator configurator() { return new Configurator(); }
    public static Configurator assembler()    { return configurator(); }
    public static Configurator builder()      { return configurator(); }

    /* ---- identity ---- */

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof OcrSettingsData that)) return false;
        return isolateForeground == that.isolateForeground
            && diagnosticMode    == that.diagnosticMode
            && textLayout        == that.textLayout
            && Objects.equals(targetColor,   that.targetColor)
            && Objects.equals(allowedGlyphs, that.allowedGlyphs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                textLayout, isolateForeground,
                targetColor, diagnosticMode, allowedGlyphs);
    }

    @Override
    public String toString() {
        return "OCR{layout=" + textLayout
                + ", fgIsolation=" + isolateForeground
                + ", glyphs=" + allowedGlyphs + "}";
    }

    /**
     * Step-by-step builder for assembling {@link OcrSettingsData}
     * instances with a fluent API.
     */
    public static class Configurator {

        private TextLayout textLayout;
        private boolean isolateForeground;
        private Color targetColor;
        private boolean diagnosticMode;
        private String allowedGlyphs;

        /* ---- primary setters ---- */

        public Configurator textLayout(TextLayout layout) {
            this.textLayout = layout;
            return this;
        }

        public Configurator isolateForeground(boolean isolate) {
            this.isolateForeground = isolate;
            return this;
        }

        public Configurator targetColor(Color color) {
            this.targetColor = color;
            return this;
        }

        public Configurator diagnosticMode(boolean enabled) {
            this.diagnosticMode = enabled;
            return this;
        }

        public Configurator allowedGlyphs(String glyphs) {
            this.allowedGlyphs = glyphs;
            return this;
        }

        /* ---- backward-compatible setter aliases ---- */

        public Configurator stripBackground(boolean s)        { return isolateForeground(s); }
        public Configurator foregroundHint(Color c)           { return targetColor(c); }
        public Configurator verbose(boolean v)                { return diagnosticMode(v); }
        public Configurator charWhitelist(String c)           { return allowedGlyphs(c); }
        public Configurator setRemoveBackground(boolean r)    { return isolateForeground(r); }
        public Configurator setTextColor(Color c)             { return targetColor(c); }
        public Configurator setDebug(boolean d)               { return diagnosticMode(d); }
        public Configurator setAllowedChars(String c)         { return allowedGlyphs(c); }

        /** Freezes the current configuration into an immutable instance. */
        public OcrSettingsData build() {
            return new OcrSettingsData(this);
        }
    }
}
