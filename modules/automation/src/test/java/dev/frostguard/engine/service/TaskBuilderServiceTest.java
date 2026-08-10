package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

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

            AutomationBlueprint loaded = service.loadDefinition(saved.builderFile().toFile(), "1");
            assertEquals("Expert Idle Exploration", loaded.getName());
            assertEquals("Pause before bag", loaded.getNodes().get(0).getNodeName());
            assertEquals("1", service.getActiveEmulatorNumber());
        } finally {
            System.setProperty("user.dir", originalUserDir);
        }
    }
}
