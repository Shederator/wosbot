package dev.frostguard.engine.nav;

import dev.frostguard.engine.helper.TemplateSearchHelper;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;

// Catalogue of reusable SearchConfig presets for template matching.
public final class SearchConfigConstants {

    private SearchConfigConstants() {}

    // one-shot
    public static final SearchConfig DEFAULT_SINGLE =
            SearchConfig.builder().withMaxAttempts(1).withThreshold(90).withDelay(300L).build();

    public static final SearchConfig QUICK_SEARCH =
            SearchConfig.builder().withMaxAttempts(1).withThreshold(90).withDelay(100L).build();

    // with retries
    public static final SearchConfig SINGLE_WITH_2_RETRIES =
            SearchConfig.builder().withMaxAttempts(2).withThreshold(90).withDelay(200L).build();

    public static final SearchConfig SINGLE_WITH_RETRIES =
            SearchConfig.builder().withMaxAttempts(3).withThreshold(90).withDelay(200L).build();

    public static final SearchConfig RESILIENT =
            SearchConfig.builder().withMaxAttempts(5).withThreshold(90).withDelay(300L).build();

    // confidence variants
    public static final SearchConfig HIGH_SENSITIVITY =
            SearchConfig.builder().withMaxAttempts(3).withThreshold(80).withDelay(200L).build();

    public static final SearchConfig STRICT_MATCHING =
            SearchConfig.builder().withMaxAttempts(3).withThreshold(95).withDelay(200L).build();

    // multi-hit
    public static final SearchConfig MULTIPLE_RESULTS =
            SearchConfig.builder().withMaxAttempts(3).withThreshold(90).withDelay(200L).withMaxResults(3).build();

    // matt, 2026-08-08: the Fire Beast marker's real grayscale match score
    // against a live-captured template sits around 85 (color match ~90.7) —
    // consistently below the standard 90 threshold used everywhere else, so
    // every single scan silently failed regardless of retry count (retrying
    // an unchanging screen against a threshold it can't clear doesn't help).
    // 80 gives a real margin above the observed ~85 while still rejecting a
    // genuinely bad match. Fire-Beast-specific — do not reuse for other
    // templates without checking their own real match scores first.
    public static final SearchConfig FIRE_BEAST_SEARCH =
            SearchConfig.builder().withMaxAttempts(3).withThreshold(80).withDelay(200L).build();

    // matt/2026-08-18: MONUMENT_REWARD_BADGE has never once returned a HIT against this account in
    // three sessions of live logs, at the standard 90 threshold, even on runs where the badge was
    // plainly visible on screen (matt sent two direct screenshots proving it). Unlike FIRE_BEAST_SEARCH
    // above, there is no known-good real match score to calibrate against yet -- this account has
    // literally never logged a positive match to measure. Deliberately generous (65, well below any
    // normal threshold) so the very next run either finds it -- giving a real score to tighten this
    // back up with -- or, if it's STILL a miss even this loose, that's real evidence the problem is
    // the template image itself (stale/wrong crop) rather than the threshold. Do not reuse for other
    // templates; revisit this value the moment real match-score evidence exists.
    public static final SearchConfig MONUMENT_BADGE_SEARCH =
            SearchConfig.builder().withMaxAttempts(6).withThreshold(65).withDelay(300L).build();
}
