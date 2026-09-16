package dev.frostguard.tasks.events;

import java.util.List;
import java.util.ArrayList;
import dev.frostguard.api.domain.*;
import dev.frostguard.vision.match.ColorBlobLocator;
import dev.frostguard.vision.match.OutlineBlobLocator;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.engine.nav.CommonGameAreas;

/** Winter water is blue; foreground grouping also covers fish absent from the old four sprites. */
final class FishingVision {
    static final int HOOK_BODY_OFFSET_Y = 65;
    private static final int HOOK_TOP = 70, HOOK_BOTTOM = 1260, TRACKING_MARGIN = 96;
    record Hook(PointData point) { }
    private FishingVision() { }

    static Hook hook(RawImageData frame) {
        return hookInBand(frame, HOOK_TOP, HOOK_BOTTOM);
    }

    static Hook hook(RawImageData frame, Hook previous) {
        if (previous != null) {
            int y = previous.point().getY();
            var tracked = hookInBand(frame, Math.max(HOOK_TOP, y - TRACKING_MARGIN),
                    Math.min(HOOK_BOTTOM, y + TRACKING_MARGIN));
            if (tracked != null) return tracked;
            var belowHud = hookBelowHud(frame, previous);
            if (belowHud != null) return belowHud;
        }
        // A transition or missed observation can move the attachment outside the tracking band.
        return hook(frame);
    }

    private static Hook hookBelowHud(RawImageData frame, Hook previous) {
        int previousY = previous.point().getY();
        if (previousY > 300) return null;
        var bodies = OpenCvPatternLocator.locateAllPatternsMonoCropped(frame, TemplatesEnum.FISHING_HOOK_BODY.getTemplate(),
                new PointData(0, 215), new PointData(190, Math.min(380, previousY + 150)), 90, 4);
        for (var body : bodies) {
            int x = body.getPoint().getX() - 1, y = body.getPoint().getY() - 53;
            if (x < 0 || x >= 175 || Math.abs(y - previousY) > TRACKING_MARGIN) continue;
            for (var forkTemplate : List.of(TemplatesEnum.FISHING_HOOK_RIGHT_FORK, TemplatesEnum.FISHING_HOOK_RIGHT_FORK_PROTECTED)) {
                var forks = OpenCvPatternLocator.locateAllPatternsMonoCropped(frame, forkTemplate.getTemplate(),
                        new PointData(Math.max(0, x + 4), Math.max(215, y + 63)),
                        new PointData(Math.min(720, x + 50), Math.min(1280, y + 115)), 85, 2);
                if (forks.stream().anyMatch(f -> Math.abs(f.getPoint().getX() - (x + 22)) <= 4
                        && Math.abs(f.getPoint().getY() - (y + 88)) <= 4)) return new Hook(new PointData(x, y));
            }
        }
        return null;
    }

    private static Hook hookInBand(RawImageData frame, int top, int bottom) {
        var candidates = OpenCvPatternLocator.locateAllPatternsMonoCropped(frame, TemplatesEnum.FISHING_HOOK.getTemplate(),
                new PointData(0, top), new PointData(720, bottom), 80, 8);
        for (var candidate : candidates) {
            int x = candidate.getPoint().getX(), y = candidate.getPoint().getY();
            int linePixels = 0;
            // The indicator glow brightens the nearest rope pixels; inspect above its halo.
            for (int offset = 24; offset <= 47; offset++) {
                byte[] data = frame.getData();
                for (int dx = -3; dx <= 3; dx++) {
                    if (x + dx < 0 || x + dx >= frame.getWidth()) continue;
                    int pixel = ((y - offset) * 720 + x + dx) * 4;
                    if ((data[pixel] & 255) + (data[pixel + 1] & 255) + (data[pixel + 2] & 255) < 150) {
                        linePixels++;
                        break;
                    }
                }
            }
            if (linePixels < 16 && (candidate.getMatchScore() < 90 || !visibleHudRope(frame, x, y))) continue;
            return new Hook(candidate.getPoint());
        }
        return null;
    }

    private static boolean visibleHudRope(RawImageData frame, int x, int y) {
        if (x >= CommonGameAreas.FISHING_PROTECTION_HUD.bottomRight().getX() || y > 300) return false;
        int run = 0;
        for (int offset = 24; offset <= 110 && y - offset >= 0; offset++) {
            int row = y - offset;
            if (covered(x, row, CommonGameAreas.FISHING_DEPTH_HUD)
                    || covered(x, row, CommonGameAreas.FISHING_CAPACITY_HUD)
                    || covered(x, row, CommonGameAreas.FISHING_PROTECTION_HUD)) { run = 0; continue; }
            boolean dark = false;
            for (int dx = -3; dx <= 3; dx++) {
                if (x + dx < 0 || x + dx >= frame.getWidth()) continue;
                int pixel = (row * frame.getWidth() + x + dx) * 4;
                byte[] data = frame.getData();
                if ((data[pixel] & 255) + (data[pixel + 1] & 255) + (data[pixel + 2] & 255) < 150) dark = true;
            }
            // This real-frame gap exposes seven dark rope pixels; HUD pixels never count.
            run = dark ? run + 1 : 0;
            if (run >= 7) return true;
        }
        return false;
    }

    private static boolean covered(int x, int y, AreaData area) {
        return x >= area.topLeft().getX() && x < area.bottomRight().getX()
                && y >= area.topLeft().getY() && y < area.bottomRight().getY();
    }

    static List<AreaData> obstacles(RawImageData frame, int hookX, int hookY) {
        var colours = ColorBlobLocator.locate(frame,
                new AreaData(new PointData(0, 0), new PointData(720, 1280)),
                (x, y, rgb) -> isObstaclePixel(x, y, rgb, hookX, hookY), 55)
                .stream().filter(area -> {
                    int x1 = area.topLeft().getX(), y1 = area.topLeft().getY();
                    int x2 = area.bottomRight().getX(), y2 = area.bottomRight().getY();
                    if (x1 < 175 && y1 < 230) return false;
                    if (x1 > 640 && y1 < 90) return false;
                    if (x2 - x1 > 400 || y2 - y1 > 300) return false;
                    return !isHookArea(area, hookX, hookY);
                }).toList();
        var result = new ArrayList<>(colours);
        var outlines = OutlineBlobLocator.locate(frame, List.of(
                AreaData.of(0, 0, 175, 230), AreaData.of(641, 0, 720, 90),
                AreaData.of(hookX - 7, 0, hookX + 8, Math.max(0, hookY - 24))), 55);
        for (var area : outlines) {
            int width = area.bottomRight().getX() - area.topLeft().getX();
            int height = area.bottomRight().getY() - area.topLeft().getY();
            if (width > 400 || height > 300) continue;
            // Outline closure includes the protection halo, which is larger than the colour body.
            if (area.topLeft().getX() >= hookX - 56 && area.bottomRight().getX() <= hookX + 56
                    && area.topLeft().getY() >= hookY - 30 && area.bottomRight().getY() <= hookY + 125) continue;
            if (colours.stream().anyMatch(colour -> contains(colour, area))) continue;
            // One outline can recover the complete body around several disconnected colour parts.
            // Retain colour-only bodies where an outline could not be closed.
            result.removeIf(colour -> contains(area, colour));
            result.add(area);
        }
        return List.copyOf(result);
    }

    private static boolean contains(AreaData outer, AreaData inner) {
        return outer.topLeft().getX() <= inner.topLeft().getX()
                && outer.topLeft().getY() <= inner.topLeft().getY()
                && outer.bottomRight().getX() >= inner.bottomRight().getX()
                && outer.bottomRight().getY() >= inner.bottomRight().getY();
    }

    static boolean isHookArea(AreaData area, int hookX, int hookY) {
        // A merged object may share the hook centre but extend outside the known hook body.
        return area.topLeft().getX() >= hookX - 48 && area.bottomRight().getX() <= hookX + 48
                && area.topLeft().getY() >= hookY - 25 && area.bottomRight().getY() <= hookY + 115;
    }

    static boolean isForeground(int rgb) {
        int red = (rgb >> 16) & 255, green = (rgb >> 8) & 255, blue = rgb & 255;
        // Exclude water, blue scenery, and the dark vertical fishing line.
        return red + green + blue > 90 && !(blue > green * 1.12 && green > red * 1.10);
    }

    static boolean isObstaclePixel(int x, int y, int rgb, int hookX, int hookY) {
        // Antialiased rope can pass the color mask and join the body into an oversized
        // contour. Disconnect it above the attachment; do not hide incoming objects below it.
        return !(Math.abs(x - hookX) <= 7 && y < hookY - 24) && isForeground(rgb);
    }
}
