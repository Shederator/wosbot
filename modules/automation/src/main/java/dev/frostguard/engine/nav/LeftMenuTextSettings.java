package dev.frostguard.engine.nav;

import dev.frostguard.api.domain.TesseractSettingsData;

import java.awt.Color;

// Tesseract presets tuned for reading the left slide-out menu overlay.
// Each configuration targets a specific text colour while reusing the
// previously captured screen image.
public final class LeftMenuTextSettings {

    private LeftMenuTextSettings() {}

    private static final String LETTERS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

    // alphabetic readers
    public static final TesseractSettingsData WHITE_SETTINGS =
            alphabeticPreset(255, 255, 255);

    public static final TesseractSettingsData GREEN_TEXT_SETTINGS =
            alphabeticPreset(0, 193, 0);

    public static final TesseractSettingsData ORANGE_SETTINGS =
            alphabeticPreset(237, 138, 33);

    public static final TesseractSettingsData RED_SETTINGS =
            TesseractSettingsData.builder()
                    .setRemoveBackground(true)
                    .setTextColor(new Color(243, 59, 59))
                    .setReuseLastImage(true)
                    .build();

    // numeric readers
    public static final TesseractSettingsData WHITE_DURATION =
            numericPreset("0123456789:d");

    public static final TesseractSettingsData WHITE_NUMBERS =
            numericPreset("0123456789d");

    public static final TesseractSettingsData WHITE_ONLY_NUMBERS =
            numericPreset("0123456789");

    private static TesseractSettingsData alphabeticPreset(int r, int g, int b) {
        return TesseractSettingsData.builder()
                .setRemoveBackground(true)
                .setTextColor(new Color(r, g, b))
                .setReuseLastImage(true)
                .setAllowedChars(LETTERS)
                .build();
    }

    private static TesseractSettingsData numericPreset(String allowedChars) {
        return TesseractSettingsData.builder()
                .setRemoveBackground(true)
                .setTextColor(new Color(255, 255, 255))
                .setReuseLastImage(true)
                .setAllowedChars(allowedChars)
                .build();
    }
}
