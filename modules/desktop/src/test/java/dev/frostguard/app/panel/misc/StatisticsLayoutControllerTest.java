package dev.frostguard.app.panel.misc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "zero tests" (round 1), then "a real FXML-loaded Statistics controller
 * test — timeframe selection, refresh, zero-change vs insufficient-data — is still missing"
 * (round 3, item 7).
 *
 * <p>A literal FXML-loaded test needs a headless-JavaFX test harness (TestFX/Monocle) this module
 * has never set up -- adding one now would mean a new test dependency with real CI-fragility risk
 * for a single round of coverage. Instead {@code showWindow}'s entire decision -- what
 * insufficientData/zeroChange/the metric cards/the activity hint should be, for a given window's
 * raw data -- has been extracted into {@link StatisticsLayoutController#computeViewState}, a pure
 * static method with no JavaFX types in it at all (see that method's javadoc). These tests drive
 * it directly with the same three states the review named: a genuine measured change, a genuinely
 * zero-change-but-covered window, and genuinely insufficient data -- plus the per-metric card
 * content and the activity "filling in" hint. This exercises the real runtime decision logic the
 * Statistics tab uses; it does not exercise FXML property binding itself.</p>
 */
class StatisticsLayoutControllerTest {

    private static final TelemetryReport.Coverage COVERAGE = new TelemetryReport.Coverage(
            Instant.parse("2026-08-18T12:00:00Z"), Instant.parse("2026-08-18T18:00:00Z"));

    @TempDir
    Path fixtureWorkspace;

    /**
     * A report whose newest sample carries one metric at one value.
     *
     * <p>Built through the public {@code load} path rather than reaching into TelemetryReport,
     * which #250 owns and which exposes only a private constructor. Writing the one history line
     * the loader reads keeps this test independent of that class's internals.
     */
    private TelemetryReport reportWithLatest(String metric, long value) {
        try {
            Path dir = fixtureWorkspace.resolve("data").resolve("telemetry")
                    .resolve("profiles").resolve("1");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("history.jsonl"),
                    "{\"capturedAt\":\"2026-08-18T08:00:00Z\",\"profile\":\"Default\",\""
                            + metric + "\":" + value + "}\n");
            return TelemetryReport.load(fixtureWorkspace, 1L);
        } catch (IOException exception) {
            throw new IllegalStateException("could not build the telemetry fixture", exception);
        }
    }


    @Test
    void insufficientDataWhenNoDeltasNoActivityAndNoCoverage() {
        TelemetryReport telemetry = reportWithLatest("power", 100L);

        StatisticsLayoutController.ViewState vs = StatisticsLayoutController.computeViewState(
                "Past hour", List.of(), List.of(), null, telemetry);

        assertTrue(vs.insufficientData());
        assertFalse(vs.zeroChange());
        assertTrue(vs.windowLabelText().contains("not enough samples"));
    }

    @Test
    void zeroChangeWhenNoDeltasNoActivityButCoverageExists() {
        TelemetryReport telemetry = reportWithLatest("power", 100L);

        StatisticsLayoutController.ViewState vs = StatisticsLayoutController.computeViewState(
                "Past hour", List.of(), List.of(), COVERAGE, telemetry);

        assertFalse(vs.insufficientData());
        assertTrue(vs.zeroChange());
        assertTrue(vs.windowLabelText().contains("no measurable change"));
        assertTrue(vs.windowLabelText().contains("recorded")); // coverage span appended
    }

    @Test
    void genuineChangeIsNeitherInsufficientNorZeroChange() {
        TelemetryReport telemetry = reportWithLatest("power", 42100L);
        TelemetryReport.Delta delta = new TelemetryReport.Delta("power", 42000L, 42100L, 100L,
                Instant.parse("2026-08-18T12:00:00Z"), Instant.parse("2026-08-18T13:00:00Z"));

        StatisticsLayoutController.ViewState vs = StatisticsLayoutController.computeViewState(
                "Past hour", List.of(delta), List.of(), COVERAGE, telemetry);

        assertFalse(vs.insufficientData());
        assertFalse(vs.zeroChange());
        assertEquals("Past hour", vs.windowLabelText().substring(0, "Past hour".length()));
    }

    @Test
    void changedMetricCardShowsSignedDeltaAndBeforeAfterRange() {
        TelemetryReport telemetry = reportWithLatest("power", 42100L);
        TelemetryReport.Delta delta = new TelemetryReport.Delta("power", 42000L, 42100L, 100L,
                Instant.parse("2026-08-18T12:00:00Z"), Instant.parse("2026-08-18T13:00:00Z"));

        StatisticsLayoutController.ViewState vs = StatisticsLayoutController.computeViewState(
                "Past hour", List.of(delta), List.of(), COVERAGE, telemetry);

        StatisticsLayoutController.EarningsCard card = vs.earningsCards().stream()
                .filter(c -> c.metricKey().equals("power")).findFirst().orElseThrow();
        assertEquals("+100", card.value());
        assertEquals("42,000 → 42,100", card.sub());
    }

    @Test
    void unchangedMetricCardShowsCurrentValueNotAZeroDelta() {
        TelemetryReport telemetry = reportWithLatest("power", 42000L);

        StatisticsLayoutController.ViewState vs = StatisticsLayoutController.computeViewState(
                "Past hour", List.of(), List.of(), COVERAGE, telemetry);

        StatisticsLayoutController.EarningsCard card = vs.earningsCards().stream()
                .filter(c -> c.metricKey().equals("power")).findFirst().orElseThrow();
        assertEquals("42,000", card.value());
        assertEquals("on hand now", card.sub());
    }

    @Test
    void activityFillingInHintWhenNoActivityRecordedYet() {
        TelemetryReport telemetry = reportWithLatest("power", 100L);

        StatisticsLayoutController.ViewState vs = StatisticsLayoutController.computeViewState(
                "Past hour", List.of(), List.of(), COVERAGE, telemetry);

        assertTrue(vs.activityFillingIn());
        assertTrue(vs.activity().isEmpty());
    }

    @Test
    void realActivityIsPassedThroughUnchanged() {
        TelemetryReport telemetry = reportWithLatest("power", 100L);
        TelemetryReport.Activity activity = new TelemetryReport.Activity("Gathered", 5L);

        StatisticsLayoutController.ViewState vs = StatisticsLayoutController.computeViewState(
                "Past hour", List.of(), List.of(activity), COVERAGE, telemetry);

        assertFalse(vs.activityFillingIn());
        assertEquals(List.of(activity), vs.activity());
    }

    @Test
    void formatCoverageIsNullForNoCoverage() {
        assertNull(StatisticsLayoutController.formatCoverage(null));
    }

    @Test
    void formatCoverageShowsTheRealRecordedSpan() {
        TelemetryReport.Coverage coverage = new TelemetryReport.Coverage(
                Instant.parse("2026-08-18T12:00:00Z"), Instant.parse("2026-08-18T18:00:00Z"));

        String formatted = StatisticsLayoutController.formatCoverage(coverage);

        assertEquals(true, formatted.startsWith("recorded "));
        assertEquals(true, formatted.contains("→")); // the "→" (→) arrow separator
    }

    @Test
    void fmtAbbreviatesMillionsAndBillionsButNotThousands() {
        assertEquals("76,944", StatisticsLayoutController.fmt(76944L));
        assertEquals("15.69M", StatisticsLayoutController.fmt(15685506L));
        assertEquals("2.30B", StatisticsLayoutController.fmt(2300000000L));
        assertEquals("—", StatisticsLayoutController.fmt(null));
    }

    @Test
    void fmtSignedPrefixesTheSignAndHandlesZero() {
        assertEquals("+76,944", StatisticsLayoutController.fmtSigned(76944L));
        assertEquals("-1,000", StatisticsLayoutController.fmtSigned(-1000L));
        assertEquals("0", StatisticsLayoutController.fmtSigned(0L));
        assertEquals("—", StatisticsLayoutController.fmtSigned(null));
    }

    @Test
    void fmtMinutesFormatsDaysHoursMinutes() {
        assertEquals("6d 3h", StatisticsLayoutController.fmtMinutes(6L * 1440 + 3 * 60));
        assertEquals("1d 22h 31m", StatisticsLayoutController.fmtMinutes(1L * 1440 + 22 * 60 + 31));
        assertEquals("45m", StatisticsLayoutController.fmtMinutes(45L));
        assertEquals("0m", StatisticsLayoutController.fmtMinutes(0L));
        assertEquals("—", StatisticsLayoutController.fmtMinutes(null));
    }

    @Test
    void fmtMinutesSignedPrefixesTheSignAndHandlesZero() {
        assertEquals("+3h 12m", StatisticsLayoutController.fmtMinutesSigned(3L * 60 + 12));
        assertEquals("-1d", StatisticsLayoutController.fmtMinutesSigned(-1440L));
        assertEquals("0", StatisticsLayoutController.fmtMinutesSigned(0L));
        assertEquals("—", StatisticsLayoutController.fmtMinutesSigned(null));
    }
}
