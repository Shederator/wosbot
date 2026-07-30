package dev.frostguard.tasks.combat;

import java.util.function.LongConsumer;
import java.util.function.Supplier;

final class PolarSpecialRewardsScanner {

    enum Result {
        AVAILABLE,
        EXHAUSTED,
        NOT_FOUND
    }

    static final int MAX_SWIPES = 5;
    static final long SWIPE_SETTLE_MILLIS = 1000;

    private PolarSpecialRewardsScanner() {
    }

    static Result scan(Supplier<Integer> visibleRewardsCount,
                       Runnable swipeToNextPosition,
                       LongConsumer waitForAnimation) {
        Result result = classify(visibleRewardsCount.get());
        if (result != Result.NOT_FOUND) {
            return result;
        }
        for (int swipe = 0; swipe < MAX_SWIPES; swipe++) {
            swipeToNextPosition.run();
            waitForAnimation.accept(SWIPE_SETTLE_MILLIS);
            result = classify(visibleRewardsCount.get());
            if (result != Result.NOT_FOUND) {
                return result;
            }
        }
        return Result.NOT_FOUND;
    }

    private static Result classify(Integer rewardsCount) {
        if (rewardsCount == null) {
            return Result.NOT_FOUND;
        }
        return rewardsCount == 0 ? Result.EXHAUSTED : Result.AVAILABLE;
    }
}
