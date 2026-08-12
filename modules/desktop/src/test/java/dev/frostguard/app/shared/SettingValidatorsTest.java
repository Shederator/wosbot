package dev.frostguard.app.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class SettingValidatorsTest {

    @Test
    void acceptsPositiveIntegerBoundaries() {
        SettingValidator<Integer> validator = SettingValidators.rangedInteger("Workers", 1, 8);

        assertEquals(1, validator.validate("1").value());
        assertEquals(8, validator.validate(" 8 ").value());
        assertTrue(validator.validate("1").isValid());
    }

    @Test
    void rejectsIncompleteMalformedOverflowAndOutOfRangeIntegers() {
        SettingValidator<Integer> validator = SettingValidators.rangedInteger("Workers", 1, 8);

        assertFalse(validator.validate("").isValid());
        assertFalse(validator.validate("two").isValid());
        assertFalse(validator.validate("999999999999999999999").isValid());
        assertFalse(validator.validate("0").isValid());
        assertFalse(validator.validate("9").isValid());
    }

    @Test
    void parsesStrictTwentyFourHourTime() {
        SettingValidator<LocalTime> validator = SettingValidators.localTime("Start time");

        assertEquals(LocalTime.of(0, 0), validator.validate("00:00").value());
        assertEquals(LocalTime.of(23, 59), validator.validate("23:59").value());
        assertFalse(validator.validate("24:00").isValid());
        assertFalse(validator.validate("9:30").isValid());
    }
}
