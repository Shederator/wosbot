package dev.frostguard.vision.match;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.RawImageData;

/** Revalidates a previously recognized static control without repeating slow OCR. */
public final class ImageRegionStability {
    private ImageRegionStability() { }

    public static boolean unchanged(RawImageData before, RawImageData after, AreaData region) {
        if (before == null || after == null || before.getBpp() != 32 || after.getBpp() != 32
                || before.getWidth() != after.getWidth() || before.getHeight() != after.getHeight()
                || before.getData() == null || after.getData() == null) return false;
        int width = before.getWidth(), height = before.getHeight();
        int x1 = region.topLeft().getX(), y1 = region.topLeft().getY();
        int x2 = region.bottomRight().getX(), y2 = region.bottomRight().getY();
        if (x1 < 0 || y1 < 0 || x2 > width || y2 > height || x2 <= x1 || y2 <= y1
                || before.getData().length < (long) width * height * 4
                || after.getData().length < (long) width * height * 4) return false;
        int changed = 0, total = (x2 - x1) * (y2 - y1);
        for (int y = y1; y < y2; y++) for (int x = x1; x < x2; x++) {
            int offset = (y * width + x) * 4;
            for (int channel = 0; channel < 3; channel++) {
                if (Math.abs((before.getData()[offset + channel] & 255)
                        - (after.getData()[offset + channel] & 255)) > 12) {
                    changed++;
                    break;
                }
            }
            if (changed > total / 100) return false;
        }
        return true;
    }
}
