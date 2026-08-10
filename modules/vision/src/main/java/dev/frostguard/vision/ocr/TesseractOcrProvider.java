package dev.frostguard.vision.ocr;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.imageio.ImageIO;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.TesseractSettingsData;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides text recognition from captured device screens or local image
 * files.  Handles sub-region extraction, upscaling, optional background
 * removal, and Tesseract engine configuration.
 *
 * <p>All public entry points are thread-safe.  The tessdata directory is
 * located once and then cached for the lifetime of the JVM.
 */
public final class TesseractOcrProvider {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcrProvider.class);

    /** Nearest-neighbour magnification applied before recognition. */
    private static final int MAGNIFICATION = 4;

    /** Per-channel distance used when isolating text pixels. */
    private static final int CHANNEL_TOLERANCE = 50;

    /** Lazily resolved, then reused for every subsequent call. */
    private static volatile String resolvedTessdataDir;

    // =====================================================================
    //  Public entry points
    // =====================================================================

    /**
     * Recognises text inside the rectangle described by two corners,
     * using the specified Tesseract language pack.
     *
     * @param capture  device screen snapshot
     * @param corner1  one corner of the target rectangle
     * @param corner2  opposite corner
     * @param lang     Tesseract language code (e.g. {@code "eng"})
     * @return trimmed text, never {@code null}
     */
    public static String recognizeText(RawImageData capture, PointData corner1,
                                       PointData corner2, String lang)
            throws TesseractException {
        requireValidCapture(capture);
        int[] clip = computeClipRect(corner1, corner2, capture);
        BufferedImage prepared = cropAndPreprocess(
                capture, clip[0], clip[1], clip[2], clip[3],
                MAGNIFICATION, false, null);
        return executeRecognition(configureTesseract(lang), prepared);
    }

    /**
     * Recognises text inside the rectangle described by two corners,
     * driven by a full {@link TesseractSettingsData}.
     *
     * @param capture  device screen snapshot
     * @param corner1  one corner of the target rectangle
     * @param corner2  opposite corner
     * @param cfg      Tesseract tuning parameters
     * @return trimmed text, never {@code null}
     */
    public static String recognizeText(RawImageData capture, PointData corner1,
                                       PointData corner2, TesseractSettingsData cfg)
            throws TesseractException {
        long t0 = System.currentTimeMillis();
        log.debug("=== Recognition Started ===");

        requireValidCapture(capture);
        int[] clip = computeClipRect(corner1, corner2, capture);
        int cx = clip[0], cy = clip[1], cw = clip[2], ch = clip[3];
        log.debug("Clip rect: x={}, y={}, w={}, h={}", cx, cy, cw, ch);
        log.debug("Config: stripBackground={}, targetColour={}",
                cfg.isRemoveBackground(), cfg.getTextColor());

        long step = System.currentTimeMillis();
        BufferedImage prepared = cropAndPreprocess(
                capture, cx, cy, cw, ch, MAGNIFICATION,
                cfg.isRemoveBackground(), cfg.getTextColor());
        log.debug("Crop + preprocess: {} ms", System.currentTimeMillis() - step);

        step = System.currentTimeMillis();
        Tesseract engine = configureTesseract(cfg);
        log.debug("Engine config: {} ms", System.currentTimeMillis() - step);

        step = System.currentTimeMillis();
        String recognised = executeRecognition(engine, prepared);
        log.debug("Engine execution: {} ms", System.currentTimeMillis() - step);

        if (cfg.isDebug()) {
            exportDiagnosticImage(capture, prepared, cx, cy, cw, ch, cfg, recognised);
        }

        log.debug("=== Recognition Finished === elapsed={} ms, text='{}'",
                System.currentTimeMillis() - t0, recognised);
        return recognised;
    }

    /**
     * Reads text from a region of a local image file on disk.
     *
     * @param file    source image (PNG, JPEG, etc.)
     * @param x       left edge of the region
     * @param y       top edge
     * @param w       width
     * @param h       height
     * @param lang    Tesseract language code
     * @return trimmed text, never {@code null}
     */
    public static String readFromFile(File file, int x, int y, int w, int h, String lang)
            throws Exception {
        BufferedImage full = ImageIO.read(file);
        if (full == null) {
            throw new IllegalArgumentException("Unreadable image: " + file);
        }
        x = Math.max(0, Math.min(x, full.getWidth() - 1));
        y = Math.max(0, Math.min(y, full.getHeight() - 1));
        w = Math.max(1, Math.min(w, full.getWidth() - x));
        h = Math.max(1, Math.min(h, full.getHeight() - y));

        BufferedImage magnified = enlargeViaGraphics(
                full.getSubimage(x, y, w, h), MAGNIFICATION);
        return executeRecognition(configureTesseract(lang), magnified);
    }

    /**
     * Converts a full {@link RawImageData} to a standard
     * {@link BufferedImage}.  Useful for diagnostic dumps only — not
     * invoked on the hot path.
     */
    public static BufferedImage toBufferedImage(RawImageData capture) {
        int w = capture.getWidth();
        int h = capture.getHeight();
        byte[] raw = capture.getData();
        int bpp = capture.getBpp();
        int[] pixels = new int[w * h];

        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                pixels[row * w + col] = decodePixel(raw, bpp, w, col, row);
            }
        }

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, pixels, 0, w);
        return img;
    }

    // =====================================================================
    //  Tesseract factory
    // =====================================================================

    /** Builds a single-line LSTM engine for the given language. */
    private static Tesseract configureTesseract(String lang) {
        Tesseract t = new Tesseract();
        t.setDatapath(locateTessdata());
        t.setLanguage(lang);
        t.setConfigs(Collections.singletonList("quiet"));
        t.setPageSegMode(7);
        t.setOcrEngineMode(1);
        return t;
    }

    /** Builds an engine whose behaviour is controlled by {@code cfg}. */
    private static Tesseract configureTesseract(TesseractSettingsData cfg) {
        Tesseract t = new Tesseract();
        t.setDatapath(locateTessdata());
        t.setLanguage("eng");
        if (cfg.hasPageSegMode())   t.setPageSegMode(cfg.getPageSegMode());
        if (cfg.hasOcrEngineMode()) t.setOcrEngineMode(cfg.getOcrEngineMode());
        if (cfg.hasAllowedChars())  t.setVariable("tessedit_char_whitelist", cfg.getAllowedChars());
        return t;
    }

    /** Runs the engine and strips whitespace / line breaks. */
    private static String executeRecognition(Tesseract engine, BufferedImage img)
            throws TesseractException {
        return engine.doOCR(img).replace("\n", "").replace("\r", "").trim();
    }

    // =====================================================================
    //  Tessdata resolution
    // =====================================================================

    /**
     * Walks upward from the working directory looking for a
     * {@code lib/tesseract} or {@code tools/tesseract} folder that
     * contains at least one {@code .traineddata} file.
     */
    private static String locateTessdata() {
        if (resolvedTessdataDir != null) return resolvedTessdataDir;
        synchronized (TesseractOcrProvider.class) {
            if (resolvedTessdataDir != null) return resolvedTessdataDir;
            for (Path candidate : candidatePaths()) {
                File dir = candidate.toFile();
                if (containsTrainedModels(dir)) {
                    resolvedTessdataDir = dir.getAbsolutePath();
                    log.info("Tessdata located at {}", resolvedTessdataDir);
                    return resolvedTessdataDir;
                }
            }
            throw new IllegalStateException(
                    "No tessdata directory found — expected .traineddata files under lib/tesseract.");
        }
    }

    private static List<Path> candidatePaths() {
        List<Path> paths = new ArrayList<>();
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path ancestor = cwd; ancestor != null; ancestor = ancestor.getParent()) {
            paths.add(ancestor.resolve("lib").resolve("tesseract"));
            paths.add(ancestor.resolve("tools").resolve("tesseract"));
        }
        return paths;
    }

    private static boolean containsTrainedModels(File dir) {
        if (!dir.isDirectory()) return false;
        File[] models = dir.listFiles(f -> f.getName().endsWith(".traineddata"));
        return models != null && models.length > 0;
    }

    // =====================================================================
    //  Pixel decoding
    // =====================================================================

    /**
     * Reads one pixel from the raw byte buffer and returns packed
     * {@code 0x00RRGGBB}.  Handles both 16-bit RGB565 and 32-bit RGBA.
     */
    private static int decodePixel(byte[] data, int bpp, int stride, int px, int py) {
        if (bpp == 16) {
            int off = (py * stride + px) * 2;
            int packed = ((data[off + 1] & 0xFF) << 8) | (data[off] & 0xFF);
            int r = ((packed >> 11) & 0x1F) << 3;
            int g = ((packed >> 5)  & 0x3F) << 2;
            int b = (packed & 0x1F) << 3;
            return (r << 16) | (g << 8) | b;
        }
        // 32 bpp RGBA
        int off = (py * stride + px) * 4;
        int r = data[off]     & 0xFF;
        int g = data[off + 1] & 0xFF;
        int b = data[off + 2] & 0xFF;
        return (r << 16) | (g << 8) | b;
    }

    // =====================================================================
    //  Image processing
    // =====================================================================

    /**
     * Extracts a rectangular region and magnifies it for OCR. When isolating
     * text ({@code stripBackground} + {@code textColour}), the region is turned
     * into a <em>soft</em> distance-to-target grayscale (anti-aliased) rather
     * than a hard black/white mask, then upscaled <em>bilinearly</em>.
     *
     * <p>The previous approach — hard per-pixel binarisation blown up by
     * nearest-neighbour — produced blocky, stair-stepped glyph edges that made
     * the LSTM flip borderline digits on clean input (verified: 2->7, 5->3).
     * A smooth grayscale keeps the "background removed" benefit while giving
     * Tesseract the anti-aliased edges its model was trained on.
     */
    private static BufferedImage cropAndPreprocess(RawImageData capture,
            int cx, int cy, int cw, int ch, int scale,
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

        // Native-resolution intermediate: soft grayscale when isolating text
        // (0 = exact text colour -> dark, >= tolerance -> light), else raw colour.
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

        // Smooth (bilinear) upscale — replaces the old nearest-neighbour blow-up.
        int outW = cw * scale, outH = ch * scale;
        BufferedImage result = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(base, 0, 0, outW, outH, null);
        g.dispose();
        return result;
    }

    /** Bilinear upscale via {@link Graphics2D} — used for file-based path. */
    private static BufferedImage enlargeViaGraphics(BufferedImage src, int factor) {
        int w = src.getWidth()  * factor;
        int h = src.getHeight() * factor;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    // =====================================================================
    //  Validation
    // =====================================================================

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

    // =====================================================================
    //  Debug / diagnostic output
    // =====================================================================

    /**
     * Writes a side-by-side diagnostic PNG to {@code <cwd>/temp/}.
     */
    private static void exportDiagnosticImage(RawImageData capture, BufferedImage processed,
            int cx, int cy, int cw, int ch,
            TesseractSettingsData cfg, String text) {
        long t0 = System.currentTimeMillis();
        try {
            Path tempDir = Paths.get(System.getProperty("user.dir")).resolve("temp");
            Files.createDirectories(tempDir);

            String summary = formatSettingsSummary(cfg, text);
            BufferedImage full = toBufferedImage(capture);
            BufferedImage composite = composeDiagnosticPanel(
                    full, processed, cx, cy, cw, ch, summary, text);

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            ImageIO.write(composite, "png", buf);
            Files.write(tempDir.resolve(System.currentTimeMillis() + "_debug.png"), buf.toByteArray());

            log.debug("Diagnostic image saved: {} ms", System.currentTimeMillis() - t0);
        } catch (IOException ex) {
            log.error("Diagnostic image export failed: {}", ex.getMessage());
        }
    }

    private static String formatSettingsSummary(TesseractSettingsData cfg, String text) {
        return "Engine Settings:"
                + "\n  Language: eng"
                + "\n  Seg Mode: " + (cfg.hasPageSegMode() ? cfg.getPageSegMode() : "Default")
                + "\n  Engine Mode: " + (cfg.hasOcrEngineMode() ? cfg.getOcrEngineMode() : "Default")
                + "\n  Whitelist: " + (cfg.hasAllowedChars() ? cfg.getAllowedChars() : "All")
                + "\n  Strip BG: " + cfg.isRemoveBackground()
                + "\n  Text Colour: " + (cfg.getTextColor() != null ? cfg.getTextColor() : "Auto")
                + "\n  Magnification: " + MAGNIFICATION + "x"
                + "\n\nRecognised: \"" + text + "\"";
    }

    private static BufferedImage composeDiagnosticPanel(BufferedImage full, BufferedImage processed,
            int cx, int cy, int cw, int ch, String summary, String detectedText) {
        final int GAP = 20;
        final int HEADER = 40;
        final int INFO_BLOCK = 200;
        int rightW = Math.max(processed.getWidth(), 500);
        int totalW = full.getWidth() + GAP + rightW;
        int totalH = Math.max(full.getHeight() + HEADER,
                processed.getHeight() + HEADER + INFO_BLOCK + GAP);

        BufferedImage canvas = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        enableSmoothing(g);

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, totalW, totalH);

        // Left panel — full image with annotation
        g.setColor(Color.BLACK);
        g.setFont(new Font("Arial", Font.BOLD, 16));
        g.drawString("Full Image with Region", 10, 20);
        g.drawImage(annotateWithRegion(full, cx, cy, cw, ch, detectedText), 0, HEADER, null);

        // Right panel — processed crop + info
        int rx = full.getWidth() + GAP;
        g.drawString("Processed Region", rx + 10, 20);
        g.drawImage(processed, rx, HEADER, null);

        int infoY = HEADER + processed.getHeight() + GAP;
        g.setColor(new Color(240, 240, 240));
        g.fillRect(rx, infoY, rightW, INFO_BLOCK);
        g.setColor(Color.GRAY);
        g.setStroke(new BasicStroke(1));
        g.drawRect(rx, infoY, rightW, INFO_BLOCK);
        g.setColor(Color.BLACK);
        g.setFont(new Font("Monospaced", Font.PLAIN, 12));
        int lineY = infoY + 20;
        for (String line : summary.split("\n")) {
            g.drawString(line, rx + 10, lineY);
            lineY += 18;
        }

        g.setColor(Color.GRAY);
        g.setStroke(new BasicStroke(2));
        g.drawLine(full.getWidth() + GAP / 2, 0, full.getWidth() + GAP / 2, totalH);

        g.dispose();
        return canvas;
    }

    private static BufferedImage annotateWithRegion(BufferedImage full,
            int cx, int cy, int cw, int ch, String label) {
        BufferedImage copy = new BufferedImage(
                full.getWidth(), full.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        enableSmoothing(g);
        g.drawImage(full, 0, 0, null);

        g.setColor(Color.RED);
        g.setStroke(new BasicStroke(3));
        g.drawRect(cx, cy, cw, ch);

        g.setFont(new Font("Arial", Font.BOLD, 20));
        FontMetrics fm = g.getFontMetrics();
        int tw = fm.stringWidth(label);
        int th = fm.getHeight();
        int tx = cx + 5;
        int ty = (cy > th + 10) ? (cy - 10) : (cy + ch + th);

        g.setColor(new Color(0, 0, 0, 180));
        g.fillRect(tx - 5, ty - th + 5, tw + 10, th);
        g.setColor(Color.RED);
        g.drawString(label, tx, ty);

        g.dispose();
        return copy;
    }

    private static void enableSmoothing(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
    }
}
