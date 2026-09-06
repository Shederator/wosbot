package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.domain.PointData;

class ShopSwipeCalibrationTest {

    @Test
    void usesIndependentFirstAndFollowUpGestureSettings() {
        Map<String, String> settings = new HashMap<>();
        settings.put(ConfigurationKeyEnum.SHOP_NAVIGATION_FIRST_SWIPE_FROM_X_INT.name(), "610");
        settings.put(ConfigurationKeyEnum.SHOP_NAVIGATION_FIRST_SWIPE_TO_X_INT.name(), "420");
        settings.put(ConfigurationKeyEnum.SHOP_NAVIGATION_FIRST_SWIPE_DURATION_MS_INT.name(), "275");
        settings.put(ConfigurationKeyEnum.SHOP_NAVIGATION_FIRST_SWIPE_SETTLE_MS_INT.name(), "1800");
        settings.put(ConfigurationKeyEnum.SHOP_NAVIGATION_FOLLOW_UP_SWIPE_FROM_X_INT.name(), "580");
        settings.put(ConfigurationKeyEnum.SHOP_NAVIGATION_FOLLOW_UP_SWIPE_TO_X_INT.name(), "460");
        settings.put(ConfigurationKeyEnum.SHOP_NAVIGATION_FOLLOW_UP_SWIPE_DURATION_MS_INT.name(), "325");
        settings.put(ConfigurationKeyEnum.SHOP_NAVIGATION_FOLLOW_UP_SWIPE_SETTLE_MS_INT.name(), "2200");

        ShopSwipeCalibration calibration = ShopSwipeCalibration.from(settings, ignored -> { });

        assertEquals(new PointData(610, 1240), calibration.forAttempt(1).from());
        assertEquals(new PointData(420, 1240), calibration.forAttempt(1).to());
        assertEquals(275, calibration.forAttempt(1).durationMs());
        assertEquals(1800, calibration.forAttempt(1).settleMs());
        assertEquals(new PointData(580, 1240), calibration.forAttempt(2).from());
        assertEquals(new PointData(460, 1240), calibration.forAttempt(2).to());
        assertEquals(325, calibration.forAttempt(3).durationMs());
        assertEquals(2200, calibration.forAttempt(3).settleMs());
        assertEquals(new PointData(460, 1240), calibration.forAttempt(2).reversed().from());
        assertEquals(new PointData(580, 1240), calibration.forAttempt(2).reversed().to());
    }

    @Test
    void zeroDurationKeepsEmulatorDefaultAndInvalidValuesFallBack() {
        List<String> warnings = new ArrayList<>();
        ShopSwipeCalibration calibration = ShopSwipeCalibration.from(Map.of(
                ConfigurationKeyEnum.SHOP_NAVIGATION_FIRST_SWIPE_FROM_X_INT.name(), "outside-screen",
                ConfigurationKeyEnum.SHOP_NAVIGATION_FIRST_SWIPE_DURATION_MS_INT.name(), "0",
                ConfigurationKeyEnum.SHOP_NAVIGATION_FIRST_SWIPE_SETTLE_MS_INT.name(), "-1"), warnings::add);

        assertEquals(new PointData(600, 1240), calibration.forAttempt(1).from());
        assertEquals(0, calibration.forAttempt(1).durationMs());
        assertEquals(1500, calibration.forAttempt(1).settleMs());
        assertEquals(2, warnings.size());
        assertTrue(warnings.stream().allMatch(message -> message.contains("fallback=")));
    }

    @Test
    void identicalEndpointsFallBackToCompleteDefaultGesture() {
        List<String> warnings = new ArrayList<>();
        ShopSwipeCalibration calibration = ShopSwipeCalibration.from(Map.of(
                ConfigurationKeyEnum.SHOP_NAVIGATION_FOLLOW_UP_SWIPE_FROM_X_INT.name(), "400",
                ConfigurationKeyEnum.SHOP_NAVIGATION_FOLLOW_UP_SWIPE_FROM_Y_INT.name(), "1240",
                ConfigurationKeyEnum.SHOP_NAVIGATION_FOLLOW_UP_SWIPE_TO_X_INT.name(), "400",
                ConfigurationKeyEnum.SHOP_NAVIGATION_FOLLOW_UP_SWIPE_TO_Y_INT.name(), "1240"), warnings::add);

        assertEquals(new PointData(600, 1240), calibration.forAttempt(2).from());
        assertEquals(new PointData(350, 1240), calibration.forAttempt(2).to());
        assertEquals(1, warnings.size());
    }
}
