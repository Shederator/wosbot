package dev.frostguard.tasks.analytics;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.JobMetrics;
import dev.frostguard.api.domain.ProfilesData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.schedule.TelemetrySnapshotSchedule;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.engine.telemetry.TelemetryHistoryStore;
import dev.frostguard.vision.ocr.HudNumberParser;
import dev.frostguard.vision.ocr.PlausibilityBand;

/**
 * Samples the top HUD on a schedule and appends the result to the workspace's
 * per-profile telemetry history.
 *
 * <p>This exists because the external Node scraper that used to do this drove
 * ADB itself, so it could not run while the bot was running — the two fought
 * over the same device. Running the capture as a task inside the bot's own
 * queue removes that conflict by construction, and inherits the engine's
 * screen-verification and retry behaviour for free.
 */
public final class TelemetrySnapshotRoutine extends DelayedTask {

    private static final Set<String> LIVE_METRIC_FIELDS = Set.of("power", "coal", "gems");

    private final TelemetryHistoryStore historyStore;

    /**
     * HUD regions, in the required 720x1280 frame. Measured against live
     * captures rather than guessed. The slot left of the temperature readout is
     * deliberately unmapped: it shows population on the City view and a UTC
     * clock on the World view, so it cannot be trusted as a single field.
     */
    // Each crop starts AFTER its icon. Verified against a live frame: the coal
    // slot (the only one with no icon inside the crop) read correctly first
    // time, while power and gems both had their icon in-frame and OCR folded
    // its edges into the digits - the diamond turned 56,112 into 596,256.
    // Measured on a magnified frame: the diamond icon ends at x=572, the digits
    // run 591-667, and the green "+" starts at 688. 578 sits in the clean gap.
    // Both earlier attempts failed by landing on a glyph edge rather than in the
    // gap - 590 clipped the leading "5" (read as 596,256) and 608 cut it off
    // entirely (read as 5,256).

    /* HUD crop regions and OCR presets are shared so saved-frame tests exercise production data. */

    public TelemetrySnapshotRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
        if (tpTask != TpDailyTaskEnum.TELEMETRY_SNAPSHOT) {
            throw new IllegalArgumentException("Unsupported telemetry task: " + tpTask);
        }
        historyStore = TelemetryHistoryStore.forCurrentWorkspace(profile.getId());
        reschedule(LocalDateTime.now());
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        // The resource HUD is identical on City and World, but pinning to WORLD
        // gives the engine one deterministic screen to return to.
        return LaunchPoint.WORLD;
    }

    @Override
    protected void execute() {
        logInfo("Sampling telemetry HUD.");

        // Root-caused against real history.jsonl data: gems was intermittently flip-flopping
        // between two distinct bands roughly 40,000 apart sample to sample (e.g. 90,629 then
        // 50,739 then back to 90,499) while power trended smoothly the whole time -- a real
        // OCR misread, not an actual gem swing (confirmed: a live screenshot mid-session showed
        // the true value sitting in the lower band). The crop itself checked out clean against a
        // fresh screenshot, so this is intermittent (a different screen state at capture time,
        // not a static coordinate bug) and not worth chasing blind. Same "decline rather than
        // guess" pattern used throughout this codebase: reject a reading that jumps implausibly
        // from the last known-good sample instead of trusting it, so a bad OCR frame produces a
        // gap in the graph, never a fake spike/drop.
        Map<String, Object> lastKnownGood = readLastKnownSample();

        // Sanity-checking applies to all three fields this task actually reads via OCR itself
        // (meat/wood/iron/steel/speedups below are cached config values from a different task's
        // scan, not read here, so there's no fresh OCR result for this check to validate against)
        // -- limiting it to only power/gems would leave coal's live OCR read unvalidated even
        // though it goes through the exact same misread-prone path.
        // Each metric carries its own band: power moves gradually, while coal and gems are spent
        // in lumps and legitimately fall by most of their value in a single step. See
        // PlausibilityBand for why a shared, symmetric band suppressed real spends.
        Long power = sanityCheckAgainstLastKnown("power", PlausibilityBand.POWER,
                readScaledNumber(CommonGameAreas.TELEMETRY_POWER_OCR_AREA,
                        CommonOCRSettings.TELEMETRY_FULL_NUMBER_SETTINGS, "power"), lastKnownGood);
        Long coal = sanityCheckAgainstLastKnown("coal", PlausibilityBand.COAL,
                readScaledNumber(CommonGameAreas.TELEMETRY_COAL_OCR_AREA,
                        CommonOCRSettings.TELEMETRY_ABBREVIATED_NUMBER_SETTINGS, "coal"), lastKnownGood);
        Long gems = sanityCheckAgainstLastKnown("gems", PlausibilityBand.GEMS,
                readScaledNumber(CommonGameAreas.TELEMETRY_GEMS_OCR_AREA,
                        CommonOCRSettings.TELEMETRY_FULL_NUMBER_SETTINGS, "gems"), lastKnownGood);

        // A frame where nothing at all resolved almost always means we are not
        // actually on the HUD (a popup, an event takeover). Recording that as a
        // row of nulls would poison the history the dashboard graphs, so skip
        // the write and let the next run pick it up.
        if (power == null && coal == null && gems == null) {
            logWarning("No telemetry HUD values resolved; skipping this sample.");
            scheduleNext();
            return;
        }

        // All four resources for the Statistics tab's "resources earned over time"
        // reports. The top HUD only ever shows one resource, so meat/wood/iron come
        // from the values ResourceStockpileRoutine last scanned (stored in config) —
        // no extra navigation, and they change slowly enough that the last scan is
        // fine for a graph. Coal stays the live HUD read (same resource, fresher).
        Long meat = readStockpile(ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LONG);
        Long wood = readStockpile(ConfigurationKeyEnum.RESOURCE_STOCKPILE_WOOD_LONG);
        Long iron = readStockpile(ConfigurationKeyEnum.RESOURCE_STOCKPILE_IRON_LONG);
        if (coal == null) {
            coal = readStockpile(ConfigurationKeyEnum.RESOURCE_STOCKPILE_COAL_LONG);
        }

        // Steel + the five speedup buckets, all sourced from the config keys the
        // Resource & Speedup Summary reader (ResourceStockpileRoutine) last cached.
        // Speedups are stored/logged in MINUTES; the Statistics tab formats them back to durations.
        Long steel = readStockpile(ConfigurationKeyEnum.RESOURCE_STOCKPILE_STEEL_LONG);
        Long spGeneral = readStockpile(ConfigurationKeyEnum.SPEEDUP_GENERAL_MIN_LONG);
        Long spTraining = readStockpile(ConfigurationKeyEnum.SPEEDUP_TRAINING_MIN_LONG);
        Long spConstruction = readStockpile(ConfigurationKeyEnum.SPEEDUP_CONSTRUCTION_MIN_LONG);
        Long spResearch = readStockpile(ConfigurationKeyEnum.SPEEDUP_RESEARCH_MIN_LONG);
        Long spHealing = readStockpile(ConfigurationKeyEnum.SPEEDUP_HEALING_MIN_LONG);

        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("capturedAt", LocalDateTime.now(ZoneOffset.UTC).toString() + "Z");
        sample.put("profile", profile.getName());
        sample.put("power", power);
        sample.put("gems", gems);
        sample.put("meat", meat);
        sample.put("wood", wood);
        sample.put("coal", coal);
        sample.put("iron", iron);
        sample.put("steel", steel);
        sample.put("sp_general", spGeneral);
        sample.put("sp_training", spTraining);
        sample.put("sp_construction", spConstruction);
        sample.put("sp_research", spResearch);
        sample.put("sp_healing", spHealing);

        // Activity snapshot: the running task-run counts and action counters, flattened as
        // "run.<Task>" / "ctr.<Counter>" number fields. The Statistics tab diffs these between two
        // snapshots to show "what the bot DID" over a window (27 intel runs, 6 pet adventures, ...).
        appendActivitySnapshot(sample);

        try {
            historyStore.append(sample);
        } catch (IOException exception) {
            logError("Could not write telemetry history to " + historyStore.directory() + ": "
                    + exception.getMessage(), exception);
        }

        logInfo("Telemetry snapshot: power=" + power + " gems=" + gems
                + " meat=" + meat + " wood=" + wood + " coal=" + coal + " iron=" + iron
                + " steel=" + steel + " sp(gen/tr/con/res/heal)=" + spGeneral + "/" + spTraining
                + "/" + spConstruction + "/" + spResearch + "/" + spHealing);
        scheduleNext();
    }

    /**
     * The "Last night" report needs clean bookends, so always take an inventory snapshot exactly
     * at 23:00 (bedtime) and 08:30 (wake) local, on top of the normal interval. Next run is the
     * soonest of those two anchors or now+interval. 08:30 matches TelemetryReport's
     * WAKE_ANCHOR_GRACE_MINUTES, which documents an observed real capture at 08:30:43 -- keeping
     * the two in sync matters, since a drift between them silently excludes the wake snapshot from
     * the "last night" window it exists to bookend.
     */
    private void scheduleNext() {
        setRecurring(true);
        reschedule(TelemetrySnapshotSchedule.nextRun(
                LocalDateTime.now(), TelemetrySnapshotSchedule.configuredInterval(profile)));
    }

    /**
     * Reads a HUD number, resolving the game's abbreviated form. Returns null
     * rather than a guess when OCR gives nothing usable — a wrong number is
     * worse than a missing one in a history meant for graphing.
     *
     * <p>{@link #readStringValue} accepts any non-null string as a successful attempt, so a
     * malformed-but-non-empty OCR read (garbage that fails {@link #parseScaled}) never triggered a
     * retry -- it consumed the whole attempt budget on one bad frame instead of trying again.
     * Calling {@code stringHelper} directly makes parseability itself the retry acceptor, so a bad
     * read gets the same up-to-5-attempts/200ms retry budget a genuinely empty read already got.
     */
    private Long readScaledNumber(AreaData area, OcrSettingsData settings, String label) {
        // stringHelper is typed ResilientOcrExecutor<String>, so the transformer must still return
        // String -- parsing happens right after, but the acceptor below is what actually moves
        // parseability into the retry decision.
        String raw = stringHelper.attemptRecognition(area.topLeft(), area.bottomRight(), 5, 200L, settings,
                candidate -> candidate != null && parseScaled(candidate) != null,
                candidate -> candidate);
        Long parsed = raw == null ? null : parseScaled(raw);
        if (parsed == null) {
            logWarning("No parseable telemetry OCR reading for " + label + " after retries.");
        }
        return parsed;
    }

    /* PlausibilityBand is shared with ResourceStockpileRoutine and selected per metric above. */

    /**
     * Rejects a candidate reading that jumps implausibly far from the last known-good value for
     * the same field, returning null (a graph gap) instead of a likely-wrong number. Passes
     * through unchanged when there's nothing to compare against (first-ever sample, previous
     * value missing, or the candidate is already null).
     */
    private Long sanityCheckAgainstLastKnown(String field, PlausibilityBand band, Long candidate,
                                             Map<String, Object> lastKnownGood) {
        if (candidate == null || lastKnownGood == null) {
            return candidate;
        }
        Object prevObj = lastKnownGood.get(field);
        if (!(prevObj instanceof Long)) {
            return candidate;
        }
        long prev = (Long) prevObj;
        if (!band.isPlausible(candidate, prev)) {
            double ratio = (double) candidate / (double) prev;
            logWarning("Telemetry " + field + " reading " + candidate + " is implausibly far from the "
                    + "last known-good " + prev + " (ratio " + String.format("%.2f", ratio)
                    + ", believable band for this metric " + band + ") -- rejecting as a likely OCR "
                    + "misread rather than recording a fake swing.");
            return null;
        }
        return candidate;
    }

    /** Reads this profile's previous numeric fields for plausibility checks. */
    private Map<String, Object> readLastKnownSample() {
        try {
            return historyStore.readLastKnownNumericFields(LIVE_METRIC_FIELDS);
        } catch (IOException exception) {
            return null;
        }
    }

    /**
     * Reads a resource stockpile value that ResourceStockpileRoutine stored in config.
     * Returns null (not 0) when it has never been scanned, so a graph shows a gap rather
     * than a misleading zero.
     */
    private Long readStockpile(ConfigurationKeyEnum key) {
        try {
            Long value = profile.getConfig(key, Long.class);
            return (value == null || value <= 0L) ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parses "11,914,539", "6.7M", "32784" and "1.2B" to a plain long.
     * Package-visible so the parsing rules can be exercised directly.
     */
    static Long parseScaled(String raw) {
        return HudNumberParser.parseScaled(raw);
    }

    /**
     * Adds the current activity totals (per-task run counts and action counters) to the sample as
     * flat number fields. Reads them straight from {@link StatisticsService} — no JSON parsing —
     * so the Statistics tab can diff two snapshots into "what the bot did" for a window.
     */
    private void appendActivitySnapshot(Map<String, Object> sample) {
        try {
            ProfilesData stats = StatisticsService.obtain().loadMetrics(profile);
            if (stats == null) return;
            if (stats.getTaskStatistics() != null) {
                for (Map.Entry<String, JobMetrics> e : stats.getTaskStatistics().entrySet()) {
                    if (e.getValue() != null) {
                        sample.put("run." + e.getKey(), e.getValue().getNumberOfRuns());
                    }
                }
            }
            if (stats.getCustomCounters() != null) {
                for (Map.Entry<String, Integer> e : stats.getCustomCounters().entrySet()) {
                    sample.put("ctr." + e.getKey(), e.getValue());
                }
            }
        } catch (Exception e) {
            logWarning("Could not snapshot telemetry activity stats: " + e.getMessage());
        }
    }
}
