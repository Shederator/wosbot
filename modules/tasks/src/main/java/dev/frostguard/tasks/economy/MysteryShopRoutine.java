package dev.frostguard.tasks.economy;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.helper.TemplateSearchHelper;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.nav.ShopTab;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.ocr.OcrException;

/**
 * Mystery Shop visit. Free rewards cost nothing. An enabled hero-gear chest
 * or generic shard is bought only at 250 badges, and only while the badge
 * counter can pay for it. A bought card stays in place and turns grey until
 * a refresh, so the purchase is confirmed by the sold-out label rather than
 * by the original icon disappearing.
 */
public class MysteryShopRoutine extends DelayedTask {

    private static final long MAX_TASK_EXECUTION_MS = 2 * 60 * 1000L;
    private static final int NAVIGATION_ATTEMPTS = 5;
    private static final int MAX_OFFERS = 9;
    private static final int SCREEN_WIDTH = 720;
    private static final int SCREEN_HEIGHT = 1280;
    private static final int MIN_SEARCH_WINDOW = 8;

    private static final int CHEST_THRESHOLD = 90;
    private static final int SHARD_THRESHOLD = 95;
    private static final int PRICE_THRESHOLD = 95;
    private static final int SOLD_OUT_THRESHOLD = 90;

    private static final long SEARCH_DELAY_MS = 300L;
    private static final long NAVIGATION_RETRY_MS = 2000L;
    private static final long FREE_TAP_SETTLE_MS = 400L;
    private static final long FREE_CONFIRM_SETTLE_MS = 300L;
    private static final long PURCHASE_SETTLE_MS = 600L;
    private static final long REFRESH_SETTLE_MS = 1500L;
    private static final long SWIPE_SETTLE_MS = 500L;
    private static final long BACK_GAP_MS = 500L;

    /** Badge digits under the hat. The gold star to their left is not part of the number. */
    static final PointData BADGE_BALANCE_TOP_LEFT = new PointData(588, 26);
    static final PointData BADGE_BALANCE_BOTTOM_RIGHT = new PointData(680, 62);

    /**
     * Confirm control of the free-reward and purchase dialogs. No template
     * exists for that button, so the measured point from the previous routine
     * is kept.
     */
    private static final PointData CONFIRM_POINT = new PointData(360, 830);
    private static final PointData SWIPE_START = new PointData(350, 1100);
    private static final PointData SWIPE_END = new PointData(350, 650);

    private static final String FREE_CLAIMS = "Mystery Shop Free Claims";
    private static final String PURCHASES = "Mystery Shop Purchases";
    private static final String REFRESHES = "Daily Refreshes Used";

    /**
     * Remembered across visits on this task instance. It is not a reason to
     * skip the next scan, and the old sticky failure flag is gone: a failed
     * refresh no longer makes the following visit leave before reading the grid.
     */
    private MysteryShopProgress progress = MysteryShopProgress.READY;
    private int consecutiveUnconfirmed;
    private MysteryShopPhase phase = MysteryShopPhase.OPENING;

    public MysteryShopRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected void execute() {
        phase = MysteryShopPhase.OPENING;
        logInfo("Resuming Mystery Shop from " + progress + ".");
        if (!openShop()) {
            return;
        }
        visitOpenShop(System.currentTimeMillis() + MAX_TASK_EXECUTION_MS);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    boolean navigateToMysteryShop() {
        logInfo("Navigating to the Mystery Shop.");
        return navigationHelper.navigateToShop(ShopTab.MYSTERY_SHOP);
    }

    private boolean openShop() {
        for (int attempt = 1; attempt <= NAVIGATION_ATTEMPTS; attempt++) {
            if (navigateToMysteryShop()) {
                return true;
            }
            logWarning("Navigate to shop failed, retrying...");
            sleepTask(NAVIGATION_RETRY_MS);
        }
        finishUnconfirmed("shop-navigation",
                "Shop navigation was not verified after five attempts", false);
        return false;
    }

    private void visitOpenShop(long deadline) {
        boolean buyChest = enabled(ConfigurationKeyEnum.BOOL_MYSTERY_SHOP_250_HERO_WIDGET);
        boolean buyShard = enabled(ConfigurationKeyEnum.BOOL_MYSTERY_SHOP_250_SHARD);
        phase = MysteryShopPhase.READING_BALANCE;
        Integer balance = readBadgeBalance();
        if (balance == null) {
            if (!claimFreeRewards(deadline)) {
                return;
            }
            finishUnconfirmed("balance-unreadable",
                    "Badge balance was not readable. Free rewards were claimed when present. "
                            + "No chest, shard, or refresh was attempted",
                    true);
            return;
        }
        logInfo("Badge balance " + balance + ".");

        boolean revealedLowerGrid = false;
        while (System.currentTimeMillis() < deadline) {
            ActionResult freeReward = claimOneFreeReward();
            if (freeReward == ActionResult.UNCONFIRMED) {
                finishUnconfirmed("action-unverified",
                        "Free reward claim outcome is unverified", true);
                return;
            }
            if (freeReward == ActionResult.DONE) {
                noteConfirmed();
                continue;
            }

            if (buyChest && MysteryShopDecisions.isAffordable(balance)) {
                ActionResult chest = buyOne(TemplatesEnum.MYSTERY_SHOP_CHEST_ICON, CHEST_THRESHOLD,
                        MysteryShopPhase.BUYING_CHEST, "250 badge chest");
                if (chest == ActionResult.UNCONFIRMED) {
                    logUnconfirmedPurchase(balance);
                    finishUnconfirmed("action-unverified",
                            "250 badge chest purchase outcome is unverified", true);
                    return;
                }
                if (chest == ActionResult.DONE) {
                    balance = MysteryShopDecisions.afterPurchase(balance);
                    noteConfirmed();
                    logInfo("250 badge chest purchased. Badge balance now " + balance + ".");
                    continue;
                }
            }

            if (buyShard && MysteryShopDecisions.isAffordable(balance)) {
                ActionResult shard = buyOne(TemplatesEnum.MYSTERY_SHOP_MYTHIC_SHARDS_BUTTON, SHARD_THRESHOLD,
                        MysteryShopPhase.BUYING_SHARD, "250 badge shard");
                if (shard == ActionResult.UNCONFIRMED) {
                    logUnconfirmedPurchase(balance);
                    finishUnconfirmed("action-unverified",
                            "250 badge shard purchase outcome is unverified", true);
                    return;
                }
                if (shard == ActionResult.DONE) {
                    balance = MysteryShopDecisions.afterPurchase(balance);
                    noteConfirmed();
                    logInfo("250 badge shard purchased. Badge balance now " + balance + ".");
                    continue;
                }
            }

            boolean targetsRemain = unaffordableTargetRemains(buyChest, buyShard, balance);
            if (!targetsRemain && !revealedLowerGrid) {
                logInfo("No target on the visible grid. Revealing the lower rows once.");
                emuManager.swipeScreen(EMULATOR_NUMBER, SWIPE_START, SWIPE_END);
                sleepTask(SWIPE_SETTLE_MS);
                revealedLowerGrid = true;
                continue;
            }

            phase = MysteryShopPhase.REFRESHING;
            boolean refreshVisible = isFreeRefresh();
            MysteryShopDecisions.RefreshChoice choice = MysteryShopDecisions.choose(
                    targetsRemain, refreshVisible);
            if (choice == MysteryShopDecisions.RefreshChoice.WAIT_FOR_BADGES) {
                finishWaiting(balance);
                return;
            }
            if (choice == MysteryShopDecisions.RefreshChoice.DAY_COMPLETE) {
                finishDay();
                return;
            }
            if (!useFreeRefresh(balance)) {
                return;
            }
            revealedLowerGrid = false;
        }
        finishUnconfirmed("execution-limit",
                "Mystery Shop reached its two-minute execution limit", true);
    }

    private boolean claimFreeRewards(long deadline) {
        while (System.currentTimeMillis() < deadline) {
            ActionResult claimed = claimOneFreeReward();
            if (claimed == ActionResult.UNCONFIRMED) {
                finishUnconfirmed("action-unverified",
                        "Free reward claim outcome is unverified", true);
                return false;
            }
            if (claimed == ActionResult.NONE) {
                return true;
            }
            noteConfirmed();
        }
        finishUnconfirmed("execution-limit",
                "Mystery Shop reached its two-minute execution limit during free rewards", true);
        return false;
    }

    /**
     * The free-reward search and tap sequence are the previous ones. A second
     * Free label elsewhere on the screen is another reward, not a failed tap.
     * The same label still sitting on the tapped card is unconfirmed.
     */
    private ActionResult claimOneFreeReward() {
        phase = MysteryShopPhase.CLAIMING_FREE;
        ImageSearchResultData reward = templateSearchHelper.locatePattern(
                TemplatesEnum.MYSTERY_SHOP_FREE_REWARD,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (!isFreeReward(reward)) {
            return ActionResult.NONE;
        }
        PointData tapped = reward.getPoint();
        tapInside(tapped, tapped);
        sleepTask(FREE_TAP_SETTLE_MS);
        tapNear(CONFIRM_POINT);
        sleepTask(FREE_CONFIRM_SETTLE_MS);
        ImageSearchResultData stillThere = templateSearchHelper.locatePattern(
                TemplatesEnum.MYSTERY_SHOP_FREE_REWARD,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (isFreeReward(stillThere) && MysteryShopDecisions.sameCard(
                tapped.getX(), tapped.getY(), stillThere.getX(), stillThere.getY())) {
            return ActionResult.UNCONFIRMED;
        }
        StatisticsService.obtain().addToCounter(profile, FREE_CLAIMS, 1);
        logInfo("A free reward has been claimed.");
        return ActionResult.DONE;
    }

    private ActionResult buyOne(TemplatesEnum icon, int iconThreshold, MysteryShopPhase buying, String label) {
        phase = buying;
        Offer offer = findPricedOffer(icon, iconThreshold);
        if (offer == null) {
            return ActionResult.NONE;
        }
        logInfo(label + " found. Icon at (" + offer.icon().getX() + "," + offer.icon().getY()
                + "), price at (" + offer.price().getX() + "," + offer.price().getY() + ").");
        if (!tapInside(offer.price())) {
            return ActionResult.UNCONFIRMED;
        }
        sleepTask(PURCHASE_SETTLE_MS);
        tapNear(CONFIRM_POINT);
        sleepTask(PURCHASE_SETTLE_MS);
        if (!isSoldOutNear(offer)) {
            return ActionResult.UNCONFIRMED;
        }
        StatisticsService.obtain().addToCounter(profile, PURCHASES, 1);
        return ActionResult.DONE;
    }

    private boolean unaffordableTargetRemains(boolean buyChest, boolean buyShard, Integer balance) {
        if (MysteryShopDecisions.isAffordable(balance)) {
            return false;
        }
        if (buyChest && findPricedOffer(TemplatesEnum.MYSTERY_SHOP_CHEST_ICON, CHEST_THRESHOLD) != null) {
            return true;
        }
        return buyShard && findPricedOffer(
                TemplatesEnum.MYSTERY_SHOP_MYTHIC_SHARDS_BUTTON, SHARD_THRESHOLD) != null;
    }

    /**
     * A free refresh that is still visible is not a failure while more free
     * refreshes remain. The grid fingerprint distinguishes a real restock
     * from a tap that did nothing. The button disappearing means the last
     * free refresh was consumed. A dead first tap is retried once in this
     * visit; a second miss is unconfirmed.
     */
    private boolean useFreeRefresh(Integer balance) {
        ImageSearchResultData refresh = templateSearchHelper.locatePattern(
                TemplatesEnum.MYSTERY_SHOP_DAILY_REFRESH,
                SearchConfigConstants.DEFAULT_SINGLE);
        if (!isFreeRefresh(refresh)) {
            finishDay();
            return false;
        }
        logInfo("No target remains. Using free refresh. Badge balance is " + balance + ".");
        RawImageData before = emuManager.captureScreen(EMULATOR_NUMBER);
        int tapsTried = 0;
        while (true) {
            if (!tapInside(refresh)) {
                finishUnconfirmed("action-unverified",
                        "Could not dispatch the free refresh tap", true);
                return false;
            }
            tapsTried++;
            sleepTask(REFRESH_SETTLE_MS);
            ImageSearchResultData stillVisible = templateSearchHelper.locatePattern(
                    TemplatesEnum.MYSTERY_SHOP_DAILY_REFRESH,
                    SearchConfigConstants.DEFAULT_SINGLE);
            if (!isFreeRefresh(stillVisible)) {
                StatisticsService.obtain().addToCounter(profile, REFRESHES, 1);
                noteConfirmed();
                logInfo("Free refresh used. The free refresh button is gone.");
                return true;
            }
            RawImageData after = emuManager.captureScreen(EMULATOR_NUMBER);
            double delta = MysteryShopDecisions.meanChannelDelta(before, after,
                    MysteryShopDecisions.GRID_LEFT, MysteryShopDecisions.GRID_TOP,
                    MysteryShopDecisions.GRID_RIGHT, MysteryShopDecisions.GRID_BOTTOM,
                    MysteryShopDecisions.GRID_STEP);
            if (MysteryShopDecisions.gridChanged(delta)) {
                StatisticsService.obtain().addToCounter(profile, REFRESHES, 1);
                noteConfirmed();
                logInfo("Free refresh used. The button is still visible and the grid changed by mean "
                        + String.format(Locale.ROOT, "%.1f", delta) + ".");
                return true;
            }
            if (MysteryShopDecisions.retryRefreshTap(tapsTried, true, delta)) {
                logInfo("Free refresh button is still visible and the grid is unchanged (mean "
                        + String.format(Locale.ROOT, "%.1f", delta)
                        + "). Tapping it once more.");
                refresh = stillVisible;
                continue;
            }
            finishUnconfirmed("action-unverified",
                    "Free refresh button is still visible and the grid is unchanged (mean "
                            + String.format(Locale.ROOT, "%.1f", delta) + ")",
                    true);
            return false;
        }
    }

    private Offer findPricedOffer(TemplatesEnum iconTemplate, int iconThreshold) {
        List<ImageSearchResultData> considered = new ArrayList<>();
        for (ImageSearchResultData icon : locateAll(iconTemplate, iconThreshold)) {
            if (icon == null || !icon.isFound()) {
                continue;
            }
            boolean duplicate = considered.stream().anyMatch(seen -> MysteryShopDecisions.sameCard(
                    seen.getX(), seen.getY(), icon.getX(), icon.getY()));
            if (duplicate) {
                continue;
            }
            considered.add(icon);
            ImageSearchResultData price = locateIn(
                    TemplatesEnum.MYSTERY_SHOP_250_BADGES_BUTTON,
                    PRICE_THRESHOLD,
                    MysteryShopDecisions.priceWindowTopLeft(icon.getX(), icon.getY()),
                    MysteryShopDecisions.priceWindowBottomRight(icon.getX(), icon.getY()));
            boolean onCard = price.isFound() && MysteryShopDecisions.priceOnSameCard(
                    icon.getX(), icon.getY(), price.getX(), price.getY());
            boolean target = iconTemplate == TemplatesEnum.MYSTERY_SHOP_CHEST_ICON
                    ? MysteryShopDecisions.isChest250(true, onCard)
                    : MysteryShopDecisions.isShard250(true, onCard);
            if (target) {
                return new Offer(icon, price);
            }
        }
        return null;
    }

    private boolean isSoldOutNear(Offer offer) {
        ImageSearchResultData soldOut = locateIn(
                TemplatesEnum.MYSTERY_SHOP_SOLD_OUT,
                SOLD_OUT_THRESHOLD,
                MysteryShopDecisions.soldOutWindowTopLeft(offer.price().getX(), offer.price().getY()),
                MysteryShopDecisions.soldOutWindowBottomRight(offer.price().getX(), offer.price().getY()));
        return MysteryShopDecisions.isSoldOut(soldOut.isFound());
    }

    private boolean isFreeReward(ImageSearchResultData result) {
        return MysteryShopDecisions.isFreeReward(result != null && result.isFound());
    }

    private boolean isFreeRefresh() {
        return isFreeRefresh(templateSearchHelper.locatePattern(
                TemplatesEnum.MYSTERY_SHOP_DAILY_REFRESH,
                SearchConfigConstants.DEFAULT_SINGLE));
    }

    private boolean isFreeRefresh(ImageSearchResultData result) {
        return MysteryShopDecisions.isFreeRefresh(result != null && result.isFound());
    }

    private List<ImageSearchResultData> locateAll(TemplatesEnum template, int threshold) {
        List<ImageSearchResultData> found = templateSearchHelper.locateAllPatterns(
                template,
                TemplateSearchHelper.SearchConfig.builder()
                        .withMaxAttempts(1)
                        .withThreshold(threshold)
                        .withDelay(SEARCH_DELAY_MS)
                        .withMaxResults(MAX_OFFERS)
                        .build());
        return found == null ? List.of() : found;
    }

    private ImageSearchResultData locateIn(TemplatesEnum template, int threshold,
            PointData topLeft, PointData bottomRight) {
        int left = clamp(Math.min(topLeft.getX(), bottomRight.getX()), 0, SCREEN_WIDTH);
        int top = clamp(Math.min(topLeft.getY(), bottomRight.getY()), 0, SCREEN_HEIGHT);
        int right = clamp(Math.max(topLeft.getX(), bottomRight.getX()), 0, SCREEN_WIDTH);
        int bottom = clamp(Math.max(topLeft.getY(), bottomRight.getY()), 0, SCREEN_HEIGHT);
        if (right - left < MIN_SEARCH_WINDOW || bottom - top < MIN_SEARCH_WINDOW) {
            return ImageSearchResultData.miss();
        }
        ImageSearchResultData result = templateSearchHelper.locatePattern(
                template,
                TemplateSearchHelper.SearchConfig.builder()
                        .withMaxAttempts(1)
                        .withThreshold(threshold)
                        .withDelay(SEARCH_DELAY_MS)
                        .withCoordinates(new PointData(left, top), new PointData(right, bottom))
                        .build());
        return result == null ? ImageSearchResultData.miss() : result;
    }

    private Integer readBadgeBalance() {
        MysteryShopPhase restore = phase;
        phase = MysteryShopPhase.READING_BALANCE;
        try {
            String raw = emuManager.readText(
                    EMULATOR_NUMBER,
                    BADGE_BALANCE_TOP_LEFT,
                    BADGE_BALANCE_BOTTOM_RIGHT,
                    CommonOCRSettings.MYSTERY_BADGE_BALANCE_SETTINGS,
                    true);
            Integer parsed = MysteryShopDecisions.parseBalance(raw);
            logInfo("Badge balance read '" + raw + "' as " + parsed + ".");
            return parsed;
        } catch (IOException | OcrException exception) {
            logWarning("Badge balance could not be read: " + exception.getMessage());
            return null;
        } finally {
            phase = restore;
        }
    }

    private void logUnconfirmedPurchase(Integer balance) {
        Integer reread = readBadgeBalance();
        logWarning("Purchase was not confirmed by the sold-out label. Balance before the tap was "
                + balance + " and the reread is " + reread + ". No badges were deducted.");
    }

    private void noteConfirmed() {
        consecutiveUnconfirmed = 0;
        if (progress == MysteryShopProgress.UNCONFIRMED) {
            progress = MysteryShopProgress.READY;
        }
    }

    private void finishWaiting(Integer balance) {
        consecutiveUnconfirmed = 0;
        progress = MysteryShopProgress.WAITING_FOR_BADGES;
        phase = MysteryShopPhase.FINISHED;
        LocalDateTime next = MysteryShopDecisions.earnMoneyVisit(
                LocalDateTime.now(), GameTimeUtils.dailyResetTime());
        logInfo("A 250 badge target is still on screen and the balance is " + balance
                + ". Next check at " + next.format(DATETIME_FORMATTER) + ".");
        reschedule(next);
        leaveShop();
    }

    private void finishDay() {
        consecutiveUnconfirmed = 0;
        progress = MysteryShopProgress.COMPLETE_UNTIL_RESET;
        phase = MysteryShopPhase.FINISHED;
        LocalDateTime next = MysteryShopDecisions.dayCompleteVisit(GameTimeUtils.dailyResetTime());
        logInfo("Mystery Shop is clear until the daily reset. Next check at "
                + next.format(DATETIME_FORMATTER) + ".");
        reschedule(next);
        leaveShop();
    }

    private void finishUnconfirmed(String snapshotType, String reason, boolean shopOpen) {
        MysteryShopPhase failedDuring = phase;
        boolean repeated = MysteryShopDecisions.repeatUnconfirmed(consecutiveUnconfirmed);
        consecutiveUnconfirmed++;
        progress = MysteryShopProgress.UNCONFIRMED;
        phase = MysteryShopPhase.FINISHED;
        LocalDateTime next = MysteryShopDecisions.unconfirmedVisit(
                LocalDateTime.now(), GameTimeUtils.dailyResetTime(), repeated);
        String snapshot = TaskDiagnosticSnapshots.capture(
                emuManager, EMULATOR_NUMBER, "mysteryshop", snapshotType);
        logWarning(reason + ". Progress " + progress + " after " + failedDuring
                + ". Next check at " + next.format(DATETIME_FORMATTER) + "; " + snapshot + ".");
        reschedule(next);
        if (shopOpen) {
            leaveShop();
        }
    }

    private void leaveShop() {
        pressBack();
        sleepTask(BACK_GAP_MS);
        pressBack();
    }

    private boolean enabled(ConfigurationKeyEnum key) {
        return Boolean.TRUE.equals(profile.getConfig(key, Boolean.class));
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private enum ActionResult {
        NONE,
        DONE,
        UNCONFIRMED
    }

    private record Offer(ImageSearchResultData icon, ImageSearchResultData price) {
    }
}
