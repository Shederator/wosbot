package dev.frostguard.tasks.pets;

import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Task that manages Pet Adventure chests.
 * 
 * <p>
 * <b>Execution Flow:</b>
 * <ol>
 * <li>Navigate to Pet Adventures (Pets â†’ Beast Cage â†’ Adventure Map)</li>
 * <li>Claim all completed adventure chests (up to 3 visible)</li>
 * <li>Start new adventures with available chests</li>
 * <li>Repeat until no more chests or attempts exhausted</li>
 * </ol>
 * 
 * <p>
 * <b>Game Mechanics:</b>
 * <ul>
 * <li>Maximum 3 chests visible on screen at once</li>
 * <li>Each chest adventure costs 10 stamina</li>
 * <li>4 daily attempts available (resets at game reset)</li>
 * <li>Chests respawn after claiming rewards</li>
 * <li>Completed chests can be shared with alliance</li>
 * </ul>
 * 
 * <p>
 * <b>Scheduling Strategy:</b>
 * <ul>
 * <li>After starting chests (attempts remaining): 2 hours (for completion)</li>
 * <li>After exhausting attempts: Next game reset</li>
 * <li>Navigation failure: 15 minutes retry</li>
 * </ul>
 * 
 * @author WoS Bot
 */
public class PetAdventureChestRoutine extends DelayedTask {

	// ========================================================================
	// GAME MECHANICS CONSTANTS
	// ========================================================================

	private static final int STAMINA_PER_CHEST = 10;
	private static final int MIN_STAMINA_REQUIRED = 10;
	private static final int TARGET_STAMINA_FOR_REFRESH = 40;
	private static final int MAX_CHEST_START_ITERATIONS = 12;

	private static final SearchConfig CHEST_CANDIDATES = SearchConfig.builder()
			.withMaxAttempts(3)
			.withThreshold(90)
			.withDelay(200L)
			.withMaxResults(3)
			.build();

	private static final SearchConfig ADVENTURE_TIMERS = SearchConfig.builder()
			.withMaxAttempts(1)
			.withThreshold((int) PetAdventureDecisions.TIMER_THRESHOLD)
			.withMaxResults(5)
			.build();

	// ========================================================================
	// NAVIGATION CONSTANTS
	// ========================================================================

	/**
	 * Area to tap for skipping reward popups after claiming chests.
	 * Taps on empty screen space to dismiss overlays.
	 */
	private static final PointData CHEST_CLAIM_POINT = new PointData(370, 800);

	// ========================================================================
	// RETRY CONSTANTS
	// ========================================================================

	private static final int NAVIGATION_RETRY_MINUTES = 15;
	private static final int CHEST_COMPLETION_HOURS = 2;

	// ========================================================================
	// CHEST PRIORITY ORDER
	// ========================================================================

	/**
	 * Chest priority order: Red (highest quality) â†’ Purple â†’ Blue (lowest).
	 */
	private static final List<TemplatesEnum> CHEST_PRIORITY = List.of(
			TemplatesEnum.PETS_CHEST_RED,
			TemplatesEnum.PETS_CHEST_PURPLE,
			TemplatesEnum.PETS_CHEST_BLUE);

	// ========================================================================
	// CONSTRUCTOR
	// ========================================================================

	/**
	 * Constructs a new PetAdventureChestRoutine.
	 * 
	 * @param profile The profile this task will execute for
	 * @param tpTask  The task type enum
	 */
	public PetAdventureChestRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
		super(profile, tpTask);
	}

	// ========================================================================
	// TASK CONFIGURATION
	// ========================================================================

	@Override
	protected boolean consumesStamina() {
		return true;
	}

	@Override
	public boolean provideDailyMissionProgress() {
		return true;
	}

	// ========================================================================
	// MAIN EXECUTION
	// ========================================================================

	/**
	 * Executes the pet adventure chest management process.
	 * 
	 * <p>
	 * <b>Process:</b>
	 * <ol>
	 * <li>Validate stamina availability</li>
	 * <li>Navigate to Pet Adventures</li>
	 * <li>Claim completed chests</li>
	 * <li>Start new adventures until attempts exhausted or no chests found</li>
	 * </ol>
	 */
	@Override
	protected void execute() {

		// Validate stamina before proceeding
		if (!staminaHelper.checkStaminaOrReschedule(
				MIN_STAMINA_REQUIRED,
				TARGET_STAMINA_FOR_REFRESH,
				this)) {
			return; // Task will be rescheduled by helper
		}

		if (!navigateToPetAdventures()) {
			rescheduleForNavigationRetry();
			return;
		}

		claimCompletedChests();
		if (observeOnlyVisit()) {
			clearObserveOnly();
			logInfo("Re-reading Pet Adventure after an unverified start; Start will not be sent on this visit.");
			rescheduleForChestCompletion();
			return;
		}
		startAvailableChests();
	}

	// ========================================================================
	// NAVIGATION METHODS
	// ========================================================================

	/**
	 * Navigates from current screen to the Pet Adventures map.
	 * 
	 * <p>
	 * <b>Steps:</b>
	 * <ol>
	 * <li>Open the Daily sidebar</li>
	 * <li>Locate the Pet Adventure row</li>
	 * <li>Tap its detected Go control</li>
	 * </ol>
	 * 
	 * @return true if navigation succeeded, false if any step failed
	 */
	private boolean navigateToPetAdventures() {
		logDebug("Opening Pet Adventure through the Daily sidebar");
		return navigationHelper.navigateToSidebarDestination(SidebarDestination.PET_ADVENTURE);
	}

	// ========================================================================
	// CLAIMING METHODS
	// ========================================================================

	/**
	 * Claims all completed adventure chests visible on the screen.
	 * 
	 * <p>
	 * Searches for up to 3 completed chests (maximum visible at once)
	 * and claims each one. After claiming, shares the chest with alliance
	 * if the share button is available.
	 * 
	 * <p>
	 * Performs 2 search attempts to ensure all completed chests are claimed,
	 * as new chests may become visible after claiming previous ones.
	 * 
	 * <p>
	 * <b>Process per chest:</b>
	 * <ol>
	 * <li>Tap completed chest</li>
	 * <li>Skip reward popups by tapping empty screen area</li>
	 * <li>Share with alliance if button available</li>
	 * <li>Return to adventure map</li>
	 * </ol>
	 */
	private void claimCompletedChests() {
		logDebug("Searching for completed chests to claim");

		for (int i = 0; i < 2; i++) {
			logDebug("Searching for completed chests. Attempt " + (i + 1) + ".");
			List<ImageSearchResultData> completedChests = templateSearchHelper.locateAllPatterns(
					TemplatesEnum.PETS_CHEST_COMPLETED,
					SearchConfigConstants.MULTIPLE_RESULTS);

			if (completedChests == null || completedChests.isEmpty()) {
				logInfo("No completed chests found on attempt " + (i + 1) + ".");
				continue;
			}

			logInfo("Found " + completedChests.size() + " completed chest(s). Claiming them now.");

			for (ImageSearchResultData chest : completedChests) {
				claimSingleChest(chest);
			}
		}
	}

	/**
	 * Claims a single completed chest.
	 * 
	 * @param chest The search result pointing to the completed chest
	 */
	private void claimSingleChest(ImageSearchResultData chest) {
		logDebug("Claiming completed chest");

		tapInside(chest.getPoint(), chest.getPoint());
		sleepTask(1000); // Wait for chest detail screen

		// Claim chest
		tapNear(CHEST_CLAIM_POINT);
		sleepTask(2000);
		pressBack();
		sleepTask(2000);

		// Check if share button is available
		ImageSearchResultData shareButton = templateSearchHelper.locatePattern(
				TemplatesEnum.PETS_CHEST_SHARE,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (shareButton.isFound()) {
			logDebug("Sharing completed chest with alliance");
			tapInside(shareButton.getPoint(), shareButton.getPoint());
			sleepTask(1000); // Wait for share action
		}

		pressBack(); // Return to adventure map
		sleepTask(1000); // Wait for screen transition
	}

	// ========================================================================
	// CHEST STARTING METHODS
	// ========================================================================

	/**
	 * Starts new adventure chests until no more are available or attempts
	 * exhausted.
	 * 
	 * <p>
	 * <b>Strategy:</b>
	 * <ul>
	 * <li>Searches for chests in priority order (Red â†’ Purple â†’ Blue)</li>
	 * <li>Starts each found chest immediately</li>
	 * <li>Repeats search after each start (UI updates with new chests)</li>
	 * <li>Stops when no chests found or attempts exhausted</li>
	 * <li>Has safety limit to prevent infinite loops</li>
	 * </ul>
	 * 
	 * <p>
	 * <b>Exit conditions:</b>
	 * <ol>
	 * <li>No more chests found on screen</li>
	 * <li>Daily attempts exhausted (detected by template)</li>
	 * <li>Safety iteration limit reached</li>
	 * </ol>
	 */
	private void startAvailableChests() {
		logDebug("Starting available adventure chests");

		int iterationCount = 0;
		boolean foundAnyChest;

		do {
			foundAnyChest = false;
			iterationCount++;

			// Safety check to prevent infinite loops
			if (iterationCount > MAX_CHEST_START_ITERATIONS) {
				scheduleUnknownChestRetry("chest scan reached its iteration limit", "iteration-limit");
				return;
			}

			// Try each chest type in priority order
			for (TemplatesEnum chestTemplate : CHEST_PRIORITY) {
				ChestStartResult result = attemptToStartChest(chestTemplate);

				if (result == ChestStartResult.STARTED) {
					foundAnyChest = true;
					break;
				} else if (result == ChestStartResult.NO_ATTEMPTS) {
					return; // Task already rescheduled
				} else if (result == ChestStartResult.UNKNOWN) {
					return;
				}
			}

		} while (foundAnyChest);

		// No more chests found - reschedule for later
		logInfo("No more available chests found. Rescheduling for chest completion check");
		rescheduleForChestCompletion();
	}

	/**
	 * Attempts to start a single chest adventure.
	 * 
	 * <p>
	 * <b>Process:</b>
	 * <ol>
	 * <li>Search for chest of specified type (with retries)</li>
	 * <li>Tap chest to open detail screen</li>
	 * <li>Tap Select button</li>
	 * <li>Check for Start button vs No Attempts message</li>
	 * <li>Skip pins whose countdown pill is already running</li>
	 * <li>If Start remains visible, retry shortly without recording a start</li>
	 * <li>If Start disappears and In Adventure is present, count the start and keep scanning</li>
	 * <li>If Start disappears without In Adventure, re-read on the next visit and do not tap Start again</li>
	 * <li>If No Attempts: reschedule and exit task</li>
	 * </ol>
	 * 
	 * @param chestTemplate The chest template to search for
	 * @return STARTED, NOT_FOUND, NO_ATTEMPTS, or UNKNOWN
	 */
	private ChestStartResult attemptToStartChest(TemplatesEnum chestTemplate) {
		logDebug("Searching for " + chestTemplate);

		List<ImageSearchResultData> chests = templateSearchHelper.locateAllPatterns(
				chestTemplate,
				CHEST_CANDIDATES);
		List<ImageSearchResultData> timers = templateSearchHelper.locateAllPatterns(
				TemplatesEnum.PETS_CHEST_ADVENTURE_TIMER,
				ADVENTURE_TIMERS);

		if (chests == null || chests.isEmpty()) {
			return ChestStartResult.NOT_FOUND;
		}

		for (ImageSearchResultData chestResult : chests) {
			if (!chestResult.isFound()) {
				continue;
			}
			if (PetAdventureDecisions.occupiedByTimer(chestResult.getPoint(), timers)) {
				logInfo("Skipping " + chestTemplate + " already in adventure at "
						+ chestResult.getPoint());
				continue;
			}
			return startIdleChest(chestTemplate, chestResult);
		}

		return ChestStartResult.NOT_FOUND;
	}

	private ChestStartResult startIdleChest(TemplatesEnum chestTemplate, ImageSearchResultData chestResult) {
		logInfo("Found " + chestTemplate + ". Attempting to start adventure");

		tapInside(chestResult.getPoint(), chestResult.getPoint());
		sleepTask(500);

		ImageSearchResultData selectButton = templateSearchHelper.locatePattern(
				TemplatesEnum.PETS_CHEST_SELECT,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (!selectButton.isFound()) {
			scheduleUnknownChestRetry("Select control was missing for " + chestTemplate, "select-control");
			pressBack();
			return ChestStartResult.UNKNOWN;
		}

		tapInside(selectButton);
		sleepTask(500);

		ImageSearchResultData startButton = templateSearchHelper.locatePattern(
				TemplatesEnum.PETS_CHEST_START,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (startButton.isFound()) {
			tapInside(startButton);
			sleepTask(1000);
			ImageSearchResultData startStillVisible = templateSearchHelper.locatePattern(
					TemplatesEnum.PETS_CHEST_START,
					SearchConfigConstants.DEFAULT_SINGLE);
			if (startStillVisible.isFound()) {
				scheduleUnknownChestRetry("Start control remained visible after the tap", "start-unconfirmed");
				pressBack();
				return ChestStartResult.UNKNOWN;
			}

			ImageSearchResultData inAdventure = templateSearchHelper.locatePattern(
					TemplatesEnum.PETS_CHEST_IN_ADVENTURE,
					SearchConfigConstants.DEFAULT_SINGLE);
			if (PetAdventureDecisions.inAdventureOverlay(inAdventure)) {
				staminaHelper.subtractStamina(STAMINA_PER_CHEST, false);
				logInfo("Started " + chestTemplate + " adventure (consumed "
						+ STAMINA_PER_CHEST + " stamina)");
				pressBack();
				sleepTask(500);
				return ChestStartResult.STARTED;
			}

			markObserveOnly();
			LocalDateTime nextCheck = unverifiedStartRecheck(LocalDateTime.now());
			String snapshot = TaskDiagnosticSnapshots.capture(
					emuManager, EMULATOR_NUMBER, "petadventurechest", "start-outcome");
			logWarning("Start tap was sent but the adventure state was not confirmed; "
					+ "the next visit only re-reads. Next check at "
					+ nextCheck.format(DATETIME_FORMATTER) + "; " + snapshot + ".");
			reschedule(nextCheck);
			pressBack();
			sleepTask(500);
			return ChestStartResult.UNKNOWN;
		}

		ImageSearchResultData noAttemptsMessage = templateSearchHelper.locatePattern(
				TemplatesEnum.PETS_CHEST_ATTEMPT,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (noAttemptsMessage.isFound()) {
			logInfo("No more adventure attempts available. Rescheduling for next game reset");
			rescheduleToGameReset();
			return ChestStartResult.NO_ATTEMPTS;
		}

		logWarning("Could not determine chest start status for " + chestTemplate);
		scheduleUnknownChestRetry("neither Start nor No Attempts was detected", "start-status");
		pressBack();
		return ChestStartResult.UNKNOWN;
	}

	// ========================================================================
	// RESCHEDULING METHODS
	// ========================================================================

	/**
	 * Reschedules the task for chest completion check (2 hours).
	 * 
	 * <p>
	 * Used when chests have been started and we need to wait for them
	 * to complete before claiming rewards.
	 */
	private void rescheduleForChestCompletion() {
		LocalDateTime nextCheck = LocalDateTime.now().plusHours(CHEST_COMPLETION_HOURS);
		reschedule(nextCheck);
		logInfo("Rescheduled for chest completion check at " +
				nextCheck.format(TIME_FORMATTER));
	}

	/**
	 * Reschedules the task to the next game reset (00:00 UTC).
	 * 
	 * <p>
	 * Used when all daily attempts have been exhausted.
	 */
	private void rescheduleToGameReset() {
		LocalDateTime nextReset = GameTimeUtils.dailyResetTime();
		reschedule(nextReset);
		logInfo("Rescheduled to next game reset: " +
				nextReset.format(DATETIME_FORMATTER));
	}

	/**
	 * Reschedules the task for navigation retry (15 minutes).
	 * 
	 * <p>
	 * Used when navigation to Pet Adventures fails.
	 */
	private void rescheduleForNavigationRetry() {
		LocalDateTime retryTime = LocalDateTime.now().plusMinutes(NAVIGATION_RETRY_MINUTES);
		reschedule(retryTime);
		logWarning("Navigation failed. Retrying at " + retryTime.format(TIME_FORMATTER));
	}

	static LocalDateTime unverifiedStartRecheck(LocalDateTime now) {
		return now.plusHours(CHEST_COMPLETION_HOURS);
	}

	private boolean observeOnlyVisit() {
		return Boolean.TRUE.equals(profile.getConfig(
				ConfigurationKeyEnum.PET_ADVENTURE_OBSERVE_ONLY_BOOL, Boolean.class));
	}

	private void markObserveOnly() {
		writeProfileSetting(ConfigurationKeyEnum.PET_ADVENTURE_OBSERVE_ONLY_BOOL, true);
	}

	private void clearObserveOnly() {
		writeProfileSetting(ConfigurationKeyEnum.PET_ADVENTURE_OBSERVE_ONLY_BOOL, false);
	}

	private void scheduleUnknownChestRetry(String reason, String type) {
		LocalDateTime retryTime = LocalDateTime.now().plusMinutes(NAVIGATION_RETRY_MINUTES);
		String snapshot = TaskDiagnosticSnapshots.capture(emuManager, EMULATOR_NUMBER, "petadventurechest", type);
		logWarning("Pet Adventure outcome is unknown: " + reason + "; retrying at "
				+ retryTime.format(DATETIME_FORMATTER) + "; " + snapshot + ".");
		reschedule(retryTime);
	}

	// ========================================================================
	// HELPER ENUMS
	// ========================================================================

	/**
	 * Result of attempting to start a chest adventure.
	 */
	private enum ChestStartResult {
		/** Chest was idle and In Adventure confirmed the start. */
		STARTED,

		/** No daily attempts remaining (task rescheduled) */
		NO_ATTEMPTS,

		/** Chest not found or could not be started */
		NOT_FOUND,

		/** The screen or action outcome could not be verified. */
		UNKNOWN
	}
}
