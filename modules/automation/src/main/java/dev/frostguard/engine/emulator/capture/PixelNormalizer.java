package dev.frostguard.engine.emulator.capture;

import java.util.Objects;

/**
 * Utility for normalizing framebuffer pixel orientations and layouts.
 */
public final class PixelNormalizer {

    private PixelNormalizer() {}

    /**
     * Inverts image rows vertically in-place (bottom-left to top-left origin conversion).
     *
     * @param pixels        raw byte array containing packed pixel scanlines
     * @param width         scanline width in pixels
     * @param height        scanline count
     * @param bytesPerPixel number of bytes per pixel (e.g. 4 for RGBA, 2 for RGB565)
     * @throws IllegalArgumentException if dimensions, pixel count, or buffer length are invalid
     */
    public static void flipY(byte[] pixels, int width, int height, int bytesPerPixel) {
        Objects.requireNonNull(pixels, "pixels required");
        if (width <= 0 || height <= 0 || bytesPerPixel <= 0) {
            throw new IllegalArgumentException("Dimensions and bytesPerPixel must be positive: "
                    + width + "x" + height + "@" + bytesPerPixel + "B");
        }
        long requiredSize = (long) width * height * bytesPerPixel;
        if (pixels.length < requiredSize) {
            throw new IllegalArgumentException("Pixel buffer too small: length=" + pixels.length
                    + ", required=" + requiredSize);
        }

        int rowBytes = width * bytesPerPixel;
        byte[] tempRow = new byte[rowBytes];
        int top = 0;
        int bottom = (height - 1) * rowBytes;

        while (top < bottom) {
            System.arraycopy(pixels, top, tempRow, 0, rowBytes);
            System.arraycopy(pixels, bottom, pixels, top, rowBytes);
            System.arraycopy(tempRow, 0, pixels, bottom, rowBytes);
            top += rowBytes;
            bottom -= rowBytes;
        }
    }
}
