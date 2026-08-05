package dev.frostguard.engine.listener.task.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.api.domain.TesseractSettingsData.PageAnalysis;
import dev.frostguard.engine.schedule.CustomTaskConfigurable;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.CustomTaskService;

/**
 * Bearguard telemetry: samples the top HUD on a schedule and appends the result
 * to a JSON history the Whiteout dashboard reads.
 *
 * <p>This exists because the external Node scraper that used to do this drove
 * ADB itself, so it could not run while the bot was running — the two fought
 * over the same device. Running the capture as a task inside the bot's own
 * queue removes that conflict by construction, and inherits the engine's
 * screen-verification and retry behaviour for free.
 *
 * <p>Deliberately additive: a new file under custom_tasks/, no upstream source
 * touched, so merges from Shederator/wosbot stay clean.
 */
public class bg_telemetry extends DelayedTask implements CustomTaskConfigurable {

    private static final Duration DEFAULT_INTERVAL = Duration.ofHours(1);
    private static final DateTimeFormatter UTC_INPUT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * HUD regions, in the required 720x1280 frame. Measured against live
     * captures rather than guessed. The slot left of the temperature readout is
     * deliberately unmapped: it shows population on the City view and a UTC
     * clock on the World view, so it cannot be trusted as a single field.
     */
    private static final PointData POWER_TL = new PointData(95, 45);
    private static final PointData POWER_BR = new PointData(270, 100);
    private static final PointData COAL_TL = new PointData(430, 0);
    private static final PointData COAL_BR = new PointData(515, 40);
    private static final PointData GEMS_TL = new PointData(550, 0);
    private static final PointData GEMS_BR = new PointData(675, 40);
    private static final PointData TEMP_TL = new PointData(305, 45);
    private static final PointData TEMP_BR = new PointData(415, 100);

    /**
     * The HUD renders white text over a busy scene. Whitelisting the separator
     * and magnitude characters matters: the game abbreviates large values
     * ("6.7M") but prints others in full ("11,914,539"), and a digits-only
     * whitelist silently turns the former into 67.
     */
    private static final TesseractSettingsData HUD_NUMBER_SETTINGS =
            TesseractSettingsData.assembler()
                    .charWhitelist("0123456789.,KMB")
                    .pageAnalysis(PageAnalysis.SINGLE_LINE)
                    .stripBackground(true)
                    .setTextColor(new java.awt.Color(255, 255, 255))
                    .build();

    private static final TesseractSettingsData HUD_TEMPERATURE_SETTINGS =
            TesseractSettingsData.assembler()
                    .charWhitelist("-0123456789.")
                    .pageAnalysis(PageAnalysis.SINGLE_LINE)
                    .stripBackground(true)
                    .setTextColor(new java.awt.Color(255, 255, 255))
                    .build();

    private Duration interval = DEFAULT_INTERVAL;

    public bg_telemetry(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
        reschedule(LocalDateTime.now(ZoneOffset.UTC));
    }

    @Override
    protected Object getDistinctKey() {
        return "bg_telemetry";
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        // The resource HUD is identical on City and World, but pinning to WORLD
        // gives the engine one deterministic screen to return to.
        return LaunchPoint.WORLD;
    }

    @Override
    public void applyCustomTaskSettings(CustomTaskService.CustomTaskSettings settings) {
        if (settings == null) {
            return;
        }
        Integer hours = settings.getFollowUpDelayHours();
        interval = hours != null && hours > 0 ? Duration.ofHours(hours) : DEFAULT_INTERVAL;

        String first = settings.getFirstExecutionUtc();
        if (first != null && !first.isBlank()) {
            try {
                reschedule(LocalDateTime.parse(first, UTC_INPUT_FORMATTER));
            } catch (RuntimeException e) {
                logWarning("bg_telemetry | Unparseable first-execution time '" + first + "', starting immediately.");
            }
        }
    }

    @Override
    protected void execute() {
        logInfo("bg_telemetry | Sampling HUD.");

        Long power = readScaledNumber(POWER_TL, POWER_BR, "power");
        Long coal = readScaledNumber(COAL_TL, COAL_BR, "coal");
        Long gems = readScaledNumber(GEMS_TL, GEMS_BR, "gems");
        Double temperature = readTemperature();

        // A frame where nothing at all resolved almost always means we are not
        // actually on the HUD (a popup, an event takeover). Recording that as a
        // row of nulls would poison the history the dashboard graphs, so skip
        // the write and let the next run pick it up.
        if (power == null && coal == null && gems == null) {
            logWarning("bg_telemetry | No HUD values resolved - not on the expected screen. Skipping this sample.");
            scheduleNext();
            return;
        }

        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("capturedAt", LocalDateTime.now(ZoneOffset.UTC).toString() + "Z");
        sample.put("profile", profile.getName());
        sample.put("power", power);
        sample.put("coal", coal);
        sample.put("gems", gems);
        sample.put("temperatureC", temperature);

        String json = toJson(sample);
        writeSample(json);

        logInfo("bg_telemetry | power=" + power + " coal=" + coal + " gems=" + gems + " tempC=" + temperature);
        scheduleNext();
    }

    private void scheduleNext() {
        setRecurring(true);
        reschedule(LocalDateTime.now(ZoneOffset.UTC).plus(interval));
    }

    /**
     * Reads a HUD number, resolving the game's abbreviated form. Returns null
     * rather than a guess when OCR gives nothing usable — a wrong number is
     * worse than a missing one in a history meant for graphing.
     */
    private Long readScaledNumber(PointData tl, PointData br, String label) {
        String raw = readStringValue(tl, br, HUD_NUMBER_SETTINGS);
        if (raw == null || raw.isBlank()) {
            logWarning("bg_telemetry | No OCR text for " + label + ".");
            return null;
        }
        Long parsed = parseScaled(raw);
        if (parsed == null) {
            logWarning("bg_telemetry | Unparseable " + label + " reading: '" + raw.trim() + "'");
        }
        return parsed;
    }

    /**
     * Parses "11,914,539", "6.7M", "32784" and "1.2B" to a plain long.
     * Package-visible so the parsing rules can be exercised directly.
     */
    static Long parseScaled(String raw) {
        String s = raw.trim().replace(",", "").replace(" ", "");
        if (s.isEmpty()) {
            return null;
        }

        long multiplier = 1L;
        char last = s.charAt(s.length() - 1);
        if (last == 'K' || last == 'M' || last == 'B') {
            multiplier = last == 'K' ? 1_000L : last == 'M' ? 1_000_000L : 1_000_000_000L;
            s = s.substring(0, s.length() - 1);
        }

        if (s.isEmpty()) {
            return null;
        }
        try {
            // Parsed as a double because the abbreviated form carries a decimal
            // ("6.7M"); the un-abbreviated form never does, so this is lossless
            // for the values the HUD actually shows.
            return (long) (Double.parseDouble(s) * multiplier);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double readTemperature() {
        String raw = readStringValue(TEMP_TL, TEMP_BR, HUD_TEMPERATURE_SETTINGS);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim().replace(" ", ""));
        } catch (NumberFormatException e) {
            logWarning("bg_telemetry | Unparseable temperature reading: '" + raw.trim() + "'");
            return null;
        }
    }

    /**
     * Appends to a JSON Lines history and overwrites a latest-sample file.
     * JSONL is used for the history so a run can never corrupt earlier samples
     * by rewriting a whole document, which matters for something appending
     * unattended overnight.
     */
    private void writeSample(String json) {
        Path dir = Paths.get(System.getProperty("user.dir"), "telemetry");
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve("history.jsonl"),
                    (json + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.write(dir.resolve("latest.json"),
                    json.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logError("bg_telemetry | Could not write telemetry to " + dir + ": " + e.getMessage());
        }
    }

    /** Minimal serializer — the project ships no JSON binding usable from here. */
    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number) {
                sb.append(v);
            } else {
                sb.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
        }
        return sb.append('}').toString();
    }
}
