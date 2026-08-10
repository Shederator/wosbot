package dev.frostguard.tasks.alliance;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.TesseractSettingsData;
import dev.frostguard.engine.helper.NavigationHelper;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.schedule.TaskQueue;
import java.time.LocalDateTime;

public class AllianceTechRoutine extends DelayedTask {

private static final AreaData DONATION_BUTTON_AREA_VALUE = new AreaData(
			new PointData(450, 1000),
			new PointData(580, 1050));

private static final AreaData POPUP_CLOSE_AREA_VALUE = new AreaData(
			new PointData(270, 30),
			new PointData(280, 80));

private static final AreaData ALLIANCE_COINS_BUTTON_AREA_VALUE = new AreaData(
			new PointData(580, 30),
			new PointData(670, 50));

private static final AreaData COIN_COUNT_OCR_AREA_VALUE = new AreaData(
			new PointData(272, 257),
			new PointData(443, 285));

private static final PointData TREE_RESET_SWIPE_START_VALUE = new PointData(360, 600);

private static final PointData TREE_RESET_SWIPE_END_VALUE = new PointData(360, 984);

private static final PointData TREE_SCROLL_SWIPE_START_VALUE = new PointData(360, 900);

private static final PointData TREE_SCROLL_SWIPE_END_VALUE = new PointData(360, 540);

private static final int TREE_RESET_SWIPE_COUNT_VALUE = 5;

private static final int TREE_SCROLL_ATTEMPT_COUNT_VALUE = 5;

private static final long TREE_SCROLL_SETTLE_MILLIS_VALUE = 500;

private static final int DONATION_TAP_COUNT_VALUE = 25;

private static final int MIN_OFFSET_MINUTES_FLOOR = 10;

private static final int DEFAULT_OFFSET_MINUTES_VALUE = 60;

private static final int ERROR_RETRY_MINUTES_VALUE = 10;

private static final TesseractSettingsData COIN_COUNT_OCR_SETTINGS_VALUE = TesseractSettingsData.assembler()
			.charWhitelist("0123456789")
			.pageAnalysis(TesseractSettingsData.PageAnalysis.SINGLE_LINE)
			.build();

private int offsetMinutes;

private boolean donationsSuccessful;

public AllianceTechRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
		super(profile, tpDailyTask);
	}

@Override
	protected void execute() {

		donationsSuccessful = false;
		hydrateConfiguration();

		if (!reachTechMenu()) {
			manageTaskFailure("Failed to navigate to Alliance Tech menu");
			return;
		}

		if (!locateDonationButton()) {
			manageTaskFailure("Thumbs-up donation button not found");
			return;
		}

		performDonationsFlow();
		donationsSuccessful = true;

		inspectAndTriggerAllianceShop();

		queueNextRun();
	}

@Override
	public boolean provideDailyMissionProgress() {
		return donationsSuccessful;
	}

private void inspectAndTriggerAllianceShop() {
		boolean shopEnabled = profile.getConfig(
				ConfigurationKeyEnum.ALLIANCE_SHOP_ENABLED_BOOL,
				Boolean.class);

		if (!shopEnabled) {
			logDebug(routineLogAllianceTechLine("Alliance Shop is disabled. Skipping shop check."));
			return;
		}

		logInfo(routineLogAllianceTechLine("Alliance Shop enabled. Inspecting current coins."));

		reachCoinsDisplay();

		Integer currentCoins = readNumberValue(COIN_COUNT_OCR_AREA_VALUE.topLeft(), COIN_COUNT_OCR_AREA_VALUE.bottomRight(),
				COIN_COUNT_OCR_SETTINGS_VALUE);

		if (currentCoins == null) {
			logWarning(routineLogAllianceTechLine("Could not read current alliance coins. Skipping shop trigger."));
			return;
		}

		Integer minCoins = profile.getConfig(
				ConfigurationKeyEnum.ALLIANCE_SHOP_MIN_COINS_TO_ACTIVATE_INT,
				Integer.class);

		logInfo(routineLogAllianceTechLine("Current alliance coins: " + currentCoins + ", Minimum required: " + minCoins));

		if (currentCoins > minCoins) {
			triggerAllianceShopRoutineFlow();
		} else {
			logInfo(routineLogAllianceTechLine("Insufficient coins to trigger Alliance Shop task"));
		}
	}

private void triggerAllianceShopRoutineFlow() {
		TaskQueue queue = dev.frostguard.engine.service.ScheduleService.obtain().getCoordinator().getQueue(profile.getId());

		if (queue == null) {
			logError(routineLogAllianceTechLine("Could not retrieve task queue for profile. Cannot trigger shop task."));
			return;
		}

		logInfo(routineLogAllianceTechLine("Triggering Alliance Shop task to execute immediately"));
		queue.runNow(TpDailyTaskEnum.ALLIANCE_SHOP, true);
	}

private String routineLogAllianceTechLine(String note) {
        return "AllianceTechRoutine | " + note;
    }

private void performDonationsFlow() {
		logInfo(routineLogAllianceTechLine("Performing " + DONATION_TAP_COUNT_VALUE + " donations to Alliance Tech"));

		tapInside(
				DONATION_BUTTON_AREA_VALUE.topLeft(),
				DONATION_BUTTON_AREA_VALUE.bottomRight(),
				DONATION_TAP_COUNT_VALUE,
				150

		);

		logInfo(routineLogAllianceTechLine("Donations completed finished cleanly"));
	}

private boolean locateDonationButton() {
		logDebug(routineLogAllianceTechLine("Scanning for thumbs-up donation button"));

		ImageSearchResultData thumbsUpResult = findThumbsUp(SearchConfigConstants.SINGLE_WITH_RETRIES);

		if (!thumbsUpResult.isFound()) {
			logInfo(routineLogAllianceTechLine(
					"Thumbs-up marker is not visible at the opened position. Searching all Tech trees."));
			thumbsUpResult = searchTechTrees();
		}

		if (thumbsUpResult.isFound()) {
			logInfo(routineLogAllianceTechLine("Thumbs-up donation button detected. Proceeding with donations."));
			tapInside(thumbsUpResult);
			sleepTask(500);

			return true;
		}

		logWarning(routineLogAllianceTechLine("Thumbs-up donation button not detected after scanning all Tech trees"));
		return false;
	}

private ImageSearchResultData searchTechTrees() {
		ImageSearchResultData lastResult = null;
		for (TechCategory category : TechCategory.values()) {
			checkPreemption();
			selectTechCategory(category);
			lastResult = findThumbsUp(SearchConfigConstants.QUICK_SEARCH);
			if (lastResult.isFound()) {
				logInfo(routineLogAllianceTechLine("Found the thumbs-up marker at the remembered '"
						+ category.label + "' tree position."));
				return lastResult;
			}

			logInfo(routineLogAllianceTechLine("Searching the '" + category.label + "' Tech tree."));
			resetTreeToTop();

			for (int viewport = 0; viewport <= TREE_SCROLL_ATTEMPT_COUNT_VALUE; viewport++) {
				checkPreemption();
				lastResult = findThumbsUp(SearchConfigConstants.QUICK_SEARCH);
				if (lastResult.isFound()) {
					logInfo(routineLogAllianceTechLine("Found the thumbs-up marker in '"
							+ category.label + "' at viewport " + (viewport + 1) + "."));
					return lastResult;
				}

				if (viewport < TREE_SCROLL_ATTEMPT_COUNT_VALUE) {
					swipe(TREE_SCROLL_SWIPE_START_VALUE, TREE_SCROLL_SWIPE_END_VALUE);
					sleepTask(TREE_SCROLL_SETTLE_MILLIS_VALUE);
				}
			}

			logDebug(routineLogAllianceTechLine(
					"No thumbs-up marker found in the bounded '" + category.label + "' tree scan."));
		}
		return lastResult;
	}

private void selectTechCategory(TechCategory category) {
		tapInside(category.tabArea.topLeft(), category.tabArea.bottomRight());
		sleepTask(TREE_SCROLL_SETTLE_MILLIS_VALUE);
	}

private void resetTreeToTop() {
		for (int swipeAttempt = 0; swipeAttempt < TREE_RESET_SWIPE_COUNT_VALUE; swipeAttempt++) {
			checkPreemption();
			swipe(TREE_RESET_SWIPE_START_VALUE, TREE_RESET_SWIPE_END_VALUE);
			sleepTask(TREE_SCROLL_SETTLE_MILLIS_VALUE);
		}
	}

private ImageSearchResultData findThumbsUp(SearchConfig searchConfig) {
		return templateSearchHelper.locatePattern(
				TemplatesEnum.ALLIANCE_TECH_THUMB_UP,
				searchConfig);
	}

private enum TechCategory {
		GROWTH("Growth", new AreaData(new PointData(22, 236), new PointData(244, 316))),
		TERRITORY("Territory", new AreaData(new PointData(251, 236), new PointData(470, 316))),
		BATTLE("Battle", new AreaData(new PointData(477, 236), new PointData(698, 316)));

		private final String label;
		private final AreaData tabArea;

		TechCategory(String label, AreaData tabArea) {
			this.label = label;
			this.tabArea = tabArea;
		}
	}

private void hydrateConfiguration() {
		int rawOffset = profile.getConfig(
				ConfigurationKeyEnum.ALLIANCE_TECH_OFFSET_INT,
				Integer.class);

		if (rawOffset < MIN_OFFSET_MINUTES_FLOOR) {
			logWarning(routineLogAllianceTechLine("Invalid offset configured: " + rawOffset +
					" minutes. Must be at least " + MIN_OFFSET_MINUTES_FLOOR +
					". Using default: " + DEFAULT_OFFSET_MINUTES_VALUE));
			offsetMinutes = DEFAULT_OFFSET_MINUTES_VALUE;
		} else {
			offsetMinutes = rawOffset;
		}

		logInfo(routineLogAllianceTechLine("Configuration loaded - Offset: " + offsetMinutes + " minutes"));
	}

private void reachCoinsDisplay() {
		logDebug(routineLogAllianceTechLine("Closing donation popup"));
		tapInside(
				POPUP_CLOSE_AREA_VALUE.topLeft(),
				POPUP_CLOSE_AREA_VALUE.bottomRight(),
				3,

				200

		);

		logDebug(routineLogAllianceTechLine("Entering alliance coins popup"));
		tapInside(
				ALLIANCE_COINS_BUTTON_AREA_VALUE.topLeft(),
				ALLIANCE_COINS_BUTTON_AREA_VALUE.bottomRight(),
				1,
				1000

		);
	}

private void manageTaskFailure(String reason) {
		logWarning(routineLogAllianceTechLine("Routine pass did not complete: " + reason));

		LocalDateTime retryTime = LocalDateTime.now().plusMinutes(ERROR_RETRY_MINUTES_VALUE);
		reschedule(retryTime);

		logInfo(routineLogAllianceTechLine("Task rescheduled to retry in " + ERROR_RETRY_MINUTES_VALUE + " minutes"));
	}

private boolean reachTechMenu() {
		logDebug(routineLogAllianceTechLine("Moving to Alliance Tech menu"));

		boolean success = navigationHelper.navigateToAllianceMenu(NavigationHelper.AllianceMenu.TECH);

		if (success) {
			logDebug(routineLogAllianceTechLine("Successfully navigated to Alliance Tech menu"));
		} else {
			logError(routineLogAllianceTechLine("Could not navigate to Alliance Tech menu"));
		}

		return success;
	}

private void queueNextRun() {
		LocalDateTime nextExecutionTime = LocalDateTime.now().plusMinutes(offsetMinutes);
		reschedule(nextExecutionTime);

		logInfo(routineLogAllianceTechLine("Alliance Tech task finished cleanly. Next execution in " +
				offsetMinutes + " minutes"));
	}
}
