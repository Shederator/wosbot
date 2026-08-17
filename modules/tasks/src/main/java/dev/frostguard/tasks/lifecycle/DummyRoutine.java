package dev.frostguard.tasks.lifecycle;

import dev.frostguard.engine.schedule.LaunchPoint;


import java.time.LocalDateTime;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;

/**
 * Dummy Task that runs infinitely until it finds a specific image.
 * If found, it reschedules itself to run again in 60 minutes.
 * Checks every 5 seconds.
 */
public class DummyRoutine extends DelayedTask {

    public DummyRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected void execute() {
        logInfo("Searching for GO image...");

        while (true) {
            // Check for preemption or stop signals relative to the task framework
            // (The framework usually handles interrupts, but good to be safe in infinite
            // loops)
            if (Thread.currentThread().isInterrupted()) {
                break;
            }

            // Check if task is still enabled in configuration
            try {
                // Refresh specific config to see if we should continue
                Boolean enabled = dev.frostguard.engine.service.ProfileService.obtain().fetchAllAccounts().stream()
                        .filter(p -> p.getId().equals(profile.getId()))
                        .findFirst()
                        .map(p -> p.getConfig(
                                dev.frostguard.api.configs.ConfigurationKeyEnum.DUMMY_TASK_ENABLED_BOOL,
                                Boolean.class))
                        .orElse(false);

                if (enabled != null && !enabled) {
                    logInfo("Dummy Task disabled in configs. Stopping task.");
                    break;
                }
            } catch (Exception e) {
                logWarning("Failed to check task status: " + e.getMessage());
            }

            // Search for the image
            ImageSearchResultData result = templateSearchHelper.locatePattern(
                    TemplatesEnum.GAME_HOME_SHORTCUTS_GO,
                    SearchConfig.builder().build());

            if (result.isFound()) {
                logInfo("Image found! Rescheduling task for 60 minutes from now.");

                // Reschedule for 60 minutes later
                reschedule(LocalDateTime.now().plusMinutes(60));

                // Exit the loop and finish the task
                break;
            } else {
                logInfo("Image not found. Waiting 5 seconds...");
                sleepTask(5000); // Wait 5 seconds before next check
            }
        }
    }
}
