package dev.frostguard.api.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.FlowStepKind;

class AutomationStepTest {

    @Test
    void describesTemplateSearchWithoutThrowingOnPercentSign() {
        AutomationStep step = new AutomationStep(1, FlowStepKind.TEMPLATE_SEARCH);

        assertEquals("Find: ? @90%  [full]", step.describeBriefly());
    }

    @Test
    void sanitizesNodeNameForReadableSavedMetadata() {
        AutomationStep step = new AutomationStep(1, FlowStepKind.WAIT);

        step.setNodeName("  first line\nsecond line with too much detail  ");

        assertEquals("first line second line with to", step.getNodeName());
        assertEquals("first line second line with to", step.getParam(AutomationStep.PARAM_NODE_NAME));
    }
}
