package dev.frostguard.tasks.pets;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.nav.SearchConfigConstants;

import java.time.LocalDateTime;

/**
 * Task that claims alliance treasures from Pet Adventure.
 * 
 * <p>
 * <b>Execution Flow:</b>
 * <ol>
 * <li>Navigate to Pets menu</li>
 * <li>Open Beast Cage</li>
 * <li>Open Alliance Treasure Map screen</li>
 * <li>Open Ally Treasure screen</li>
 * <li>Claim available treasures</li>
 * </ol>
 * 
 * <p>
 * <b>Scheduling:</b>
 * <ul>
 * <li>Success: Reschedules to next game reset</li>
 * <li>No treasure available: Reschedules to next game reset</li>
 * <li>Navigation failure: Retries in 5 minutes</li>
 * </ul>
 */
public class PetAllianceTreasuresRoutine extends DelayedTask {

	// ========================================================================
	// NAVIGATION CONSTANTS
	// ========================================================================

	/**
	 * Delay in minutes before retrying after navigation failure.
	 */
	private static final int RETRY_DELAY_MINUTES = 5;

	// ========================================================================
	// CONSTRUCTOR
	// ========================================================================

	/**
	 * Constructs a new PetAllianceTreasuresRoutine.
	 * 
	 * @param profile     The profile this task will execute for
	 * @param tpDailyTask The task type enum
	 */
	public PetAllianceTreasuresRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
		super(profile, tpDailyTask);
	}

	// ========================================================================
	// TASK CONFIGURATION
	// ========================================================================

	/**
	 * This task can start from any screen location.
	 * 
	 * @return ANY - works from both home and world screens
	 */
	@Override
	public LaunchPoint getRequiredStartLocation() {
		return LaunchPoint.ANY;
	}

	// ========================================================================
	// MAIN EXECUTION
	// ========================================================================

	/**
	 * Executes the alliance treasure claiming process.
	 * 
	 * <p>
	 * Navigates through Pets â†’ Beast Cage â†’ Alliance Treasures and attempts
	 * to claim available rewards. Reschedules appropriately based on outcome.
	 */
	@Override
	protected void execute() {

		if (!navigateToPetAdventure()) {
			rescheduleForRetry();
			return;
		}

		if (!openAllyTreasureScreen()) {
			rescheduleForRetry();
			return;
		}
		claimTreasureIfAvailable();
	}

	// ========================================================================
	// NAVIGATION METHODS
	// ========================================================================

	/**
	 * Navigates from current screen to the Beast Cage menu.
	 * 
	 * <p>
	 * <b>Steps:</b>
	 * <ol>
	 * <li>Search for Pets button (with retries for reliability)</li>
	 * <li>Tap to open Pets menu</li>
	 * <li>Search for Beast Cage button</li>
	 * <li>Tap to open Beast Cage</li>
	 * </ol>
	 * 
	 * @return true if navigation succeeded, false if any step failed
	 */
	private boolean navigateToPetAdventure() {
		logDebug("Opening Pet Adventure through the Daily sidebar");
		return navigationHelper.navigateToSidebarDestination(SidebarDestination.PET_ADVENTURE);
	}

	/**
	 * Opens the alliance treasure screens within Beast Cage.
	 * 
	 * <p>
	 * <b>Navigation sequence:</b>
	 * <ol>
	 * <li>Tap Alliance Treasure Map button (opens treasure map view)</li>
	 * <li>Tap Ally Treasure button (opens claim screen)</li>
	 * </ol>
	 * 
	 * <p>
	 * This method assumes Pet Adventure is open.
	 */
	private boolean openAllyTreasureScreen() {
		ImageSearchResultData allyTreasure = templateSearchHelper.locatePattern(
				TemplatesEnum.PETS_ALLY_TREASURE,
				SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (!allyTreasure.isFound()) {
			logWarning("Ally Treasure control not found on Pet Adventure screen");
			return false;
		}

		logDebug("Opening Ally Treasure screen");
		tapInside(allyTreasure);
		sleepTask(1000);
		return true;
	}

	// ========================================================================
	// CLAIM LOGIC
	// ========================================================================

	/**
	 * Attempts to claim alliance treasure if the claim button is available.
	 * 
	 * <p>
	 * <b>Behavior:</b>
	 * <ul>
	 * <li>If claim button found: Claims treasure, reschedules to next game
	 * reset</li>
	 * <li>If claim button not found: Already claimed, reschedules to next game
	 * reset</li>
	 * </ul>
	 * 
	 * <p>
	 * Always navigates back to previous screen after checking, regardless of
	 * outcome.
	 */
	private void claimTreasureIfAvailable() {
		ImageSearchResultData claimButton = templateSearchHelper.locatePattern(
				TemplatesEnum.PETS_BEAST_ALLIANCE_CLAIM,
				SearchConfigConstants.DEFAULT_SINGLE);

		if (claimButton.isFound()) {
			logInfo("Claim button found - claiming alliance treasure");
			tapInside(claimButton.getPoint(), claimButton.getPoint());
			sleepTask(500); // Wait for claim animation
			rescheduleToGameReset("Alliance treasure claimed successfully");
		} else {
			logInfo("No claimable treasure found - already claimed today");
			rescheduleToGameReset("Treasure already claimed");
		}
	}

	// ========================================================================
	// RESCHEDULING METHODS
	// ========================================================================

	/**
	 * Reschedules the task to the next game reset (00:00 UTC).
	 * 
	 * <p>
	 * Used when treasure is successfully claimed or when no treasure
	 * is available (indicating it was already claimed today).
	 * 
	 * @param reason Descriptive reason for logging purposes
	 */
	private void rescheduleToGameReset(String reason) {
		LocalDateTime nextReset = GameTimeUtils.dailyResetTime();
		reschedule(nextReset);
		logInfo(reason + ". Rescheduling to next game reset: " +
				nextReset.format(DATETIME_FORMATTER));
	}

	/**
	 * Reschedules the task to retry in 5 minutes.
	 * 
	 * <p>
	 * Used when navigation fails (Pets button or Beast Cage not found).
	 * This handles temporary UI issues or feature unavailability.
	 */
	private void rescheduleForRetry() {
		LocalDateTime retryTime = LocalDateTime.now().plusMinutes(RETRY_DELAY_MINUTES);
		reschedule(retryTime);
		logWarning("Navigation failed. Retrying at " + retryTime.format(TIME_FORMATTER));
	}
}
