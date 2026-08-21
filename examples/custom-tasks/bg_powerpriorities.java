package dev.frostguard.engine.listener.task.impl;

import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Bearguard power-composition capture: opens Chief Profile -> Power breakdown
 * on a schedule, OCRs the graded category table (Building/Troop/Hero/Hero
 * Gear/Chief Gear/Tech/Pet Power - grade, current/cap, percentile), and
 * writes the result as JSON for the Whiteout dashboard's Power & Priorities
 * tab to read. Same pattern as bg_telemetry, but for the deeper Power dialog
 * rather than the always-visible top HUD.
 *
 * <p>Deliberately additive: a new file under custom_tasks/, no upstream
 * source touched, so merges from Shederator/wosbot stay clean.
 *
 * <p>All coordinates below were measured directly against a live 720x1280
 * capture of this account's Power dialog on 2026-08-05 (not guessed) - see
 * scratchpad grid_top.png / grid_scrolled.png from that session if the game
 * ever reflows this screen and the crops need re-measuring.
 */
public class bg_powerpriorities extends DelayedTask implements CustomTaskConfigurable {

    // Standing cadence policy: anything live-checkable
    // runs every 2 hours; only the real-money shop walkthrough (not yet
    // built) gets the slower 24-hour cadence.
    private static final Duration DEFAULT_INTERVAL = Duration.ofHours(2);

    // Chief Profile avatar (top-left HUD portrait) -> Power magnifier next to
    // the Total Power figure on the profile card -> "Power" button on the
    // resulting Bonus Overview popup opens the graded breakdown dialog.
    private static final PointData CHIEF_AVATAR = new PointData(44, 44);
    private static final PointData POWER_MAGNIFIER = new PointData(408, 1005);
    private static final PointData BONUS_OVERVIEW_POWER_BUTTON = new PointData(360, 727);

    // Scroll gestures. The rest-position after opening the dialog is a hard
    // top clamp (reliably reproducible - verified pixel-identical across
    // repeats). The bottom is only reliably reproducible if the swipe travels
    // far enough to hit ITS clamp too: a short swipe lands at a slightly
    // different offset every time (momentum/elastic scroll drift - verified
    // this drifts ~15px run to run), whereas a long swipe past the bottom
    // clamps identically every time (verified pixel-identical across 2 runs).
    private static final PointData SCROLL_UP_START = new PointData(360, 1150);
    private static final PointData SCROLL_UP_END = new PointData(360, 250);

    // Header, fixed regardless of scroll state (the summary card above the
    // scrolling category list never moves).
    private static final PointData FURNACE_TL = new PointData(225, 268);
    private static final PointData FURNACE_BR = new PointData(400, 300);
    private static final PointData TOTAL_POWER_TL = new PointData(225, 303);
    private static final PointData TOTAL_POWER_BR = new PointData(496, 332);
    private static final PointData OVERALL_GRADE_TL = new PointData(615, 276);
    private static final PointData OVERALL_GRADE_BR = new PointData(652, 320);
    private static final PointData OVERALL_PCT_TL = new PointData(390, 361);
    private static final PointData OVERALL_PCT_BR = new PointData(424, 380);

    // Category rows repeat every 104px in the unscrolled ("top") capture,
    // which shows Building/Troop/Hero/Hero Gear/Chief Gear/Tech fully (rows
    // 0-5). Pet Power (row 6) is clipped by the bottom edge there, so it is
    // read from the clamped-bottom capture instead, at a fixed measured
    // y (not a row-step extrapolation - the two captures aren't guaranteed
    // to be an exact multiple of ROW_STEP apart).
    private static final int ROW_STEP = 104;
    private static final int TOP_ROW0_Y = 427;
    private static final int PET_ROW_Y = 988;

    // Column offsets within a row, relative to that row's label y-coordinate.
    // Grade offset is intentionally generous (-4/+27, not the tighter
    // 0/+22 the top rows alone would need) because it was measured against
    // both captures and the extra margin is blank background either way.
    private static final int GRADE_X_TL = 494;
    private static final int GRADE_X_BR = 518;
    private static final int GRADE_Y_OFFSET_TOP = -4;
    private static final int GRADE_Y_OFFSET_BOTTOM = 27;
    private static final int BAR_X_TL = 45;
    private static final int BAR_X_BR = 513;
    private static final int BAR_Y_OFFSET_TOP = 31;
    private static final int BAR_Y_OFFSET_BOTTOM = 53;
    private static final int PCT_X_TL = 183;
    private static final int PCT_X_BR = 236;
    private static final int PCT_Y_OFFSET_TOP = 58;
    private static final int PCT_Y_OFFSET_BOTTOM = 86;

    private static final String[] CATEGORY_NAMES = {
            "Building Power", "Troop Power", "Hero Power",
            "Hero Gear Power", "Chief Gear Power", "Tech Power", "Pet Power"
    };

    // Grade badges render as solid orange glyphs (measured ~RGB(236,117,28),
    // gradient-shaded but within isolation tolerance) - isolating that colour
    // and reading as a single word was the only setting that reliably beat
    // the busy pastel background across all 7 rows in live testing.
    private static final TesseractSettingsData GRADE_SETTINGS =
            TesseractSettingsData.assembler()
                    .charWhitelist("SABCD")
                    .pageAnalysis(PageAnalysis.SINGLE_WORD)
                    .stripBackground(true)
                    .setTextColor(new Color(236, 117, 28))
                    .build();

    private static final TesseractSettingsData BAR_VALUE_SETTINGS =
            TesseractSettingsData.assembler()
                    .charWhitelist("0123456789,/")
                    .pageAnalysis(PageAnalysis.SINGLE_LINE)
                    .stripBackground(true)
                    .setTextColor(Color.WHITE)
                    .build();

    // No colour isolation here - the percentile number is a blue similar
    // enough to nearby anti-aliased label-text edges that isolating it
    // picked up false positives; a tight crop on just the digits (excluding
    // the "Overpowered "/" of Chiefs..." label text on either side) plus a
    // plain digit whitelist was what actually worked in live testing.
    private static final TesseractSettingsData PCT_SETTINGS =
            TesseractSettingsData.assembler()
                    .charWhitelist("0123456789%")
                    .pageAnalysis(PageAnalysis.SINGLE_LINE)
                    .build();

    private Duration interval = DEFAULT_INTERVAL;

    public bg_powerpriorities(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
        // See bg_telemetry for why this must be LocalDateTime.now() (local
        // time) rather than a UTC instant - the queue compares local time.
        reschedule(LocalDateTime.now());
    }

    @Override
    protected Object getDistinctKey() {
        return "bg_powerpriorities";
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    @Override
    public void applyCustomTaskSettings(CustomTaskService.CustomTaskSettings settings) {
        // Deliberately ignores settings.getFollowUpDelayHours() - that field
        // is hours-granularity (the app itself writes a default of 8 back to
        // custom_tasks.json once this task is loaded), which can't express
        // the ~30-minute cadence matt asked for. DEFAULT_INTERVAL is the
        // real, fixed cadence for this task.
        interval = DEFAULT_INTERVAL;
    }

    @Override
    protected void execute() {
        logInfo("bg_powerpriorities | Opening Power breakdown.");

        if (!openPowerBreakdown()) {
            logWarning("bg_powerpriorities | Could not reach the Power breakdown dialog. Skipping this sample.");
            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);
            scheduleNext();
            return;
        }

        Integer furnaceLevel = readNumberValue(FURNACE_TL, FURNACE_BR,
                TesseractSettingsData.assembler().charWhitelist("0123456789").pageAnalysis(PageAnalysis.SINGLE_LINE).build());
        Long totalPower = readScaledNumber(TOTAL_POWER_TL, TOTAL_POWER_BR);
        String overallGrade = readGrade(OVERALL_GRADE_TL, OVERALL_GRADE_BR);
        Integer overallPct = readPercent(OVERALL_PCT_TL, OVERALL_PCT_BR);

        List<Map<String, Object>> categories = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            categories.add(readCategoryRow(CATEGORY_NAMES[i], TOP_ROW0_Y + i * ROW_STEP));
        }

        // Pet Power (row 6) is below the fold in the top capture - a long
        // swipe past the list's bottom clamp brings it fully on-screen at a
        // fixed, reproducible position (see PET_ROW_Y comment above).
        swipe(SCROLL_UP_START, SCROLL_UP_END);
        sleepTask(400);
        emuManager.captureScreen(EMULATOR_NUMBER);
        categories.add(readCategoryRow(CATEGORY_NAMES[6], PET_ROW_Y));

        closePowerBreakdown();

        boolean anyResolved = totalPower != null || categories.stream().anyMatch(c -> c.get("current") != null);
        if (!anyResolved) {
            logWarning("bg_powerpriorities | No values resolved - not on the expected screen. Skipping this sample.");
            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);
            scheduleNext();
            return;
        }

        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("capturedAt", LocalDateTime.now(ZoneOffset.UTC).toString() + "Z");
        sample.put("profile", profile.getName());
        sample.put("furnaceLevel", furnaceLevel);
        sample.put("totalPower", totalPower);
        sample.put("overallGrade", overallGrade);
        sample.put("overallPercentile", overallPct);
        sample.put("categories", categories);

        writeSample(toJson(sample));
        logInfo("bg_powerpriorities | furnace=" + furnaceLevel + " totalPower=" + totalPower
                + " overallGrade=" + overallGrade + " categoriesRead="
                + categories.stream().filter(c -> c.get("current") != null).count() + "/7");

        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);
        scheduleNext();
    }

    private boolean openPowerBreakdown() {
        tapPoint(CHIEF_AVATAR);
        sleepTask(600);
        emuManager.captureScreen(EMULATOR_NUMBER);

        tapPoint(POWER_MAGNIFIER);
        sleepTask(500);
        emuManager.captureScreen(EMULATOR_NUMBER);

        tapPoint(BONUS_OVERVIEW_POWER_BUTTON);
        sleepTask(500);
        emuManager.captureScreen(EMULATOR_NUMBER);

        Long check = readScaledNumber(TOTAL_POWER_TL, TOTAL_POWER_BR);
        return check != null;
    }

    private void closePowerBreakdown() {
        // Three stacked dialogs (Power breakdown -> Bonus Overview -> Chief
        // Profile) - back should unwind them one at a time.
        pressBack();
        sleepTask(300);
        pressBack();
        sleepTask(300);
        pressBack();
        sleepTask(300);
    }

    private void scheduleNext() {
        setRecurring(true);
        reschedule(LocalDateTime.now().plus(interval));
    }

    private Map<String, Object> readCategoryRow(String name, int rowLabelY) {
        String grade = readGrade(
                new PointData(GRADE_X_TL, rowLabelY + GRADE_Y_OFFSET_TOP),
                new PointData(GRADE_X_BR, rowLabelY + GRADE_Y_OFFSET_BOTTOM));

        String barText = readStringValue(
                new PointData(BAR_X_TL, rowLabelY + BAR_Y_OFFSET_TOP),
                new PointData(BAR_X_BR, rowLabelY + BAR_Y_OFFSET_BOTTOM),
                BAR_VALUE_SETTINGS);

        Integer pct = readPercent(
                new PointData(PCT_X_TL, rowLabelY + PCT_Y_OFFSET_TOP),
                new PointData(PCT_X_BR, rowLabelY + PCT_Y_OFFSET_BOTTOM));

        Long current = null;
        Long cap = null;
        if (barText != null && barText.contains("/")) {
            String[] parts = barText.replace(",", "").split("/", 2);
            try {
                current = Long.parseLong(parts[0].trim());
                cap = Long.parseLong(parts[1].trim());
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                logWarning("bg_powerpriorities | Unparseable bar text for " + name + ": '" + barText + "'");
            }
        } else if (barText != null) {
            logWarning("bg_powerpriorities | No '/' in bar text for " + name + ": '" + barText + "'");
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("grade", grade);
        row.put("current", current);
        row.put("cap", cap);
        row.put("percentile", pct);
        return row;
    }

    private String readGrade(PointData tl, PointData br) {
        String raw = readStringValue(tl, br, GRADE_SETTINGS);
        if (raw == null) {
            return null;
        }
        String cleaned = raw.trim().toUpperCase().replaceAll("[^SABCD]", "");
        return cleaned.isEmpty() ? null : cleaned;
    }

    private Integer readPercent(PointData tl, PointData br) {
        String raw = readStringValue(tl, br, PCT_SETTINGS);
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Reads "13,199,810" style full-precision numbers (no K/M/B here). */
    private Long readScaledNumber(PointData tl, PointData br) {
        String raw = readStringValue(tl, br,
                TesseractSettingsData.assembler()
                        .charWhitelist("0123456789,:")
                        .pageAnalysis(PageAnalysis.SINGLE_LINE)
                        .build());
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void writeSample(String json) {
        Path dir = Paths.get(System.getProperty("user.dir"), "telemetry");
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve("power_composition_history.jsonl"),
                    (json + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.write(dir.resolve("power_composition_latest.json"),
                    json.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logError("bg_powerpriorities | Could not write telemetry to " + dir + ": " + e.getMessage());
        }
    }

    /** Minimal serializer - handles the flat + list-of-maps shape this task needs. */
    @SuppressWarnings("unchecked")
    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            writeValue(sb, e.getValue());
        }
        return sb.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Number) {
            sb.append(v);
        } else if (v instanceof Map) {
            sb.append(toJson((Map<String, Object>) v));
        } else if (v instanceof List) {
            sb.append('[');
            List<Object> list = (List<Object>) v;
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                writeValue(sb, list.get(i));
            }
            sb.append(']');
        } else {
            sb.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
    }
}
