package dev.frostguard.api.domain;

import java.awt.Color;
import java.util.Objects;

/**
 * Encapsulates the full set of parameters passed to the Tesseract
 * OCR recognition engine. Instances are assembled exclusively
 * through the {@link Configurator} fluent builder.
 *
 * <p>Pre-configured factories like {@link #forNumberRecognition()}
 * and {@link #forTextBlock()} cover the most common use cases.</p>
 */
public class TesseractSettingsData {

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

    /**
     * Page segmentation strategies that control how Tesseract
     * identifies text regions within an image.
     */
    public enum PageAnalysis {
        OSD_ONLY(0), AUTO_WITH_OSD(1), AUTO_NO_OSD(2), FULLY_AUTO(3),
        SINGLE_COLUMN(4), VERTICAL_BLOCK(5), UNIFORM_BLOCK(6),
        SINGLE_LINE(7), SINGLE_WORD(8), CIRCULAR_WORD(9),
        SINGLE_GLYPH(10), SPARSE(11), SPARSE_WITH_OSD(12), RAW_LINE(13);

        private final int code;

        PageAnalysis(int code) { this.code = code; }

        /** Numeric identifier passed to the native engine. */
        public int code() { return code; }
    }

    /* ---- immutable configuration fields ---- */

    private final PageAnalysis pageAnalysis;
    private final RecognitionEngine recognitionEngine;
    private final boolean isolateForeground;
    private final Color targetColor;
    private final boolean diagnosticMode;
    private final String allowedGlyphs;
    private final boolean reuseFrame;

    /* ---- private: construction via Configurator only ---- */

    private TesseractSettingsData(Configurator cfg) {
        this.pageAnalysis      = cfg.pageAnalysis;
        this.recognitionEngine = cfg.recognitionEngine;
        this.isolateForeground = cfg.isolateForeground;
        this.targetColor       = cfg.targetColor;
        this.diagnosticMode    = cfg.diagnosticMode;
        this.allowedGlyphs    = cfg.allowedGlyphs;
        this.reuseFrame        = cfg.reuseFrame;
    }

    /* ---- pre-configured presets ---- */

    /**
     * Settings optimised for recognising numeric strings
     * (digits, commas, and decimal points only).
     */
    public static TesseractSettingsData forNumberRecognition() {
        return configurator()
                .pageAnalysis(PageAnalysis.SINGLE_LINE)
                .recognitionEngine(RecognitionEngine.LSTM_ONLY)
                .allowedGlyphs("0123456789,.")
                .build();
    }

    /**
     * Settings suitable for reading a uniform block of mixed text.
     */
    public static TesseractSettingsData forTextBlock() {
        return configurator()
                .pageAnalysis(PageAnalysis.UNIFORM_BLOCK)
                .recognitionEngine(RecognitionEngine.LSTM_ONLY)
                .build();
    }

    /**
     * Settings for recognising isolated single words, such as
     * button labels or short status indicators.
     */
    public static TesseractSettingsData forSingleWord() {
        return configurator()
                .pageAnalysis(PageAnalysis.SINGLE_WORD)
                .recognitionEngine(RecognitionEngine.LSTM_ONLY)
                .build();
    }

    /**
     * Settings for reading white text on dark game backgrounds,
     * with foreground isolation enabled.
     */
    public static TesseractSettingsData forWhiteTextOnDark() {
        return configurator()
                .pageAnalysis(PageAnalysis.SINGLE_LINE)
                .recognitionEngine(RecognitionEngine.LSTM_ONLY)
                .isolateForeground(true)
                .targetColor(Color.WHITE)
                .build();
    }

    /**
     * Returns {@code true} when this settings instance uses all
     * default (unset) values — no page analysis, no engine, no
     * foreground isolation, and no glyph filter.
     */
    public boolean isDefaultConfiguration() {
        return pageAnalysis == null
                && recognitionEngine == null
                && !isolateForeground
                && targetColor == null
                && !diagnosticMode
                && (allowedGlyphs == null || allowedGlyphs.isEmpty())
                && !reuseFrame;
    }

    /**
     * Creates a new {@link Configurator} pre-populated with this
     * instance's values, allowing incremental modification.
     *
     * @return a mutable configurator seeded with current settings
     */
    public Configurator toConfigurator() {
        return new Configurator()
                .pageAnalysis(this.pageAnalysis)
                .recognitionEngine(this.recognitionEngine)
                .isolateForeground(this.isolateForeground)
                .targetColor(this.targetColor)
                .diagnosticMode(this.diagnosticMode)
                .allowedGlyphs(this.allowedGlyphs)
                .reuseFrame(this.reuseFrame);
    }

    /* ---- primary accessors ---- */

    /** Active page segmentation strategy. */
    public PageAnalysis pageAnalysis()           { return pageAnalysis; }

    /** Active recognition backend. */
    public RecognitionEngine recognitionEngine()  { return recognitionEngine; }

    /** Whether background removal is applied before recognition. */
    public boolean isolateForeground()           { return isolateForeground; }

    /** Hint colour used during foreground isolation. */
    public Color targetColor()                   { return targetColor; }

    /** Whether verbose debug output is enabled. */
    public boolean diagnosticMode()              { return diagnosticMode; }

    /** Restricted character set for recognition (whitelist). */
    public String allowedGlyphs()                { return allowedGlyphs; }

    /** Whether the previous screen capture should be recycled. */
    public boolean reuseFrame()                  { return reuseFrame; }

    /* ---- presence checks ---- */

    public boolean hasPageAnalysis() { return pageAnalysis != null; }
    public boolean hasEngine()       { return recognitionEngine != null; }

    public boolean hasGlyphFilter() {
        return allowedGlyphs != null && !allowedGlyphs.isEmpty();
    }

    /** Returns the numeric code of the page analysis mode, or {@code null}. */
    public Integer pageAnalysisCode() {
        return pageAnalysis != null ? pageAnalysis.code() : null;
    }

    /** Returns the numeric code of the engine backend, or {@code null}. */
    public Integer engineCode() {
        return recognitionEngine != null ? recognitionEngine.code() : null;
    }

    /* ---------- backward-compatible accessor shims ---------- */

    public Integer segmentationCode()       { return pageAnalysisCode(); }
    public Integer backendCode()            { return engineCode(); }
    public boolean shouldStripBackground()  { return isolateForeground; }
    public Color getForegroundHint()        { return targetColor; }
    public boolean isVerbose()              { return diagnosticMode; }
    public boolean hasSegmentation()        { return hasPageAnalysis(); }
    public boolean hasBackend()             { return hasEngine(); }
    public String getCharWhitelist()        { return allowedGlyphs; }
    public boolean hasCharWhitelist()       { return hasGlyphFilter(); }
    public boolean shouldRecycleCapture()   { return reuseFrame; }
    public Integer getPageSegMode()         { return pageAnalysisCode(); }
    public Integer getOcrEngineMode()       { return engineCode(); }
    public boolean isRemoveBackground()     { return isolateForeground; }
    public Color getTextColor()             { return targetColor; }
    public boolean isDebug()                { return diagnosticMode; }
    public boolean hasPageSegMode()         { return hasPageAnalysis(); }
    public boolean hasOcrEngineMode()       { return hasEngine(); }
    public String getAllowedChars()         { return allowedGlyphs; }
    public boolean hasAllowedChars()        { return hasGlyphFilter(); }
    public boolean isReuseLastImage()       { return reuseFrame; }

    /* ---- factory entry points ---- */

    /** Creates a fresh configurator for building settings instances. */
    public static Configurator configurator() { return new Configurator(); }
    public static Configurator assembler()    { return configurator(); }
    public static Configurator builder()      { return configurator(); }

    /* ---- identity ---- */

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof TesseractSettingsData that)) return false;
        return isolateForeground == that.isolateForeground
            && diagnosticMode    == that.diagnosticMode
            && reuseFrame        == that.reuseFrame
            && pageAnalysis      == that.pageAnalysis
            && recognitionEngine == that.recognitionEngine
            && Objects.equals(targetColor,   that.targetColor)
            && Objects.equals(allowedGlyphs, that.allowedGlyphs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                pageAnalysis, recognitionEngine, isolateForeground,
                targetColor, diagnosticMode, allowedGlyphs, reuseFrame);
    }

    @Override
    public String toString() {
        return "OCR{page=" + pageAnalysis
                + ", engine=" + recognitionEngine
                + ", fgIsolation=" + isolateForeground
                + ", glyphs=" + allowedGlyphs + "}";
    }

    /**
     * Step-by-step builder for assembling {@link TesseractSettingsData}
     * instances with a fluent API.
     */
    public static class Configurator {

        private PageAnalysis pageAnalysis;
        private RecognitionEngine recognitionEngine;
        private boolean isolateForeground;
        private Color targetColor;
        private boolean diagnosticMode;
        private String allowedGlyphs;
        private boolean reuseFrame = false;

        /* ---- primary setters ---- */

        public Configurator pageAnalysis(PageAnalysis mode) {
            this.pageAnalysis = mode;
            return this;
        }

        public Configurator recognitionEngine(RecognitionEngine engine) {
            this.recognitionEngine = engine;
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

        public Configurator reuseFrame(boolean reuse) {
            this.reuseFrame = reuse;
            return this;
        }

        /* ---- backward-compatible setter aliases ---- */

        public Configurator segmentation(PageAnalysis m)      { return pageAnalysis(m); }
        public Configurator backend(RecognitionEngine b)      { return recognitionEngine(b); }
        public Configurator stripBackground(boolean s)        { return isolateForeground(s); }
        public Configurator foregroundHint(Color c)           { return targetColor(c); }
        public Configurator verbose(boolean v)                { return diagnosticMode(v); }
        public Configurator charWhitelist(String c)           { return allowedGlyphs(c); }
        public Configurator recycleCapture(boolean r)         { return reuseFrame(r); }
        public Configurator setPageSegMode(PageAnalysis m)    { return pageAnalysis(m); }
        public Configurator setOcrEngineMode(RecognitionEngine b) { return recognitionEngine(b); }
        public Configurator setRemoveBackground(boolean r)    { return isolateForeground(r); }
        public Configurator setTextColor(Color c)             { return targetColor(c); }
        public Configurator setDebug(boolean d)               { return diagnosticMode(d); }
        public Configurator setAllowedChars(String c)         { return allowedGlyphs(c); }
        public Configurator setReuseLastImage(boolean r)      { return reuseFrame(r); }

        /** Freezes the current configuration into an immutable instance. */
        public TesseractSettingsData build() {
            return new TesseractSettingsData(this);
        }
    }
}
