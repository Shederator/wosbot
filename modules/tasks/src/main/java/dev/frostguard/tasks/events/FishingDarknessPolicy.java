package dev.frostguard.tasks.events;

import dev.frostguard.api.domain.RawImageData;

/** A fixed corner per cast avoids blind zig-zagging; the indicator still authorizes input. */
final class FishingDarknessPolicy {
    private final int corner;
    private int darkFrames;
    private long sequence = -1;

    FishingDarknessPolicy(boolean left) { corner = left ? 70 : 650; }

    int corner() { return corner; }

    boolean observe(RawImageData frame, long frameSequence) {
        if (frameSequence <= sequence) return darkFrames >= 3;
        sequence = frameSequence;
        if (frame.getWidth() != 720 || frame.getHeight() != 1280) { darkFrames = 0; return false; }
        byte[] pixels = frame.getData();
        int dark = 0, total = 0;
        for (int y = 240; y < 980; y += 16) for (int x = 190; x < 630; x += 16) {
            int offset = (y * frame.getWidth() + x) * 4;
            if ((pixels[offset] & 255) < 35 && (pixels[offset + 1] & 255) < 35
                    && (pixels[offset + 2] & 255) < 35) dark++;
            total++;
        }
        darkFrames = dark * 10L >= total * 7L ? Math.min(3, darkFrames + 1) : 0;
        return darkFrames >= 3;
    }
}
