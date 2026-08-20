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
import dev.frostguard.engine.service.StatisticsService;
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


		int allianceGiftsCollected = 0;
		allianceGiftsCollected += gatherLootChests();
		allianceGiftsCollected += gatherAllianceGifts();
		allianceGiftsCollected += gatherHonorChest();

		// Count what was actually collected this pass, not a per-run +1. The
		// per-row individual-gift loop reports a true claimed count; the loot claim and (config-
		// gated) honor claim are single blind taps with no on-screen success signal, so each is
		// counted as 1 when its claim action is performed.
		if (allianceGiftsCollected > 0) {
			StatisticsService.obtain().addToCounter(profile, "Alliance Gifts Collected", allianceGiftsCollected);
		}


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

private int gatherIndividualGifts() {
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

		return giftsClaimed;
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

private int gatherAllianceGifts() {
		logInfo(routineLogAllianceChestLine("Entering alliance gifts section."));
		tapInside(new PointData(410, 375), new PointData(626, 420));
		sleepTask(TAB_CHANGE_WAIT_TIME_MS);


		ImageSearchResultData claimAllButton = templateSearchHelper.locatePattern(
				TemplatesEnum.ALLIANCE_CHEST_CLAIM_ALL_BUTTON,
				SearchConfigConstants.DEFAULT_SINGLE);

		int giftsCollected;
		if (claimAllButton.isFound()) {
			// 'Claim All' is only present when at least one gift is claimable, so its presence
			// proves ≥1 real claim. Count the batch as 1 (the per-gift breakdown isn't exposed here).
			logInfo(routineLogAllianceChestLine("'Claim All' button detected. Collecting all gifts."));
			tapInside(claimAllButton);
			sleepTask(CLAIM_WAIT_TIME_MS);


			dismissPopupIfPresent();
			giftsCollected = 1;
		} else {
			logInfo(routineLogAllianceChestLine("Zero 'Claim All' button for gifts. Inspecting for individual gifts."));
			giftsCollected = gatherIndividualGifts();
		}
		sleepTask(SHORT_WAIT_TIME_MS);
		return giftsCollected;
	}

private void restoreHomeScreen() {
		logInfo(routineLogAllianceChestLine("Returning to home screen."));
		pressBack();
		sleepTask(SHORT_WAIT_TIME_MS);
		pressBack();
		sleepTask(SHORT_WAIT_TIME_MS);


	}

private int gatherLootChests() {
		logInfo(routineLogAllianceChestLine("Collecting loot chests."));
		tapInside(new PointData(56, 375), new PointData(320, 420));
		sleepTask(TAB_CHANGE_WAIT_TIME_MS);


		tapNear(new PointData(360, 1204));
		sleepTask(CLAIM_WAIT_TIME_MS);


		dismissPopupIfPresent();
		sleepTask(SHORT_WAIT_TIME_MS);

		// Blind claim tap (no on-screen success signal available here) — count as one loot claim.
		return 1;
	}

private int gatherHonorChest() {
		boolean honorChestEnabled = profile.getConfig(ConfigurationKeyEnum.ALLIANCE_HONOR_CHEST_BOOL, Boolean.class);

		if (honorChestEnabled) {
			logInfo(routineLogAllianceChestLine("Collecting honor chest."));
			tapInside(new PointData(320, 200), new PointData(400, 250));
			sleepTask(TAB_CHANGE_WAIT_TIME_MS);


			dismissPopupIfPresent();
			// Blind claim tap gated only by config — count as one honor claim when enabled.
			return 1;
		} else {
			logInfo(routineLogAllianceChestLine("Honor chest collection is disabled. Skipping."));
			return 0;
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
