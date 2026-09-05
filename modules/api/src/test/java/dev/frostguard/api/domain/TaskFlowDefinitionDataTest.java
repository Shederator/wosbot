package dev.frostguard.api.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskFlowDefinitionDataTest {

    @Test
    void rejectsEdgesThatReferenceUnknownNodes() {
        List<TaskFlowNodeData> nodes = List.of(new TaskFlowNodeData("first", "First"));

        assertThrows(IllegalArgumentException.class, () -> new TaskFlowDefinitionData(
                "first", nodes, List.of(new TaskFlowEdgeData("first", "missing", "branch"))));
    }

    @Test
    void createsSingleStepFlowWithMatchingEntry() {
        TaskFlowDefinitionData flow = TaskFlowDefinitionData.singleStep("task", "Existing task");

        assertEquals("task", flow.entryStepId());
        assertEquals("Existing task", flow.entryStep().label());
    }
}
