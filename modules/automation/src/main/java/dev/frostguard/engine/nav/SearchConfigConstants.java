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

    // matt caught it live, 2026-08-19: MonumentRoutine was reusing MONUMENT_BADGE_SEARCH's
    // threshold=30 for its post-tap "is the badge still there" check too -- but that threshold was
    // tuned for finding the real badge on ITS OWN screen pre-tap, not for ruling it out on a
    // DIFFERENT screen (whatever opened after tapping it) post-tap. Real logged evidence: the
    // post-tap check fired a "still detectable" false positive at 48.29% match, scale 0.60,
    // position (67,460) -- a completely different position AND scale than the original tap's
    // 89.44% match at scale 1.25, position (372,540). That's not the same badge; it's threshold=30
    // coincidentally matching something else on the newly-opened panel across a full multi-scale
    // scan, and it happened on effectively every real pass, permanently blocking Monument from ever
    // reaching Claim All. A verification check needs to actually rule things OUT, so it uses a real
    // threshold -- the codebase's ordinary default (90) other templates already use safely.
    public static final SearchConfig MONUMENT_BADGE_STILL_THERE_CHECK =
            SearchConfig.builder().withMaxAttempts(1).withThreshold(90).withDelay(0L).build();

    // matt caught it live, 2026-08-19: "it has to go to the bottom and hit claim all... it's not
    // hitting claim all." Root-caused with real evidence, not guessed -- a live-captured native
    // 720x1280 frame of the Atlas panel measured MONUMENT_ATLAS_CLAIM_BUTTON (the individual green
    // "Claim" pill) at 100.0% against the real enabled button, but 89.04% against the DISABLED grey
    // "Claim" button on an unfinished row ("Log in for 60 days", 33/60) -- just 0.96 points below
    // QUICK_SEARCH's threshold=90. That's not a safe margin; ordinary rendering/compression variance
    // can and does cross it, and when it does the individual-claim loop taps a dead button (no state
    // change) instead of the real ready rewards, potentially burning its whole MAX_CLAIM_LOOPS budget
    // there and never reaching Claim All with the real ready rows still unclaimed underneath it.
    // threshold=96 sits comfortably below the true positive (100.0) and comfortably above the
    // measured false positive (89.04) -- a wide margin on both sides, not another guess.
    public static final SearchConfig MONUMENT_ATLAS_CLAIM_BUTTON_SEARCH =
            SearchConfig.builder().withMaxAttempts(1).withThreshold(96).withDelay(100L).build();
}
