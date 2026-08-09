package dev.frostguard.tasks.combat;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.data.entity.DailyTask;
import dev.frostguard.data.repository.DailyTaskRepository;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.helper.DeploymentHelper;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.engine.service.TaskManagementService;

public class BeastSlayRoutine extends DelayedTask {

	private static final int DEFAULT_STAMINA_RESERVE = 130;

	private final DailyTaskRepository iDailyTaskRepository = DailyTaskRepository.getRepository();
	private final TaskManagementService taskManagementService = TaskManagementService.shared();

	private int maxQueues;
	private int beastLevel;
	private int staminaReserve;

	/** Tracks the earliest time this task should resume (like GatherRoutine pattern). */
	private LocalDateTime earliestReschedule;

	public BeastSlayRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
		super(profile, tpTask);
	}

	@Override
	protected boolean consumesStamina() {
		return true;
	}

	@Override
	protected void execute() {
		earliestReschedule = null;

		// Load configuration
		Integer configMarches = profile.getConfig(ConfigurationKeyEnum.BEAST_HUNTING_MARCHES_INT, Integer.class);
		this.maxQueues = (configMarches != null) ? configMarches : 3;
		Integer configLevel = profile.getConfig(ConfigurationKeyEnum.BEAST_HUNTING_LEVEL_INT, Integer.class);
		this.beastLevel = (configLevel != null) ? configLevel : 30;
		Integer configReserve = profile.getConfig(ConfigurationKeyEnum.STAMINA_RESERVE_INT, Integer.class);
		this.staminaReserve = (configReserve != null) ? configReserve : DEFAULT_STAMINA_RESERVE;

		// Yield to Intel if it is about to run, so Beast Hunting never starves it.
		if (shouldDeferToIntel()) {
			logInfo("Intel task is scheduled to run soon. Planning next Beast run 5 min later.");
			reschedule(LocalDateTime.now().plusMinutes(5));
			return;
		}

		// Only spend stamina above the reserve, so at least `staminaReserve` stays for Intel/Rally.
		int minToAct = staminaReserve + 1;

		// Use staminaHelper to check stamina (already read during initialization/validation)
		if (!staminaHelper.checkStaminaAndMarchesOrReschedule(
				minToAct, staminaReserve + DeploymentHelper.MAX_ATTACK_STAMINA_COST, this)) {
			return;
		}

		int currentStamina = staminaHelper.getCurrentStamina();
		logInfo("Initiating beast attacks. Stamina: " + currentStamina + ", Reserve: " + staminaReserve
				+ ", Max queues: " + maxQueues + ", Beast level: " + beastLevel);

		int attacksDone = 0;

		// Fill available queues with beast attacks, never dipping below the reserve
		while (currentStamina > staminaReserve && attacksDone < maxQueues) {

			sleepTask(6000);
			// Open the creature search menu
			tapInside(new PointData(25, 850), new PointData(67, 898));
			sleepTask(1500);

			// Select the "Beasts" tab by template, swiping the tab row until found.
			// Mirrors PolarTerrorHuntingRoutine.openUpPolarsMenu so an inserted event
			// tab (e.g. "Berserk Cryptid") can no longer shift the blind tap onto the
			// wrong creature. If the tab can't be located, skip rather than mis-attack.
			if (!selectBeastTab()) {
				logWarning("Could not locate the Beasts tab (an event may have changed the search menu). "
						+ "Skipping attack to avoid hitting the wrong creature. Retrying in 5 min.");
				updateReschedule(LocalDateTime.now().plusMinutes(5));
				break;
			}

			// go to level 1
			swipe(new PointData(180, 1050), new PointData(1, 1050));

			// select beast level
			tapInside(new PointData(470, 1040), new PointData(500, 1070), beastLevel - 1, 100);
			sleepTask(1000);
			// click search
			tapInside(new PointData(301, 1200), new PointData(412, 1229));
			sleepTask(6000);

			// click attack - search for the attack button template

			tapInside(new PointData(270, 600), new PointData(460, 630));
			sleepTask(6000);
			
			ImageSearchResultData attackBtn = templateSearchHelper.locatePattern(
					TemplatesEnum.GAME_HOME_SHORTCUTS_ATTACK, SearchConfig.builder().build());
			if (attackBtn != null && attackBtn.isFound()) {
				tapInside(attackBtn);
			}
			
			sleepTask(3000);


			try {
				if (deploymentHelper.hasNoDeployableTroops()) {
					logWarning("No deployable troops are available for Beast Hunting.");
					pressBack();
					updateReschedule(LocalDateTime.now().plusMinutes(5));
					break;
				}
				var deployment = deploymentHelper.readScreen(DeploymentHelper.MAX_ATTACK_STAMINA_COST);
				long travelSeconds = deployment.travelTimeSeconds();
				int staminaCost = deployment.staminaCost();
				if (deploymentHelper.isDeployCostRed()) {
					logWarning("Deploy stamina cost is red; no attack was sent or deducted.");
					pressBack();
					updateReschedule(LocalDateTime.now().plusMinutes(5));
					break;
				}
				if (currentStamina - staminaCost < staminaReserve) {
					int target = staminaReserve + DeploymentHelper.MAX_ATTACK_STAMINA_COST;
					LocalDateTime retryAt = LocalDateTime.now().plusMinutes(
							staminaHelper.staminaRegenerationTime(currentStamina, target));
					logInfo("Deployment costs " + staminaCost + " stamina; current " + currentStamina
							+ " would cross reserve " + staminaReserve + ". Waiting until " + target + ".");
					pressBack();
					deferForStamina(staminaReserve + staminaCost, target, retryAt);
					updateReschedule(retryAt);
					break;
				}

				ImageSearchResultData deploy = templateSearchHelper.locatePattern(
						TemplatesEnum.DEPLOY_BUTTON, SearchConfigConstants.SINGLE_WITH_RETRIES);
				if (!deploy.isFound()) {
					logWarning("Deploy button not found after reading the formation screen.");
					updateReschedule(LocalDateTime.now().plusMinutes(5));
					break;
				}
				tapInside(deploy);
				sleepTask(1000);

				if (deploymentHelper.isSameTargetDialog()) {
					logWarning("Another march is already targeting this beast; cancelling deployment.");
					pressBack();
					pressBack();
					updateReschedule(LocalDateTime.now().plusMinutes(1));
					break;
				}

				ImageSearchResultData deployStillPresent = templateSearchHelper.locatePattern(
						TemplatesEnum.DEPLOY_BUTTON, SearchConfigConstants.SINGLE_WITH_2_RETRIES);
				if (deployStillPresent.isFound()) {
					logWarning("Deploy button remained visible; stamina and statistics were not updated.");
					updateReschedule(LocalDateTime.now().plusMinutes(5));
					break;
				}

				staminaHelper.subtractStamina(staminaCost, false);
				currentStamina = staminaHelper.getCurrentStamina();
				attacksDone++;
				StatisticsService.obtain().addToCounter(profile, "Beast Attacks Sent", 1);

				// March returns in ~2x travel time
				long returnSeconds = (travelSeconds > 0) ? travelSeconds * 2 : 120;
				LocalDateTime marchReturn = LocalDateTime.now().plusSeconds(returnSeconds);
				updateReschedule(marchReturn);

				logInfo("Beast attacked. March returns in ~" + returnSeconds
						+ "s. Cost: " + staminaCost + ", remaining stamina: " + currentStamina
						+ ", attacks done: " + attacksDone);

			} catch (Exception e) {
				logError("Failed during beast attack: " + e.getMessage());
				// Conservative fallback reschedule
				updateReschedule(LocalDateTime.now().plusMinutes(5));
				break;
			}
		}

		// Finalize: reschedule to earliest beast return time (freeing the thread for other tasks)
		finalizeReschedule();
	}

	@Override
	protected LaunchPoint getRequiredStartLocation() {
		return LaunchPoint.WORLD;
	}

	/**
	 * Selects the "Beasts" tab in the creature search menu by locating its icon
	 * and swiping the tab row until it is found. Mirrors the robust approach of
	 * {@link PolarTerrorHuntingRoutine#openUpPolarsMenu}, so an inserted event tab
	 * (e.g. "Berserk Cryptid" during a Cryptid event) can no longer shift a blind
	 * tap onto the wrong creature.
	 *
	 * @return {@code true} once the Beasts tab has been tapped; {@code false} if the
	 *         icon could not be located after several swipes.
	 */
	private boolean selectBeastTab() {
		ImageSearchResultData beastTab = templateSearchHelper.locatePattern(
				TemplatesEnum.BEAST_SEARCH_ICON, SearchConfigConstants.SINGLE_WITH_RETRIES);
		for (int i = 0; i < 4 && (beastTab == null || !beastTab.isFound()); i++) {
			swipe(new PointData(40, 913), new PointData(678, 913));
			sleepTask(500);
			beastTab = templateSearchHelper.locatePattern(
					TemplatesEnum.BEAST_SEARCH_ICON, SearchConfigConstants.SINGLE_WITH_RETRIES);
		}
		if (beastTab == null || !beastTab.isFound()) {
			return false;
		}
		tapInside(beastTab);
		sleepTask(1000);
		return true;
	}

	/**
	 * Mirrors PolarTerrorHuntingRoutine: if Intel is enabled and scheduled to run
	 * within the next 5 minutes, Beast Hunting defers so it doesn't consume stamina
	 * Intel is about to need.
	 */
	private boolean shouldDeferToIntel() {
		if (!Boolean.TRUE.equals(profile.getConfig(ConfigurationKeyEnum.INTEL_BOOL, Boolean.class))) {
			return false;
		}
		if (!taskManagementService.lookupTaskState(profile.getId(), TpDailyTaskEnum.INTEL.getId()).isScheduled()) {
			return false;
		}
		DailyTask intel = iDailyTaskRepository.findByAccountIdAndTaskType(profile.getId(), TpDailyTaskEnum.INTEL);
		return intel != null
				&& ChronoUnit.MINUTES.between(LocalDateTime.now(), intel.getScheduledAt()) < 5;
	}

	// ========================================================================
	// SCHEDULING HELPERS (GatherRoutine pattern)
	// ========================================================================

	private void updateReschedule(LocalDateTime t) {
		if (earliestReschedule == null || t.isBefore(earliestReschedule)) {
			earliestReschedule = t;
		}
	}

	private void finalizeReschedule() {
		if (earliestReschedule != null) {
			logInfo("Beast Hunting finished. Rescheduling to " + earliestReschedule + " (earliest march return).");
			reschedule(earliestReschedule);
		} else {
			logInfo("Beast Hunting finished. No marches dispatched. Rescheduling in 5 minutes.");
			reschedule(LocalDateTime.now().plusMinutes(5));
		}
	}

}
