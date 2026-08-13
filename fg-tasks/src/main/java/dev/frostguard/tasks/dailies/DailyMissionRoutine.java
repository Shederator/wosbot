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

	/**
	 * How many times a claim may reappear at the same coordinates before it is judged stuck.
	 * Set well above a normal re-flow run, which matt reports is only a few in a row.
	 */
	private static final int MAX_SAME_POSITION_CLAIMS = 8;

	/**
	 * The Growth tab, bottom-left of the Missions dialog, beside the Daily tab.
	 *
	 * <p>matt, 2026-08-08: Growth missions were never claimed because this routine only ever
	 * knew about the Daily tab — there was no reference to Growth anywhere in it. The two tabs
	 * sit side by side at the bottom of the same dialog, and Growth carries its own red badge
	 * when something is claimable. Derived from a 760x1339 capture (Growth centred at x=256,
	 * Daily at x=503) scaled to the emulator's 720x1280; that scaling puts Daily at x=476,
	 * which matches the coordinate this routine already resolves for it, so the mapping checks
	 * out on a known-good point.</p>
	 */
	private static final PointData GROWTH_TAB_VALUE = new PointData(242, 1135);

	/** Time for the Growth list to animate in before its claim buttons match reliably. */
	private static final int GROWTH_TAB_SETTLE_MS = 1800;

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
		int dailyMissionsClaimed = dailyScreenReached ? redeemAllRewards() : 0;
		if (dailyMissionsClaimed > 0) {
			StatisticsService.obtain().addToCounter(profile, "Daily Missions Claimed", dailyMissionsClaimed);
		}

		// matt, 2026-08-08: Growth missions live behind the second tab of this same dialog and
		// were being left on the table entirely. Claimed after Daily so the existing flow is
		// untouched, and independently of claimFlowCompleted — a Daily pass that found nothing
		// says nothing about whether Growth has rewards waiting.
		redeemGrowthMissionRewardsFlow();

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
		tapPoint(dailyTabButton.getPoint());
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

	/**
	 * Finds the topmost enabled Claim button on screen.
	 *
	 * <p>matt, 2026-08-08: <em>"click the top claim button until no claim button exists"</em>. The
	 * previous behaviour took whatever single match the template search happened to return, which
	 * is not necessarily the highest one — and since claiming a row makes the list re-flow upward,
	 * working from the top down is the only order that stays predictable. Picking the smallest y
	 * also removes any reliance on match ordering.</p>
	 */
	private ImageSearchResultData seekForIndividualClaimButton() {
		java.util.List<ImageSearchResultData> matches = templateSearchHelper.locateAllPatterns(
				TemplatesEnum.DAILY_MISSION_CLAIM_BUTTON,
				SearchConfigConstants.DEFAULT_SINGLE);

		ImageSearchResultData claimButton = (matches == null ? java.util.List.<ImageSearchResultData>of() : matches)
				.stream()
				.filter(java.util.Objects::nonNull)
				.filter(ImageSearchResultData::isFound)
				.min(java.util.Comparator.comparingInt(r -> r.getPoint().getY()))
				.orElse(ImageSearchResultData.miss());

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

	/**
	 * Switches to the Growth tab and claims anything waiting there.
	 *
	 * <p>Growth has no "Claim All" control — only per-row green Claim buttons — so this reuses
	 * the individual claim loop, which re-scans after every tap and therefore copes with the
	 * list re-flowing under it.</p>
	 */
	private void redeemGrowthMissionRewardsFlow() {
		logInfo(routineLogDailyMissionLine("Switching to Growth missions tab at " + GROWTH_TAB_VALUE));
		tapPoint(GROWTH_TAB_VALUE);

		// matt, 2026-08-08: the Growth list animates in, and 800ms was not enough for it to
		// settle. Observed live: a pre-check found a claim button and the very next search,
		// 180ms later, did not — so the routine announced "Claimable Growth mission(s) detected"
		// and then immediately claimed nothing. The pre-check is gone entirely rather than
		// retried: searching twice for the same button is what created the race, and
		// redeemRewardsIndividually already reports "Claimed 0 rewards" when there is nothing,
		// so nothing is lost by letting it do the only search.
		sleepTask(GROWTH_TAB_SETTLE_MS);

		int growthMissionsClaimed = redeemRewardsIndividually();

		if (growthMissionsClaimed > 0) {
			StatisticsService.obtain().addToCounter(profile, "Growth Missions Claimed", growthMissionsClaimed);
		} else {
			logInfo(routineLogDailyMissionLine("No Growth missions to claim this pass."));
		}
	}

private int redeemRewardsIndividually() {
		logWarning(routineLogDailyMissionLine("'Claim All' button not detected. Collecting missions individually"));

		int claimedCount = 0;
		int consecutiveSamePositionClaims = 0;

		while (claimedCount < MAX_INDIVIDUAL_CLAIMS) {
			ImageSearchResultData claimResult = seekForIndividualClaimButton();
			if (!claimResult.isFound()) {
				logInfo(routineLogDailyMissionLine("Individual collecting complete. Claimed " + claimedCount + " rewards"));
				return claimedCount;
			}

			claimedCount++;
			PointData claimPoint = claimResult.getPoint();
			logDebug(routineLogDailyMissionLine("Collecting individual reward #" + claimedCount + " at " + claimPoint));

			tapPoint(claimPoint);
			dismissRewardPopupsFlow();
			sleepTask(500);

			// matt, 2026-08-08: a Claim button reappearing in the SAME spot is the normal case,
			// not a stuck UI. The mission list re-flows upward as rows are claimed, so the next
			// claimable row slides into the position just vacated — matt confirmed this happens
			// several times in a row. The old code treated the second same-position hit as "no
			// visual progress" and aborted the entire pass, abandoning every remaining reward.
			// Tolerate a run of them, with MAX_INDIVIDUAL_CLAIMS still bounding the loop, and
			// only bail once the same spot repeats far more often than a re-flow could explain.
			ImageSearchResultData nextClaim = seekForIndividualClaimButton();
			if (nextClaim.isFound() && sameClaimTarget(claimPoint, nextClaim.getPoint())) {
				consecutiveSamePositionClaims++;
				if (consecutiveSamePositionClaims >= MAX_SAME_POSITION_CLAIMS) {
					logWarning(routineLogDailyMissionLine("Claim button stayed at " + claimPoint
							+ " for " + consecutiveSamePositionClaims
							+ " consecutive taps. Treating it as stuck and stopping"));
					return claimedCount;
				}
				logDebug(routineLogDailyMissionLine("Another claim appeared at " + claimPoint
						+ " (run of " + consecutiveSamePositionClaims + ") — list re-flowed, continuing"));
			} else {
				consecutiveSamePositionClaims = 0;
			}
		}

		logWarning(routineLogDailyMissionLine("Stopped individual claims at safety limit "
				+ MAX_INDIVIDUAL_CLAIMS));
		return claimedCount;
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
		tapRandomPoint(
				POPUP_DISMISS_MIN_VALUE,
				POPUP_DISMISS_MAX_VALUE,
				POPUP_DISMISS_TAP_COUNT_VALUE,
				150

		);
	}

private void redeemAllRewardsAtOnce(ImageSearchResultData claimAllResult) {
		logInfo(routineLogDailyMissionLine("'Claim All' button detected. Collecting all rewards at once"));

		tapPoint(claimAllResult.getPoint());
		dismissRewardPopupsFlow();
	}

private ImageSearchResultData seekForClaimAllButton() {
		return templateSearchHelper.locatePattern(
				TemplatesEnum.DAILY_MISSION_CLAIMALL_BUTTON,
				SearchConfigConstants.DEFAULT_SINGLE);
	}

private void reachDailyMissions() {
		logInfo(routineLogDailyMissionLine("Moving to daily missions interface"));

		tapPoint(DAILY_MISSIONS_BUTTON_VALUE);
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

private int redeemAllRewards() {
		logInfo(routineLogDailyMissionLine("Scanning for claim buttons"));

		ImageSearchResultData claimAllResult = seekForClaimAllButton();

		if (claimAllResult.isFound()) {
			// The 'Claim All' button is only present when at least one reward is claimable — the
			// game hides it once everything is claimed — so its presence proves ≥1 real claim.
			redeemAllRewardsAtOnce(claimAllResult);
			return 1;
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
