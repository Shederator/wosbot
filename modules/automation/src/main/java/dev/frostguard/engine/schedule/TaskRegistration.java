package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.ControlledExecutionCapability;
import dev.frostguard.api.configs.TpDailyTaskEnum;

import java.util.Objects;

public record TaskRegistration(
        TpDailyTaskEnum taskType,
        ControlledExecutionCapability controlledExecutionCapability) {

    public TaskRegistration {
        Objects.requireNonNull(taskType, "taskType");
        Objects.requireNonNull(controlledExecutionCapability, "controlledExecutionCapability");
    }
}
