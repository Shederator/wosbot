package dev.frostguard.tasks.exploration;

import java.time.Duration;
import java.time.LocalDateTime;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.nav.SearchConfigConstants;

public class TundraTrekRoutine extends DelayedTask {

    public TundraTrekRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    public LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.ANY;
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

        // Open left menu on city section
        marchHelper.openLeftMenuCitySection(true);

        for (int i = 0; i < 5; i++) { // Try up to 5 times (swipes)
            ImageSearchResultData trekSupplies = templateSearchHelper.locatePattern(
                    TemplatesEnum.TUNDRA_TREK_SUPPLIES,
                    SearchConfigConstants.DEFAULT_SINGLE);

            if (trekSupplies.isFound()) {
                logInfo("Found the Tundra Trek Supplies button.");
                tapInside(trekSupplies);
                sleepTask(1000);

                // Open supplies claim screen
                tapInside(new PointData(500, 29), new PointData(590, 49));
                sleepTask(2000);
                return true;
            } else {
                logInfo("Tundra Trek Supplies not visible. Swiping down to search... (Attempt " + (i + 1) + "/5)");
                swipe(new PointData(320, 765), new PointData(50, 500));
                sleepTask(1000);
            }
        }
        return false;
    }
}
