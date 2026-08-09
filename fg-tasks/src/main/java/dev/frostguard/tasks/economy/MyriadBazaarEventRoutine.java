package dev.frostguard.tasks.economy;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.helper.TemplateSearchHelper;

import static dev.frostguard.api.configs.TemplatesEnum.DAILY_MISSION_CLAIM_BUTTON;
import static dev.frostguard.api.configs.TemplatesEnum.EVENTS_MYRIAD_BAZAAR_ICON;

/**
 * Task implementation for claiming free rewards on Myriad Bazaar event.
 * This task handles the automation of claiming rewards from the Myriad Bazaar
 * event.
 */
public class MyriadBazaarEventRoutine extends DelayedTask {

    public MyriadBazaarEventRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    public LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    protected void execute() {

        // search the myriad bazaar event icon and click it
        ImageSearchResultData bazaarIcon = templateSearchHelper.locatePattern(
                EVENTS_MYRIAD_BAZAAR_ICON, SearchConfigConstants.DEFAULT_SINGLE);

        if (!bazaarIcon.isFound()) {
            logInfo("Myriad Bazaar event probably not active");
            reschedule(GameTimeUtils.dailyResetTime());
            return;
        }
        logInfo("Myriad Bazaar is active, claiming free rewards");
        // wait for the event window to open
        tapInside(bazaarIcon);
        sleepTask(2000);

        // define area to search for free rewards
        PointData topLeft = new PointData(50, 280);
        PointData bottomRight = new PointData(650, 580);

        // claim all the rewards available using a while loop until no more rewards are
        // availableD
        int failCount = 0;
        ImageSearchResultData freeReward = templateSearchHelper.locatePattern(DAILY_MISSION_CLAIM_BUTTON,
                TemplateSearchHelper.SearchConfig.builder()
                        .withMaxAttempts(1)
                        .withThreshold(90)
                        .withDelay(300L)
                        .withCoordinates(topLeft, bottomRight)
                        .build());
        while (true) {
            if (freeReward != null && freeReward.isFound()) {
                logInfo("Claiming free rewards");
                tapInside(freeReward);
                sleepTask(1000);
                failCount = 0;
            } else {
                failCount++;
                if (failCount >= 3) {
                    logInfo("No rewards found after 3 consecutive attempts, exiting loop");
                    break;
                }
                sleepTask(500);
            }
            freeReward = templateSearchHelper.locatePattern(DAILY_MISSION_CLAIM_BUTTON,
                    TemplateSearchHelper.SearchConfig.builder()
                            .withMaxAttempts(1)
                            .withThreshold(90)
                            .withDelay(300L)
                            .withCoordinates(topLeft, bottomRight)
                            .build());
        }
        reschedule(GameTimeUtils.dailyResetTime());

    }

}
