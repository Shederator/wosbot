package dev.frostguard.api.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.frostguard.api.configs.FlowStepKind;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Locks the persisted shape of a task-builder flow.
 *
 * <p>Both domain types keep legacy accessor shims and derived read-only views
 * alongside their canonical accessors. When Jackson auto-detected those
 * methods, every value was written two or three times under different names and
 * each derived getter ran during the write — so one failing getter aborted
 * serialization mid-document and left a truncated, unparseable file behind.
 * These tests pin the canonical key set, prove the file stays parseable, and
 * prove that flows saved by older builds still load.</p>
 */
class AutomationBlueprintSerializationTest {

    /** Mirrors the mapper configuration used by the task-builder service. */
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    private AutomationBlueprint sampleFlow() {
        AutomationBlueprint blueprint = new AutomationBlueprint("dead_shot");
        blueprint.setStartLocation("HOME");

        AutomationStep find = new AutomationStep(0, FlowStepKind.TEMPLATE_SEARCH);
        find.setNodeName("search deal");
        find.setParam("templatePath", "HOME_DEALS_BUTTON");
        find.setParam("threshold", "90");
        blueprint.addNode(find);

        AutomationStep wait = new AutomationStep(0, FlowStepKind.WAIT);
        wait.setNodeName("wait for panel");
        wait.setParam("durationMs", "1500");
        blueprint.addNode(wait);

        return blueprint;
    }

    /**
     * The reported symptom: saving a flow containing a template-search node
     * produced a file that stopped mid-object.
     */
    @Test
    void savesAFlowWithATemplateSearchNodeAsCompleteParseableJson() throws Exception {
        String json = mapper.writeValueAsString(sampleFlow());

        JsonNode parsed = mapper.readTree(json);

        assertEquals("dead_shot", parsed.path("title").asText());
        assertEquals(2, parsed.path("steps").size());
    }

    @Test
    void writesOnlyCanonicalStepKeys() throws Exception {
        JsonNode step = mapper.readTree(mapper.writeValueAsString(sampleFlow()))
                .path("steps").path(0);

        List<String> actual = new java.util.ArrayList<>();
        step.fieldNames().forEachRemaining(actual::add);
        java.util.Collections.sort(actual);

        assertEquals(List.of("alternateId", "attributes", "completed", "kind",
                        "lastReadValue", "layoutX", "layoutY", "stepId", "successorId"),
                actual);
    }

    @Test
    void writesOnlyCanonicalBlueprintKeys() throws Exception {
        JsonNode parsed = mapper.readTree(mapper.writeValueAsString(sampleFlow()));

        List<String> actual = new java.util.ArrayList<>();
        parsed.fieldNames().forEachRemaining(actual::add);
        java.util.Collections.sort(actual);

        assertEquals(List.of("createdEpochMs", "initialScreen", "modifiedEpochMs",
                        "notes", "steps", "title"),
                actual);
    }

    /**
     * Derived, computed accessors must never be invoked while writing: that is
     * what let a single failing getter corrupt the whole document.
     */
    @Test
    void neverWritesDerivedOrComputedViews() throws Exception {
        String json = mapper.writeValueAsString(sampleFlow());

        for (String derived : List.of("\"summary\"", "\"branching\"", "\"region\"",
                "\"successor\"", "\"alternate\"", "\"attributeCount\"", "\"nextStepId\"")) {
            assertFalse(json.contains(derived), "derived view leaked into the saved file: " + derived);
        }
    }

    @Test
    void preservesNodeMetadataAcrossASaveAndReload() throws Exception {
        AutomationBlueprint reloaded =
                mapper.readValue(mapper.writeValueAsString(sampleFlow()), AutomationBlueprint.class);

        assertEquals("dead_shot", reloaded.getTitle());
        assertEquals("HOME", reloaded.getInitialScreen());
        assertEquals(2, reloaded.getSteps().size());
        assertEquals("search deal", reloaded.getSteps().get(0).getNodeName());
        assertEquals(FlowStepKind.TEMPLATE_SEARCH, reloaded.getSteps().get(0).getKind());
        assertEquals("HOME_DEALS_BUTTON", reloaded.getSteps().get(0).getParam("templatePath"));
        assertEquals("wait for panel", reloaded.getSteps().get(1).getNodeName());
    }

    /** Flows saved by earlier builds used the duplicated legacy key spellings. */
    @Test
    void loadsFlowsSavedWithLegacyKeyNames() throws Exception {
        String legacy = """
                {
                  "name": "legacy flow",
                  "description": "notes",
                  "startLocation": "WORLD",
                  "createdAt": 111,
                  "updatedAt": 222,
                  "nodes": [ {
                    "id": 7,
                    "type": "WAIT",
                    "params": { "durationMs": "500", "nodeName": "pause" },
                    "executed": true,
                    "canvasX": 12.0,
                    "canvasY": 34.0,
                    "nextNodeId": 9,
                    "nextNodeFalseId": -1,
                    "lastOcrResult": "read"
                  } ]
                }
                """;

        AutomationBlueprint blueprint = mapper.readValue(legacy, AutomationBlueprint.class);
        AutomationStep step = blueprint.getSteps().get(0);

        assertEquals("legacy flow", blueprint.getTitle());
        assertEquals("notes", blueprint.getNotes());
        assertEquals("WORLD", blueprint.getInitialScreen());
        assertEquals(111, blueprint.getCreatedEpochMs());
        assertEquals(222, blueprint.getModifiedEpochMs());
        assertEquals(7, step.getStepId());
        assertEquals(FlowStepKind.WAIT, step.getKind());
        assertEquals("500", step.getParam("durationMs"));
        assertEquals("pause", step.getNodeName());
        assertTrue(step.isCompleted());
        assertEquals(12.0, step.getLayoutX());
        assertEquals(34.0, step.getLayoutY());
        assertEquals(9, step.getSuccessorId());
        assertEquals(-1, step.getAlternateId());
        assertEquals("read", step.getLastReadValue());
    }

    /** A hand-edited file may spell a collection as null; that must not crash the editor. */
    @Test
    void treatsExplicitNullCollectionsAsEmpty() throws Exception {
        AutomationBlueprint blueprint =
                mapper.readValue("{\"title\":\"t\",\"steps\":null}", AutomationBlueprint.class);
        AutomationStep step =
                mapper.readValue("{\"stepId\":1,\"attributes\":null}", AutomationStep.class);

        assertNotNull(blueprint.getSteps());
        assertTrue(blueprint.getSteps().isEmpty());
        assertNotNull(step.getAttributes());
        assertTrue(step.getAttributes().isEmpty());
    }
}
