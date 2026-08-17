package dev.frostguard.vision.ocr;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.vision.convert.ImagePreprocessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Provider-neutral OCR facade.
 *
 * <p>All call sites use this class rather than a concrete provider directly.
 * Tesseract is the sole provider in this step; additional providers will be
 * added opt-in in a subsequent step once their behavior is validated against
 * real saved frames.
 *
 * <p>The shared provider instance is created lazily.
 */
public final class OcrEngine {

    private static final Logger log = LoggerFactory.getLogger(OcrEngine.class);

    private static volatile OcrProvider provider;

    private OcrEngine() {}

    private static OcrProvider getProvider() {
        OcrProvider p = provider;
        if (p == null) {
            synchronized (OcrEngine.class) {
                p = provider;
                if (p == null) {
                    log.debug("Initializing Tesseract OCR provider");
                    p = new TesseractOcrProvider();
                    provider = p;
                }
            }
        }
        return p;
    }

    /**
     * Recognizes text within the specified region using the default language.
     *
     * @param capture raw RGBA frame from the emulator
     * @param c1      top-left corner of the crop region
     * @param c2      bottom-right corner of the crop region
     * @param lang    OCR language code (e.g. {@code "eng"})
     * @return trimmed recognized text, never {@code null}
     */
    public static String recognizeText(RawImageData capture, PointData c1, PointData c2, String lang)
            throws OcrException {
        requireValidCapture(capture);
        int[] clip = computeClipRect(c1, c2, capture);
        BufferedImage prepared = ImagePreprocessor.prepareForOcr(
                capture, clip[0], clip[1], clip[2], clip[3],
                false, null);
        return getProvider().recognizeText(prepared, lang);
    }

    /**
     * Recognizes text within the specified region using explicit OCR presets.
     *
     * @param capture raw RGBA frame from the emulator
     * @param c1      top-left corner of the crop region
     * @param c2      bottom-right corner of the crop region
     * @param cfg     tuning parameters (page segmentation, whitelist, scaling, etc.)
     * @return trimmed recognized text, never {@code null}
     */
    public static String recognizeText(RawImageData capture, PointData c1, PointData c2, OcrSettingsData cfg)
            throws OcrException {
        requireValidCapture(capture);
        int[] clip = computeClipRect(c1, c2, capture);
        int cx = clip[0], cy = clip[1], cw = clip[2], ch = clip[3];
        log.debug("Clip rect: x={}, y={}, w={}, h={}", cx, cy, cw, ch);
        log.debug("Config: stripBackground={}, targetColour={}",
                cfg.isolateForeground(), cfg.targetColor());

        long step = System.currentTimeMillis();
        BufferedImage prepared = ImagePreprocessor.prepareForOcr(
                capture, cx, cy, cw, ch,
                cfg.isolateForeground(), cfg.targetColor());
        log.debug("Crop + preprocess: {} ms", System.currentTimeMillis() - step);

        String recognized = getProvider().recognizeText(prepared, cfg);
        if (cfg.diagnosticMode()) {
            try {
                OcrDiagnosticWriter.write(capture, prepared, cx, cy, cw, ch, cfg, recognized);
            } catch (IOException | RuntimeException exception) {
                log.error("OCR diagnostic image export failed: {}", exception.getMessage());
            }
        }
        return recognized;
    }

    /**
     * Reads text from a sub-region of an image file.
     *
     * @param file image file on disk
     * @param x    left edge of the crop region in pixels
     * @param y    top edge of the crop region in pixels
     * @param w    width of the crop region in pixels
     * @param h    height of the crop region in pixels
     * @param lang OCR language code
     * @return trimmed recognized text, never {@code null}
     */
    public static String readFromFile(File file, int x, int y, int w, int h, String lang) throws OcrException {
        BufferedImage full;
        try {
            full = ImageIO.read(file);
        } catch (IOException e) {
            throw new OcrException("Failed to read file", e);
        }
        if (full == null) {
            throw new IllegalArgumentException("Unreadable image: " + file);
        }
        x = Math.max(0, Math.min(x, full.getWidth() - 1));
        y = Math.max(0, Math.min(y, full.getHeight() - 1));
        w = Math.max(1, Math.min(w, full.getWidth() - x));
        h = Math.max(1, Math.min(h, full.getHeight() - y));

        // Use a basic zoom for files (no foreground isolation logic needed)
        BufferedImage cropped = full.getSubimage(x, y, w, h);
        int outW = w * 4; // MAGNIFICATION = 4
        int outH = h * 4;
        BufferedImage magnified = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = magnified.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(cropped, 0, 0, outW, outH, null);
        g.dispose();

        return getProvider().recognizeText(magnified, lang);
    }

    private static void requireValidCapture(RawImageData capture) {
        if (capture == null) {
            throw new IllegalArgumentException("Screen capture must not be null.");
        }
    }

    /**
     * Converts two corners into a clamped {@code [x, y, w, h]} clip rect.
     */
    private static int[] computeClipRect(PointData c1, PointData c2, RawImageData capture) {
        int x = (int) Math.min(c1.getX(), c2.getX());
        int y = (int) Math.min(c1.getY(), c2.getY());
        int w = (int) Math.abs(c1.getX() - c2.getX());
        int h = (int) Math.abs(c1.getY() - c2.getY());
        if (x + w > capture.getWidth() || y + h > capture.getHeight()) {
            throw new IllegalArgumentException("Clip rect exceeds capture dimensions.");
        }
        return new int[]{ x, y, w, h };
    }
}
