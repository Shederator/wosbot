package dev.frostguard.tasks.alliance;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.convert.GameTimeUtils;
import java.time.LocalDateTime;

public class AllianceChestRoutine extends DelayedTask {

private static final int TAB_CHANGE_WAIT_TIME_MS = 500;

private static final int CLAIM_WAIT_TIME_MS = 1500;

private static final int SHORT_WAIT_TIME_MS = 300;

public AllianceChestRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
		super(profile, tpDailyTask);
	}

@Override
	protected void execute() {

		if (!reachAllianceScreen()) {
			deferAndExit("Failed to navigate to alliance screen");
			return;
		}

		if (!openUpAllianceChestScreen()) {
			deferAndExit("Failed to open alliance chest screen");
			return;
		}


		gatherLootChests();
		gatherAllianceGifts();
		gatherHonorChest();


		restoreHomeScreen();


		queueNextRun();
	}

private String routineLogAllianceChestLine(String note) {
        return "AllianceChestRoutine | " + note;
    }

private boolean openUpAllianceChestScreen() {
		ImageSearchResultData allianceChestResult = templateSearchHelper.locatePattern(
				TemplatesEnum.ALLIANCE_CHEST_BUTTON,
				SearchConfigConstants.DEFAULT_SINGLE);
		if (!allianceChestResult.isFound()) {
			logWarning(routineLogAllianceChestLine("Alliance chest button not detected."));
			return false;
		}

		tapInside(allianceChestResult);
		sleepTask(TAB_CHANGE_WAIT_TIME_MS);
		return true;
	}

private void gatherIndividualGifts() {
		int giftsClaimed = 0;
		int consecutiveFailures = 0;
		int maxConsecutiveFailures = 3;


		while (consecutiveFailures < maxConsecutiveFailures) {
			ImageSearchResultData claimButton = templateSearchHelper.locatePattern(
					TemplatesEnum.ALLIANCE_CHEST_CLAIM_BUTTON,
					SearchConfigConstants.DEFAULT_SINGLE);

			if (claimButton.isFound()) {
				logDebug(routineLogAllianceChestLine("Collecting individual gift #" + (giftsClaimed + 1)));
				tapInside(claimButton);
				sleepTask(CLAIM_WAIT_TIME_MS);
				giftsClaimed++;
				consecutiveFailures = 0;


				dismissPopupIfPresent();
			} else {
				consecutiveFailures++;


				if (consecutiveFailures >= maxConsecutiveFailures) {
					logDebug(routineLogAllianceChestLine("Zero additional individual gifts detected."));
					break;
				}
			}
		}

		if (giftsClaimed > 0) {
			logInfo(routineLogAllianceChestLine("Successfully collected " + giftsClaimed + " individual gifts."));
		} else {
			logInfo(routineLogAllianceChestLine("Zero individual gifts to claim."));
		}
	}

private void dismissPopupIfPresent() {


		tapInside(new PointData(578, 1180), new PointData(641, 1200), 2, 200);
		sleepTask(SHORT_WAIT_TIME_MS);
	}

private boolean reachAllianceScreen() {
		logInfo(routineLogAllianceChestLine("Moving to alliance screen"));
		tapInside(new PointData(493, 1187), new PointData(561, 1240));
		sleepTask(3000);


		ImageSearchResultData allianceVerification = templateSearchHelper.locatePattern(
				TemplatesEnum.ALLIANCE_CHEST_BUTTON,
				SearchConfigConstants.DEFAULT_SINGLE);
		return allianceVerification.isFound();
	}

private void gatherAllianceGifts() {
		logInfo(routineLogAllianceChestLine("Entering alliance gifts section."));
		tapInside(new PointData(410, 375), new PointData(626, 420));
		sleepTask(TAB_CHANGE_WAIT_TIME_MS);


		ImageSearchResultData claimAllButton = templateSearchHelper.locatePattern(
				TemplatesEnum.ALLIANCE_CHEST_CLAIM_ALL_BUTTON,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (claimAllButton.isFound()) {
			logInfo(routineLogAllianceChestLine("'Claim All' button detected. Collecting all gifts."));
			tapInside(claimAllButton);
			sleepTask(CLAIM_WAIT_TIME_MS);


			dismissPopupIfPresent();
		} else {
			logInfo(routineLogAllianceChestLine("Zero 'Claim All' button for gifts. Inspecting for individual gifts."));
			gatherIndividualGifts();
		}
		sleepTask(SHORT_WAIT_TIME_MS);
	}

private void restoreHomeScreen() {
		logInfo(routineLogAllianceChestLine("Returning to home screen."));
		pressBack();
		sleepTask(SHORT_WAIT_TIME_MS);
		pressBack();
		sleepTask(SHORT_WAIT_TIME_MS);


	}

private void gatherLootChests() {
		logInfo(routineLogAllianceChestLine("Collecting loot chests."));
		tapInside(new PointData(56, 375), new PointData(320, 420));
		sleepTask(TAB_CHANGE_WAIT_TIME_MS);


		tapNear(new PointData(360, 1204));
		sleepTask(CLAIM_WAIT_TIME_MS);


		dismissPopupIfPresent();
		sleepTask(SHORT_WAIT_TIME_MS);
	}

private void gatherHonorChest() {
		boolean honorChestEnabled = profile.getConfig(ConfigurationKeyEnum.ALLIANCE_HONOR_CHEST_BOOL, Boolean.class);

		if (honorChestEnabled) {
			logInfo(routineLogAllianceChestLine("Collecting honor chest."));
			tapInside(new PointData(320, 200), new PointData(400, 250));
			sleepTask(TAB_CHANGE_WAIT_TIME_MS);


			dismissPopupIfPresent();
		} else {
			logInfo(routineLogAllianceChestLine("Honor chest collection is disabled. Skipping."));
		}
	}

private void queueNextRun() {
		int offsetMinutes = profile.getConfig(ConfigurationKeyEnum.ALLIANCE_CHESTS_OFFSET_INT, Integer.class);
		LocalDateTime nextExecutionTime = LocalDateTime.now().plusMinutes(offsetMinutes);
		nextExecutionTime = nextExecutionTime.isAfter(GameTimeUtils.dailyResetTime()) ? GameTimeUtils.dailyResetTime()
				: nextExecutionTime;
		reschedule(nextExecutionTime);
		logInfo(routineLogAllianceChestLine("Alliance chest task completed. Next run at: " + nextExecutionTime.format(DATETIME_FORMATTER)));
	}

private void deferAndExit(String reason) {
		logWarning(routineLogAllianceChestLine(reason + ". Planning next run task to run in 5 minutes."));
		LocalDateTime nextExecutionTime = LocalDateTime.now().plusMinutes(5);
		reschedule(nextExecutionTime);
	}
}
