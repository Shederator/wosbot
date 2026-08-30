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

    // The Fire Beast marker does not hold still: the flame pulses and the pin bobs, so the same
    // marker on the same screen scores across a wide band and a scan at the shared threshold of 90
    // is a coin toss it usually loses. Measured live on 30 August 2026, one Fire Beast, four
    // captures: 73.7, 88.3, 89.7 and 90.0 -- and the frame that scored 73.7 is the one where Intel
    // read the map as empty with the beast plainly on it. Against four frames with no marker at all
    // the same template scores 33.4, 33.4, 37.9 and 38.5, so 60 sits about twenty points clear of
    // the loudest noise and about fourteen below the dimmest real sighting. Four attempts 400ms
    // apart span roughly a second and a half of the animation rather than half of one.
    // Fire-Beast-specific -- do not reuse for other templates without measuring their own scores.
    public static final SearchConfig FIRE_BEAST_SEARCH =
            SearchConfig.builder().withMaxAttempts(4).withThreshold(60).withDelay(400L).build();
}
