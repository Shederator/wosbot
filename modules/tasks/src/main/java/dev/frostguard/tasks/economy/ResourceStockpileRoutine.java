package dev.frostguard.tasks.economy;

import java.awt.Color;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;

/**
 * Reads current Meat/Wood/Coal/Iron totals and caches them to profile config for
 * {@link GatherRoutine}'s Smart Gathering.
 *
 * <p><b>Source screen:</b> tapping the top-bar resource counter opens the
 * <b>Overview → Resource Production</b> panel, which lists all four gather resources with their
 * "Owned" total on one screen. Unlike a research-cost panel, it is always available and does not
 * disappear once tech nodes are maxed.
 *
 * <p><b>Steel and speedups</b> are read from the Backpack "Resource &amp; Speedup Summary" popup in
 * the same pass.
 *
 * <p>Every fixed-coordinate tap is followed by a landmark check ({@code verifyLandedOn}); a read is
 * skipped rather than OCR'd from an unverified screen. Gated on {@code GATHER_SMART_PRIORITY_BOOL}
 * (the "Smart Gathering" checkbox); runs hourly.
 */
public class ResourceStockpileRoutine extends DelayedTask {

    private static final Duration DEFAULT_INTERVAL = Duration.ofHours(1);

    // ── Navigation (720x1280) ──
    /** Top-bar resource pouch counter; tapping it opens Overview → Resource Production. */
    private static final PointData RESOURCE_COUNTER = new PointData(392, 22);
    /** "Resource Production" tab at the bottom of the Overview panel (usually already active). */
    private static final PointData RESOURCE_PRODUCTION_TAB = new PointData(525, 1216);
    /** "X" that closes the Overview panel (top-right of its header). */
    private static final PointData CLOSE_OVERVIEW_X = new PointData(690, 358);

    // "Owned" total crops — the LARGE top number in each resource row (the smaller
    // shield/protected teal number ~20px below is deliberately excluded).
    private static final PointData MEAT_TL = new PointData(452, 495);
    private static final PointData MEAT_BR = new PointData(568, 528);
    private static final PointData WOOD_TL = new PointData(452, 608);
    private static final PointData WOOD_BR = new PointData(568, 641);
    private static final PointData COAL_TL = new PointData(452, 721);
    private static final PointData COAL_BR = new PointData(568, 754);
    private static final PointData IRON_TL = new PointData(452, 833);
    private static final PointData IRON_BR = new PointData(568, 866);

    private static final OcrSettingsData OWNED_TEXT_SETTINGS =
            OcrSettingsData.assembler()
                    .charWhitelist("0123456789.,KMB")
                    .textLayout(TextLayout.SINGLE_LINE)
                    .stripBackground(true)
                    .setTextColor(new Color(61, 92, 140)) // measured digit color; being off in blue rendered ".8M" as invisible gray
                    .build();

    // ── Steel + Speedups: Backpack → chart button → "Resource & Speedup Summary" ──
    private static final PointData BACKPACK_NAV = new PointData(305, 1255);       // bottom-nav Backpack
    private static final PointData SUMMARY_CHART_BUTTON = new PointData(682, 40); // chart button (NOT 712 — dead corner)
    private static final PointData SPEEDUP_TAB = new PointData(547, 355);         // "Speedup" tab in the popup
    private static final PointData SUMMARY_CLOSE_X = new PointData(697, 258);     // popup close X
    // The popup opens on the "Resources" tab, so Steel's "Total Resources" cell is read before
    // switching to Speedup.
    //
    // stripBackground(false): the decimal point is anti-aliased against the background, so colour
    // isolation (CHANNEL_TOLERANCE around the text colour) erases it and "811.71K" reads as
    // "811 71K". The raw crop has enough contrast on the light popup background to keep the dot.
    // The larger, wider-spaced Speedup durations below read fine isolated. The crop is padded a few
    // pixels so descenders are not clipped.
    private static final PointData STEEL_TL = new PointData(494, 796);
    private static final PointData STEEL_BR = new PointData(622, 842);
    private static final OcrSettingsData STEEL_TEXT_SETTINGS =
            OcrSettingsData.assembler()
                    .charWhitelist("0123456789.,KMB")
                    .textLayout(TextLayout.SINGLE_LINE)
                    .stripBackground(false)
                    .build();
    // "Total Speedup" duration crops (x418-672, ~90px row pitch).
    private static final PointData SPD_GENERAL_TL = new PointData(418, 456);
    private static final PointData SPD_GENERAL_BR = new PointData(672, 494);
    private static final PointData SPD_TRAINING_TL = new PointData(418, 546);
    private static final PointData SPD_TRAINING_BR = new PointData(672, 584);
    private static final PointData SPD_CONSTRUCTION_TL = new PointData(418, 636);
    private static final PointData SPD_CONSTRUCTION_BR = new PointData(672, 674);
    private static final PointData SPD_RESEARCH_TL = new PointData(418, 726);
    private static final PointData SPD_RESEARCH_BR = new PointData(672, 764);
    private static final PointData SPD_HEALING_TL = new PointData(418, 816);
    private static final PointData SPD_HEALING_BR = new PointData(672, 854);

    // Speedup durations render as muted navy text ("1 day(s)7 hr(s)28 min"). Keep the unit letters so
    // readDurationMinutes can regex out each of day/hr/min. Text colour is (81,104,143).
    private static final OcrSettingsData SPEEDUP_TEXT_SETTINGS =
            OcrSettingsData.assembler()
                    .charWhitelist("0123456789 dayhrmins()")
                    .textLayout(TextLayout.SINGLE_LINE)
                    .stripBackground(true)
                    .setTextColor(new Color(81, 104, 143))
                    .build();

    private static final Pattern DAYS_PAT = Pattern.compile("(\\d+)\\s*day");
    private static final Pattern HRS_PAT  = Pattern.compile("(\\d+)\\s*hr");
    private static final Pattern MIN_PAT  = Pattern.compile("(\\d+)\\s*min");

    private Duration interval = DEFAULT_INTERVAL;

    public ResourceStockpileRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
        reschedule(LocalDateTime.now());
    }

    // Does not override getDistinctKey(): a distinct key would store task state under
    // "902:<key>" while the task list reads plain "902", freezing the displayed last-run time.

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        // WORLD, not HOME: the resource counter tap opens the Overview panel from the World map.
        // From Base City the tap misses and the crops read city pixels.
        return LaunchPoint.WORLD;
    }

    @Override
    protected void execute() {
        Boolean smartGatheringOn = profile.getConfig(ConfigurationKeyEnum.GATHER_SMART_PRIORITY_BOOL, Boolean.class);
        if (!Boolean.TRUE.equals(smartGatheringOn)) {
            reschedule(LocalDateTime.now().plus(interval));
            return;
        }

        logInfo("ResourceStockpileRoutine | Scanning Meat/Wood/Coal/Iron from the Overview panel.");

        Map<String, Long> read = readOverviewPanel();

        if (read != null) {
            // OCR can drop the decimal point in the abbreviated form ("84.9M" read as "849M"), a
            // 10x swing that would steer Smart Gathering. Each value is checked against the last
            // value this routine cached before it is accepted.
            Long meat = sanityCheckAgainstCached("meat", read.get("meat"),
                    ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LONG);
            Long wood = sanityCheckAgainstCached("wood", read.get("wood"),
                    ConfigurationKeyEnum.RESOURCE_STOCKPILE_WOOD_LONG);
            Long coal = sanityCheckAgainstCached("coal", read.get("coal"),
                    ConfigurationKeyEnum.RESOURCE_STOCKPILE_COAL_LONG);
            Long iron = sanityCheckAgainstCached("iron", read.get("iron"),
                    ConfigurationKeyEnum.RESOURCE_STOCKPILE_IRON_LONG);

            // Each field's LAST_READ timestamp advances only when that field was accepted, so a
            // rejected value never looks fresh because a sibling was read in the same pass.
            String now = LocalDateTime.now().toString();
            if (meat != null) {
                profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LONG, meat);
                profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LAST_READ_STRING, now);
            }
            if (wood != null) {
                profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_WOOD_LONG, wood);
                profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_WOOD_LAST_READ_STRING, now);
            }
            if (coal != null) {
                profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_COAL_LONG, coal);
                profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_COAL_LAST_READ_STRING, now);
            }
            if (iron != null) {
                profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_IRON_LONG, iron);
                profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_IRON_LAST_READ_STRING, now);
            }
            // Shared timestamp, read by GatherRoutine only when a per-field timestamp is missing.
            if (meat != null || wood != null || coal != null || iron != null) {
                profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_LAST_READ_STRING, now);
            }
            setShouldUpdateConfig(true);
            logInfo("ResourceStockpileRoutine | Stockpiles cached: meat=" + meat + " wood=" + wood
                    + " coal=" + coal + " iron=" + iron + " (raw OCR: " + read + ")");
        } else {
            logWarning("ResourceStockpileRoutine | Overview panel unreadable this cycle. Leaving the "
                    + "last cached stockpile values in place (GatherRoutine falls back to blind rotation).");
        }

        // Speedups live on a different screen (Backpack → Summary → Speedup tab). Read them in the
        // same cycle so the stats track speedup gain/loss alongside resources.
        readAndCacheSpeedups();

        navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);
        setRecurring(true);
        reschedule(LocalDateTime.now().plus(interval));
    }

    /**
     * Confirms a fixed-coordinate tap reached the intended screen before anything is OCR'd from it.
     *
     * <p>A slow animation, an overlapping dialog or a UI shift would otherwise OCR whatever is on
     * screen. The landmarks are cropped from saved frames of each screen; a bounded retry allows
     * for the animation before the read is skipped.</p>
     */
    private boolean verifyLandedOn(TemplatesEnum landmark, String screenLabel) {
        ImageSearchResultData hit = templateSearchHelper.locatePattern(landmark,
                SearchConfig.builder().withMaxAttempts(3).withDelay(250L).withThreshold(85).build());
        if (hit == null || !hit.isFound()) {
            logWarning("ResourceStockpileRoutine | Could not confirm arrival on " + screenLabel
                    + " (landmark " + landmark + " not detected) -- skipping this read rather than OCR-ing blind.");
            return false;
        }
        return true;
    }

    /** Opens Backpack → Resource &amp; Speedup Summary, OCRs Steel on the default "Resources" tab,
     *  then switches to the Speedup tab and OCRs the five speedup totals to minutes. */
    private void readAndCacheSpeedups() {
        try {
            tapNear(BACKPACK_NAV);
            sleepTask(1600);
            if (!verifyLandedOn(TemplatesEnum.RESOURCE_STOCKPILE_BACKPACK_SCREEN_TITLE, "Backpack screen")) {
                return;
            }

            tapNear(SUMMARY_CHART_BUTTON);
            // The popup slides in; at ~1.4s the Steel cell can still be mid-animation.
            sleepTask(2200);
            if (!verifyLandedOn(TemplatesEnum.RESOURCE_STOCKPILE_SUMMARY_POPUP_TITLE, "Resource & Speedup Summary popup")) {
                return;
            }

            // Opens on "Resources": read Steel before switching tabs.
            String steelRaw = readStringValue(STEEL_TL, STEEL_BR, STEEL_TEXT_SETTINGS);
            Long steel = sanityCheckAgainstCached("steel",
                    (steelRaw == null || steelRaw.isBlank()) ? null : parseScaled(steelRaw.trim()),
                    ConfigurationKeyEnum.RESOURCE_STOCKPILE_STEEL_LONG);
            if (steel != null) {
                profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_STEEL_LONG, steel);
                setShouldUpdateConfig(true);
            }
            logInfo("ResourceStockpileRoutine | Steel cached: " + steel);

            tapNear(SPEEDUP_TAB);
            sleepTask(1000);
            if (!verifyLandedOn(TemplatesEnum.RESOURCE_STOCKPILE_SPEEDUP_TAB_HEADER, "Speedup tab")) {
                // Close the popup, but do not OCR speedups from an unverified tab.
                tapNear(SUMMARY_CLOSE_X);
                sleepTask(300);
                return;
            }

            Long gen  = readDurationMinutes(SPD_GENERAL_TL, SPD_GENERAL_BR);
            Long tr   = readDurationMinutes(SPD_TRAINING_TL, SPD_TRAINING_BR);
            Long con  = readDurationMinutes(SPD_CONSTRUCTION_TL, SPD_CONSTRUCTION_BR);
            Long res  = readDurationMinutes(SPD_RESEARCH_TL, SPD_RESEARCH_BR);
            Long heal = readDurationMinutes(SPD_HEALING_TL, SPD_HEALING_BR);

            tapNear(SUMMARY_CLOSE_X);
            sleepTask(300);

            if (gen != null)  profile.setConfig(ConfigurationKeyEnum.SPEEDUP_GENERAL_MIN_LONG, gen);
            if (tr != null)   profile.setConfig(ConfigurationKeyEnum.SPEEDUP_TRAINING_MIN_LONG, tr);
            if (con != null)  profile.setConfig(ConfigurationKeyEnum.SPEEDUP_CONSTRUCTION_MIN_LONG, con);
            if (res != null)  profile.setConfig(ConfigurationKeyEnum.SPEEDUP_RESEARCH_MIN_LONG, res);
            if (heal != null) profile.setConfig(ConfigurationKeyEnum.SPEEDUP_HEALING_MIN_LONG, heal);
            setShouldUpdateConfig(true);
            logInfo("ResourceStockpileRoutine | Speedups cached (min): general=" + gen + " training=" + tr
                    + " construction=" + con + " research=" + res + " healing=" + heal);
        } catch (Exception e) {
            logWarning("ResourceStockpileRoutine | Speedup read failed this cycle: " + e.getMessage());
        }
    }

    private Long readDurationMinutes(PointData tl, PointData br) {
        String raw = readStringValue(tl, br, SPEEDUP_TEXT_SETTINGS);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        long total = 0;
        boolean any = false;
        Matcher d = DAYS_PAT.matcher(raw); if (d.find()) { total += Long.parseLong(d.group(1)) * 1440L; any = true; }
        Matcher h = HRS_PAT.matcher(raw);  if (h.find()) { total += Long.parseLong(h.group(1)) * 60L;   any = true; }
        Matcher m = MIN_PAT.matcher(raw);  if (m.find()) { total += Long.parseLong(m.group(1));         any = true; }
        return any ? total : null;
    }

    private Map<String, Long> readOverviewPanel() {
        // The resource counter opens Overview directly on the Resource Production tab.
        tapNear(RESOURCE_COUNTER);
        sleepTask(2600); // let the panel fully slide+render before OCR
        if (!verifyLandedOn(TemplatesEnum.RESOURCE_STOCKPILE_OVERVIEW_PANEL_TITLE, "Overview panel")) {
            return null;
        }

        Long meat = readOwned(MEAT_TL, MEAT_BR);
        Long wood = readOwned(WOOD_TL, WOOD_BR);
        Long coal = readOwned(COAL_TL, COAL_BR);
        Long iron = readOwned(IRON_TL, IRON_BR);

        tapNear(CLOSE_OVERVIEW_X);
        sleepTask(300);

        if (meat == null || wood == null || coal == null || iron == null) {
            logDebug("ResourceStockpileRoutine | Owned read incomplete: meat=" + meat + " wood=" + wood
                    + " coal=" + coal + " iron=" + iron);
            return null;
        }

        Map<String, Long> out = new LinkedHashMap<>();
        out.put("meat", meat);
        out.put("wood", wood);
        out.put("coal", coal);
        out.put("iron", iron);
        return out;
    }

    /** Ratio band outside which a new reading is treated as a likely OCR misread rather than a real
     *  change. Stockpiles change gradually between hourly reads; a dropped decimal point jumps ~10x,
     *  far outside this band. */
    private static final double SANITY_BAND_MAX_RATIO = 1.5;
    private static final double SANITY_BAND_MIN_RATIO = 1.0 / SANITY_BAND_MAX_RATIO;

    /** The cache is compared against the last accepted value, so a bad value that got in, or a
     *  large legitimate change (a big upgrade spend or claim), would otherwise reject every later
     *  correct reading forever. Consecutive rejected readings that agree with each other are
     *  stronger evidence than one old cached number: once REJECT_STREAK_TO_TRUST is reached, the
     *  streak is trusted and the cache catches up. In-memory, so a restart costs at most one extra
     *  recovery cycle. */
    private static final int REJECT_STREAK_TO_TRUST = 3;
    private final Map<String, Long> rejectStreakAnchor = new java.util.HashMap<>();
    private final Map<String, Integer> rejectStreakCount = new java.util.HashMap<>();

    /**
     * Rejects a candidate reading that jumps implausibly far from the value this routine last
     * cached for the same field, returning null (config keeps its last known-good value and
     * GatherRoutine falls back as on any other unreadable cycle), unless the candidate is the
     * latest in a streak of mutually consistent rejected readings (see field doc above).
     *
     * <p>Tesseract can drop the decimal point when two digits precede it ("87.4M" read as
     * "874M"), which would leave Meat/Wood permanently rejected. A candidate that falls back into
     * the band after dividing by 10 is taken as that specific misread and corrected; anything else
     * outside the band is rejected and counts toward the streak.
     */
    private Long sanityCheckAgainstCached(String field, Long candidate, ConfigurationKeyEnum cacheKey) {
        if (candidate == null) {
            return null;
        }
        Long cached;
        try {
            cached = profile.getConfig(cacheKey, Long.class);
        } catch (Exception e) {
            cached = null;
        }
        if (cached == null || cached <= 0L) {
            rejectStreakCount.remove(field);
            rejectStreakAnchor.remove(field);
            return candidate;
        }
        if (inBand(candidate, cached)) {
            rejectStreakCount.remove(field);
            rejectStreakAnchor.remove(field);
            return candidate;
        }
        long corrected = candidate / 10L;
        if (inBand(corrected, cached)) {
            logWarning("ResourceStockpileRoutine | " + field + " reading " + candidate + " is implausibly "
                    + "far from the last cached " + cached + ", but /10 (" + corrected + ") fits -- this is "
                    + "the known dropped-decimal-point misread, using the corrected value.");
            rejectStreakCount.remove(field);
            rejectStreakAnchor.remove(field);
            return corrected;
        }

        Long anchor = rejectStreakAnchor.get(field);
        int streak = (anchor != null && inBand(candidate, anchor)) ? rejectStreakCount.getOrDefault(field, 0) + 1 : 1;
        rejectStreakAnchor.put(field, candidate);
        rejectStreakCount.put(field, streak);

        if (streak >= REJECT_STREAK_TO_TRUST) {
            logWarning("ResourceStockpileRoutine | " + field + " has now read consistently near "
                    + candidate + " for " + streak + " consecutive cycles while cached stays at "
                    + cached + " -- trusting the consistent new readings over the stale cache.");
            rejectStreakCount.remove(field);
            rejectStreakAnchor.remove(field);
            return candidate;
        }

        logWarning("ResourceStockpileRoutine | " + field + " reading " + candidate + " is implausibly "
                + "far from the last cached " + cached + " (ratio " + String.format("%.2f", (double) candidate / cached)
                + ", /10 correction didn't fit either) -- rejecting for now, keeping the last known-good "
                + "value (streak toward trusting a consistent new reading: " + streak + "/" + REJECT_STREAK_TO_TRUST + ").");
        return null;
    }

    private static boolean inBand(long candidate, long cached) {
        double ratio = (double) candidate / (double) cached;
        return ratio <= SANITY_BAND_MAX_RATIO && ratio >= SANITY_BAND_MIN_RATIO;
    }

    private Long readOwned(PointData tl, PointData br) {
        String raw = readStringValue(tl, br, OWNED_TEXT_SETTINGS);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return parseScaled(raw.trim());
    }

    /** Parses "72.3M", "583,853", "1.2M" etc to a plain long. */
    static Long parseScaled(String raw) {
        String s = raw.trim().replace(",", "").replace(" ", "");
        if (s.isEmpty()) {
            return null;
        }
        long multiplier = 1L;
        char last = s.charAt(s.length() - 1);
        boolean abbreviated = last == 'K' || last == 'M' || last == 'B';
        if (abbreviated) {
            multiplier = last == 'K' ? 1_000L : last == 'M' ? 1_000_000L : 1_000_000_000L;
            s = s.substring(0, s.length() - 1);
        } else {
            s = s.replace(".", "");
        }
        if (s.isEmpty()) {
            return null;
        }
        try {
            return (long) (Double.parseDouble(s) * multiplier);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
