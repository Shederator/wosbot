package dev.frostguard.engine.schedule;

import dev.frostguard.engine.service.CustomTaskService;

/**
 * Contract for runtime-loaded custom tasks that expose additional settings
 * through the Custom Tasks UI.
 */
public interface CustomTaskConfigurable {

    /**
     * Applies persisted custom task settings to a newly instantiated task.
     *
     * @param settings persisted custom task settings
     */
    void applyCustomTaskSettings(CustomTaskService.CustomTaskSettings settings);
}
