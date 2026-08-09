package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.TpDailyTaskEnum;

/**
 * Prevents profile work from running until initialization has established the
 * configured in-game character.
 */
final class TaskInitializationGate {

    private boolean initializationRequired = true;

    boolean isInitializationRequired() {
        return initializationRequired;
    }

    boolean allows(TpDailyTaskEnum taskType) {
        return !initializationRequired || taskType == TpDailyTaskEnum.INITIALIZE;
    }

    void requireInitialization() {
        initializationRequired = true;
    }

    void completeInitialization() {
        initializationRequired = false;
    }
}
