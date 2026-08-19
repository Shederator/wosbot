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
 * Reads the telemetry history that {@code bg_telemetry} appends to
 * {@code data/telemetry/profiles/<id>/history.jsonl} and turns it into the "what did the bot
 * earn" reports the Statistics tab shows.
 *
 * <p>matt, 2026-08-09: he wants the Statistics page to answer real questions —
 * "how many resources did I gather overnight", "how much power / how many gems
 * did botting earn me today / this week / total" — instead of run counts. Each
 * report is a delta between the snapshot at the start of a window and the most
 * recent one, so it reads directly off the same history the bot already logs.</p>
 *
 * <p>Dave's #250 review, 2026-08-18: previously read {@code telemetry/history.jsonl} off
 * {@code user.dir} and filtered by profile NAME within one shared file -- and a row with no
 * "profile" field (or a null caller-supplied name) was accepted for every profile, not rejected.
 * Now that {@code bg_telemetry} writes one file per profile ID under the workspace, {@link #load}
 * just opens that profile's own file directly. There is nothing left to filter, so that bug class
 * is gone by construction rather than patched.</p>
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

    /** A start→end change in one metric over a window. */
    public record Delta(String metric, Long start, Long end, Long change) {}

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
        // bg_telemetry writes an ISO local-datetime with a trailing 'Z' already on
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

    /** Latest sample whose time is at or before {@code cutoff}, or null. */
    private Sample latestAtOrBefore(Instant cutoff) {
        Sample found = null;
        for (Sample s : samples) {
            if (!s.at().isAfter(cutoff)) found = s; else break;
        }
        return found;
    }

    /** Earliest sample whose time is at or after {@code from}, or null. */
    private Sample earliestAtOrAfter(Instant from) {
        for (Sample s : samples) {
            if (!s.at().isBefore(from)) return s;
        }
        return null;
    }

    /**
     * Change in every metric between the first sample at/after {@code from} and the last
     * at/before {@code to} that actually carries that metric.
     *
     * <p>Dave's #250 review: this used to anchor every metric's END value to the single latest
     * overall sample, so a metric simply missing from THAT one row (a transient OCR miss, or a
     * row written before that metric existed) vanished from the whole window even though earlier
     * in-window samples had it. Each metric now finds its own latest carrying sample independently,
     * matching how the start side already worked -- a transient gap in one field no longer hides
     * every field.</p>
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
            // Per-metric baseline: the earliest sample in-window that actually carries this metric.
            // Older rows predate meat/wood/iron capture, so a single global start sample would drop
            // them entirely — this lets each resource show as soon as it has two data points.
            Long start = null;
            for (Sample s : samples) {
                if (s.at().isBefore(from)) continue;
                if (s.at().isAfter(endAt)) break;
                Long v = s.get(metric);
                if (v != null) { start = v; break; }
            }
            // Dave's #250/#251 review + matt live, 2026-08-19: a genuinely zero-change metric
            // (real start AND end samples, equal values) used to be skipped here just like a
            // metric with NO coverage at all -- the caller couldn't tell "measured, no change"
            // from "no data", and StatisticsLayoutController's fallback for the latter (show the
            // raw current stockpile, unlabeled as a delta) fired for BOTH cases. When telemetry
            // gaps for days, every metric hits that fallback and the tab silently displays a raw
            // absolute value (e.g. current power) sitting in the "what did you earn" grid --
            // exactly what read as "gained 24 million power" overnight. Always emit a Delta once
            // both endpoints are known, even change=0, so the caller can render "steady" honestly
            // instead of a bare number that looks like a headline gain.
            if (start == null) continue;
            deltas.add(new Delta(metric, start, end, end - start));
        }
        return deltas;
    }

    // ---- named windows ------------------------------------------------------

    /**
     * matt/2026-08-10: the 08:30 "wake" snapshot fires AT 08:30 but timestamps a bit later once
     * navigation + OCR finish (observed 08:30:43). Without grace, that purpose-built end-of-night
     * reading lands just past an exact 08:30:00 cutoff and gets excluded, so "last night" ended at the
     * prior hourly sample instead. Extend the window end by this grace so the wake anchor is always in.
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

    // ---- real recorded coverage (matt/2026-08-15) ---------------------------
    // "I just don't trust these statistics... put like at the top the timeframe that it was
    // recorded." A window LABEL like "Last night (23:00-08:30)" describes the intended window,
    // not what was actually captured -- if bg_telemetry was disabled, gapped, or only caught two
    // samples three hours apart, the label alone hides that. This exposes the REAL first/last
    // sample timestamps a window's delta was actually built from, so the UI can show both.

    /** The actual [firstSampleAt, lastSampleAt] a window's delta was built from -- not the
     *  requested window bounds, the real timestamps of the samples used. Null when there's
     *  nothing to show (matches deltaOverWindow's own "not enough data" case). */
    public record Coverage(Instant actualFrom, Instant actualTo) {}

    public Coverage coverageForWindow(Instant from, Instant to) {
        Sample endS = latestAtOrBefore(to);
        if (endS == null) {
            return null;
        }
        Sample startS = earliestAtOrAfter(from);
        if (startS == null || startS.at().isAfter(endS.at())) {
            return null;
        }
        return new Coverage(startS.at(), endS.at());
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
        // matt/2026-08-10: accomplishments ONLY — never scan/run tallies ("I only care about things
        // accomplished"). Every tile is a ctr.* counter the game code increments when the thing actually
        // happens. run.* task-execution counts were all removed. Labels kept short so they don't truncate.
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

    private List<Activity> activityOverWindow(Instant from, Instant to) {
        List<Activity> out = new ArrayList<>();
        // Baseline off the earliest sample that actually has activity data — the older
        // resource-only rows (before activity capture existed) would otherwise zero it out.
        Sample startS = earliestActivityAtOrAfter(from);
        Sample endS = latestActivityAtOrBefore(to);
        if (startS == null || endS == null || !startS.at().isBefore(endS.at())) {
            return out;
        }
        for (Map.Entry<String, String> entry : ACTIVITY_LABELS.entrySet()) {
            Long s = startS.activity().get(entry.getKey());
            Long e = endS.activity().get(entry.getKey());
            if (s == null || e == null) continue;
            long change = e - s;
            if (change > 0) out.add(new Activity(entry.getValue(), change));
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
