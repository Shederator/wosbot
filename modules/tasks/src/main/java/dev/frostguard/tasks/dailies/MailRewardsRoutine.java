package dev.frostguard.tasks.dailies;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.vision.convert.GameTimeUtils;
import java.time.LocalDateTime;

public class MailRewardsRoutine extends DelayedTask {

private static final int MAX_MAIL_SEARCH_ATTEMPTS_LIMIT = 100;

private static final int MAIL_MENU_SEARCH_RETRIES_VALUE = 5;

private static final int MAIL_MENU_OPEN_VERIFICATION_RETRIES_VALUE = 5;

private static final int UNCLAIMED_REWARDS_SEARCH_RETRIES_VALUE = 3;

private static final int SWIPES_PER_PAGE_VALUE = 10;

private static final int MAIL_TAB_COUNT_VALUE = 3;

private static final int ERROR_RETRY_MINUTES_VALUE = 10;

private static final int DEFAULT_OFFSET_MINUTES_VALUE = 60;

private static final PointData MAIL_MENU_SEARCH_TOP_LEFT_VALUE = new PointData(600, 1000);

private static final PointData MAIL_MENU_SEARCH_BOTTOM_RIGHT_VALUE = new PointData(715, 1100);

private static final PointData MAIL_MENU_OPEN_TOP_LEFT_VALUE = new PointData(75, 10);

private static final PointData MAIL_MENU_OPEN_BOTTOM_RIGHT_VALUE = new PointData(175, 60);

private static final PointData CLAIM_BUTTON_TOP_LEFT_VALUE = new PointData(420, 1227);

private static final PointData CLAIM_BUTTON_BOTTOM_RIGHT_VALUE = new PointData(450, 1250);

private static final int CLAIM_BUTTON_TAP_COUNT_VALUE = 4;

private static final int CLAIM_BUTTON_TAP_DELAY_MS = 500;

private static final PointData SCROLL_START_POINT_VALUE = new PointData(40, 913);

private static final PointData SCROLL_END_POINT_VALUE = new PointData(40, 400);

private static final PointData TAB_ALLIANCE_VALUE = new PointData(230, 120);

private static final PointData TAB_SYSTEM_VALUE = new PointData(360, 120);

private static final PointData TAB_REPORTS_VALUE = new PointData(500, 120);

private static final PointData[] MAIL_TAB_BUTTONS = { TAB_ALLIANCE_VALUE, TAB_SYSTEM_VALUE, TAB_REPORTS_VALUE };

private static final String[] MAIL_TAB_NAMES = { "Alliance", "System", "Reports" };

private int scheduleOffsetMinutes;

public MailRewardsRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

@Override
    protected void execute() {
        hydrateConfiguration();

        if (!openUpMailMenu()) {
            manageMailMenuOpenFailure();
            return;
        }

        handleAllMailTabs();
        dismissMailMenu();
        queueNextRun();
    }

@Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.ANY;
    }

@Override
    public boolean provideDailyMissionProgress() {
        return false;
    }

private String routineLogMailRewardsLine(String note) {
        return "MailRewardsRoutine | " + note;
    }

private void manageMailMenuOpenFailure() {
        logError(routineLogMailRewardsLine("Could not open mail menu. Retrying in " + ERROR_RETRY_MINUTES_VALUE + " minutes."));
        reschedule(LocalDateTime.now().plusMinutes(ERROR_RETRY_MINUTES_VALUE));
    }

private LocalDateTime computeNextExecutionTime() {
        LocalDateTime proposedTime = LocalDateTime.now().plusMinutes(scheduleOffsetMinutes);
        LocalDateTime gameResetTime = GameTimeUtils.dailyResetTime();


        return proposedTime.isAfter(gameResetTime) ? gameResetTime : proposedTime;
    }

private void redeemAllVisibleRewards() {
        logInfo(routineLogMailRewardsLine("Collecting rewards in current tab."));
        tapInside(
                CLAIM_BUTTON_TOP_LEFT_VALUE,
                CLAIM_BUTTON_BOTTOM_RIGHT_VALUE,
                CLAIM_BUTTON_TAP_COUNT_VALUE,
                CLAIM_BUTTON_TAP_DELAY_MS);
        sleepTask(500);

        StatisticsService.obtain().addToCounter(profile, "Mail Rewards Claimed", 1);
    }

private boolean hasUnclaimedRewardsFlow() {
        ImageSearchResultData unclaimedRewards = templateSearchHelper.locatePattern(
                TemplatesEnum.MAIL_UNCLAIMED_REWARDS,
                SearchConfig.builder()
                        .withMaxAttempts(UNCLAIMED_REWARDS_SEARCH_RETRIES_VALUE)
                        .withDelay(100L)
                        .build());

        return unclaimedRewards.isFound();
    }

private void scrollDownMailListFlow() {
        for (int i = 0; i < SWIPES_PER_PAGE_VALUE; i++) {


            swipe(SCROLL_START_POINT_VALUE, SCROLL_END_POINT_VALUE);
            sleepTask(250);

        }
    }

private void handleOverflowRewards() {
        int searchAttempts = 0;

        while (hasUnclaimedRewardsFlow()) {
            if (searchAttempts > 0) {
                logInfo(routineLogMailRewardsLine("Overflow rewards detected. Scrolling to reveal more mail."));
                scrollDownMailListFlow();
            }

            redeemAllVisibleRewards();

            searchAttempts++;
            if (searchAttempts >= MAX_MAIL_SEARCH_ATTEMPTS_LIMIT) {
                logError(routineLogMailRewardsLine("There is absolutely no way this condition should ever be hit in a normal scenario. " +
                        "Something is broken. Either you have not checked your mail in DAYS, " +
                        "or we are stuck somewhere you shouldn't be. " +
                        "Please report to the devs which menu this was stuck on if you see this message."));
                break;
            }
        }

        if (searchAttempts > 0) {
            logDebug(routineLogMailRewardsLine("Processed " + searchAttempts + " overflow reward cycle(s)."));
        }
    }

private boolean confirmMailMenuOpened() {
        ImageSearchResultData inMailMenu = templateSearchHelper.locatePattern(
                TemplatesEnum.MAIL_MENU_OPEN,
                SearchConfig.builder()
                        .withArea(new AreaData(MAIL_MENU_OPEN_TOP_LEFT_VALUE, MAIL_MENU_OPEN_BOTTOM_RIGHT_VALUE))
                        .withMaxAttempts(MAIL_MENU_OPEN_VERIFICATION_RETRIES_VALUE)
                        .withDelay(200L)
                        .build());

        if (!inMailMenu.isFound()) {
            logError(routineLogMailRewardsLine("Mail menu did not open successfully."));
            return false;
        }

        logDebug(routineLogMailRewardsLine("Mail menu opened finished cleanly."));
        return true;
    }

private void hydrateConfiguration() {
        Integer configuredOffset = profile.getConfig(
                ConfigurationKeyEnum.MAIL_REWARDS_OFFSET_INT, Integer.class);
        this.scheduleOffsetMinutes = (configuredOffset != null)
                ? configuredOffset
                : DEFAULT_OFFSET_MINUTES_VALUE;

        logDebug(routineLogMailRewardsLine("Configuration loaded - Schedule offset: " + scheduleOffsetMinutes + " minutes"));
    }

private void handleAllMailTabs() {
        for (int i = 0; i < MAIL_TAB_COUNT_VALUE; i++) {
            PointData tabButton = MAIL_TAB_BUTTONS[i];
            String tabName = MAIL_TAB_NAMES[i];

            logInfo(routineLogMailRewardsLine("Processing " + tabName + " tab."));
            handleMailTab(tabButton);
        }
    }

private void switchToTabFlow(PointData tabButton) {
        tapNear(tabButton);
        sleepTask(200);

    }

private void dismissMailMenu() {
        logDebug(routineLogMailRewardsLine("Closing mail menu."));
        pressBack();
        sleepTask(500);

    }

private boolean openUpMailMenu() {
        logInfo(routineLogMailRewardsLine("Entering mail menu."));

        ImageSearchResultData mailMenu = templateSearchHelper.locatePattern(
                TemplatesEnum.MAIL_MENU,
                SearchConfig.builder()
                        .withArea(new AreaData(MAIL_MENU_SEARCH_TOP_LEFT_VALUE, MAIL_MENU_SEARCH_BOTTOM_RIGHT_VALUE))
                        .withMaxAttempts(MAIL_MENU_SEARCH_RETRIES_VALUE)
                        .withDelay(200L)
                        .build());

        if (!mailMenu.isFound()) {
            logError(routineLogMailRewardsLine("Could not find mail menu icon."));
            return false;
        }

        tapInside(mailMenu);
        sleepTask(500);


        return confirmMailMenuOpened();
    }

private void handleMailTab(PointData tabButton) {
        switchToTabFlow(tabButton);


        redeemAllVisibleRewards();


        handleOverflowRewards();
    }

private void queueNextRun() {
        LocalDateTime nextExecutionTime = computeNextExecutionTime();
        reschedule(nextExecutionTime);
        logInfo(routineLogMailRewardsLine("Mail rewards task completed. Next run at: " + nextExecutionTime.format(DATETIME_FORMATTER)));
    }
}
