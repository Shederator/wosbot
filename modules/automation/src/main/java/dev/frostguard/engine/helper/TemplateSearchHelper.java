package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.service.LoggingService;
import dev.frostguard.vision.logging.ProfileContextLogger;

import java.util.List;
import java.util.Objects;

/**
 * Retry-aware template matcher that delegates to the emulator's
 * image-search subsystem.  Supports colour and grayscale matching,
 * single and multi-result modes, and optional preemption hooks.
 */
public class TemplateSearchHelper {

    private static final String TAG = "TemplateSearchHelper";

    private final EmulatorController emu;
    private final String device;
    private final ProfileContextLogger log;
    private final String accountName;
    private final AccountDescriptor profile;
    private final LoggingService logs;
    private int missCount;

    private Runnable preemptionHook = () -> {};

    public TemplateSearchHelper(EmulatorController emuManager, String emulatorNumber,
                                AccountDescriptor profile) {
        this.emu         = emuManager;
        this.device      = emulatorNumber;
        this.log         = new ProfileContextLogger(TemplateSearchHelper.class, profile);
        this.accountName = profile.getName();
        this.profile     = profile;
        this.logs        = LoggingService.obtain();
    }

    public int getFailedSearches() { return missCount; }

    public void setPreemptionCheck(Runnable check) {
        this.preemptionHook = Objects.requireNonNull(check, "preemptionCheck");
    }

    // ── single-match (colour) ────────────────────────────────────────

    public ImageSearchResultData locatePattern(TemplatesEnum tpl, SearchConfig cfg) {
        return retrySearch(tpl, cfg, false, false);
    }

    // ── single-match (greyscale) ─────────────────────────────────────

    public ImageSearchResultData locatePatternMono(TemplatesEnum tpl, SearchConfig cfg) {
        return retrySearch(tpl, cfg, true, false);
    }

    public ImageSearchResultData locatePatternMultiScale(TemplatesEnum tpl, SearchConfig cfg) {
        return retrySearch(tpl, cfg, false, true);
    }

    // ── multi-match (colour) ─────────────────────────────────────────

    public List<ImageSearchResultData> locateAllPatterns(TemplatesEnum tpl, SearchConfig cfg) {
        return retryMultiSearch(tpl, cfg, false);
    }

    // ── multi-match (greyscale) ──────────────────────────────────────

    public List<ImageSearchResultData> locateAllPatternsMono(TemplatesEnum tpl, SearchConfig cfg) {
        return retryMultiSearch(tpl, cfg, true);
    }

    // ── retry loops ──────────────────────────────────────────────────

    private ImageSearchResultData retrySearch(TemplatesEnum tpl, SearchConfig cfg,
                                              boolean mono, boolean multiScale) {
        ImageSearchResultData last = null;
        for (int a = 1; a <= cfg.getMaxAttempts(); a++) {
            last = multiScale ? doSearchMultiScale(tpl, cfg) : mono ? doSearchGrey(tpl, cfg) : doSearch(tpl, cfg);
            if (last != null && last.isFound()) {
                dbg(tpl.name() + (multiScale ? " (multi-scale)" : "") + " found @ attempt " + a);
                return last;
            }
            if (a < cfg.getMaxAttempts()) sleepWithPreemption(cfg.getDelayBetweenAttempts());
        }
        dbg(tpl.name() + (multiScale ? " (multi-scale)" : "") + " not found after " + cfg.getMaxAttempts() + " attempts");
        return last;
    }

    private List<ImageSearchResultData> retryMultiSearch(TemplatesEnum tpl, SearchConfig cfg,
                                                         boolean mono) {
        List<ImageSearchResultData> last = null;
        for (int a = 1; a <= cfg.getMaxAttempts(); a++) {
            last = mono ? doMultiGrey(tpl, cfg) : doMulti(tpl, cfg);
            if (last != null && !last.isEmpty()) {
                dbg(tpl.name() + ": " + last.size() + " matches");
                return last;
            }
            if (a < cfg.getMaxAttempts()) sleepWithPreemption(cfg.getDelayBetweenAttempts());
        }
        dbg(tpl.name() + " multi not found after " + cfg.getMaxAttempts() + " attempts");
        return last;
    }

    // ── single-shot delegates ────────────────────────────────────────

    private ImageSearchResultData doSearch(TemplatesEnum tpl, SearchConfig c) {
        if (c.hasArea())        return emu.locatePattern(device, tpl, c.getArea().topLeft(), c.getArea().bottomRight(), c.getThreshold());
        if (c.hasCoordinates()) return emu.locatePattern(device, tpl, c.getStartPoint(), c.getEndPoint(), c.getThreshold());
        return emu.locatePattern(device, tpl, c.getThreshold());
    }

    private ImageSearchResultData doSearchGrey(TemplatesEnum tpl, SearchConfig c) {
        if (c.hasArea())        return emu.locatePatternMono(device, tpl, c.getArea().topLeft(), c.getArea().bottomRight(), c.getThreshold());
        if (c.hasCoordinates()) return emu.locatePatternMono(device, tpl, c.getStartPoint(), c.getEndPoint(), c.getThreshold());
        return emu.locatePatternMono(device, tpl, c.getThreshold());
    }

    private ImageSearchResultData doSearchMultiScale(TemplatesEnum tpl, SearchConfig c) {
        if (c.hasArea())        return emu.locatePatternMultiScale(device, tpl, c.getArea().topLeft(), c.getArea().bottomRight(), c.getThreshold());
        if (c.hasCoordinates()) return emu.locatePatternMultiScale(device, tpl, c.getStartPoint(), c.getEndPoint(), c.getThreshold());
        return emu.locatePatternMultiScale(device, tpl, c.getThreshold());
    }

    private List<ImageSearchResultData> doMulti(TemplatesEnum tpl, SearchConfig c) {
        if (c.hasArea())        return emu.locateAllPatterns(device, tpl, c.getArea().topLeft(), c.getArea().bottomRight(), c.getThreshold(), c.getMaxResults());
        if (c.hasCoordinates()) return emu.locateAllPatterns(device, tpl, c.getStartPoint(), c.getEndPoint(), c.getThreshold(), c.getMaxResults());
        return emu.locateAllPatterns(device, tpl, c.getThreshold(), c.getMaxResults());
    }

    private List<ImageSearchResultData> doMultiGrey(TemplatesEnum tpl, SearchConfig c) {
        if (c.hasArea())        return emu.locateAllPatternsMono(device, tpl, c.getArea().topLeft(), c.getArea().bottomRight(), c.getThreshold(), c.getMaxResults());
        if (c.hasCoordinates()) return emu.locateAllPatternsMono(device, tpl, c.getStartPoint(), c.getEndPoint(), c.getThreshold(), c.getMaxResults());
        return emu.locateAllPatternsMono(device, tpl, c.getThreshold(), c.getMaxResults());
    }

    // ── sleep / preemption ───────────────────────────────────────────

    private void sleepWithPreemption(long ms) {
        preemptionHook.run();
        try { Thread.sleep(ms); }
        catch (InterruptedException e) {
            warn("Sleep interrupted");
            Thread.currentThread().interrupt();
        }
    }

    // ── SearchConfig ─────────────────────────────────────────────────

    public static class SearchConfig {
        private final int maxAttempts;
        private final long delayBetweenAttempts;
        private final int threshold;
        private final int maxResults;
        private final PointData startPoint;
        private final PointData endPoint;
        private final AreaData area;

        private SearchConfig(Builder b) {
            this.maxAttempts = b.maxAttempts;
            this.delayBetweenAttempts = b.delay;
            this.threshold = b.threshold;
            this.maxResults = b.maxResults;
            this.startPoint = b.sp;
            this.endPoint = b.ep;
            this.area = b.area;
        }

        public static Builder builder() { return new Builder(); }

        public static class Builder {
            private int maxAttempts = 1;
            private long delay = 300;
            private int threshold = 90;
            private int maxResults = 1;
            private PointData sp, ep;
            private AreaData area;

            public Builder withMaxAttempts(int v) { this.maxAttempts = v; return this; }
            public Builder withDelay(long v)      { this.delay = v; return this; }
            public Builder withThreshold(int v)   { this.threshold = v; return this; }
            public Builder withMaxResults(int v)  { this.maxResults = v; return this; }
            public Builder withArea(AreaData a)   { this.area = a; sp = ep = null; return this; }
            public Builder withCoordinates(PointData s, PointData e) { sp = s; ep = e; area = null; return this; }
            public SearchConfig build() { return new SearchConfig(this); }
        }

        public int getMaxAttempts()          { return maxAttempts; }
        public long getDelayBetweenAttempts(){ return delayBetweenAttempts; }
        public int getThreshold()            { return threshold; }
        public int getMaxResults()           { return maxResults; }
        public PointData getStartPoint()     { return startPoint; }
        public PointData getEndPoint()       { return endPoint; }
        public AreaData getArea()            { return area; }
        public boolean hasCoordinates()      { return startPoint != null && endPoint != null; }
        public boolean hasArea()             { return area != null; }
    }

    // ── logging bridge ───────────────────────────────────────────────

    private void dbg(String msg) {
        String full = accountName + " - " + msg;
        log.debug(full);
        logs.emit(TpMessageSeverityEnum.DEBUG, TAG, accountName, msg);
        if (msg.contains("not found after")) missCount++;
    }

    private void warn(String msg) {
        String full = accountName + " - " + msg;
        log.warn(full);
        logs.emit(TpMessageSeverityEnum.WARNING, TAG, accountName, msg);
    }
}
