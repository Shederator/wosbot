package dev.frostguard.tasks.economy;

import java.awt.Color;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.api.domain.TesseractSettingsData.PageAnalysis;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;

/**
 * Reads current Meat/Wood/Coal/Iron warehouse totals and caches them to profile
 * config for {@link GatherRoutine}'s Smart Gathering and for {@code bg_telemetry}
 * (which reads meat/wood/iron from these config keys).
 *
 * <p><b>Source screen (matt-directed + live-verified 2026-08-10):</b> tapping the
 * top-bar resource counter (the pouch the HUD rotates to, e.g. "11.4M") opens the
 * <b>Overview → Resource Production</b> panel, which lists all four gather
 * resources with their real "Owned" total on ONE screen (no scrolling). This
 * screen is always available and never maxes out — unlike the prior tech-node
 * "Research Cost" hack, which on a mature account found every candidate node MAXED
 * and so left stale/garbage stockpile values frozen (meat cached at 235M while the
 * real Owned total was 72.3M).
 *
 * <p>The four "Owned" crops below were verified by cropping a live 720x1280 capture
 * of this panel and confirming they cleanly frame 72.3M / 79.8M / 11.4M / 4.3M.
 *
 * <p><b>Steel + speedups</b> live on the richer Backpack "Resource &amp; Speedup
 * Summary" popup (needs its own calibration pass); their config keys stay dormant
 * until that reader is calibrated, and their Statistics tiles simply don't render
 * while unpopulated.
 *
 * <p>Gated on {@code GATHER_SMART_PRIORITY_BOOL} (the "Smart Gathering" checkbox);
 * force-run at startup via {@code ScheduleService} so the stats have fresh totals
 * the moment the bot launches, then hourly.
 */
public class ResourceStockpileRoutine extends DelayedTask {

    private static final Duration DEFAULT_INTERVAL = Duration.ofHours(1);

    // ── Navigation (720x1280, live-verified 2026-08-10) ──
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

    private static final TesseractSettingsData OWNED_TEXT_SETTINGS =
            TesseractSettingsData.assembler()
                    .charWhitelist("0123456789.,KMB")
                    .pageAnalysis(PageAnalysis.SINGLE_LINE)
                    .stripBackground(true)
                    .setTextColor(new Color(61, 92, 140)) // measured digit color; being off in blue rendered ".8M" as invisible gray
                    .build();

    // ── Steel + Speedups: Backpack → chart button → "Resource & Speedup Summary" (verified live) ──
    private static final PointData BACKPACK_NAV = new PointData(305, 1255);       // bottom-nav Backpack
    private static final PointData SUMMARY_CHART_BUTTON = new PointData(682, 40); // chart button (NOT 712 — dead corner)
    private static final PointData SPEEDUP_TAB = new PointData(547, 355);         // "Speedup" tab in the popup
    private static final PointData SUMMARY_CLOSE_X = new PointData(697, 258);     // popup close X
    // matt/2026-08-15 "fix all": Steel sat dormant (config key never written) because no reader was
    // ever calibrated for it. The popup's default landing tab is "Resources" (Meat/Wood/Coal/Iron/
    // Steel/Chief Stamina, one screen, no scroll) -- same popup the Speedup tab already reads, just
    // read BEFORE switching tabs. "Total Resources" column crop, verified against a live capture
    // showing "811.71K".
    //
    // matt/2026-08-15: first two live passes both misread this as "117MK" regardless of settle
    // delay. Root-caused by replaying TesseractOcrProvider's exact preprocessing in isolation
    // (color-distance-from-target -> grayscale, see cropAndPreprocess): the decimal point's pixels
    // are anti-aliased against the background, so their colour sits far enough from the pure text
    // colour (81,104,143) that CHANNEL_TOLERANCE washes the dot out to pure white before Tesseract
    // ever sees it -- "811.71K" becomes "811 71K" with no dot at all, and different OCR passes
    // segment/guess the two fragments differently. The raw (non-isolated) crop keeps the dot fine on
    // its own (dark digits on a light, fairly uniform blue popup background give plenty of natural
    // contrast) -- stripBackground(false) here, unlike the Speedup durations below which are larger
    // text with more spacing and read fine isolated. Widened a few px on every edge too so descenders
    // aren't clipped at the crop boundary.
    private static final PointData STEEL_TL = new PointData(494, 796);
    private static final PointData STEEL_BR = new PointData(622, 842);
    private static final TesseractSettingsData STEEL_TEXT_SETTINGS =
            TesseractSettingsData.assembler()
                    .charWhitelist("0123456789.,KMB")
                    .pageAnalysis(PageAnalysis.SINGLE_LINE)
                    .stripBackground(false)
                    .build();
    // "Total Speedup" duration crops (x418-672, ~90px row pitch, verified against a live capture).
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
    // parseDurationMinutes can regex out each of day/hr/min. Colour measured live at (81,104,143).
    private static final TesseractSettingsData SPEEDUP_TEXT_SETTINGS =
            TesseractSettingsData.assembler()
                    .charWhitelist("0123456789 dayhrmins()")
                    .pageAnalysis(PageAnalysis.SINGLE_LINE)
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

    // matt/2026-08-10: intentionally does NOT override getDistinctKey(). A non-null distinct key made
    // execution write task-state under key "902:resource_stockpile_scan" while the Control→Tasks UI
    // reads plain "902" (written once at startup by the force-run), so the tile froze at "Ready, last
    // run 1h ago" forever even though the task ran. TIMER_SWEEP is immune precisely because it doesn't
    // override this. Nothing dequeues this task by that string, so dropping the override is safe.

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        // matt/2026-08-10: WORLD, not HOME. The engine forces this screen before execute()
        // (DelayedTask.run → ensureCorrectScreenLocation). The top-bar resource counter + Overview
        // panel live on the World map; declaring HOME parked the game on Base City, so the (480,22)
        // tap missed the panel and the crops OCR'd city pixels (meat=1, coal=12). Every other
        // top-bar/HUD reader (bg_telemetry, GatherRoutine) pins to WORLD.
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
            // matt/2026-08-15: "check the last couple days" turned up meat/wood/iron swinging 10x
            // between consecutive hourly reads (e.g. 84.9M then 849M then back) while power/coal
            // trended smoothly the same hours -- an OCR misread of the "." in the abbreviated form
            // (Tesseract dropping the decimal point turns "84.9M" into "849M"), not a real stockpile
            // swing. That misread was going straight into config, which GatherRoutine's Smart
            // Gathering also reads -- so this wasn't just corrupting the stats graph, it was
            // corrupting live gather-priority decisions. Same "reject an implausible jump against the
            // last known-good value" guard used in bg_telemetry for power/gems, applied here against
            // the config value this routine itself last wrote (since this IS the writer).
            Long meat = sanityCheckAgainstCached("meat", read.get("meat"),
                    ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LONG);
            Long wood = sanityCheckAgainstCached("wood", read.get("wood"),
                    ConfigurationKeyEnum.RESOURCE_STOCKPILE_WOOD_LONG);
            Long coal = sanityCheckAgainstCached("coal", read.get("coal"),
                    ConfigurationKeyEnum.RESOURCE_STOCKPILE_COAL_LONG);
            Long iron = sanityCheckAgainstCached("iron", read.get("iron"),
                    ConfigurationKeyEnum.RESOURCE_STOCKPILE_IRON_LONG);

            if (meat != null) profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_MEAT_LONG, meat);
            if (wood != null) profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_WOOD_LONG, wood);
            if (coal != null) profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_COAL_LONG, coal);
            if (iron != null) profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_IRON_LONG, iron);
            profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_LAST_READ_STRING,
                    LocalDateTime.now().toString());
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

    /** Opens Backpack → Resource &amp; Speedup Summary, OCRs Steel on the default "Resources" tab,
     *  then switches to the Speedup tab and OCRs the five speedup totals to minutes. */
    private void readAndCacheSpeedups() {
        try {
            tapPoint(BACKPACK_NAV);
            sleepTask(1600);
            tapPoint(SUMMARY_CHART_BUTTON);
            // matt/2026-08-15: first live pass at 1400ms caught the popup slide-in mid-animation and
            // OCR'd a garbled "117MK" for Steel (correctly rejected as unparseable, but still a wasted
            // cycle) -- the manual calibration pass that measured the crop used ~2s and read cleanly.
            sleepTask(2200);

            // Lands on "Resources" by default -- read Steel here before switching tabs.
            String steelRaw = readStringValue(STEEL_TL, STEEL_BR, STEEL_TEXT_SETTINGS);
            Long steel = sanityCheckAgainstCached("steel",
                    (steelRaw == null || steelRaw.isBlank()) ? null : parseScaled(steelRaw.trim()),
                    ConfigurationKeyEnum.RESOURCE_STOCKPILE_STEEL_LONG);
            if (steel != null) {
                profile.setConfig(ConfigurationKeyEnum.RESOURCE_STOCKPILE_STEEL_LONG, steel);
                setShouldUpdateConfig(true);
            }
            logInfo("ResourceStockpileRoutine | Steel cached: " + steel);

            tapPoint(SPEEDUP_TAB);
            sleepTask(1000);

            Long gen  = readDurationMinutes(SPD_GENERAL_TL, SPD_GENERAL_BR);
            Long tr   = readDurationMinutes(SPD_TRAINING_TL, SPD_TRAINING_BR);
            Long con  = readDurationMinutes(SPD_CONSTRUCTION_TL, SPD_CONSTRUCTION_BR);
            Long res  = readDurationMinutes(SPD_RESEARCH_TL, SPD_RESEARCH_BR);
            Long heal = readDurationMinutes(SPD_HEALING_TL, SPD_HEALING_BR);

            tapPoint(SUMMARY_CLOSE_X);
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
        // Tapping the resource ICON opens Overview directly on the Resource Production tab (verified
        // live) — no tab tap needed. One clean tap + settle matches the proven manual flow.
        tapPoint(RESOURCE_COUNTER);
        sleepTask(2600); // let the panel fully slide+render before OCR

        Long meat = readOwned(MEAT_TL, MEAT_BR);
        Long wood = readOwned(WOOD_TL, WOOD_BR);
        Long coal = readOwned(COAL_TL, COAL_BR);
        Long iron = readOwned(IRON_TL, IRON_BR);

        tapPoint(CLOSE_OVERVIEW_X);
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

    /** Fraction outside of which a new resource reading is rejected as an implausible OCR misread
     *  rather than a real change. Same value/rationale as bg_telemetry's power/gems guard -- real
     *  stockpile change between hourly reads is gradual; the observed misreads (a dropped decimal
     *  point in the abbreviated form) jumped ~10x, far outside this band. */
    private static final double SANITY_BAND_MAX_RATIO = 1.5;
    private static final double SANITY_BAND_MIN_RATIO = 1.0 / SANITY_BAND_MAX_RATIO;

    /**
     * Rejects a candidate reading that jumps implausibly far from the value this routine last
     * cached for the same field, returning null (config keeps its last known-good value, and
     * GatherRoutine/bg_telemetry both fall back the same way they do on any other unreadable
     * cycle) instead of overwriting a good cached value with a likely-wrong one.
     *
     * <p>matt/2026-08-15: live testing found this ISN'T random noise for Meat/Wood -- a live capture
     * showed a crisp, unambiguous "87.4M" in the crop, but OCR reliably read it as "874M" both times
     * (Coal "9.8M" and Iron "1.8M", one digit before the point, read correctly both times; Meat/Wood
     * always have two). Tesseract is dropping the decimal point itself when there are two leading
     * digits -- a specific, well-understood failure mode, not an arbitrary misread -- so a pure
     * reject would leave these two fields permanently stale (every future real reading would also
     * come out 10x high and get rejected forever). Before rejecting, check whether dividing by 10
     * repairs it back into the plausible band; if so that confirms the dropped-decimal pattern and
     * the corrected value is used. This is a narrow, specific correction for one identified failure
     * mode, not an open-ended guess -- anything that doesn't fit even after /10 still gets rejected.
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
            return candidate;
        }
        if (inBand(candidate, cached)) {
            return candidate;
        }
        long corrected = candidate / 10L;
        if (inBand(corrected, cached)) {
            logWarning("ResourceStockpileRoutine | " + field + " reading " + candidate + " is implausibly "
                    + "far from the last cached " + cached + ", but /10 (" + corrected + ") fits -- this is "
                    + "the known dropped-decimal-point misread, using the corrected value.");
            return corrected;
        }
        logWarning("ResourceStockpileRoutine | " + field + " reading " + candidate + " is implausibly "
                + "far from the last cached " + cached + " (ratio " + String.format("%.2f", (double) candidate / cached)
                + ", /10 correction didn't fit either) -- rejecting as a likely OCR misread, keeping the "
                + "last known-good value.");
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
