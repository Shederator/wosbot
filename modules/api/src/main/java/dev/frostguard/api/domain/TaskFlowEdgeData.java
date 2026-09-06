package dev.frostguard.api.domain;

public record TaskFlowEdgeData(String fromStepId, String toStepId, String label) {

    public TaskFlowEdgeData {
        if (fromStepId == null || fromStepId.isBlank()) {
            throw new IllegalArgumentException("Task flow edge source is required");
        }
        if (toStepId == null || toStepId.isBlank()) {
            throw new IllegalArgumentException("Task flow edge destination is required");
        }
        fromStepId = fromStepId.trim();
        toStepId = toStepId.trim();
        label = label == null ? "" : label.trim();
    }
}
