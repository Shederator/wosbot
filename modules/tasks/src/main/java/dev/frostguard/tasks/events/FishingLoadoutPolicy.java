package dev.frostguard.tasks.events;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.RawImageData;

/** Only the two useful MVP boosters are selectable; Horn targeting and Scanner steering are unsupported. */
final class FishingLoadoutPolicy {
    enum Item {
        HORN(155), STABILIZER(291), SCANNER(429), LANTERN(567);
        final int x;
        Item(int x) { this.x = x; }
        AreaData countArea() { return AreaData.of(x + 16, 1040, x + 46, 1072); }
        AreaData cardArea() { return AreaData.of(x - 48, 974, x + 54, 1079); }
    }

    private FishingLoadoutPolicy() { }

    static boolean wants(Item item, boolean lantern, boolean stabilizer, int used, int limit) {
        return used < limit && (item == Item.LANTERN && lantern || item == Item.STABILIZER && stabilizer);
    }

    static boolean selected(RawImageData frame, Item item) {
        if (frame.getWidth() != 720 || frame.getHeight() != 1280) return false;
        int greenPixels = 0;
        for (int y = 975; y < 1001; y++) for (int x = item.x + 29; x < item.x + 56; x++) {
            int offset = (y * frame.getWidth() + x) * 4;
            int r = frame.getData()[offset] & 255, g = frame.getData()[offset + 1] & 255;
            int b = frame.getData()[offset + 2] & 255;
            if (g > 130 && g > r * 1.2 && g > b * 1.4) greenPixels++;
        }
        return greenPixels >= 30;
    }

    static int count(String text) {
        if (text == null || !text.trim().matches("\\d{1,4}")) return -1;
        return Integer.parseInt(text.trim());
    }
}
