package dev.frostguard.tasks.shop;

import java.time.LocalDateTime;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;

/**
 * Top-right cart-icon Shop panel, "Daily Deals" tab (second tab,
 * next to Custom Armament Chest). Has a genuinely free "Free" chest badge on the
 * left side of the banner, once a day -- everything else on this tab is a paid
 * pack ($0.99-$4.99). This routine ONLY ever taps the free badge.
 *
 * <p>
 * <b>Live-verified 2026-08-13</b>: cart icon -> Daily Deals tab -> Free chest badge
 * tap -> reward reveal (100 gems, confirmed via balance going 47,599 -> 47,699) ->
 * badge instantly replaced by a countdown timer, same silent single-tap-claim
 * pattern as Custom Armament Chest.
 */
public class DailyDealsFreeChestRoutine extends DelayedTask {

    private static final PointData REWARD_REVEAL_TAP_ANYWHERE = new PointData(360, 640);
    private static final int IDLE_RECHECK_HOURS = 24;
    private static final int PANEL_SETTLE_MS = 1200;
    private static final int ACTION_SETTLE_MS = 900;

    public DailyDealsFreeChestRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    @Override
    public boolean provideDailyMissionProgress() {
        return true;
    }

    // Same fix as CustomArmamentChestRoutine -- a single locatePattern attempt for
    // the cart icon had no recovery if some other task left a popup/panel open, so it silently gave
    // up and waited a full day instead of clearing back to a known screen and retrying.
    private static final int MAX_NAV_RETRIES = 3;

    private ImageSearchResultData locateCartButtonRobust() {
        ImageSearchResultData cartBtn = templateSearchHelper.locatePattern(
                TemplatesEnum.HOME_SHOP_CART_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (cartBtn.isFound()) {
            return cartBtn;
        }

        logInfo(logLine("Cart icon not visible on the first look -- clearing whatever's in the way "
                + "and retrying instead of assuming it's genuinely gone."));
        for (int attempt = 1; attempt <= MAX_NAV_RETRIES; attempt++) {
            pressBack();
            sleepTask(500);
            navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);
            cartBtn = templateSearchHelper.locatePattern(
                    TemplatesEnum.HOME_SHOP_CART_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
            if (cartBtn.isFound()) {
                logInfo(logLine("Cart icon found after " + attempt + " recovery attempt(s)."));
                return cartBtn;
            }
        }
        return cartBtn;
    }

    @Override
    protected void execute() {
        ImageSearchResultData cartBtn = locateCartButtonRobust();
        if (!cartBtn.isFound()) {
            logInfo(logLine("Shop cart icon not found even after clearing/retrying. Rechecking in "
                    + IDLE_RECHECK_HOURS + " hours."));
            reschedule(LocalDateTime.now().plusHours(IDLE_RECHECK_HOURS));
            return;
        }
        tapNear(cartBtn.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        ImageSearchResultData tab = templateSearchHelper.locatePattern(
                TemplatesEnum.SHOP_TAB_DAILY_DEALS, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!tab.isFound()) {
            logInfo(logLine("Daily Deals tab not found. Closing and rechecking in " + IDLE_RECHECK_HOURS + " hours."));
            pressBack();
            reschedule(LocalDateTime.now().plusHours(IDLE_RECHECK_HOURS));
            return;
        }
        tapNear(tab.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        ImageSearchResultData freeChest = templateSearchHelper.locatePattern(
                TemplatesEnum.SHOP_DAILY_DEALS_FREE_CHEST, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (freeChest.isFound()) {
            logInfo(logLine("Free chest badge found. Claiming."));
            tapNear(freeChest.getPoint());
            sleepTask(ACTION_SETTLE_MS);
            tapNear(REWARD_REVEAL_TAP_ANYWHERE);
            sleepTask(ACTION_SETTLE_MS);
            StatisticsService.obtain().addToCounter(profile, "Daily Deals Free Chest Claimed", 1);
        } else {
            logInfo(logLine("Free chest already claimed today."));
        }

        pressBack();

        logInfo(logLine("Rechecking in " + IDLE_RECHECK_HOURS + " hours."));
        reschedule(LocalDateTime.now().plusHours(IDLE_RECHECK_HOURS));
    }

    private String logLine(String note) {
        return "DailyDealsFreeChestRoutine | " + note;
    }
}
