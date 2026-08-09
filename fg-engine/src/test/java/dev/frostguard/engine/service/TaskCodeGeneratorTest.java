package dev.frostguard.engine.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.FlowStepKind;
import dev.frostguard.api.domain.AutomationBlueprint;
import dev.frostguard.api.domain.AutomationStep;

class TaskCodeGeneratorTest {

    @Test
    void emitsPackageRelativeTemplateSearchAsFileLookup() {
        AutomationBlueprint blueprint = new AutomationBlueprint("Dead Shot");
        AutomationStep node = new AutomationStep(1, FlowStepKind.TEMPLATE_SEARCH);
        node.setParam("templatePath", "templates/deals/deadshot/event_tab.png");
        blueprint.addNode(node);

        String source = new TaskCodeGenerator().generate(blueprint, "DeadShot", "Dead Shot");

        assertTrue(source.contains("TemplatePathResolver.resolveFileReference(\"templates/deals/deadshot/event_tab.png\")"));
        assertTrue(source.contains("locatePatternFromFile"));
        assertFalse(source.contains("TemplatesEnum.templates"));
    }
}
