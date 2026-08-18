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

    // matt/2026-08-18: real live evidence (two logged misses at 40.7 and 50.6, different scores on
    // a supposedly-static template) plus matt catching it live -- "the icon in the game dances back
    // and forth" -- points at the same problem LifeEssenceRoutine already hit and documented: a
    // claimable badge that bounces/animates never settles into one shape/position, so no single
    // correlation score is reliable, and even that badge's own clean self-match capped at ~0.84
    // (84). Life Essence's fix was color-blob detection; Monument doesn't need that rebuild because
    // it already has two independent real backstops a plain low-confidence match doesn't: the
    // Events-tab landing check, and the post-tap "is the badge still detectable" verification. Per
    // matt's call ("what's really worse case? it gets into Monument and nothing is there to do? I
    // don't think there's a downside") -- dropped to 30, comfortably under the lowest logged miss
    // (40.7) so a low point in the bounce cycle still clears it, while still requiring real
    // correlation (not near-zero) so an unrelated screen element can't match by coincidence during
    // the full-frame scan. Do not reuse for other templates; revisit if real evidence says otherwise.
    public static final SearchConfig MONUMENT_BADGE_SEARCH =
            SearchConfig.builder().withMaxAttempts(6).withThreshold(30).withDelay(300L).build();
}
