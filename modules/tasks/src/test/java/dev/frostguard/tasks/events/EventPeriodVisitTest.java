package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import dev.frostguard.tasks.events.EventPeriodVisit.OpenedScreen;
import dev.frostguard.tasks.events.EventPeriodVisit.TabMiss;

class EventPeriodVisitTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 29, 17, 0);
    private static final LocalDateTime RESET = LocalDateTime.of(2026, 9, 30, 2, 0);
    private static final LocalDateTime CLOCK = LocalDateTime.of(2026, 9, 29, 17, 15);

    @Test
    void firstTabMissRetriesOnceAndTheSecondWaitsForTheReset() {
        assertEquals(TabMiss.RETRY, EventPeriodVisit.tabMiss(false));
        assertTrue(EventPeriodVisit.rememberExtraVisit(false));
        assertEquals(NOW.plusMinutes(5), EventPeriodVisit.retryAt(NOW));

        assertEquals(TabMiss.REST, EventPeriodVisit.tabMiss(true));
        assertFalse(EventPeriodVisit.rememberExtraVisit(true));
    }

    @Test
    void buttonAbsenceIsNotCompletionAndAMatchedCompletionWaitsForTheReset() {
        assertEquals(OpenedScreen.ACTIVE, EventPeriodVisit.openedScreen(true, false, false));
        assertEquals(OpenedScreen.UNRECOGNIZED, EventPeriodVisit.openedScreen(false, false, false));
        assertEquals(OpenedScreen.COMPLETED, EventPeriodVisit.openedScreen(false, true, false));
        assertEquals(OpenedScreen.COUNTDOWN_TO_START, EventPeriodVisit.openedScreen(false, false, true));

        assertEquals(NOW.plusMinutes(5), EventPeriodVisit.nextVisit(OpenedScreen.UNRECOGNIZED, NOW, RESET, null));
        assertEquals(RESET, EventPeriodVisit.nextVisit(OpenedScreen.COMPLETED, NOW, RESET, CLOCK));
        assertEquals(CLOCK, EventPeriodVisit.nextVisit(OpenedScreen.COUNTDOWN_TO_START, NOW, RESET, CLOCK));
        assertEquals(NOW.plusMinutes(5),
                EventPeriodVisit.nextVisit(OpenedScreen.COUNTDOWN_TO_START, NOW, RESET, null));
    }

    @Test
    void readableEndCountdownWithoutCompletionIsAnErrorAndTheDurationIsOnlyAFallback() {
        assertTrue(EventPeriodVisit.endCountdownUnconfirmed(true, false));
        assertFalse(EventPeriodVisit.endCountdownUnconfirmed(true, true));
        assertFalse(EventPeriodVisit.endCountdownUnconfirmed(false, false));
        assertEquals(NOW.plusDays(3), EventPeriodVisit.durationFallback(NOW));
        assertFalse(EventPeriodTemplates.installed(EventPeriodTemplates.MERCENARY_COMPLETION));
        assertFalse(EventPeriodTemplates.installed(EventPeriodTemplates.MERCENARY_END_COUNTDOWN));
    }
}
