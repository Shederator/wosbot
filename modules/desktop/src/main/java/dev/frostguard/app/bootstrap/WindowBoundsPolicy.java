package dev.frostguard.app.bootstrap;

import javafx.geometry.Rectangle2D;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

final class WindowBoundsPolicy {

    private WindowBoundsPolicy() {
    }

    static Optional<WindowBounds> recover(WindowBounds saved, List<Rectangle2D> screens,
                                           double minimumVisibleSide) {
        if (!saved.isValid() || screens.isEmpty()) {
            return Optional.empty();
        }

        Optional<Rectangle2D> bestScreen = screens.stream()
                .max(Comparator.comparingDouble(screen -> visibleArea(screen, saved)));
        if (bestScreen.isEmpty()
                || visibleArea(bestScreen.get(), saved) < minimumVisibleSide * minimumVisibleSide) {
            return Optional.empty();
        }

        Rectangle2D screen = bestScreen.get();
        double width = Math.min(saved.width(), screen.getWidth());
        double height = Math.min(saved.height(), screen.getHeight());
        double x = clamp(saved.x(), screen.getMinX(), screen.getMaxX() - width);
        double y = clamp(saved.y(), screen.getMinY(), screen.getMaxY() - height);
        return Optional.of(new WindowBounds(x, y, width, height));
    }

    private static double visibleArea(Rectangle2D screen, WindowBounds window) {
        double visibleLeft = Math.max(window.x(), screen.getMinX());
        double visibleTop = Math.max(window.y(), screen.getMinY());
        double visibleRight = Math.min(window.x() + window.width(), screen.getMaxX());
        double visibleBottom = Math.min(window.y() + window.height(), screen.getMaxY());
        return Math.max(0, visibleRight - visibleLeft) * Math.max(0, visibleBottom - visibleTop);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    record WindowBounds(double x, double y, double width, double height) {

        boolean isValid() {
            return Double.isFinite(x) && Double.isFinite(y)
                    && Double.isFinite(width) && width > 0
                    && Double.isFinite(height) && height > 0;
        }
    }
}
