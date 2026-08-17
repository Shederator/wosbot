package dev.frostguard.tasks.economy;

import java.time.LocalDateTime;
import java.awt.Color;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.helper.TemplateSearchHelper;

/**
 * Task responsible for managing bank deposit operations.
 * 
 * <p>
 * The bank allows players to deposit resources for a fixed duration
 * (1, 7, 15, or 30 days) and earn interest. This task automates:
 * <ul>
 * <li>Checking for ready deposits and withdrawing them</li>
 * <li>Creating new deposits based on configured duration</li>
 * <li>Reading remaining time on active deposits and rescheduling
 * appropriately</li>
 * </ul>
 * 
 * <p>
 * <b>Deposit Types:</b>
 * <ul>
 * <li><b>1-day:</b> Short-term deposit (top-left position)</li>
 * <li><b>7-day:</b> Weekly deposit (top-right position)</li>
 * <li><b>15-day:</b> Bi-weekly deposit (bottom-left position)</li>
 * <li><b>30-day:</b> Monthly deposit (bottom-right position)</li>
 * </ul>
 * 
 * <p>
 * <b>Task Flow:</b>
 * <ol>
 * <li>Navigate to bank via Deals menu</li>
 * <li>Check if deposit is ready to withdraw</li>
 * <li>If ready: Withdraw and create new deposit</li>
 * <li>If not ready: Read remaining time and reschedule</li>
 * <li>If no deposit exists: Create new deposit</li>
 * </ol>
 * 
 * <p>
 * <b>Error Handling:</b>
 * Navigation failures, OCR failures, or missing deposit options trigger
 * retry in 5 minutes to handle transient issues.
 */
public class BankRoutine extends DelayedTask {

	// ===============================
	// CONSTANTS
	// ===============================

	/** Retry delay when navigation or operations fail (minutes) */
	private static final int OPERATION_FAILURE_RETRY_MINUTES = 5;

	/** Maximum OCR retry attempts for reading remaining time */
	private static final int MAX_OCR_RETRIES = 5;

	/** Delay between OCR retry attempts (milliseconds) */
	private static final long OCR_RETRY_DELAY_MS = 1000L;

	/** Number of taps to close withdrawal confirmation screen */
	private static final int CLOSE_SCREEN_TAP_COUNT = 3;

	/** Maximum attempts to open and verify the bank interface. */
	private static final int BANK_NAVIGATION_ATTEMPTS = 2;

	/** Time for the deals carousel to stop moving after the final swipe. */
	private static final long DEALS_CAROUSEL_SETTLE_MS = 1000L;

	/** Time for the selected deals tab to render its bank-specific controls. */
	private static final long BANK_INTERFACE_SETTLE_MS = 1500L;

	// Navigation coordinates
	private static final PointData SWIPE_TAB_START = new PointData(630, 143);
	private static final PointData SWIPE_TAB_END = new PointData(2, 128);
	private static final PointData DEALS_TAB_BAR_TOP_LEFT = new PointData(0, 80);
	private static final PointData DEALS_TAB_BAR_BOTTOM_RIGHT = new PointData(720, 190);

	// Withdrawal screen close button area
	private static final PointData CLOSE_BUTTON_POINT = new PointData(670, 40);

	// Deposit confirmation coordinates
	private static final PointData CONFIRM_SWIPE_START = new PointData(168, 762);
	private static final PointData CONFIRM_SWIPE_END = new PointData(477, 760);
	private static final PointData CONFIRM_TAP_MIN = new PointData(410, 877);
	private static final PointData CONFIRM_TAP_MAX = new PointData(589, 919);

	// OCR region for reading remaining time
	private static final PointData TIME_OCR_TOP_LEFT = new PointData(220, 770);
	private static final PointData TIME_OCR_BOTTOM_RIGHT = new PointData(490, 810);

	// ===============================
	// FIELDS
	// ===============================

	/** Configured bank deposit duration (1, 7, 15, or 30 days) */
	private int bankDepositDuration;

	/** Helper for flexible OCR-based time recognition */
	private final ResilientOcrExecutor<LocalDateTime> timeHelper;

	// ===============================
	// CONSTRUCTOR
	// ===============================

	/**
	 * Constructs a new BankRoutine.
	 * 
	 * @param profile The game profile this task operates on
	 * @param tpTask  The task type enum from the daily task registry
	 */
	public BankRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
		super(profile, tpTask);
		this.timeHelper = new ResilientOcrExecutor<>(provider);
	}

	// ===============================
	// MAIN EXECUTION
	// ===============================

	/**
	 * Main execution method for the bank task.
	 * 
	 * <p>
	 * <b>Execution Flow:</b>
	 * <ol>
	 * <li>Load bank deposit duration configuration</li>
	 * <li>Navigate to bank interface</li>
	 * <li>Handle bank operations (withdraw/deposit/reschedule)</li>
	 * </ol>
	 * 
	 * <p>
	 * Navigation failures trigger retry in
	 * {@value #OPERATION_FAILURE_RETRY_MINUTES} minutes.
	 * All execution paths ensure the task is properly rescheduled.
	 */
	@Override
	protected void execute() {

		loadBankConfiguration();

		if (!navigateToBank()) {
			rescheduleForRetry("Navigation failed");
			return;
		}

		handleBankOperations();
	}

	/**
	 * Loads bank task configuration from the profile.
	 * 
	 * <p>
	 * Configuration loaded:
	 * <ul>
	 * <li><b>INT_BANK_DELAY:</b> Deposit duration (1, 7, 15, or 30 days)</li>
	 * </ul>
	 */
	private void loadBankConfiguration() {
		this.bankDepositDuration = profile.getConfig(
				ConfigurationKeyEnum.INT_BANK_DELAY,
				Integer.class);

		logInfo("Configuration loaded - Deposit duration: " + bankDepositDuration + " days");
	}

	// ===============================
	// NAVIGATION
	// ===============================

	/**
	 * Navigates to the bank section in the game.
	 * 
	 * <p>
	 * <b>Navigation Flow:</b>
	 * <ol>
	 * <li>Find and tap Deals button</li>
	 * <li>Swipe left twice to reveal bank tab</li>
	 * <li>Find and tap Bank option</li>
	 * </ol>
	 * 
	 * @return true if navigation successful, false otherwise
	 */
	private boolean navigateToBank() {
		logInfo("Navigating to bank");

		for (int attempt = 1; attempt <= BANK_NAVIGATION_ATTEMPTS; attempt++) {
			if (attempt > 1) {
				logInfo("Retrying bank navigation after the previous tab did not show bank controls");
				navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
			}

			if (!findAndTapDealsButton()) {
				logWarning("Bank navigation attempt " + attempt + "/" + BANK_NAVIGATION_ATTEMPTS
						+ " could not open Deals");
				continue;
			}

			swipeToRevealBankTab();
			if (findAndTapBankOption(attempt)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Finds and taps the Deals button on the home screen.
	 * 
	 * @return true if button found and tapped, false otherwise
	 */
	private boolean findAndTapDealsButton() {
		ImageSearchResultData dealsResult = templateSearchHelper.locatePattern(
				TemplatesEnum.HOME_DEALS_BUTTON,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (!dealsResult.isFound()) {
			logWarning("Deals button not found");
			return false;
		}

		tapInside(dealsResult);
		sleepTask(2000); // Wait for deals menu to open
		return true;
	}

	/**
	 * Swipes left twice to reveal the bank tab.
	 * 
	 * <p>
	 * The bank option is often hidden and requires swiping
	 * through the deals carousel to reveal it.
	 */
	private void swipeToRevealBankTab() {
		logDebug("Swiping to reveal bank tab");

		swipe(SWIPE_TAB_START, SWIPE_TAB_END);
		sleepTask(400); // Let the first carousel movement establish its final position.

		swipe(SWIPE_TAB_START, SWIPE_TAB_END);
		sleepTask(DEALS_CAROUSEL_SETTLE_MS);
	}

	/**
	 * Finds and taps the Bank option in the deals menu.
	 * 
	 * @return true if bank option found and tapped, false otherwise
	 */
	private boolean findAndTapBankOption(int attempt) {
		ImageSearchResultData bankResult = templateSearchHelper.locatePattern(
				TemplatesEnum.EVENTS_DEALS_BANK,
				TemplateSearchHelper.SearchConfig.builder()
						.withMaxAttempts(1)
						.withThreshold(90)
						.withDelay(300L)
						.withCoordinates(DEALS_TAB_BAR_TOP_LEFT, DEALS_TAB_BAR_BOTTOM_RIGHT)
						.build());

		if (!bankResult.isFound()) {
			logWarning("Bank option not found in deals menu on attempt " + attempt + "/"
					+ BANK_NAVIGATION_ATTEMPTS);
			return false;
		}

		tapInside(bankResult);
		sleepTask(BANK_INTERFACE_SETTLE_MS);

		String evidence = findBankInterfaceEvidence();
		if (evidence != null) {
			logInfo("Successfully navigated to bank; verified by " + evidence);
			return true;
		}

		logWarning("Tapped bank candidate at " + bankResult.getPoint() + " with score "
				+ String.format("%.1f", bankResult.getMatchScore())
				+ "%, but no bank-specific controls appeared on attempt " + attempt + "/"
				+ BANK_NAVIGATION_ATTEMPTS);
		return false;
	}

	private String findBankInterfaceEvidence() {
		if (templateSearchHelper.locatePattern(
				TemplatesEnum.EVENTS_DEALS_BANK_WITHDRAW,
				SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
			return "withdraw control";
		}
		if (templateSearchHelper.locatePattern(
				TemplatesEnum.EVENTS_DEALS_BANK_INDEPOSIT,
				SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
			return "active-deposit control";
		}
		if (templateSearchHelper.locatePattern(
				TemplatesEnum.EVENTS_DEALS_BANK_DEPOSIT,
				SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
			return "deposit control";
		}
		return null;
	}

	// ===============================
	// BANK OPERATIONS
	// ===============================

	/**
	 * Handles all bank operations based on current deposit status.
	 * 
	 * <p>
	 * <b>Operation Flow:</b>
	 * <ol>
	 * <li>Check if deposit is ready to withdraw</li>
	 * <li>If ready: Withdraw existing deposit and create new one</li>
	 * <li>If not ready: Check remaining time and reschedule</li>
	 * </ol>
	 * 
	 * <p>
	 * Closes bank interface after operations complete.
	 */
	private void handleBankOperations() {
		ImageSearchResultData withdrawAvailableResult = checkWithdrawAvailability();

		if (withdrawAvailableResult.isFound()) {
			handleReadyDeposit();
		} else {
			handlePendingDeposit();
		}

		closeBank();
	}

	/**
	 * Checks if a deposit is ready to withdraw.
	 * 
	 * @return Search result for withdraw button
	 */
	private ImageSearchResultData checkWithdrawAvailability() {
		return templateSearchHelper.locatePattern(
				TemplatesEnum.EVENTS_DEALS_BANK_WITHDRAW,
				SearchConfigConstants.DEFAULT_SINGLE);
	}

	/**
	 * Handles a deposit that is ready to withdraw.
	 * 
	 * <p>
	 * Withdraws the deposit and immediately creates a new one
	 * based on the configured duration.
	 */
	private void handleReadyDeposit() {
		logInfo("Deposit ready for withdrawal");

		withdrawDeposit();
		createNewDeposit();
	}

	/**
	 * Handles a pending deposit that is not yet ready.
	 * 
	 * <p>
	 * Either reads the remaining time on an active deposit
	 * or creates a new deposit if none exists.
	 */
	private void handlePendingDeposit() {
		logInfo("No deposit ready for withdrawal. Checking status");

		if (hasActiveDeposit()) {
			readRemainingTimeAndReschedule();
		} else {
			createNewDeposit();
		}
	}

	/**
	 * Closes the bank interface by tapping back button.
	 */
	private void closeBank() {
		pressBack();
	}

	// ===============================
	// WITHDRAWAL
	// ===============================

	/**
	 * Withdraws a ready deposit.
	 * 
	 * <p>
	 * <b>Withdrawal Flow:</b>
	 * <ol>
	 * <li>Find and tap withdraw button</li>
	 * <li>Wait for withdrawal to process</li>
	 * <li>Close confirmation screen</li>
	 * </ol>
	 */
	private void withdrawDeposit() {
		ImageSearchResultData withdrawResult = templateSearchHelper.locatePattern(
				TemplatesEnum.EVENTS_DEALS_BANK_WITHDRAW,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (withdrawResult.isFound()) {
			tapInside(withdrawResult.getPoint(), withdrawResult.getPoint());
			sleepTask(1000); // Wait for withdrawal to process

			closeWithdrawalScreen();

			logInfo("Deposit successfully withdrawn");
		} else {
			logWarning("Withdraw button not found during withdrawal attempt");
		}
	}

	/**
	 * Closes the withdrawal confirmation screen.
	 * 
	 * <p>
	 * Taps the close button area multiple times to ensure
	 * all popups and animations are dismissed.
	 */
	private void closeWithdrawalScreen() {
		tapInside(
				CLOSE_BUTTON_POINT,
				CLOSE_BUTTON_POINT,
				CLOSE_SCREEN_TAP_COUNT,
				100 // Delay between taps
		);
		sleepTask(1000); // Wait for screen to close
	}

	// ===============================
	// DEPOSIT CREATION
	// ===============================

	/**
	 * Creates a new deposit based on configured duration.
	 * 
	 * <p>
	 * Uses deposit configuration to determine:
	 * <ul>
	 * <li>Search area for the deposit button</li>
	 * <li>Deposit duration in days</li>
	 * <li>Display name for logging</li>
	 * </ul>
	 * 
	 * <p>
	 * If deposit option not found, reschedules retry.
	 * If configuration invalid, uses 1-day deposit as fallback.
	 */
	private void createNewDeposit() {
		DepositConfig config = getDepositConfig(bankDepositDuration);

		ImageSearchResultData depositResult = searchForDepositOption(config);

		if (depositResult.isFound()) {
			executeDepositCreation(config, depositResult);
		} else {
			handleDepositNotFound(config);
		}
	}

	/**
	 * Gets deposit configuration for the specified duration.
	 * 
	 * @param duration Deposit duration in days (1, 7, 15, or 30)
	 * @return Deposit configuration, or 1-day fallback if invalid
	 */
	private DepositConfig getDepositConfig(int duration) {
		DepositConfig config = DepositConfig.fromDuration(duration);

		if (config == null) {
			logWarning("Invalid bank duration: " + duration + ". Using 1-day fallback");
			config = DepositConfig.ONE_DAY;
		}

		return config;
	}

	/**
	 * Searches for the deposit option in the configured area.
	 * 
	 * @param config Deposit configuration containing search area
	 * @return Search result for deposit button
	 */
	private ImageSearchResultData searchForDepositOption(DepositConfig config) {
		return templateSearchHelper.locatePattern(
				TemplatesEnum.EVENTS_DEALS_BANK_DEPOSIT,
				TemplateSearchHelper.SearchConfig.builder()
						.withMaxAttempts(1)
						.withThreshold(90)
						.withDelay(300L)
						.withCoordinates(config.searchMin, config.searchMax)
						.build());
	}

	/**
	 * Executes the deposit creation process.
	 * 
	 * @param config        Deposit configuration
	 * @param depositResult Search result containing deposit button location
	 */
	private void executeDepositCreation(DepositConfig config, ImageSearchResultData depositResult) {
		logInfo("Creating " + config.displayName + " deposit");

		tapInside(depositResult.getPoint(), depositResult.getPoint());
		sleepTask(2000); // Wait for deposit interface to load

		confirmDeposit();

		LocalDateTime nextCheck = LocalDateTime.now().plusDays(config.durationDays);
		reschedule(nextCheck);

		logInfo("Deposit created. Next check scheduled for: " + nextCheck.format(DATETIME_FORMATTER));
	}

	/**
	 * Confirms the deposit by swiping and tapping confirmation button.
	 */
	private void confirmDeposit() {
		swipe(CONFIRM_SWIPE_START, CONFIRM_SWIPE_END);
		tapInside(CONFIRM_TAP_MIN, CONFIRM_TAP_MAX);
	}

	/**
	 * Handles the case when deposit option is not found.
	 * 
	 * @param config Deposit configuration that was searched for
	 */
	private void handleDepositNotFound(DepositConfig config) {
		logWarning(config.displayName + " deposit option not available");
		rescheduleForRetry("Deposit option not found");
	}

	// ===============================
	// ACTIVE DEPOSIT HANDLING
	// ===============================

	/**
	 * Checks if there's an active deposit.
	 * 
	 * @return true if active deposit found, false otherwise
	 */
	private boolean hasActiveDeposit() {
		ImageSearchResultData activeDepositResult = templateSearchHelper.locatePattern(
				TemplatesEnum.EVENTS_DEALS_BANK_INDEPOSIT,
				SearchConfigConstants.DEFAULT_SINGLE);

		return activeDepositResult.isFound();
	}

	/**
	 * Reads remaining time on active deposit and reschedules task.
	 * 
	 * <p>
	 * Uses {@link ResilientOcrExecutor} for robust OCR with:
	 * <ul>
	 * <li>Time format validation</li>
	 * <li>Automatic retry on failure</li>
	 * <li>Clean error handling</li>
	 * </ul>
	 * 
	 * <p>
	 * If OCR fails after all retries, reschedules retry in 5 minutes.
	 */
	private void readRemainingTimeAndReschedule() {
		logInfo("Active deposit found. Reading remaining time");

		tapActiveDeposit();

		LocalDateTime nextTime = extractRemainingTime();

		if (nextTime != null) {
			reschedule(nextTime);
			logInfo("Deposit not ready. Rescheduled for: " + nextTime.format(DATETIME_FORMATTER));
		} else {
			rescheduleForRetry("OCR failed to read remaining time");
		}
	}

	/**
	 * Taps on the active deposit to reveal remaining time.
	 */
	private void tapActiveDeposit() {
		ImageSearchResultData activeDepositResult = templateSearchHelper.locatePattern(
				TemplatesEnum.EVENTS_DEALS_BANK_INDEPOSIT,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (activeDepositResult.isFound()) {
			tapInside(activeDepositResult);
			sleepTask(200); // Wait for time display to appear
		}
	}

	/**
	 * Extracts remaining time from the UI via OCR.
	 * 
	 * <p>
	 * Uses {@link ResilientOcrExecutor} with:
	 * <ul>
	 * <li>Validator: {@link GameTimeUtils#isAcceptedFormat}</li>
	 * <li>Converter: {@link GameTimeUtils#parseDuration}</li>
	 * <li>Max retries: {@value #MAX_OCR_RETRIES}</li>
	 * <li>Retry delay: {@value #OCR_RETRY_DELAY_MS}ms</li>
	 * </ul>
	 * 
	 * @return LocalDateTime when deposit will be ready, or null if extraction
	 *         failed
	 */
	private LocalDateTime extractRemainingTime() {
		logDebug("Extracting remaining time via OCR");

		LocalDateTime nextTime = timeHelper.attemptRecognition(
				TIME_OCR_TOP_LEFT,
				TIME_OCR_BOTTOM_RIGHT,
				MAX_OCR_RETRIES,
				OCR_RETRY_DELAY_MS,
				OcrSettingsData.assembler()
                        .stripBackground(true)
                        .setTextColor(new Color(255, 255, 255))
                        .charWhitelist("0123456789:d")
                        .build(),
				GameTimeUtils::isAcceptedFormat,
				text -> LocalDateTime.now().plus(GameTimeUtils.parseDuration(text)));

		if (nextTime == null) {
			logWarning("Failed to extract remaining time after " + MAX_OCR_RETRIES + " attempts");
		}

		return nextTime;
	}

	// ===============================
	// RETRY HANDLING
	// ===============================

	/**
	 * Reschedules the task for retry after a failure.
	 * 
	 * @param reason Reason for the retry (for logging)
	 */
	private void rescheduleForRetry(String reason) {
		LocalDateTime retryTime = LocalDateTime.now().plusMinutes(OPERATION_FAILURE_RETRY_MINUTES);
		reschedule(retryTime);

		logWarning(reason + ". Retrying in " + OPERATION_FAILURE_RETRY_MINUTES +
				" minutes at: " + retryTime.format(DATETIME_FORMATTER));
	}

	// ===============================
	// TASK FRAMEWORK OVERRIDES
	// ===============================

	/**
	 * Specifies the required starting screen location for this task.
	 * 
	 * <p>
	 * The deals shortcut is available from the home screen. Starting there also
	 * gives a failed tab-selection attempt a deterministic recovery point.
	 * 
	 * @return HOME as the required start location
	 */
	@Override
	protected LaunchPoint getRequiredStartLocation() {
		return LaunchPoint.HOME;
	}

	// ===============================
	// INNER CLASSES
	// ===============================

	/**
	 * Enumeration of available deposit configurations.
	 * 
	 * <p>
	 * Each configuration specifies:
	 * <ul>
	 * <li>Duration in days</li>
	 * <li>Display name for logging</li>
	 * <li>Search area coordinates for the deposit button</li>
	 * </ul>
	 */
	private enum DepositConfig {
		/** 1-day deposit (top-left position) */
		ONE_DAY(
				1,
				"1-day",
				1,
				new PointData(50, 580),
				new PointData(320, 920)),

		/** 7-day deposit (top-right position) */
		SEVEN_DAY(
				7,
				"7-day",
				7,
				new PointData(380, 580),
				new PointData(670, 920)),

		/** 15-day deposit (bottom-left position) */
		FIFTEEN_DAY(
				15,
				"15-day",
				15,
				new PointData(50, 900),
				new PointData(340, 1250)),

		/** 30-day deposit (bottom-right position) */
		THIRTY_DAY(
				30,
				"30-day",
				30,
				new PointData(380, 900),
				new PointData(660, 1250));

		/** Configuration key value (1, 7, 15, or 30) */
		final int configValue;

		/** Display name for logging */
		final String displayName;

		/** Duration in days */
		final int durationDays;

		/** Top-left corner of search area */
		final PointData searchMin;

		/** Bottom-right corner of search area */
		final PointData searchMax;

		/**
		 * Constructs a deposit configuration.
		 * 
		 * @param configValue  Configuration key value
		 * @param displayName  Display name for logging
		 * @param durationDays Duration in days
		 * @param searchMin    Top-left corner of search area
		 * @param searchMax    Bottom-right corner of search area
		 */
		DepositConfig(
				int configValue,
				String displayName,
				int durationDays,
				PointData searchMin,
				PointData searchMax) {
			this.configValue = configValue;
			this.displayName = displayName;
			this.durationDays = durationDays;
			this.searchMin = searchMin;
			this.searchMax = searchMax;
		}

		/**
		 * Gets deposit configuration for a duration value.
		 * 
		 * @param duration Duration value (1, 7, 15, or 30)
		 * @return Matching deposit configuration, or null if invalid
		 */
		static DepositConfig fromDuration(int duration) {
			for (DepositConfig config : values()) {
				if (config.configValue == duration) {
					return config;
				}
			}
			return null;
		}
	}
}
