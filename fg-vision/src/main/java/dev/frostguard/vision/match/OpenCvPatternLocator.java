package dev.frostguard.vision.match;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.configs.TemplatesEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Performs normalised cross-correlation between device screen captures
 * and pre-loaded reference sprites.  Both colour (BGR) and luminance-only
 * (greyscale) pipelines are provided, each with single-hit and multi-hit
 * variants.
 *
 * <p>Reference sprites are loaded from the classpath once and held in a
 * process-wide cache so that subsequent look-ups avoid repeated I/O and
 * JPEG/PNG decoding.  A dedicated {@link ForkJoinPool} is used for the
 * warm-up phase to avoid blocking the caller's thread.
 *
 * <p>All public entry points are thread-safe.  OpenCV {@link Mat} objects
 * created during a search are explicitly released in {@code finally}
 * blocks to prevent native memory leaks.
 */
public class OpenCvPatternLocator {

    private static final Logger log = LoggerFactory.getLogger(OpenCvPatternLocator.class);

    /* ------------------------------------------------------------------ */
    /*  Sprite caches – keyed by classpath resource path                   */
    /* ------------------------------------------------------------------ */

    /** Decoded BGR sprites, keyed by resource path. */
    private static final ConcurrentHashMap<String, Mat> spriteStore =
            new ConcurrentHashMap<>();

    /** Greyscale copies of the same sprites. */
    private static final ConcurrentHashMap<String, Mat> greyscaleSpriteStore =
            new ConcurrentHashMap<>();

    /** Raw bytes read from the classpath (avoids re-reading the JAR). */
    private static final ConcurrentHashMap<String, byte[]> rawBytesStore =
            new ConcurrentHashMap<>();

    /* ------------------------------------------------------------------ */
    /*  Thread pool & lifecycle                                            */
    /* ------------------------------------------------------------------ */

    private static final ForkJoinPool matchPool = new ForkJoinPool(
            Math.min(Runtime.getRuntime().availableProcessors(), 4));

    private static volatile boolean storeReady = false;

    /* ------------------------------------------------------------------ */
    /*  Per-thread profile label (for structured logging)                   */
    /* ------------------------------------------------------------------ */

    private static final ThreadLocal<String> accountTag = new ThreadLocal<>();

    /**
     * Associates a human-readable profile label with the calling thread
     * so that every subsequent log line carries contextual identity.
     */
    public static void setContextLabel(String label) {
        accountTag.set(label);
    }

    /** Removes the profile label from the calling thread. */
    public static void clearContextLabel() {
        accountTag.remove();
    }

    /**
     * Prepends the thread-local profile label (if any) to a diagnostic
     * message.
     */
    private static String tagged(String msg) {
        String tag = accountTag.get();
        return (tag != null && !tag.isEmpty()) ? (tag + " - " + msg) : msg;
    }

    /* ------------------------------------------------------------------ */
    /*  Static initialiser – warm-up & shutdown hook                        */
    /* ------------------------------------------------------------------ */

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            matchPool.shutdown();
            spriteStore.values().forEach(Mat::release);
            spriteStore.clear();
            greyscaleSpriteStore.values().forEach(Mat::release);
            greyscaleSpriteStore.clear();
            rawBytesStore.clear();
        }));

        preloadSpriteStore();
    }

    /**
     * Iterates over every {@link TemplatesEnum} value and decodes both
     * colour and greyscale copies into the sprite stores.  Runs on the
     * dedicated pool so that class-loading is not blocked.
     */
    private static void preloadSpriteStore() {
        if (storeReady) return;

        matchPool.submit(() -> {
            try {
                log.info("Pre-loading sprite store...");
                for (TemplatesEnum vt : TemplatesEnum.values()) {
                    String path = vt.getTemplate();
                    try {
                        fetchSprite(path);
                        fetchGreyscaleSprite(path);
                        log.debug(tagged("Sprite cached: " + path));
                    } catch (Exception ex) {
                        log.warn(tagged("Sprite skipped: " + path + " - " + ex.getMessage()));
                    }
                }
                storeReady = true;
                log.info(tagged("Sprite store ready - colour=" + spriteStore.size()
                        + ", grey=" + greyscaleSpriteStore.size()));
            } catch (Exception ex) {
                log.error(tagged("Sprite store pre-load failed: " + ex.getMessage()));
            }
        });
    }

    /* ================================================================== */
    /*  Public entry points – RawImageData (raw pixel) overloads          */
    /* ================================================================== */

    /** Colour single-hit search against a raw device capture. */
    public static ImageSearchResultData locatePattern(RawImageData capture, String spritePath,
                                            PointData topLeft, PointData bottomRight,
                                            double threshold) {
        return scanCapture(capture.getData(), capture.getWidth(), capture.getHeight(),
                capture.getBpp(), spritePath, topLeft, bottomRight, threshold);
    }

    /** Colour multi-hit search against a raw device capture. */
    public static List<ImageSearchResultData> locateAllPatterns(RawImageData capture, String spritePath,
                                                      PointData topLeft, PointData bottomRight,
                                                      double threshold, int limit) {
        return scanCaptureMulti(capture.getData(), capture.getWidth(), capture.getHeight(),
                capture.getBpp(), spritePath, topLeft, bottomRight, threshold, limit);
    }

    /** Greyscale single-hit search against a raw device capture. */
    public static ImageSearchResultData locatePatternMono(RawImageData capture, String spritePath,
                                                PointData topLeft, PointData bottomRight,
                                                double threshold) {
        return scanCaptureMono(capture.getData(), capture.getWidth(), capture.getHeight(),
                capture.getBpp(), spritePath, topLeft, bottomRight, threshold);
    }

    /** Greyscale multi-hit search against a raw device capture. */
    public static List<ImageSearchResultData> locateAllPatternsMono(RawImageData capture,
                                                          String spritePath,
                                                          PointData topLeft, PointData bottomRight,
                                                          double threshold, int limit) {
        return scanCaptureMonoMulti(capture.getData(), capture.getWidth(), capture.getHeight(),
                capture.getBpp(), spritePath, topLeft, bottomRight, threshold, limit);
    }

    /* ================================================================== */
    /*  Public entry points – encoded byte[] (PNG/JPEG) overloads          */
    /* ================================================================== */

    /** Colour single-hit search against an encoded (PNG/JPEG) screenshot. */
    public static ImageSearchResultData locatePattern(byte[] encoded, String spritePath,
                                            PointData topLeft, PointData bottomRight,
                                            double threshold) {
        return scanEncoded(encoded, spritePath, topLeft, bottomRight, threshold);
    }

    /** Colour multi-hit search against an encoded screenshot. */
    public static List<ImageSearchResultData> locateAllPatterns(byte[] encoded, String spritePath,
                                                      PointData topLeft, PointData bottomRight,
                                                      double threshold, int limit) {
        return scanEncodedMulti(encoded, spritePath, topLeft, bottomRight, threshold, limit);
    }

    /** Greyscale single-hit search against an encoded screenshot. */
    public static ImageSearchResultData locatePatternMono(byte[] encoded, String spritePath,
                                                PointData topLeft, PointData bottomRight,
                                                double threshold) {
        return scanEncodedMono(encoded, spritePath, topLeft, bottomRight, threshold);
    }

    /** Greyscale multi-hit search against an encoded screenshot. */
    public static List<ImageSearchResultData> locateAllPatternsMono(byte[] encoded, String spritePath,
                                                          PointData topLeft, PointData bottomRight,
                                                          double threshold, int limit) {
        return scanEncodedMonoMulti(encoded, spritePath, topLeft, bottomRight, threshold, limit);
    }

    /** Raw-pixel multi-hit with explicit dimensions. */
    public static List<ImageSearchResultData> locateAllPatterns(byte[] pixels, int w, int h, int bpp,
                                                      String spritePath,
                                                      PointData topLeft, PointData bottomRight,
                                                      double threshold, int limit) {
        return scanCaptureMulti(pixels, w, h, bpp, spritePath, topLeft, bottomRight,
                threshold, limit);
    }

        /* ================================================================== */
    // Ad-hoc sprite matching from caller-supplied bytes (not cached)
        /* ================================================================== */

    /**
     * Searches for a template provided as raw bytes (e.g. loaded from an
     * absolute filesystem path) against a captured emulator screen image.
     *
     * <p>This bypasses the classpath resource cache entirely; the template Mat is
     * decoded on-the-fly from the supplied bytes. The result is NOT cached.</p>
     *
     * @param rawImage      Raw emulator screenshot
     * @param templateBytes PNG/JPG bytes of the custom template image
     * @param topLeft       Top-left corner of the search region
     * @param bottomRight   Bottom-right corner of the search region
     * @param threshold     Match threshold (0-100)
     * @return Search result with found flag, coordinates and match percentage
     */
    public static ImageSearchResultData matchFromRawTemplate(RawImageData rawImage, byte[] templateBytes,
            PointData topLeft, PointData bottomRight, double threshold) {

        Mat frameMat = null;
        Mat template = null;
        Mat roiSlice = null;
        Mat heatmap = null;

        try {
            // Decode template from supplied bytes
            MatOfByte mob = new MatOfByte(templateBytes);
            template = Imgcodecs.imdecode(mob, Imgcodecs.IMREAD_COLOR);
            if (template == null || template.empty()) {
                log.error(tagged("matchFromSuppliedBytes: could not decode template bytes"));
                return new ImageSearchResultData(false, null, 0.0);
            }

            // Convert screen raw data to Mat
            frameMat = pixelsToMat(rawImage.getData(), rawImage.getWidth(), rawImage.getHeight(),
                    rawImage.getBpp());
            if (frameMat.empty()) {
                return new ImageSearchResultData(false, null, 0.0);
            }

            // Clamp ROI to image bounds
            int clipX = Math.max(0, topLeft.getX());
            int clipY = Math.max(0, topLeft.getY());
            int roiW = Math.min(bottomRight.getX(), frameMat.cols()) - clipX;
            int roiH = Math.min(bottomRight.getY(), frameMat.rows()) - clipY;

            if (roiW <= 0 || roiH <= 0) {
                return new ImageSearchResultData(false, null, 0.0);
            }

            int resultCols = roiW - template.cols() + 1;
            int resultRows = roiH - template.rows() + 1;
            if (resultCols <= 0 || resultRows <= 0) {
                return new ImageSearchResultData(false, null, 0.0);
            }

            Rect roi = new Rect(clipX, clipY, roiW, roiH);
            roiSlice = new Mat(frameMat, roi);
            heatmap = new Mat(resultRows, resultCols, CvType.CV_32FC1);
            Imgproc.matchTemplate(roiSlice, template, heatmap, Imgproc.TM_CCOEFF_NORMED);

            Core.MinMaxLocResult mmr = Core.minMaxLoc(heatmap);
            double boundedVal = Math.max(-1.0, Math.min(1.0, mmr.maxVal));
            if (Double.isNaN(boundedVal) || Double.isInfinite(boundedVal)) {
                return new ImageSearchResultData(false, null, 0.0);
            }
            double matchPct = boundedVal * 100.0;

            if (matchPct < threshold) {
                log.info(tagged(
                        "Custom template (bytes): NOT FOUND (match: " + String.format("%.2f", matchPct) + "%)"));
                return new ImageSearchResultData(false, null, matchPct);
            }

            int centerX = (int) (mmr.maxLoc.x + roi.x + template.cols() / 2.0);
            int centerY = (int) (mmr.maxLoc.y + roi.y + template.rows() / 2.0);
            log.info(tagged("Custom template (bytes): FOUND at (" + centerX + "," + centerY
                    + ") match: " + String.format("%.2f", matchPct) + "%"));
            return new ImageSearchResultData(true, new PointData(centerX, centerY), matchPct);

        } catch (Exception e) {
            log.error(tagged("matchFromSuppliedBytes: exception during search"), e);
            return new ImageSearchResultData(false, null, 0.0);
        } finally {
            if (frameMat != null) frameMat.release();
            if (template != null) template.release();
            if (roiSlice != null) roiSlice.release();
            if (heatmap != null) heatmap.release();
        }
    }

    /**
     * Grayscale variant of {@link #matchFromSuppliedBytes}. Both the template
     * and the screen ROI are converted to grayscale before matching.
     *
     * @param rawImage      Raw emulator screenshot
     * @param templateBytes PNG/JPG bytes of the custom template image
     * @param topLeft       Top-left corner of the search region
     * @param bottomRight   Bottom-right corner of the search region
     * @param threshold     Match threshold (0-100)
     * @return Search result with found flag, coordinates and match percentage
     */
    public static ImageSearchResultData matchFromRawTemplateMono(RawImageData rawImage, byte[] templateBytes,
            PointData topLeft, PointData bottomRight, double threshold) {

        Mat frameMat = null;
        Mat template = null;
        Mat grayTemplate = null;
        Mat grayROI = null;
        Mat roiSlice = null;
        Mat heatmap = null;

        try {
            MatOfByte mob = new MatOfByte(templateBytes);
            template = Imgcodecs.imdecode(mob, Imgcodecs.IMREAD_COLOR);
            if (template == null || template.empty()) {
                log.error(tagged("matchFromSuppliedBytesMono: could not decode template bytes"));
                return new ImageSearchResultData(false, null, 0.0);
            }

            grayTemplate = new Mat();
            Imgproc.cvtColor(template, grayTemplate, Imgproc.COLOR_BGR2GRAY);

            frameMat = pixelsToMat(rawImage.getData(), rawImage.getWidth(), rawImage.getHeight(),
                    rawImage.getBpp());
            if (frameMat.empty()) {
                return new ImageSearchResultData(false, null, 0.0);
            }

            int clipX = Math.max(0, topLeft.getX());
            int clipY = Math.max(0, topLeft.getY());
            int roiW = Math.min(bottomRight.getX(), frameMat.cols()) - clipX;
            int roiH = Math.min(bottomRight.getY(), frameMat.rows()) - clipY;

            if (roiW <= 0 || roiH <= 0) {
                return new ImageSearchResultData(false, null, 0.0);
            }

            int resultCols = roiW - grayTemplate.cols() + 1;
            int resultRows = roiH - grayTemplate.rows() + 1;
            if (resultCols <= 0 || resultRows <= 0) {
                return new ImageSearchResultData(false, null, 0.0);
            }

            Rect roi = new Rect(clipX, clipY, roiW, roiH);
            roiSlice = new Mat(frameMat, roi);
            grayROI = new Mat();
            Imgproc.cvtColor(roiSlice, grayROI, Imgproc.COLOR_BGR2GRAY);

            heatmap = new Mat(resultRows, resultCols, CvType.CV_32FC1);
            Imgproc.matchTemplate(grayROI, grayTemplate, heatmap, Imgproc.TM_CCOEFF_NORMED);

            Core.MinMaxLocResult mmr = Core.minMaxLoc(heatmap);
            double boundedVal = Math.max(-1.0, Math.min(1.0, mmr.maxVal));
            if (Double.isNaN(boundedVal) || Double.isInfinite(boundedVal)) {
                return new ImageSearchResultData(false, null, 0.0);
            }
            double matchPct = boundedVal * 100.0;

            if (matchPct < threshold) {
                return new ImageSearchResultData(false, null, matchPct);
            }

            int centerX = (int) (mmr.maxLoc.x + roi.x + grayTemplate.cols() / 2.0);
            int centerY = (int) (mmr.maxLoc.y + roi.y + grayTemplate.rows() / 2.0);
            return new ImageSearchResultData(true, new PointData(centerX, centerY), matchPct);

        } catch (Exception e) {
            log.error(tagged("matchFromSuppliedBytesMono: exception during search"), e);
            return new ImageSearchResultData(false, null, 0.0);
        } finally {
            if (frameMat != null) frameMat.release();
            if (template != null) template.release();
            if (grayTemplate != null) grayTemplate.release();
            if (roiSlice != null) roiSlice.release();
            if (grayROI != null) grayROI.release();
            if (heatmap != null) heatmap.release();
        }
    }

    /**
     * Loads and caches a reference image from the classpath.
     */
    private static Mat fetchSprite(String spritePath) {
        // Try to get from cache first
        Mat cachedTemplate = spriteStore.get(spritePath);
        if (cachedTemplate != null && !cachedTemplate.empty()) {
            return cachedTemplate.clone(); // Return a copy for thread safety
        }

        try {
            // Load bytes from cache or resource
            byte[] templateBytes = rawBytesStore.computeIfAbsent(spritePath, path -> {
                try (InputStream is = OpenCvPatternLocator.class.getResourceAsStream(path)) {
                    if (is == null) {
                        log.error(tagged("Reference asset not found: " + path));
                        return null;
                    }
                    return is.readAllBytes();
                } catch (IOException e) {
                    log.error(tagged("Failed to read reference bytes from: " + path), e);
                    return null;
                }
            });

            if (templateBytes == null) {
                return new Mat(); // Empty Mat
            }

            // Decode template
            MatOfByte templateMatOfByte = new MatOfByte(templateBytes);
            Mat template = Imgcodecs.imdecode(templateMatOfByte, Imgcodecs.IMREAD_COLOR);

            if (!template.empty()) {
                // Save to cache (clone to avoid modifications)
                spriteStore.put(spritePath, template.clone());
            }

            return template;

        } catch (Exception e) {
            log.error(tagged("Exception decoding reference: " + spritePath), e);
            return new Mat();
        }
    }

    /**
     * Loads and caches a luminance-only reference image.
     */
    private static Mat fetchGreyscaleSprite(String spritePath) {
        // Try to get from grayscale cache first
        Mat cachedTemplate = greyscaleSpriteStore.get(spritePath);
        if (cachedTemplate != null && !cachedTemplate.empty()) {
            return cachedTemplate.clone(); // Return a copy for thread safety
        }

        try {
            // Load the color template first
            Mat colorTemplate = fetchSprite(spritePath);
            if (colorTemplate.empty()) {
                return new Mat();
            }

            // Convert to grayscale
            Mat grayTemplate = new Mat();
            Imgproc.cvtColor(colorTemplate, grayTemplate, Imgproc.COLOR_BGR2GRAY);

            // Save to grayscale cache
            if (!grayTemplate.empty()) {
                greyscaleSpriteStore.put(spritePath, grayTemplate.clone());
            }

            // Release color template as we don't need it anymore
            colorTemplate.release();

            return grayTemplate;
        } catch (Exception e) {
            log.error(tagged("Exception decoding mono reference: " + spritePath), e);
            return new Mat();
        }
    }

    /**
     * Loads a mask for the given template if it exists.
     * Masks follow the naming convention: path/template_mask.png
     * 
     * @param spritePath The template resource path
     * @return Mat containing the mask, or null if no mask exists
     */
    private static Mat fetchSpriteMask(String spritePath) {
        // Derive mask path from template path
        // Examples:
        // - /path/template.png -> /path/template_mask.png
        // - /path/template_CH.png -> /path/template_mask.png (same mask for both)

        String maskPath;
        if (spritePath.contains("_CH.png")) {
            // For region-specific templates, use base mask
            maskPath = spritePath.replace("_CH.png", "_mask.png");
        } else if (spritePath.endsWith(".png")) {
            // For global templates
            maskPath = spritePath.replace(".png", "_mask.png");
        } else {
            return null; // Unsupported format
        }

        // Try to get from cache first
        Mat cachedMask = spriteStore.get(maskPath);
        if (cachedMask != null && !cachedMask.empty()) {
            return cachedMask.clone(); // Return a copy for thread safety
        }

        try {
            // Load bytes from cache or resource
            byte[] maskBytes = rawBytesStore.computeIfAbsent(maskPath, path -> {
                try (InputStream is = OpenCvPatternLocator.class.getResourceAsStream(path)) {
                    if (is == null) {
                        // No mask file found - this is normal, not all templates have masks
                        log.debug("No mask found for Pattern: {}", spritePath);
                        return null;
                    }
                    log.debug("Mask found and loaded: {}", maskPath);
                    return is.readAllBytes();
                } catch (IOException e) {
                    log.debug("Error loading mask for: {}", path);
                    return null;
                }
            });

            if (maskBytes == null) {
                return null; // No mask available
            }

            // Decode mask (load as grayscale)
            MatOfByte maskMatOfByte = new MatOfByte(maskBytes);
            Mat mask = Imgcodecs.imdecode(maskMatOfByte, Imgcodecs.IMREAD_GRAYSCALE);

            if (!mask.empty()) {
                // Save to cache (clone to avoid modifications)
                spriteStore.put(maskPath, mask.clone());
                return mask;
            }

            return null;

        } catch (Exception e) {
            log.debug("Exception loading mask for: {}", spritePath);
            return null;
        }
    }

    /**
     * Performs correlation-based pattern search with cache and better memory
     * management.
     */
    public static ImageSearchResultData scanCapture(byte[] rawImageData, int width, int height, int bpp,
            String spritePath, PointData upperLeft, PointData lowerRight,
            double threshold) {

        long startTime = System.currentTimeMillis();
        log.debug("=== Pattern Correlation Started ===");
        log.debug("Pattern: {}, Threshold: {}%, ROI: ({},{}) to ({},{})",
                spritePath, threshold, upperLeft.getX(), upperLeft.getY(),
                lowerRight.getX(), lowerRight.getY());

        Mat frameMat = null;
        Mat template = null;
        Mat mask = null;
        Mat roiSlice = null;
        Mat heatmap = null;

        String[] pathParts = spritePath.split("/");
        String spriteLabel = pathParts[pathParts.length - 1];

        try {
            // Convert raw image data directly to OpenCV Mat
            long conversionStartTime = System.currentTimeMillis();
            frameMat = pixelsToMat(rawImageData, width, height, bpp);
            long conversionEndTime = System.currentTimeMillis();
            log.debug("Raw data to Mat conversion: {} ms", (conversionEndTime - conversionStartTime));

            if (frameMat.empty()) {
                log.error("Decoded frame is empty");
                return new ImageSearchResultData(false, null, 0.0);
            }

            // Quick ROI validation
            int clipX = upperLeft.getX();
            int clipY = upperLeft.getY();
            int clipW = lowerRight.getX() - upperLeft.getX();
            int clipH = lowerRight.getY() - upperLeft.getY();

            if (clipW <= 0 || clipH <= 0) {
                log.error(tagged("Clip region has non-positive extent"));
                return new ImageSearchResultData(false, null, 0.0);
            }

            // Load optimized template with cache
            long templateLoadStartTime = System.currentTimeMillis();
            template = fetchSprite(spritePath);
            long templateLoadEndTime = System.currentTimeMillis();
            log.debug("Reference loading: {} ms (from cache: {})",
                    (templateLoadEndTime - templateLoadStartTime),
                    spriteStore.containsKey(spritePath));

            if (template.empty()) {
                log.error("Reference image is blank: {}", spritePath);
                return new ImageSearchResultData(false, null, 0.0);
            }

            // Load mask if available
            mask = fetchSpriteMask(spritePath);
            if (mask != null && !mask.empty()) {
                log.debug("Applying mask for reference: {}", spritePath);
            }

            log.debug("Template size: {}x{}, Image size: {}x{}",
                    template.cols(), template.rows(), frameMat.cols(), frameMat.rows());

            // ROI vs image validation
            if (clipX + clipW > frameMat.cols() || clipY + clipH > frameMat.rows()) {
                log.error(tagged("Clip region extends beyond frame bounds"));
                return new ImageSearchResultData(false, null, 0.0);
            }

            // Create ROI
            long roiStartTime = System.currentTimeMillis();
            Rect roi = new Rect(clipX, clipY, clipW, clipH);
            roiSlice = new Mat(frameMat, roi);
            long roiEndTime = System.currentTimeMillis();
            log.debug("ROI creation: {} ms", (roiEndTime - roiStartTime));

            // Optimized size check
            int resultCols = roiSlice.cols() - template.cols() + 1;
            int resultRows = roiSlice.rows() - template.rows() + 1;
            if (resultCols <= 0 || resultRows <= 0) {
                log.error("Reference exceeds clip region");
                return new ImageSearchResultData(false, null, 0.0);
            }

            // Template matching
            long matchStartTime = System.currentTimeMillis();
            heatmap = new Mat(resultRows, resultCols, CvType.CV_32FC1);

            int method = (mask != null && !mask.empty())
                    ? Imgproc.TM_CCORR_NORMED
                    : Imgproc.TM_CCOEFF_NORMED;

            // Use mask if available, otherwise use standard matching
            if (mask != null && !mask.empty()) {
                Imgproc.matchTemplate(roiSlice, template, heatmap, method, mask);
            } else {
                Imgproc.matchTemplate(roiSlice, template, heatmap, method);
            }

            long matchEndTime = System.currentTimeMillis();
            log.debug("Template matching execution: {} ms", (matchEndTime - matchStartTime));

            // Search for the best match
            Core.MinMaxLocResult mmr = Core.minMaxLoc(heatmap);

            // Log the raw value for debugging
            if (mmr.maxVal > 1.0 || mmr.maxVal < -1.0 || Double.isNaN(mmr.maxVal) || Double.isInfinite(mmr.maxVal)) {
                log.warn("Abnormal peak score detected: {} for Pattern: {}", mmr.maxVal, spritePath);
            }

            // Clamp the value to valid range [-1.0, 1.0] before converting to percentage
            double boundedVal = Math.max(-1.0, Math.min(1.0, mmr.maxVal));

            // Handle NaN or Infinite values
            if (Double.isNaN(boundedVal) || Double.isInfinite(boundedVal)) {
                log.error("Corrupt correlation score (NaN or Infinite) for Pattern: {}", spritePath);
                return new ImageSearchResultData(false, null, 0.0);
            }

            double scorePct = boundedVal * 100.0;

            long totalTime = System.currentTimeMillis() - startTime;

            if (scorePct < threshold) {
                log.info(
                        "=== Pattern Correlation Completed === Pattern: {}, Total: {} ms, Match: {}% (BELOW threshold)",
                        spriteLabel, totalTime, String.format("%.2f", scorePct));
                return new ImageSearchResultData(false, null, scorePct);
            }

            // Calculate center coordinates
            Point bestPos = mmr.maxLoc;
            double centerX = bestPos.x + roi.x + (template.cols() / 2.0);
            double centerY = bestPos.y + roi.y + (template.rows() / 2.0);

            log.info("=== Pattern Correlation Completed === Pattern: {}, Total: {} ms, Match: {}%, Position: ({},{})",
                    spriteLabel, totalTime, String.format("%.2f", scorePct), (int) centerX, (int) centerY);

            return new ImageSearchResultData(true, new PointData((int) centerX, (int) centerY), scorePct);

        } catch (Exception e) {
            log.error(tagged("Exception during pattern correlation"), e);
            return new ImageSearchResultData(false, null, 0.0);
        } finally {
            // Explicit release of OpenCV memory
            if (frameMat != null)
                frameMat.release();
            if (template != null)
                template.release();
            if (mask != null)
                mask.release();
            if (roiSlice != null)
                roiSlice.release();
            if (heatmap != null)
                heatmap.release();
        }
    }

    private static Mat pixelsToMat(byte[] rawData, int width, int height, int bpp) {
        return decodePixelsToMat(rawData, width, height, bpp);
    }

    /* ================================================================== */
    /*  Fast-path helpers for hot-loop callers                              */
    /* ================================================================== */

    /**
     * Fast bulk conversion of raw ADB screencap data to OpenCV BGR Mat.
     * <p>
     * Uses a single bulk {@code Mat.put()} call instead of per-pixel calls,
     * reducing JNI round-trips from ~920,000 to 1-2 per frame.
     * For 32 bpp (RGBA_8888): single put + native {@code cvtColor(RGBAâ†’BGR)}.
     * For 16 bpp (RGB565): tight-loop decode to BGR array + single put.
     * <p>
     * Typically <b>10-50Ã— faster</b> than the original per-pixel version.
     *
     * @param rawData Raw pixel bytes (no header)
     * @param width   Image width in pixels
     * @param height  Image height in pixels
     * @param bpp     Bits per pixel (16 or 32)
     * @return BGR Mat (caller must release)
     */
    public static Mat decodePixelsToMat(byte[] rawData, int width, int height, int bpp) {
        if (rawData == null || rawData.length == 0 || width <= 0 || height <= 0) {
            log.warn("decodePixelsToMat: invalid input (data={}, {}x{}, {}bpp)",
                    rawData == null ? "null" : rawData.length, width, height, bpp);
            return new Mat();
        }
        if (bpp == 32) {
            // RGBA_8888 â†’ 4-channel bulk put, then native RGBAâ†’BGR
            int expectedSize = width * height * 4;
            if (rawData.length < expectedSize / 2) {
                // Data is far too small â€” likely corrupted or wrong bpp
                log.warn("decodePixelsToMat: data too small for 32bpp (got {} bytes, expected {})",
                        rawData.length, expectedSize);
                return new Mat();
            }
            Mat rgba = new Mat(height, width, CvType.CV_8UC4);
            byte[] data = (rawData.length == expectedSize)
                    ? rawData
                    : java.util.Arrays.copyOf(rawData, expectedSize);
            rgba.put(0, 0, data);
            Mat bgr = new Mat();
            try {
                // Workaround for OpenCV 4.11.0 bug where dcn sometimes gets uninitialized
                // memory
                Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR, 3);
            } catch (Exception e) {
                log.warn("decodePixelsToMat: cvtColor failed ({}x{} {}bpp, data={}): {}",
                        width, height, bpp, rawData.length, e.getMessage());
                rgba.release();
                bgr.release();
                return new Mat();
            }
            rgba.release();
            return bgr;
        } else {
            // RGB565 â†’ decode to BGR byte array in tight loop, single put
            int pixels = width * height;
            byte[] bgrData = new byte[pixels * 3];
            for (int i = 0, s = 0, d = 0; i < pixels; i++, s += 2, d += 3) {
                int pixel = ((rawData[s + 1] & 0xFF) << 8) | (rawData[s] & 0xFF);
                bgrData[d] = (byte) ((pixel & 0x1F) << 3);
                bgrData[d + 1] = (byte) (((pixel >> 5) & 0x3F) << 2);
                bgrData[d + 2] = (byte) (((pixel >> 11) & 0x1F) << 3);
            }
            Mat mat = new Mat(height, width, CvType.CV_8UC3);
            mat.put(0, 0, bgrData);
            return mat;
        }
    }

    /**
     * Returns a <b>direct reference</b> to the cached template Mat.
     * <p>
     * Unlike {@code fetchSprite()}, this does <b>not</b> clone the Mat.
     * Ideal for tight loops that search the same template every frame.
     * <p>
     * <b>WARNING:</b> The returned Mat must NOT be modified or released by the
     * caller. It is shared across all users of the cache.
     *
     * @param spritePath Resource path (e.g. from
     *                             {@code TemplatesEnum.getTemplate()})
     * @return Cached template Mat, or empty Mat if not found
     */
    public static Mat getReferenceMatrix(String spritePath) {
        Mat cached = spriteStore.get(spritePath);
        if (cached != null && !cached.empty()) {
            return cached;
        }
        // Trigger the normal load path (which clones into cache)
        Mat clone = fetchSprite(spritePath);
        if (clone != null) {
            clone.release(); // discard the clone
        }
        // Return the original from cache
        cached = spriteStore.get(spritePath);
        return (cached != null) ? cached : new Mat();
    }

    /**
     * Performs template matching directly on pre-converted OpenCV Mats.
     * <p>
     * Designed for tight loops where the caller converts the frame once and
     * caches the template reference. No logging, no path resolution, no
     * Reference loading/cloning per call â€” pure matching only.
     *
     * @param bgrImage            BGR Mat from {@link #decodePixelsToMat}
     * @param template            Template Mat from {@link #getTemplateMat}
     *                            (read-only)
     * @param topLeft             Top-left corner of search region
     * @param bottomRight         Bottom-right corner of search region
     * @param threshold Match threshold (0-100)
     * @return Search result with coordinates and confidence
     */
    public static ImageSearchResultData matchDirect(
            Mat bgrImage, Mat template,
            PointData topLeft, PointData bottomRight,
            double threshold) {

        Mat roiSlice = null;
        Mat heatmap = null;

        try {
            int clipX = topLeft.getX();
            int clipY = topLeft.getY();
            int roiW = Math.min(bottomRight.getX() - clipX, bgrImage.cols() - clipX);
            int roiH = Math.min(bottomRight.getY() - clipY, bgrImage.rows() - clipY);

            if (roiW <= template.cols() || roiH <= template.rows()) {
                return new ImageSearchResultData(false, null, 0.0);
            }

            Rect roi = new Rect(clipX, clipY, roiW, roiH);
            roiSlice = new Mat(bgrImage, roi);

            int resultCols = roiW - template.cols() + 1;
            int resultRows = roiH - template.rows() + 1;
            heatmap = new Mat(resultRows, resultCols, CvType.CV_32FC1);

            Imgproc.matchTemplate(roiSlice, template, heatmap, Imgproc.TM_CCOEFF_NORMED);

            Core.MinMaxLocResult mmr = Core.minMaxLoc(heatmap);
            double boundedVal = Math.max(-1.0, Math.min(1.0, mmr.maxVal));
            if (Double.isNaN(boundedVal) || Double.isInfinite(boundedVal)) {
                return new ImageSearchResultData(false, null, 0.0);
            }
            double matchPct = boundedVal * 100.0;

            if (matchPct < threshold) {
                return new ImageSearchResultData(false, null, matchPct);
            }

            int centerX = (int) (mmr.maxLoc.x + clipX + template.cols() / 2.0);
            int centerY = (int) (mmr.maxLoc.y + clipY + template.rows() / 2.0);
            return new ImageSearchResultData(true, new PointData(centerX, centerY), matchPct);

        } finally {
            if (roiSlice != null)
                roiSlice.release();
            if (heatmap != null)
                heatmap.release();
        }
    }

    /**
     * Optimized version for multiple search with parallelization.
     */
    public static CompletableFuture<List<ImageSearchResultData>> locateAllPatternsAsync(byte[] image, String spritePath, PointData upperLeft,
            PointData lowerRight, double threshold, int maxHits) {

        return CompletableFuture.supplyAsync(() -> scanEncodedMulti(image, spritePath,
                upperLeft, lowerRight, threshold, maxHits), matchPool);
    }

    /**
     * Optimized version of multiple search with better memory management.
     */
    public static List<ImageSearchResultData> scanEncodedMulti(byte[] image,
            String spritePath, PointData upperLeft, PointData lowerRight,
            double threshold, int maxHits) {

        List<ImageSearchResultData> results = new ArrayList<>();
        Mat decodedFrame = null;
        Mat template = null;
        Mat imageROI = null;
        Mat correlation = null;
        Mat heatmapCopy = null;

        try {
            // Quick ROI validation
            int clipX = upperLeft.getX();
            int clipY = upperLeft.getY();
            int clipW = lowerRight.getX() - upperLeft.getX();
            int clipH = lowerRight.getY() - upperLeft.getY();

            if (clipW <= 0 || clipH <= 0) {
                return results;
            }

            // Optimized decoding
            MatOfByte matOfByte = new MatOfByte(image);
            decodedFrame = Imgcodecs.imdecode(matOfByte, Imgcodecs.IMREAD_COLOR);

            if (decodedFrame.empty()) {
                return results;
            }

            // Load template with cache
            template = fetchSprite(spritePath);
            if (template.empty()) {
                return results;
            }

            // Validations
            if (clipX + clipW > decodedFrame.cols() || clipY + clipH > decodedFrame.rows()) {
                return results;
            }

            // Create ROI
            Rect roi = new Rect(clipX, clipY, clipW, clipH);
            imageROI = new Mat(decodedFrame, roi);

            int resultCols = imageROI.cols() - template.cols() + 1;
            int resultRows = imageROI.rows() - template.rows() + 1;
            if (resultCols <= 0 || resultRows <= 0) {
                return results;
            }

            // Template matching
            correlation = new Mat(resultRows, resultCols, CvType.CV_32FC1);
            Imgproc.matchTemplate(imageROI, template, correlation, Imgproc.TM_CCOEFF_NORMED);

            // Optimized search for multiple matches
            double normThreshold = threshold / 100.0;
            heatmapCopy = correlation.clone();
            int sprW = template.cols();
            int sprH = template.rows();

            // Pre-calculate for optimization
            int halfSpriteW = sprW / 2;
            int halfSpriteH = sprH / 2;

            while (results.size() < maxHits || maxHits <= 0) {
                Core.MinMaxLocResult mmr = Core.minMaxLoc(heatmapCopy);
                double score = mmr.maxVal;

                if (score < normThreshold) {
                    break;
                }

                Point bestPos = mmr.maxLoc;
                double centerX = bestPos.x + roi.x + halfSpriteW;
                double centerY = bestPos.y + roi.y + halfSpriteH;

                results.add(new ImageSearchResultData(true,
                        new PointData((int) centerX, (int) centerY), score * 100.0));

                // Optimized suppression
                int supX = Math.max(0, (int) bestPos.x - halfSpriteW);
                int supY = Math.max(0, (int) bestPos.y - halfSpriteH);
                int supW = Math.min(sprW, heatmapCopy.cols() - supX);
                int supH = Math.min(sprH, heatmapCopy.rows() - supY);

                if (supW > 0 && supH > 0) {
                    Rect suppressRect = new Rect(supX, supY, supW, supH);
                    Mat suppressedRegion = new Mat(heatmapCopy, suppressRect);
                    suppressedRegion.setTo(new org.opencv.core.Scalar(0));
                    suppressedRegion.release();
                }
            }

        } catch (Exception e) {
            log.error(tagged("Exception during multi-hit correlation"), e);
        } finally {
            // Explicit memory release
            if (decodedFrame != null)
                decodedFrame.release();
            if (template != null)
                template.release();
            if (imageROI != null)
                imageROI.release();
            if (correlation != null)
                correlation.release();
            if (heatmapCopy != null)
                heatmapCopy.release();
        }

        return results;
    }

    /**
     * Performs correlation-based pattern search for encoded images (PNG/JPEG).
     */
    private static ImageSearchResultData scanEncoded(byte[] image, String spritePath,
            PointData upperLeft, PointData lowerRight, double threshold) {

        Mat frameMat = null;
        Mat template = null;
        Mat roiSlice = null;
        Mat heatmap = null;

        try {
            // Quick ROI validation
            int clipX = upperLeft.getX();
            int clipY = upperLeft.getY();
            int clipW = lowerRight.getX() - upperLeft.getX();
            int clipH = lowerRight.getY() - upperLeft.getY();

            if (clipW <= 0 || clipH <= 0) {
                log.error(tagged("Clip region has non-positive extent"));
                return new ImageSearchResultData(false, null, 0.0);
            }

            // Decode image
            MatOfByte matOfByte = new MatOfByte(image);
            frameMat = Imgcodecs.imdecode(matOfByte, Imgcodecs.IMREAD_COLOR);

            if (frameMat.empty()) {
                return new ImageSearchResultData(false, null, 0.0);
            }

            // Load optimized template with cache
            template = fetchSprite(spritePath);
            if (template.empty()) {
                return new ImageSearchResultData(false, null, 0.0);
            }

            // ROI vs image validation
            if (clipX + clipW > frameMat.cols() || clipY + clipH > frameMat.rows()) {
                log.error(tagged("Clip region extends beyond frame bounds"));
                return new ImageSearchResultData(false, null, 0.0);
            }

            // Create ROI
            Rect roi = new Rect(clipX, clipY, clipW, clipH);
            roiSlice = new Mat(frameMat, roi);

            // Optimized size check
            int resultCols = roiSlice.cols() - template.cols() + 1;
            int resultRows = roiSlice.rows() - template.rows() + 1;
            if (resultCols <= 0 || resultRows <= 0) {
                return new ImageSearchResultData(false, null, 0.0);
            }

            // Template matching
            heatmap = new Mat(resultRows, resultCols, CvType.CV_32FC1);
            Imgproc.matchTemplate(roiSlice, template, heatmap, Imgproc.TM_CCOEFF_NORMED);

            // Search for the best match
            Core.MinMaxLocResult mmr = Core.minMaxLoc(heatmap);
            double scorePct = mmr.maxVal * 100.0;

            if (scorePct < threshold) {
                log.warn(tagged("Template " + spritePath + " match percentage " + scorePct
                        + " below threshold " + threshold));
                return new ImageSearchResultData(false, null, scorePct);
            }

            log.info(tagged(
                    "Template " + spritePath + " found with match percentage: " + scorePct));

            // Calculate center coordinates
            Point bestPos = mmr.maxLoc;
            double centerX = bestPos.x + roi.x + (template.cols() / 2.0);
            double centerY = bestPos.y + roi.y + (template.rows() / 2.0);

            return new ImageSearchResultData(true, new PointData((int) centerX, (int) centerY), scorePct);

        } catch (Exception e) {
            log.error(tagged("Exception during pattern correlation"), e);
            return new ImageSearchResultData(false, null, 0.0);
        } finally {
            // Explicit release of OpenCV memory
            if (frameMat != null)
                frameMat.release();
            if (template != null)
                template.release();
            if (roiSlice != null)
                roiSlice.release();
            if (heatmap != null)
                heatmap.release();
        }
    }



    /**
     * Grayscale search for encoded images (PNG/JPEG).
     */
    private static ImageSearchResultData scanEncodedMono(byte[] image,
            String spritePath,
            PointData upperLeft, PointData lowerRight, double threshold) {

        Mat frameMat = null;
        Mat frameGrey = null;
        Mat template = null;
        Mat roiSlice = null;
        Mat heatmap = null;

        try {
            // Quick ROI validation
            int clipX = upperLeft.getX();
            int clipY = upperLeft.getY();
            int clipW = lowerRight.getX() - upperLeft.getX();
            int clipH = lowerRight.getY() - upperLeft.getY();

            if (clipW <= 0 || clipH <= 0) {
                log.error(tagged("Clip region has non-positive extent"));
                return new ImageSearchResultData(false, null, 0.0);
            }

            // Decoding of main image
            MatOfByte matOfByte = new MatOfByte(image);
            frameMat = Imgcodecs.imdecode(matOfByte, Imgcodecs.IMREAD_COLOR);

            if (frameMat.empty()) {
                return new ImageSearchResultData(false, null, 0.0);
            }

            // Convert main image to grayscale
            frameGrey = new Mat();
            Imgproc.cvtColor(frameMat, frameGrey, Imgproc.COLOR_BGR2GRAY);
            frameMat.release();
            frameMat = null;

            // Load optimized Mono reference with cache
            template = fetchGreyscaleSprite(spritePath);
            if (template.empty()) {
                return new ImageSearchResultData(false, null, 0.0);
            }

            // ROI vs image validation
            if (clipX + clipW > frameGrey.cols() || clipY + clipH > frameGrey.rows()) {
                log.error(tagged("Clip region extends beyond frame bounds"));
                return new ImageSearchResultData(false, null, 0.0);
            }

            // Create ROI
            Rect roi = new Rect(clipX, clipY, clipW, clipH);
            roiSlice = new Mat(frameGrey, roi);

            // Optimized size check
            int resultCols = roiSlice.cols() - template.cols() + 1;
            int resultRows = roiSlice.rows() - template.rows() + 1;
            if (resultCols <= 0 || resultRows <= 0) {
                return new ImageSearchResultData(false, null, 0.0);
            }

            // Template matching
            heatmap = new Mat(resultRows, resultCols, CvType.CV_32FC1);
            Imgproc.matchTemplate(roiSlice, template, heatmap, Imgproc.TM_CCOEFF_NORMED);

            // Search for the best match
            Core.MinMaxLocResult mmr = Core.minMaxLoc(heatmap);
            double scorePct = mmr.maxVal * 100.0;

            if (scorePct < threshold) {
                log.warn(tagged("Mono reference " + spritePath + " match percentage "
                        + scorePct + " below threshold " + threshold));
                return new ImageSearchResultData(false, null, scorePct);
            }

            log.info(tagged(
                    "Mono reference " + spritePath + " found with match percentage: " + scorePct));

            // Calculate center point of the match (taking ROI into profile)
            int centerX = (int) (mmr.maxLoc.x + (double) template.cols() / 2 + clipX);
            int centerY = (int) (mmr.maxLoc.y + (double) template.rows() / 2 + clipY);

            return new ImageSearchResultData(true, new PointData(centerX, centerY), scorePct);

        } catch (Exception e) {
            log.error(tagged("Exception during mono pattern correlation"), e);
            return new ImageSearchResultData(false, null, 0.0);
        } finally {
            // Explicit memory release for all Mat objects
            if (frameMat != null)
                frameMat.release();
            if (frameGrey != null)
                frameGrey.release();
            if (template != null)
                template.release();
            if (roiSlice != null)
                roiSlice.release();
            if (heatmap != null)
                heatmap.release();
        }
    }

    /**
     * Grayscale search for multiple matches in encoded images (PNG/JPEG).
     */
    private static List<ImageSearchResultData> scanEncodedMonoMulti(byte[] image,
            String spritePath, PointData upperLeft, PointData lowerRight,
            double threshold, int maxHits) {

        List<ImageSearchResultData> results = new ArrayList<>();
        Mat decodedFrame = null;
        Mat decodedGrey = null;
        Mat template = null;
        Mat imageROI = null;
        Mat correlation = null;
        Mat heatmapCopy = null;

        try {
            // Quick ROI validation
            int clipX = upperLeft.getX();
            int clipY = upperLeft.getY();
            int clipW = lowerRight.getX() - upperLeft.getX();
            int clipH = lowerRight.getY() - upperLeft.getY();

            if (clipW <= 0 || clipH <= 0) {
                return results;
            }

            // Optimized decoding
            MatOfByte matOfByte = new MatOfByte(image);
            decodedFrame = Imgcodecs.imdecode(matOfByte, Imgcodecs.IMREAD_COLOR);

            if (decodedFrame.empty()) {
                return results;
            }

            // Convert to grayscale
            decodedGrey = new Mat();
            Imgproc.cvtColor(decodedFrame, decodedGrey, Imgproc.COLOR_BGR2GRAY);
            decodedFrame.release();
            decodedFrame = null;

            // Load Mono reference with cache
            template = fetchGreyscaleSprite(spritePath);
            if (template.empty()) {
                return results;
            }

            // Validations
            if (clipX + clipW > decodedGrey.cols() || clipY + clipH > decodedGrey.rows()) {
                return results;
            }

            // Create ROI
            Rect roi = new Rect(clipX, clipY, clipW, clipH);
            imageROI = new Mat(decodedGrey, roi);

            int resultCols = imageROI.cols() - template.cols() + 1;
            int resultRows = imageROI.rows() - template.rows() + 1;
            if (resultCols <= 0 || resultRows <= 0) {
                return results;
            }

            // Template matching
            correlation = new Mat(resultRows, resultCols, CvType.CV_32FC1);
            Imgproc.matchTemplate(imageROI, template, correlation, Imgproc.TM_CCOEFF_NORMED);

            // Optimized search for multiple matches
            double normThreshold = threshold / 100.0;
            heatmapCopy = correlation.clone();
            int sprW = template.cols();
            int sprH = template.rows();

            // Pre-calculate for optimization
            int halfSpriteW = sprW / 2;
            int halfSpriteH = sprH / 2;

            while (results.size() < maxHits || maxHits <= 0) {
                Core.MinMaxLocResult mmr = Core.minMaxLoc(heatmapCopy);
                double score = mmr.maxVal;

                if (score < normThreshold) {
                    break;
                }

                Point bestPos = mmr.maxLoc;
                double centerX = bestPos.x + roi.x + halfSpriteW;
                double centerY = bestPos.y + roi.y + halfSpriteH;

                results.add(new ImageSearchResultData(true,
                        new PointData((int) centerX, (int) centerY), score * 100.0));

                // Optimized suppression
                int supX = Math.max(0, (int) bestPos.x - halfSpriteW);
                int supY = Math.max(0, (int) bestPos.y - halfSpriteH);
                int supW = Math.min(sprW, heatmapCopy.cols() - supX);
                int supH = Math.min(sprH, heatmapCopy.rows() - supY);

                if (supW > 0 && supH > 0) {
                    Rect suppressRect = new Rect(supX, supY, supW, supH);
                    Mat suppressedRegion = new Mat(heatmapCopy, suppressRect);
                    suppressedRegion.setTo(new org.opencv.core.Scalar(0));
                    suppressedRegion.release();
                }
            }

        } catch (Exception e) {
            log.error(tagged("Exception during multi-hit correlation"), e);
        } finally {
            // Explicit memory release
            if (decodedFrame != null)
                decodedFrame.release();
            if (template != null)
                template.release();
            if (imageROI != null)
                imageROI.release();
            if (correlation != null)
                correlation.release();
            if (heatmapCopy != null)
                heatmapCopy.release();
        }

        return results;
    }

    /**
     * Grayscale search for raw image data.
     */
    private static ImageSearchResultData scanCaptureMono(byte[] rawImageData, int width, int height,
            int bpp,
            String spritePath, PointData upperLeft, PointData lowerRight,
            double threshold) {

        Mat frameMat = null;
        Mat frameGrey = null;
        Mat template = null;
        Mat roiSlice = null;
        Mat heatmap = null;

        try {
            // Convert raw image data directly to OpenCV Mat
            frameMat = pixelsToMat(rawImageData, width, height, bpp);

            if (frameMat.empty()) {
                return new ImageSearchResultData(false, null, 0.0);
            }

            // Quick ROI validation
            int clipX = upperLeft.getX();
            int clipY = upperLeft.getY();
            int clipW = lowerRight.getX() - upperLeft.getX();
            int clipH = lowerRight.getY() - upperLeft.getY();

            if (clipW <= 0 || clipH <= 0) {
                log.error(tagged("Clip region has non-positive extent"));
                return new ImageSearchResultData(false, null, 0.0);
            }

            // Convert main image to grayscale
            frameGrey = new Mat();
            Imgproc.cvtColor(frameMat, frameGrey, Imgproc.COLOR_BGR2GRAY);
            frameMat.release();
            frameMat = null;

            // Load optimized Mono reference with cache
            template = fetchGreyscaleSprite(spritePath);
            if (template.empty()) {
                return new ImageSearchResultData(false, null, 0.0);
            }

            // ROI vs image validation
            if (clipX + clipW > frameGrey.cols() || clipY + clipH > frameGrey.rows()) {
                log.error(tagged("Clip region extends beyond frame bounds"));
                return new ImageSearchResultData(false, null, 0.0);
            }

            // Create ROI
            Rect roi = new Rect(clipX, clipY, clipW, clipH);
            roiSlice = new Mat(frameGrey, roi);

            // Optimized size check
            int resultCols = roiSlice.cols() - template.cols() + 1;
            int resultRows = roiSlice.rows() - template.rows() + 1;
            if (resultCols <= 0 || resultRows <= 0) {
                return new ImageSearchResultData(false, null, 0.0);
            }

            // Template matching
            heatmap = new Mat(resultRows, resultCols, CvType.CV_32FC1);
            Imgproc.matchTemplate(roiSlice, template, heatmap, Imgproc.TM_CCOEFF_NORMED);

            // Search for the best match
            Core.MinMaxLocResult mmr = Core.minMaxLoc(heatmap);
            double scorePct = mmr.maxVal * 100.0;

            if (scorePct < threshold) {
                log.warn(tagged("Mono reference " + spritePath + " match percentage "
                        + scorePct + " below threshold " + threshold));
                return new ImageSearchResultData(false, null, scorePct);
            }

            log.info(tagged(
                    "Mono reference " + spritePath + " found with match percentage: " + scorePct));

            // Calculate center point of the match (taking ROI into profile)
            int centerX = (int) (mmr.maxLoc.x + (double) template.cols() / 2 + clipX);
            int centerY = (int) (mmr.maxLoc.y + (double) template.rows() / 2 + clipY);

            return new ImageSearchResultData(true, new PointData(centerX, centerY), scorePct);

        } catch (Exception e) {
            log.error(tagged("Exception during mono pattern correlation with raw data"), e);
            return new ImageSearchResultData(false, null, 0.0);
        } finally {
            // Explicit memory release for all Mat objects
            if (frameMat != null)
                frameMat.release();
            if (frameGrey != null)
                frameGrey.release();
            if (template != null)
                template.release();
            if (roiSlice != null)
                roiSlice.release();
            if (heatmap != null)
                heatmap.release();
        }
    }

    /**
     * Grayscale search for multiple matches using raw image data.
     */
    private static List<ImageSearchResultData> scanCaptureMonoMulti(byte[] rawImageData,
            int width, int height, int bpp,
            String spritePath, PointData upperLeft, PointData lowerRight,
            double threshold, int maxHits) {

        List<ImageSearchResultData> results = new ArrayList<>();
        Mat decodedFrame = null;
        Mat decodedGrey = null;
        Mat template = null;
        Mat imageROI = null;
        Mat correlation = null;
        Mat heatmapCopy = null;

        try {
            // Convert raw image data directly to OpenCV Mat
            decodedFrame = pixelsToMat(rawImageData, width, height, bpp);

            if (decodedFrame.empty()) {
                return results;
            }

            // Quick ROI validation
            int clipX = upperLeft.getX();
            int clipY = upperLeft.getY();
            int clipW = lowerRight.getX() - upperLeft.getX();
            int clipH = lowerRight.getY() - upperLeft.getY();

            if (clipW <= 0 || clipH <= 0) {
                return results;
            }

            // Convert to grayscale
            decodedGrey = new Mat();
            Imgproc.cvtColor(decodedFrame, decodedGrey, Imgproc.COLOR_BGR2GRAY);
            decodedFrame.release();
            decodedFrame = null;

            // Load Mono reference with cache
            template = fetchGreyscaleSprite(spritePath);
            if (template.empty()) {
                return results;
            }

            // Validations
            if (clipX + clipW > decodedGrey.cols() || clipY + clipH > decodedGrey.rows()) {
                return results;
            }

            // Create ROI
            Rect roi = new Rect(clipX, clipY, clipW, clipH);
            imageROI = new Mat(decodedGrey, roi);

            int resultCols = imageROI.cols() - template.cols() + 1;
            int resultRows = imageROI.rows() - template.rows() + 1;
            if (resultCols <= 0 || resultRows <= 0) {
                return results;
            }

            // Template matching
            correlation = new Mat(resultRows, resultCols, CvType.CV_32FC1);
            Imgproc.matchTemplate(imageROI, template, correlation, Imgproc.TM_CCOEFF_NORMED);

            // Optimized search for multiple matches
            double normThreshold = threshold / 100.0;
            heatmapCopy = correlation.clone();
            int sprW = template.cols();
            int sprH = template.rows();

            // Pre-calculate for optimization
            int halfSpriteW = sprW / 2;
            int halfSpriteH = sprH / 2;

            while (results.size() < maxHits || maxHits <= 0) {
                Core.MinMaxLocResult mmr = Core.minMaxLoc(heatmapCopy);
                double score = mmr.maxVal;

                if (score < normThreshold) {
                    break;
                }

                Point bestPos = mmr.maxLoc;
                double centerX = bestPos.x + roi.x + halfSpriteW;
                double centerY = bestPos.y + roi.y + halfSpriteH;

                results.add(new ImageSearchResultData(true,
                        new PointData((int) centerX, (int) centerY), score * 100.0));

                // Optimized suppression
                int supX = Math.max(0, (int) bestPos.x - halfSpriteW);
                int supY = Math.max(0, (int) bestPos.y - halfSpriteH);
                int supW = Math.min(sprW, heatmapCopy.cols() - supX);
                int supH = Math.min(sprH, heatmapCopy.rows() - supY);

                if (supW > 0 && supH > 0) {
                    Rect suppressRect = new Rect(supX, supY, supW, supH);
                    Mat suppressedRegion = new Mat(heatmapCopy, suppressRect);
                    suppressedRegion.setTo(new org.opencv.core.Scalar(0));
                    suppressedRegion.release();
                }
            }

        } catch (Exception e) {
            log.error(tagged("Exception during multi-hit mono correlation"), e);
        } finally {
            // Explicit memory release
            if (decodedFrame != null)
                decodedFrame.release();
            if (template != null)
                template.release();
            if (imageROI != null)
                imageROI.release();
            if (correlation != null)
                correlation.release();
            if (heatmapCopy != null)
                heatmapCopy.release();
        }

        return results;
    }

    /**
     * Multiple template search using raw image data.
     */
    private static List<ImageSearchResultData> scanCaptureMulti(byte[] rawImageData, int width,
            int height, int bpp,
            String spritePath, PointData upperLeft, PointData lowerRight,
            double threshold, int maxHits) {

        List<ImageSearchResultData> results = new ArrayList<>();
        Mat decodedFrame = null;
        Mat template = null;
        Mat imageROI = null;
        Mat correlation = null;
        Mat heatmapCopy = null;

        try {
            // Convert raw image data directly to OpenCV Mat
            decodedFrame = pixelsToMat(rawImageData, width, height, bpp);

            if (decodedFrame.empty()) {
                return results;
            }

            // Quick ROI validation
            int clipX = upperLeft.getX();
            int clipY = upperLeft.getY();
            int clipW = lowerRight.getX() - upperLeft.getX();
            int clipH = lowerRight.getY() - upperLeft.getY();

            if (clipW <= 0 || clipH <= 0) {
                return results;
            }

            // Load template with cache
            template = fetchSprite(spritePath);
            if (template.empty()) {
                return results;
            }

            // Validations
            if (clipX + clipW > decodedFrame.cols() || clipY + clipH > decodedFrame.rows()) {
                return results;
            }

            // Create ROI
            Rect roi = new Rect(clipX, clipY, clipW, clipH);
            imageROI = new Mat(decodedFrame, roi);

            int resultCols = imageROI.cols() - template.cols() + 1;
            int resultRows = imageROI.rows() - template.rows() + 1;
            if (resultCols <= 0 || resultRows <= 0) {
                return results;
            }

            // Template matching
            correlation = new Mat(resultRows, resultCols, CvType.CV_32FC1);
            Imgproc.matchTemplate(imageROI, template, correlation, Imgproc.TM_CCOEFF_NORMED);

            // Optimized search for multiple matches
            double normThreshold = threshold / 100.0;
            heatmapCopy = correlation.clone();
            int sprW = template.cols();
            int sprH = template.rows();

            // Pre-calculate for optimization
            int halfSpriteW = sprW / 2;
            int halfSpriteH = sprH / 2;

            while (results.size() < maxHits || maxHits <= 0) {
                Core.MinMaxLocResult mmr = Core.minMaxLoc(heatmapCopy);
                double score = mmr.maxVal;

                if (score < normThreshold) {
                    break;
                }

                Point bestPos = mmr.maxLoc;
                double centerX = bestPos.x + roi.x + halfSpriteW;
                double centerY = bestPos.y + roi.y + halfSpriteH;

                results.add(new ImageSearchResultData(true,
                        new PointData((int) centerX, (int) centerY), score * 100.0));

                // Optimized suppression
                int supX = Math.max(0, (int) bestPos.x - halfSpriteW);
                int supY = Math.max(0, (int) bestPos.y - halfSpriteH);
                int supW = Math.min(sprW, heatmapCopy.cols() - supX);
                int supH = Math.min(sprH, heatmapCopy.rows() - supY);

                if (supW > 0 && supH > 0) {
                    Rect suppressRect = new Rect(supX, supY, supW, supH);
                    Mat suppressedRegion = new Mat(heatmapCopy, suppressRect);
                    suppressedRegion.setTo(new org.opencv.core.Scalar(0));
                    suppressedRegion.release();
                }
            }

        } catch (Exception e) {
            log.error(tagged("Exception during multi-hit correlation with raw data"), e);
        } finally {
            // Explicit memory release
            if (decodedFrame != null)
                decodedFrame.release();
            if (template != null)
                template.release();
            if (imageROI != null)
                imageROI.release();
            if (correlation != null)
                correlation.release();
            if (heatmapCopy != null)
                heatmapCopy.release();
        }

        return results;
    }

    /** Schedules background decoding for a single sprite path. */
    public static void warmupReference(String spritePath) {
        matchPool.submit(() -> fetchSprite(spritePath));
    }

    /** Releases all cached sprites and resets the ready flag. */
    public static void purgeCache() {
        spriteStore.values().forEach(Mat::release);
        spriteStore.clear();
        greyscaleSpriteStore.values().forEach(Mat::release);
        greyscaleSpriteStore.clear();
        rawBytesStore.clear();
        storeReady = false;
    }

    /** Convenience overload accepting a {@link TemplatesEnum} enum value. */
    public static ImageSearchResultData locatePattern(byte[] image, TemplatesEnum vt,
            PointData tl, PointData br, double threshold) {
        return locatePattern(image, vt.getTemplate(), tl, br, threshold);
    }

    /** Multi-hit convenience overload accepting a {@link TemplatesEnum}. */
    public static List<ImageSearchResultData> locateAllPatterns(byte[] image, TemplatesEnum vt,
            PointData tl, PointData br, double threshold, int limit) {
        return locateAllPatterns(image, vt.getTemplate(), tl, br, threshold, limit);
    }

    /** Greyscale convenience overload accepting a {@link TemplatesEnum}. */
    public static ImageSearchResultData locatePatternMono(byte[] image, TemplatesEnum vt,
            PointData tl, PointData br, double threshold) {
        return locatePatternMono(image, vt.getTemplate(), tl, br, threshold);
    }

    /** Greyscale multi-hit convenience accepting a {@link TemplatesEnum}. */
    public static List<ImageSearchResultData> locateAllPatternsMono(byte[] image, TemplatesEnum vt,
            PointData tl, PointData br, double threshold, int limit) {
        return locateAllPatternsMono(image, vt.getTemplate(), tl, br, threshold, limit);
    }

    /** Asynchronous multi-hit convenience accepting a {@link TemplatesEnum}. */
    public static CompletableFuture<List<ImageSearchResultData>> locateAllPatternsAsync(
            byte[] image, TemplatesEnum vt, PointData tl,
            PointData br, double threshold, int limit) {
        return locateAllPatternsAsync(image, vt.getTemplate(), tl, br, threshold, limit);
    }

    /** Returns {@code true} once all sprites have been pre-loaded. */
    public static boolean isStoreReady() {
        return storeReady;
    }

    /** Returns a human-readable summary of current cache occupancy. */
    public static String cacheStatistics() {
        return String.format("Sprites loaded: %d / %d, raw-bytes entries: %d",
                spriteStore.size(), TemplatesEnum.values().length, rawBytesStore.size());
    }

    /**
     * Extracts a bundled native library from the classpath into
     * {@code lib/opencv/} and loads it via {@link System#load}.
     */
    public static void extractAndLoadNative(String resourcePath) throws IOException {
        String[] segments = resourcePath.split("/");
        String fileName = segments[segments.length - 1];

        File targetDir = new File("lib/opencv");
        if (!targetDir.exists()) targetDir.mkdirs();

        File dest = new File(targetDir, fileName);

        try (InputStream src = OpenCvPatternLocator.class.getResourceAsStream(resourcePath);
             OutputStream sink = new FileOutputStream(dest)) {
            if (src == null) {
                log.error(tagged("Bundled native not found: " + resourcePath));
                throw new IOException("Bundled native not found: " + resourcePath);
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = src.read(buf)) != -1) sink.write(buf, 0, n);
        } catch (IOException ex) {
            log.error(tagged("Native extraction failed: " + ex.getMessage()));
            throw ex;
        }

        System.load(dest.getAbsolutePath());
        log.info(tagged("Native library loaded from: " + dest.getPath()));
    }
}

