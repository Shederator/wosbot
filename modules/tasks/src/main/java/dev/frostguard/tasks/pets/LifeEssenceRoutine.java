package dev.frostguard.tasks.pets;

import java.awt.image.BufferedImage;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;

public class LifeEssenceRoutine extends DelayedTask {

	// ===================== CONSTANTS =====================
	// Navigation coordinates
	private static final PointData SHOP_TAB_BUTTON = new PointData(670, 195);
	private static final PointData EXIT_BUTTON = new PointData(40, 30);

	// Default configuration values
	private static final int DEFAULT_OFFSET_MINUTES = Integer.parseInt(
			ConfigurationKeyEnum.LIFE_ESSENCE_OFFSET_INT.getDefaultValue());
	// Configuration (loaded fresh each execution)
	private int offsetMinutes;
	private boolean buyWeeklyScroll;

	// Persisted retry state loaded at the start of each execution
	private int consecutiveFailures = 0;

	public LifeEssenceRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
		super(profile, tpDailyTask);
	}

	@Override
	protected void execute() {

		// Load configuration
		loadConfiguration();

		loadFailureState();

		// Navigate to Life Essence menu
		if (!navigateToLifeEssenceMenu()) {
			handleNavigationFailure();
			return;
		}

		// Claim available Life Essence
		ClaimRun claim = claimLifeEssence();
		if (!claim.cleanFinish()) {
			handleClaimFailure(claim.confirmedClaims());
			return;
		}

		// Buy weekly free scroll if enabled and available
		if (buyWeeklyScroll && shouldBuyWeeklyScroll()) {
			buyWeeklyFreeScroll();
		}

		likeIsland();

		// Exit and reschedule
		exitAndReschedule(claim.confirmedClaims());
	}

	private void likeIsland() {

		ImageSearchResultData likeButton = templateSearchHelper.locatePattern(
				TemplatesEnum.ISLAND_LIKE_BUTTON,
				SearchConfig.builder()
						.withArea(new AreaData(new PointData(634, 718), new PointData(700, 774)))
						.withThreshold(95)
						.withMaxAttempts(3)
						.withDelay(100)
						.build());
		if (likeButton.isFound()) {
			logInfo("Liking the island");
			tapInside(likeButton);
			sleepTask(500); // Wait for like action
		}
	}

	/**
	 * Load configuration from profile after refresh
	 */
	private void loadConfiguration() {
		Integer configOffset = profile.getConfig(
				ConfigurationKeyEnum.LIFE_ESSENCE_OFFSET_INT,
				Integer.class);

		this.offsetMinutes = (configOffset != null && configOffset > 0)
				? configOffset
				: DEFAULT_OFFSET_MINUTES;

		this.buyWeeklyScroll = profile.getConfig(
				ConfigurationKeyEnum.LIFE_ESSENCE_BUY_WEEKLY_SCROLL_BOOL,
				Boolean.class);

		logDebug("Configuration loaded: offsetMinutes=" + offsetMinutes +
				", buyWeeklyScroll=" + buyWeeklyScroll);
	}

	private void loadFailureState() {
		Integer failures = profile.getConfig(
				ConfigurationKeyEnum.LIFE_ESSENCE_CONSECUTIVE_FAILURES_INT,
				Integer.class);
		consecutiveFailures = failures == null ? 0 : Math.max(0, failures);
	}

	/**
	 * Navigate to the Life Essence menu
	 * 
	 * Navigation flow:
	 * Uses the shared sidebar navigator to select Daily, locate the Life Essence row,
	 * and tap the Go control associated with that detected row.
	 * 
	 * @return true if navigation successful, false otherwise
	 */
	private boolean navigateToLifeEssenceMenu() {
		logInfo("Navigating to Life Essence menu");
		return navigationHelper.navigateToSidebarDestination(SidebarDestination.LIFE_ESSENCE);
	}

	/**
	 * Claim visible Life Essence markers.
	 *
	 * A confirmed claim is a marker that disappears on the next capture.
	 * Two valid empty captures finish the run, including an island that was
	 * already collected. A capture failure stops the run so it can be retried.
	 */
	private ClaimRun claimLifeEssence() {
		logInfo("Searching for claimable Life Essence");
		LifeEssenceClaimPolicy.State state = LifeEssenceClaimPolicy.State.initial();
		while (true) {
			LifeEssenceClaimPolicy.Step step = LifeEssenceClaimPolicy.advance(state, observeClaimMarkers());
			state = step.next();
			logDebug("Claim capture " + state.captures() + "/" + LifeEssenceClaimPolicy.MAX_CAPTURES
					+ " outcome=" + step.outcome()
					+ " confirmed=" + step.confirmedClaims()
					+ " reason=" + step.reason());
			switch (step.outcome()) {
				case TAP -> {
					logInfo("Tapping Life Essence marker at " + step.tap());
					tapNear(step.tap());
					sleepTask(500);
				}
				case WAIT -> sleepTask(500);
				case FINISH -> {
					logInfo("Claimed " + step.confirmedClaims() + " Life Essence items");
					return new ClaimRun(true, step.confirmedClaims());
				}
				case RETRY -> {
					logWarning("Life Essence claim stopped: " + step.reason()
							+ ". Confirmed claims: " + step.confirmedClaims());
					return new ClaimRun(false, step.confirmedClaims());
				}
			}
		}
	}

	private LifeEssenceClaimPolicy.Observation observeClaimMarkers() {
		try {
			RawImageData frame = emuManager.captureScreen(EMULATOR_NUMBER);
			BufferedImage image = ImageConverter.toBufferedImage(frame);
			return LifeEssenceClaimPolicy.Observation.markers(
					LifeEssenceSearchKind.COLOR.open(null).find(image));
		} catch (Exception ex) {
			logWarning("Life Essence capture failed: " + ex.getClass().getSimpleName()
					+ ": " + ex.getMessage());
			return LifeEssenceClaimPolicy.Observation.failed();
		}
	}

	/**
	 * Check if weekly free scroll should be purchased
	 * 
	 * Checks profile config for next allowed purchase time.
	 * If not set or time has passed, returns true.
	 */
	private boolean shouldBuyWeeklyScroll() {
		String nextScrollTimeStr = profile.getConfig(
				ConfigurationKeyEnum.LIFE_ESSENCE_NEXT_SCROLL_TIME_STRING,
				String.class);

		if (nextScrollTimeStr == null || nextScrollTimeStr.isEmpty()) {
			logDebug("No scroll cooldown set. Attempting to buy.");
			return true;
		}

		try {
			LocalDateTime nextScrollTime = LocalDateTime.parse(nextScrollTimeStr);

			if (LocalDateTime.now().isAfter(nextScrollTime)) {
				logDebug("Scroll cooldown expired. Attempting to buy.");
				return true;
			}

			logInfo("Weekly scroll not yet available. Next purchase at: " +
					nextScrollTime);
			return false;

		} catch (Exception e) {
			logWarning("Failed to parse next scroll time: " + e.getMessage());
			return true; // Try anyway if parse fails
		}
	}

	/**
	 * Attempt to purchase the weekly free scroll
	 * 
	 * Process:
	 * 1. Navigate to shop tab
	 * 2. Search for weekly free scroll offer
	 * 3. Click scroll to open purchase dialog
	 * 4. Click buy button to confirm
	 * 5. Update next available time to next Monday 00:00 UTC
	 */
	private void buyWeeklyFreeScroll() {
		logInfo("Attempting to buy weekly free scroll");

		// Navigate to shop tab
		logDebug("Opening shop tab");
		tapNear(SHOP_TAB_BUTTON);
		sleepTask(1000); // Wait for tab transition

		// Search for weekly free scroll offer
		ImageSearchResultData scrollOffer = templateSearchHelper.locatePattern(
				TemplatesEnum.ISLAND_WEEKLY_FREE_SCROLL,
				SearchConfig.builder().build());

		if (!scrollOffer.isFound()) {
			logInfo("Weekly free scroll not available (already purchased this week)");

			// Set next available time even though we didn't buy
			// This prevents repeatedly checking for an already-purchased scroll
			LocalDateTime nextScrollTime = calculateNextMondayReset();
			writeProfileSetting(ConfigurationKeyEnum.LIFE_ESSENCE_NEXT_SCROLL_TIME_STRING,
					nextScrollTime.toString());
			logInfo("Next scroll purchase check scheduled for: " + nextScrollTime);

			tapNear(EXIT_BUTTON);
			return;
		}

		// Click scroll to open purchase dialog
		logInfo("Weekly free scroll found. Opening purchase dialog.");
		tapInside(scrollOffer);
		sleepTask(500); // Wait for dialog

		// Search for buy button
		ImageSearchResultData buyButton = templateSearchHelper.locatePattern(
				TemplatesEnum.ISLAND_WEEKLY_FREE_SCROLL_BUY_BUTTON,
				SearchConfig.builder().build());

		if (!buyButton.isFound()) {
			logWarning("Buy button not found. Purchase may have failed.");
			pressBack(); // Close dialog
			sleepTask(500);
			tapNear(EXIT_BUTTON); // Exit shop
			sleepTask(500);
			return;
		}

		// Confirm purchase
		tapInside(buyButton);
		sleepTask(500); // Wait for purchase to complete

		logInfo("Weekly free scroll purchased successfully");

		LocalDateTime nextScrollTime = calculateNextMondayReset();
		writeProfileSetting(ConfigurationKeyEnum.LIFE_ESSENCE_NEXT_SCROLL_TIME_STRING,
				nextScrollTime.toString());
		logInfo("Next scroll purchase available at: " + nextScrollTime);

		// Exit shop
		tapNear(EXIT_BUTTON);
		sleepTask(500);
	}

	/**
	 * Handle navigation failure by incrementing failure count and rescheduling
	 */
	private void handleNavigationFailure() {
		scheduleRetry("Navigation failed");
	}

	private void handleClaimFailure(int confirmedClaims) {
		logDebug("Exiting Life Essence interface after an unfinished claim");
		tapNear(EXIT_BUTTON);
		sleepTask(1000);
		scheduleRetry("Life Essence claim unfinished after " + confirmedClaims + " confirmed claims");
	}

	private void scheduleRetry(String reason) {
		LifeEssenceRetryPolicy.Decision decision =
				LifeEssenceRetryPolicy.afterFailure(consecutiveFailures);
		consecutiveFailures = decision.persistedFailures();

		writeProfileSetting(ConfigurationKeyEnum.LIFE_ESSENCE_CONSECUTIVE_FAILURES_INT, consecutiveFailures);

		Duration retryDelay = decision.retryDelay();
		LocalDateTime nextAttempt = LocalDateTime.now().plus(retryDelay);

		reschedule(nextAttempt);

		logWarning(reason + ". Consecutive failures: " + consecutiveFailures
				+ ". Task remains enabled and will retry in " + retryDelay.toMinutes()
				+ " minutes at " + GameTimeUtils.formatCountdown(nextAttempt));
	}

	/**
	 * Calculates and returns next Monday game reset time
	 */
	private LocalDateTime calculateNextMondayReset() {
		ZonedDateTime nowUtc = ZonedDateTime.now(ZoneOffset.UTC);

		// Get next Monday at 00:00 UTC (or current Monday if before reset)
		ZonedDateTime nextMonday = nowUtc
				.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
				.truncatedTo(ChronoUnit.DAYS);

		// If we're past the reset time on Monday, move to next week
		if (!nextMonday.isAfter(nowUtc)) {
			nextMonday = nextMonday.plusWeeks(1);
		}

		return nextMonday.toLocalDateTime();
	}

	/**
	 * Exit Life Essence interface and reschedule task
	 * 
	 * @param claimedCount number of essence items claimed
	 */
	private void exitAndReschedule(int claimedCount) {
		// Exit Life Essence interface
		logDebug("Exiting Life Essence interface");
		tapNear(EXIT_BUTTON);
		sleepTask(1000); // Wait for menu close

		// Reset failure count on successful execution
		if (consecutiveFailures > 0) {
			consecutiveFailures = 0;
			writeProfileSetting(ConfigurationKeyEnum.LIFE_ESSENCE_CONSECUTIVE_FAILURES_INT, 0);
			logInfo("Consecutive failure count reset after successful execution");
		}

		// Calculate next schedule time
		int scheduleOffset = offsetMinutes;

		LocalDateTime nextSchedule = LocalDateTime.now().plusMinutes(scheduleOffset);
		reschedule(nextSchedule);

		logInfo("Life Essence task completed. Claimed: " + claimedCount +
				". Next run in: " + GameTimeUtils.formatCountdown(nextSchedule));
	}

	@Override
	protected LaunchPoint getRequiredStartLocation() {
		return LaunchPoint.ANY;
	}

	private record ClaimRun(boolean cleanFinish, int confirmedClaims) {
	}

}
