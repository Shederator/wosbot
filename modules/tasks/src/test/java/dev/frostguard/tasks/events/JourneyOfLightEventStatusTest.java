package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JourneyOfLightEventStatusTest {
    @Test
    void recognizesEndMarkerWithoutCaseSensitivity() {
        assertEquals(JourneyOfLightEventStatus.ENDED,
                JourneyOfLightEventStatus.classify("COLLECT YOUR REWARDS", ""));
        assertEquals(JourneyOfLightEventStatus.ENDED,
                JourneyOfLightEventStatus.classify("Journey of Light", "COLLECT"));
    }

    @Test
    void requiresQueueControlsAlongsideEventTitleToRecognizeActiveState() {
        assertEquals(JourneyOfLightEventStatus.ACTIVE,
                JourneyOfLightEventStatus.classify("Journey of Light", "Employ Expedition Teams"));
    }

    @Test
    void treatsEventTitleWithoutQueueControlsAsUnknown() {
        assertEquals(JourneyOfLightEventStatus.UNKNOWN,
                JourneyOfLightEventStatus.classify("Journey of Light", ""));
    }

    @Test
    void doesNotTreatTransientDispatchBannerAsActiveEvidence() {
        assertEquals(JourneyOfLightEventStatus.UNKNOWN,
                JourneyOfLightEventStatus.classify("Expedition team dispatched", ""));
    }

    @Test
    void treatsBlankTextAsUnknown() {
        assertEquals(JourneyOfLightEventStatus.UNKNOWN, JourneyOfLightEventStatus.classify("  ", ""));
    }

    @Test
    void treatsUnrecognizedTextAsUnknown() {
        assertEquals(JourneyOfLightEventStatus.UNKNOWN,
                JourneyOfLightEventStatus.classify("Daily expedition", ""));
    }
}
