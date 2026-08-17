package dev.frostguard.api.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.configs.FlowStepKind;
import org.junit.jupiter.api.Test;

class AutomationStepTest {

    /**
     * A malformed conversion in the TEMPLATE_SEARCH branch threw
     * {@code IllegalFormatFlagsException}, which broke every caller of the
     * summary: the editor could not render a template-search card, and
     * serialization aborted part-way through writing the saved flow.
     */
    @Test
    void summarizesTemplateSearchWithoutFailingOnThePercentLiteral() {
        AutomationStep step = new AutomationStep(1, FlowStepKind.TEMPLATE_SEARCH);

        assertEquals("Find: ? @90%  [full]", step.describeBriefly());
    }

    @Test
    void summarizesTemplateSearchWithConfiguredTemplateAndRegion() {
        AutomationStep step = new AutomationStep(1, FlowStepKind.TEMPLATE_SEARCH);
        step.setParam("templatePath", "HOME_DEALS_BUTTON");
        step.setParam("threshold", "85");
        step.setParam("grayscale", "true");
        step.setParam("tlX", "10");
        step.setParam("brX", "40");

        assertEquals("Find: HOME_DEALS_BUTTON @85%  GS [area]", step.describeBriefly());
    }

    /** Every kind must produce a summary; none may throw. */
    @Test
    void summarizesEveryStepKind() {
        for (FlowStepKind kind : FlowStepKind.values()) {
            AutomationStep step = new AutomationStep(1, kind);

            String summary = step.describeBriefly();

            assertNotNull(summary, "no summary for " + kind);
            assertTrue(!summary.isBlank(), "blank summary for " + kind);
        }
    }

    @Test
    void sanitizesNodeNameForReadableSavedMetadata() {
        AutomationStep step = new AutomationStep(1, FlowStepKind.WAIT);

        step.setNodeName("  first line\nsecond line with too much detail  ");

        assertEquals("first line second line with to", step.getNodeName());
        assertEquals("first line second line with to", step.getParam(AutomationStep.PARAM_NODE_NAME));
    }

    /** A node name written straight into the attribute map is still sanitized. */
    @Test
    void sanitizesNodeNameSuppliedThroughTheAttributeMap() {
        AutomationStep step = new AutomationStep(1, FlowStepKind.WAIT);

        step.setParams(java.util.Map.of(AutomationStep.PARAM_NODE_NAME, "  raw\nname  "));

        assertEquals("raw name", step.getNodeName());
    }

    /** Replacing the attribute map must not alias the caller's map. */
    @Test
    void copiesSuppliedAttributesInsteadOfAliasingThem() {
        AutomationStep step = new AutomationStep(1, FlowStepKind.WAIT);
        java.util.Map<String, String> supplied = new java.util.HashMap<>();
        supplied.put("durationMs", "500");

        step.setAttributes(supplied);
        supplied.put("durationMs", "999");

        assertEquals("500", step.getParam("durationMs"));
    }

    @Test
    void treatsNullAttributesAsAnEmptyMap() {
        AutomationStep step = new AutomationStep(1, FlowStepKind.WAIT);

        step.setAttributes(null);

        assertNotNull(step.getAttributes());
        assertTrue(step.getAttributes().isEmpty());
    }
}
