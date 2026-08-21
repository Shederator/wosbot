package dev.frostguard.tasks.exploration;

import java.time.Duration;
import java.time.LocalDateTime;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.nav.SearchConfigConstants;

public class TundraTrekRoutine extends DelayedTask {

    private static final PointData SUPPLY_COUNTER_TOP_LEFT = new PointData(500, 29);
    private static final PointData SUPPLY_COUNTER_BOTTOM_RIGHT = new PointData(590, 49);

    public TundraTrekRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    public LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    protected void execute() {
        if (navigateToTrekSupplies()) {
            // Search for claim button
            ImageSearchResultData trekClaimButton = templateSearchHelper.locatePattern(
                    TemplatesEnum.TUNDRA_TREK_CLAIM_BUTTON,
                    SearchConfigConstants.DEFAULT_SINGLE);
            if (trekClaimButton.isFound()) {
                logInfo("Trek Supplies are available. Claiming now...");
                tapInside(trekClaimButton);
                sleepTask(3000);
            } else {
                logInfo("Trek Supplies have already been claimed or are not yet available.");
                sleepTask(500);
            }

            // Do OCR to find next reward time and reschedule
            try {
                Duration nextRewardTimeDuration = durationHelper.attemptRecognition(
                        new PointData(526, 592),
                        new PointData(627, 616),
                        3,
                        200L,
                        null,
                        GameTimeUtils::isAcceptedFormat,
                        GameTimeUtils::parseDuration);
                LocalDateTime nextRewardTime = LocalDateTime.now().plus(nextRewardTimeDuration);
                reschedule(nextRewardTime);
                logInfo("Successfully parsed the next reward time. Rescheduling the task for: "
                        + nextRewardTime.format(DATETIME_FORMATTER));
            } catch (IllegalArgumentException e) {
                logError("Failed to read or parse the next reward time. Rescheduling for 1 hour from now.", e);
                reschedule(LocalDateTime.now().plusHours(1));
            }
        } else {
            logError("Failed to navigate to Tundra Trek Supplies after multiple attempts. Rescheduling for 1 hour.");
            reschedule(LocalDateTime.now().plusHours(1)); // Reschedule for later
        }
    }

    private boolean navigateToTrekSupplies() {
        logInfo("Navigating to Tundra Trek Supplies...");

        if (!navigationHelper.navigateToSidebarDestination(SidebarDestination.TUNDRA_TREK_SUPPLIES)) {
            logWarning("Trek Supplies destination is not available in the Daily sidebar");
            return false;
        }

        // The Daily shortcut may open either Dawn Academy or the claim panel directly.
        if (!isClaimButtonVisible()) {
            tapInside(SUPPLY_COUNTER_TOP_LEFT, SUPPLY_COUNTER_BOTTOM_RIGHT);
            sleepTask(2000);
        }
        return true;
    }

    private boolean isClaimButtonVisible() {
        return templateSearchHelper.locatePattern(
                TemplatesEnum.TUNDRA_TREK_CLAIM_BUTTON,
                SearchConfigConstants.DEFAULT_SINGLE).isFound();
    }
}
