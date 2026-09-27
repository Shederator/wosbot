package dev.frostguard.vision.detection;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Finds close-cross shapes with a scale-tolerant OpenCV template search. */
public final class CloseCrossDetector {

    private static final String CROSS_TEMPLATE = "/templates/common/closeCross.png";
    private static final double MATCH_THRESHOLD = 55.0;
    private static final double[] SCALES = {0.60, 0.70, 0.80, 0.90, 1.00, 1.10, 1.20};
    private static final int MAX_MATCHES_PER_SCALE = 12;
    private static final int MAX_RESULTS = 32;

    private CloseCrossDetector() {
    }

    /** Finds controls in the right half of the image, where these game controls normally appear. */
    public static List<Detection> locate(BufferedImage frame) {
        int left = frame.getWidth() / 2;
        return locate(frame, AreaData.of(left, 0, frame.getWidth() - 1, frame.getHeight() - 1));
    }

    /** Finds controls in the right half of a raw emulator capture. */
    public static List<Detection> locate(RawImageData frame) {
        if (frame == null) {
            throw new IllegalArgumentException("Raw close-cross frame must contain valid pixel data.");
        }
        int left = frame.getWidth() / 2;
        return locate(frame, AreaData.of(left, 0, frame.getWidth() - 1, frame.getHeight() - 1));
    }

    /** Finds controls inside an inclusive, image-relative search area. */
    public static List<Detection> locate(BufferedImage frame, AreaData searchArea) {
        AreaBounds bounds = clipArea(searchArea, frame.getWidth(), frame.getHeight());
        if (bounds == null) {
            return List.of();
        }
        loadOpenCv();
        Mat gray = toGray(frame, bounds);
        try {
            return search(gray, bounds.left(), bounds.top());
        } finally {
            gray.release();
        }
    }

    /** Finds controls directly from an emulator capture without an intermediate image conversion. */
    public static List<Detection> locate(RawImageData frame, AreaData searchArea) {
        if (frame == null) {
            throw new IllegalArgumentException("Raw close-cross frame must contain valid pixel data.");
        }
        int bytesPerPixel = switch (frame.getBpp()) {
            case 2, 16 -> 2;
            case 4, 32 -> 4;
            default -> throw new IllegalArgumentException("Unsupported raw close-cross pixel depth: "
                    + frame.getBpp());
        };
        if (frame.getWidth() <= 0 || frame.getHeight() <= 0 || frame.getData() == null
                || frame.getData().length < Math.multiplyExact(
                        Math.multiplyExact(frame.getWidth(), frame.getHeight()), bytesPerPixel)) {
            throw new IllegalArgumentException("Raw close-cross frame must contain valid pixel data.");
        }
        AreaBounds bounds = clipArea(searchArea, frame.getWidth(), frame.getHeight());
        if (bounds == null) {
            return List.of();
        }
        loadOpenCv();
        Mat gray = toGray(frame, bytesPerPixel, bounds);
        try {
            return search(gray, bounds.left(), bounds.top());
        } finally {
            gray.release();
        }
    }

    /** Returns matches scoring at least 55 percent in an inclusive image-relative area. */
    private static List<Detection> search(Mat roi, int offsetX, int offsetY) {
        MatOfByte encoded = null;
        Mat template = null;
        List<Mat> scaledTemplates = new ArrayList<>();
        List<Detection> candidates = new ArrayList<>();
        try (InputStream stream = CloseCrossDetector.class.getResourceAsStream(CROSS_TEMPLATE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing close-cross template " + CROSS_TEMPLATE);
            }
            encoded = new MatOfByte(stream.readAllBytes());
            template = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE);
            if (template.empty()) {
                throw new IllegalStateException("Could not decode close-cross template " + CROSS_TEMPLATE);
            }
            for (double scale : SCALES) {
                int width = Math.max(8, (int) Math.round(template.cols() * scale));
                int height = Math.max(8, (int) Math.round(template.rows() * scale));
                if (width > roi.cols() || height > roi.rows()) {
                    continue;
                }
                Mat scaled = new Mat();
                Imgproc.resize(template, scaled, new Size(width, height), 0, 0,
                        scale < 1 ? Imgproc.INTER_AREA : Imgproc.INTER_CUBIC);
                scaledTemplates.add(scaled);
                findMatchesAtScale(roi, scaled, width, height, offsetX, offsetY, candidates);
            }
            return suppressDuplicates(candidates);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read close-cross template", ex);
        } finally {
            for (Mat scaled : scaledTemplates) {
                scaled.release();
            }
            if (template != null) template.release();
            if (encoded != null) encoded.release();
        }
    }

    private static void loadOpenCv() {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (IOException ex) {
            throw new IllegalStateException("OpenCV native library could not be loaded", ex);
        }
    }

    private static void findMatchesAtScale(Mat roi, Mat template, int width, int height,
            int offsetX, int offsetY, List<Detection> candidates) {
        Mat scores = new Mat();
        try {
            Imgproc.matchTemplate(roi, template, scores, Imgproc.TM_CCOEFF_NORMED);
            for (int index = 0; index < MAX_MATCHES_PER_SCALE; index++) {
                Core.MinMaxLocResult peak = Core.minMaxLoc(scores);
                double score = peak.maxVal * 100.0;
                if (!Double.isFinite(score) || score < MATCH_THRESHOLD) {
                    break;
                }
                int x = (int) Math.round(peak.maxLoc.x + offsetX);
                int y = (int) Math.round(peak.maxLoc.y + offsetY);
                Detection detection = new Detection(
                        AreaData.of(x, y, x + width - 1, y + height - 1),
                        new PointData(x + width / 2, y + height / 2),
                        score);
                candidates.add(detection);
                Imgproc.rectangle(scores,
                        new org.opencv.core.Point(peak.maxLoc.x - width / 2.0, peak.maxLoc.y - height / 2.0),
                        new org.opencv.core.Point(peak.maxLoc.x + width / 2.0, peak.maxLoc.y + height / 2.0),
                        new Scalar(-1.0), -1);
            }
        } finally {
            scores.release();
        }
    }

    private static Mat toGray(BufferedImage frame, AreaBounds bounds) {
        int width = bounds.width();
        int height = bounds.height();
        byte[] pixels = new byte[Math.multiplyExact(width, height)];
        int index = 0;
        for (int y = bounds.top(); y <= bounds.bottom(); y++) {
            for (int x = bounds.left(); x <= bounds.right(); x++) {
                int rgb = frame.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                pixels[index++] = (byte) ((299 * red + 587 * green + 114 * blue) / 1000);
            }
        }
        Mat gray = new Mat(height, width, CvType.CV_8UC1);
        gray.put(0, 0, pixels);
        return gray;
    }

    private static Mat toGray(RawImageData frame, int bytesPerPixel, AreaBounds bounds) {
        int width = bounds.width();
        int height = bounds.height();
        byte[] raw = frame.getData();
        byte[] pixels = new byte[Math.multiplyExact(width, height)];
        for (int y = bounds.top(), pixel = 0; y <= bounds.bottom(); y++) {
            for (int x = bounds.left(); x <= bounds.right(); x++, pixel++) {
                int source = (y * frame.getWidth() + x) * bytesPerPixel;
                int red;
                int green;
                int blue;
                if (bytesPerPixel == 4) {
                    red = raw[source] & 0xFF;
                    green = raw[source + 1] & 0xFF;
                    blue = raw[source + 2] & 0xFF;
                } else {
                    int packed = ((raw[source + 1] & 0xFF) << 8) | (raw[source] & 0xFF);
                    red = ((packed >> 11) & 0x1F) << 3;
                    green = ((packed >> 5) & 0x3F) << 2;
                    blue = (packed & 0x1F) << 3;
                }
                pixels[pixel] = (byte) ((299 * red + 587 * green + 114 * blue) / 1000);
            }
        }
        Mat gray = new Mat(height, width, CvType.CV_8UC1);
        gray.put(0, 0, pixels);
        return gray;
    }

    private static AreaBounds clipArea(AreaData area, int width, int height) {
        int left = Math.max(0, area.topLeft().getX());
        int top = Math.max(0, area.topLeft().getY());
        int right = Math.min(width - 1, area.bottomRight().getX());
        int bottom = Math.min(height - 1, area.bottomRight().getY());
        return left <= right && top <= bottom
                ? new AreaBounds(left, top, right, bottom)
                : null;
    }

    private static List<Detection> suppressDuplicates(List<Detection> candidates) {
        candidates.sort(Comparator.comparingDouble(Detection::score).reversed());
        List<Detection> unique = new ArrayList<>();
        for (Detection candidate : candidates) {
            PointData center = candidate.center();
            boolean duplicate = unique.stream().anyMatch(existing -> {
                PointData other = existing.center();
                int tolerance = Math.min(candidate.width(), existing.width()) / 2;
                return Math.abs(center.getX() - other.getX()) <= tolerance
                        && Math.abs(center.getY() - other.getY()) <= tolerance;
            });
            if (!duplicate) {
                unique.add(candidate);
                if (unique.size() == MAX_RESULTS) {
                    break;
                }
            }
        }
        return List.copyOf(unique);
    }

    /** Bounds are inclusive; center is a suggested location inside those bounds. */
    public record Detection(AreaData bounds, PointData center, double score) {
        public int width() {
            return bounds.bottomRight().getX() - bounds.topLeft().getX() + 1;
        }

        public int height() {
            return bounds.bottomRight().getY() - bounds.topLeft().getY() + 1;
        }
    }

    private record AreaBounds(int left, int top, int right, int bottom) {
        int width() {
            return right - left + 1;
        }

        int height() {
            return bottom - top + 1;
        }
    }

}
