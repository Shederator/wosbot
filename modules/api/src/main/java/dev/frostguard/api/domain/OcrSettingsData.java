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
    // Language used to be hardcoded to "eng" in TesseractOcrProvider regardless of what any
    // caller configured - Whiteout's Alliance/World chat is genuinely multilingual and an
    // English-only model cannot read non-Latin scripts, it just emits near-random glyph guesses.
    // Only "eng" and "chi_sim" have trained-data models actually packaged with the app (see
    // TesseractOcrProvider#SUPPORTED_LANGUAGES). null (every pre-existing caller, since none of
    // them ever set this field) keeps behaviour identical -- falls back to "eng". A non-null
    // request for a language with no packaged model fails loudly instead
    // (UnsupportedOcrLanguageException) rather than silently substituting "eng": a caller that
    // explicitly asked for a specific script deserves an honest failure, not confident-looking
    // garbage from running the wrong model against it.
    //
    // The consumer this exists for is chat reading. A chat panel is the one screen that is
    // routinely not in the operator's own language, and reading it with the English model yields
    // confident nonsense rather than an error, so the language has to be selectable per call site
    // rather than fixed for the process. That reader is proposed separately; until it lands this
    // field simply stays unset, which is what every existing call site already does.
    private final String language;
    private final boolean preserveLineBreaks;

    /* ---- private: construction via Configurator only ---- */

    private OcrSettingsData(Configurator cfg) {
        this.textLayout        = cfg.textLayout;
        this.isolateForeground = cfg.isolateForeground;
        this.targetColor       = cfg.targetColor;
        this.diagnosticMode    = cfg.diagnosticMode;
        this.allowedGlyphs    = cfg.allowedGlyphs;
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
                && (allowedGlyphs == null || allowedGlyphs.isEmpty())
                && language == null
                && !preserveLineBreaks;
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

    /**
     * Tesseract language code(s), e.g. {@code "eng"} or {@code "eng+chi_sim"}. Null means the caller
     * never asked, and recognition defaults to {@code "eng"}.
     *
     * <p>It exists for reading multi-line, mixed-language player text -- world and alliance chat,
     * where one screen routinely carries Latin, Cyrillic, Arabic and Chinese at once and an
     * English-only model returns confident nonsense for most of it. The per-message chat reader
     * that consumes it is proposed separately and depends on this change; until that lands, a null
     * language with {@code preserveLineBreaks == false} reproduces exactly the behaviour every
     * existing call site had before these options existed.
     *
     * <p>Recorded here so the next reader does not mistake an option with no current call site for
     * dead configuration. The language cannot be a process-wide setting: the same run reads English
     * countdowns and non-Latin chat minutes apart, so it has to be chosen per call site.
     */
    public String language()                     { return language; }

    /**
     * Whether recognised text keeps its original line breaks; the default flattens it to one line.
     *
     * <p>Same status as {@link #language()} -- see there. It matters for the same case: a chat
     * panel is inherently multi-line, and flattening it destroys the message boundaries that make
     * the text usable at all.
     */
    public boolean preserveLineBreaks()           { return preserveLineBreaks; }

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
            && preserveLineBreaks == that.preserveLineBreaks
            && textLayout        == that.textLayout
            && Objects.equals(targetColor,   that.targetColor)
            && Objects.equals(allowedGlyphs, that.allowedGlyphs)
            && Objects.equals(language,      that.language);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                textLayout, isolateForeground,
                targetColor, diagnosticMode, allowedGlyphs,
                language, preserveLineBreaks);
    }

    @Override
    public String toString() {
        return "OCR{layout=" + textLayout
                + ", fgIsolation=" + isolateForeground
                + ", glyphs=" + allowedGlyphs
                + ", language=" + language
                + ", preserveLineBreaks=" + preserveLineBreaks + "}";
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

        /** Freezes the current configuration into an immutable instance. */
        public OcrSettingsData build() {
            return new OcrSettingsData(this);
        }
    }
}
