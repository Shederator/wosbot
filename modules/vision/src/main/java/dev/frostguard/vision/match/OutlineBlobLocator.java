package dev.frostguard.vision.match;

import java.util.ArrayList;
import java.util.List;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.RawImageData;

/** Colour-independent outline groups; these prove occupancy, not object identity. */
public final class OutlineBlobLocator {
    private static final int SCALE = 2;
    private OutlineBlobLocator() { }

    public static List<AreaData> locate(RawImageData frame, List<AreaData> excluded, int minimumArea) {
        if (frame.getBpp() != 32 || frame.getData().length < (long)frame.getWidth() * frame.getHeight() * 4
                || minimumArea < 1) throw new IllegalArgumentException("Complete RGBA frame and positive area required");
        Mat source = new Mat(frame.getHeight(), frame.getWidth(), CvType.CV_8UC4);
        Mat gray = new Mat(), edges = new Mat(), hierarchy = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));
        var contours = new ArrayList<MatOfPoint>();
        try {
            source.put(0, 0, frame.getData());
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY);
            Imgproc.resize(gray, gray, new Size((frame.getWidth() + 1) / SCALE,
                    (frame.getHeight() + 1) / SCALE), 0, 0, Imgproc.INTER_AREA);
            Imgproc.GaussianBlur(gray, gray, new Size(3, 3), 0);
            Imgproc.Canny(gray, edges, 20, 50);
            erase(edges, excluded);
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel);
            erase(edges, excluded);
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            var result = new ArrayList<AreaData>();
            for (MatOfPoint contour : contours) {
                Rect box = Imgproc.boundingRect(contour);
                if (Imgproc.contourArea(contour) * SCALE * SCALE < minimumArea
                        || box.width * SCALE < 12 || box.height * SCALE < 8) continue;
                result.add(AreaData.of(box.x * SCALE, box.y * SCALE,
                        Math.min(frame.getWidth(), (box.x + box.width) * SCALE),
                        Math.min(frame.getHeight(), (box.y + box.height) * SCALE)));
            }
            return List.copyOf(result);
        } finally {
            contours.forEach(Mat::release);
            source.release(); gray.release(); edges.release(); hierarchy.release(); kernel.release();
        }
    }

    private static void erase(Mat edges, List<AreaData> excluded) {
        for (AreaData area : excluded) {
            int x1 = Math.max(0, area.topLeft().getX() / SCALE), y1 = Math.max(0, area.topLeft().getY() / SCALE);
            int x2 = Math.min(edges.cols(), (area.bottomRight().getX() + SCALE - 1) / SCALE);
            int y2 = Math.min(edges.rows(), (area.bottomRight().getY() + SCALE - 1) / SCALE);
            if (x2 <= x1 || y2 <= y1) continue;
            Imgproc.rectangle(edges, new Point(x1, y1), new Point(x2 - 1, y2 - 1), new Scalar(0), -1);
        }
    }
}
