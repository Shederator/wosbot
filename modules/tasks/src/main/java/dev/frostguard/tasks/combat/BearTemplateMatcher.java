package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.nav.CommonGameAreas;

/** Same CPU-only matcher for live observations and replay; never obtains a new capture. */
final class BearTemplateMatcher implements BearFrameClassifier.TemplateMatcher {
    private final EmulatorController controller;
    private final String device;

    BearTemplateMatcher(EmulatorController controller, String device) {
        this.controller = controller;
        this.device = device;
    }

    @Override
    public boolean found(RawImageData frame, TemplatesEnum template, int threshold) {
        return foundIn(frame, template, threshold, CommonGameAreas.bearClassifierSearchArea(template));
    }

    @Override
    public boolean foundIn(RawImageData frame, TemplatesEnum template, int threshold,
            dev.frostguard.api.domain.AreaData area) {
        if (frame.getWidth() != 720 || frame.getHeight() != 1280) {
            return false; // No calibrated targets or search regions at other geometries.
        }
        return controller.locatePattern(device, frame, template,
                area.topLeft(), area.bottomRight(), threshold).isFound();
    }
}
