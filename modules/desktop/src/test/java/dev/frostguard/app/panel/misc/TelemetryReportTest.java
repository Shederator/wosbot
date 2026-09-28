package dev.frostguard.app.panel.misc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import dev.frostguard.engine.schedule.TelemetrySnapshotSchedule;
import java.time.ZoneOffset;
import java.time.LocalTime;
import java.time.LocalDate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers workspace-relative loading, per-profile isolation, malformed-line tolerance, and
 * per-metric (not per-sample) delta anchoring.
 */
class TelemetryReportTest {

    @TempDir
    Path workspace;

    private Path historyFile(long profileId) throws IOException {
        Path dir = workspace.resolve("data").resolve("telemetry")
                .resolve("profiles").resolve(String.valueOf(profileId));
        Files.createDirectories(dir);
        return dir.resolve("history.jsonl");
    }

    private static String row(String capturedAt, String extraFields) {
        return "{\"capturedAt\":\"" + capturedAt + "\",\"profile\":\"Default\"" + extraFields + "}";
    }

    @Test
    void loadsFromTheWorkspaceRelativePerProfileFile() throws IOException {
        Files.writeString(historyFile(1L),
                row("2026-08-18T08:00:00Z", ",\"power\":100") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);

        assertEquals(1, report.size());
        assertEquals(100L, report.latest().get("power"));
    }

    @Test
    void neverReadsAnotherProfilesFile() throws IOException {
        Files.writeString(historyFile(1L), row("2026-08-18T08:00:00Z", ",\"power\":100") + "\n");
        Files.writeString(historyFile(2L), row("2026-08-18T08:00:00Z", ",\"power\":999") + "\n");

        TelemetryReport profile1 = TelemetryReport.load(workspace, 1L);
        TelemetryReport profile2 = TelemetryReport.load(workspace, 2L);

        assertEquals(100L, profile1.latest().get("power"));
        assertEquals(999L, profile2.latest().get("power"));
    }

    @Test
    void missingProfileFileLoadsEmptyRatherThanThrowing() {
        TelemetryReport report = TelemetryReport.load(workspace, 404L);

        assertTrue(report.isEmpty());
        assertEquals(0, report.size());
    }

    @Test
    void oneCorruptLineNeverSinksTheWholeHistory() throws IOException {
        Files.writeString(historyFile(1L),
                row("2026-08-18T08:00:00Z", ",\"power\":100") + "\n"
                        + "{this is not valid json at all\n"
                        + row("2026-08-18T09:00:00Z", ",\"power\":150") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);

        assertEquals(2, report.size());
        assertEquals(150L, report.latest().get("power"));
    }

    @Test
    void deltaAnchorsEachMetricToItsOwnLatestCarryingSample() throws IOException {
        // power is present in every row; gems is only present in the middle row -- the newest
        // row (the one deltaOverWindow used to anchor EVERY metric's end to) omits gems entirely.
        // Before the #250 fix this made gems vanish from the window even though an earlier
        // in-window sample had it.
        Files.writeString(historyFile(1L),
                row("2026-08-18T06:00:00Z", ",\"power\":100,\"gems\":5000") + "\n"
                        + row("2026-08-18T07:00:00Z", ",\"power\":110,\"gems\":5200") + "\n"
                        + row("2026-08-18T08:00:00Z", ",\"power\":120") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Delta> deltas = report.deltaOverWindow(
                Instant.parse("2026-08-18T00:00:00Z"), Instant.parse("2026-08-18T23:59:59Z"));

        TelemetryReport.Delta power = findMetric(deltas, "power");
        assertEquals(100L, power.start());
        assertEquals(120L, power.end());
        assertEquals(20L, power.change());

        TelemetryReport.Delta gems = findMetric(deltas, "gems");
        assertEquals(5000L, gems.start());
        assertEquals(5200L, gems.end());
        assertEquals(200L, gems.change());
    }

    @Test
    void unchangedMetricStillProducesAZeroChangeDeltaRatherThanBeingOmitted() throws IOException {
        // A genuine zero-change measurement must not make the metric vanish -- that is
        // indistinguishable from "no data ever captured for this metric". A real start AND end
        // sample both existing, even with equal values, is measured coverage and must say so; only
        // a genuinely missing pair should produce no Delta at all.
        Files.writeString(historyFile(1L),
                row("2026-08-18T06:00:00Z", ",\"power\":100") + "\n"
                        + row("2026-08-18T08:00:00Z", ",\"power\":100") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Delta> deltas = report.deltaOverWindow(
                Instant.parse("2026-08-18T00:00:00Z"), Instant.parse("2026-08-18T23:59:59Z"));

        assertEquals(1, deltas.size());
        TelemetryReport.Delta d = deltas.get(0);
        assertEquals("power", d.metric());
        assertEquals(100L, d.start());
        assertEquals(100L, d.end());
        assertEquals(0L, d.change());
    }

    @Test
    void metricWithNoUsableSamplePairProducesNoDeltaAtAll() throws IOException {
        // The genuine "no data" case: a metric that was never captured at all must still be
        // omitted -- distinct from unchangedMetricStillProducesAZeroChangeDeltaRatherThanBeingOmitted
        // above, which has real samples that just happen to be equal.
        Files.writeString(historyFile(1L),
                row("2026-08-18T06:00:00Z", ",\"power\":100") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Delta> deltas = report.deltaOverWindow(
                Instant.parse("2026-08-19T00:00:00Z"), Instant.parse("2026-08-19T23:59:59Z"));

        assertTrue(deltas.isEmpty(), "a metric with no sample inside or before the window has nothing to report");
    }

    @Test
    void changedMetricStillProducesTheRealDelta() throws IOException {
        // Guards against a regression that makes everything look "unchanged" -- a real change must
        // still come through with its real start/end/change values, not just zero-change coverage.
        Files.writeString(historyFile(1L),
                row("2026-08-18T06:00:00Z", ",\"power\":100") + "\n"
                        + row("2026-08-18T08:00:00Z", ",\"power\":150") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Delta> deltas = report.deltaOverWindow(
                Instant.parse("2026-08-18T00:00:00Z"), Instant.parse("2026-08-18T23:59:59Z"));

        assertEquals(1, deltas.size());
        TelemetryReport.Delta d = deltas.get(0);
        assertEquals(100L, d.start());
        assertEquals(150L, d.end());
        assertEquals(50L, d.change());
    }

    @Test
    void narrowWindowUsesTheLastValueBeforeItOpenedAsBaseline() throws IOException {
        // "Past Hour" against an hourly writer normally contains exactly one in-window sample, so
        // a first-in-window baseline makes start==end and the delta always reads as zero -- even
        // though a real earlier sample just outside the window proves the value changed. One sample
        // well before the window plus one inside a narrow 1-hour window is exactly that shape.
        Files.writeString(historyFile(1L),
                row("2026-08-18T05:00:00Z", ",\"power\":100") + "\n"   // well before the window
                        + row("2026-08-18T08:50:00Z", ",\"power\":140") + "\n"); // the only in-window sample

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Delta> deltas = report.deltaOverWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        TelemetryReport.Delta power = findMetric(deltas, "power");
        assertEquals(100L, power.start(), "baseline should be the last known value before the window opened");
        assertEquals(140L, power.end());
        assertEquals(40L, power.change());
    }

    @Test
    void metricWithNoPreWindowValueFallsBackToFirstInWindowSample() throws IOException {
        // A metric that simply didn't exist before the window (e.g. newly added capture) has no
        // pre-window baseline to anchor to -- falls back to the original first-in-window behavior
        // instead of never showing a delta at all.
        Files.writeString(historyFile(1L),
                row("2026-08-18T08:10:00Z", ",\"gems\":5000") + "\n"
                        + row("2026-08-18T08:50:00Z", ",\"gems\":5300") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Delta> deltas = report.deltaOverWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        TelemetryReport.Delta gems = findMetric(deltas, "gems");
        assertEquals(5000L, gems.start());
        assertEquals(5300L, gems.end());
        assertEquals(300L, gems.change());
    }

    @Test
    void sampleExactlyAtWindowStartIsUsedAsTheBaseline() throws IOException {
        // The baseline used to accept only samples STRICTLY before
        // `from` (isBefore), so a sample landing exactly on the boundary -- e.g. an hourly writer
        // firing right at the window's start anchor -- was skipped in favour of an older sample,
        // overcounting activity that happened before the window actually opened.
        Files.writeString(historyFile(1L),
                row("2026-08-18T07:00:00Z", ",\"power\":50") + "\n"    // older, should be ignored
                        + row("2026-08-18T08:00:00Z", ",\"power\":100") + "\n" // exactly at the boundary
                        + row("2026-08-18T08:50:00Z", ",\"power\":140") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Delta> deltas = report.deltaOverWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        TelemetryReport.Delta power = findMetric(deltas, "power");
        assertEquals(100L, power.start(), "the exact-boundary sample should be the baseline, not an older one");
        assertEquals(140L, power.end());
        assertEquals(40L, power.change());
    }

    @Test
    void coverageForWindowReportsThePerMetricTimestampsActuallyUsed() throws IOException {
        // power has a pre-window baseline; gems doesn't (first-ever sample lands inside the
        // window) -- coverage should span the widest range actually used across both metrics, not
        // some unrelated global first/last sample pair.
        Files.writeString(historyFile(1L),
                row("2026-08-18T07:00:00Z", ",\"power\":100") + "\n"                  // power baseline
                        + row("2026-08-18T08:10:00Z", ",\"power\":110,\"gems\":5000") + "\n" // gems baseline (fallback)
                        + row("2026-08-18T08:50:00Z", ",\"power\":140,\"gems\":5300") + "\n"); // end for both

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        TelemetryReport.Coverage coverage = report.coverageForWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        assertEquals(Instant.parse("2026-08-18T07:00:00Z"), coverage.actualFrom(),
                "should reflect power's real pre-window baseline, not the in-window gems baseline");
        assertEquals(Instant.parse("2026-08-18T08:50:00Z"), coverage.actualTo());
    }

    @Test
    void coverageForWindowIsNullWhenThereIsNoUsableSamplePair() throws IOException {
        // The genuine "no data" case -- nothing in or before the window at all.
        Files.writeString(historyFile(1L), row("2026-08-19T08:10:00Z", ",\"power\":100") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        TelemetryReport.Coverage coverage = report.coverageForWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        assertEquals(null, coverage);
    }

    @Test
    void coverageForWindowIsNotNullForAGenuinelyUnchangedButMeasuredWindow() throws IOException {
        // coverageForWindow() derives its answer from deltaOverWindow(), so a window holding two
        // real, valid samples that happen to carry the same value must not report null coverage --
        // that is indistinguishable from "not enough data" on the Statistics tab. Both cases then
        // hit the same UI fallback (a raw current value with no delta framing), which is what reads
        // as an overnight "gained 24 million power".
        Files.writeString(historyFile(1L),
                row("2026-08-18T08:10:00Z", ",\"power\":100") + "\n"
                        + row("2026-08-18T08:50:00Z", ",\"power\":100") + "\n"); // unchanged, but real

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        TelemetryReport.Coverage coverage = report.coverageForWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        assertEquals(Instant.parse("2026-08-18T08:10:00Z"), coverage.actualFrom());
        assertEquals(Instant.parse("2026-08-18T08:50:00Z"), coverage.actualTo());
    }

    @Test
    void coverageForWindowReportsTheRealRangeForAChangedWindow() throws IOException {
        Files.writeString(historyFile(1L),
                row("2026-08-18T08:10:00Z", ",\"power\":100") + "\n"
                        + row("2026-08-18T08:50:00Z", ",\"power\":150") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        TelemetryReport.Coverage coverage = report.coverageForWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        assertEquals(Instant.parse("2026-08-18T08:10:00Z"), coverage.actualFrom());
        assertEquals(Instant.parse("2026-08-18T08:50:00Z"), coverage.actualTo());
    }

    @Test
    void activityDeltaUsesTheLastActivityBearingSampleAtOrBeforeTheWindowAsBaseline() throws IOException {
        // ActivityOverWindow() only ever looked at samples AT OR AFTER
        // `from`, so with the hourly writer, a narrow window (e.g. "Past Hour") normally contains
        // exactly one activity-bearing sample -- start and end are the same row, and every activity
        // count reads as zero even though real activity happened just before the window opened.
        Files.writeString(historyFile(1L),
                row("2026-08-18T07:00:00Z", ",\"ctr.Gather Marches Deployed\":3") + "\n" // pre-window baseline
                        + row("2026-08-18T08:50:00Z", ",\"ctr.Gather Marches Deployed\":7") + "\n"); // the only in-window sample

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Activity> activity = report.activityOverWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        TelemetryReport.Activity gather = activity.stream()
                .filter(a -> a.label().equals("Gather marches")).findFirst()
                .orElseThrow(() -> new AssertionError("expected a Gather marches delta"));
        assertEquals(4L, gather.change(), "baseline should be the pre-window sample (3), not the in-window one (7)");
    }

    @Test
    void activityDeltaFallsBackToTheEarliestInWindowSampleWhenNothingPredatesTheWindow() throws IOException {
        // No activity-bearing sample exists before the window at all (activity capture just
        // started, or these are the very first rows ever) -- falls back to the earliest in-window
        // sample so the count isn't withheld indefinitely.
        Files.writeString(historyFile(1L),
                row("2026-08-18T08:10:00Z", ",\"ctr.Gather Marches Deployed\":3") + "\n"
                        + row("2026-08-18T08:50:00Z", ",\"ctr.Gather Marches Deployed\":7") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Activity> activity = report.activityOverWindow(
                Instant.parse("2026-08-18T08:00:00Z"), Instant.parse("2026-08-18T09:00:00Z"));

        TelemetryReport.Activity gather = activity.stream()
                .filter(a -> a.label().equals("Gather marches")).findFirst()
                .orElseThrow(() -> new AssertionError("expected a Gather marches delta"));
        assertEquals(4L, gather.change());
    }

    private static TelemetryReport.Delta findMetric(List<TelemetryReport.Delta> deltas, String metric) {
        return deltas.stream().filter(d -> d.metric().equals(metric)).findFirst()
                .orElseThrow(() -> new AssertionError("No delta for metric " + metric));
    }

    @Test
    void theWakeAnchorSnapshotLandsInsideTheLastNightWindow() throws IOException {
        // The writer takes a snapshot at TelemetrySnapshotSchedule.WAKE_ANCHOR (08:30), but a real
        // capture was observed landing at 08:30:43 -- past an exact 08:30:00 cutoff. If the
        // reader's window ended precisely on the anchor, that snapshot would fall outside the very
        // window it exists to bookend, and "last night" would silently end at the prior hourly
        // sample instead. That is a wrong answer rather than a visible failure, so it is asserted
        // behaviourally here rather than by reading the grace constant.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String bedtime = today.minusDays(1) + "T23:00:00Z";
        String wakeCapture = today + "T08:30:43Z";

        Files.writeString(historyFile(1L),
                row(bedtime, ",\"power\":100") + "\n"
                        + row(wakeCapture, ",\"power\":160") + "\n");

        TelemetryReport report = TelemetryReport.load(workspace, 1L);
        List<TelemetryReport.Delta> deltas =
                report.lastNight(ZoneOffset.UTC, LocalTime.of(23, 0), TelemetrySnapshotSchedule.WAKE_ANCHOR);

        assertEquals(1, deltas.size(),
                "the 08:30:43 wake snapshot fell outside the last-night window -- the reader's grace "
                        + "window no longer covers the writer's wake anchor");
        assertEquals(60L, deltas.get(0).change());
    }
}
