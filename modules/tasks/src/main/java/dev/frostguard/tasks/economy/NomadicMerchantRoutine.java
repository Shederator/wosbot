package dev.frostguard.tasks.economy;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.TaskFlowDefinitionData;
import dev.frostguard.api.domain.TaskFlowEdgeData;
import dev.frostguard.api.domain.TaskFlowNodeData;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.nav.ShopTab;
import dev.frostguard.engine.helper.TemplateSearchHelper;

import java.time.LocalDateTime;

public class NomadicMerchantRoutine extends DelayedTask {

    public static final String OPEN_SHOP_STEP = "open-shop";
    public static final String CLAIM_RESOURCES_STEP = "claim-resources";
    public static final String PURCHASE_VIP_STEP = "purchase-vip";
    public static final String REFRESH_OFFERS_STEP = "refresh-offers";
    public static final String RECORD_OUTCOME_STEP = "record-outcome";

    private static final String OPEN_SHOP_LABEL = "Open shop";
    private static final String CLAIM_RESOURCES_LABEL = "Claim free resource offers";
    private static final String PURCHASE_VIP_LABEL = "Purchase VIP points";
    private static final String REFRESH_OFFERS_LABEL = "Refresh merchant offers";
    private static final String RECORD_OUTCOME_LABEL = "Record merchant outcome";

    private static final long MAX_TASK_EXECUTION_MS = 2 * 60 * 1000L;

    private static final TemplatesEnum[] RESOURCE_TEMPLATES = { TemplatesEnum.NOMADIC_MERCHANT_COAL,
            TemplatesEnum.NOMADIC_MERCHANT_MEAT, TemplatesEnum.NOMADIC_MERCHANT_STONE,
            TemplatesEnum.NOMADIC_MERCHANT_WOOD };

    public NomadicMerchantRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    public static TaskFlowDefinitionData workbenchFlow() {
        return new TaskFlowDefinitionData(
                OPEN_SHOP_STEP,
                java.util.List.of(
                        new TaskFlowNodeData(OPEN_SHOP_STEP, OPEN_SHOP_LABEL),
                        new TaskFlowNodeData(CLAIM_RESOURCES_STEP, CLAIM_RESOURCES_LABEL),
                        new TaskFlowNodeData(PURCHASE_VIP_STEP, PURCHASE_VIP_LABEL),
                        new TaskFlowNodeData(REFRESH_OFFERS_STEP, REFRESH_OFFERS_LABEL),
                        new TaskFlowNodeData(RECORD_OUTCOME_STEP, RECORD_OUTCOME_LABEL)),
                java.util.List.of(
                        new TaskFlowEdgeData(OPEN_SHOP_STEP, CLAIM_RESOURCES_STEP, "shop opened"),
                        new TaskFlowEdgeData(CLAIM_RESOURCES_STEP, PURCHASE_VIP_STEP, "offers checked"),
                        new TaskFlowEdgeData(CLAIM_RESOURCES_STEP, RECORD_OUTCOME_STEP, "deadline reached"),
                        new TaskFlowEdgeData(PURCHASE_VIP_STEP, CLAIM_RESOURCES_STEP, "purchased"),
                        new TaskFlowEdgeData(PURCHASE_VIP_STEP, REFRESH_OFFERS_STEP, "not purchased or disabled"),
                        new TaskFlowEdgeData(REFRESH_OFFERS_STEP, CLAIM_RESOURCES_STEP, "refreshed"),
                        new TaskFlowEdgeData(REFRESH_OFFERS_STEP, RECORD_OUTCOME_STEP, "no refresh")));
    }

    @Override
    protected void execute() {
        if (!step(OPEN_SHOP_STEP, OPEN_SHOP_LABEL, this::openShop)) {
            return;
        }

        int freeResourcesClaimedCount = 0;
        int vipPointsPurchasedCount = 0;
        int dailyRefreshUsedCount = 0;
        long executionDeadlineMs = System.currentTimeMillis() + MAX_TASK_EXECUTION_MS;
        boolean continueOperations = true;

        while (continueOperations && System.currentTimeMillis() < executionDeadlineMs) {
            freeResourcesClaimedCount += step(CLAIM_RESOURCES_STEP, CLAIM_RESOURCES_LABEL,
                    () -> claimFreeResources(executionDeadlineMs));

            if (System.currentTimeMillis() >= executionDeadlineMs) {
                break;
            }

            boolean vipBuyEnabled = profile.getConfig(
                    ConfigurationKeyEnum.BOOL_NOMADIC_MERCHANT_VIP_POINTS, Boolean.class);
            boolean purchasedVipPoints;
            if (vipBuyEnabled) {
                purchasedVipPoints = step(PURCHASE_VIP_STEP, PURCHASE_VIP_LABEL, this::purchaseVipPoints);
            } else {
                skipStep(PURCHASE_VIP_STEP, PURCHASE_VIP_LABEL);
                purchasedVipPoints = false;
            }

            if (purchasedVipPoints) {
                vipPointsPurchasedCount++;
                logInfo("VIP points purchased. Re-checking for new resource templates.");
                continue;
            }

            boolean refreshed = step(REFRESH_OFFERS_STEP, REFRESH_OFFERS_LABEL, this::refreshMerchantOffers);
            if (refreshed) {
                dailyRefreshUsedCount++;
            } else {
                continueOperations = false;
            }
        }

        int claimed = freeResourcesClaimedCount;
        int vipPurchased = vipPointsPurchasedCount;
        int refreshes = dailyRefreshUsedCount;
        step(RECORD_OUTCOME_STEP, RECORD_OUTCOME_LABEL,
                () -> recordOutcome(claimed, vipPurchased, refreshes, executionDeadlineMs));
    }

    private boolean openShop() {
        if (navigateToNomadicMerchantShop()) {
            return true;
        }

        logWarning("Nomadic Merchant shop navigation failed. Rescheduling for 1 hour.");
        LocalDateTime nextAttempt = LocalDateTime.now().plusHours(1);
        this.reschedule(nextAttempt);
        return false;
    }

    private int claimFreeResources(long executionDeadlineMs) {
        int claimed = 0;
        boolean foundResourceTemplate = true;
        logInfo("Searching for free resources to claim.");

        while (foundResourceTemplate && System.currentTimeMillis() < executionDeadlineMs) {
            foundResourceTemplate = false;
            for (TemplatesEnum template : RESOURCE_TEMPLATES) {
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
                    claimed++;
                    foundResourceTemplate = true;
                    break;
                }
            }
        }
        return claimed;
    }

    private boolean purchaseVipPoints() {
        logInfo("VIP purchase is enabled. Searching for VIP points to buy.");
        ImageSearchResultData vipResult = templateSearchHelper.locatePattern(
                TemplatesEnum.NOMADIC_MERCHANT_VIP,
                SearchConfigConstants.DEFAULT_SINGLE);

        if (!vipResult.isFound()) {
            return false;
        }

        logInfo("Found VIP points. Purchasing with gems.");
        tapNear(new PointData(vipResult.getPoint().getX(), vipResult.getPoint().getY() + 100));
        sleepTask(1000);
        tapNear(new PointData(368, 830));
        sleepTask(1000);
        tapNear(new PointData(355, 788));
        sleepTask(1000);
        return true;
    }

    private boolean refreshMerchantOffers() {
        logInfo("No more resources or VIP points found. Checking for daily refresh.");
        ImageSearchResultData dailyRefreshResult = templateSearchHelper.locatePattern(
                TemplatesEnum.MYSTERY_SHOP_DAILY_REFRESH,
                SearchConfigConstants.DEFAULT_SINGLE);

        if (!dailyRefreshResult.isFound()) {
            logInfo("No daily refresh available. All Nomadic Merchant operations are complete.");
            return false;
        }

        logInfo("Daily refresh is available. Using it now.");
        tapInside(dailyRefreshResult.getPoint(), dailyRefreshResult.getPoint());
        sleepTask(2000);
        return true;
    }

    private void recordOutcome(int freeResourcesClaimedCount, int vipPointsPurchasedCount,
            int dailyRefreshUsedCount, long executionDeadlineMs) {
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

        reschedule(GameTimeUtils.dailyResetTime());
    }

    boolean navigateToNomadicMerchantShop() {
        return navigationHelper.navigateToShop(ShopTab.NOMADIC_MERCHANT);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }
}
