package dev.frostguard.engine.schedule;

import dev.frostguard.api.configs.ControlledExecutionCapability;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.TaskFlowDefinitionData;

import java.util.Objects;

public record TaskRegistration(
        TpDailyTaskEnum taskType,
        ControlledExecutionCapability controlledExecutionCapability,
        TaskFlowDefinitionData flowDefinition) {

    public TaskRegistration {
        Objects.requireNonNull(taskType, "taskType");
        Objects.requireNonNull(controlledExecutionCapability, "controlledExecutionCapability");
        Objects.requireNonNull(flowDefinition, "flowDefinition");
    }

    public TaskRegistration(TpDailyTaskEnum taskType,
            ControlledExecutionCapability controlledExecutionCapability) {
        this(taskType, controlledExecutionCapability,
                TaskFlowDefinitionData.singleStep(taskType.name(), taskType.getName()));
    }
}
