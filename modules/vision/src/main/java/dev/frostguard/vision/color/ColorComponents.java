package dev.frostguard.vision.color;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/** Finds contiguous regions of pixels selected by a caller-defined colour predicate. */
public final class ColorComponents {

    private ColorComponents() {
    }

    public static List<Component> find(BufferedImage image, AreaData area, IntPredicate matches, int minimumPixels) {
        int left = Math.max(0, area.topLeft().getX());
        int top = Math.max(0, area.topLeft().getY());
        int right = Math.min(image.getWidth() - 1, area.bottomRight().getX());
        int bottom = Math.min(image.getHeight() - 1, area.bottomRight().getY());
        boolean[] visited = new boolean[(right - left + 1) * (bottom - top + 1)];
        List<Component> components = new ArrayList<>();

        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                int index = (y - top) * (right - left + 1) + x - left;
                if (visited[index] || !matches.test(image.getRGB(x, y))) {
                    visited[index] = true;
                    continue;
                }
                Component component = floodFill(image, left, top, right, bottom, x, y, visited, matches);
                if (component.pixelCount() >= minimumPixels) {
                    components.add(component);
                }
            }
        }
        return components;
    }

    private static Component floodFill(BufferedImage image, int left, int top, int right, int bottom,
            int startX, int startY, boolean[] visited, IntPredicate matches) {
        int width = right - left + 1;
        ArrayDeque<PointData> pending = new ArrayDeque<>();
        pending.add(new PointData(startX, startY));
        int minX = startX, maxX = startX, minY = startY, maxY = startY, pixels = 0;

        while (!pending.isEmpty()) {
            PointData point = pending.removeFirst();
            int x = point.getX();
            int y = point.getY();
            int index = (y - top) * width + x - left;
            if (x < left || x > right || y < top || y > bottom || visited[index]) {
                continue;
            }
            visited[index] = true;
            if (!matches.test(image.getRGB(x, y))) {
                continue;
            }
            pixels++;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            if (x > left) pending.add(new PointData(x - 1, y));
            if (x < right) pending.add(new PointData(x + 1, y));
            if (y > top) pending.add(new PointData(x, y - 1));
            if (y < bottom) pending.add(new PointData(x, y + 1));
        }
        return new Component(new AreaData(new PointData(minX, minY), new PointData(maxX, maxY)), pixels);
    }

    public record Component(AreaData bounds, int pixelCount) {
        public PointData center() {
            return new PointData((bounds.topLeft().getX() + bounds.bottomRight().getX()) / 2,
                    (bounds.topLeft().getY() + bounds.bottomRight().getY()) / 2);
        }
    }
}
