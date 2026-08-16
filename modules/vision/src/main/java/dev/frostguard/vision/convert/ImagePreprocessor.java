package dev.frostguard.vision.convert;

import dev.frostguard.api.domain.RawImageData;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Prepares raw screen captures for OCR by applying cropping, background
 * isolation, and smooth upscaling.
 */
public final class ImagePreprocessor {

    /** Magnification applied before recognition. */
    private static final int MAGNIFICATION = 4;

    /** Per-channel distance used when isolating text pixels. */
    private static final int CHANNEL_TOLERANCE = 50;

    private ImagePreprocessor() {}

    /**
     * Extracts a rectangular region and magnifies it for OCR. When isolating
     * text ({@code stripBackground} + {@code textColour}), the region is turned
     * into a soft distance-to-target grayscale rather than a hard black/white mask,
     * then upscaled bilinearly.
     */
    public static BufferedImage prepareForOcr(RawImageData capture,
            int cx, int cy, int cw, int ch,
            boolean stripBackground, Color textColour) {

        byte[] raw = capture.getData();
        int bpp = capture.getBpp();
        int srcStride = capture.getWidth();
        boolean isolate = stripBackground && textColour != null;

        int tR = 0, tG = 0, tB = 0;
        if (isolate) {
            tR = textColour.getRed();
            tG = textColour.getGreen();
            tB = textColour.getBlue();
        }

        int[] px = new int[cw * ch];
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                int rgb = decodePixel(raw, bpp, srcStride, cx + x, cy + y);
                if (isolate) {
                    int pr = (rgb >> 16) & 0xFF, pg = (rgb >> 8) & 0xFF, pb = rgb & 0xFF;
                    int dist = Math.max(Math.abs(pr - tR), Math.max(Math.abs(pg - tG), Math.abs(pb - tB)));
                    int v = Math.min(255, dist * 255 / CHANNEL_TOLERANCE);
                    px[y * cw + x] = (v << 16) | (v << 8) | v;
                } else {
                    px[y * cw + x] = rgb;
                }
            }
        }
        BufferedImage base = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
        base.setRGB(0, 0, cw, ch, px, 0, cw);

        int outW = cw * MAGNIFICATION;
        int outH = ch * MAGNIFICATION;
        BufferedImage result = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        // Bilinear interpolation is intentional. Nearest-neighbour scaling caused real
        // recognition regressions in small game glyphs (notably 2 -> 7 and 5 -> 3).
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(base, 0, 0, outW, outH, null);
        g.dispose();
        return result;
    }

    private static int decodePixel(byte[] data, int bpp, int stride, int px, int py) {
        if (bpp == 2 || bpp == 16) {
            int off = (py * stride + px) * 2;
            int packed = ((data[off + 1] & 0xFF) << 8) | (data[off] & 0xFF);
            int r = ((packed >> 11) & 0x1F) << 3;
            int g = ((packed >> 5)  & 0x3F) << 2;
            int b = (packed & 0x1F) << 3;
            return (r << 16) | (g << 8) | b;
        }
        if (bpp != 4 && bpp != 32) {
            throw new IllegalArgumentException("Unsupported capture depth: " + bpp);
        }
        // 32-bit RGBA
        int off = (py * stride + px) * 4;
        int r = data[off]     & 0xFF;
        int g = data[off + 1] & 0xFF;
        int b = data[off + 2] & 0xFF;
        return (r << 16) | (g << 8) | b;
    }
}
