package dev.frostguard.tasks.pets;

import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.nav.SearchConfigConstants;

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
				logWarning("Reached maximum iteration limit (" + MAX_CHEST_START_ITERATIONS +
						"). Stopping chest search.");
				rescheduleForChestCompletion();
				return;
			}

			// Try each chest type in priority order
			for (TemplatesEnum chestTemplate : CHEST_PRIORITY) {
				ChestStartResult result = attemptToStartChest(chestTemplate);

				if (result == ChestStartResult.STARTED) {
					foundAnyChest = true;
					break; // Restart search from highest priority
				} else if (result == ChestStartResult.NO_ATTEMPTS) {
					return; // Task already rescheduled
				}
				// Continue to next chest type if NOT_FOUND
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
	 * <li>If Start found: tap it, subtract stamina, return to map</li>
	 * <li>If No Attempts: reschedule and exit task</li>
	 * </ol>
	 * 
	 * @param chestTemplate The chest template to search for
	 * @return Result indicating what happened (STARTED, NO_ATTEMPTS, or NOT_FOUND)
	 */
	private ChestStartResult attemptToStartChest(TemplatesEnum chestTemplate) {
		logDebug("Searching for " + chestTemplate);

		ImageSearchResultData chestResult = templateSearchHelper.locatePattern(
				chestTemplate,
				SearchConfigConstants.SINGLE_WITH_RETRIES);

		if (!chestResult.isFound()) {
			return ChestStartResult.NOT_FOUND;
		}

		logInfo("Found " + chestTemplate + ". Attempting to start adventure");

		// Open chest detail screen
		tapInside(chestResult.getPoint(), chestResult.getPoint());
		sleepTask(500); // Wait for detail screen

		// Tap Select button
		ImageSearchResultData selectButton = templateSearchHelper.locatePattern(
				TemplatesEnum.PETS_CHEST_SELECT,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (!selectButton.isFound()) {
			logWarning("Select button not found for " + chestTemplate);
			return ChestStartResult.NOT_FOUND;
		}

		tapInside(selectButton);
		sleepTask(500); // Wait for confirmation screen

		// Check for Start button (attempts available)
		ImageSearchResultData startButton = templateSearchHelper.locatePattern(
				TemplatesEnum.PETS_CHEST_START,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (startButton.isFound()) {
			// Start the adventure
			tapInside(startButton);
			sleepTask(500); // Wait for start confirmation

			staminaHelper.subtractStamina(STAMINA_PER_CHEST, false);
			logInfo("Started " + chestTemplate + " adventure (consumed " +
					STAMINA_PER_CHEST + " stamina)");

			pressBack(); // Return to adventure map
			sleepTask(500); // Wait for screen transition

			return ChestStartResult.STARTED;
		}

		// Check for No Attempts message
		ImageSearchResultData noAttemptsMessage = templateSearchHelper.locatePattern(
				TemplatesEnum.PETS_CHEST_ATTEMPT,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (noAttemptsMessage.isFound()) {
			logInfo("No more adventure attempts available. Rescheduling for next game reset");
			rescheduleToGameReset();
			return ChestStartResult.NO_ATTEMPTS;
		}

		// Neither Start nor No Attempts found - unknown state
		logWarning("Could not determine chest start status for " + chestTemplate);
		pressBack(); // Try to recover by going back
		return ChestStartResult.NOT_FOUND;
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

	// ========================================================================
	// HELPER ENUMS
	// ========================================================================

	/**
	 * Result of attempting to start a chest adventure.
	 */
	private enum ChestStartResult {
		/** Chest was found and adventure started successfully */
		STARTED,

		/** No daily attempts remaining (task rescheduled) */
		NO_ATTEMPTS,

		/** Chest not found or could not be started */
		NOT_FOUND
	}
}
