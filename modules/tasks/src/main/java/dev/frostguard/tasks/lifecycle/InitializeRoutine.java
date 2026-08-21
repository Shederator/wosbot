package dev.frostguard.tasks.lifecycle;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.error.ActionRequiredContext;
import dev.frostguard.engine.error.ProfileCooldownException;
import dev.frostguard.engine.error.ProfileInReconnectStateException;
import dev.frostguard.engine.error.StartupCaptureException;
import dev.frostguard.engine.error.StopExecutionException;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.helper.CharacterSwitchHelper;
import dev.frostguard.vision.convert.ImageConverter;
import dev.frostguard.vision.match.OpenCvPatternLocator;

import java.time.LocalDateTime;

/**
 * Initialize task that starts the bot and prepares the game for automation.
 * 
 * <p>
 * This task is the first task executed when the bot starts and performs
 * critical initialization:
 * <ul>
 * <li>Ensures the emulator is running (launches if needed)</li>
 * <li>Verifies Whiteout Survival is installed</li>
 * <li>Launches the game if not already running</li>
 * <li>Waits for home or world screen to appear</li>
 * <li>Reads initial stamina value from profile</li>
 * </ul>
 * 
 * <p>
 * <b>Unique Behavior:</b>
 * <ul>
 * <li>This task does NOT reschedule after execution</li>
 * <li>Sets recurring=false on start to prevent re-execution</li>
 * <li>Unknown startup failures receive one immediate emulator restart</li>
 * <li>Persistent blockers pause the profile until a conservative retry time</li>
 * <li>Exception on success: Task completes without rescheduling</li>
 * </ul>
 * 
 * <p>
 * <b>Error Handling:</b>
 * <ul>
 * <li>Game not installed: Throws StopExecutionException (stops queue)</li>
 * <li>Reconnect state detected: Throws ProfileInReconnectStateException</li>
 * <li>Home screen not found: Performs one bounded emulator restart</li>
 * <li>Verified Google Play redirect: Requires operator action and releases the
 * emulator slot during a profile cooldown</li>
 * </ul>
 * 
 * <p>
 * <b>State Management:</b>
 * The {@code isStarted} field is instance state that persists between
 * executions.
 * This is intentional - if the task retries (recurring=true), it will re-check
 * emulator status but maintain this flag across retry attempts.
 */
public class InitializeRoutine extends DelayedTask {

	// ========== Home Screen Detection Constants ==========
	private static final int MAX_HOME_SCREEN_ATTEMPTS = 10;
	private static final int RESOURCE_DOWNLOAD_TIMEOUT_MINUTES = 10;
	private static final int RESOURCE_DOWNLOAD_POLL_DELAY_MS = 5000;
	private static final int MAX_RESOURCE_DOWNLOAD_ATTEMPTS =
			RESOURCE_DOWNLOAD_TIMEOUT_MINUTES * 60_000 / RESOURCE_DOWNLOAD_POLL_DELAY_MS;
	private static final int RESOURCE_DOWNLOAD_PROGRESS_LOG_INTERVAL = 12;
	private static final PointData WELCOME_BACK_TITLE_AREA_TOP_LEFT = new PointData(170, 170);
	private static final PointData WELCOME_BACK_TITLE_AREA_BOTTOM_RIGHT = new PointData(550, 340);
	private static final PointData WELCOME_BACK_CONFIRM_AREA_TOP_LEFT = new PointData(150, 880);
	private static final PointData WELCOME_BACK_CONFIRM_AREA_BOTTOM_RIGHT = new PointData(570, 1140);
	private static final PointData UPDATE_TITLE_AREA_TOP_LEFT = new PointData(250, 250);
	private static final PointData UPDATE_TITLE_AREA_BOTTOM_RIGHT = new PointData(470, 350);
	private static final PointData UPDATE_BUTTON_AREA_TOP_LEFT = new PointData(200, 850);
	private static final PointData UPDATE_BUTTON_AREA_BOTTOM_RIGHT = new PointData(520, 1050);
	private static final PointData CLOSEABLE_OVERLAY_AREA_TOP_LEFT = new PointData(540, 65);
	private static final PointData CLOSEABLE_OVERLAY_AREA_BOTTOM_RIGHT = new PointData(680, 200);
	private static final int UPDATE_PATTERN_THRESHOLD = 90;
	private static final int UPDATE_POSTCONDITION_TIMEOUT_MINUTES = 10;
	private static final int UPDATE_POSTCONDITION_POLL_DELAY_MS = 5000;
	private static final int MAX_UPDATE_POSTCONDITION_ATTEMPTS =
			UPDATE_POSTCONDITION_TIMEOUT_MINUTES * 60_000 / UPDATE_POSTCONDITION_POLL_DELAY_MS;
	private static final int UPDATE_PROGRESS_LOG_INTERVAL = 12;
	private static final String GOOGLE_PLAY_PACKAGE = "com.android.vending";
	private static final int MAX_WELCOME_BACK_DISMISSALS = 1;
	private static final int MAX_CLOSEABLE_OVERLAY_DISMISSALS = 3;
	private static final int STARTUP_PATTERN_THRESHOLD = 90;

	// ========== Instance State ==========
	/**
	 * Tracks whether the emulator has been successfully started.
	 * This persists across task executions (when recurring=true triggers retry).
	 */
	boolean isStarted = false;
	private int emulatorRestartAttempts = 0;
	private int welcomeBackDismissals = 0;
	private int closeableOverlayDismissals = 0;
	private String lastVerifiedStartupState = "initialization started";

	/**
	 * Helper for character switching operations.
	 */
	private CharacterSwitchHelper characterSwitchHelper;

	/**
	 * Constructs a new InitializeRoutine.
	 *
	 * @param profile     the profile this task belongs to
	 * @param tpDailyTask the task type enum
	 */
	public InitializeRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
		super(profile, tpDailyTask);
		// Initialize character switch helper
		this.characterSwitchHelper = new CharacterSwitchHelper(emuManager, EMULATOR_NUMBER, profile);
	}

	/**
	 * Main execution method for initialization.
	 * 
	 * <p>
	 * Flow:
	 * <ol>
	 * <li>Set recurring=false (one-time execution by default)</li>
	 * <li>Ensure emulator is running</li>
	 * <li>Verify game is installed</li>
	 * <li>Launch game if needed</li>
	 * <li>Wait for home/world screen</li>
	 * <li>Update initial stamina value</li>
	 * </ol>
	 * 
	 * <p>
	 * <b>No Reschedule:</b>
	 * This task intentionally does not call reschedule(). It either:
	 * <ul>
	 * <li>Completes successfully (recurring=false, task stops)</li>
	 * <li>Fails and sets recurring=true (immediate retry)</li>
	 * <li>Throws exception (queue handles appropriately)</li>
	 * </ul>
	 * 
	 * @throws StopExecutionException           if game is not installed
	 * @throws ProfileInReconnectStateException if profile needs reconnection
	 */
	@Override
	protected void execute() {
		setRecurring(false);

		ensureEmulatorRunning();
		ensureGameInstalled();
		ensureGameRunning();
		
		// Wait for home screen
		if (!waitForHomeScreen()) {
			// Home screen not found - already handled (emulator closed, recurring set)
			return;
		}
		
		// Verify and switch character if needed (before reading stamina)
		if (!verifyAndSwitchCharacter()) {
			// Character verification/switching failed - already handled
			return;
		}
		
		// All checks passed - complete initialization
		handleInitializationSuccess();
	}

	/**
	 * Ensures the emulator is running, launching it if necessary.
	 * 
	 * <p>
	 * This method loops until the emulator is confirmed running.
	 * If not running, it attempts to launch and waits before checking again.
	 * 
	 * <p>
	 * The {@code isStarted} flag prevents redundant checks on subsequent
	 * calls within the same execution.
	 */
	private void ensureEmulatorRunning() {
		logInfo("Checking emulator status...");

		while (!isStarted) {
			if (emuManager.isRunning(EMULATOR_NUMBER)) {
				isStarted = true;
				lastVerifiedStartupState = "emulator running";
				logInfo("Emulator is running.");
			} else {
				logInfo("Emulator not found. Attempting to start it...");
				emuManager.launchEmulator(EMULATOR_NUMBER);
				logInfo("Waiting 10 seconds before checking again.");
				sleepTask(10000); // Wait for emulator to start
			}
		}
	}

	/**
	 * Verifies that Whiteout Survival is installed on the emulator.
	 * 
	 * <p>
	 * If the game is not installed, throws StopExecutionException to halt
	 * the task queue, as automation cannot proceed without the game.
	 * 
	 * @throws StopExecutionException if game is not installed
	 */
	private void ensureGameInstalled() {
		if (!emuManager.isGameInstalled(EMULATOR_NUMBER)) {
			logError("Whiteout Survival is not installed. Stopping the task queue.");
			throw new StopExecutionException("Game not installed");
		}
		lastVerifiedStartupState = "game installed";
	}

	/**
	 * Ensures the game is running, launching it if necessary.
	 * 
	 * <p>
	 * Checks if Whiteout Survival is currently running. If not, launches
	 * the game and waits for it to start.
	 */
	private void ensureGameRunning() {
		if (!emuManager.isPackageRunning(EMULATOR_NUMBER, EmulatorController.GAME.getPackageName())) {
			logInfo("Whiteout Survival is not running. Launching the game...");
			emuManager.launchApp(EMULATOR_NUMBER, EmulatorController.GAME.getPackageName());
			sleepTask(10000); // Wait for game to launch
			lastVerifiedStartupState = "game launch requested and settle delay completed";
		} else {
			lastVerifiedStartupState = "game foreground package verified";
			logInfo("Whiteout Survival is already running.");
		}
	}

	private RawImageData captureStartupFrame(String inspection) {
		String serial;
		try {
			serial = emuManager.getDeviceSerial(EMULATOR_NUMBER);
		} catch (RuntimeException failure) {
			serial = "unavailable (" + failure.getClass().getSimpleName() + ")";
		}
		return StartupCaptureRetry.capture(
				new StartupCaptureRetry.CaptureContext(
						EMULATOR_NUMBER, serial, inspection, lastVerifiedStartupState),
				() -> emuManager.captureScreen(EMULATOR_NUMBER),
				this::logWarning,
				this::sleepTask);
	}

	/**
	 * Waits for the home or world screen to appear.
	 * 
	 * <p>
	 * Continuously searches for home/world screen indicators up to
	 * MAX_HOME_SCREEN_ATTEMPTS. If a reconnect popup is detected, throws
	 * ProfileInReconnectStateException.
	 * 
	 * <p>
	 * If home screen is not found after all attempts, the first failure restarts
	 * the emulator. A subsequent failure enters a profile cooldown and releases
	 * the emulator slot.
	 *
	 * <p>
	 * A verified update dialog uses only its detected button, then waits for a
	 * fresh known postcondition. Only a proven Google Play foreground redirect bypasses the
	 * restart and escalates immediately.
	 *
	 * <p>
	 * The bounded-restart path:
	 * <ul>
	 * <li>Closes the emulator</li>
	 * <li>Resets isStarted flag</li>
	 * <li>Sets recurring=true (triggers immediate retry)</li>
	 * </ul>
	 * 
	 * <p>
	 * If home screen is found, returns true to allow caller to proceed with
	 * character verification and initialization.
	 * 
	 * @return true if home screen was found, false if not found after max attempts
	 * @throws ProfileInReconnectStateException if reconnect popup detected
	 */
	private boolean waitForHomeScreen() {
		int attempts = 0;
		boolean homeScreenFound = false;

		while (attempts < MAX_HOME_SCREEN_ATTEMPTS) {
			if (searchForHomeScreen()) {
				homeScreenFound = true;
				lastVerifiedStartupState = "home/world screen";
				logInfo("Home screen found.");
				break;
			}

			checkForReconnectState();

			ResourceDownloadResult downloadResult = downloadRequiredResources();
			if (downloadResult == ResourceDownloadResult.COMPLETED) {
				homeScreenFound = true;
				logInfo("Home screen found after required resource download.");
				break;
			}
			if (downloadResult == ResourceDownloadResult.TIMED_OUT) {
				handleHomeScreenNotFound();
				return false;
			}

			if (dismissWelcomeBackIfPresent()) {
				logInfo("Verified Welcome back dialog dismissed. Waiting for home/world postcondition.");
				sleepTask(2000);
				attempts++;
				continue;
			}

			MandatoryUpdateResult updateResult = followMandatoryUpdateIfPresent();
			if (updateResult == MandatoryUpdateResult.COMPLETED) {
				homeScreenFound = true;
				logInfo("Home screen found after the game update flow.");
				break;
			}

			if (dismissCloseableStartupOverlayIfPresent()) {
				logInfo("Verified closeable startup overlay dismissed. Waiting for home/world postcondition.");
				sleepTask(2000);
				attempts++;
				continue;
			}

			logWarning("Home screen not found on an unsupported startup screen. "
					+ "Waiting 5 seconds for a passive state change before retrying...");
			sleepTask(5000);
			attempts++;
		}

		if (!homeScreenFound) {
			handleHomeScreenNotFound();
			return false;
		}
		
		return true;
	}

	private MandatoryUpdateResult followMandatoryUpdateIfPresent() {
		MandatoryUpdateInspection inspection;
		try {
			RawImageData capture = captureStartupFrame("mandatory-update dialog inspection");
			if (capture == null) {
				return MandatoryUpdateResult.NOT_PRESENT;
			}
			inspection = inspectMandatoryUpdate(capture);
		} catch (StartupCaptureException ex) {
			throw ex;
		} catch (RuntimeException ex) {
			logDebug("Mandatory-update screen inspection unavailable: " + ex.getMessage());
			return MandatoryUpdateResult.NOT_PRESENT;
		}

		MandatoryUpdateScreenClassifier.Evidence evidence = inspection.evidence();
		if (!evidence.detected()) {
			return MandatoryUpdateResult.NOT_PRESENT;
		}

		logInfo("Mandatory game update dialog and its action were verified. "
				+ evidence.technicalSummary());
		lastVerifiedStartupState = "mandatory in-game update dialog and button";
		if (!tapInside(inspection.updateButton())) {
			deferUnverifiedUpdatePostcondition(
					"The detected Update button could not be tapped safely.",
					evidence.technicalSummary());
		}
		logInfo("Tapped the detected in-game Update button. Waiting for a verified fresh postcondition.");
		lastVerifiedStartupState = "mandatory in-game Update action sent";
		return observeUpdatePostcondition(evidence);
	}

	private MandatoryUpdateResult observeUpdatePostcondition(
			MandatoryUpdateScreenClassifier.Evidence updateEvidence) {
		for (int attempt = 1; attempt <= MAX_UPDATE_POSTCONDITION_ATTEMPTS; attempt++) {
			sleepTask(UPDATE_POSTCONDITION_POLL_DELAY_MS);

			if (searchForHomeScreen()) {
				lastVerifiedStartupState = "home/world screen after update";
				logInfo("Game update flow returned to the home/world screen without operator action.");
				return MandatoryUpdateResult.COMPLETED;
			}
			checkForReconnectState();

			ResourceDownloadResult downloadResult = downloadRequiredResources();
			if (downloadResult == ResourceDownloadResult.COMPLETED) {
				return MandatoryUpdateResult.COMPLETED;
			}
			if (downloadResult == ResourceDownloadResult.TIMED_OUT) {
				deferUnverifiedUpdatePostcondition(
						"Required resources did not finish after the game update action.",
						updateEvidence.technicalSummary());
			}

			if (dismissWelcomeBackIfPresent()) {
				logInfo("Verified Welcome back dialog dismissed during the game update flow.");
				continue;
			}
			if (dismissCloseableStartupOverlayIfPresent()) {
				logInfo("Verified closeable startup overlay dismissed during the game update flow.");
				continue;
			}

			RawImageData freshFrame = captureStartupFrame("post-update foreground screen inspection");
			StoreRedirectEvidence storeRedirect = inspectStoreRedirect(freshFrame);
			if (storeRedirect.detected()) {
				lastVerifiedStartupState = "Google Play foreground after in-game Update action";
				deferPlayStoreRedirect(updateEvidence, storeRedirect);
			}

			if (attempt % UPDATE_PROGRESS_LOG_INTERVAL == 0) {
				logInfo("Game update postcondition is still pending ("
						+ attempt * UPDATE_POSTCONDITION_POLL_DELAY_MS / 1000
						+ " seconds elapsed). No unverified screen input was sent.");
			}
		}

		deferUnverifiedUpdatePostcondition(
				"The Update action did not reach a known safe postcondition within "
						+ UPDATE_POSTCONDITION_TIMEOUT_MINUTES + " minutes.",
				updateEvidence.technicalSummary());
		return MandatoryUpdateResult.NOT_PRESENT;
	}

	private MandatoryUpdateInspection inspectMandatoryUpdate(RawImageData capture) {
		if (capture == null) {
			return new MandatoryUpdateInspection(
					MandatoryUpdateScreenClassifier.inspect(null, false, 0, false, 0),
					new ImageSearchResultData());
		}
		ImageSearchResultData title = OpenCvPatternLocator.locatePattern(
				capture,
				TemplatesEnum.GAME_START_MANDATORY_UPDATE_TITLE.getTemplate(),
				UPDATE_TITLE_AREA_TOP_LEFT,
				UPDATE_TITLE_AREA_BOTTOM_RIGHT,
				UPDATE_PATTERN_THRESHOLD);
		ImageSearchResultData updateButton = OpenCvPatternLocator.locatePattern(
				capture,
				TemplatesEnum.GAME_START_MANDATORY_UPDATE_BUTTON.getTemplate(),
				UPDATE_BUTTON_AREA_TOP_LEFT,
				UPDATE_BUTTON_AREA_BOTTOM_RIGHT,
				UPDATE_PATTERN_THRESHOLD);
		return new MandatoryUpdateInspection(
				MandatoryUpdateScreenClassifier.inspect(
						ImageConverter.toBufferedImage(capture),
						title.isFound(), title.getMatchScore(),
						updateButton.isFound(), updateButton.getMatchScore()),
				updateButton);
	}

	private boolean dismissWelcomeBackIfPresent() {
		if (welcomeBackDismissals >= MAX_WELCOME_BACK_DISMISSALS) {
			return false;
		}

		RawImageData capture = captureStartupFrame("Welcome back dialog inspection");
		if (capture == null) {
			return false;
		}
		ImageSearchResultData title = OpenCvPatternLocator.locatePattern(
				capture,
				TemplatesEnum.GAME_START_WELCOME_BACK_TITLE.getTemplate(),
				WELCOME_BACK_TITLE_AREA_TOP_LEFT,
				WELCOME_BACK_TITLE_AREA_BOTTOM_RIGHT,
				UPDATE_PATTERN_THRESHOLD);
		ImageSearchResultData confirm = OpenCvPatternLocator.locatePattern(
				capture,
				TemplatesEnum.GAME_START_WELCOME_BACK_CONFIRM_BUTTON.getTemplate(),
				WELCOME_BACK_CONFIRM_AREA_TOP_LEFT,
				WELCOME_BACK_CONFIRM_AREA_BOTTOM_RIGHT,
				UPDATE_PATTERN_THRESHOLD);
		if (!title.isFound() || !confirm.isFound()) {
			return false;
		}

		welcomeBackDismissals++;
		lastVerifiedStartupState = "Welcome back dialog and Confirm button";
		logInfo("Welcome back startup dialog and Confirm action verified from one fresh frame"
				+ "; titlePattern=" + String.format(java.util.Locale.ROOT, "%.1f", title.getMatchScore())
				+ "%"
				+ "; confirmPattern=" + String.format(java.util.Locale.ROOT, "%.1f", confirm.getMatchScore())
				+ "%");
		if (!tapInside(confirm)) {
			logWarning("Verified Welcome back Confirm area was unavailable for a safe tap; no fallback input sent.");
			return false;
		}
		lastVerifiedStartupState = "Welcome back Confirm action sent";
		return true;
	}

	private boolean dismissCloseableStartupOverlayIfPresent() {
		if (closeableOverlayDismissals >= MAX_CLOSEABLE_OVERLAY_DISMISSALS) {
			return false;
		}

		RawImageData capture = captureStartupFrame("closeable startup overlay inspection");
		if (capture == null) {
			return false;
		}
		ImageSearchResultData close = OpenCvPatternLocator.locatePattern(
				capture,
				TemplatesEnum.GAME_START_CLOSEABLE_OVERLAY_CLOSE.getTemplate(),
				CLOSEABLE_OVERLAY_AREA_TOP_LEFT,
				CLOSEABLE_OVERLAY_AREA_BOTTOM_RIGHT,
				STARTUP_PATTERN_THRESHOLD);
		if (!close.isFound()) {
			return false;
		}

		closeableOverlayDismissals++;
		lastVerifiedStartupState = "closeable startup overlay and concrete close control";
		logInfo("Closeable startup overlay verified from a fresh frame"
				+ "; closePattern="
				+ String.format(java.util.Locale.ROOT, "%.1f", close.getMatchScore())
				+ "%"
				+ "; dismissal=" + closeableOverlayDismissals
				+ "/" + MAX_CLOSEABLE_OVERLAY_DISMISSALS + ".");
		if (!tapInside(close)) {
			logWarning("Verified startup-overlay close area was unavailable for a safe tap; no fallback input sent.");
			return false;
		}
		lastVerifiedStartupState = "closeable startup overlay close action sent";
		return true;
	}

	private StoreRedirectEvidence inspectStoreRedirect(RawImageData capture) {
		boolean playStoreForeground = emuManager.isPackageRunning(EMULATOR_NUMBER, GOOGLE_PLAY_PACKAGE);
		return new StoreRedirectEvidence(
				isVerifiedPlayStoreRedirect(playStoreForeground, capture != null),
				playStoreForeground,
				capture != null);
	}

	static boolean isVerifiedPlayStoreRedirect(
			boolean playStoreForeground, boolean freshFrameCaptured) {
		return playStoreForeground && freshFrameCaptured;
	}

	private record StoreRedirectEvidence(boolean detected,
			boolean playStoreForeground, boolean freshFrameCaptured) {
	}

	private record MandatoryUpdateInspection(
			MandatoryUpdateScreenClassifier.Evidence evidence,
			ImageSearchResultData updateButton) {
	}

	private void deferPlayStoreRedirect(MandatoryUpdateScreenClassifier.Evidence updateEvidence,
			StoreRedirectEvidence storeRedirect) {
		LocalDateTime retryAt = LocalDateTime.now().plus(StartupRecoveryPolicy.PLAY_STORE_REDIRECT_COOLDOWN);
		ProfileCooldownException cooldown = playStoreRedirectCooldown(retryAt);
		logError("Initialization requires operator action for profile=" + profile.getName()
				+ ", expected=update completed outside Frostguard or home/world"
				+ ", observed=Google Play foreground package"
				+ ", lastAction=tapped verified in-game Update button"
				+ ", fallback=stop-game-and-release-slot, retryAt=" + retryAt
				+ ". Technical evidence: " + updateEvidence.technicalSummary()
				+ "; foreground package " + GOOGLE_PLAY_PACKAGE + ": "
				+ storeRedirect.playStoreForeground()
				+ "; fresh post-click frame captured: " + storeRedirect.freshFrameCaptured() + ".");
		throw cooldown;
	}

	static ProfileCooldownException playStoreRedirectCooldown(LocalDateTime retryAt) {
		String reason = "Google Play is waiting for the game update to be completed.";
		return new ProfileCooldownException(reason, retryAt, new ActionRequiredContext(
				"startup.play-store-redirect",
				"Complete the game update in Google Play",
				"Game update completed outside Frostguard or home/world screen",
				"Google Play foreground package after the in-game update action",
				"Tapped the detected in-game Update button, then captured a fresh frame while Google Play was foreground",
				"Stop the game, release the slot, and retry initialization after one hour"));
	}

	private void deferUnverifiedUpdatePostcondition(String reason, String technicalEvidence) {
		LocalDateTime retryAt = LocalDateTime.now().plus(StartupRecoveryPolicy.UPDATE_FOLLOW_UP_COOLDOWN);
		logError("Initialization paused for profile=" + profile.getName()
				+ ", expected=automatic update, resource download, Play Store redirect, or home/world"
				+ ", observed=unsupported update follow-up, lastAction=tapped verified Update button"
				+ ", fallback=stop-game-and-release-slot, retryAt=" + retryAt
				+ ", reason=" + reason + " Technical evidence: " + technicalEvidence + ".");
		throw new ProfileCooldownException(reason, retryAt);
	}

	private enum MandatoryUpdateResult {
		NOT_PRESENT,
		COMPLETED
	}

	/**
	 * Searches for home or world screen indicators.
	 * 
	 * @return true if home or world screen is found, false otherwise
	 */
	private boolean searchForHomeScreen() {
		RawImageData frame = captureStartupFrame("home/world pattern inspection");
		ImageSearchResultData home = emuManager.locatePattern(
				EMULATOR_NUMBER, frame, TemplatesEnum.GAME_HOME_FURNACE, STARTUP_PATTERN_THRESHOLD);
		ImageSearchResultData world = emuManager.locatePattern(
				EMULATOR_NUMBER, frame, TemplatesEnum.GAME_HOME_WORLD, STARTUP_PATTERN_THRESHOLD);

		return home.isFound() || world.isFound();
	}

	/**
	 * Chooses the foreground download when a resource-pack prompt blocks startup,
	 * then waits for the game to reach the home or world screen. Pressing Enter
	 * Game would allow automation to run while required assets are still missing.
	 *
	 * @return the observed prompt/download outcome
	 */
	private ResourceDownloadResult downloadRequiredResources() {
		RawImageData frame = captureStartupFrame("resource-download prompt inspection");
		ImageSearchResultData downloadNow = emuManager.locatePattern(
				EMULATOR_NUMBER, frame, TemplatesEnum.GAME_START_DOWNLOAD_NOW, STARTUP_PATTERN_THRESHOLD);

		if (!downloadNow.isFound()) {
			return ResourceDownloadResult.NOT_PRESENT;
		}

		logInfo("Resource download prompt detected. Downloading required resources before entering the game.");
		lastVerifiedStartupState = "resource-download prompt and Download Now button";
		tapInside(downloadNow);
		lastVerifiedStartupState = "resource-download action sent";

		for (int attempt = 1; attempt <= MAX_RESOURCE_DOWNLOAD_ATTEMPTS; attempt++) {
			sleepTask(RESOURCE_DOWNLOAD_POLL_DELAY_MS);
			if (searchForHomeScreen()) {
				logInfo("Required resource download completed.");
				return ResourceDownloadResult.COMPLETED;
			}
			checkForReconnectState();
			if (dismissWelcomeBackIfPresent()) {
				logInfo("Verified Welcome back dialog dismissed after the required resource download.");
			}
			if (dismissCloseableStartupOverlayIfPresent()) {
				logInfo("Verified closeable startup overlay dismissed after the required resource download.");
			}
			if (attempt % RESOURCE_DOWNLOAD_PROGRESS_LOG_INTERVAL == 0) {
				logInfo("Required resource download still in progress ("
						+ attempt * RESOURCE_DOWNLOAD_POLL_DELAY_MS / 1000 + " seconds elapsed).");
			}
		}

		logError("Required resource download did not reach the home screen within "
				+ RESOURCE_DOWNLOAD_TIMEOUT_MINUTES + " minutes.");
		return ResourceDownloadResult.TIMED_OUT;
	}

	private enum ResourceDownloadResult {
		NOT_PRESENT,
		COMPLETED,
		TIMED_OUT
	}

	/**
	 * Checks for reconnect state popup.
	 * 
	 * <p>
	 * If the reconnect popup is detected, throws ProfileInReconnectStateException
	 * to notify the queue that the profile needs to reconnect before automation
	 * can continue.
	 * 
	 * @throws ProfileInReconnectStateException if reconnect popup is found
	 */
	private void checkForReconnectState() {
		RawImageData frame = captureStartupFrame("reconnect-state inspection");
		ImageSearchResultData reconnect = emuManager.locatePattern(
				EMULATOR_NUMBER, frame, TemplatesEnum.GAME_HOME_RECONNECT, STARTUP_PATTERN_THRESHOLD);

		if (reconnect.isFound()) {
			throw new ProfileInReconnectStateException(
					"Profile " + profile.getName() + " is in a reconnect state and cannot execute the task: "
							+ taskName);
		}
	}

	/**
	 * Handles the case where home screen was not found after all attempts.
	 * 
	 * <p>
	 * Strategy:
	 * <ol>
	 * <li>Performs an ADB health check (restarts ADB if needed)</li>
	 * <li>Closes the emulator (clean slate, also invalidates ADB caches)</li>
	 * <li>Resets isStarted flag (will re-launch emulator on retry)</li>
	 * <li>Sets recurring=true (triggers immediate re-execution)</li>
	 * </ol>
	 * 
	 * <p>
	 * When the task re-executes after the one permitted restart, it will go
	 * through the full initialization flow again. If startup is still blocked,
	 * it enters a visible profile cooldown instead of restarting again.
	 */
	private void handleHomeScreenNotFound() {
		if (StartupRecoveryPolicy.forUnknownBlocker(emulatorRestartAttempts)
				== StartupRecoveryPolicy.UnknownBlockerAction.COOLDOWN_AND_RELEASE_SLOT) {
			deferUnknownStartupBlocker();
		}

		emulatorRestartAttempts++;
		logError("Home screen not found after " + MAX_HOME_SCREEN_ATTEMPTS
				+ " attempts. Performing bounded emulator recovery " + emulatorRestartAttempts
				+ "/" + StartupRecoveryPolicy.MAX_EMULATOR_RESTART_ATTEMPTS + ".");

		// Perform ADB health check before closing emulator
		// This may restart the ADB bridge if it's degraded
		logInfo("Performing ADB health check before emulator restart...");
		boolean adbHealthy = emuManager.performAdbHealthCheck(EMULATOR_NUMBER);
		if (adbHealthy) {
			logInfo("ADB health check passed. Proceeding with emulator restart.");
		} else {
			logWarning("ADB health check failed even after recovery attempts. "
					+ "Will still try to restart emulator.");
		}

		emuManager.closeEmulator(EMULATOR_NUMBER);
		isStarted = false;
		setRecurring(true); // Trigger immediate retry
	}

	private void deferUnknownStartupBlocker() {
		LocalDateTime retryAt = LocalDateTime.now().plus(StartupRecoveryPolicy.UNKNOWN_BLOCKER_COOLDOWN);
		String reason = "home/world remained unavailable after bounded emulator recovery";
		logError("Initialization blocked for profile=" + profile.getName()
				+ ", expected=home/world, observed=unknown-startup-blocker"
				+ ", lastAction=emulator-restart, recoveryAttempts=" + emulatorRestartAttempts
				+ "/" + StartupRecoveryPolicy.MAX_EMULATOR_RESTART_ATTEMPTS
				+ ", fallback=stop-game-and-release-slot, retryAt=" + retryAt
				+ ", reason=" + reason + ".");
		throw new ProfileCooldownException(reason, retryAt, new ActionRequiredContext(
				"startup.home-unavailable-after-restart",
				"Startup remains blocked after automatic recovery",
				"home/world",
				"unsupported startup screen",
				"One bounded emulator restart; no speculative screen input",
				"Stop the game, release the slot, and retry initialization after fifteen minutes"));
	}

	/**
	 * Verifies and switches character if needed.
	 * 
	 * <p>
	 * This method:
	 * <ol>
	 * <li>Verifies current character matches profile configuration</li>
	 * <li>If character doesn't match, switches to correct character</li>
	 * <li>If character matches or config not set, continues normally</li>
	 * </ol>
	 * 
	 * <p>
	 * If character switching fails (character not found), the emulator is closed
	 * and the method returns false. The queue will continue to the next profile.
	 * 
	 * <p>
	 * If character switch is successful, waits for game to reload and re-checks
	 * home screen before returning true.
	 * 
	 * @return true if character verification/switching succeeded, false if failed
	 */
	private boolean verifyAndSwitchCharacter() {
		// Check if character configuration is set
		String characterName = profile.getCharacterName();
		String characterId = profile.getCharacterId();
		
		// If no character info is configured, skip verification
		if ((characterName == null || characterName.isEmpty()) &&
			(characterId == null || characterId.isEmpty())) {
			logInfo("No character configuration found. Skipping character verification.");
			return true; // Continue with initialization
		}
		
		// Verify current character
		boolean characterMatches = characterSwitchHelper.verifyCurrentCharacter(profile);
		
		if (!characterMatches) {
			logInfo("Current character does not match profile configuration. Switching character...");
			
			// Switch to correct character
			boolean switchSuccess = characterSwitchHelper.switchToCharacter(profile);
			
			if (!switchSuccess) {
				// Character not found - emulator already closed by helper
				// According to requirements: do not retry, continue to next profile
				logError("Character switching failed. Character not found. Continuing to next profile.");
				// Reset isStarted flag since emulator was closed
				isStarted = false;
				// Do not set recurring=true - let queue continue to next profile
				return false;
			}
			
			// Character switch successful, wait for game to reload
			logInfo("Character switch successful. Waiting for game to reload...");
			sleepTask(CharacterSwitchHelper.CHARACTER_SWITCH_RELOAD_DELAY_MS);
			
			// Re-check home screen after character switch
			if (!waitForHomeScreen()) {
				// Home screen not found after character switch
				return false;
			}
			
			logInfo("Home screen verified after character switch.");
		} else {
			logInfo("Character verification passed - correct character is active.");
		}
		
		return true;
	}

	/**
	 * Handles successful initialization.
	 * 
	 * <p>
	 * Reads the current stamina value from the profile screen and stores it
	 * in the StaminaService for use by other tasks.
	 * 
	 * <p>
	 * After this method completes, the task ends without rescheduling
	 * (recurring=false is already set at the start of execute()).
	 */
	private void handleInitializationSuccess() {
		logInfo("Initialization successful. Reading initial stamina value.");
		staminaHelper.updateStaminaFromProfile();
		logInfo("Initialization task completed successfully.");
	}

	/**
	 * Specifies that this task can start from any screen location.
	 * Since this is the first task, the screen state is unknown.
	 * 
	 * @return LaunchPoint.ANY
	 */
	@Override
	protected LaunchPoint getRequiredStartLocation() {
		return LaunchPoint.ANY;
	}

	/**
	 * Indicates that this task does not provide daily mission progress.
	 * 
	 * @return false
	 */
	@Override
	public boolean provideDailyMissionProgress() {
		return false;
	}
}
