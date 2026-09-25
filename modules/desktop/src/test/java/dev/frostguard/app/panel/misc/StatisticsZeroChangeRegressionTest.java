package dev.frostguard.app.panel.misc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives REAL {@link TelemetryReport} data through
 * {@link StatisticsLayoutController#computeViewState}, rather than hand-built lists.
 *
 * <p>The bug this exists to stop: the view state decided "nothing changed" from list EMPTINESS,
 * while the report had started emitting a zero-valued Delta for a genuinely unchanged metric. On
 * real data the earned list is therefore never empty, so the zero-change branch became unreachable
 * and an unchanged window silently rendered as an ordinary one. Both halves were individually
 * correct and tested; they simply disagreed about the shape, which is precisely what hand-built
 * fixtures could not catch. Everything here loads a real history file and asks the report itself
 * for the deltas.
 *
 * <p>Evidence level: automated tests.
 */
class StatisticsZeroChangeRegressionTest {

    @TempDir
    Path workspace;

    private TelemetryReport reportOf(String... rows) throws IOException {
        Path dir = workspace.resolve("data").resolve("telemetry").resolve("profiles").resolve("1");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("history.jsonl"), String.join("\n", rows) + "\n");
        return TelemetryReport.load(workspace, 1L);
    }

    /** A sample N hours ago, so it lands inside the windows the report queries. */
    private static String row(int hoursAgo, String fields) {
        Instant at = Instant.now().minus(hoursAgo, ChronoUnit.HOURS);
        return "{\"capturedAt\":\"" + at + "\",\"profile\":\"Default\"" + fields + "}";
    }

    private StatisticsLayoutController.ViewState viewStateOverLast24h(TelemetryReport report) {
        return StatisticsLayoutController.computeViewState(
                "Last 24 hours",
                report.last(24, ChronoUnit.HOURS),
                report.activityLast(24, ChronoUnit.HOURS),
                report.coverageForLast(24, ChronoUnit.HOURS),
                report);
    }

    @Test
    void arealUnchangedReportIsReportedAsZeroChangeNotAsAnOrdinaryWindow() throws IOException {
        // Two real samples, identical power. The report emits a Delta with change() == 0, so the
        // earned list is NOT empty -- the exact shape the old emptiness check misread.
        TelemetryReport report = reportOf(
                row(20, ",\"power\":1000000"),
                row(2, ",\"power\":1000000"));

        assertFalse(report.last(24, ChronoUnit.HOURS).isEmpty(),
                "precondition: an unchanged metric still produces a Delta, so the list is non-empty");

        StatisticsLayoutController.ViewState vs = viewStateOverLast24h(report);

        assertTrue(vs.zeroChange(), "a measured, genuinely unchanged window must say so");
        assertFalse(vs.insufficientData(), "it was measured, so this is not an insufficient-data window");
        assertTrue(vs.windowLabelText().contains("no measurable change"),
                "label was: " + vs.windowLabelText());
    }

    @Test
    void arealChangedReportIsNeitherZeroChangeNorInsufficient() throws IOException {
        TelemetryReport report = reportOf(
                row(20, ",\"power\":1000000"),
                row(2, ",\"power\":1250000"));

        StatisticsLayoutController.ViewState vs = viewStateOverLast24h(report);

        assertFalse(vs.zeroChange());
        assertFalse(vs.insufficientData());
    }

    @Test
    void asingleSampleIsInsufficientDataNotZeroChange() throws IOException {
        TelemetryReport report = reportOf(row(2, ",\"power\":1000000"));

        StatisticsLayoutController.ViewState vs = viewStateOverLast24h(report);

        assertTrue(vs.insufficientData(), "one sample cannot measure a window");
        assertFalse(vs.zeroChange());
        assertTrue(vs.windowLabelText().contains("not enough samples"));
    }

    @Test
    void oneUnchangedMetricAlongsideAChangedOneStillCountsAsAChangedWindow() throws IOException {
        TelemetryReport report = reportOf(
                row(20, ",\"power\":1000000,\"coal\":500"),
                row(2, ",\"power\":1000000,\"coal\":900"));

        StatisticsLayoutController.ViewState vs = viewStateOverLast24h(report);

        assertFalse(vs.zeroChange(), "coal moved, so the window is not a zero-change window");
        assertFalse(vs.insufficientData());
    }

    @Test
    void measuredButUnchangedActivityDoesNotAskForMoreCycles() throws IOException {
        // Two samples carrying the same counter value: measurable, and nothing happened.
        TelemetryReport report = reportOf(
                row(20, ",\"power\":1000000,\"ctr.Intel Beast\":7"),
                row(2, ",\"power\":1000000,\"ctr.Intel Beast\":7"));

        List<TelemetryReport.Activity> activity = report.activityLast(24, ChronoUnit.HOURS);
        assertFalse(activity.isEmpty(),
                "a measurable counter must appear even at zero, or sufficiency is lost");
        assertEquals(0L, activity.get(0).change());

        StatisticsLayoutController.ViewState vs = viewStateOverLast24h(report);

        assertFalse(vs.activityFillingIn(),
                "the window WAS measured -- it must not tell the operator to wait for more cycles");
        assertTrue(vs.activity().isEmpty(), "a zero counter earns no card");
    }

    @Test
    void genuinelyAbsentActivityStillReportsFillingIn() throws IOException {
        TelemetryReport report = reportOf(
                row(20, ",\"power\":1000000"),
                row(2, ",\"power\":1000000"));

        StatisticsLayoutController.ViewState vs = viewStateOverLast24h(report);

        assertTrue(vs.activityFillingIn(),
                "no counter was ever captured, so activity genuinely has nothing to measure");
    }

    @Test
    void realActivityProducesACardAndIsNotFillingIn() throws IOException {
        TelemetryReport report = reportOf(
                row(20, ",\"power\":1000000,\"ctr.Intel Beast\":7"),
                row(2, ",\"power\":1000000,\"ctr.Intel Beast\":12"));

        StatisticsLayoutController.ViewState vs = viewStateOverLast24h(report);

        assertFalse(vs.activityFillingIn());
        assertEquals(1, vs.activity().size());
        assertEquals(5L, vs.activity().get(0).change());
        assertFalse(vs.zeroChange(), "the bot did something, so this is not a zero-change window");
    }
}
