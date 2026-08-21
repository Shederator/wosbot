package dev.frostguard.engine.nav;

import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;

import java.awt.Color;
import java.util.regex.Pattern;

// Shared OCR presets and regex helpers for OCR-based game-state readers.
public final class CommonOCRSettings {

    private CommonOCRSettings() {}

    // stamina fraction: "123/500" style
    public static final OcrSettingsData STAMINA_FRACTION_SETTINGS =
            buildConfig("0123456789/", true, 255, 255, 255, TextLayout.SINGLE_LINE);

    // spent stamina: digits only, near-white foreground
    public static final OcrSettingsData SPENT_STAMINA_SETTINGS =
            buildSpentStaminaConfig();

    // travel time: "12:34:56" in white, next to a clock icon that has no white pixel at all
    public static final OcrSettingsData TRAVEL_TIME_SETTINGS =
            buildConfig("0123456789:", true, 255, 255, 255, TextLayout.SINGLE_LINE);

    // march queue countdown: "00:01:53" in white on top of the progress bar
    public static final OcrSettingsData MARCH_QUEUE_TIMER_SETTINGS =
            buildConfig("0123456789:", true, 255, 255, 255, TextLayout.SINGLE_LINE);

    public static final OcrSettingsData INTEL_COOLDOWN_SETTINGS =
            buildConfig("0123456789:", true, 255, 255, 255, TextLayout.SINGLE_LINE);

    // Daily sidebar status: "Intel Gain: 8" in green.
    public static final OcrSettingsData INTEL_GAIN_SETTINGS =
            buildConfig("IntelGain: 0123456789", true, 0, 193, 0, TextLayout.SINGLE_LINE);

    // red cooldown clock, isolated from illustrated skill backgrounds
    public static final OcrSettingsData RED_DURATION_SETTINGS =
            buildConfig("0123456789:", true, 243, 59, 59, TextLayout.SINGLE_LINE);

    // Keep raw anti-aliased glyphs: colour isolation drops the slender "1" in wrapped "1d" timers.
    public static final OcrSettingsData RED_MULTILINE_DURATION_SETTINGS =
            buildConfig("0123456789d:", false, 0, 0, 0, TextLayout.SPARSE);

    // polar terror level: dark slate digits inside a pale pill
    public static final OcrSettingsData POLAR_LEVEL_SETTINGS =
            OcrSettingsData.assembler()
                    .charWhitelist("0123456789")
                    .textLayout(TextLayout.SINGLE_LINE)
                    .stripBackground(true)
                    .setTextColor(new Color(66, 84, 108))
                    .build();

    // special rewards heading: "Special Rewards (8 left)" in white on a blue section bar
    public static final OcrSettingsData POLAR_SPECIAL_REWARDS_SETTINGS =
            buildConfig("SpecialRewardsleft0123456789() ", true,
                    255, 255, 255, TextLayout.SINGLE_LINE);

    // extraction pattern for pulling first integer from noisy OCR text
    public static final Pattern NUMBER_PATTERN = Pattern.compile(".*?(\\d+).*");

    private static OcrSettingsData buildConfig(String glyphs, boolean isolate,
                                               int r, int g, int b,
                                               TextLayout layout) {
        OcrSettingsData.Configurator builder = OcrSettingsData.builder()
                .allowedGlyphs(glyphs)
                .textLayout(layout);
        if (isolate) builder.isolateForeground(true).targetColor(new Color(r, g, b));
        return builder.build();
    }

    private static OcrSettingsData buildSpentStaminaConfig() {
        return OcrSettingsData.builder()
                .textLayout(TextLayout.SINGLE_LINE)
                .isolateForeground(true)
                .targetColor(new Color(254, 254, 254))
                .allowedGlyphs("0123456789")
                .build();
    }
}
