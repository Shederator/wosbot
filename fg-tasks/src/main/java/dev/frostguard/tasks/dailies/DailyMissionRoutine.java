package dev.frostguard.tasks.dailies;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.vision.convert.GameTimeUtils;
import java.time.LocalDateTime;

public class DailyMissionRoutine extends DelayedTask {

private static final int FINAL_CHECK_BEFORE_RESET_MINUTES_VALUE = 2;

private static final int SAFETY_RESCHEDULE_MINUTES_VALUE = 30;

private static final int POPUP_DISMISS_TAP_COUNT_VALUE = 3;

private static final int MAX_INDIVIDUAL_CLAIMS = 20;

private static final int CLAIM_PROGRESS_TOLERANCE_PIXELS = 20;

private static final PointData DAILY_MISSIONS_BUTTON_VALUE = new PointData(50, 1050);

private static final PointData POPUP_DISMISS_MIN_VALUE = new PointData(10, 100);

private static final PointData POPUP_DISMISS_MAX_VALUE = new PointData(600, 120);

private static final SearchConfig DAILY_SCREEN_TITLE_SEARCH = SearchConfig.builder()
		.withMaxAttempts(3)
		.withDelay(300)
		.withThreshold(90)
		.withArea(new AreaData(new PointData(180, 70), new PointData(540, 170)))
		.build();

private static final SearchConfig DAILY_TAB_BUTTON_SEARCH = SearchConfig.builder()
		.withMaxAttempts(3)
		.withDelay(300)
		.withThreshold(90)
		.withArea(new AreaData(new PointData(300, 1050), new PointData(720, 1200)))
		.build();

private boolean autoScheduleEnabled;

private int checkOffsetMinutes;

public DailyMissionRoutine(AccountDescriptor profile, TpDailyTaskEnum dailyMission) {
		super(profile, dailyMission);
	}

@Override
	protected void execute() {

		hydrateTaskConfiguration();
		reachDailyMissions();
		boolean dailyScreenReached = switchToDailyMissionsTabFlow();
		boolean claimFlowCompleted = dailyScreenReached && redeemAllRewards();
		if (claimFlowCompleted) {
			StatisticsService.obtain().addToCounter(profile, "Daily Missions Claimed", 1);
		}
		dismissInterface();

		configureRecurringBehaviorFlow();
		queueNextExecution();
	}

@Override
	protected LaunchPoint getRequiredStartLocation() {
		return LaunchPoint.ANY;
	}

private void configureRecurringBehaviorFlow() {
		boolean shouldRecur = !autoScheduleEnabled;
		setRecurring(shouldRecur);

		logInfo(routineLogDailyMissionLine(String.format("Task recurring: %s (auto-schedule: %s)",
				shouldRecur, autoScheduleEnabled)));
	}

private boolean switchToDailyMissionsTabFlow() {
		ImageSearchResultData dailyScreenTitle = templateSearchHelper.locatePattern(
				TemplatesEnum.DAILY_MISSION_SCREEN_TITLE,
				DAILY_SCREEN_TITLE_SEARCH);

		if (dailyScreenTitle.isFound()) {
			logDebug(routineLogDailyMissionLine("Daily missions screen already selected"));
			return true;
		}

		ImageSearchResultData dailyTabButton = templateSearchHelper.locatePattern(
				TemplatesEnum.DAILY_MISSION_DAILY_TAB_BUTTON,
				DAILY_TAB_BUTTON_SEARCH);

		if (!dailyTabButton.isFound()) {
			logWarning(routineLogDailyMissionLine("Daily tab button not detected. Skipping claims"));
			return false;
		}

		logInfo(routineLogDailyMissionLine("Switching to daily missions tab at " + dailyTabButton.getPoint()));
		tapInside(dailyTabButton);
		sleepTask(500);

		dailyScreenTitle = templateSearchHelper.locatePattern(
				TemplatesEnum.DAILY_MISSION_SCREEN_TITLE,
				DAILY_SCREEN_TITLE_SEARCH);
		if (!dailyScreenTitle.isFound()) {
			logWarning(routineLogDailyMissionLine("Daily missions title not detected after tab switch. Skipping claims"));
			return false;
		}

		logInfo(routineLogDailyMissionLine("Daily missions screen confirmed"));
		return true;
	}

private ImageSearchResultData seekForIndividualClaimButton() {
		ImageSearchResultData claimButton = templateSearchHelper.locatePattern(
				TemplatesEnum.DAILY_MISSION_CLAIM_BUTTON,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (!claimButton.isFound()) {
			return claimButton;
		}

		ImageSearchResultData disabledClaimButton = templateSearchHelper.locatePattern(
				TemplatesEnum.DAILY_MISSION_CLAIM_BUTTON_DISABLED,
				SearchConfigConstants.DEFAULT_SINGLE);
		if (disabledClaimButton.isFound()
				&& sameClaimTarget(claimButton.getPoint(), disabledClaimButton.getPoint())) {
			logDebug(routineLogDailyMissionLine("Ignoring disabled Claim button at " + claimButton.getPoint()));
			return ImageSearchResultData.miss();
		}

		return claimButton;
	}

private boolean sameClaimTarget(PointData first, PointData second) {
		return first.manhattanDistanceTo(second) <= CLAIM_PROGRESS_TOLERANCE_PIXELS;
	}

private String routineLogDailyMissionLine(String note) {
        return "DailyMissionRoutine | " + note;
    }

private LocalDateTime queueFinalCheckBeforeReset(LocalDateTime gameReset) {
		LocalDateTime finalCheck = gameReset.minusMinutes(FINAL_CHECK_BEFORE_RESET_MINUTES_VALUE);
		logInfo(routineLogDailyMissionLine("Scheduling final check before reset at: " +
				finalCheck.format(DATETIME_FORMATTER)));
		return finalCheck;
	}

private boolean redeemRewardsIndividually() {
		logWarning(routineLogDailyMissionLine("'Claim All' button not detected. Collecting missions individually"));

		int claimedCount = 0;

		while (claimedCount < MAX_INDIVIDUAL_CLAIMS) {
			ImageSearchResultData claimResult = seekForIndividualClaimButton();
			if (!claimResult.isFound()) {
				logInfo(routineLogDailyMissionLine("Individual collecting complete. Claimed " + claimedCount + " rewards"));
				return true;
			}

			claimedCount++;
			PointData claimPoint = claimResult.getPoint();
			logDebug(routineLogDailyMissionLine("Collecting individual reward #" + claimedCount + " at " + claimPoint));

			tapNear(claimPoint);
			dismissRewardPopupsFlow();
			sleepTask(500);

			ImageSearchResultData nextClaim = seekForIndividualClaimButton();
			if (nextClaim.isFound() && sameClaimTarget(claimPoint, nextClaim.getPoint())) {
				sleepTask(500);
				nextClaim = seekForIndividualClaimButton();
				if (nextClaim.isFound() && sameClaimTarget(claimPoint, nextClaim.getPoint())) {
					logWarning(routineLogDailyMissionLine("Claim button remained at " + claimPoint
							+ " after tapping. Stopping because no visual progress was detected"));
					return false;
				}
			}
		}

		logWarning(routineLogDailyMissionLine("Stopped individual claims at safety limit "
				+ MAX_INDIVIDUAL_CLAIMS));
		return false;
	}

private void hydrateTaskConfiguration() {
		this.autoScheduleEnabled = profile.getConfig(
				ConfigurationKeyEnum.DAILY_MISSION_AUTO_SCHEDULE_BOOL,
				Boolean.class);

		this.checkOffsetMinutes = profile.getConfig(
				ConfigurationKeyEnum.DAILY_MISSION_OFFSET_INT,
				Integer.class);

		logInfo(routineLogDailyMissionLine(String.format("Configuration - Auto-schedule: %s, Check offset: %d minutes",
				autoScheduleEnabled, checkOffsetMinutes)));
	}

private void dismissRewardPopupsFlow() {
		tapInside(
				POPUP_DISMISS_MIN_VALUE,
				POPUP_DISMISS_MAX_VALUE,
				POPUP_DISMISS_TAP_COUNT_VALUE,
				150

		);
	}

private void redeemAllRewardsAtOnce(ImageSearchResultData claimAllResult) {
		logInfo(routineLogDailyMissionLine("'Claim All' button detected. Collecting all rewards at once"));

		tapInside(claimAllResult);
		dismissRewardPopupsFlow();
	}

private ImageSearchResultData seekForClaimAllButton() {
		return templateSearchHelper.locatePattern(
				TemplatesEnum.DAILY_MISSION_CLAIMALL_BUTTON,
				SearchConfigConstants.DEFAULT_SINGLE);
	}

private void reachDailyMissions() {
		logInfo(routineLogDailyMissionLine("Moving to daily missions interface"));

		tapNear(DAILY_MISSIONS_BUTTON_VALUE);
		sleepTask(3000);

	}

private void queueNextExecution() {
		if (isRecurring()) {
			queueManualMode();
		} else {
			queueAutoMode();
		}
	}

private LocalDateTime queueAtOffsetTime(LocalDateTime proposedTime, LocalDateTime gameReset,
			boolean beforeFinalCheckWindow) {
		LocalDateTime cappedTime = gameReset.minusMinutes(FINAL_CHECK_BEFORE_RESET_MINUTES_VALUE);

		if (beforeFinalCheckWindow && proposedTime.isAfter(cappedTime)) {
			logInfo(routineLogDailyMissionLine("Proposed time exceeds reset window. Capping at: " +
					cappedTime.format(DATETIME_FORMATTER)));
			return cappedTime;
		}

		return proposedTime;
	}

private boolean redeemAllRewards() {
		logInfo(routineLogDailyMissionLine("Scanning for claim buttons"));

		ImageSearchResultData claimAllResult = seekForClaimAllButton();

		if (claimAllResult.isFound()) {
			redeemAllRewardsAtOnce(claimAllResult);
			return true;
		} else {
			return redeemRewardsIndividually();
		}
	}

private void dismissInterface() {
		pressBack();
		sleepTask(500);

	}

private void queueManualMode() {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime gameReset = GameTimeUtils.dailyResetTime();
		LocalDateTime proposedTime = now.plusMinutes(checkOffsetMinutes);
		LocalDateTime finalCheckTime = gameReset.minusMinutes(FINAL_CHECK_BEFORE_RESET_MINUTES_VALUE);
		boolean beforeFinalCheckWindow = now.isBefore(finalCheckTime);

		LocalDateTime nextExecution;

		if (beforeFinalCheckWindow && proposedTime.isAfter(gameReset)) {
			nextExecution = queueFinalCheckBeforeReset(gameReset);
		} else {
			nextExecution = queueAtOffsetTime(proposedTime, gameReset, beforeFinalCheckWindow);
		}

		reschedule(nextExecution);
		logInfo(routineLogDailyMissionLine("Next execution scheduled for: " + nextExecution.format(DATETIME_FORMATTER) +
				" (Manual mode)"));
	}

private void queueAutoMode() {
		LocalDateTime safetyTime = LocalDateTime.now().plusMinutes(SAFETY_RESCHEDULE_MINUTES_VALUE);
		reschedule(safetyTime);

		logInfo(routineLogDailyMissionLine("Auto-schedule mode - safety reschedule at: " +
				safetyTime.format(DATETIME_FORMATTER)));
	}
}
