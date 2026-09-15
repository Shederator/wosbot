package dev.frostguard.api.domain;

public record TaskFlowNodeData(String id, String label) {

    public TaskFlowNodeData {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Task flow node id is required");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Task flow node label is required");
        }
        id = id.trim();
        label = label.trim();
    }
}
