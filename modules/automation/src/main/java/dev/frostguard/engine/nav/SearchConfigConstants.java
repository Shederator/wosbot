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
}
