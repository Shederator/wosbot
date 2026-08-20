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

    /**
     * Backends supported by the Tesseract recognition engine.
     */
    public enum RecognitionEngine {
        LEGACY_ONLY(0), LSTM_ONLY(1), COMBINED(2), AUTO(3);

        private final int code;

        RecognitionEngine(int code) { this.code = code; }

        /** Numeric identifier passed to the native engine. */
        public int code() { return code; }
    }

    /* ---- immutable configuration fields ---- */

    private final TextLayout textLayout;
    private final boolean isolateForeground;
    private final Color targetColor;
    private final boolean diagnosticMode;
    private final String allowedGlyphs;
    private final RecognitionEngine recognitionEngine;
    private final boolean reuseFrame;
    // Was always hardcoded to "eng" in TesseractOcrProvider
    // regardless of what any caller configured - Whiteout's Alliance/World
    // chat is genuinely multilingual (Arabic/Chinese/Russian/Portuguese/
    // Czech/French all seen live), and an English-only model cannot read
    // non-Latin scripts, it just emits near-random glyph guesses. null keeps
    // every existing caller's behaviour identical (falls back to "eng").
    private final String language;
    private final boolean preserveLineBreaks;

    /* ---- private: construction via Configurator only ---- */

    private OcrSettingsData(Configurator cfg) {
        this.textLayout        = cfg.textLayout;
        this.isolateForeground = cfg.isolateForeground;
        this.targetColor       = cfg.targetColor;
        this.diagnosticMode    = cfg.diagnosticMode;
        this.allowedGlyphs    = cfg.allowedGlyphs;
        this.recognitionEngine = cfg.recognitionEngine;
        this.reuseFrame        = cfg.reuseFrame;
        this.language           = cfg.language;
        this.preserveLineBreaks = cfg.preserveLineBreaks;
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
                .allowedGlyphs(this.allowedGlyphs)
                .recognitionEngine(this.recognitionEngine)
                .reuseFrame(this.reuseFrame)
                .language(this.language)
                .preserveLineBreaks(this.preserveLineBreaks);
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

    /** Tesseract recognition backend, e.g. LSTM_ONLY. Null -> engine default. */
    public RecognitionEngine recognitionEngine()  { return recognitionEngine; }

    /** Whether the previous screen capture should be recycled. */
    public boolean reuseFrame()                  { return reuseFrame; }

    /** Tesseract language code(s), e.g. "eng" or "eng+chi_sim". Null -> caller defaults to "eng". */
    public String language()                     { return language; }

    /** Whether recognized text should keep its original line breaks (default: flattened to one line). */
    public boolean preserveLineBreaks()           { return preserveLineBreaks; }

    /* ---- presence checks ---- */

    public boolean hasTextLayout() { return textLayout != null; }

    public boolean hasGlyphFilter() {
        return allowedGlyphs != null && !allowedGlyphs.isEmpty();
    }

    public boolean hasEngine() { return recognitionEngine != null; }

    /** Numeric OCR-engine-mode code for the native engine, or {@code null} if unset. */
    public Integer getOcrEngineMode() {
        return recognitionEngine != null ? recognitionEngine.code() : null;
    }

    public boolean hasOcrEngineMode() { return hasEngine(); }

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
        private RecognitionEngine recognitionEngine;
        private boolean reuseFrame = false;
        private String language;
        private boolean preserveLineBreaks = false;

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

        public Configurator recognitionEngine(RecognitionEngine engine) {
            this.recognitionEngine = engine;
            return this;
        }

        public Configurator reuseFrame(boolean reuse) {
            this.reuseFrame = reuse;
            return this;
        }

        public Configurator language(String lang) {
            this.language = lang;
            return this;
        }

        public Configurator preserveLineBreaks(boolean preserve) {
            this.preserveLineBreaks = preserve;
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
        public Configurator backend(RecognitionEngine b)      { return recognitionEngine(b); }
        public Configurator setOcrEngineMode(RecognitionEngine b) { return recognitionEngine(b); }

        /** Freezes the current configuration into an immutable instance. */
        public OcrSettingsData build() {
            return new OcrSettingsData(this);
        }
    }
}
