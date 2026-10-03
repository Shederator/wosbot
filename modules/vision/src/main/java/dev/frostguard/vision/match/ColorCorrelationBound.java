package dev.frostguard.vision.match;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.imgproc.Imgproc;

/** Optional negative-only prefilter for unmasked, fixed-scale color CCOEFF_NORMED. */
final class ColorCorrelationBound {
    // Empirical floating-point slack, not a proof of OpenCV's numerical error bound.
    private static final double PEAK_SLACK = 0.005;
    private static final double REJECTION_SLACK = 0.03;
    private static final double MINIMUM_VARIANCE = 100.0;

    private ColorCorrelationBound() { }

    static boolean rejects(Mat image, Mat template, double threshold) {
        if (image.type() != CvType.CV_8UC3 || template.type() != CvType.CV_8UC3
                || template.empty() || image.cols() < template.cols() || image.rows() < template.rows()
                || !Double.isFinite(threshold) || threshold < 0.5 || threshold > 0.99) return false;
        MatOfDouble mean = new MatOfDouble(), deviation = new MatOfDouble();
        Mat projection = new Mat(1, 3, CvType.CV_32F);
        Mat imageFloat = new Mat(), templateFloat = new Mat();
        Mat imageSum = new Mat(), templateSum = new Mat(), heatmap = new Mat();
        try {
            Core.meanStdDev(template, mean, deviation);
            double totalVariance = 0;
            for (double value : deviation.toArray()) totalVariance += value * value;
            if (!Double.isFinite(totalVariance) || totalVariance < MINIMUM_VARIANCE) return false;

            // Float sums preserve the linear projection exactly for 8-bit integer pixels.
            // Rounded 8-bit grayscale would not preserve the bound.
            projection.put(0, 0, new float[]{1, 1, 1});
            template.convertTo(templateFloat, CvType.CV_32F);
            Core.transform(templateFloat, templateSum, projection);
            Core.meanStdDev(templateSum, mean, deviation);
            double sumDeviation = deviation.toArray()[0];
            double sumVariance = sumDeviation * sumDeviation;
            if (!Double.isFinite(sumVariance) || sumVariance < MINIMUM_VARIANCE) return false;
            double fraction = sumVariance / (3 * totalVariance);
            if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) return false;
            // Even perfect rejection of the projected channel cannot help this template.
            if (Math.sqrt(1 - fraction) >= threshold - REJECTION_SLACK) return false;

            image.convertTo(imageFloat, CvType.CV_32F);
            Core.transform(imageFloat, imageSum, projection);
            Core.meanStdDev(imageSum, mean, deviation);
            double imageDeviation = deviation.toArray()[0];
            if (!Double.isFinite(imageDeviation) || imageDeviation * imageDeviation < MINIMUM_VARIANCE) return false;
            Imgproc.matchTemplate(imageSum, templateSum, heatmap, Imgproc.TM_CCOEFF_NORMED);
            Core.MinMaxLocResult peak = Core.minMaxLoc(heatmap);
            if (!Core.checkRange(heatmap) || !Double.isFinite(peak.maxVal)
                    || peak.maxVal < -1 || peak.maxVal > 1) return false;
            double correlation = Math.min(1, Math.max(0, peak.maxVal) + PEAK_SLACK);
            // Orthogonal sum-channel/complement decomposition, then Cauchy-Schwarz:
            // color NCC <= sqrt(1-w + w*r^2), w = Var(B+G+R)/(3*sum Var(channel)).
            double upperBound = Math.sqrt(1 - fraction + fraction * correlation * correlation);
            return upperBound < threshold - REJECTION_SLACK;
        } catch (RuntimeException unavailable) {
            return false; // The original color matcher remains authoritative.
        } finally {
            mean.release(); deviation.release(); projection.release();
            imageFloat.release(); templateFloat.release(); imageSum.release(); templateSum.release(); heatmap.release();
        }
    }
}
