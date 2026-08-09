package dev.frostguard.engine.nav;

import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.api.domain.TesseractSettingsData.PageAnalysis;
import dev.frostguard.api.domain.TesseractSettingsData.RecognitionEngine;

import java.awt.Color;
import java.util.regex.Pattern;

// Shared Tesseract presets and regex helpers for OCR-based game-state readers.
public final class CommonOCRSettings {

    private CommonOCRSettings() {}

    // stamina fraction: "123/500" style
    public static final TesseractSettingsData STAMINA_FRACTION_SETTINGS =
            buildLstmConfig("0123456789/", true, 255, 255, 255, PageAnalysis.SINGLE_LINE);

    // spent stamina: digits only, near-white foreground
    public static final TesseractSettingsData SPENT_STAMINA_SETTINGS =
            buildSpentStaminaConfig();

    // travel time: "12:34:56" in white, next to a clock icon that has no white pixel at all
    public static final TesseractSettingsData TRAVEL_TIME_SETTINGS =
            buildLstmConfig("0123456789:", true, 255, 255, 255, PageAnalysis.SINGLE_LINE);

    // march queue countdown: "00:01:53" in white on top of the progress bar
    public static final TesseractSettingsData MARCH_QUEUE_TIMER_SETTINGS =
            buildLstmConfig("0123456789:", true, 255, 255, 255, PageAnalysis.SINGLE_LINE);

    public static final TesseractSettingsData INTEL_COOLDOWN_SETTINGS =
            buildLstmConfig("0123456789:", true, 255, 255, 255, PageAnalysis.SINGLE_LINE);

    // polar terror level: dark slate digits inside a pale pill
    public static final TesseractSettingsData POLAR_LEVEL_SETTINGS =
            TesseractSettingsData.assembler()
                    .charWhitelist("0123456789")
                    .pageAnalysis(PageAnalysis.SINGLE_LINE)
                    .stripBackground(true)
                    .setTextColor(new Color(66, 84, 108))
                    .build();

    // special rewards heading: "Special Rewards (8 left)" in white on a blue section bar
    public static final TesseractSettingsData POLAR_SPECIAL_REWARDS_SETTINGS =
            buildLstmConfig("SpecialRewardsleft0123456789() ", true,
                    255, 255, 255, PageAnalysis.SINGLE_LINE);

    // extraction pattern for pulling first integer from noisy OCR text
    public static final Pattern NUMBER_PATTERN = Pattern.compile(".*?(\\d+).*");

    private static TesseractSettingsData buildLstmConfig(String glyphs, boolean isolate,
                                                          int r, int g, int b,
                                                          PageAnalysis pageMode) {
        TesseractSettingsData.Configurator builder = TesseractSettingsData.builder()
                .allowedGlyphs(glyphs)
                .pageAnalysis(pageMode)
                .recognitionEngine(RecognitionEngine.LSTM_ONLY);
        if (isolate) builder.isolateForeground(true).targetColor(new Color(r, g, b));
        return builder.build();
    }

    private static TesseractSettingsData buildSpentStaminaConfig() {
        return TesseractSettingsData.builder()
                .pageAnalysis(PageAnalysis.SINGLE_WORD)
                .recognitionEngine(RecognitionEngine.LSTM_ONLY)
                .isolateForeground(true)
                .targetColor(new Color(254, 254, 254))
                .allowedGlyphs("0123456789")
                .build();
    }
}
