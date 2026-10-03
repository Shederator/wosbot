package dev.frostguard.app.panel.misc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.frostguard.api.runtime.WorkspacePaths;

/**
 * Reads the telemetry history that the built-in telemetry routine appends to
 * {@code data/telemetry/profiles/<id>/history.jsonl} and turns it into the "what did the bot
 * earn" reports the Statistics tab shows.
 *
 * <p>The Statistics page answers real questions -- "how many resources did I gather overnight",
 * "how much power / how many gems did botting earn me today / this week / total" -- instead of
 * run counts. Each report is a delta between the snapshot at the start of a window and the most
 * recent one, so it reads directly off the same history the bot already logs.</p>
 *
 * <p>Reads one file per profile ID under the workspace root ({@link #load}), rather than a single
 * shared history filtered by profile name -- a row with no "profile" field (or a null
 * caller-supplied name) can't accidentally be accepted for every profile when there is nothing
 * left to filter.</p>
 */
public final class TelemetryReport {

    /**
     * The tracked metrics, in display order. Key matches the JSONL field name.
     * The five {@code sp_*} entries are speedup durations in MINUTES (not counts) —
     * StatisticsLayoutController formats those back to "6d 3h" for display.
     */
    public static final List<String> METRICS = List.of("power", "gems", "meat", "wood", "coal", "iron",
            "steel", "sp_general", "sp_training", "sp_construction", "sp_research", "sp_healing");

    /** One telemetry sample. Any metric may be null when that read was unavailable. */
    public record Sample(Instant at, Map<String, Long> values, Map<String, Long> activity) {
        public Long get(String metric) { return values.get(metric); }
    }

    /** A named activity total's change over a window, e.g. "Intel missions": +27. */
    public record Activity(String label, long change) {}

    /** A start→end change in one metric over a window. {@code startAt}/{@code endAt} are the real
     *  sample timestamps the two values came from -- not the requested window bounds -- so
     *  {@link #coverageForWindow} can report what was actually used instead of guessing. */
    public record Delta(String metric, Long start, Long end, Long change, Instant startAt, Instant endAt) {}

    private final List<Sample> samples;

    private TelemetryReport(List<Sample> samples) {
        this.samples = samples;
    }

    /**
     * Loads and sorts one profile's samples (oldest first) from its own workspace-local file.
     * Never throws — returns empty on any problem (no file yet, unreadable, all lines corrupt).
     */
    public static TelemetryReport load(long profileId) {
        return load(WorkspacePaths.current().root(), profileId);
    }

    /** Overload taking an explicit workspace root, for tests that don't want a real installed
     *  workspace on disk. */
    public static TelemetryReport load(Path workspaceRoot, long profileId) {
        List<Sample> out = new ArrayList<>();
        Path file = workspaceRoot.resolve("data").resolve("telemetry")
                .resolve("profiles").resolve(String.valueOf(profileId)).resolve("history.jsonl");
        if (!Files.isReadable(file)) {
            return new TelemetryReport(out);
        }
        ObjectMapper mapper = new ObjectMapper();
        try {
            for (String line : Files.readAllLines(file)) {
                if (line == null || line.isBlank()) continue;
                JsonNode node;
                try {
                    node = mapper.readTree(line);
                } catch (IOException badLine) {
                    continue; // one corrupt line never sinks the whole history
                }
                Instant at = parseInstant(node.path("capturedAt").asText(null));
                if (at == null) continue;
                Map<String, Long> values = new LinkedHashMap<>();
                for (String metric : METRICS) {
                    JsonNode v = node.get(metric);
                    if (v != null && v.isNumber()) {
                        values.put(metric, v.asLong());
                    }
                }
                // Activity fields are flattened as "run.<Task>" / "ctr.<Counter>".
                Map<String, Long> activity = new LinkedHashMap<>();
                node.fields().forEachRemaining(f -> {
                    String k = f.getKey();
                    if ((k.startsWith("run.") || k.startsWith("ctr.")) && f.getValue().isNumber()) {
                        activity.put(k, f.getValue().asLong());
                    }
                });
                out.add(new Sample(at, values, activity));
            }
        } catch (IOException e) {
            return new TelemetryReport(new ArrayList<>());
        }
        out.sort((a, b) -> a.at().compareTo(b.at()));
        return new TelemetryReport(out);
    }

    private static Instant parseInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        // The telemetry routine writes an ISO local-datetime with a trailing 'Z' already on
        // a UTC value, occasionally producing "...ZZ". Normalise to a single Z.
        String s = raw.trim();
        while (s.endsWith("ZZ")) s = s.substring(0, s.length() - 1);
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            try {
                // Fallback: treat a bare local-datetime as UTC.
                return java.time.LocalDateTime.parse(s.replace("Z", "")).toInstant(java.time.ZoneOffset.UTC);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    public boolean isEmpty() { return samples.isEmpty(); }

    public int size() { return samples.size(); }

    public Sample latest() { return samples.isEmpty() ? null : samples.get(samples.size() - 1); }

    public List<Sample> samples() { return samples; }


    /**
     * Change in every metric over the window ending {@code to}, using {@code from} as the
     * window's start boundary.
     *
     * <p>Each metric finds its own end value independently -- the latest in-window sample that
     * actually carries it -- rather than anchoring every metric to the single latest overall
     * sample, so a metric simply missing from that one row (a transient OCR miss, or a row written
     * before that metric existed) doesn't vanish from the whole window when earlier in-window
     * samples had it.</p>
     *
     * <p>The start baseline is the last known value AT OR BEFORE {@code from} (the actual state
     * right when the window opened), which is what "change during this window" means, and is what
     * makes a short window (e.g. "Past Hour") against an infrequent writer show a real delta
     * instead of always reading zero. Falls back to the earliest in-window sample only when a
     * metric has no pre-window value at all -- meat/wood/iron predate their own capture, so a
     * metric can't be given a baseline from before it existed, but still deserves to show as soon
     * as it has two real data points inside the window.</p>
     */
    public List<Delta> deltaOverWindow(Instant from, Instant to) {
        List<Delta> deltas = new ArrayList<>();
        for (String metric : METRICS) {
            // Per-metric end: the latest in-window sample that actually carries this metric.
            Long end = null;
            Instant endAt = null;
            for (Sample s : samples) {
                if (s.at().isAfter(to)) break;
                Long v = s.get(metric);
                if (v != null) { end = v; endAt = s.at(); }
            }
            if (end == null) continue;

            // Preferred baseline: the last known value at or before the window opened -- this is
            // what actually answers "how much changed during this window", and is what makes a
            // short window (Past Hour) against an infrequent writer show a real delta instead of
            // always reading zero. "At or before" (not strictly before) matters when a sample
            // lands exactly on the boundary -- e.g. an hourly writer firing right at the window's
            // start anchor -- otherwise that sample is skipped in favour of an older one and the
            // window overcounts activity that happened before it actually opened.
            Long start = null;
            Instant startAt = null;
            for (Sample s : samples) {
                if (s.at().isAfter(from)) break;
                Long v = s.get(metric);
                if (v != null) { start = v; startAt = s.at(); }
            }

            // Fallback: no pre-window value exists (the metric didn't exist yet, or this is the
            // very first sample ever) -- use the earliest in-window sample instead, so a
            // newly-introduced metric can still show a delta as soon as it has two real points,
            // rather than never showing anything until a full window predates it.
            if (start == null) {
                for (Sample s : samples) {
                    if (s.at().isBefore(from)) continue;
                    if (s.at().isAfter(endAt)) break;
                    Long v = s.get(metric);
                    if (v != null) { start = v; startAt = s.at(); break; }
                }
            }

            // A genuinely zero-change metric (real start AND end samples, equal values) must not
            // be skipped like a metric with NO coverage at all. coverageForWindow() derives its
            // answer straight from this list, so skipping would make a window of real, measured,
            // unchanged samples return null coverage -- indistinguishable from "not enough data".
            // The visible consequence: with telemetry gapped, every window falls into the same "no
            // coverage" bucket and the caller renders a raw absolute value with no delta framing,
            // which reads as "gained 24 million power" overnight. Always emit a Delta once both
            // endpoints are known, even change=0, so callers can render "measured, steady" honestly
            // rather than treating it identically to "never measured".
            //
            // But a single sample used for BOTH start and end (startAt == endAt -- e.g. the only
            // sample that exists sits before an otherwise-empty window, so the same row satisfies
            // both searches) is not a measurement of change at all, just one snapshot in time --
            // that genuinely has no coverage to report and must still be skipped. Comparing
            // timestamps rather than values is what tells the two cases apart: two distinct samples
            // that happen to carry equal values are a real zero-change measurement; one sample
            // matched twice is not a measurement at all.
            if (start == null || startAt.equals(endAt)) continue;
            deltas.add(new Delta(metric, start, end, end - start, startAt, endAt));
        }
        return deltas;
    }

    // ---- named windows ------------------------------------------------------

    /**
     * The 08:30 "wake" snapshot fires AT 08:30 but timestamps a bit later once navigation + OCR
     * finish (observed 08:30:43 live). Without grace, that purpose-built end-of-night reading
     * lands just past an exact 08:30:00 cutoff and gets excluded, so "last night" would end at the
     * prior hourly sample instead. Extend the window end by this grace so the wake anchor is
     * always included.
     */
    private static final long WAKE_ANCHOR_GRACE_MINUTES = 20;

    public List<Delta> lastNight(ZoneId zone, LocalTime sleepStart, LocalTime wakeEnd) {
        LocalDate today = LocalDate.now(zone);
        Instant from = today.minusDays(1).atTime(sleepStart).atZone(zone).toInstant();
        Instant to = today.atTime(wakeEnd).plusMinutes(WAKE_ANCHOR_GRACE_MINUTES).atZone(zone).toInstant();
        return deltaOverWindow(from, to);
    }

    public List<Delta> last(long amount, ChronoUnit unit) {
        Instant now = Instant.now();
        return deltaOverWindow(now.minus(amount, unit), now);
    }

    public List<Delta> total() {
        if (samples.size() < 2) return new ArrayList<>();
        return deltaOverWindow(samples.get(0).at(), samples.get(samples.size() - 1).at());
    }

    // ---- real recorded coverage -----------------------------------------------------------
    // A window LABEL like "Last night (23:00-08:30)" describes the intended window, not what was
    // actually captured -- if telemetry was gapped or only caught two samples three
    // hours apart, the label alone hides that. This exposes the REAL first/last sample timestamps
    // a window's delta was actually built from, so the UI can show both.

    /** The actual [firstSampleAt, lastSampleAt] a window's delta was built from -- not the
     *  requested window bounds, the real timestamps of the samples used. Null when there's
     *  nothing to show (matches deltaOverWindow's own "not enough data" case). */
    public record Coverage(Instant actualFrom, Instant actualTo) {}

    /**
     * Reports the envelope of the per-metric baseline/end timestamps {@link #deltaOverWindow}
     * actually used, rather than an unrelated global first/last sample pair -- since each metric
     * finds its own baseline/end sample independently, the true coverage for the numbers shown is
     * the span of THOSE timestamps. Returns null when there are no deltas to report, matching
     * {@code deltaOverWindow}'s own empty case.
     */
    public Coverage coverageForWindow(Instant from, Instant to) {
        List<Delta> deltas = deltaOverWindow(from, to);
        if (deltas.isEmpty()) {
            return null;
        }
        Instant earliest = null;
        Instant latest = null;
        for (Delta d : deltas) {
            if (earliest == null || d.startAt().isBefore(earliest)) earliest = d.startAt();
            if (latest == null || d.endAt().isAfter(latest)) latest = d.endAt();
        }
        return new Coverage(earliest, latest);
    }

    public Coverage coverageForLastNight(ZoneId zone, LocalTime sleepStart, LocalTime wakeEnd) {
        LocalDate today = LocalDate.now(zone);
        Instant from = today.minusDays(1).atTime(sleepStart).atZone(zone).toInstant();
        Instant to = today.atTime(wakeEnd).plusMinutes(WAKE_ANCHOR_GRACE_MINUTES).atZone(zone).toInstant();
        return coverageForWindow(from, to);
    }

    public Coverage coverageForLast(long amount, ChronoUnit unit) {
        Instant now = Instant.now();
        return coverageForWindow(now.minus(amount, unit), now);
    }

    public Coverage coverageForTotal() {
        if (samples.size() < 2) {
            return null;
        }
        return new Coverage(samples.get(0).at(), samples.get(samples.size() - 1).at());
    }

    // ---- activity ("what the bot did") --------------------------------------

    /** Human-readable names for the activity keys worth surfacing, in display order. */
    private static final Map<String, String> ACTIVITY_LABELS = new LinkedHashMap<>();
    static {
        // Accomplishments ONLY -- never scan/run tallies. Every tile is a ctr.* counter the game
        // code increments when the thing actually happens; run.* task-execution counts are
        // intentionally excluded. Labels kept short so they don't truncate.
        ACTIVITY_LABELS.put("ctr.Intel Beast", "Beasts hunted");
        ACTIVITY_LABELS.put("ctr.Intel Journeys", "Journeys scouted");
        ACTIVITY_LABELS.put("ctr.Intel Survivor Camps", "Survivor camps");
        ACTIVITY_LABELS.put("ctr.Gather Marches Deployed", "Gather marches");
        ACTIVITY_LABELS.put("ctr.Daily Missions Claimed", "Daily missions");
        ACTIVITY_LABELS.put("ctr.Growth Missions Claimed", "Growth missions");
        ACTIVITY_LABELS.put("ctr.Mail Rewards Claimed", "Mail rewards");
        ACTIVITY_LABELS.put("ctr.Exploration Fights Won", "Exploration wins");
        ACTIVITY_LABELS.put("ctr.Arena Battles Won", "Arena wins");
        ACTIVITY_LABELS.put("ctr.Beast Attacks Sent", "Beast attacks");
        ACTIVITY_LABELS.put("ctr.Storehouse Chests Opened", "Storehouse chests");
        ACTIVITY_LABELS.put("ctr.Alliance Gifts Collected", "Alliance chests");
        ACTIVITY_LABELS.put("ctr.Pet Adventure Chests", "Pet chests");
        ACTIVITY_LABELS.put("ctr.Alliance Triumph Rewards", "Triumph rewards");
    }

    /** Latest sample at/before cutoff that actually carries activity fields. */
    private Sample latestActivityAtOrBefore(Instant cutoff) {
        Sample found = null;
        for (Sample s : samples) {
            if (s.at().isAfter(cutoff)) break;
            if (!s.activity().isEmpty()) found = s;
        }
        return found;
    }

    /** Earliest sample at/after from that actually carries activity fields. */
    private Sample earliestActivityAtOrAfter(Instant from) {
        for (Sample s : samples) {
            if (!s.at().isBefore(from) && !s.activity().isEmpty()) return s;
        }
        return null;
    }

    /**
     * Uses the same at-or-before baseline semantics as {@link #deltaOverWindow}: prefer the last
     * activity-bearing sample AT OR BEFORE the window opened (the actual state right when the
     * window started) over always using the earliest in-window sample, which with the hourly
     * writer normally means exactly one such sample exists in a short window (e.g. "Past Hour") --
     * start and end become the same row, and every activity count reads as zero even though real
     * activity happened. Falls back to the earliest in-window sample only when nothing predates
     * the window (the older resource-only rows written before activity capture existed, or this is
     * the very first
     * activity-bearing sample ever).
     */
    List<Activity> activityOverWindow(Instant from, Instant to) {
        List<Activity> out = new ArrayList<>();
        Sample endS = latestActivityAtOrBefore(to);
        if (endS == null) {
            return out;
        }
        Sample startS = latestActivityAtOrBefore(from);
        if (startS == null || !startS.at().isBefore(endS.at())) {
            startS = earliestActivityAtOrAfter(from);
        }
        if (startS == null || !startS.at().isBefore(endS.at())) {
            return out;
        }
        for (Map.Entry<String, String> entry : ACTIVITY_LABELS.entrySet()) {
            Long s = startS.activity().get(entry.getKey());
            Long e = endS.activity().get(entry.getKey());
            if (s == null || e == null) continue;
            long change = e - s;
            // Zero counts. A counter present at both ends WAS measured, and reporting nothing
            // for it is indistinguishable from never having measured it -- which is what made
            // a quiet window look like a window with no data. Negative changes are still
            // dropped: those are counter resets, not activity.
            if (change >= 0) out.add(new Activity(entry.getValue(), change));
        }
        return out;
    }

    public List<Activity> activityLastNight(ZoneId zone, LocalTime sleepStart, LocalTime wakeEnd) {
        LocalDate today = LocalDate.now(zone);
        Instant from = today.minusDays(1).atTime(sleepStart).atZone(zone).toInstant();
        Instant to = today.atTime(wakeEnd).plusMinutes(WAKE_ANCHOR_GRACE_MINUTES).atZone(zone).toInstant();
        return activityOverWindow(from, to);
    }

    public List<Activity> activityLast(long amount, ChronoUnit unit) {
        Instant now = Instant.now();
        return activityOverWindow(now.minus(amount, unit), now);
    }

    /**
     * All-time accomplishments. The activity counters are cumulative, so the latest snapshot's
     * values ARE the totals — show them directly rather than diffing, which also means this
     * populates immediately (a window delta needs two activity-bearing samples).
     */
    public List<Activity> activityTotal() {
        Sample latest = latestActivityAtOrBefore(Instant.now());
        List<Activity> out = new ArrayList<>();
        if (latest == null) return out;
        for (Map.Entry<String, String> entry : ACTIVITY_LABELS.entrySet()) {
            Long v = latest.activity().get(entry.getKey());
            if (v != null && v > 0) out.add(new Activity(entry.getValue(), v));
        }
        return out;
    }
}
