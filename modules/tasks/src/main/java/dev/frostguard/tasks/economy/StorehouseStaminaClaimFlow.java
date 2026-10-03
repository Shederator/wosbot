package dev.frostguard.tasks.economy;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

import dev.frostguard.api.domain.ImageSearchResultData;

final class StorehouseStaminaClaimFlow {

    interface Actions {
        boolean isRewardDialogVisible();

        ImageSearchResultData findClaimButton();

        boolean isTooltipVisible();

        void pressBack();

        void waitAfterBack();

        void waitForNextPoll();
    }

    record Result(ImageSearchResultData claimButton, boolean rewardDialogSeen) {
    }

    private StorehouseStaminaClaimFlow() {
    }

    static Result awaitClaimButton(Actions actions, Duration timeout, LongSupplier nanoTime) {
        Objects.requireNonNull(actions, "actions");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(nanoTime, "nanoTime");

        long deadline = nanoTime.getAsLong() + timeout.toNanos();
        boolean tooltipDismissed = false;
        boolean rewardDialogSeen = false;
        while (nanoTime.getAsLong() < deadline) {
            if (actions.isRewardDialogVisible()) {
                rewardDialogSeen = true;
                ImageSearchResultData claimButton = actions.findClaimButton();
                if (claimButton != null && claimButton.isFound()) {
                    return new Result(claimButton, true);
                }
            }

            if (!tooltipDismissed && actions.isTooltipVisible()) {
                actions.pressBack();
                tooltipDismissed = true;
                actions.waitAfterBack();
                continue;
            }

            actions.waitForNextPoll();
        }
        return new Result(null, rewardDialogSeen);
    }
}
