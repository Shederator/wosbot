package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;

import java.util.function.Predicate;

final class BearTerritoryTransition {

    private static final int SEARCH_PADDING = 8;

    private BearTerritoryTransition() {}

    static boolean hasLeftTriggerRegion(ImageSearchResultData trigger,
                                        Predicate<AreaData> templatePresent) {
        return !templatePresent.test(triggerRegion(trigger));
    }

    private static AreaData triggerRegion(ImageSearchResultData trigger) {
        AreaData matched = trigger.getMatchedArea();
        int left = matched != null ? matched.topLeft().getX() : trigger.getHitX() - 32;
        int top = matched != null ? matched.topLeft().getY() : trigger.getHitY() - 32;
        int right = matched != null ? matched.bottomRight().getX() : trigger.getHitX() + 32;
        int bottom = matched != null ? matched.bottomRight().getY() : trigger.getHitY() + 32;
        return AreaData.of(
                Math.max(0, left - SEARCH_PADDING),
                Math.max(0, top - SEARCH_PADDING),
                Math.min(719, right + SEARCH_PADDING),
                Math.min(1279, bottom + SEARCH_PADDING));
    }
}
