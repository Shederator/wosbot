package dev.frostguard.app.panel.misc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

class GiftCodeAutomationServiceTest {

    @Test
    void profileRefreshIsSkippedWhenTheCallingThreadIsInterrupted() {
        assertTrue(GiftCodeAutomationService.isProfileRefreshAllowed());

        Thread.currentThread().interrupt();
        try {
            assertFalse(GiftCodeAutomationService.isProfileRefreshAllowed());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void hourlyCheckIsAlignedToTheNextFullUtcHour() {
        ZonedDateTime now = ZonedDateTime.of(2026, 7, 29, 7, 58, 57, 123_000_000, ZoneOffset.UTC);

        assertEquals(Duration.ofMinutes(1).plusSeconds(2).plusMillis(877),
                GiftCodeAutomationService.delayUntilNextHourlyCheck(now));
    }

    @Test
    void retryBackoffAvoidsRapidRepeatedCenturyRequests() {
        assertEquals(5_000L, GiftCodeAutomationService.retryDelayMillis(1));
        assertEquals(15_000L, GiftCodeAutomationService.retryDelayMillis(2));
    }
}
