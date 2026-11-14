package dev.frostguard.vision.ocr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the per-metric bands actually used live, in both directions, plus the boundaries, the
 * no-previous-value pass-through, and the real misreads observed on a live account.
 *
 * <p>The scenarios are deliberately expressed per metric rather than against one shared ratio. A
 * single symmetric band cannot serve all three: power moves gradually, while coal and gems are
 * spent in lumps and legitimately fall by most of their value in one step. The earlier shared
 * {@code maxRatio = 1.5} band was {@code [0.667, 1.5]}, so it rejected any drawdown past 33% —
 * which is an ordinary building upgrade or shop purchase, not a misread.
 */
class PlausibilityBandTest {

    // ---------- power: tight both ways ----------

    @Test
    void powerGradualGrowthIsPlausible() {
        // Power climbs steadily between samples; +20% over a few hours is ordinary.
        assertTrue(PlausibilityBand.POWER.isPlausible(24_000_000L, 20_000_000L));
    }

    @Test
    void powerModestTroopLossIsPlausible() {
        // Losing troops dents power, but not catastrophically -- -10% stays believable.
        assertTrue(PlausibilityBand.POWER.isPlausible(18_000_000L, 20_000_000L));
    }

    @Test
    void powerCollapsingByHalfIsRejected() {
        // Power does not halve between two samples. This is the shape of a misread, not a battle.
        assertFalse(PlausibilityBand.POWER.isPlausible(10_000_000L, 20_000_000L));
    }

    // ---------- coal / gems: large legitimate drawdowns ----------

    @Test
    void coalSpentOnABuildingUpgradeIsPlausible() {
        // A 40% drawdown is an ordinary upgrade payment, not a misread. Under a shared symmetric
        // [0.667, 1.5] band this was rejected, because deriving the floor as 1/maxRatio allows only
        // a 33% decrease -- so the check discarded the very spends the tracker exists to record.
        assertTrue(PlausibilityBand.COAL.isPlausible(12_000_000L, 20_000_000L));
    }

    @Test
    void coalNearlyEmptiedByALargeUpgradeIsPlausible() {
        // A big Furnace step can take almost the whole stockpile in one go.
        assertTrue(PlausibilityBand.COAL.isPlausible(1_500_000L, 20_000_000L));
    }

    @Test
    void coalProductionFromALowBalanceUsesAbsoluteAllowance() {
        assertTrue(PlausibilityBand.COAL.isPlausible(197_800L, 47_200L));
    }

    @Test
    void gemsSpentOnASinglePurchaseIsPlausible() {
        // 55,000 -> 25,000 is one shop purchase, not an OCR error.
        assertTrue(PlausibilityBand.GEMS.isPlausible(25_000L, 55_000L));
    }

    @Test
    void gemsJumpingUpBeyondTheCeilingIsRejected() {
        // Gems rising 2x between samples is the misread shape; a top-up large enough to do that
        // legitimately is rare enough to be worth re-reading rather than trusting.
        assertFalse(PlausibilityBand.GEMS.isPlausible(110_000L, 55_000L));
    }

    // ---------- boundaries ----------

    @Test
    void identicalValueIsAlwaysPlausible() {
        assertTrue(PlausibilityBand.POWER.isPlausible(100, 100));
        assertTrue(PlausibilityBand.COAL.isPlausible(100, 100));
    }

    @Test
    void exactBoundariesAreInclusive() {
        // Exactly 1.5x and exactly the metric's own floor must both pass (<=/>=, not </>).
        assertTrue(PlausibilityBand.POWER.isPlausible(150, 100));
        assertTrue(PlausibilityBand.POWER.isPlausible(80, 100));
        assertTrue(PlausibilityBand.COAL.isPlausible(5, 100));
    }

    @Test
    void justOutsideTheBoundariesIsRejected() {
        assertFalse(PlausibilityBand.POWER.isPlausible(151, 100));
        assertFalse(PlausibilityBand.POWER.isPlausible(79, 100));
        assertFalse(PlausibilityBand.COAL.isPlausible(4, 100));
    }

    // ---------- real observed misreads ----------

    @Test
    void realLiveObservedMisreadIsRejected() {
        // Live account-log confirmation: steel jumped 1,174,000 -> 839,000,000 (ratio ~714.6x), the
        // actual misread that slipped through ResourceStockpileRoutine's unbounded trust-streak
        // escape hatch. Every band must catch this on the first read.
        assertFalse(PlausibilityBand.POWER.isPlausible(839_000_000L, 1_174_000L));
        assertFalse(PlausibilityBand.COAL.isPlausible(839_000_000L, 1_174_000L));
        assertFalse(PlausibilityBand.GEMS.isPlausible(839_000_000L, 1_174_000L));
    }

    @Test
    void realLiveObservedUpwardMisreadIsRejected() {
        // The documented "~1.7-1.8x" upward misread class from live telemetry evidence.
        assertFalse(PlausibilityBand.POWER.isPlausible(177, 100));
        assertFalse(PlausibilityBand.COAL.isPlausible(17_700_000L, 10_000_000L));
    }

    // ---------- no previous value ----------

    @Test
    void noPreviousValueAlwaysPasses() {
        // First-ever sample: nothing to compare against, so nothing to reject.
        assertTrue(PlausibilityBand.POWER.isPlausible(500, 0));
        assertTrue(PlausibilityBand.COAL.isPlausible(500, -1));
    }

    @Test
    void zeroCandidateAgainstAPositivePreviousIsRejected() {
        // A read of exactly zero against a real positive previous is outside every band's floor.
        // Even a fully-spent stockpile does not read as a clean 0 between two normal samples.
        assertFalse(PlausibilityBand.POWER.isPlausible(0, 100));
        assertFalse(PlausibilityBand.COAL.isPlausible(0, 100));
    }

    // ---------- band construction ----------

    @Test
    void bandsExposeBothBoundsExplicitly() {
        // The whole point of the change: both bounds are stated, not derived from one another.
        assertEquals(0.80, PlausibilityBand.POWER.minRatio());
        assertEquals(1.50, PlausibilityBand.POWER.maxRatio());
    }

    @Test
    void nonsenseBandsAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new PlausibilityBand(0.0, 1.5));
        assertThrows(IllegalArgumentException.class, () -> new PlausibilityBand(1.2, 1.5));
        assertThrows(IllegalArgumentException.class, () -> new PlausibilityBand(0.5, 0.9));
        assertThrows(IllegalArgumentException.class, () -> new PlausibilityBand(0.5, 1.5, -1L));
    }

    @Test
    void aTighterBandRejectsWhatALooserOneAccepts() {
        // Confirms band width has a real, testable effect rather than being decorative.
        assertFalse(new PlausibilityBand(0.9, 1.2).isPlausible(130, 100));
        assertTrue(new PlausibilityBand(0.5, 1.5).isPlausible(130, 100));
    }
}
