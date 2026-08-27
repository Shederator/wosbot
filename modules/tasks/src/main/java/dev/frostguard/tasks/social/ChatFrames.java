package dev.frostguard.tasks.social;

import java.awt.image.BufferedImage;

import dev.frostguard.api.domain.RawImageData;

/**
 * Packs a saved screen back into the shape the emulator hands one over in.
 *
 * <p>Screens are photographed first and read afterwards, so by the time the reader sees one it is a
 * file rather than a capture. Everything downstream still expects the emulator's own format, and
 * converting here rather than teaching the pipeline about files keeps one path through the code.
 */
final class ChatFrames {

    private ChatFrames() {
    }

    static RawImageData toRaw(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        byte[] px = new byte[w * h * 4];
        int i = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                px[i++] = (byte) ((rgb >> 16) & 0xFF);
                px[i++] = (byte) ((rgb >> 8) & 0xFF);
                px[i++] = (byte) (rgb & 0xFF);
                px[i++] = (byte) 0xFF;
            }
        }
        return RawImageData.capture(px, w, h, 32);
    }
}
