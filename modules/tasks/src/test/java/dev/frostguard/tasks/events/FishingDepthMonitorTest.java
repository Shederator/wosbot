package dev.frostguard.tasks.events;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FishingDepthMonitorTest {
    @org.junit.jupiter.api.Test
    void independentlyVerifiedSetupRejectsTheExtraLeadingDigitBeforeItCanLockTheFilter() {
        assertFalse(FishingDepthMonitor.matchesMaximum(new FishingDepthMonitor.Depth(178, 1550), 550));
        assertTrue(FishingDepthMonitor.matchesMaximum(new FishingDepthMonitor.Depth(178, 550), 550));
        assertFalse(FishingDepthMonitor.matchesMaximum(null, 550));
        assertTrue(FishingDepthMonitor.matchesMaximum(new FishingDepthMonitor.Depth(19, 100), 100));
    }
    @Test
    void separatelyBoundedFieldsPermitMissingUnitsButNotTrailingNoiseOrImpossibleDepth() {
        assertEquals(new FishingDepthMonitor.Depth(32, 100), FishingDepthMonitor.parseFields("32", "100M"));
        assertEquals(new FishingDepthMonitor.Depth(172, 550), FishingDepthMonitor.parseFields("172m", "550"));
        assertNull(FishingDepthMonitor.parseFields("19M8", "100M"));
        assertNull(FishingDepthMonitor.parseFields("m", "100M"));
        assertNull(FishingDepthMonitor.parseFields("101m", "100M"));
        assertNull(FishingDepthMonitor.parseFields("4m 44m", "100M"));
    }

    @Test
    void rejectsMissingMalformedAndImpossibleDepthRatherThanInferringLineExhaustion() {
        assertNull(FishingDepthMonitor.parse(null));
        assertNull(FishingDepthMonitor.parse("550M"));
        assertNull(FishingDepthMonitor.parse("550M 172M"));
        assertNull(FishingDepthMonitor.parse("999999999999M 999999999999M"));
        assertEquals(new FishingDepthMonitor.Depth(172, 550), FishingDepthMonitor.parse("172M\n550M"));
        assertEquals(new FishingDepthMonitor.Depth(550, 550), FishingDepthMonitor.parse("550M 550M"));
    }
}
