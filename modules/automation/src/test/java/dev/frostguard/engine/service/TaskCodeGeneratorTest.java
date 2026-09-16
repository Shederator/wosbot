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

    @Test
    void generatesSharedShopNavigationWithSafeFailureHandling() {
        AutomationBlueprint blueprint = new AutomationBlueprint("Open Gem Shop");
        AutomationStep step = new AutomationStep(1, FlowStepKind.SHOP_NAVIGATION);
        step.setParam(AutomationStep.PARAM_SHOP_TAB, "GEM_SHOP");
        blueprint.addNode(step);

        String source = new TaskCodeGenerator().generate(blueprint, "open_gem_shop", "Open Gem Shop");

        assertTrue(source.contains("import dev.frostguard.engine.nav.ShopTab;"));
        assertTrue(source.contains("if (!navigationHelper.navigateToShop(ShopTab.GEM_SHOP))"));
        assertTrue(source.contains("logWarning(\"Shop navigation failed: Gem Shop\")"));
        assertTrue(source.contains("__state = -1;"));
    }

    @Test
    void rejectsShopNavigationWithoutAValidTab() {
        AutomationBlueprint blueprint = new AutomationBlueprint("Invalid Shop");
        AutomationStep step = new AutomationStep(7, FlowStepKind.SHOP_NAVIGATION);
        step.setParam(AutomationStep.PARAM_SHOP_TAB, "NOT_A_SHOP");
        blueprint.addNode(step);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new TaskCodeGenerator().generate(blueprint, "invalid_shop", "Invalid Shop"));
    }

    @Test
    void generatesBothSidebarOperationsWithSafeFailureHandling() {
        AutomationBlueprint blueprint = new AutomationBlueprint("Sidebar probe");
        AutomationStep section = new AutomationStep(1, FlowStepKind.SIDEBAR_NAVIGATION);
        section.setParam(AutomationStep.PARAM_SIDEBAR_MODE, "SECTION");
        section.setParam(AutomationStep.PARAM_SIDEBAR_TARGET, "DAILY");
        blueprint.addNode(section);
        AutomationStep destination = new AutomationStep(2, FlowStepKind.SIDEBAR_NAVIGATION);
        destination.setParam(AutomationStep.PARAM_SIDEBAR_MODE, "DESTINATION");
        destination.setParam(AutomationStep.PARAM_SIDEBAR_TARGET, "ARENA");
        blueprint.addNode(destination);

        String source = new TaskCodeGenerator().generate(blueprint, "sidebar_probe", "Sidebar probe");

        assertTrue(source.contains("navigationHelper.openSidebarSection(SidebarSection.DAILY)"));
        assertTrue(source.contains("navigationHelper.navigateToSidebarDestination(SidebarDestination.ARENA)"));
        assertTrue(source.contains("logWarning(\"Sidebar navigation failed: SECTION DAILY\")"));
        assertTrue(source.contains("logWarning(\"Sidebar navigation failed: DESTINATION ARENA\")"));
        assertTrue(source.contains("__state = -1;"));
    }

    @Test
    void rejectsSidebarNavigationWithMismatchedModeAndTarget() {
        AutomationBlueprint blueprint = new AutomationBlueprint("Invalid Sidebar");
        AutomationStep step = new AutomationStep(7, FlowStepKind.SIDEBAR_NAVIGATION);
        step.setParam(AutomationStep.PARAM_SIDEBAR_MODE, "SECTION");
        step.setParam(AutomationStep.PARAM_SIDEBAR_TARGET, "ARENA");
        blueprint.addNode(step);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new TaskCodeGenerator().generate(blueprint, "invalid_sidebar", "Invalid Sidebar"));
    }

    @Test
    void generatesAllianceAndEventNavigationWithSafeFailureHandling() {
        AutomationBlueprint blueprint = new AutomationBlueprint("Menu probe");
        AutomationStep alliance = new AutomationStep(1, FlowStepKind.ALLIANCE_NAVIGATION);
        alliance.setParam(AutomationStep.PARAM_ALLIANCE_MENU, "TERRITORY");
        blueprint.addNode(alliance);
        AutomationStep event = new AutomationStep(2, FlowStepKind.EVENT_NAVIGATION);
        event.setParam(AutomationStep.PARAM_EVENT_MENU, "ALLIANCE_CHAMPIONSHIP");
        blueprint.addNode(event);

        String source = new TaskCodeGenerator().generate(blueprint, "menu_probe", "Menu probe");

        assertTrue(source.contains("navigationHelper.navigateToAllianceMenu(AllianceMenu.TERRITORY)"));
        assertTrue(source.contains("navigationHelper.navigateToEventMenu(EventMenu.ALLIANCE_CHAMPIONSHIP)"));
        assertTrue(source.contains("logWarning(\"Alliance navigation failed: TERRITORY\")"));
        assertTrue(source.contains("logWarning(\"Event navigation failed: ALLIANCE_CHAMPIONSHIP\")"));
        assertTrue(source.contains("__state = -1;"));
    }

    @Test
    void rejectsInvalidAllianceAndEventTargets() {
        for (FlowStepKind kind : new FlowStepKind[] {
                FlowStepKind.ALLIANCE_NAVIGATION, FlowStepKind.EVENT_NAVIGATION }) {
            AutomationBlueprint blueprint = new AutomationBlueprint("Invalid menu");
            AutomationStep step = new AutomationStep(7, kind);
            step.setParam(kind == FlowStepKind.ALLIANCE_NAVIGATION
                    ? AutomationStep.PARAM_ALLIANCE_MENU : AutomationStep.PARAM_EVENT_MENU, "UNKNOWN");
            blueprint.addNode(step);

            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new TaskCodeGenerator().generate(blueprint, "invalid_menu", "Invalid menu"));
        }
    }
}
