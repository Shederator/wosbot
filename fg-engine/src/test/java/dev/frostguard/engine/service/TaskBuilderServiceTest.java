package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.frostguard.api.configs.FlowStepKind;
import dev.frostguard.api.domain.AutomationBlueprint;
import dev.frostguard.api.domain.AutomationStep;

class TaskBuilderServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void savesBuilderJsonAndGeneratedJavaBesideIt() throws Exception {
        String originalUserDir = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            TaskBuilderService service = new TaskBuilderService();
            service.startSession("Expert Idle Exploration", "0");

            AutomationStep step = new AutomationStep(1, FlowStepKind.WAIT);
            step.setNodeName("Pause before bag");
            step.setParam("durationMs", "200");
            service.addNode(step);

            Path builderFile = tempDir.resolve("custom_tasks").resolve("expert_idle_exploration.json");
            TaskBuilderService.CustomTaskSaveResult saved =
                    service.saveCurrentTaskToCustomTasks("Expert Idle Exploration", builderFile);

            assertEquals("expert_idle_exploration", saved.className());
            assertTrue(Files.exists(saved.builderFile()));
            assertTrue(Files.exists(saved.javaFile()));

            String javaSource = Files.readString(saved.javaFile());
            assertTrue(javaSource.contains("// Pause before bag"));

            String builderJson = Files.readString(saved.builderFile());
            assertTrue(builderJson.contains("\"stepId\""));
            assertTrue(builderJson.contains("\"kind\""));
            assertTrue(builderJson.contains("\"attributes\""));
            assertTrue(builderJson.contains("\"nodeName\""));
            assertFalse(builderJson.contains("\"id\""));
            assertFalse(builderJson.contains("\"type\""));
            assertFalse(builderJson.contains("\"params\""));
            assertFalse(builderJson.contains("\"summary\""));
            assertFalse(builderJson.contains("\"branching\""));

            JsonNode savedRoot = new ObjectMapper().readTree(builderJson);
            JsonNode savedNode = savedRoot.path("steps").get(0);
            assertFalse(savedNode.has("nodeName"));
            assertTrue(savedNode.path("attributes").has("durationMs"));
            assertEquals("Pause before bag", savedNode.path("attributes").path("nodeName").asText());

            AutomationBlueprint loaded = service.loadDefinition(saved.builderFile().toFile(), "1");
            assertEquals("Expert Idle Exploration", loaded.getName());
            assertEquals("Pause before bag", loaded.getNodes().get(0).getNodeName());
            assertEquals("1", service.getActiveEmulatorNumber());
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }

    @Test
    void loadsLegacyBuilderJsonAliasesWithoutRewritingThem() throws Exception {
        String legacyJson = """
                {
                  "name": "Legacy Flow",
                  "startLocation": "ANY",
                  "nodes": [ {
                    "id": 7,
                    "type": "TEMPLATE_SEARCH",
                    "nodeName": "Find furnace",
                    "params": {
                      "templatePath": "GAME_HOME_FURNACE",
                      "threshold": "91"
                    },
                    "executed": true,
                    "canvasX": 12.0,
                    "canvasY": 34.0,
                    "nextNodeId": 8,
                    "nextNodeFalseId": 9,
                    "summary": "ignored"
                  } ]
                }
                """;

        AutomationBlueprint loaded = new ObjectMapper().readValue(legacyJson, AutomationBlueprint.class);

        assertEquals("Legacy Flow", loaded.getName());
        AutomationStep step = loaded.getNodes().get(0);
        assertEquals(7, step.getStepId());
        assertEquals(FlowStepKind.TEMPLATE_SEARCH, step.getKind());
        assertEquals("Find furnace", step.getNodeName());
        assertEquals("Find furnace", step.getAttributes().get("nodeName"));
        assertEquals("GAME_HOME_FURNACE", step.getParam("templatePath"));
        assertEquals("91", step.getParam("threshold"));
        assertTrue(step.isCompleted());
        assertEquals(12.0, step.getLayoutX());
        assertEquals(34.0, step.getLayoutY());
        assertEquals(8, step.getSuccessorId());
        assertEquals(9, step.getAlternateId());
    }
}
