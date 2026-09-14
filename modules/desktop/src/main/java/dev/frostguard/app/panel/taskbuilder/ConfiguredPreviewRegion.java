package dev.frostguard.app.panel.taskbuilder;

import dev.frostguard.api.configs.FlowStepKind;
import dev.frostguard.api.domain.AutomationStep;

import java.util.Optional;

record ConfiguredPreviewRegion(double x, double y, double width, double height) {

    private static final double PREVIEW_INSET = 2.0;

    static Optional<ConfiguredPreviewRegion> forNode(AutomationStep node,
                                                      double imageWidth, double imageHeight,
                                                      double viewWidth, double viewHeight) {
        if (node == null || (node.getType() != FlowStepKind.TAP_POINT
                && node.getType() != FlowStepKind.OCR_READ)
                || !Double.isFinite(imageWidth) || !Double.isFinite(imageHeight)
                || !Double.isFinite(viewWidth) || !Double.isFinite(viewHeight)
                || imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return Optional.empty();
        }

        try {
            int left = Integer.parseInt(node.getParam("tlX").trim());
            int top = Integer.parseInt(node.getParam("tlY").trim());
            int right = Integer.parseInt(node.getParam("brX").trim());
            int bottom = Integer.parseInt(node.getParam("brY").trim());
            if (left < 0 || top < 0 || right <= left || bottom <= top
                    || right > imageWidth || bottom > imageHeight) {
                return Optional.empty();
            }

            double scaleX = viewWidth / imageWidth;
            double scaleY = viewHeight / imageHeight;
            return Optional.of(new ConfiguredPreviewRegion(
                    PREVIEW_INSET + left * scaleX,
                    PREVIEW_INSET + top * scaleY,
                    (right - left) * scaleX,
                    (bottom - top) * scaleY));
        } catch (NumberFormatException | NullPointerException invalidCoordinates) {
            return Optional.empty();
        }
    }
}
