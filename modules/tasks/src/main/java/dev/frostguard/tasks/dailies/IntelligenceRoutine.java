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
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.OcrSettingsData.TextLayout;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.helper.DeploymentHelper;
import dev.frostguard.engine.helper.StaminaTopUpResult;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.schedule.TaskQueue;
import dev.frostguard.engine.schedule.TroopSlotPolicy;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.StaminaService;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.engine.service.TaskManagementService;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
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

	// matt, 2026-08-08: real bug found live — after a clean re-scan finds
	// nothing, the routine trusted the game's own full-LIST "Refreshes In"
	// cooldown (observed ~7 hours) as "nothing to check until then." But
	// individual Beast/Fire Beast spawns clearly happen well inside that
	// window — 3 separate fire beasts appeared roughly 10 minutes apart
	// across one test session. Trusting the long cooldown left new spawns
	// sitting unattacked for hours. Beast/Fire Beast hunting now caps the
	// reschedule at this interval regardless of how far out the list
	// cooldown reads, so a new spawn is never missed by more than this.
	private static final int MAX_BEAST_RECHECK_MINUTES = 15;

	// matt, 2026-08-09 (Part 2 — Intel refresh-timer fix): sanity ceiling on the OCR-read full-list
	// "Refreshes In" cooldown. The real refresh runs ~7h, but the value is read by OCR and the same
	// font that turns "1d" into "Jd" elsewhere (see TimerSweepRoutine.normaliseDayGlyphs / its
	// SANITY_CEILING) can inflate this read — a spurious day prefix or an extra digit pushes the
	// refresh from ~7h out to a day or more, silently parking Intel long past its next real refresh.
	// When Beast/Fire Beast hunting is on, MAX_BEAST_RECHECK_MINUTES already caps this tightly; but
	// with only Survivor/Exploration missions enabled there was no upper bound at all. 8h gives real
	// headroom above the genuine ~7h refresh while catching any misread that lands implausibly far.
	private static final int MAX_INTEL_REFRESH_MINUTES = 8 * 60;

	/** Safety bound on the Claim All loop so a stuck button cannot spin the routine forever. */
	private static final int CLAIM_ALL_MAX_PRESSES = 4;

	/** Grace after a beast march's return ETA before checking, so the battle has resolved. */
	private static final int BEAST_RETURN_CLAIM_BUFFER_SECONDS = 45;

	/**
	 * If Gather is already going to run within this window when an Intel pass finishes, leave its
	 * schedule alone instead of yanking it to "now" — pulling it forward again would only churn the
	 * queue (and the march screen) for no gain. See {@link #triggerGatherResourcesNowFlow()}.
	 */
	private static final long GATHER_TRIGGER_GRACE_SECONDS = 90;

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

// matt, 2026-08-06: "if we run on stamina, go ahead and refresh it" - same
// Chief Stamina item top-up PolarTerrorHuntingRoutine/CryptidHostingRoutine
// already use, so a low-stamina Intel run tops up from the backpack instead
// of sitting idle for the full regen window.
private boolean useStaminaItems;

private int staminaItemReserve;

private boolean processingTask;

// matt/2026-08-13: the while(processingTask) loop below had NO exit and NO delay
// for the case where missions exist but genuinely can never be processed (e.g. a
// Fire Beast / mob too high-level for every available troop composition to beat) --
// manageRescheduling()'s "!anyIntelProcessed" branch just logged and fell through
// to "continuing," looping again immediately. Confirmed live: this pegged the
// single-threaded TaskQueue in a tight loop overnight (memory climbed to 1GB+,
// and since this loop never returns control, EVERY other task -- not just Intel --
// was frozen behind it, which is very likely what looked like a random "stuck exit
// screen" the whole night; nothing else in the queue ever got a turn). Tracks
// consecutive no-progress cycles and forces a real backoff instead of an infinite
// immediate retry.
// matt/2026-08-13: "why 30 minutes, why not until the next refresh" -- correct
// catch. A stuck mission (like an unbeatable mob) doesn't clear on its own; it sits
// there until the whole Intel board refreshes, and that refresh is already known
// elsewhere in this file to run ~7h (MAX_INTEL_REFRESH_MINUTES, 8h ceiling with
// headroom). A short guess would just re-fail against the exact same mission every
// 30 minutes for hours -- wasted cycles for no better outcome. There's no OCR'able
// per-mission "expires in" countdown while the board still has items on it (that
// countdown only appears once the board is fully empty, which is what
// tryRescheduleFromCooldownFlow() already reads) -- so this reuses the same known
// real-world refresh window instead of inventing a new number.
private int consecutiveNoProgressCycles;
private static final int MAX_CONSECUTIVE_NO_PROGRESS_CYCLES = 3;

// matt/2026-08-13: the actual loop matt caught live doesn't even trip the counter
// above -- a beast the game itself says is "certain to fail" still counts as
// "found" every cycle (seekAndProcessGrayscale only checks whether the template
// matched, not whether the attack succeeded), so anyIntelProcessed stays true
// forever and the outer no-progress counter never increments. This tracks
// consecutive certain-to-fail deployments specifically and stops attempting beasts
// for the REST of this run once it trips, so a single unbeatable beast can never
// crowd out Survivor/Explorations (already reordered to run first) or block the
// outer loop from eventually detecting real no-progress and backing off.
private int consecutiveBeastDeploymentFailures;
private boolean beastStuckThisRun;
private static final int MAX_CONSECUTIVE_BEAST_DEPLOYMENT_FAILURES = 2;

// matt/2026-08-15: "it's reaching a beast that's too hard to defeat, and it's just looping...
// then it's gonna run in another fifteen minutes and do the same thing." consecutiveBeastDeploymentFailures
// and beastStuckThisRun above are plain instance fields -- they correctly stop this RUN from
// re-attacking a certain-to-fail beast, but DelayedTaskRegistry.create() hands out a fresh
// IntelligenceRoutine instance every scheduled execution, so that memory is gone by the very
// next run. Confirmed live: the same still-there beast got re-attacked, failed twice, and
// stopped again every ~15 minutes, forever. INTEL_BEAST_SKIP_UNTIL_LONG persists the give-up
// state across runs; this is deliberately shorter than a full board refresh (matt's own past
// call on a DIFFERENT no-progress case -- "why not until next refresh") because a beast spawn
// can rotate mid-board, and 60 minutes was picked as a middle ground between "stop spamming
// the same fight" and "don't miss a beast that gets weaker/replaced partway through the board's
// multi-hour cycle." Survivor Camps/Explorations are never affected by this -- only the
// beast/fire-beast scan itself is skipped while the timestamp is in the future.
private static final int BEAST_STUCK_BACKOFF_MINUTES = 60;

private int maxIntelMarches;

private int intelMarchesRemaining;

// Changed by pernerch | Date: 2026-07-04 | Why: keep runtime Intel capacity override when march capacity drops (e.g., VIP expiry).
private Integer intelMarchCapacityOverride;

private int survivorMissionsSincePause;

// Changed by pernerch | Date: 2026-07-02 | Why: ensure gather and autojoin can be resumed after Intel priority handling.
private boolean shouldRequeueGatherAfterIntel;

// Changed by pernerch | Date: 2026-07-02 | Why: restore autojoin after Intel processing so helping rallies continues.
private boolean shouldRequeueAutoJoinAfterIntel;

// matt, 2026-08-09 (Part 3): true once this Intel pass has already pulled Gather forward, so the
// completion path and the finally-block requeue can't double-fire runNow on the same run.
private boolean gatherRunNowTriggered;

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
		gatherRunNowTriggered = false;
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
		// matt, 2026-08-08: claim BEFORE the availability check, not after. Finished missions sit
		// on the Intel map as a marker wearing a green tick, with a big "Claim All" button at the
		// bottom of the screen. None of the mission templates match a ticked marker, so
		// hasAnyIntelMissionAvailableFlow() reported "nothing here" and returned at the branch
		// below — which skipped redeemCompletedMissions() entirely, because that only ran further
		// down the flow. Net effect: a beaten Fire Beast could sit unclaimed indefinitely while
		// the log cheerfully said there was no intel to process. Observed live 2026-08-08 14:23.
		// Claiming first also re-rolls the board, so a fresh spawn is picked up by the very next
		// check instead of waiting for the following cycle.
		int rewardsClaimed = claimAllCompletedRewardsFlow();
		if (rewardsClaimed > 0) {
			logInfo(routineLogIntelligenceLine("Claimed " + rewardsClaimed
					+ " completed intel reward(s) via Claim All before scanning for new missions."));
		}

		boolean intelMissionsDetected = hasAnyIntelMissionAvailableFlow();
		// matt/2026-08-09: record whether Intel actually has work so GatherRoutine can distinguish a
		// real march-consuming Intel from Intel's idle ~15-min beast recheck. Without this, Gather saw
		// "Intel pending within 15 min" every cycle, recalled + deferred, and NEVER deployed (livelock).
		profile.setConfig(ConfigurationKeyEnum.INTEL_LAST_RUN_HAD_MISSIONS_BOOL, intelMissionsDetected);
		setShouldUpdateConfig(true);
		if (!intelMissionsDetected) {
			logInfo(routineLogIntelligenceLine("No intel missions detected. Skipping Intel run for now."));
			// matt/2026-08-09 (troop-slot economy): no missions ⇒ no genuine slot demand. Drop any
			// stale Intel claim (also pulls Gather forward). Agrees with the LAST_RUN_HAD_MISSIONS flag
			// above — the ledger is the generalization, the flag the belt-and-suspenders.
			TroopSlotPolicy.release(profile, TpDailyTaskEnum.INTEL);
			tryRescheduleFromCooldownFlow();
			processingTask = false;
			return;
		}

		// matt/2026-08-09 (troop-slot economy): Intel has confirmed actionable missions, so publish its
		// real demand on the slot ledger. Gather recalls only the shortfall and defers to this claim's
		// expiry; the claim self-sweeps if this pass is interrupted before finalize releases it.
		TroopSlotPolicy.claim(profile, TpDailyTaskEnum.INTEL, resolveConfiguredIntelMarchesFlow(),
				LocalDateTime.now().plusMinutes(MAX_BEAST_RECHECK_MINUTES));

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


			// matt/2026-08-13: Survivor Camps and Explorations run BEFORE Beast/Fire Beast
			// now, deliberately -- these always fully complete (no "too high level to
			// beat" failure mode), while a beast can be stuck unbeatable and burn repeated
			// attempts. Processing the guaranteed-completable activities first means a
			// stuck beast can never crowd out or delay them within a cycle; beast is
			// attempted last, only after everything else this cycle is already done.
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


			if (beastsEnabled && !beastStuckThisRun && shouldProcessBeastsFlow()) {
				if (handleBeastIntel()) {
					anyIntelProcessed = true;
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

		// matt, 2026-08-08: an unclaimed reward IS actionable intel. Without this the pre-check
		// answered "no" to a screen showing a finished Fire Beast and a Claim All button.
		if (templateSearchHelper.locatePattern(
				TemplatesEnum.INTEL_CLAIM_ALL, SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
			return true;
		}

		if (fireBeastsEnabled && templateSearchHelper
				.locatePatternMono(TemplatesEnum.INTEL_FIRE_BEAST, SearchConfigConstants.FIRE_BEAST_SEARCH)
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

		// matt, 2026-08-09 (Part 3): reaching this method means Intel has nothing left to do and is
		// booking its next run — i.e. the pass is complete. Trigger Gather right here so gathering
		// resumes immediately, covering the early no-missions return (which exits before the
		// try/finally that would otherwise run requeueGatherTasksFlow). The once-per-run guard inside
		// keeps this from double-firing with the finally-block requeue on the normal path.
		triggerGatherResourcesNowFlow();

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

		// matt, 2026-08-09 (Part 2 — Intel refresh-timer fix): clamp the OCR-read refresh to a
		// plausible maximum before anything downstream trusts it. Without this, a garbled cooldown
		// read (stray day prefix / extra digit) could park Intel a day or more out — well past the
		// next real ~7h refresh — whenever Beast hunting wasn't on to cap it. See the constant's note.
		LocalDateTime refreshCeiling = LocalDateTime.now().plusMinutes(MAX_INTEL_REFRESH_MINUTES);
		if (cooldown.isAfter(refreshCeiling)) {
			logWarning(routineLogIntelligenceLine(String.format(
					"List refresh read as %s — beyond the %d-minute plausible ceiling (real refresh is ~7h); "
							+ "capping there in case the cooldown OCR misread a day prefix or an extra digit.",
					cooldown.format(DATETIME_FORMATTER), MAX_INTEL_REFRESH_MINUTES)));
			cooldown = refreshCeiling;
		}

		// matt/2026-08-09: this path only runs when the board is EMPTY (nothing left to fight) and shows
		// the central refresh countdown (read from the middle of the screen). Nothing — beasts included —
		// appears until that countdown expires, so the old 15-minute Beast/Fire-Beast recheck cap here
		// just re-opened the Intel screen every 15 min for hours with nothing to find (wasted activity /
		// ban risk). Honor the OCR'd countdown directly. A beast march still in flight is handled below
		// and pulls the next run forward to claim. Add +1..3 min of randomness (matt's request) so we
		// arrive just AFTER the refresh — never reading a stale "still counting" value — and never hit
		// the exact same instant twice.
		long jitterSeconds = java.util.concurrent.ThreadLocalRandom.current().nextLong(60, 181); // 1–3 min
		LocalDateTime nextRun = cooldown.plusSeconds(jitterSeconds);
		logInfo(routineLogIntelligenceLine(String.format(
				"Empty board — scheduling next Intel run to the middle-screen refresh countdown %s + %ds jitter.",
				cooldown.format(DATETIME_FORMATTER), jitterSeconds)));

		// matt, 2026-08-08: a march already in flight beats every other signal. When a beast
		// march was just sent, the reward lands at the return ETA — come back then and claim it,
		// rather than honouring a cooldown or the 15-minute cap and leaving a dead beast on the
		// board. The buffer covers the battle resolving after the march touches down.
		LocalDateTime earliestBeastReturn = intelBeastReturnTimes.stream()
				.filter(java.util.Objects::nonNull)
				.filter(eta -> eta.isAfter(LocalDateTime.now()))
				.min(LocalDateTime::compareTo)
				.orElse(null);

		if (earliestBeastReturn != null) {
			LocalDateTime claimCheck = earliestBeastReturn.plusSeconds(BEAST_RETURN_CLAIM_BUFFER_SECONDS);
			if (claimCheck.isBefore(nextRun)) {
				logInfo(routineLogIntelligenceLine(String.format(
						"A beast march returns at %s — pulling the next Intel run forward to %s to claim the "
								+ "reward instead of waiting until %s.",
						earliestBeastReturn.format(DATETIME_FORMATTER),
						claimCheck.format(DATETIME_FORMATTER),
						nextRun.format(DATETIME_FORMATTER))));
				nextRun = claimCheck;
			}
		}

		reschedule(nextRun);
		pressBack();

		logInfo(routineLogIntelligenceLine("Zero new intel detected. Planning next run task to run at: " + nextRun.format(DATETIME_FORMATTER)));
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
		return seekAndProcessGrayscale(template, processMethod, SearchConfigConstants.SINGLE_WITH_RETRIES);
	}

private boolean seekAndProcessGrayscale(TemplatesEnum template, Consumer<ImageSearchResultData> processMethod,
		SearchConfig searchConfig) {
		logInfo(routineLogIntelligenceLine("Scanning for grayscale template '" + template + "'"));
		ImageSearchResultData result = templateSearchHelper.locatePatternMono(template, searchConfig);

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

	/**
	 * Presses the Intel screen's "Claim All" button until it is gone.
	 *
	 * <p>This is deliberately independent of {@link #redeemCompletedMissions()}, which hunts for
	 * individual green tick icons. That icon is a ~25px tick drawn over a pale snow map and it
	 * matched nothing in practice ("Zero completed missions detected" on every attempt, while a
	 * ticked Fire Beast was plainly on screen). The Claim All button is a large solid green
	 * control that template-matches at 1.00 against a live frame and scores 0.33-0.38 on frames
	 * without it, so it is a far more reliable signal for the same condition.</p>
	 *
	 * @return how many times a Claim All press was performed
	 */
	private int claimAllCompletedRewardsFlow() {
		intelScreenHelper.ensureOnIntelScreen();

		int pressed = 0;
		for (int attempt = 0; attempt < CLAIM_ALL_MAX_PRESSES; attempt++) {
			ImageSearchResultData claimAll = templateSearchHelper.locatePattern(
					TemplatesEnum.INTEL_CLAIM_ALL, SearchConfigConstants.DEFAULT_SINGLE);

			if (!claimAll.isFound()) {
				break;
			}

			logInfo(routineLogIntelligenceLine("Claim All button present — claiming finished intel rewards."));
			tapNear(claimAll.getPoint());
			pressed++;
			sleepTask(1200);

			// Reward popups stack on top of the map; dismiss them so the next look at the
			// board sees the map itself rather than a chest animation.
			tapInside(new PointData(700, 1270), new PointData(710, 1280), 3, 100);
			sleepTask(800);
			intelScreenHelper.ensureOnIntelScreen();
		}

		return pressed;
	}

private int redeemCompletedMissions() {
		intelScreenHelper.ensureOnIntelScreen();
		logInfo(routineLogIntelligenceLine("Scanning for completed missions to claim."));
		int claimedRewards = claimAllCompletedRewardsFlow();

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
		triggerGatherResourcesNowFlow();
		sleepTask(500);
	}

	// matt, 2026-08-09 (Part 3): Intel recalls/cancels gather marches while it works, and Gather
	// itself speculatively defers ~15-25 min out the moment it sees Intel pending (see
	// GatherRoutine.checkHighPriorityEventConflict). Once Intel finishes its pass and books its long
	// (~7h) refresh, that speculative defer is stale — nothing was pulling Gather back, so gathering
	// sat idle for up to ~25 min after every Intel run, especially after a recall. This mirrors
	// GatherRoutine's own triggerPendingIntelNowFlow (queue.runNow) in the opposite direction: when
	// Intel completes, pull GATHER_RESOURCES forward to run now so it re-evaluates immediately
	// instead of honouring a defer Intel has already made irrelevant. Gather still makes the real
	// call when it runs — it will wait properly if troops are genuinely returning or all queues are
	// full — so this never forces a bad deployment, it just stops the wasted idle window.
	//
	// Guarded three ways to keep this from becoming screen-churn (a real ban risk this codebase
	// guards against elsewhere): only when Gather is actually enabled, only once per Intel pass, and
	// only when Gather isn't already about to run within the grace window.
	private void triggerGatherResourcesNowFlow() {
		if (gatherRunNowTriggered) {
			return;
		}

		Boolean gatherEnabled = profile.getConfig(ConfigurationKeyEnum.GATHER_TASK_BOOL, Boolean.class);
		if (!Boolean.TRUE.equals(gatherEnabled)) {
			return;
		}

		TaskQueue queue = dev.frostguard.engine.service.ScheduleService.obtain().getCoordinator().getQueue(profile.getId());
		if (queue == null) {
			logError(routineLogIntelligenceLine(
					"Intel finished but no active queue was available to trigger Gather immediately for profile "
							+ profile.getName()));
			return;
		}

		// Already imminent — let it run on its own rather than churning the heap and the march screen.
		if (queue.isTaskScheduledSoon(TpDailyTaskEnum.GATHER_RESOURCES, GATHER_TRIGGER_GRACE_SECONDS)) {
			gatherRunNowTriggered = true;
			logInfo(routineLogIntelligenceLine("Gather is already scheduled to run within "
					+ GATHER_TRIGGER_GRACE_SECONDS + "s; leaving its schedule as-is."));
			return;
		}

		queue.runNow(TpDailyTaskEnum.GATHER_RESOURCES, true);
		gatherRunNowTriggered = true;
		logInfo(routineLogIntelligenceLine("Intel pass complete — pulled Gather Resources forward to run now so "
				+ "gathering resumes immediately instead of waiting on a stale defer."));
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
		// matt/2026-08-09 (troop-slot economy): the Intel pass is over — drop its slot claim. release()
		// also pulls Gather forward, so freed slots refill immediately. The existing requeue below is
		// harmless (isTaskScheduledSoon grace prevents a double runNow).
		TroopSlotPolicy.release(profile, TpDailyTaskEnum.INTEL);

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
		marchHelper.openLeftMenuSection(cityTab);
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

		while (recalled < MARCH_QUEUE_REGIONS.length) {
			marchHelper.openLeftMenuSection(false);
			try {
				List<MarchSlotState> slots = marchHelper.readVisibleMarchQueue();
				if (countIdleMarchesFlow(slots) >= SMART_PROCESSING_MIN_IDLE_MARCHES_FOR_INTEL) {
					break;
				}

				GatherMarchCandidate candidate = findLongestDuplicateGatherMarchFlow(slots);
				if (candidate == null || !recallGatherMarchByQueueFromOpenPanelFlow(candidate.queueIndex())) {
					break;
				}
				recalled++;
			} finally {
				marchHelper.closeLeftMenu();
			}
			sleepTask(250);
		}

		return recalled;
	}

private GatherMarchCandidate findLongestDuplicateGatherMarchFlow(List<MarchSlotState> slots) {
		Map<MarchResourceType, List<GatherMarchCandidate>> groupedByType = slots
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
	}

private boolean recallGatherMarchByQueueFromOpenPanelFlow(int queueIndex) {
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
	}

private int countIdleMarchesFlow() {
		return countIdleMarchesFlow(marchHelper.readMarchQueue());
}

private int countIdleMarchesFlow(List<MarchSlotState> slots) {
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
		Boolean configuredUseStaminaItems = profile.getConfig(ConfigurationKeyEnum.INTEL_USE_STAMINA_ITEMS_BOOL, Boolean.class);
		this.useStaminaItems = configuredUseStaminaItems != null && configuredUseStaminaItems;
		Integer configuredStaminaItemReserve = profile.getConfig(ConfigurationKeyEnum.INTEL_STAMINA_ITEM_RESERVE_INT, Integer.class);
		this.staminaItemReserve = configuredStaminaItemReserve != null ? Math.max(0, configuredStaminaItemReserve) : 0;
		this.textHelper = new ResilientOcrExecutor<>(provider);

		logDebug(routineLogIntelligenceLine("Configuration loaded: fcEra=" + fcEra + ", useSmartProcessing=" + useSmartProcessing +
				", recallGatherTroopsFlow=" + recallGatherTroopsFlow + ", useFlag=" + useFlag + ", beastsEnabled="
				+ beastsEnabled));
	}

private boolean hasEnoughStaminaFlow() {
		int staminaValue = StaminaService.getServices().getCurrentStamina(profile.getId());

		if (staminaValue >= MIN_STAMINA_REQUIRED_FLOOR) {
			return true;
		}

		logWarning(routineLogIntelligenceLine("Not enough stamina to process intel. Current stamina: " + staminaValue +
				". Required: " + MIN_STAMINA_REQUIRED_FLOOR + "."));

		if (useStaminaItems) {
			StaminaTopUpResult result = staminaHelper.topUpFromProfile(MIN_STAMINA_REQUIRED_FLOOR, staminaItemReserve);
			if (result.successful()) {
				logInfo(routineLogIntelligenceLine("Topped up Chief Stamina from the backpack. Continuing Intel run."));
				return true;
			}
			if (!result.confirmedItemShortage()) {
				// Read/UI hiccup rather than a confirmed empty backpack - worth a quick retry
				// rather than falling straight through to a multi-hour regen wait.
				logWarning(routineLogIntelligenceLine("Stamina top-up attempt did not confirm (status="
						+ result.status() + "). Retrying in 2 minutes."));
				reschedule(LocalDateTime.now().plusMinutes(2));
				processingTask = false;
				return false;
			}
			logWarning(routineLogIntelligenceLine("Stamina items exhausted (reserve=" + staminaItemReserve
					+ "). Falling back to natural regeneration."));
		}

		long minutesToRegen = StaminaService.minutesToRegenerate(
				staminaValue, MIN_STAMINA_REQUIRED_FLOOR);
		LocalDateTime rescheduleTime = LocalDateTime.now().plusMinutes(minutesToRegen);
		deferForStamina(MIN_STAMINA_REQUIRED_FLOOR, MIN_STAMINA_REQUIRED_FLOOR, rescheduleTime);
		return false;
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
			consecutiveNoProgressCycles++;
			if (consecutiveNoProgressCycles >= MAX_CONSECUTIVE_NO_PROGRESS_CYCLES) {
				// matt/2026-08-13: don't blindly wait MAX_INTEL_REFRESH_MINUTES (8h) here --
				// that could mean sitting idle for 8h when the real board refresh was only
				// 1 minute away. Read the actual "Refreshes In" timer (same OCR helper the
				// empty-board cooldown path already uses -- the WITH_MARKERS layout works
				// even though mission markers are still on the board here) and reschedule
				// to THAT real time + 3 minutes. Only fall back to the fixed 8h ceiling if
				// the OCR genuinely can't read anything at all.
				LocalDateTime backoffTime;
				LocalDateTime cooldown = readCooldownFlow(
						CommonGameAreas.INTEL_COOLDOWN_WITH_MARKERS_OCR_AREA, "marker-map");
				if (cooldown == null) {
					cooldown = readCooldownFlow(CommonGameAreas.INTEL_COOLDOWN_EMPTY_MAP_OCR_AREA, "empty-map");
				}
				if (cooldown != null) {
					LocalDateTime refreshCeiling = LocalDateTime.now().plusMinutes(MAX_INTEL_REFRESH_MINUTES);
					if (cooldown.isAfter(refreshCeiling)) {
						logWarning(routineLogIntelligenceLine(String.format(
								"Refresh timer read as %s -- beyond the %d-minute plausible ceiling; capping there "
										+ "in case the OCR misread a day prefix or an extra digit.",
								cooldown.format(DATETIME_FORMATTER), MAX_INTEL_REFRESH_MINUTES)));
						cooldown = refreshCeiling;
					}
					backoffTime = cooldown.plusMinutes(3);
					logWarning(routineLogIntelligenceLine("Missions exist but " + consecutiveNoProgressCycles
							+ " consecutive cycles processed nothing (likely a mission nothing can currently "
							+ "clear, e.g. a mob too high-level for available troops). Read the real refresh "
							+ "timer as " + cooldown.format(DATETIME_FORMATTER) + " -- rescheduling for "
							+ backoffTime.format(DATETIME_FORMATTER) + " (+3min)."));
				} else {
					backoffTime = LocalDateTime.now().plusMinutes(MAX_INTEL_REFRESH_MINUTES);
					logWarning(routineLogIntelligenceLine("Missions exist but " + consecutiveNoProgressCycles
							+ " consecutive cycles processed nothing, and the refresh timer couldn't be read via "
							+ "OCR either. Falling back to the " + MAX_INTEL_REFRESH_MINUTES
							+ "-minute ceiling -- rescheduling for " + backoffTime.format(DATETIME_FORMATTER) + "."));
				}

				// matt/2026-08-14, caught live watching the app: "intel timer is wrong" -- this branch
				// was scheduling straight off the full board-refresh cooldown (up to
				// MAX_INTEL_REFRESH_MINUTES / 8h away, confirmed live jumping from a 12:00 to a 20:00
				// UTC read), completely ignoring MAX_BEAST_RECHECK_MINUTES. That constant's own header
				// comment already establishes new Beast/Fire Beast spawns happen well inside the full
				// refresh window (3 separate fire beasts ~10 min apart in one observed session) -- it
				// was only ever applied to the march-slot claim expiry (line ~285), never to this actual
				// reschedule() call, so a board that's just stuck on one unbeatable beast (the exact
				// case that lands here, since a stuck beast keeps anyIntelProcessed false) could sit
				// unrechecked for hours with new, winnable spawns appearing and going completely
				// unattended. Cap the backoff at the same 15-minute recheck interval whenever
				// Beast/Fire Beast hunting is enabled, regardless of how far out the real board refresh
				// reads.
				if (beastsEnabled || fireBeastsEnabled) {
					LocalDateTime beastRecheckCeiling = LocalDateTime.now().plusMinutes(MAX_BEAST_RECHECK_MINUTES);
					if (backoffTime.isAfter(beastRecheckCeiling)) {
						logWarning(routineLogIntelligenceLine(String.format(
								"Backoff of %s is beyond the %d-minute Beast/Fire Beast recheck cap -- capping "
										+ "there so a new spawn is never missed by more than that, regardless of "
										+ "how far out the board's own full refresh reads.",
								backoffTime.format(DATETIME_FORMATTER), MAX_BEAST_RECHECK_MINUTES)));
						backoffTime = beastRecheckCeiling;
					}
				}

				reschedule(backoffTime);
				processingTask = false;
				consecutiveNoProgressCycles = 0;
				return;
			}
			logInfo(routineLogIntelligenceLine("Missions still exist but none were processed this cycle ("
					+ consecutiveNoProgressCycles + "/" + MAX_CONSECUTIVE_NO_PROGRESS_CYCLES
					+ " before backing off). Retrying immediately."));
		} else {
			consecutiveNoProgressCycles = 0;
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

	// matt/2026-08-14, caught live watching the app: "that purple is being ignored... it SHOULD
	// atk firebeast last." Root cause -- Fire Beast was scanned FIRST here, and Fire Beast is
	// disproportionately likely to be "too strong to beat" (confirmed live: two straight
	// "certain to fail" deployments). consecutiveBeastDeploymentFailures/beastStuckThisRun are
	// SHARED between Fire Beast and the regular Beast (skull icon) -- once Fire Beast trips that
	// circuit breaker, the outer loop's `!beastStuckThisRun` gate blocks handleBeastIntel() from
	// running again for the REST of the run, meaning the regular Beast never gets attempted on
	// any later cycle even if it's genuinely winnable. Reordered so the regular Beast goes
	// first and Fire Beast goes last, exactly as matt asked -- a Fire Beast that's too strong
	// can no longer crowd out a beatable regular Beast.
	private boolean handleBeastIntel() {
		if (isBeastSkipActive()) {
			return false;
		}

		intelScreenHelper.ensureOnIntelScreen();
		boolean beastFound = false;

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
					if (useFlag) {
						return true;
					}
					break;
				}
			}
		}


		if (fireBeastsEnabled && !(useFlag && beastMarchSent)) {
			logInfo(routineLogIntelligenceLine("Scanning for fire beasts."));
			if (seekAndProcessGrayscale(TemplatesEnum.INTEL_FIRE_BEAST, this::handleBeast, SearchConfigConstants.FIRE_BEAST_SEARCH)) {
				beastFound = true;
			}
		}

		return beastFound;
	}

	/** True while a persisted beast-skip is active (see INTEL_BEAST_SKIP_UNTIL_LONG's header
	 *  comment) -- this run should not scan for or attack Beast/Fire Beast at all, but Survivor
	 *  Camps/Explorations still run normally. */
	private boolean isBeastSkipActive() {
		Long skipUntilMillis = profile.getConfig(ConfigurationKeyEnum.INTEL_BEAST_SKIP_UNTIL_LONG, Long.class);
		if (skipUntilMillis == null || skipUntilMillis <= 0) {
			return false;
		}
		LocalDateTime skipUntil = LocalDateTime.ofInstant(
				java.time.Instant.ofEpochMilli(skipUntilMillis), java.time.ZoneId.systemDefault());
		if (LocalDateTime.now().isBefore(skipUntil)) {
			logInfo(routineLogIntelligenceLine("Skipping Beast/Fire Beast scan -- a previous run found the "
					+ "current beast certain-to-fail and this backoff doesn't expire until "
					+ skipUntil.format(DATETIME_FORMATTER) + ". Survivor Camps/Explorations are unaffected."));
			return true;
		}
		return false;
	}

	// matt/2026-08-14, caught live watching the app: several recovery paths in this routine press
	// back TWICE unconditionally, assuming exactly two screens are stacked (e.g. Attack detail +
	// Deploy screen). That assumption isn't always true -- when only ONE layer was actually open,
	// the second back press lands on a bare screen, and this game's own back-button handling
	// there is to pop a native "Quit game?" confirmation dialog. Left alone, that's one accidental
	// tap away from actually exiting the game mid-automation -- confirmed live, matt found it
	// sitting open after a beast-deployment abort. Safety net: after any back-press chain that
	// might overshoot, check specifically for this dialog's "Quit game?" body text and tap Cancel
	// -- never Confirm -- to back out of it safely. Coordinates/OCR region calibrated from a live
	// capture of the actual dialog.
	private static final PointData QUIT_DIALOG_BODY_TL = new PointData(80, 505);
	private static final PointData QUIT_DIALOG_BODY_BR = new PointData(640, 705);
	private static final PointData QUIT_DIALOG_CANCEL_BUTTON = new PointData(207, 789);

	private static final OcrSettingsData QUIT_DIALOG_OCR_SETTINGS = OcrSettingsData.assembler()
			.stripBackground(true)
			.charWhitelist("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ? ")
			.textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
			.build();

	private void dismissQuitGameDialogIfPresent() {
		String bodyText = stringHelper.attemptRecognition(
				QUIT_DIALOG_BODY_TL, QUIT_DIALOG_BODY_BR,
				2, 150L, QUIT_DIALOG_OCR_SETTINGS,
				s -> s != null && !s.isBlank(),
				s -> s);
		if (bodyText != null && bodyText.toLowerCase(Locale.ROOT).contains("quit")) {
			logWarning(routineLogIntelligenceLine(
					"Quit-game confirmation dialog detected after a back-press chain -- tapping Cancel "
							+ "to back out safely instead of risking an accidental exit."));
			tapNear(QUIT_DIALOG_CANCEL_BUTTON);
			sleepTask(500);
		}
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
			dismissQuitGameDialogIfPresent();

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
			dismissQuitGameDialogIfPresent();

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

	// matt, 2026-08-08: the game shows this exact red warning on the
	// deployment screen when the composition can't win — the bot was never
	// checking for it, so it equalized troops into a thin 4-5% ratio and
	// deployed straight into a doomed march. Calibrated live 2026-08-08.
	private static final PointData FAIL_WARNING_TOP_LEFT = new PointData(0, 590);
	private static final PointData FAIL_WARNING_BOTTOM_RIGHT = new PointData(720, 640);

	// matt/2026-08-13: caught live, real troop losses (5 attacks against the same beast before it
	// stopped) — the real in-game warning reads "Not likely to prevail," which never contains the
	// literal word "fail" this check was looking for. The substring match silently returned false
	// every single time and let the deploy go straight through the warning. Broadened to the actual
	// confirmed wording plus reasonable variants, since the exact on-screen phrasing wasn't
	// available to lock down further at the time of this fix -- narrow this to the precise string
	// once a real screenshot of the warning is in hand.
	private static final String[] DEPLOYMENT_FAIL_WARNING_PHRASES = {
			"fail", "not likely to prevail", "unlikely to prevail", "likely to lose", "low chance"
	};

	private boolean isDeploymentCertainToFail() {
		OcrSettingsData settings = OcrSettingsData.assembler()
				.textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
				.recognitionEngine(OcrSettingsData.RecognitionEngine.LSTM_ONLY)
				.stripBackground(true)
				.build();
		String text = readStringValue(FAIL_WARNING_TOP_LEFT, FAIL_WARNING_BOTTOM_RIGHT, settings);
		String normalized = text == null ? null : text.toLowerCase(Locale.ROOT);
		boolean failing = normalized != null && java.util.Arrays.stream(DEPLOYMENT_FAIL_WARNING_PHRASES)
				.anyMatch(normalized::contains);
		if (failing) {
			logWarning(routineLogIntelligenceLine("Deployment warning detected even at max troops: '" + text + "'"));
		}
		return failing;
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
			dismissQuitGameDialogIfPresent();

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


		// matt, 2026-08-08: max troops instead of Equalize — Equalize was
		// spreading a thin 4-5% ratio per type, which the game itself then
		// flagged as "almost certain to fail" and the bot deployed anyway.
		deploymentHelper.maxAllTroopSliders();
		sleepTask(300);

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

		// matt, 2026-08-08: even at max troops, the game itself can still say
		// this march will lose (not enough total power available right now).
		// Never deploy through that warning — back out, pull any troops
		// still out gathering back to the city to free up more power, and
		// let the normal loop retry this same target on its next pass with
		// more available. This deployment is never allowed to happen while
		// the warning is showing.
		if (isDeploymentCertainToFail()) {
			consecutiveBeastDeploymentFailures++;
			if (consecutiveBeastDeploymentFailures >= MAX_CONSECUTIVE_BEAST_DEPLOYMENT_FAILURES) {
				beastStuckThisRun = true;
				LocalDateTime skipUntil = LocalDateTime.now().plusMinutes(BEAST_STUCK_BACKOFF_MINUTES);
				ConfigService.obtain().writeAccountSetting(profile, ConfigurationKeyEnum.INTEL_BEAST_SKIP_UNTIL_LONG,
						String.valueOf(skipUntil.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()));
				logWarning(routineLogIntelligenceLine(
						"Deployment certain to fail " + consecutiveBeastDeploymentFailures + " times in a row -- "
								+ "this beast is too strong for current troops. Not attempting any more beasts "
								+ "for the rest of this run so Survivor Camps/Explorations are never blocked. "
								+ "Persisting a beast-skip until " + skipUntil.format(DATETIME_FORMATTER)
								+ " so the NEXT run (15 minutes from now) doesn't just re-attack the same beast "
								+ "and fail the same way again."));
			} else {
				logWarning(routineLogIntelligenceLine(
						"Deployment still certain to fail at max troops. Aborting — no march sent, no stamina spent. "
								+ "Recalling gather troops to free up more power before retrying."));
			}
			pressBack();
			pressBack();
			dismissQuitGameDialogIfPresent();
			recallGatherTroopsFlow();
			return;
		}
		consecutiveBeastDeploymentFailures = 0;

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
			dismissQuitGameDialogIfPresent();
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

		// matt, 2026-08-08: record the return ETA ALWAYS, not just in smart mode. With
		// smart=false (matt's Default profile) this was computed from a good OCR read and then
		// silently discarded, so the run that follows a deployment had no idea a march was in
		// flight. Concrete case observed at 14:45: travelSeconds=24, so the beast was dead and
		// claimable by ~14:46:35 — but the next Intel visit was booked for 15:01 off the generic
		// recheck cap, leaving a finished Fire Beast sitting unclaimed on screen for ~14 minutes.
		// That is precisely the "Intel ran, and there's still a beast sitting there" report.
		LocalDateTime beastReturnEta = LocalDateTime.now().plusSeconds(travelTimeSeconds * 2);
		intelBeastReturnTimes.add(beastReturnEta);

		if (useSmartProcessing) {
			logInfo(routineLogIntelligenceLine("Smart Intel beast march return ETA: "
					+ GameTimeUtils.formatCountdown(beastReturnEta)
					+ ". Continuing loop to use remaining available marches."));
		} else {
			logInfo(routineLogIntelligenceLine("Intel beast march return ETA: "
					+ GameTimeUtils.formatCountdown(beastReturnEta)
					+ ". Next Intel run will be pulled forward to claim the reward on arrival."));
		}
	}
}
