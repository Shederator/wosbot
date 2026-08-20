package dev.frostguard.tasks.events;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * Generic "unknown fish" blob detection for the Fishing Tournament minigame.
 *
 * <p><b>Why this exists:</b> {@link FishingMinigameRoutine} can only
 * recognize the 4 species it has hand-calibrated templates for
 * (pufferfish/redfish/small/stripe). Every OTHER species swimming past is
 * invisible to it. The operator's plan (2026-08-06): instead of hand-calibrating a
 * template for all ~140 catalogued species up front, let every ordinary
 * dive automatically notice and log whatever it doesn't recognize -
 * cropped image + rough speed - so the template library grows on its own
 * across dives instead of needing a dedicated calibration pass per species.
 *
 * <p><b>Approach:</b> background-color subtraction, not template matching
 * (there is no template for an unknown species by definition). The
 * background is a fairly consistent blue gradient; anything that deviates
 * from that hue range, above a minimum blob size, in the playable area
 * (below the HUD), is treated as a candidate creature. Validated in Python
 * against real captured frames from tonight's session before being ported
 * here (see scratchpad/fish_detect/ if that prototype is still around) -
 * 8/8 fish correctly boxed on one frame, most-but-not-all on a busier one.
 *
 * <p><b>Known, disclosed limitation:</b> a few silvery-gray fish whose body
 * color sits very close to the water's own blue were under-detected (only
 * their eye-dot registered, not the full body) even after widening the hue
 * range once. That is a real gap, not fixed here - color-only segmentation
 * has a real ceiling against camouflaged sprites. It was deliberately not
 * chased further with more threshold tuning tonight: by design's own
 * "grows over time" design, a species only needs to be cleanly caught in
 * SOME frames across many dives, not every single frame it appears in, so
 * this ships as a genuinely useful v1 rather than blocking on a harder
 * edge-detection-combined approach that would need more live iteration.
 */
public class FishSpeciesDiscovery {

    // Playable area starts below the HUD (score/depth/catch/shield cards).
    // Only the top-LEFT stat-card column needs excluding - the rest of the
    // top strip is open water even in the topmost frames.
    private static final int HUD_EXCLUDE_X = 175;
    private static final int HUD_EXCLUDE_Y = 215;

    // HSV background band, widened once already after live testing (see
    // class doc) to also catch duller olive/gray fish.
    private static final Scalar BG_LOWER = new Scalar(90, 25, 15);
    private static final Scalar BG_UPPER = new Scalar(135, 255, 255);

    private static final double MIN_BLOB_AREA = 100;
    private static final double MAX_BLOB_AREA = 25000;
    private static final double MAX_ASPECT = 6.0;
    private static final double MIN_ASPECT = 0.12;

    // Radius around the hook's own last-known position to exclude - the
    // hook itself (and its glow) is not a fish and would otherwise register
    // as a permanent, wrongly-repeated "sighting" every scan.
    private static final int HOOK_EXCLUSION_RADIUS = 45;

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    public record Candidate(int x, int y, int w, int h, double area) {}

    /**
     * Finds candidate unknown-creature blobs in one frame. Does NOT check
     * them against the 4 known templates - the caller
     * ({@link FishingMinigameRoutine}) already tracks those separately each
     * tick, so it should discard any candidate whose box overlaps a
     * currently-tracked known fish before treating the rest as "unknown."
     */
    public static List<Candidate> findCandidates(RawImageData frame, Integer hookX, Integer hookY) {
        List<Candidate> out = new ArrayList<>();
        Mat bgr = null, hsv = null, bgMask = null, fgMask = null, kernel = null;
        try {
            bgr = OpenCvPatternLocator.decodePixelsToMat(
                    frame.getData(), frame.getWidth(), frame.getHeight(), frame.getBpp());
            if (bgr == null || bgr.empty()) {
                return out;
            }

            hsv = new Mat();
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV);

            bgMask = new Mat();
            Core.inRange(hsv, BG_LOWER, BG_UPPER, bgMask);

            fgMask = new Mat();
            Core.bitwise_not(bgMask, fgMask);

            // Zero out the HUD corner.
            Imgproc.rectangle(fgMask, new Point(0, 0), new Point(HUD_EXCLUDE_X, HUD_EXCLUDE_Y),
                    new Scalar(0), -1);

            // Zero out a radius around the hook so it never counts as a "fish."
            if (hookX != null && hookY != null) {
                Imgproc.circle(fgMask, new Point(hookX, hookY), HOOK_EXCLUSION_RADIUS, new Scalar(0), -1);
            }

            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
            Imgproc.morphologyEx(fgMask, fgMask, Imgproc.MORPH_OPEN, kernel, new Point(-1, -1), 1);
            Imgproc.morphologyEx(fgMask, fgMask, Imgproc.MORPH_CLOSE, kernel, new Point(-1, -1), 3);

            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(fgMask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            hierarchy.release();

            for (MatOfPoint c : contours) {
                Rect r = Imgproc.boundingRect(c);
                double area = Imgproc.contourArea(c);
                c.release();
                if (area < MIN_BLOB_AREA || area > MAX_BLOB_AREA) {
                    continue;
                }
                double aspect = (double) r.width / Math.max(1, r.height);
                if (aspect > MAX_ASPECT || aspect < MIN_ASPECT) {
                    continue;
                }
                out.add(new Candidate(r.x, r.y, r.width, r.height, area));
            }
        } finally {
            if (bgr != null) bgr.release();
            if (hsv != null) hsv.release();
            if (bgMask != null) bgMask.release();
            if (fgMask != null) fgMask.release();
            if (kernel != null) kernel.release();
        }
        return out;
    }

    /**
     * Crops one candidate out of the frame (with a small margin) and saves
     * it plus a manifest row, for the future human-review page to consume.
     * Content-dedup is intentionally NOT done here - visual clustering
     * across sightings is a separate, harder step; this just logs every
     * sighting honestly and lets that later step group them.
     */
    public static void saveDiscovery(Path baseDir, RawImageData frame, Candidate c, String channelLabel) {
        try {
            BufferedImage full = toBufferedImage(frame);
            int margin = 6;
            int x0 = Math.max(0, c.x() - margin);
            int y0 = Math.max(0, c.y() - margin);
            int x1 = Math.min(full.getWidth(), c.x() + c.w() + margin);
            int y1 = Math.min(full.getHeight(), c.y() + c.h() + margin);
            if (x1 <= x0 || y1 <= y0) {
                return;
            }
            BufferedImage crop = full.getSubimage(x0, y0, x1 - x0, y1 - y0);

            Path discoveryDir = baseDir.resolve("discovered");
            Files.createDirectories(discoveryDir);
            String stamp = LocalDateTime.now(ZoneOffset.UTC).format(FILE_STAMP);
            Path shot = discoveryDir.resolve("sighting-" + stamp + ".png");
            ImageIO.write(crop, "png", shot.toFile());

            String row = String.format(
                    "{\"capturedAt\":\"%sZ\",\"channel\":\"%s\",\"frame\":\"%s\",\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d,\"area\":%.0f}",
                    LocalDateTime.now(ZoneOffset.UTC), channelLabel,
                    baseDir.relativize(shot).toString().replace('\\', '/'),
                    c.x(), c.y(), c.w(), c.h(), c.area());
            Files.write(baseDir.resolve("discovery_log.jsonl"),
                    (row + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Best-effort logging feature - never worth failing the fishing
            // task itself over a missed discovery crop.
        }
    }

    private static BufferedImage toBufferedImage(RawImageData capture) {
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

    private static int decodePixel(byte[] data, int bpp, int stride, int px, int py) {
        if (bpp == 16) {
            int off = (py * stride + px) * 2;
            int packed = ((data[off + 1] & 0xFF) << 8) | (data[off] & 0xFF);
            int r = ((packed >> 11) & 0x1F) << 3;
            int g = ((packed >> 5) & 0x3F) << 2;
            int b = (packed & 0x1F) << 3;
            return (r << 16) | (g << 8) | b;
        }
        int off = (py * stride + px) * 4;
        int r = data[off] & 0xFF;
        int g = data[off + 1] & 0xFF;
        int b = data[off + 2] & 0xFF;
        return (r << 16) | (g << 8) | b;
    }

    private static Path baseDir() {
        return Paths.get(System.getProperty("user.dir"), "telemetry", "fishing");
    }

    /** Convenience overload using the standard telemetry/fishing/ base dir. */
    public static void saveDiscovery(RawImageData frame, Candidate c, String channelLabel) {
        saveDiscovery(baseDir(), frame, c, channelLabel);
    }
}
