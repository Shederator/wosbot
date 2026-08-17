package dev.frostguard.tasks.exploration;

import java.time.LocalDateTime;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.SizeData;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;

public class ExplorationRoutine extends DelayedTask {

	private static final PointData EXPLORATION_ENTRY_TOP_LEFT = new PointData(40, 1190);
	private static final PointData EXPLORATION_ENTRY_BOTTOM_RIGHT = new PointData(100, 1250);
	private static final PointData REWARD_POPUP_TOP_LEFT = new PointData(230, 890);
	private static final PointData REWARD_POPUP_BOTTOM_RIGHT = new PointData(490, 960);
	private static final int MAX_OPEN_ATTEMPTS = 2;
	private static final int MAX_CLAIM_TAP_ATTEMPTS = 3;

	public ExplorationRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
		super(profile, tpDailyTask);
	}

	@Override
	protected LaunchPoint getRequiredStartLocation() {
		return LaunchPoint.WORLD;
	}

	@Override
	protected void execute() {
		logInfo(routineLogExplorationLine("Opening Exploration screen from world view."));
		boolean opened = openExplorationScreen();
		boolean claimedAnyReward = opened && claimAvailableRewards();
		if (claimedAnyReward) {
			logInfo(routineLogExplorationLine("Exploration rewards claimed."));
		} else if (!opened) {
			logWarning(routineLogExplorationLine("Exploration screen could not be confirmed."));
		} else {
			logInfo(routineLogExplorationLine("No exploration rewards to claim."));
		}

		rescheduleFromConfiguredOffset();
		pressBack();
		sleepTask(500);
	}

	private String routineLogExplorationLine(String note) {
		return "ExplorationRoutine | " + note;
	}

	private boolean openExplorationScreen() {
		for (int attempt = 1; attempt <= MAX_OPEN_ATTEMPTS; attempt++) {
			tapInside(EXPLORATION_ENTRY_TOP_LEFT, EXPLORATION_ENTRY_BOTTOM_RIGHT);
			sleepTask(1000);

			if (isExplorationScreenVisible()) {
				logDebug(routineLogExplorationLine("Exploration screen confirmed on attempt " + attempt + "."));
				return true;
			}

			logDebug(routineLogExplorationLine("Exploration screen not confirmed on attempt " + attempt + "."));
		}
		return false;
	}

	private boolean isExplorationScreenVisible() {
		if (locateEnabledClaimButton().isFound()) {
			return true;
		}

		if (locateDisabledClaimButton().isFound()) {
			return true;
		}

		ImageSearchResultData explorationButton = templateSearchHelper.locatePattern(
				TemplatesEnum.EXPLORATION_BUTTON, SearchConfigConstants.SINGLE_WITH_2_RETRIES);
		return explorationButton.isFound();
	}

	private boolean claimAvailableRewards() {
		for (int attempt = 1; attempt <= MAX_CLAIM_TAP_ATTEMPTS; attempt++) {
			ImageSearchResultData claimResult = locateEnabledClaimButton();

			if (!claimResult.isFound()) {
				logDebug(routineLogExplorationLine("Enabled claim button not detected on attempt " + attempt + "."));
				return attempt > 1;
			}

			logInfo(routineLogExplorationLine(String.format(
					"Enabled claim button detected at %s with score %.2f. Pressing attempt %d/%d.",
					claimResult.getPoint(), claimResult.getMatchPercentage(), attempt, MAX_CLAIM_TAP_ATTEMPTS)));
			tapClaimButton(claimResult);
			sleepTask(800);
			dismissRewardPopup();

			ImageSearchResultData disabledClaim = locateDisabledClaimButton();
			if (disabledClaim.isFound()) {
				logInfo(routineLogExplorationLine(String.format(
						"Disabled claim button detected at %s with score %.2f; reward claim succeeded.",
						disabledClaim.getPoint(), disabledClaim.getMatchPercentage())));
				return true;
			}

			ImageSearchResultData remainingEnabledClaim = locateEnabledClaimButton();
			if (!remainingEnabledClaim.isFound()) {
				logInfo(routineLogExplorationLine(String.format(
						"Claim button no longer visible after attempt %d; reward claim succeeded.",
						attempt)));
				return true;
			}

			logWarning(routineLogExplorationLine(String.format(
					"Enabled claim button still detected after attempt %d; retrying claim tap.",
					attempt)));
		}

		logWarning(routineLogExplorationLine(
				"Enabled claim button remained after all claim attempts. Rescheduling normally."));
		return false;
	}

	private ImageSearchResultData locateEnabledClaimButton() {
		return templateSearchHelper.locatePattern(
				TemplatesEnum.EXPLORATION_CLAIM, SearchConfigConstants.STRICT_MATCHING);
	}

	private ImageSearchResultData locateDisabledClaimButton() {
		return templateSearchHelper.locatePattern(
				TemplatesEnum.EXPLORATION_CLAIM_DISABLED, SearchConfigConstants.SINGLE_WITH_2_RETRIES);
	}

	private void tapClaimButton(ImageSearchResultData claimResult) {
		SizeData templateSize = claimResult.getTemplateSize();
		if (templateSize == null || templateSize.getWidth() <= 0 || templateSize.getHeight() <= 0) {
			tapInside(claimResult);
			return;
		}

		PointData center = claimResult.getPoint();
		PointData topLeft = new PointData(
				center.getX() - templateSize.getWidth() / 2,
				center.getY() - templateSize.getHeight() / 2);
		PointData bottomRight = new PointData(
				center.getX() + templateSize.getWidth() / 2,
				center.getY() + templateSize.getHeight() / 2);
		tapInside(topLeft, bottomRight);
	}

	private void dismissRewardPopup() {
		tapInside(REWARD_POPUP_TOP_LEFT, REWARD_POPUP_BOTTOM_RIGHT);
		sleepTask(500);
	}

	private void rescheduleFromConfiguredOffset() {
		Integer minutes = profile.getConfig(ConfigurationKeyEnum.INT_EXPLORATION_CHEST_OFFSET, Integer.class);
		LocalDateTime nextSchedule = LocalDateTime.now().plusMinutes(minutes);
		reschedule(nextSchedule);
		logInfo(routineLogExplorationLine("Next execution scheduled at: "
				+ nextSchedule.format(DATETIME_FORMATTER) + " (" + minutes + " minutes)."));
	}

}
