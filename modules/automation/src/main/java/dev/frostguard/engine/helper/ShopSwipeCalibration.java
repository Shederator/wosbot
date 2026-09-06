package dev.frostguard.engine.helper;

import java.util.Map;
import java.util.function.Consumer;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.service.ConfigService;

/** Resolves persisted swipe calibration for the ordered Shop footer. */
final class ShopSwipeCalibration {

    private static final int MAX_X = 719;
    private static final int MAX_Y = 1279;
    private static final int MAX_DURATION_MS = 10_000;
    private static final int MAX_SETTLE_MS = 30_000;

    private final Gesture first;
    private final Gesture followUp;

    private ShopSwipeCalibration(Gesture first, Gesture followUp) {
        this.first = first;
        this.followUp = followUp;
    }

    static ShopSwipeCalibration load(Consumer<String> warn) {
        Map<String, String> settings = ConfigService.obtain().loadGlobalSettings();
        return from(settings == null ? Map.of() : settings, warn);
    }

    static ShopSwipeCalibration defaults() {
        return from(Map.of(), ignored -> { });
    }

    static ShopSwipeCalibration from(Map<String, String> settings, Consumer<String> warn) {
        Map<String, String> safeSettings = settings == null ? Map.of() : settings;
        Consumer<String> safeWarn = warn == null ? ignored -> { } : warn;
        return new ShopSwipeCalibration(
                readGesture(safeSettings, safeWarn,
                        ConfigurationKeyEnum.SHOP_NAVIGATION_FIRST_SWIPE_FROM_X_INT,
                        ConfigurationKeyEnum.SHOP_NAVIGATION_FIRST_SWIPE_FROM_Y_INT,
                        ConfigurationKeyEnum.SHOP_NAVIGATION_FIRST_SWIPE_TO_X_INT,
                        ConfigurationKeyEnum.SHOP_NAVIGATION_FIRST_SWIPE_TO_Y_INT,
                        ConfigurationKeyEnum.SHOP_NAVIGATION_FIRST_SWIPE_DURATION_MS_INT,
                        ConfigurationKeyEnum.SHOP_NAVIGATION_FIRST_SWIPE_SETTLE_MS_INT),
                readGesture(safeSettings, safeWarn,
                        ConfigurationKeyEnum.SHOP_NAVIGATION_FOLLOW_UP_SWIPE_FROM_X_INT,
                        ConfigurationKeyEnum.SHOP_NAVIGATION_FOLLOW_UP_SWIPE_FROM_Y_INT,
                        ConfigurationKeyEnum.SHOP_NAVIGATION_FOLLOW_UP_SWIPE_TO_X_INT,
                        ConfigurationKeyEnum.SHOP_NAVIGATION_FOLLOW_UP_SWIPE_TO_Y_INT,
                        ConfigurationKeyEnum.SHOP_NAVIGATION_FOLLOW_UP_SWIPE_DURATION_MS_INT,
                        ConfigurationKeyEnum.SHOP_NAVIGATION_FOLLOW_UP_SWIPE_SETTLE_MS_INT));
    }

    Gesture forAttempt(int attempt) {
        return attempt <= 1 ? first : followUp;
    }

    private static Gesture readGesture(Map<String, String> settings, Consumer<String> warn,
            ConfigurationKeyEnum fromXKey, ConfigurationKeyEnum fromYKey,
            ConfigurationKeyEnum toXKey, ConfigurationKeyEnum toYKey,
            ConfigurationKeyEnum durationKey, ConfigurationKeyEnum settleKey) {
        PointData from = new PointData(
                readInt(settings, warn, fromXKey, 0, MAX_X),
                readInt(settings, warn, fromYKey, 0, MAX_Y));
        PointData to = new PointData(
                readInt(settings, warn, toXKey, 0, MAX_X),
                readInt(settings, warn, toYKey, 0, MAX_Y));
        int durationMs = readInt(settings, warn, durationKey, 0, MAX_DURATION_MS);
        int settleMs = readInt(settings, warn, settleKey, 0, MAX_SETTLE_MS);

        if (from.equals(to)) {
            warn.accept("Invalid shop swipe calibration has identical endpoints; using defaults for "
                    + fromXKey.name().replace("_FROM_X_INT", ""));
            return defaultGesture(fromXKey, fromYKey, toXKey, toYKey, durationKey, settleKey);
        }
        return new Gesture(from, to, durationMs, settleMs);
    }

    private static Gesture defaultGesture(ConfigurationKeyEnum fromXKey, ConfigurationKeyEnum fromYKey,
            ConfigurationKeyEnum toXKey, ConfigurationKeyEnum toYKey,
            ConfigurationKeyEnum durationKey, ConfigurationKeyEnum settleKey) {
        return new Gesture(
                new PointData(fromXKey.defaultAsInt(), fromYKey.defaultAsInt()),
                new PointData(toXKey.defaultAsInt(), toYKey.defaultAsInt()),
                durationKey.defaultAsInt(), settleKey.defaultAsInt());
    }

    private static int readInt(Map<String, String> settings, Consumer<String> warn,
            ConfigurationKeyEnum key, int minimum, int maximum) {
        int fallback = key.defaultAsInt();
        String raw = settings.get(key.name());
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value >= minimum && value <= maximum) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // The warning below reports the invalid persisted value and fallback.
        }
        warn.accept("Invalid shop swipe calibration: key=" + key.name() + " value='" + raw
                + "' allowed=" + minimum + ".." + maximum + " fallback=" + fallback);
        return fallback;
    }

    record Gesture(PointData from, PointData to, int durationMs, int settleMs) {

        Gesture reversed() {
            return new Gesture(to, from, durationMs, settleMs);
        }

        String durationDescription() {
            return durationMs == 0 ? "default" : durationMs + "ms";
        }
    }
}
