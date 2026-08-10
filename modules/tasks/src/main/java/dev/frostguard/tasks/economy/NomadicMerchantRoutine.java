package dev.frostguard.tasks.economy;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.helper.TemplateSearchHelper;

import java.time.LocalDateTime;

public class NomadicMerchantRoutine extends DelayedTask {

    private static final long MAX_TASK_EXECUTION_MS = 2 * 60 * 1000L;

    private final TemplatesEnum[] TEMPLATES = { TemplatesEnum.NOMADIC_MERCHANT_COAL,
            TemplatesEnum.NOMADIC_MERCHANT_MEAT, TemplatesEnum.NOMADIC_MERCHANT_STONE,
            TemplatesEnum.NOMADIC_MERCHANT_WOOD };

    public NomadicMerchantRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected void execute() {

        // STEP 1: Navigate to shop - Search for the bottom bar shop button
        ImageSearchResultData shopButtonResult = templateSearchHelper.locatePattern(
                TemplatesEnum.GAME_HOME_BOTTOM_BAR_SHOP_BUTTON,
                SearchConfigConstants.DEFAULT_SINGLE);

        if (!shopButtonResult.isFound()) {
            logWarning("Shop button not found on the main screen. Rescheduling for 1 hour.");
            LocalDateTime nextAttempt = LocalDateTime.now().plusHours(1);
            this.reschedule(nextAttempt);
            return;
        }

        // Tap on shop button and wait for shop to load
        logInfo("Navigating to the shop.");
        tapInside(shopButtonResult.getPoint(), shopButtonResult.getPoint());
        sleepTask(2000);

        // STEP 2: Main loop to handle all nomadic merchant operations
        boolean continueOperations = true;
        int freeResourcesClaimedCount = 0;
        int vipPointsPurchasedCount = 0;
        int dailyRefreshUsedCount = 0;
        long executionDeadlineMs = System.currentTimeMillis() + MAX_TASK_EXECUTION_MS;

        while (continueOperations && System.currentTimeMillis() < executionDeadlineMs) {
            // PHASE 1: Search for resource templates until none are found
            boolean foundResourceTemplate = true;
            logInfo("Searching for free resources to claim.");

            while (foundResourceTemplate && System.currentTimeMillis() < executionDeadlineMs) {
                foundResourceTemplate = false;

                // Iterate through each resource template
                for (TemplatesEnum template : TEMPLATES) {
                    ImageSearchResultData result = templateSearchHelper.locatePattern(
                            template,
                            TemplateSearchHelper.SearchConfig.builder()
                                    .withMaxAttempts(1)
                                    .withThreshold(90)
                                    .withDelay(300L)
                                    .withCoordinates(new PointData(25, 412), new PointData(690, 1200))
                                    .build());

                    if (result.isFound()) {
                        logInfo("Found resource: " + template.name() + ". Purchasing it.");
                        tapInside(result);
                        sleepTask(500);
                        freeResourcesClaimedCount++;
                        foundResourceTemplate = true;
                        break; // Restart resource search from beginning
                    }
                }
            }

            if (System.currentTimeMillis() >= executionDeadlineMs) {
                continueOperations = false;
                break;
            }

            // PHASE 2: Check if VIP purchase is enabled and search for VIP templates
            boolean vipBuyEnabled = profile.getConfig(ConfigurationKeyEnum.BOOL_NOMADIC_MERCHANT_VIP_POINTS,
                    Boolean.class);
            boolean foundVipTemplate = false;

            if (vipBuyEnabled) {
                logInfo("VIP purchase is enabled. Searching for VIP points to buy.");

                // Search for VIP template in the entire screen
                ImageSearchResultData vipResult = templateSearchHelper.locatePattern(
                        TemplatesEnum.NOMADIC_MERCHANT_VIP,
                        SearchConfigConstants.DEFAULT_SINGLE);

                if (vipResult.isFound()) {
                    logInfo("Found VIP points. Purchasing with gems.");
                    // Tap slightly below the VIP template to access purchase options
                    tapNear(new PointData(vipResult.getPoint().getX(), vipResult.getPoint().getY() + 100));
                    sleepTask(1000);

                    // Tap buy with gems button
                    tapNear(new PointData(368, 830));
                    sleepTask(1000);

                    // Confirm purchase
                    tapNear(new PointData(355, 788));
                    sleepTask(1000);

                    vipPointsPurchasedCount++;
                    foundVipTemplate = true;
                }
            }

            // PHASE 3: If VIP was purchased, recheck for new resource templates
            if (foundVipTemplate) {
                logInfo("VIP points purchased. Re-checking for new resource templates.");
                continue; // Go back to PHASE 1 to check for new resources
            }

            // PHASE 4: Check for daily refresh button if no resources or VIP were found
            logInfo("No more resources or VIP points found. Checking for daily refresh.");
            ImageSearchResultData dailyRefreshResult = templateSearchHelper.locatePattern(
                    TemplatesEnum.MYSTERY_SHOP_DAILY_REFRESH,
                    SearchConfigConstants.DEFAULT_SINGLE);

            if (dailyRefreshResult.isFound()) {
                logInfo("Daily refresh is available. Using it now.");
                tapInside(dailyRefreshResult.getPoint(), dailyRefreshResult.getPoint());
                sleepTask(2000); // Wait longer for refresh to complete
                dailyRefreshUsedCount++;
                // Continue the main loop to check for new items after refresh
            } else {
                // PHASE 5: No refresh available, operations complete
                logInfo("No daily refresh available. All Nomadic Merchant operations are complete.");
                continueOperations = false;
            }
        }

        if (System.currentTimeMillis() <= executionDeadlineMs) {
            StatisticsService.obtain().addToCounter(profile, "Nomadic Merchant Free Resources Claimed", freeResourcesClaimedCount);
            StatisticsService.obtain().addToCounter(profile, "Nomadic Merchant VIP Points Purchased", vipPointsPurchasedCount);
            StatisticsService.obtain().addToCounter(profile, "Nomadic Merchant Daily Refresh Used", dailyRefreshUsedCount);

            logInfo("Nomadic Merchant stats - free resources claimed: " + freeResourcesClaimedCount
                    + ", VIP points purchased: " + vipPointsPurchasedCount
                    + ", daily refresh used: " + dailyRefreshUsedCount);
        }
        else
        {
            logWarning("Nomadic Merchant task reached execution limit. Ending current cycle to avoid infinite loop.");
        }

        // Final step: schedule task till game reset
        reschedule(GameTimeUtils.dailyResetTime());
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }
}
