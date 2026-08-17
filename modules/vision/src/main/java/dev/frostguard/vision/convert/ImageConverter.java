package dev.frostguard.vision.convert;

import dev.frostguard.api.domain.RawImageData;
import java.awt.image.BufferedImage;

public final class ImageConverter {

    private ImageConverter() {
        // static utility
    }

    /**
     * Converts a raw byte array capture from the emulator into a standard
     * {@link BufferedImage}. Useful for diagnostic dumps or when an image
     * processing library requires AWT images.
     */
    public static BufferedImage toBufferedImage(RawImageData capture) {
        if (capture == null) {
            throw new IllegalArgumentException("Capture must not be null.");
        }
        int w = capture.getWidth();
        int h = capture.getHeight();
        byte[] raw = capture.getData();
        int bpp = capture.getBpp();
        int bytesPerPixel = switch (bpp) {
            case 2, 16 -> 2;
            case 4, 32 -> 4;
            default -> throw new IllegalArgumentException("Unsupported capture depth: " + bpp);
        };
        int requiredBytes = Math.multiplyExact(Math.multiplyExact(w, h), bytesPerPixel);
        if (w <= 0 || h <= 0 || raw == null || raw.length < requiredBytes) {
            throw new IllegalArgumentException("Capture has invalid dimensions or pixel data.");
        }

        int[] pixels = new int[w * h];
        for (int index = 0; index < pixels.length; index++) {
            int offset = index * bytesPerPixel;
            if (bytesPerPixel == 2) {
                int packed = ((raw[offset + 1] & 0xFF) << 8) | (raw[offset] & 0xFF);
                int red = ((packed >> 11) & 0x1F) << 3;
                int green = ((packed >> 5) & 0x3F) << 2;
                int blue = (packed & 0x1F) << 3;
                pixels[index] = (red << 16) | (green << 8) | blue;
            } else {
                int red = raw[offset] & 0xFF;
                int green = raw[offset + 1] & 0xFF;
                int blue = raw[offset + 2] & 0xFF;
                pixels[index] = (red << 16) | (green << 8) | blue;
            }
        }

        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, w, h, pixels, 0, w);
        return image;
    }
}
