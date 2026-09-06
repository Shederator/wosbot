package dev.frostguard.api.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record TaskFlowDefinitionData(
        String entryStepId,
        List<TaskFlowNodeData> nodes,
        List<TaskFlowEdgeData> edges) {

    public TaskFlowDefinitionData {
        if (entryStepId == null || entryStepId.isBlank()) {
            throw new IllegalArgumentException("Task flow entry step is required");
        }
        entryStepId = entryStepId.trim();
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        validate(entryStepId, nodes, edges);
    }

    public static TaskFlowDefinitionData singleStep(String id, String label) {
        return new TaskFlowDefinitionData(id, List.of(new TaskFlowNodeData(id, label)), List.of());
    }

    public TaskFlowNodeData entryStep() {
        return nodes.stream()
                .filter(node -> node.id().equals(entryStepId))
                .findFirst()
                .orElseThrow();
    }

    private static void validate(String entryStepId, List<TaskFlowNodeData> nodes,
            List<TaskFlowEdgeData> edges) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("Task flow requires at least one node");
        }
        Set<String> ids = new HashSet<>();
        for (TaskFlowNodeData node : nodes) {
            if (node == null || !ids.add(node.id())) {
                throw new IllegalArgumentException("Task flow node ids must be non-null and unique");
            }
        }
        if (!ids.contains(entryStepId)) {
            throw new IllegalArgumentException("Task flow entry step is not registered");
        }
        for (TaskFlowEdgeData edge : edges) {
            if (edge == null || !ids.contains(edge.fromStepId()) || !ids.contains(edge.toStepId())) {
                throw new IllegalArgumentException("Task flow edges must reference registered nodes");
            }
        }
    }
}
