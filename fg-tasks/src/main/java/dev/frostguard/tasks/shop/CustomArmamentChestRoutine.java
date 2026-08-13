package dev.frostguard.tasks.shop;

import java.time.LocalDateTime;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;

/**
 * matt/2026-08-13: the top-right cart-icon Shop panel, being built out tab by tab
 * ("let's go through and tab by tab... putting options like when this shows up you
 * do X/Y/Z"). Custom Armament Chest is the first (default) tab -- a rotating,
 * periodic event that "might not exist for weeks at a time." Its free reward is a
 * plain "Claimable" badge on the chest icon (top-right of the banner), separate from
 * the paid $4.99/$9.99/etc chest packs below it -- this routine ONLY ever taps the
 * free Claimable badge, never a purchase button.
 *
 * <p>
 * <b>Live-verified 2026-08-13</b>: top-right cart icon -> Custom Armament Chest tab
 * (default/first tab) -> Claimable badge tap -> badge instantly replaced by a
 * countdown timer, no reward-reveal popup -- confirms it's a silent single-tap claim.
 *
 * <p>
 * Checked once a day per matt's request, since the event itself may not be running
 * at all -- a miss here just means "not currently available," not a failure.
 */
public class CustomArmamentChestRoutine extends DelayedTask {

    private static final int IDLE_RECHECK_HOURS = 24;
    private static final int PANEL_SETTLE_MS = 1200;
    private static final int ACTION_SETTLE_MS = 900;

    public CustomArmamentChestRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
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

    @Override
    protected void execute() {
        ImageSearchResultData cartBtn = templateSearchHelper.locatePattern(
                TemplatesEnum.HOME_SHOP_CART_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!cartBtn.isFound()) {
            logInfo(logLine("Shop cart icon not found. Rechecking in " + IDLE_RECHECK_HOURS + " hours."));
            reschedule(LocalDateTime.now().plusHours(IDLE_RECHECK_HOURS));
            return;
        }
        tapPoint(cartBtn.getPoint());
        sleepTask(PANEL_SETTLE_MS);

        // Custom Armament Chest is the default/first tab -- no tab navigation needed,
        // just check for the Claimable badge on whatever loaded.
        ImageSearchResultData claimable = templateSearchHelper.locatePattern(
                TemplatesEnum.SHOP_CUSTOM_ARMAMENT_CHEST_CLAIMABLE, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (claimable.isFound()) {
            logInfo(logLine("Claimable chest badge found. Claiming."));
            tapPoint(claimable.getPoint());
            sleepTask(ACTION_SETTLE_MS);
            StatisticsService.obtain().addToCounter(profile, "Custom Armament Chest Claimed", 1);
        } else {
            logInfo(logLine("Nothing claimable right now (event may not be running)."));
        }

        pressBack();

        logInfo(logLine("Rechecking in " + IDLE_RECHECK_HOURS + " hours."));
        reschedule(LocalDateTime.now().plusHours(IDLE_RECHECK_HOURS));
    }

    private String logLine(String note) {
        return "CustomArmamentChestRoutine | " + note;
    }
}
