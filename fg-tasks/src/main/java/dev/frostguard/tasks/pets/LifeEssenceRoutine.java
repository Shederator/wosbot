package dev.frostguard.tasks.pets;

import java.awt.image.BufferedImage;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.ocr.TesseractOcrProvider;
import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;

public class LifeEssenceRoutine extends DelayedTask {

	// ===================== CONSTANTS =====================
	// Navigation coordinates
	private static final PointData SHOP_TAB_BUTTON = new PointData(670, 195);
	private static final PointData EXIT_BUTTON = new PointData(40, 30);

	// Scroll coordinates
	private static final PointData MENU_SCROLL_START = new PointData(220, 845);
	private static final PointData MENU_SCROLL_END = new PointData(220, 94);

	// Search areas
	private static final AreaData LIFE_ESSENCE_SEARCH_AREA = new AreaData(
			new PointData(0, 65),
			new PointData(720, 1280));

	// Retry limits
	private static final int MAX_NAVIGATION_FAILURES = 5;
	private static final int MAX_CLAIM_SEARCH_ATTEMPTS = 5;
	private static final int MAX_CLAIM_RESULTS = 5;

	// Default configuration values
	private static final int DEFAULT_OFFSET_MINUTES = 60;
	private static final int BACKOFF_MULTIPLIER = 5;
	private static final int MAX_BACKOFF_MINUTES = 30;

	// Configuration (loaded fresh each execution)
	private int offsetMinutes;
	private boolean buyWeeklyScroll;

	// Execution state (reset each execution)
	private int consecutiveFailures = 0;

	public LifeEssenceRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
		super(profile, tpDailyTask);
	}

	@Override
	protected void execute() {

		// Load configuration
		loadConfiguration();

		// Check if we should stop trying after too many failures
		if (shouldStopRetrying()) {
			return;
		}

		// Navigate to Life Essence menu
		if (!navigateToLifeEssenceMenu()) {
			handleNavigationFailure();
			return;
		}

		// Claim available Life Essence
		int claimedCount = claimLifeEssence();

		// Buy weekly free scroll if enabled and available
		if (buyWeeklyScroll && shouldBuyWeeklyScroll()) {
			buyWeeklyFreeScroll();
		}

		likeIsland();

		// Exit and reschedule
		exitAndReschedule(claimedCount);
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
			tapPoint(likeButton.getPoint());
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

	/**
	 * Check if task should stop retrying after consecutive failures
	 */
	private boolean shouldStopRetrying() {
		// Get consecutive failure count from profile config (persisted)
		Integer failures = profile.getConfig(
				ConfigurationKeyEnum.LIFE_ESSENCE_CONSECUTIVE_FAILURES_INT,
				Integer.class);

		consecutiveFailures = (failures != null) ? failures : 0;

		if (consecutiveFailures >= MAX_NAVIGATION_FAILURES) {
			logWarning("Maximum consecutive failures (" + MAX_NAVIGATION_FAILURES +
					") reached. Disabling task. Re-enable in configs to retry.");
			setRecurring(false);
			return true;
		}

		return false;
	}

	/**
	 * Navigate to the Life Essence menu
	 * 
	 * Navigation flow:
	 * 1. Open side menu shortcut
	 * 2. Switch to City tab
	 * 3. Scroll down to reveal Life Essence option
	 * 4. Tap Life Essence menu
	 * 
	 * @return true if navigation successful, false otherwise
	 */
	private boolean navigateToLifeEssenceMenu() {
		logInfo("Navigating to Life Essence menu");

		// Open side menu
		marchHelper.openLeftMenuCitySection(true);

		// Scroll down to reveal Life Essence menu
		logDebug("Scrolling to reveal Life Essence menu");
		swipe(MENU_SCROLL_START, MENU_SCROLL_END);
		sleepTask(1000); // Wait for scroll to settle

		// Search for Life Essence menu option
		ImageSearchResultData lifeEssenceMenu = templateSearchHelper.locatePattern(
				TemplatesEnum.LIFE_ESSENCE_MENU,
				SearchConfig.builder().build());

		// Try second swipe if not found
		if (!lifeEssenceMenu.isFound()) {
			logDebug("Life Essence menu not visible. Trying second swipe.");
			swipe(MENU_SCROLL_START, MENU_SCROLL_END);
			sleepTask(1000); // Wait for scroll

			lifeEssenceMenu = templateSearchHelper.locatePattern(
					TemplatesEnum.LIFE_ESSENCE_MENU,
					SearchConfig.builder().build());
		}

		if (!lifeEssenceMenu.isFound()) {
			logWarning("Life Essence menu not found after scrolling");
			return false;
		}

		// Open Life Essence menu
		logInfo("Life Essence menu found. Opening.");
		tapPoint(lifeEssenceMenu.getPoint());
		sleepTask(3000); // Wait for menu to fully load

		logInfo("Successfully navigated to Life Essence area");
		return true;
	}

	/**
	 * Claim all available Life Essence items
	 * 
	 * Strategy:
	 * - Search multiple times in case new essence appears after claiming
	 * - Stop early if no essence found on consecutive attempts
	 * 
	 * @return number of essence items claimed
	 */
	// matt/2026-08-14, caught live via screenshot: template matching kept missing real, visible
	// claimable badges (0 claimed while 2 badges sat unclaimed on screen). Root cause turned out to
	// be TWO different badge shapes in play (a white/orange rounded-square badge over buildings, and
	// a solid-orange speech-bubble/pin badge directly over the tree) -- AND both bounce/animate like
	// the Custom Armament Chest badge did earlier tonight, so even a freshly-recaptured template only
	// self-matched at ~0.84, under the 0.90 threshold. Rather than chase more template shapes, switch
	// to color-blob detection: both badge shapes share the same solid-orange fill, so scanning the
	// search area for orange pixel clusters and tapping each cluster's centroid finds every claimable
	// badge regardless of exact shape or animation phase. Same "always look for the [color], not the
	// exact frame" philosophy matt asked for with the red-dot fix.
	// matt/2026-08-14: first cut of the color-blob detection over-fired -- "Claimed 12" when only 2
	// real badges were on screen. Root cause: the search area also catches OTHER orange UI (an event
	// banner -- "The battle for the Fortress will start in..." -- was live on screen during that
	// run), and a wide banner sliver can easily clear a bare pixel-count threshold. Fix: also require
	// the blob's bounding box to be badge-shaped (compact, roughly square, ~30-75px each side) and
	// densely filled (fill ratio = pixelCount / bboxArea) -- live-measured real badges score
	// 0.51/0.58 fill in a ~45-55px square bbox, while a live-measured banner fragment scored 0.10
	// fill in a non-square bbox. This is a shape check, not just a color count.
	private static final int ORANGE_R_MIN = 200;
	private static final int ORANGE_G_MIN = 90;
	private static final int ORANGE_G_MAX = 180;
	private static final int ORANGE_B_MAX = 90;
	private static final int MIN_BLOB_PIXELS = 150;
	private static final int BADGE_MIN_DIMENSION = 30;
	private static final int BADGE_MAX_DIMENSION = 75;
	private static final double BADGE_MIN_FILL_RATIO = 0.35;

	private BufferedImage captureFrame() {
		try {
			RawImageData frame = emuManager.captureScreen(String.valueOf(EMULATOR_NUMBER));
			return TesseractOcrProvider.toBufferedImage(frame);
		} catch (Exception e) {
			logWarning("Failed to capture frame for badge scan: " + e.getMessage());
			return null;
		}
	}

	/**
	 * Scans the search area for solid-orange pixel clusters (the shared color of both claim badge
	 * shapes) and returns each cluster's centroid as a tap target. Flood-fill connected components,
	 * filtered by a minimum pixel count to reject noise.
	 */
	private List<PointData> findClaimableBadges(BufferedImage img) {
		int left = LIFE_ESSENCE_SEARCH_AREA.topLeft().col();
		int top = LIFE_ESSENCE_SEARCH_AREA.topLeft().row();
		int right = Math.min(LIFE_ESSENCE_SEARCH_AREA.bottomRight().col(), img.getWidth());
		int bottom = Math.min(LIFE_ESSENCE_SEARCH_AREA.bottomRight().row(), img.getHeight());

		int width = right - left;
		int height = bottom - top;
		if (width <= 0 || height <= 0) {
			return List.of();
		}

		boolean[] mask = new boolean[width * height];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int rgb = img.getRGB(left + x, top + y);
				int r = (rgb >> 16) & 0xFF;
				int g = (rgb >> 8) & 0xFF;
				int b = rgb & 0xFF;
				if (r > ORANGE_R_MIN && g > ORANGE_G_MIN && g < ORANGE_G_MAX && b < ORANGE_B_MAX) {
					mask[y * width + x] = true;
				}
			}
		}

		boolean[] visited = new boolean[width * height];
		int[] stackX = new int[width * height];
		int[] stackY = new int[width * height];
		int[] dx = {-1, 1, 0, 0};
		int[] dy = {0, 0, -1, 1};
		List<PointData> centroids = new ArrayList<>();

		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int idx = y * width + x;
				if (!mask[idx] || visited[idx]) {
					continue;
				}

				int sp = 0;
				stackX[sp] = x;
				stackY[sp] = y;
				sp++;
				visited[idx] = true;
				long sumX = 0;
				long sumY = 0;
				int count = 0;
				int minX = x, maxX = x, minY = y, maxY = y;

				while (sp > 0) {
					sp--;
					int cx = stackX[sp];
					int cy = stackY[sp];
					sumX += cx;
					sumY += cy;
					count++;
					if (cx < minX) minX = cx;
					if (cx > maxX) maxX = cx;
					if (cy < minY) minY = cy;
					if (cy > maxY) maxY = cy;

					for (int d = 0; d < 4; d++) {
						int nx = cx + dx[d];
						int ny = cy + dy[d];
						if (nx < 0 || nx >= width || ny < 0 || ny >= height) {
							continue;
						}
						int nidx = ny * width + nx;
						if (mask[nidx] && !visited[nidx]) {
							visited[nidx] = true;
							stackX[sp] = nx;
							stackY[sp] = ny;
							sp++;
						}
					}
				}

				int bboxWidth = maxX - minX + 1;
				int bboxHeight = maxY - minY + 1;
				double fillRatio = (double) count / (bboxWidth * bboxHeight);
				boolean badgeShaped = count >= MIN_BLOB_PIXELS
						&& bboxWidth >= BADGE_MIN_DIMENSION && bboxWidth <= BADGE_MAX_DIMENSION
						&& bboxHeight >= BADGE_MIN_DIMENSION && bboxHeight <= BADGE_MAX_DIMENSION
						&& fillRatio >= BADGE_MIN_FILL_RATIO;

				if (badgeShaped) {
					centroids.add(new PointData(left + (int) (sumX / count), top + (int) (sumY / count)));
				} else if (count >= MIN_BLOB_PIXELS) {
					logDebug("Rejected non-badge orange blob: size=" + count + " bbox=" + bboxWidth + "x"
							+ bboxHeight + " fill=" + String.format("%.2f", fillRatio));
				}
			}
		}

		return centroids;
	}

	private int claimLifeEssence() {
		logInfo("Searching for claimable Life Essence");
		int totalClaimed = 0;
		int emptySearches = 0;

		for (int searchAttempt = 1; searchAttempt <= MAX_CLAIM_SEARCH_ATTEMPTS; searchAttempt++) {
			logDebug("Claim search attempt " + searchAttempt + "/" + MAX_CLAIM_SEARCH_ATTEMPTS);

			BufferedImage frame = captureFrame();
			List<PointData> badges = frame != null ? findClaimableBadges(frame) : List.of();

			if (badges.isEmpty()) {
				emptySearches++;
				logDebug("No claimable essence found on attempt " + searchAttempt);

				// If we've had 2 consecutive empty searches, likely done
				if (emptySearches >= 2) {
					logDebug("Two consecutive empty searches. Stopping claim attempts.");
					break;
				}

				// Wait a bit in case essence is still loading
				sleepTask(500);
				continue;
			}

			// Reset empty counter if we found something
			emptySearches = 0;

			// Claim each found essence
			logDebug("Found " + badges.size() + " claimable essence items (color-blob, shape/animation-proof)");
			for (PointData badge : badges) {
				tapPoint(badge);
				sleepTask(500); // Wait for claim animation
				totalClaimed++;
			}

			// Wait for UI to update after claiming
			sleepTask(500);
		}

		logInfo("Claimed " + totalClaimed + " Life Essence items");
		return totalClaimed;
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
		tapPoint(SHOP_TAB_BUTTON);
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
			profile.setConfig(ConfigurationKeyEnum.LIFE_ESSENCE_NEXT_SCROLL_TIME_STRING,
					nextScrollTime.toString());
			setShouldUpdateConfig(true);
			logInfo("Next scroll purchase check scheduled for: " + nextScrollTime);

			tapPoint(EXIT_BUTTON);
			return;
		}

		// Click scroll to open purchase dialog
		logInfo("Weekly free scroll found. Opening purchase dialog.");
		tapPoint(scrollOffer.getPoint());
		sleepTask(500); // Wait for dialog

		// Search for buy button
		ImageSearchResultData buyButton = templateSearchHelper.locatePattern(
				TemplatesEnum.ISLAND_WEEKLY_FREE_SCROLL_BUY_BUTTON,
				SearchConfig.builder().build());

		if (!buyButton.isFound()) {
			logWarning("Buy button not found. Purchase may have failed.");
			pressBack(); // Close dialog
			sleepTask(500);
			tapPoint(EXIT_BUTTON); // Exit shop
			sleepTask(500);
			return;
		}

		// Confirm purchase
		tapPoint(buyButton.getPoint());
		sleepTask(500); // Wait for purchase to complete

		logInfo("Weekly free scroll purchased successfully");

		LocalDateTime nextScrollTime = calculateNextMondayReset();
		profile.setConfig(ConfigurationKeyEnum.LIFE_ESSENCE_NEXT_SCROLL_TIME_STRING,
				nextScrollTime.toString());
		logInfo("Next scroll purchase available at: " + nextScrollTime);

		// Exit shop
		tapPoint(EXIT_BUTTON);
		sleepTask(500);
	}

	/**
	 * Handle navigation failure by incrementing failure count and rescheduling
	 */
	private void handleNavigationFailure() {
		consecutiveFailures++;

		profile.setConfig(ConfigurationKeyEnum.LIFE_ESSENCE_CONSECUTIVE_FAILURES_INT, consecutiveFailures);

		logWarning("Navigation failed. Consecutive failures: " + consecutiveFailures +
				"/" + MAX_NAVIGATION_FAILURES);

		// Calculate backoff time: 5, 10, 15, 20, 25 minutes (max 30)
		int backoffMinutes = Math.min(BACKOFF_MULTIPLIER * consecutiveFailures, MAX_BACKOFF_MINUTES);
		LocalDateTime nextAttempt = LocalDateTime.now().plusMinutes(backoffMinutes);

		reschedule(nextAttempt);

		logInfo("Rescheduling with " + backoffMinutes + " minute backoff. Next attempt: " +
				GameTimeUtils.formatCountdown(nextAttempt));
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
		tapPoint(EXIT_BUTTON);
		sleepTask(1000); // Wait for menu close

		// Reset failure count on successful execution
		if (consecutiveFailures > 0) {
			consecutiveFailures = 0;
			profile.setConfig(ConfigurationKeyEnum.LIFE_ESSENCE_CONSECUTIVE_FAILURES_INT, 0);
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

}
