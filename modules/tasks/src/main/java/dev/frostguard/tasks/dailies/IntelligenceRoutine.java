package dev.frostguard.tasks.dailies;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.MarchResourceType;
import dev.frostguard.api.domain.MarchSlotState;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.TaskStateData;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.helper.DeploymentHelper;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.service.StaminaService;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.engine.service.TaskManagementService;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.List;

public class IntelligenceRoutine extends DelayedTask {

private static final int MIN_STAMINA_REQUIRED_FLOOR = 30;

private static final int SURVIVOR_STAMINA_COST_VALUE = 12;

private static final int JOURNEY_STAMINA_COST_VALUE = 10;

private static final int SMART_PROCESSING_MIN_IDLE_MARCHES_FOR_INTEL = 2;

private static final int MAX_INTEL_MARCH_SLOTS = 6;

private static final int MIN_INTEL_MARCH_SLOTS = 1;

private static final int SURVIVOR_BATCH_LIMIT = 2;

private static final long SURVIVOR_BATCH_PAUSE_MILLIS = 60_000L;

private static final PointData MARCH_RECALL_CONFIRM_TOP_LEFT = new PointData(446, 780);

private static final PointData MARCH_RECALL_CONFIRM_BOTTOM_RIGHT = new PointData(578, 800);

private static final MarchQueueRegion[] MARCH_QUEUE_REGIONS = {
		new MarchQueueRegion(new PointData(10, 342), new PointData(435, 407)),
		new MarchQueueRegion(new PointData(10, 415), new PointData(435, 480)),
		new MarchQueueRegion(new PointData(10, 488), new PointData(435, 553)),
		new MarchQueueRegion(new PointData(10, 561), new PointData(435, 626)),
		new MarchQueueRegion(new PointData(10, 634), new PointData(435, 699)),
		new MarchQueueRegion(new PointData(10, 707), new PointData(435, 772)),
};

private boolean marchQueueLimitReached;

private boolean autoJoinDisabledForIntel;

private boolean recallGatherTroopsFlow;

private boolean fcEra;

private boolean useSmartProcessing;

private boolean useFlag;

private Integer flagNumber;

private boolean beastsEnabled;

private boolean fireBeastsEnabled;

private boolean survivorCampsEnabled;

private boolean explorationsEnabled;

private boolean isAutoJoinTaskEnabled;

private boolean processingTask;

private int maxIntelMarches;

private int intelMarchesRemaining;

// Changed by pernerch | Date: 2026-07-04 | Why: keep runtime Intel capacity override when march capacity drops (e.g., VIP expiry).
private Integer intelMarchCapacityOverride;

private int survivorMissionsSincePause;

// Changed by pernerch | Date: 2026-07-02 | Why: ensure gather and autojoin can be resumed after Intel priority handling.
private boolean shouldRequeueGatherAfterIntel;

// Changed by pernerch | Date: 2026-07-02 | Why: restore autojoin after Intel processing so helping rallies continues.
private boolean shouldRequeueAutoJoinAfterIntel;

// Changed by pernerch | Date: 2026-07-02 | Why: track beast march dispatch to keep intel rescheduling accurate.
private boolean beastMarchSent;

private final List<LocalDateTime> intelBeastReturnTimes = new ArrayList<>();

private TaskStateData autoJoinTask;

private ResilientOcrExecutor<LocalDateTime> textHelper;

private SearchConfig searchConfigMultiple = SearchConfig.builder()
			.withMaxAttempts(3)
			.withDelay(300L)
			.withThreshold(90)
			.withMaxResults(10)
			.build();

public IntelligenceRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
		super(profile, tpTask);
	}

@Override
	protected void execute() {
		intelBeastReturnTimes.clear();


		hydrateConfiguration();


		processingTask = true;
		beastMarchSent = false;
		// Changed by pernerch | Date: 2026-07-04 | Why: reset per-run Intel march-capacity override before processing cycle starts.
		intelMarchCapacityOverride = null;
		shouldRequeueGatherAfterIntel = false;
		shouldRequeueAutoJoinAfterIntel = false;
		survivorMissionsSincePause = 0;

		autoJoinTask = TaskManagementService.shared().lookupTaskState(profile.getId(),
				TpDailyTaskEnum.ALLIANCE_AUTOJOIN.getId());
		isAutoJoinTaskEnabled = (autoJoinTask != null) ? true : false;

		if (!autoJoinDisabledForIntel && isAutoJoinTaskEnabled && autoJoinTask.isScheduled()) {
			logInfo(routineLogIntelligenceLine("Auto-join is enabled and scheduled, proceeding to disable it."));
			autoJoinDisabledForIntel = allianceHelper.disableAutoJoin();
			if (!autoJoinDisabledForIntel)
				logDebug(routineLogIntelligenceLine("Could not disable auto-join, proceeding anyway."));
			else {
				shouldRequeueAutoJoinAfterIntel = true;
			}
		}

		try {


		// Changed by pernerch | Date: 2026-07-02 | Why: check mission availability before
		// recalling gather marches, so Intel does not disrupt gathering when nothing is actionable.
		boolean intelMissionsDetected = hasAnyIntelMissionAvailableFlow();
		if (!intelMissionsDetected) {
			logInfo(routineLogIntelligenceLine("No intel missions detected. Skipping Intel run for now."));
			tryRescheduleFromCooldownFlow();
			processingTask = false;
			return;
		}

		// Changed by pernerch | Date: 2026-07-02 | Why: return to the world screen so gather marches can be recalled from the correct UI context.
		navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);

		// Changed by pernerch | Date: 2026-07-02 | Why: Intel must preempt gather for full-march execution when smart processing is disabled.
		if (!useSmartProcessing || recallGatherTroopsFlow) {
			logInfo(routineLogIntelligenceLine("Intel gather-priority mode active (smart=" + useSmartProcessing
					+ ", recall=" + recallGatherTroopsFlow + "). Recalling all gather troops..."));
			recallGatherTroopsFlow();
			shouldRequeueGatherAfterIntel = true;
			logInfo(routineLogIntelligenceLine("All gather troops recalled. Proceeding with intel processing."));
		} else {
			// Changed by pernerch | Date: 2026-07-02 | Why: in smart processing mode, free Intel marches by recalling long-running duplicate gather marches first.
			int recalledDuplicateMarches = recallDuplicateGatherMarchesForSmartProcessingFlow();
			if (recalledDuplicateMarches > 0) {
				shouldRequeueGatherAfterIntel = true;
				logInfo(routineLogIntelligenceLine("Smart processing recalled " + recalledDuplicateMarches
						+ " duplicate gather march(es) to free Intel capacity."));
			}
		}

		initializeIntelMarchCountersFlow();

		while (processingTask) {
			boolean anyIntelProcessed = false;
			boolean nonBeastIntelProcessed = false;
			beastMarchSent = false;


			navigationHelper.ensureCorrectScreenLocation(LaunchPoint.WORLD);


			MarchesAvailable marchesAvailable = inspectMarchAvailability();
			marchQueueLimitReached = !marchesAvailable.available();


			redeemCompletedMissions();


			if (!hasEnoughStaminaFlow()) {
				processingTask = false;
				return;

			}


			if (beastsEnabled && shouldProcessBeastsFlow()) {
				if (handleBeastIntel()) {
					anyIntelProcessed = true;
				}
			}


			if (survivorCampsEnabled) {
				intelScreenHelper.ensureOnIntelScreen();
				logInfo(routineLogIntelligenceLine("Scanning for survivor camps using grayscale matching."));
				TemplatesEnum survivorTemplate = fcEra ? TemplatesEnum.INTEL_SURVIVOR_GRAYSCALE_FC
						: TemplatesEnum.INTEL_SURVIVOR_GRAYSCALE;
				if (seekAndProcessGrayscale(survivorTemplate, this::handleSurvivor)) {
					anyIntelProcessed = true;
					nonBeastIntelProcessed = true;
				}
			}


			if (explorationsEnabled) {
				intelScreenHelper.ensureOnIntelScreen();
				logInfo(routineLogIntelligenceLine("Scanning for explorations using grayscale matching."));
				TemplatesEnum journeyTemplate = fcEra ? TemplatesEnum.INTEL_JOURNEY_GRAYSCALE_FC
						: TemplatesEnum.INTEL_JOURNEY_GRAYSCALE;
				if (seekAndProcessGrayscale(journeyTemplate, this::handleJourney)) {
					anyIntelProcessed = true;
					nonBeastIntelProcessed = true;
				}
			}


			manageRescheduling(anyIntelProcessed, nonBeastIntelProcessed, marchesAvailable);
		}
		} finally {
			finalizePostIntelTaskFlow();
		}

	}

private boolean hasAnyIntelMissionAvailableFlow() {
		// Changed by pernerch | Date: 2026-07-02 | Why: lightweight pre-check to avoid unnecessary
		// gather recalls when Intel has no visible missions to process.
		intelScreenHelper.ensureOnIntelScreen();

		if (fireBeastsEnabled && templateSearchHelper
				.locatePatternMono(TemplatesEnum.INTEL_FIRE_BEAST, SearchConfigConstants.DEFAULT_SINGLE)
				.isFound()) {
			return true;
		}

		if (beastsEnabled) {
			TemplatesEnum[] beastTemplates = fcEra
					? new TemplatesEnum[] { TemplatesEnum.INTEL_BEAST_GRAYSCALE_FC, TemplatesEnum.INTEL_BEAST_GRAYSCALE_FC1 }
					: new TemplatesEnum[] { TemplatesEnum.INTEL_BEAST_GRAYSCALE };

			for (TemplatesEnum template : beastTemplates) {
				if (templateSearchHelper.locatePatternMono(template, SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
					return true;
				}
			}
		}

		if (survivorCampsEnabled) {
			TemplatesEnum survivorTemplate = fcEra ? TemplatesEnum.INTEL_SURVIVOR_GRAYSCALE_FC
					: TemplatesEnum.INTEL_SURVIVOR_GRAYSCALE;
			if (templateSearchHelper.locatePatternMono(survivorTemplate, SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
				return true;
			}
		}

		if (explorationsEnabled) {
			TemplatesEnum journeyTemplate = fcEra ? TemplatesEnum.INTEL_JOURNEY_GRAYSCALE_FC
					: TemplatesEnum.INTEL_JOURNEY_GRAYSCALE;
			if (templateSearchHelper.locatePatternMono(journeyTemplate, SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
				return true;
			}
		}

		return false;
	}

@Override
	protected LaunchPoint getRequiredStartLocation() {
		return LaunchPoint.WORLD;
	}

@Override
	protected boolean consumesStamina() {
		return true;
	}

public record MarchesAvailable(boolean available, LocalDateTime rescheduleTo) {
	}

private void tryRescheduleFromCooldownFlow() {
		int completedRewardsClaimed = redeemCompletedMissions();
		if (completedRewardsClaimed > 0) {
			logInfo(routineLogIntelligenceLine(
					"Claimed " + completedRewardsClaimed + " completed Intel reward(s) before cooldown scheduling."));
		}

		logInfo(routineLogIntelligenceLine("Zero intel items detected. Attempting to read the cooldown timer."));

		LocalDateTime cooldown = readCooldownFlow(
				CommonGameAreas.INTEL_COOLDOWN_WITH_MARKERS_OCR_AREA, "marker-map");
		if (cooldown == null) {
			logDebug(routineLogIntelligenceLine(
					"Cooldown was not readable in the marker-map banner. Trying the empty-map layout."));
			cooldown = readCooldownFlow(CommonGameAreas.INTEL_COOLDOWN_EMPTY_MAP_OCR_AREA, "empty-map");
		}

		if (cooldown == null) {
			logWarning(routineLogIntelligenceLine("Could not read cooldown timer via OCR. Planning next run in 10 minutes."));
			reschedule(LocalDateTime.now().plusMinutes(10));
			pressBack();
			return;
		}

		reschedule(cooldown);
		pressBack();

		logInfo(routineLogIntelligenceLine("Zero new intel detected. Planning next run task to run at: " + cooldown.format(DATETIME_FORMATTER)));
	}

private LocalDateTime readCooldownFlow(AreaData area, String layout) {
		LocalDateTime cooldown = textHelper.attemptRecognition(
				area,
				3,
				200L,
				CommonOCRSettings.INTEL_COOLDOWN_SETTINGS,
				GameTimeUtils::isAcceptedFormat,
				text -> LocalDateTime.now().plus(GameTimeUtils.parseDuration(text)));
		if (cooldown != null) {
			logInfo(routineLogIntelligenceLine("Cooldown timer read from " + layout + " layout."));
		}
		return cooldown;
	}

private String routineLogIntelligenceLine(String note) {
        return "IntelligenceRoutine | " + note;
    }

private MarchesAvailable inspectMarchAvailability() {
		if (useSmartProcessing) {
			return resolveMarchesAvailable();
		} else {
			boolean available = intelMarchesRemaining > 0;
			return new MarchesAvailable(available, LocalDateTime.now());
		}
	}

private boolean shouldProcessBeastsFlow() {

		if (intelMarchesRemaining <= 0) {
			logInfo(routineLogIntelligenceLine("Internal Intel march counter exhausted (0/" + maxIntelMarches
					+ "). Skipping Beast/Fire Beast until marches return."));
			marchQueueLimitReached = true;
			return false;
		}


		if (useFlag && beastMarchSent) {
			logInfo(routineLogIntelligenceLine("Beast march already sent (flag mode), skipping beast search."));
			return false;
		}

		return true;
	}

private boolean seekAndProcessGrayscale(TemplatesEnum template, Consumer<ImageSearchResultData> processMethod) {
		logInfo(routineLogIntelligenceLine("Scanning for grayscale template '" + template + "'"));
		ImageSearchResultData result = templateSearchHelper.locatePatternMono(template, SearchConfigConstants.SINGLE_WITH_RETRIES);

		if (result.isFound()) {
			logInfo(routineLogIntelligenceLine("Grayscale template detected: " + template));
			processMethod.accept(result);
			return true;
		}
		logWarning(routineLogIntelligenceLine("Grayscale template not detected: " + template));
		return false;
	}

private MarchesAvailable resolveMarchesAvailable() {
		List<MarchSlotState> slots = marchHelper.readMarchQueue();
		IntelMarchAvailabilityPolicy.Decision decision = IntelMarchAvailabilityPolicy.assess(slots);
		if (slots.isEmpty()) {
			logWarning(routineLogIntelligenceLine(
					"March queue could not be read. Treating capacity as occupied and retrying in 5 minutes."));
			return new MarchesAvailable(false, LocalDateTime.now().plus(decision.retryDelay()));
		}

		if (decision.available()) {
			logInfo(routineLogIntelligenceLine(
					"Shared March Queue reader found " + decision.idleCount() + " idle slot(s)."));
			return new MarchesAvailable(true, null);
		}

		LocalDateTime now = LocalDateTime.now();
		LocalDateTime retryAt = now.plus(decision.retryDelay());
		if (decision.exactRelease()) {
			logInfo(routineLogIntelligenceLine(
					"No idle slots. Earliest exact returning-march release: " + retryAt));
			return new MarchesAvailable(false, retryAt);
		}

		logInfo(routineLogIntelligenceLine(
				"No idle slot or exact release countdown. Retrying in 5 minutes; lower-bound attack, rally, "
						+ "and gather timers are not treated as slot-free times."));
		return new MarchesAvailable(false, retryAt);
	}

private int redeemCompletedMissions() {
		intelScreenHelper.ensureOnIntelScreen();
		logInfo(routineLogIntelligenceLine("Scanning for completed missions to claim."));
		int claimedRewards = 0;

		for (int i = 0; i < 2; i++) {
			logDebug(routineLogIntelligenceLine("Scanning for completed missions. Attempt " + (i + 1) + "."));
			List<ImageSearchResultData> completed = templateSearchHelper.locateAllPatterns(
					TemplatesEnum.INTEL_COMPLETED,
					searchConfigMultiple);

			if (completed.isEmpty()) {
				logInfo(routineLogIntelligenceLine("Zero completed missions detected on attempt " + (i + 1) + "."));
				continue;
			}

			logInfo(routineLogIntelligenceLine("Detected " + completed.size() + " completed missions. Collecting them now."));

			for (ImageSearchResultData completedMission : completed) {
				tapInside(completedMission);
				claimedRewards++;
				sleepTask(500);
				tapInside(new PointData(700, 1270), new PointData(710, 1280), 3, 100);
				sleepTask(500);
			}
		}

		return claimedRewards;
	}

private void requeueGatherTasksFlow() {
		logInfo(routineLogIntelligenceLine("Re-queueing gather tasks after Intel completion..."));


		TaskQueue queue = dev.frostguard.engine.service.ScheduleService.obtain().getCoordinator().getQueue(profile.getId());
		if (queue == null) {
			logError(routineLogIntelligenceLine("Could not access task queue for profile " + profile.getName()));
			return;
		}


		if (profile.getConfig(ConfigurationKeyEnum.GATHER_TASK_BOOL, Boolean.class)) {
			queue.runNow(TpDailyTaskEnum.GATHER_RESOURCES, true);
			logInfo(routineLogIntelligenceLine("Re-queued Gather Resources task"));
		}

		sleepTask(500);
	}

private void requeueAutoJoinTaskFlow() {
		if (!isAutoJoinTaskEnabled || autoJoinTask == null) {
			return;
		}

		Boolean autoJoinEnabled = profile.getConfig(ConfigurationKeyEnum.ALLIANCE_AUTOJOIN_BOOL, Boolean.class);
		if (!Boolean.TRUE.equals(autoJoinEnabled)) {
			return;
		}

		TaskQueue queue = dev.frostguard.engine.service.ScheduleService.obtain().getCoordinator().getQueue(profile.getId());
		if (queue == null) {
			logError(routineLogIntelligenceLine("Could not access task queue to re-queue autojoin for profile " + profile.getName()));
			return;
		}

		queue.runNow(TpDailyTaskEnum.ALLIANCE_AUTOJOIN, true);
		logInfo(routineLogIntelligenceLine("Re-queued Alliance Autojoin task after Intel completion."));
	}

private void finalizePostIntelTaskFlow() {
		if (shouldRequeueGatherAfterIntel) {
			requeueGatherTasksFlow();
		}

		if (shouldRequeueAutoJoinAfterIntel) {
			requeueAutoJoinTaskFlow();
		}

		autoJoinDisabledForIntel = false;
		shouldRequeueGatherAfterIntel = false;
		shouldRequeueAutoJoinAfterIntel = false;
	}

private void recallGatherTroopsFlow() {
		int maxRetries = 120;

		int attempt = 0;

		logInfo(routineLogIntelligenceLine("Recalling all gather troops to the city..."));
		SearchConfig marchStatusSearchConfig = SearchConfig.builder()
				.withThreshold(90)
				.withMaxAttempts(5)
				.withDelay(200L)
				.build();

		while (attempt < maxRetries) {
			attempt++;
			ImageSearchResultData returningArrow = locatePatternWithMonoFallback(
					TemplatesEnum.MARCHES_AREA_RECALL_BUTTON,
					marchStatusSearchConfig);
			ImageSearchResultData marchView = locatePatternWithMonoFallback(
					TemplatesEnum.MARCHES_AREA_VIEW_BUTTON,
					marchStatusSearchConfig);
			ImageSearchResultData marchSpeedup = locatePatternWithMonoFallback(
					TemplatesEnum.MARCHES_AREA_SPEEDUP_BUTTON,
					marchStatusSearchConfig);

			boolean foundReturning = returningArrow != null && returningArrow.isFound();
			boolean foundView = marchView != null && marchView.isFound();
			boolean foundSpeedup = marchSpeedup != null && marchSpeedup.isFound();

			logDebug(routineLogIntelligenceLine(String.format(
					"recallGatherTroopsFlow status => returning:%b view:%b speedup:%b (attempt %d)",
					foundReturning, foundView, foundSpeedup, attempt)));

			if (!foundReturning && !foundView && !foundSpeedup) {
				// Changed by pernerch | Date: 2026-07-04 | Why: replace hard-coded march ceiling with configured/adjusted runtime capacity.
				int configuredMarches = resolveConfiguredIntelMarchesFlow();
				int idleMarches = countIdleMarchesFlow();
				if (idleMarches >= configuredMarches) {
					logInfo(routineLogIntelligenceLine("Zero march indicators detected and all marches are idle ("
							+ idleMarches + "/" + configuredMarches + "). Recall is complete."));
					return;
				}

				int vipAdjustedMarches = Math.max(MIN_INTEL_MARCH_SLOTS, configuredMarches - 1);
				// Changed by pernerch | Date: 2026-07-04 | Why: treat configured-1 idle marches with zero indicators as possible VIP slot expiry.
				if (configuredMarches > MIN_INTEL_MARCH_SLOTS && idleMarches == vipAdjustedMarches) {
					applyIntelMarchCapacityOverrideFlow(vipAdjustedMarches,
							"No recall/view/speedup indicators and only " + idleMarches + "/" + configuredMarches
									+ " marches idle, possibly VIP expired.");
					logInfo(routineLogIntelligenceLine("Recall treated as complete with adjusted Intel march capacity "
							+ vipAdjustedMarches + "/" + configuredMarches + "."));
					return;
				}

				logWarning(routineLogIntelligenceLine(
						"Zero recall/view/speedup indicators detected, but only " + idleMarches +
						"/" + configuredMarches + " marches are idle. Retrying recall to avoid false success."));
				sleepTask(600);
				continue;
			}

			if (foundReturning) {
				logInfo(routineLogIntelligenceLine("Returning arrow detected - attempting to tap recall button"));
				tapInside(returningArrow.getPoint(), returningArrow.getPoint(), 1, 300);
				tapInside(new PointData(446, 780), new PointData(578, 800), 1, 200);
			}

			if (foundView || foundSpeedup) {
				logInfo(routineLogIntelligenceLine("Troops are still marching - waiting for them to return"));
				sleepTask(1000);
			}

			sleepTask(200);
		}

		logError(routineLogIntelligenceLine("recallGatherTroopsFlow exceeded max attempts (" + maxRetries + "), exiting to avoid deadlock"));
	}

	// Changed by pernerch | Date: 2026-07-02 | Why: keep detection and recall click in the same
	// tab-open cycle so a found recall button can be acted on immediately without UI drift.
	private TabRecallResult inspectAndRecallForTabFlow(boolean cityTab, SearchConfig searchConfig) {
		int tapped = 0;
		marchHelper.openLeftMenuCitySection(cityTab);
		sleepTask(350);
		try {
			ImageSearchResultData returningArrow = locatePatternWithMonoFallback(
					TemplatesEnum.MARCHES_AREA_RECALL_BUTTON,
					searchConfig);
			ImageSearchResultData marchView = locatePatternWithMonoFallback(
					TemplatesEnum.MARCHES_AREA_VIEW_BUTTON,
					searchConfig);
			ImageSearchResultData marchSpeedup = locatePatternWithMonoFallback(
					TemplatesEnum.MARCHES_AREA_SPEEDUP_BUTTON,
					searchConfig);

			// Changed by pernerch | Date: 2026-07-02 | Why: gather recall must act on the full set
			// of visible recall buttons in the opened tab instead of repeatedly probing one button.
			if (returningArrow != null && returningArrow.isFound()) {
				List<ImageSearchResultData> recallButtons = locateAllPatternsWithMonoFallback(
						TemplatesEnum.MARCHES_AREA_RECALL_BUTTON,
						SearchConfig.builder()
								.withThreshold(searchConfig.getThreshold())
								.withMaxAttempts(searchConfig.getMaxAttempts())
								.withDelay(searchConfig.getDelayBetweenAttempts())
								.withMaxResults(6)
								.build());

				Map<String, ImageSearchResultData> uniqueRecallButtons = new LinkedHashMap<>();
				for (ImageSearchResultData recallButton : recallButtons) {
					if (recallButton != null && recallButton.isFound()) {
						String key = recallButton.getPoint().getX() + ":" + recallButton.getPoint().getY();
						uniqueRecallButtons.putIfAbsent(key, recallButton);
					}
				}

				for (ImageSearchResultData recallButton : uniqueRecallButtons.values()) {
					tapInside(recallButton.getPoint(), recallButton.getPoint(), 1, 300);
					tapInside(new PointData(446, 780), new PointData(578, 800), 1, 200);
					sleepTask(500);
					tapped++;
				}
			} else if (marchView != null && marchView.isFound() || marchSpeedup != null && marchSpeedup.isFound()) {
				List<GatherMarchCandidate> visibleRows = collectVisibleGatherRowsForRecallFlow();
				for (GatherMarchCandidate row : visibleRows) {
					if (tapGatherRowThenRecallFlow(row, searchConfig)) {
						tapped++;
					}
				}
			}

			return new TabRecallResult(new MarchStatusShape(
					returningArrow != null && returningArrow.isFound(),
					marchView != null && marchView.isFound(),
					marchSpeedup != null && marchSpeedup.isFound()), tapped);
		} finally {
			marchHelper.closeLeftMenu();
		}
	}

private record MarchStatusShape(boolean hasRecallButton, boolean hasViewButton, boolean hasSpeedupButton) {
	}

private record TabRecallResult(MarchStatusShape status, int tappedButtons) {
	}

private boolean tapGatherRowThenRecallFlow(GatherMarchCandidate candidate, SearchConfig searchConfig) {
		tapInside(candidate.rowPoint(), candidate.rowPoint(), 1, 300);
		sleepTask(350);

		ImageSearchResultData recallButton = locatePatternWithMonoFallback(
				TemplatesEnum.MARCHES_AREA_RECALL_BUTTON,
				SearchConfig.builder()
						.withThreshold(searchConfig.getThreshold())
						.withMaxAttempts(searchConfig.getMaxAttempts())
						.withDelay(searchConfig.getDelayBetweenAttempts())
						.build());

		if (recallButton == null || !recallButton.isFound()) {
			return false;
		}

		tapInside(recallButton.getPoint(), recallButton.getPoint(), 1, 300);
		tapInside(MARCH_RECALL_CONFIRM_TOP_LEFT, MARCH_RECALL_CONFIRM_BOTTOM_RIGHT, 1, 200);
		sleepTask(500);
		return true;
	}

private List<GatherMarchCandidate> collectVisibleGatherRowsForRecallFlow() {
		return marchHelper.readVisibleMarchQueue().stream()
				.filter(MarchSlotState::isGather)
				.map(this::gatherCandidateFromSlot)
				.filter(java.util.Objects::nonNull)
				.sorted(Comparator.comparing(GatherMarchCandidate::returnAt).reversed())
				.toList();
	}

	private ImageSearchResultData locatePatternWithMonoFallback(TemplatesEnum template, SearchConfig searchConfig) {
		ImageSearchResultData result = templateSearchHelper.locatePattern(template, searchConfig);
		if (result != null && result.isFound()) {
			return result;
		}
		return templateSearchHelper.locatePatternMono(template, searchConfig);
	}

	private List<ImageSearchResultData> locateAllPatternsWithMonoFallback(TemplatesEnum template, SearchConfig searchConfig) {
		List<ImageSearchResultData> results = templateSearchHelper.locateAllPatterns(template, searchConfig);
		if (results != null && !results.isEmpty()) {
			return results;
		}
		return templateSearchHelper.locateAllPatternsMono(template, searchConfig);
	}

private int recallDuplicateGatherMarchesForSmartProcessingFlow() {
		int recalled = 0;
		int idleMarches = countIdleMarchesFlow();

		while (idleMarches < SMART_PROCESSING_MIN_IDLE_MARCHES_FOR_INTEL) {
			GatherMarchCandidate candidate = findLongestDuplicateGatherMarchFlow();
			if (candidate == null) {
				break;
			}

			if (!recallGatherMarchByQueueFlow(candidate.queueIndex())) {
				break;
			}

			recalled++;
			sleepTask(250);
			idleMarches = countIdleMarchesFlow();
		}

		return recalled;
	}

private GatherMarchCandidate findLongestDuplicateGatherMarchFlow() {
		marchHelper.openLeftMenuCitySection(false);
		try {
			Map<MarchResourceType, List<GatherMarchCandidate>> groupedByType = marchHelper.readVisibleMarchQueue()
					.stream()
					.filter(MarchSlotState::isGather)
					.map(this::gatherCandidateFromSlot)
					.filter(java.util.Objects::nonNull)
					.collect(java.util.stream.Collectors.groupingBy(GatherMarchCandidate::type));

			List<GatherMarchCandidate> duplicates = new ArrayList<>();
			for (List<GatherMarchCandidate> candidates : groupedByType.values()) {
				if (candidates.size() >= 2) {
					duplicates.addAll(candidates);
				}
			}

			if (duplicates.isEmpty()) {
				logInfo(routineLogIntelligenceLine("Smart processing found no duplicate gather marches to recall."));
				return null;
			}

			GatherMarchCandidate selected = duplicates.stream()
					.max(Comparator.comparing(GatherMarchCandidate::returnAt))
					.orElse(null);

			if (selected != null) {
				logInfo(routineLogIntelligenceLine("Smart processing selected duplicate "
						+ selected.type().name().toLowerCase()
						+ " gather march on queue #" + (selected.queueIndex() + 1)
						+ " with longest return time for recall."));
			}

			return selected;
		} finally {
			marchHelper.closeLeftMenu();
		}
	}

private boolean recallGatherMarchByQueueFlow(int queueIndex) {
		marchHelper.openLeftMenuCitySection(false);
		try {
			List<ImageSearchResultData> recallButtons = templateSearchHelper.locateAllPatterns(
					TemplatesEnum.MARCHES_AREA_RECALL_BUTTON,
					SearchConfig.builder()
							.withArea(new AreaData(MARCH_QUEUE_REGIONS[0].topLeft(), MARCH_QUEUE_REGIONS[MARCH_QUEUE_REGIONS.length - 1].bottomRight()))
							.withMaxAttempts(3)
							.withDelay(3)
							.withMaxResults(MARCH_QUEUE_REGIONS.length)
							.build());

			if (recallButtons.isEmpty()) {
				return false;
			}

			int targetRowCenterY = (MARCH_QUEUE_REGIONS[queueIndex].topLeft().getY() + MARCH_QUEUE_REGIONS[queueIndex].bottomRight().getY()) / 2;
			ImageSearchResultData bestRowButton = recallButtons.stream()
					.min(Comparator.comparingInt(button -> Math.abs(button.getPoint().getY() - targetRowCenterY)))
					.orElse(null);

			if (bestRowButton == null) {
				return false;
			}

			tapInside(bestRowButton.getPoint(), bestRowButton.getPoint(), 1, 200);
			tapInside(MARCH_RECALL_CONFIRM_TOP_LEFT, MARCH_RECALL_CONFIRM_BOTTOM_RIGHT, 1, 200);
			logInfo(routineLogIntelligenceLine("Recalled gather march from queue #" + (queueIndex + 1)
					+ " for smart Intel prioritization."));
			return true;
		} finally {
			marchHelper.closeLeftMenu();
		}
	}

private int countIdleMarchesFlow() {
		List<MarchSlotState> slots = marchHelper.readMarchQueue();
		if (slots.isEmpty()) {
			logWarning(routineLogIntelligenceLine("Could not classify march slots while counting idle capacity."));
			return 0;
		}
		return (int) slots.stream().filter(MarchSlotState::isIdle).count();
}

private GatherMarchCandidate gatherCandidateFromSlot(MarchSlotState slot) {
		int queueIndex = slot.slot() - 1;
		MarchResourceType resource = slot.resourceType();
		if (queueIndex < 0 || queueIndex >= MARCH_QUEUE_REGIONS.length
				|| resource == null || resource == MarchResourceType.UNKNOWN) {
			return null;
		}
		MarchQueueRegion row = MARCH_QUEUE_REGIONS[queueIndex];
		PointData rowPoint = new PointData(
				(row.topLeft().getX() + row.bottomRight().getX()) / 2,
				(row.topLeft().getY() + row.bottomRight().getY()) / 2);
		LocalDateTime returnEstimate = LocalDateTime.now().plus(
				slot.countdown() == null ? java.time.Duration.ofMinutes(5) : slot.countdown());
		return new GatherMarchCandidate(resource, queueIndex, returnEstimate, rowPoint);
	}

private record MarchQueueRegion(PointData topLeft, PointData bottomRight) {
	}

private record GatherMarchCandidate(MarchResourceType type, int queueIndex, LocalDateTime returnAt, PointData rowPoint) {
	}

private void hydrateConfiguration() {
		this.fcEra = profile.getConfig(ConfigurationKeyEnum.INTEL_FC_ERA_BOOL, Boolean.class);
		this.useSmartProcessing = profile.getConfig(ConfigurationKeyEnum.INTEL_SMART_PROCESSING_BOOL, Boolean.class);
		this.recallGatherTroopsFlow = profile.getConfig(ConfigurationKeyEnum.INTEL_RECALL_GATHER_TROOPS_BOOL,
				Boolean.class);
		this.useFlag = profile.getConfig(ConfigurationKeyEnum.INTEL_USE_FLAG_BOOL, Boolean.class);
		this.flagNumber = useFlag ? profile.getConfig(ConfigurationKeyEnum.INTEL_BEASTS_FLAG_INT, Integer.class) : null;
		this.beastsEnabled = profile.getConfig(ConfigurationKeyEnum.INTEL_BEASTS_BOOL, Boolean.class);
		this.fireBeastsEnabled = profile.getConfig(ConfigurationKeyEnum.INTEL_FIRE_BEAST_BOOL, Boolean.class);
		this.survivorCampsEnabled = profile.getConfig(ConfigurationKeyEnum.INTEL_CAMP_BOOL, Boolean.class);
		this.explorationsEnabled = profile.getConfig(ConfigurationKeyEnum.INTEL_EXPLORATION_BOOL, Boolean.class);
		this.textHelper = new ResilientOcrExecutor<>(provider);

		logDebug(routineLogIntelligenceLine("Configuration loaded: fcEra=" + fcEra + ", useSmartProcessing=" + useSmartProcessing +
				", recallGatherTroopsFlow=" + recallGatherTroopsFlow + ", useFlag=" + useFlag + ", beastsEnabled="
				+ beastsEnabled));
	}

private boolean hasEnoughStaminaFlow() {
		int staminaValue = StaminaService.getServices().getCurrentStamina(profile.getId());

		if (staminaValue < MIN_STAMINA_REQUIRED_FLOOR) {
			logWarning(routineLogIntelligenceLine("Not enough stamina to process intel. Current stamina: " + staminaValue +
					". Required: " + MIN_STAMINA_REQUIRED_FLOOR + "."));
			long minutesToRegen = StaminaService.minutesToRegenerate(
					staminaValue, MIN_STAMINA_REQUIRED_FLOOR);
			LocalDateTime rescheduleTime = LocalDateTime.now().plusMinutes(minutesToRegen);
			deferForStamina(MIN_STAMINA_REQUIRED_FLOOR, MIN_STAMINA_REQUIRED_FLOOR, rescheduleTime);
			return false;
		}
		return true;
	}

private void manageRescheduling(boolean anyIntelProcessed, boolean nonBeastIntelProcessed,
			MarchesAvailable marchesAvailable) {
		sleepTask(500);

		boolean missionsStillAvailable = hasAnyIntelMissionAvailableFlow();
		boolean nonMarchBoundMissionAvailable = hasNonMarchBoundIntelMissionAvailableFlow();
		boolean onlyMarchBoundMissionsLeft = missionsStillAvailable && !nonMarchBoundMissionAvailable;

		if (onlyMarchBoundMissionsLeft && marchQueueLimitReached) {
			LocalDateTime waitUntil = resolveMarchReturnWaitTimeFlow(marchesAvailable);
			reschedule(waitUntil);
			logInfo(routineLogIntelligenceLine("Only march-bound intel missions remain and all Intel marches are occupied. "
					+ "Waiting for march return and rescheduling Intel at: " + waitUntil.format(DATETIME_FORMATTER)));
			processingTask = false;
			return;
		}

		if (!missionsStillAvailable) {
			logInfo(routineLogIntelligenceLine("No intel missions found after re-scan. Intel run is complete for now."));
			tryRescheduleFromCooldownFlow();
			processingTask = false;
			return;
		}

		if (!anyIntelProcessed) {
			logInfo(routineLogIntelligenceLine("Missions still exist but none were processed this cycle. Retrying immediately."));
		}

		if (missionsStillAvailable && marchQueueLimitReached && nonMarchBoundMissionAvailable) {
			logInfo(routineLogIntelligenceLine("Intel march-bound missions are blocked, but Survivor/Journey missions remain. Continuing without march consumption."));
		}

		logInfo(routineLogIntelligenceLine("Missions are available. Continuing Intel mission processing."));
	}

	private LocalDateTime resolveMarchReturnWaitTimeFlow(MarchesAvailable marchesAvailable) {
		LocalDateTime now = LocalDateTime.now();
		LocalDateTime queueRelease = marchesAvailable == null ? null : marchesAvailable.rescheduleTo();
		return IntelMarchAvailabilityPolicy.resolveNextRelease(now, queueRelease, intelBeastReturnTimes);
	}

	private void initializeIntelMarchCountersFlow() {
		int resolved = resolveConfiguredIntelMarchesFlow();

		maxIntelMarches = resolved;
		intelMarchesRemaining = resolved;

		logInfo(routineLogIntelligenceLine("Initialized internal Intel march counter: " + intelMarchesRemaining
				+ "/" + maxIntelMarches + " (Beast/Fire Beast only)."));
	}

	private int resolveConfiguredIntelMarchesFlow() {
		// Changed by pernerch | Date: 2026-07-04 | Why: keep one source of truth for configured march capacity plus runtime override.
		Integer configuredMarches = profile.getConfig(ConfigurationKeyEnum.GATHER_ACTIVE_MARCH_QUEUE_INT, Integer.class);
		int resolved = configuredMarches != null ? configuredMarches : MAX_INTEL_MARCH_SLOTS;
		resolved = Math.max(MIN_INTEL_MARCH_SLOTS, Math.min(MAX_INTEL_MARCH_SLOTS, resolved));
		if (intelMarchCapacityOverride != null) {
			resolved = Math.min(resolved, intelMarchCapacityOverride);
		}
		return resolved;
	}

	private void applyIntelMarchCapacityOverrideFlow(int adjustedCapacity, String reason) {
		// Changed by pernerch | Date: 2026-07-04 | Why: clamp and apply temporary capacity downgrade when VIP-expiry conditions are detected.
		int normalized = Math.max(MIN_INTEL_MARCH_SLOTS, Math.min(MAX_INTEL_MARCH_SLOTS, adjustedCapacity));
		if (intelMarchCapacityOverride != null && intelMarchCapacityOverride == normalized) {
			return;
		}

		intelMarchCapacityOverride = normalized;
		logWarning(routineLogIntelligenceLine("Adjusted internal Intel march capacity to " + normalized
				+ " (possibly VIP expired). Reason: " + reason));

		if (maxIntelMarches > 0) {
			maxIntelMarches = normalized;
			intelMarchesRemaining = Math.min(intelMarchesRemaining, normalized);
			logInfo(routineLogIntelligenceLine("Internal Intel march counter updated after capacity adjustment: "
					+ intelMarchesRemaining + "/" + maxIntelMarches + "."));
		}
	}

	private boolean hasNonMarchBoundIntelMissionAvailableFlow() {
		intelScreenHelper.ensureOnIntelScreen();

		if (survivorCampsEnabled) {
			TemplatesEnum survivorTemplate = fcEra ? TemplatesEnum.INTEL_SURVIVOR_GRAYSCALE_FC
					: TemplatesEnum.INTEL_SURVIVOR_GRAYSCALE;
			if (templateSearchHelper.locatePatternMono(survivorTemplate, SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
				return true;
			}
		}

		if (explorationsEnabled) {
			TemplatesEnum journeyTemplate = fcEra ? TemplatesEnum.INTEL_JOURNEY_GRAYSCALE_FC
					: TemplatesEnum.INTEL_JOURNEY_GRAYSCALE;
			if (templateSearchHelper.locatePatternMono(journeyTemplate, SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
				return true;
			}
		}

		return false;
	}

	private boolean handleBeastIntel() {
		intelScreenHelper.ensureOnIntelScreen();
		boolean beastFound = false;


		if (fireBeastsEnabled && !(useFlag && beastMarchSent)) {
			logInfo(routineLogIntelligenceLine("Scanning for fire beasts."));
			if (seekAndProcessGrayscale(TemplatesEnum.INTEL_FIRE_BEAST, this::handleBeast)) {
				beastFound = true;
				if (useFlag) {
					return true;

				}
			}
		}


		if (!(useFlag && beastMarchSent)) {
			logInfo(routineLogIntelligenceLine("Scanning for beasts using grayscale matching."));
			TemplatesEnum[] beast_screenings;
			if (fcEra) {


				beast_screenings = new TemplatesEnum[] {
						TemplatesEnum.INTEL_BEAST_GRAYSCALE_FC,
						TemplatesEnum.INTEL_BEAST_GRAYSCALE_FC1,
				};
			} else {


				beast_screenings = new TemplatesEnum[] {
						TemplatesEnum.INTEL_BEAST_GRAYSCALE
				};
			}

			for (TemplatesEnum beast_screening : beast_screenings) {
				if (seekAndProcessGrayscale(beast_screening, this::handleBeast)) {
					beastFound = true;
					break;
				}
			}
		}

		return beastFound;
	}

private void handleSurvivor(ImageSearchResultData result) {
		if (survivorMissionsSincePause >= SURVIVOR_BATCH_LIMIT) {
			logInfo(routineLogIntelligenceLine("Survivor batch limit reached (" + SURVIVOR_BATCH_LIMIT
					+ "). Waiting 1 minute before launching more survivor missions."));
			sleepTask(SURVIVOR_BATCH_PAUSE_MILLIS);
			survivorMissionsSincePause = 0;
		}

		tapInside(result);
		sleepTask(2000);

		ImageSearchResultData view = templateSearchHelper.locatePattern(TemplatesEnum.INTEL_VIEW, SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (!view.isFound()) {
			logWarning(routineLogIntelligenceLine("Could not find the 'View' button for the survivor. Going back."));
			pressBack();
			return;
		}

		tapInside(view);
		sleepTask(500);

		ImageSearchResultData rescue = templateSearchHelper.locatePattern(TemplatesEnum.INTEL_RESCUE, SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (!rescue.isFound()) {
			logWarning(routineLogIntelligenceLine("Could not find the 'Rescue' button for the survivor. Going back."));
			pressBack();
			pressBack();

			return;
		}

		tapInside(rescue);
		sleepTask(500);
		survivorMissionsSincePause++;
		StaminaService.getServices().subtractStamina(profile.getId(), SURVIVOR_STAMINA_COST_VALUE);
		StatisticsService.obtain().addToCounter(profile, "Intel Survivor Camps", 1);
	}

private void handleJourney(ImageSearchResultData result) {
		tapInside(result);
		sleepTask(2000);

		ImageSearchResultData view = templateSearchHelper.locatePattern(TemplatesEnum.INTEL_VIEW, SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (!view.isFound()) {
			logWarning(routineLogIntelligenceLine("Could not find the 'View' button for the journey. Going back."));
			pressBack();
			return;
		}

		tapInside(view);
		sleepTask(500);

		ImageSearchResultData explore = templateSearchHelper.locatePattern(TemplatesEnum.INTEL_EXPLORE, SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (!explore.isFound()) {
			logWarning(routineLogIntelligenceLine("Could not find the 'Explore' button for the journey. Going back."));
			pressBack();
			pressBack();

			return;
		}

		tapInside(explore);
		sleepTask(500);
		tapNear(new PointData(520, 1200));
		sleepTask(1000);
		pressBack();
		StaminaService.getServices().subtractStamina(profile.getId(), JOURNEY_STAMINA_COST_VALUE);
		StatisticsService.obtain().addToCounter(profile, "Intel Journeys", 1);
	}

private void handleBeast(ImageSearchResultData beast) {
		if (marchQueueLimitReached) {
			logInfo(routineLogIntelligenceLine("Beast detected but march queue is full. Skipping deployment but marking as detected."));
			return;
		}

		if (useFlag && beastMarchSent) {
			logInfo(routineLogIntelligenceLine("Beast march already sent with flag. Skipping beast hunt."));
			return;
		}

		tapInside(beast);
		sleepTask(2000);

		ImageSearchResultData view = templateSearchHelper.locatePattern(TemplatesEnum.INTEL_VIEW, SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (!view.isFound()) {
			logWarning(routineLogIntelligenceLine("Could not find the 'View' button for the beast. Going back."));
			pressBack();
			return;
		}
		tapInside(view);
		sleepTask(500);

		ImageSearchResultData attack = templateSearchHelper.locatePattern(TemplatesEnum.INTEL_ATTACK, SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (!attack.isFound()) {
			logWarning(routineLogIntelligenceLine("Could not find the 'Attack' button for the beast. Going back."));
			pressBack();
			pressBack();

			return;
		}
		tapInside(attack);
		sleepTask(500);


		ImageSearchResultData deployButton = templateSearchHelper.locatePattern(TemplatesEnum.DEPLOY_BUTTON,
				SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (!deployButton.isFound()) {
			logError(routineLogIntelligenceLine("March queue is full. Cannot start a new march."));
			marchQueueLimitReached = true;
			return;
		}


		if (useFlag) {
			if (!marchHelper.selectFlag(flagNumber)) {
				logWarning(routineLogIntelligenceLine("Configured formation #" + flagNumber
						+ " is unavailable. Cancelling beast deployment."));
				pressBack();
				reschedule(LocalDateTime.now().plusMinutes(5));
				processingTask = false;
				return;
			}
		}


		if (deploymentHelper.tapEqualize()) {
			sleepTask(300);
		}

		var deployment = deploymentHelper.readScreen(DeploymentHelper.MAX_ATTACK_STAMINA_COST);
		long travelTimeSeconds = deployment.travelTimeSeconds();
		int spentStamina = deployment.staminaCost();
		if (deploymentHelper.hasNoDeployableTroops() || deploymentHelper.isDeployCostRed()) {
			logWarning(routineLogIntelligenceLine(
					"Deployment blocked by troops or stamina. No march was sent or deducted; retrying in 5 minutes."));
			pressBack();
			reschedule(LocalDateTime.now().plusMinutes(5));
			processingTask = false;
			return;
		}


		ImageSearchResultData deploy = templateSearchHelper.locatePattern(TemplatesEnum.DEPLOY_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (!deploy.isFound()) {
			logError(routineLogIntelligenceLine("Deploy button not detected. Planning next run to try again in 5 minutes."));
			reschedule(LocalDateTime.now().plusMinutes(5));
			processingTask = false;

			return;
		}

		tapInside(deploy);
		sleepTask(1000);


		ImageSearchResultData confirmDialog = templateSearchHelper.locatePattern(TemplatesEnum.DEPLOY_CONFIRMATION_DIALOG, SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (confirmDialog.isFound()) {
			logInfo(routineLogIntelligenceLine("Deployment confirmation dialog detected (troop imbalance). Confirming deployment."));
			tapNear(new PointData(211, 713));
			sleepTask(300);
			tapNear(new PointData(509, 789));
			sleepTask(300);
		}

		if (deploymentHelper.isSameTargetDialog()) {
			logInfo(routineLogIntelligenceLine(
					"Another march is already targeting this beast. Cancelling deployment without stamina deduction."));
			pressBack();
			pressBack();
			reschedule(LocalDateTime.now().plusMinutes(1));
			processingTask = false;
			return;
		}

		deploy = templateSearchHelper.locatePattern(TemplatesEnum.DEPLOY_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
		if (deploy.isFound()) {
			logWarning(routineLogIntelligenceLine("Deploy button still present after deployment attempt. March may have did not complete. Planning next run in 5 minutes."));
			reschedule(LocalDateTime.now().plusMinutes(5));
			processingTask = false;

			return;
		}

		logInfo(routineLogIntelligenceLine("Beast march deployed finished cleanly."));
		beastMarchSent = true;
		intelMarchesRemaining = Math.max(0, intelMarchesRemaining - 1);
		if (intelMarchesRemaining <= 0) {
			marchQueueLimitReached = true;
		}
		logInfo(routineLogIntelligenceLine("Internal Intel march counter updated: " + intelMarchesRemaining
				+ "/" + maxIntelMarches + " remaining after Beast/Fire Beast deployment."));
		StatisticsService.obtain().addToCounter(profile, "Intel Beast", 1);


		staminaHelper.subtractStamina(spentStamina, false);


		if (travelTimeSeconds <= 0) {
			logError(routineLogIntelligenceLine("Could not parse travel time via OCR. Using 5 minute fallback reschedule."));
			LocalDateTime rescheduleTime = LocalDateTime.now().plusMinutes(5);
			reschedule(rescheduleTime);
			processingTask = false;

			return;
		}

		if (useSmartProcessing) {
			LocalDateTime rescheduleTime = LocalDateTime.now().plusSeconds(travelTimeSeconds * 2);
			intelBeastReturnTimes.add(rescheduleTime);
			logInfo(routineLogIntelligenceLine("Smart Intel beast march return ETA: "
					+ GameTimeUtils.formatCountdown(rescheduleTime)
					+ ". Continuing loop to use remaining available marches."));
		}
	}
}
