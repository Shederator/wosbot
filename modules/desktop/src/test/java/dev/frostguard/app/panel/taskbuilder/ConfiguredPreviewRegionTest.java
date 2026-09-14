package dev.frostguard.app.panel.taskbuilder;

import dev.frostguard.api.configs.FlowStepKind;
import dev.frostguard.api.domain.AutomationStep;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredPreviewRegionTest {

    @Test
    void projectsConfiguredTapAndOcrAreasOntoTheCapturedPreview() {
        for (FlowStepKind type : new FlowStepKind[] {FlowStepKind.TAP_POINT, FlowStepKind.OCR_READ}) {
            AutomationStep node = regionNode(type, "100", "200", "300", "400");

            ConfiguredPreviewRegion region = ConfiguredPreviewRegion.forNode(
                    node, 720, 1280, 360, 640).orElseThrow();

            assertEquals(52, region.x());
            assertEquals(102, region.y());
            assertEquals(100, region.width());
            assertEquals(100, region.height());
        }
    }

    @Test
    void hidesIncompleteOrInvalidAreasInsteadOfShowingMisleadingBounds() {
        AutomationStep missing = regionNode(FlowStepKind.OCR_READ, "100", "200", "300", "400");
        missing.getParams().remove("brY");

        assertTrue(ConfiguredPreviewRegion.forNode(missing, 720, 1280, 360, 640).isEmpty());
        assertTrue(ConfiguredPreviewRegion.forNode(
                regionNode(FlowStepKind.TAP_POINT, "100", "200", "100", "400"),
                720, 1280, 360, 640).isEmpty());
        assertTrue(ConfiguredPreviewRegion.forNode(
                regionNode(FlowStepKind.OCR_READ, "100", "bad", "300", "400"),
                720, 1280, 360, 640).isEmpty());
        assertTrue(ConfiguredPreviewRegion.forNode(
                regionNode(FlowStepKind.OCR_READ, "100", "200", "800", "400"),
                720, 1280, 360, 640).isEmpty());
        assertTrue(ConfiguredPreviewRegion.forNode(
                regionNode(FlowStepKind.OCR_READ, "100", "200", "300", "400"),
                720, 1280, 0, 640).isEmpty());
    }

    private AutomationStep regionNode(FlowStepKind type, String left, String top, String right, String bottom) {
        AutomationStep node = new AutomationStep(1, type);
        node.setParam("tlX", left);
        node.setParam("tlY", top);
        node.setParam("brX", right);
        node.setParam("brY", bottom);
        return node;
    }
}
