package dev.frostguard.vision.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class GameTimeUtilsTest {

    @Test
    void acceptsDayQualifierSplitAcrossLines() {
        String timer = "1d\n10:14:57";

        assertTrue(GameTimeUtils.isAcceptedFormat(timer));
        assertEquals(
                Duration.ofDays(1).plusHours(10).plusMinutes(14).plusSeconds(57),
                GameTimeUtils.parseDuration(timer));
    }

    @Test
    void formatsSingularAndPluralDaysGrammatically() {
        assertTrue(GameTimeUtils.formatCountdown(LocalDateTime.now().plusDays(1).plusMinutes(1))
                .startsWith("1 day "));
        assertTrue(GameTimeUtils.formatCountdown(LocalDateTime.now().plusDays(2).plusMinutes(1))
                .startsWith("2 days "));
    }
}
