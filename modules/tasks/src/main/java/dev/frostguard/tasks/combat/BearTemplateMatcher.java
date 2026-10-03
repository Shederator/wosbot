package dev.frostguard.tasks.combat;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
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
        RawImageData region = copyRegion(frame, area);
        if (region != null && switch (template) {
            case GAME_HOME_RECONNECT, GAME_START_DOWNLOAD_NOW, GAME_START_MANDATORY_UPDATE_TITLE -> true;
            default -> false;
        }) {
            return controller.locatePatternWithProjectionRejection(device, region, template,
                    new PointData(0, 0), new PointData(region.getWidth(), region.getHeight()), threshold).isFound();
        }
        return region != null && controller.locatePattern(device, region, template,
                new PointData(0, 0), new PointData(region.getWidth(), region.getHeight()), threshold).isFound();
    }

    /** Same exclusive lower-right bounds as the matcher, before expensive full-frame conversion. */
    static RawImageData copyRegion(RawImageData frame, AreaData area) {
        int bytes = switch (frame.getBpp()) {
            case 2, 16 -> 2;
            case 4, 32 -> 4;
            default -> 0;
        };
        int x = area.topLeft().getX(), y = area.topLeft().getY();
        int width = area.bottomRight().getX() - x, height = area.bottomRight().getY() - y;
        if (bytes == 0 || x < 0 || y < 0 || width <= 0 || height <= 0
                || area.bottomRight().getX() > frame.getWidth()
                || area.bottomRight().getY() > frame.getHeight()
                || frame.getData() == null
                || frame.getData().length < (long) frame.getWidth() * frame.getHeight() * bytes) return null;
        byte[] pixels = new byte[width * height * bytes];
        for (int row = 0; row < height; row++) {
            System.arraycopy(frame.getData(), ((y + row) * frame.getWidth() + x) * bytes,
                    pixels, row * width * bytes, width * bytes);
        }
        return RawImageData.capture(pixels, width, height, frame.getBpp());
    }
}
