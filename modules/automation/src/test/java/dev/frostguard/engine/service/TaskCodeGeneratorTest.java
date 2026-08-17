package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.configs.FlowStepKind;
import dev.frostguard.api.domain.AutomationBlueprint;
import dev.frostguard.api.domain.AutomationStep;
import org.junit.jupiter.api.Test;

class TaskCodeGeneratorTest {

    private String generateFor(String templatePath) {
        AutomationBlueprint blueprint = new AutomationBlueprint("Find Deals");
        AutomationStep step = new AutomationStep(0, FlowStepKind.TEMPLATE_SEARCH);
        step.setParam("templatePath", templatePath);
        blueprint.addNode(step);

        return new TaskCodeGenerator().generate(blueprint, "find_deals", "Find Deals");
    }

    /**
     * Baking the absolute path of the authoring machine into the generated task
     * made the task unusable anywhere else. The generated code now stores the
     * reference and resolves it at run time.
     */
    @Test
    void resolvesFileTemplatesAtRunTimeInsteadOfHardCodingTheAuthoringPath() {
        String source = generateFor("templates/deals/event_tab.png");

        assertTrue(source.contains("import dev.frostguard.engine.service.TemplatePathResolver;"),
                "generated task must import the resolver");
        assertTrue(source.contains("TemplatePathResolver.resolveFileReference(\"templates/deals/event_tab.png\")"),
                "generated task must resolve the stored reference at run time");
    }

    @Test
    void escapesWindowsSeparatorsInGeneratedStringLiterals() {
        String source = generateFor(TemplatePathResolver.FILE_PREFIX + "C:\\ops\\tpl.png");

        assertTrue(source.contains("resolveFileReference(\"file://C:\\\\ops\\\\tpl.png\")"),
                "backslashes must be escaped for the Java literal");
    }

    /** Enum-backed templates keep using the bundled classpath assets. */
    @Test
    void keepsUsingEnumTemplatesForBundledAssets() {
        String source = generateFor("HOME_DEALS_BUTTON");

        assertTrue(source.contains("TemplatesEnum.HOME_DEALS_BUTTON"));
        assertFalse(source.contains("resolveFileReference"),
                "enum templates must not be routed through the file resolver");
    }

    /** A node name is emitted as a comment so generated code stays readable. */
    @Test
    void carriesNodeNamesIntoTheGeneratedSourceAsComments() {
        AutomationBlueprint blueprint = new AutomationBlueprint("Wait Flow");
        AutomationStep step = new AutomationStep(0, FlowStepKind.WAIT);
        step.setNodeName("pause before bag");
        step.setParam("durationMs", "250");
        blueprint.addNode(step);

        String source = new TaskCodeGenerator().generate(blueprint, "wait_flow", "Wait Flow");

        assertTrue(source.contains("// pause before bag"));
    }
}
