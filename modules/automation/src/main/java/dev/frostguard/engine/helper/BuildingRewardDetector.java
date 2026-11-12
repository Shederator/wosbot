package dev.frostguard.engine.helper;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.engine.helper.NavigationHelper.BuildingDestination;
import dev.frostguard.engine.nav.CommonGameAreas;

import java.util.Objects;

/**
 * Maps each supported City building to the visual indicator for an available daily reward.
 * This class only detects the indicator; callers own any collection action and confirmation.
 */
public final class BuildingRewardDetector {

    private static final RewardSearch BAKERY = new RewardSearch(
            TemplatesEnum.BAKERY_REWARD_ICON,
            CommonGameAreas.BAKERY_REWARD_ICON_SEARCH,
            80,
            3,
            300L);
    private static final RewardSearch FARM = new RewardSearch(
            TemplatesEnum.FARM_REWARD_ICON,
            CommonGameAreas.FARM_REWARD_ICON_SEARCH,
            75,
            5,
            300L);

    private BuildingRewardDetector() {}

    public static RewardSearch searchFor(BuildingDestination destination) {
        return switch (Objects.requireNonNull(destination, "destination")) {
            case BAKERY -> BAKERY;
            case FARM -> FARM;
        };
    }

    public static ImageSearchResultData detect(BuildingDestination destination, Matcher matcher) {
        Objects.requireNonNull(matcher, "matcher");
        RewardSearch search = searchFor(destination);
        return matcher.locate(search.template(), search.searchConfig());
    }

    @FunctionalInterface
    public interface Matcher {
        ImageSearchResultData locate(TemplatesEnum template, TemplateSearchHelper.SearchConfig config);
    }

    public record RewardSearch(TemplatesEnum template, AreaData area, int threshold,
                               int maxAttempts, long delayMillis) {

        public RewardSearch {
            Objects.requireNonNull(template, "template");
            Objects.requireNonNull(area, "area");
        }

        public TemplateSearchHelper.SearchConfig searchConfig() {
            return TemplateSearchHelper.SearchConfig.builder()
                    .withArea(area)
                    .withThreshold(threshold)
                    .withMaxAttempts(maxAttempts)
                    .withDelay(delayMillis)
                    .build();
        }
    }
}
