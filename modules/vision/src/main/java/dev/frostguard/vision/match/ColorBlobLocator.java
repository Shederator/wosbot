package dev.frostguard.vision.match;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;

/** Groups foreground pixels without requiring a separate sprite for each object. */
public final class ColorBlobLocator {
    @FunctionalInterface
    public interface PixelPredicate {
        boolean test(int x, int y, int rgb);
    }
    private ColorBlobLocator() { }

    public static List<AreaData> locate(RawImageData frame, AreaData region,
            IntPredicate foreground, int minimumArea) {
        return locate(frame, region, (x, y, rgb) -> foreground.test(rgb), minimumArea);
    }

    public static List<AreaData> locate(RawImageData frame, AreaData region,
            PixelPredicate foreground, int minimumArea) {
        if (frame.getBpp() != 32 || frame.getData().length < frame.getWidth() * frame.getHeight() * 4) {
            throw new IllegalArgumentException("A complete RGBA capture is required");
        }
        int x1 = Math.max(0, region.topLeft().getX());
        int y1 = Math.max(0, region.topLeft().getY());
        int x2 = Math.min(frame.getWidth(), region.bottomRight().getX());
        int y2 = Math.min(frame.getHeight(), region.bottomRight().getY());
        int width = x2 - x1, height = y2 - y1;
        if (width <= 0 || height <= 0) return List.of();
        byte[] pixels = frame.getData(), maskPixels = new byte[width * height];
        for (int y = y1, output = 0; y < y2; y++) {
            for (int x = x1; x < x2; x++, output++) {
                int in = (y * frame.getWidth() + x) * 4;
                int rgb = ((pixels[in] & 255) << 16) | ((pixels[in + 1] & 255) << 8) | (pixels[in + 2] & 255);
                if (foreground.test(x, y, rgb)) maskPixels[output] = (byte) 255;
            }
        }
        Mat mask = new Mat(height, width, CvType.CV_8UC1), hierarchy = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(7, 5));
        List<MatOfPoint> contours = new ArrayList<>();
        try {
            mask.put(0, 0, maskPixels);
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel);
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            List<AreaData> result = new ArrayList<>();
            for (MatOfPoint contour : contours) {
                if (Imgproc.contourArea(contour) < minimumArea) continue;
                Rect bounds = Imgproc.boundingRect(contour);
                if (bounds.width < 12 || bounds.height < 8) continue;
                result.add(new AreaData(new PointData(bounds.x + x1, bounds.y + y1),
                        new PointData(bounds.x + x1 + bounds.width, bounds.y + y1 + bounds.height)));
            }
            return List.copyOf(result);
        } finally {
            contours.forEach(Mat::release);
            kernel.release();
            hierarchy.release();
            mask.release();
        }
    }
}
