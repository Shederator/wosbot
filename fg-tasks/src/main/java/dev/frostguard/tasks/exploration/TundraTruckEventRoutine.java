package dev.frostguard.tasks.exploration;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;

public class TundraTruckEventRoutine extends DelayedTask {

	// ===================== CONSTANTS =====================
	// UI Areas
	private static final AreaData MY_TRUCKS_TAB = new AreaData(
			new PointData(120, 250),
			new PointData(280, 270));
	private static final AreaData REMAINING_TRUCKS_OCR = new AreaData(
			new PointData(477, 1151),
			new PointData(527, 1179));
	private static final AreaData COUNTDOWN_OCR = new AreaData(
			new PointData(194, 943),
			new PointData(345, 976));
	private static final AreaData CLOSE_WINDOW = new AreaData(
			new PointData(300, 1150),
			new PointData(450, 1200));
	private static final AreaData REFRESH_BUTTON = new AreaData(
			new PointData(588, 405),
			new PointData(622, 436));
	private static final AreaData CONFIRM_CHECKBOX = new AreaData(
			new PointData(445, 667),
			new PointData(586, 806));
	private static final AreaData CANCEL_POPUP = new AreaData(
			new PointData(626, 438),
			new PointData(643, 454));
	private static final AreaData CLOSE_DETAIL = new AreaData(
			new PointData(617, 770),
			new PointData(650, 795));

	// Truck positions
	private static final AreaData LEFT_TRUCK = new AreaData(
			new PointData(205, 643),
			new PointData(265, 790));
	private static final AreaData RIGHT_TRUCK = new AreaData(
			new PointData(450, 643),
			new PointData(515, 790));

	private static final AreaData LEFT_TRUCK_TIME = new AreaData(
			new PointData(185, 852),
			new PointData(287, 875));
	private static final AreaData RIGHT_TRUCK_TIME = new AreaData(
			new PointData(432, 852),
			new PointData(535, 875));

	private static final AreaData TIPS_POPUP_CHECKBOX = new AreaData(
			new PointData(198, 699),
			new PointData(225, 726));

	// When a truck is already departed and selected, the details screen is different and the truck position is shifted up by 143 pixels.
	private static final int TRUCK_ALT_POSITION_Y_OFFSET = -143;

	// Retry limits
	private static final int MAX_NAVIGATION_ATTEMPTS = 2;
	private static final int MAX_REFRESH_ATTEMPTS = 10;

	// Configuration (loaded fresh each execution)
	private boolean useGems;
	private boolean truckSSR;
	private String activationTime; // Format: "HH:mm"
	private boolean useActivationTime;

	public TundraTruckEventRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
		super(profile, tpDailyTask);
	}

	@Override
	protected void execute() {

		// Load configuration
		loadConfiguration();

		// Schedule based on activation time if configured
		if (useActivationTime) {
			validateActivationTime();
			if (scheduledToActivationTime())
				return;
		}

		// Attempt navigation
		for (int attempt = 0; attempt < MAX_NAVIGATION_ATTEMPTS; attempt++) {
			TundraNavigationResult result = navigateToTundraEvent();

			switch (result) {
				case SUCCESS:
					logInfo("Successfully navigated to Tundra Truck event");
					handleTundraEvent();
					return;

				case COUNTDOWN:
					logInfo("Event in countdown. Waiting for next activation time.");
					return;

				case ENDED:
					logInfo("Event has ended. Task disabled.");
					return;

				case FAILURE:
					logError("Navigation failed (attempt " + (attempt + 1) + "/" + MAX_NAVIGATION_ATTEMPTS + ")");
					if (attempt < MAX_NAVIGATION_ATTEMPTS - 1) {
						sleepTask(300);
						pressBack();
					}
					break;
			}
		}

		// All navigation attempts failed
		logWarning("Could not find Tundra Truck event after " + MAX_NAVIGATION_ATTEMPTS +
				" attempts.");
		rescheduleWithActivationTime();
	}

	/**
	 * Load configuration from profile after refresh
	 */
	private void loadConfiguration() {
		this.useGems = profile.getConfig(ConfigurationKeyEnum.TUNDRA_TRUCK_USE_GEMS_BOOL, Boolean.class);
		this.truckSSR = profile.getConfig(ConfigurationKeyEnum.TUNDRA_TRUCK_SSR_BOOL, Boolean.class);
		this.activationTime = profile.getConfig(ConfigurationKeyEnum.TUNDRA_TRUCK_ACTIVATION_TIME_STRING, String.class);
		this.useActivationTime = profile.getConfig(ConfigurationKeyEnum.TUNDRA_TRUCK_ACTIVATION_TIME_BOOL,
				Boolean.class);

		logDebug("Configuration loaded: " + "useGems=" + useGems +
				", truckSSR=" + truckSSR + ", useActivationTime=" + useActivationTime +
				", activationTime=" + activationTime);
	}

	/**
	 * Check if activation time is in valid HH:mm format
	 */
	private void validateActivationTime() {
		if (activationTime == null || activationTime.trim().isEmpty()) {
			logWarning("Invalid activation time format: '" + activationTime
					+ "'. Expected HH:mm (e.g., '14:30'). Changing to default time: 14:00");
			activationTime = "14:00";
		}

		try {
			LocalTime.parse(activationTime.trim(), TIME_FORMATTER);
		} catch (DateTimeParseException e) {
			logWarning("Invalid activation time format: '" + activationTime
					+ "'. Expected HH:mm (e.g., '14:30'). Changing to default time: 14:00");
			activationTime = "14:00";
		}
	}

	/**
	 * Schedule task based on configured activation time in UTC.
	 * If activation time has already passed today, schedule immediately instead of
	 * tomorrow.
	 * 
	 * @return true if scheduled to activation time, false if running now
	 */
	private boolean scheduledToActivationTime() {
		try {
			LocalTime targetTime = LocalTime.parse(activationTime.trim(), TIME_FORMATTER);
			ZonedDateTime nowUtc = ZonedDateTime.now(ZoneId.of("UTC"));

			// Create UTC time for today at the activation time
			ZonedDateTime activationTimeUtc = nowUtc.toLocalDate()
					.atTime(targetTime)
					.atZone(ZoneId.of("UTC"));

			// Convert to local time for scheduling
			ZonedDateTime localActivationTime = activationTimeUtc.withZoneSameInstant(ZoneId.systemDefault());

			// If activation time has already passed today, run immediately
			if (nowUtc.isAfter(activationTimeUtc)) {
				logInfo("Activation time " + activationTime + " UTC has already passed today. Running immediately.");
				return false;
			} else {
				logInfo("Scheduling Tundra Truck task for " + activationTime + " UTC (" +
						localActivationTime.format(DATETIME_FORMATTER) + " local time)");
				reschedule(localActivationTime.toLocalDateTime());
				return true;
			}
		} catch (DateTimeParseException e) {
			logError("Failed to parse activation time '" + activationTime + "': " + e.getMessage());
			// Fallback to game reset
			reschedule(GameTimeUtils.dailyResetTime());
		}
		return true;
	}

	/**
	 * Reschedule with activation time or game reset
	 */
	private void rescheduleWithActivationTime() {
		if (useActivationTime) {
			validateActivationTime();
			try {
				LocalTime targetTime = LocalTime.parse(activationTime.trim(), TIME_FORMATTER);
				ZonedDateTime nowUtc = ZonedDateTime.now(ZoneId.of("UTC"));

				// Schedule for tomorrow at activation time
				ZonedDateTime tomorrowActivationUtc = nowUtc.toLocalDate().plusDays(1)
						.atTime(targetTime)
						.atZone(ZoneId.of("UTC"));

				ZonedDateTime localActivationTime = tomorrowActivationUtc.withZoneSameInstant(ZoneId.systemDefault());

				logInfo("Rescheduling for next activation at " + activationTime + " UTC tomorrow (" +
						localActivationTime.format(DATETIME_FORMATTER) + " local time)");

				reschedule(localActivationTime.toLocalDateTime());
			} catch (DateTimeParseException e) {
				logError("Failed to parse activation time: " + e.getMessage());
				reschedule(GameTimeUtils.dailyResetTime());
			}
		} else {
			logInfo("Rescheduling for game reset time");
			reschedule(GameTimeUtils.dailyResetTime());
		}
	}

	/**
	 * Navigate to Tundra Truck event section
	 * 
	 * <p>
	 * Uses the generic NavigationHelper.navigateToEventMenu() method.
	 */
	private TundraNavigationResult navigateToTundraEvent() {
		logInfo("Navigating to Tundra Truck event");

		boolean success = navigationHelper.navigateToEventMenu(
				dev.frostguard.engine.helper.NavigationHelper.EventMenu.TUNDRA_TRUCK);

		if (!success) {
			logWarning("Failed to navigate to Tundra Truck event");
			return TundraNavigationResult.FAILURE;
		}

		sleepTask(2000);

		// Check if in countdown
		String countdownText = stringHelper.attemptRecognition(
				COUNTDOWN_OCR.topLeft(),
				COUNTDOWN_OCR.bottomRight(),
				1,
				300L,
				null,
				s -> !s.isEmpty(),
				s -> s);
		if (countdownText != null && countdownText.toLowerCase().contains("countdown")) {
			rescheduleWithActivationTime();
			return TundraNavigationResult.COUNTDOWN;
		}

		// Check if ended
		if (isEventEnded()) {
			return TundraNavigationResult.ENDED;
		}

		return TundraNavigationResult.SUCCESS;
	}

	/**
	 * Check if event has ended
	 */
	private boolean isEventEnded() {
		ImageSearchResultData endedResult = templateSearchHelper.locatePattern(
				TemplatesEnum.TUNDRA_TRUCK_ENDED,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (endedResult.isFound()) {
			logInfo("Tundra Truck event has ended. Rescheduling for next reset.");
			reschedule(GameTimeUtils.dailyResetTime());
			return true;
		}

		return false;
	}

	/**
	 * Main event handling logic
	 */
	private void handleTundraEvent() {
		clickMyTrucksTab();
		collectArrivedTrucks();

		if (!checkAvailableTrucks()) {
			return; // No trucks remaining, already rescheduled
		}

		attemptSendTrucks();
	}

	/**
	 * Click the "My Trucks" tab
	 */
	private void clickMyTrucksTab() {
		tapInside(MY_TRUCKS_TAB.topLeft(), MY_TRUCKS_TAB.bottomRight());
		sleepTask(1000);
	}

	/**
	 * Collect any arrived trucks
	 */
	private void collectArrivedTrucks() {
		List<ImageSearchResultData> arrivedsTruck = templateSearchHelper.locateAllPatterns(
				TemplatesEnum.TUNDRA_TRUCK_ARRIVED,
				SearchConfig.builder()
						.withThreshold(80)
						.withMaxAttempts(2)
						.withMaxResults(3)
						.build());

		logInfo("Searching for arrived trucks");

		if (arrivedsTruck.isEmpty())
			logInfo("No arrived trucks found.");
		else
			logInfo(arrivedsTruck.size() + " arrived trucks found. Collecting rewards now.");

		for (ImageSearchResultData result : arrivedsTruck) {
			if (result.isFound()) {
				tapInside(result);
				sleepTask(500);
				closeWindow();
			}
		}

		sleepTask(1000);
	}

	/**
	 * Check if trucks are available to send
	 * Also checks if trucks are in transit before rescheduling to reset
	 */
	private boolean checkAvailableTrucks() {
		try {
			String text = stringHelper.attemptRecognition(
					REMAINING_TRUCKS_OCR.topLeft(),
					REMAINING_TRUCKS_OCR.bottomRight(),
					1,
					300L,
					null,
					s -> !s.isEmpty(),
					s -> s);
			logInfo("Remaining trucks OCR: '" + text + "'");

			if (text != null && text.trim().matches("0\\s*/\\s*\\d+")) {
				logInfo("No trucks available to send (0/4)");

				// Check if any trucks are still in transit
				if (hasInTransitTrucks()) {
					logInfo("Trucks are in transit. Scheduling next check for truck return time.");
					scheduleNextTruckCheck();
					return false;
				}

				logInfo("No trucks available and none in transit. Rescheduling for next activation time.");
				rescheduleWithActivationTime();
				return false;
			}

			return true;
		} catch (Exception e) {
			logError("Error checking available trucks: " + e.getMessage(), e);
			return true; // Proceed anyway
		}
	}

	/**
	 * Check if any trucks are currently in transit (have return times)
	 */
	private boolean hasInTransitTrucks() {
		logDebug("Checking if any trucks are in transit...");

		Optional<LocalDateTime> leftTime = extractTruckTime(TruckSide.LEFT);
		Optional<LocalDateTime> rightTime = extractTruckTime(TruckSide.RIGHT);

		boolean leftInTransit = leftTime.isPresent() && leftTime.get().isAfter(LocalDateTime.now());
		boolean rightInTransit = rightTime.isPresent() && rightTime.get().isAfter(LocalDateTime.now());

		if (leftInTransit) {
			logInfo("Left truck is in transit, returns at: " + leftTime.get());
		}
		if (rightInTransit) {
			logInfo("Right truck is in transit, returns at: " + rightTime.get());
		}

		return leftInTransit || rightInTransit;
	}

	/**
	 * Attempt to send both trucks
	 */
	private void attemptSendTrucks() {
		int rightTruckYOffset = 0;
		TruckStatus leftStatus = checkTruckStatus(TruckSide.LEFT);
		if(leftStatus == TruckStatus.DEPARTED)
		{
			rightTruckYOffset = TRUCK_ALT_POSITION_Y_OFFSET;
		}
		TruckStatus rightStatus = checkTruckStatus(TruckSide.RIGHT, 0, rightTruckYOffset);


		// If both already departed, just schedule next check
		if (leftStatus == TruckStatus.DEPARTED && rightStatus == TruckStatus.DEPARTED) {
			logInfo("Both trucks already departed. Scheduling next check.");
			scheduleNextTruckCheck();
			return;
		}

		boolean leftSent = false;
		boolean rightSent = false;

		if (leftStatus == TruckStatus.AVAILABLE) {
			logInfo("Attempting to send left truck.");
			leftSent = trySendTruck(TruckSide.LEFT);
		}

		if (rightStatus == TruckStatus.AVAILABLE) {
			logInfo("Attempting to send right truck.");
			rightSent = trySendTruck(TruckSide.RIGHT, 0, ((leftStatus == TruckStatus.DEPARTED) || leftSent) ? rightTruckYOffset : 0);
		}

		logInfo((leftSent || rightSent ? "Truck(s) sent" : "No trucks sent") + ". Scheduling next check.");
		scheduleNextTruckCheck();
	}

	/**
	 * Check status of a specific truck
	 * @param side Left or Right truck
	 * @param xoffset Optional X offset to apply to search area (for details screen), defaults to 0
	 * @param yoffset Optional Y offset to apply to search area (for details screen), defaults to 0
	 * @return TruckStatus enum indicating if truck is available, departed, or not found
	 */
	private TruckStatus checkTruckStatus(TruckSide side, int xoffset, int yoffset) {

		PointData start = new PointData(side == TruckSide.LEFT ? LEFT_TRUCK.topLeft() : RIGHT_TRUCK.topLeft());
		PointData end = new PointData(side == TruckSide.LEFT ? LEFT_TRUCK.bottomRight() : RIGHT_TRUCK.bottomRight());
		start.addX(xoffset);
		start.addY(yoffset);
		end.addX(xoffset);
		end.addY(yoffset);

		// Tap to open truck details
		tapInside(start, end);
		sleepTask(500);

		// Check if already departed
		ImageSearchResultData departedResult = templateSearchHelper.locatePattern(
				TemplatesEnum.TUNDRA_TRUCK_DEPARTED,
				SearchConfigConstants.DEFAULT_SINGLE);
		if (departedResult.isFound()) {
			logInfo(side + " truck has already departed");
			// The details display differently for a departed truck. There is not window to close
			return TruckStatus.DEPARTED;
		}

		// Check if available to send
		ImageSearchResultData escortResult = templateSearchHelper.locatePattern(
				TemplatesEnum.TUNDRA_TRUCK_ESCORT,
				SearchConfigConstants.DEFAULT_SINGLE);
		if (escortResult.isFound()) {
			logInfo(side + " truck is available to send");
			pressBack();
			sleepTask(300);
			closeWindow();
			return TruckStatus.AVAILABLE;
		}

		logWarning("Could not determine " + side + " truck status");
		pressBack();
		sleepTask(300);
		closeWindow();
		return TruckStatus.NOT_FOUND;
	}

	private TruckStatus checkTruckStatus(TruckSide side) {
		return checkTruckStatus(side, 0, 0);
	}

	/**
	 * Try to send a specific truck
	 */
	private boolean trySendTruck(TruckSide side, int xoffset, int yoffset) {
		PointData start = new PointData(side == TruckSide.LEFT ? LEFT_TRUCK.topLeft() : RIGHT_TRUCK.topLeft());
		PointData end = new PointData(side == TruckSide.LEFT ? LEFT_TRUCK.bottomRight() : RIGHT_TRUCK.bottomRight());
		start.addX(xoffset);
		start.addY(yoffset);
		end.addX(xoffset);
		end.addY(yoffset);

		tapInside(start, end);
		sleepTask(500);

		// Check if already departed
		if (isTruckDeparted(side)) {
			return false;
		}

		// Check if escort button available
		ImageSearchResultData escortButton = templateSearchHelper.locatePattern(
				TemplatesEnum.TUNDRA_TRUCK_ESCORT,
				SearchConfigConstants.DEFAULT_SINGLE);
		sleepTask(500);

		if (!escortButton.isFound()) {
			logInfo("No " + side + " truck available to send");
			return false;
		}

		// If SSR required, find one
		if (truckSSR && !findSSRTruck()) {
			logInfo("SSR truck not found and required. Skipping " + side + " truck.");
			// We send what we found instead of skipping entirely
		}

		logInfo("Sending " + side + " truck" + (truckSSR ? " (SSR)" : ""));
		tapInside(escortButton);
		sleepTask(1000);

		// Check for "higher-level trucks" pop-up
		ImageSearchResultData tipsPopup = templateSearchHelper.locatePattern(
				TemplatesEnum.TUNDRA_TRUCK_TIPS_POPUP,
				SearchConfigConstants.SINGLE_WITH_2_RETRIES);
		if (tipsPopup.isFound()) {
			tapInside(TIPS_POPUP_CHECKBOX.topLeft(), TIPS_POPUP_CHECKBOX.bottomRight(), 1, 300);
			sleepTask(100);
			tapInside(CONFIRM_CHECKBOX.topLeft(), CONFIRM_CHECKBOX.bottomRight(), 1, 300);
		}

		return true;
	}

	private boolean trySendTruck(TruckSide side) {
		return trySendTruck(side, 0, 0);
	}

	/**
	 * Check if truck already departed
	 */
	private boolean isTruckDeparted(TruckSide side) {
		ImageSearchResultData departedResult = templateSearchHelper.locatePattern(
				TemplatesEnum.TUNDRA_TRUCK_DEPARTED,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (departedResult.isFound()) {
			logInfo(side + " truck already departed. Skipping.");
			tapInside(CLOSE_DETAIL.topLeft(), CLOSE_DETAIL.bottomRight());
			closeWindow();
			return true;
		}

		return false;
	}

	/**
	 * Find SSR truck through refreshes
	 */
	private boolean findSSRTruck() {
		ImageSearchResultData ssrTruck = templateSearchHelper.locatePattern(
				TemplatesEnum.TUNDRA_TRUCK_YELLOW,
				SearchConfigConstants.DEFAULT_SINGLE);

		for (int attempt = 0; attempt < MAX_REFRESH_ATTEMPTS && !ssrTruck.isFound(); attempt++) {
			logInfo("SSR truck not found. Refreshing (attempt " + (attempt + 1) + "/" + MAX_REFRESH_ATTEMPTS + ")");

			if (!refreshTrucks()) {
				logWarning("Refresh failed (likely no gems/free refreshes). Aborting SSR search.");
				return false;
			}

			ssrTruck = templateSearchHelper.locatePattern(
					TemplatesEnum.TUNDRA_TRUCK_YELLOW,
					SearchConfigConstants.DEFAULT_SINGLE);
		}

		if (!ssrTruck.isFound()) {
			logWarning("SSR truck not found after " + MAX_REFRESH_ATTEMPTS + " refresh attempts");
		}

		return ssrTruck.isFound();
	}

	/**
	 * Refresh available trucks
	 */
	private boolean refreshTrucks() {
		tapInside(REFRESH_BUTTON.topLeft(), REFRESH_BUTTON.bottomRight());
		sleepTask(1000);

		ImageSearchResultData freeRefresh = templateSearchHelper.locatePattern(
				TemplatesEnum.TUNDRA_TRUCK_REFRESH,
				SearchConfigConstants.SINGLE_WITH_RETRIES);

		ImageSearchResultData gemRefresh = templateSearchHelper.locatePattern(
				TemplatesEnum.TUNDRA_TRUCK_REFRESH_GEMS,
				SearchConfigConstants.STRICT_MATCHING);

		if (freeRefresh.isFound()) {
			logInfo("Free refresh available - confirming");
			tapInside(CONFIRM_CHECKBOX.topLeft(), CONFIRM_CHECKBOX.bottomRight());
			sleepTask(500);
			tapInside(freeRefresh);
			return true;
		}

		if (gemRefresh.isFound()) {
			return handleGemRefresh(gemRefresh);
		}

		logDebug("Trucks refreshed without confirmation popup");
		return true;
	}

	/**
	 * Handle gem refresh popup
	 */
	private boolean handleGemRefresh(ImageSearchResultData gemButton) {
		logInfo("Gem refresh popup detected");

		if (useGems) {
			logInfo("Using gems for refresh (useGems=true)");
			tapInside(CONFIRM_CHECKBOX.topLeft(), CONFIRM_CHECKBOX.bottomRight());
			sleepTask(500);
			tapInside(gemButton);
			return true;
		}

		logInfo("Declining gem refresh (useGems=false)");
		tapInside(CANCEL_POPUP.topLeft(), CANCEL_POPUP.bottomRight());
		closeWindow();
		return false;
	}

	/**
	 * Schedule next truck check based on soonest return time
	 */
	private void scheduleNextTruckCheck() {
		logInfo("Extracting next truck return times");

		Optional<LocalDateTime> leftTime = extractTruckTime(TruckSide.LEFT);
		Optional<LocalDateTime> rightTime = extractTruckTime(TruckSide.RIGHT);

		LocalDateTime now = LocalDateTime.now();
		LocalDateTime nextSchedule;

		if (leftTime.isPresent() && rightTime.isPresent()) {
			// Use the EARLIER time (soonest truck return)
			nextSchedule = leftTime.get().isBefore(rightTime.get()) ? leftTime.get() : rightTime.get();
			logInfo("Both truck times extracted. Next check: " + nextSchedule.format(DATETIME_FORMATTER)
					+ " (soonest return)");
		} else if (leftTime.isPresent()) {
			nextSchedule = leftTime.get();
			logInfo("Only left truck time extracted. Next check: " + nextSchedule.format(DATETIME_FORMATTER));
		} else if (rightTime.isPresent()) {
			nextSchedule = rightTime.get();
			logInfo("Only right truck time extracted. Next check: " + nextSchedule.format(DATETIME_FORMATTER));
		} else {
			// Fallback: 30 minutes
			nextSchedule = now.plusMinutes(30);
			logInfo("Could not extract truck times. Fallback: next check in 30 minutes");
		}

		reschedule(nextSchedule);
	}

	/**
	 * Extract truck return time from UI
	 */
	private Optional<LocalDateTime> extractTruckTime(TruckSide side) {
		try {
			PointData start = side == TruckSide.LEFT ? LEFT_TRUCK_TIME.topLeft() : RIGHT_TRUCK_TIME.topLeft();
			PointData end = side == TruckSide.LEFT ? LEFT_TRUCK_TIME.bottomRight() : RIGHT_TRUCK_TIME.bottomRight();

			String text = stringHelper.attemptRecognition(
					start,
					end,
					1,
					300L,
					null,
					s -> !s.isEmpty(),
					s -> s);

			if (text == null || text.trim().isEmpty()) {
				logDebug("OCR returned empty for " + side + " truck time");
				return Optional.empty();
			}

			logDebug(side + " truck time OCR: '" + text + "'");

			// Use GameTimeUtils to parse
			LocalDateTime returnTime = GameTimeUtils.resolveFromNow(text);
			logInfo(side + " truck returns at: " + returnTime);
			return Optional.of(returnTime);

		} catch (Exception e) {
			logError("Error extracting " + side + " truck time: " + e.getMessage());
			return Optional.empty();
		}
	}

	/**
	 * Close confirmation window
	 */
	private void closeWindow() {
		sleepTask(300);
		tapInside(CLOSE_WINDOW.topLeft(), CLOSE_WINDOW.bottomRight(), 2, 600);
	}

	@Override
	public LaunchPoint getRequiredStartLocation() {
		return LaunchPoint.WORLD;
	}

	// ===================== ENUMS =====================

	private enum TundraNavigationResult {
		SUCCESS,
		FAILURE,
		COUNTDOWN,
		ENDED
	}

	private enum TruckStatus {
		AVAILABLE,
		DEPARTED,
		NOT_FOUND
	}

	private enum TruckSide {
		LEFT("Left"),
		RIGHT("Right");

		private final String displayName;

		TruckSide(String displayName) {
			this.displayName = displayName;
		}

		@Override
		public String toString() {
			return displayName;
		}
	}
}
