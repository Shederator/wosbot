package dev.frostguard.engine.nav;

import dev.frostguard.api.domain.OcrSettingsData;

import java.awt.Color;

// Tesseract presets tuned for reading the left slide-out menu overlay.
// Each configuration targets a specific text colour. Caller must reuse
// frames explicitly if reading multiple regions in one pass.
public final class LeftMenuTextSettings {

    private LeftMenuTextSettings() {}

    private static final String LETTERS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    // alphabetic readers
    public static final OcrSettingsData WHITE_SETTINGS =
            alphabeticPreset(255, 255, 255);

    public static final OcrSettingsData GREEN_TEXT_SETTINGS =
            alphabeticPreset(0, 193, 0);

    public static final OcrSettingsData ORANGE_SETTINGS =
            alphabeticPreset(237, 138, 33);

    public static final OcrSettingsData RED_SETTINGS =
            OcrSettingsData.builder()
                    .setRemoveBackground(true)
                    .setTextColor(new Color(243, 59, 59))
                    .build();

    // numeric readers
    public static final OcrSettingsData WHITE_DURATION =
            numericPreset("0123456789:d");

    public static final OcrSettingsData WHITE_NUMBERS =
            numericPreset("0123456789d");

    public static final OcrSettingsData WHITE_ONLY_NUMBERS =
            numericPreset("0123456789");

    private static OcrSettingsData alphabeticPreset(int r, int g, int b) {
        return OcrSettingsData.builder()
                .setRemoveBackground(true)
                .setTextColor(new Color(r, g, b))
                .setAllowedChars(LETTERS)
                .build();
    }

    private static OcrSettingsData numericPreset(String allowedChars) {
        return OcrSettingsData.builder()
                .setRemoveBackground(true)
                .setTextColor(new Color(255, 255, 255))
                .setAllowedChars(allowedChars)
                .build();
    }
}
