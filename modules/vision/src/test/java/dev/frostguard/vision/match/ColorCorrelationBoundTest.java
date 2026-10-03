package dev.frostguard.vision.match;

import static org.junit.jupiter.api.Assertions.*;
import dev.frostguard.api.configs.TemplatesEnum;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

class ColorCorrelationBoundTest {
    @BeforeAll static void nativeLibrary() throws java.io.IOException { OpenCvPatternLocator.loadNativeLibrary(); }

    @Test
    void noiseMixedGamePatchesStraddleTheActualDecisionThreshold() {
        Random random = new Random(33198);
        for (var id : new TemplatesEnum[]{TemplatesEnum.GAME_HOME_RECONNECT,
                TemplatesEnum.GAME_START_DOWNLOAD_NOW, TemplatesEnum.GAME_START_MANDATORY_UPDATE_TITLE}) {
            Mat template = OpenCvPatternLocator.getReferenceMatrix(id.getTemplate());
            Mat independent = noise(random, template.rows(), template.cols());
            Mat image = noise(random, 200, 300), patch = new Mat();
            Mat destination = new Mat(image, new Rect(10, 20, template.cols(), template.rows()));
            try {
                for (double target : new double[]{.849, .851}) {
                    double low = 0, high = 1, score = 0;
                    for (int step = 0; step < 18; step++) {
                        double weight = (low + high) / 2;
                        Core.addWeighted(template, weight, independent, 1 - weight, 0, patch);
                        patch.copyTo(destination);
                        score = originalPeak(image, template);
                        if (score < target) low = weight; else high = weight;
                    }
                    assertEquals(target, score, .0005, "fixture must actually exercise the threshold boundary");
                    // Even the near-boundary negative must fall back, preserving numeric slack.
                    assertFalse(ColorCorrelationBound.rejects(image, template, .85), id.name());
                }
            } finally { destination.release(); independent.release(); image.release(); patch.release(); }
        }
    }

    @Test
    void nearlyFlatLocalPatchesInsideNoisyImagesRetainOraclePositives() {
        Random random = new Random(13466);
        int positives = 0;
        for (var id : new TemplatesEnum[]{TemplatesEnum.GAME_HOME_RECONNECT,
                TemplatesEnum.GAME_START_DOWNLOAD_NOW, TemplatesEnum.GAME_START_MANDATORY_UPDATE_TITLE}) {
            Mat template = OpenCvPatternLocator.getReferenceMatrix(id.getTemplate());
            Mat image = noise(random, 200, 300), patch = new Mat();
            Mat destination = new Mat(image, new Rect(10, 20, template.cols(), template.rows()));
            try {
                for (double contrast : new double[]{.005, .01, .02, .05}) {
                    for (double offset : new double[]{0, 64, 128, 248}) {
                        template.convertTo(patch, CvType.CV_8UC3, contrast, offset);
                        patch.copyTo(destination);
                        if (originalPeak(image, template) >= .85) {
                            positives++;
                            assertFalse(ColorCorrelationBound.rejects(image, template, .85),
                                    id.name() + " contrast=" + contrast + " offset=" + offset);
                        }
                    }
                }
            } finally { destination.release(); image.release(); patch.release(); }
        }
        assertTrue(positives >= 12, "near-flat oracle-positive cases must actually be exercised");
    }

    @Test
    void maskedTemplatesKeepTheOriginalMatcherAndScore() {
        String path = "/templates/island/likeButton.png";
        Mat template = OpenCvPatternLocator.getReferenceMatrix(path);
        Mat image = noise(new Random(17), 200, 240);
        Mat destination = new Mat(image, new Rect(30, 40, template.cols(), template.rows()));
        Mat rgba = new Mat();
        try {
            template.copyTo(destination);
            Imgproc.cvtColor(image, rgba, Imgproc.COLOR_BGR2RGBA);
            byte[] pixels = new byte[(int) (rgba.total() * rgba.channels())];
            rgba.get(0, 0, pixels);
            var raw = dev.frostguard.api.domain.RawImageData.capture(pixels, 240, 200, 32);
            var tl = new dev.frostguard.api.domain.PointData(0, 0);
            var br = new dev.frostguard.api.domain.PointData(240, 200);
            var baseline = OpenCvPatternLocator.locatePattern(raw, path, tl, br, 85);
            var optimized = OpenCvPatternLocator.locatePatternWithProjectionRejection(raw, path, tl, br, 85);
            assertTrue(baseline.isFound());
            assertEquals(baseline.isFound(), optimized.isFound());
            assertEquals(baseline.getPoint(), optimized.getPoint());
            assertEquals(baseline.getConfidence(), optimized.getConfidence());
        } finally { destination.release(); image.release(); rgba.release(); }
    }

    @Test
    void originalColorOracleAlwaysWinsForRandomAndLowContrastPatches() {
        Random random = new Random(73491);
        int positives = 0, rejected = 0;
        for (int example = 0; example < 80; example++) {
            Mat template = noise(random, 23, 19);
            Mat image = noise(random, 69, 83);
            Mat patch = new Mat(), destination = new Mat(image, new Rect(example % 3 == 0 ? 0 : 40,
                    example % 2 == 0 ? 0 : 35, template.cols(), template.rows()));
            try {
                double contrast = new double[]{0.01, 0.02, 0.1, 0.4, 1}[example % 5];
                template.convertTo(patch, CvType.CV_8UC3, contrast, (1 - contrast) * 240);
                if (example % 4 != 0) patch.copyTo(destination);
                double score = originalPeak(image, template);
                for (double threshold : new double[]{0.5, 0.75, 0.85, 0.95}) {
                    boolean excluded = ColorCorrelationBound.rejects(image, template, threshold);
                    if (score >= threshold) {
                        positives++;
                        assertFalse(excluded, "Original color match lost: score=" + score + " threshold=" + threshold);
                    }
                    if (excluded) rejected++;
                }
            } finally { destination.release(); patch.release(); template.release(); image.release(); }
        }
        assertTrue(positives > 100, "exercise actual oracle-positive decisions");
        assertTrue(rejected > 0, "exercise the optimized negative path");
    }

    @Test
    void retainedGameTemplatesMatchAtEdgesAndWithLowContrastWithoutFalseRejection() {
        Random random = new Random(9744);
        for (var id : new TemplatesEnum[]{TemplatesEnum.GAME_HOME_RECONNECT,
                TemplatesEnum.GAME_START_DOWNLOAD_NOW, TemplatesEnum.GAME_START_MANDATORY_UPDATE_TITLE}) {
            Mat template = OpenCvPatternLocator.getReferenceMatrix(id.getTemplate()); // borrowed, never release
            for (int placement = 0; placement < 4; placement++) {
                Mat image = noise(random, 1280, 720);
                Mat destination = new Mat(image, new Rect(placement % 2 == 0 ? 0 : 720 - template.cols(),
                        placement < 2 ? 0 : 1280 - template.rows(), template.cols(), template.rows()));
                Mat patch = new Mat();
                try {
                    template.convertTo(patch, CvType.CV_8UC3, placement == 3 ? 0.02 : 1, placement == 3 ? 240 : 0);
                    patch.copyTo(destination);
                    double score = originalPeak(image, template);
                    assertTrue(score >= .85, "synthetic oracle positive must be meaningful");
                    assertFalse(ColorCorrelationBound.rejects(image, template, .85), id.name());
                } finally { destination.release(); patch.release(); image.release(); }
            }
        }
    }

    @Test
    void degenerateAndUnsupportedInputsFallBack() {
        Mat flat = new Mat(80, 90, CvType.CV_8UC3, new Scalar(128, 128, 128));
        Mat constant = new Mat(12, 13, CvType.CV_8UC3, new Scalar(150, 150, 150));
        Mat noise = noise(new Random(41), 12, 13);
        Mat mono = new Mat(12, 13, CvType.CV_8UC1, new Scalar(10));
        try {
            assertFalse(ColorCorrelationBound.rejects(flat, constant, .85));
            assertFalse(ColorCorrelationBound.rejects(flat, noise, .85));
            assertFalse(ColorCorrelationBound.rejects(flat, mono, .85));
            assertFalse(ColorCorrelationBound.rejects(noise, flat, .85));
            assertFalse(ColorCorrelationBound.rejects(flat, noise, Double.NaN));
        } finally { flat.release(); constant.release(); noise.release(); mono.release(); }
    }

    private static Mat noise(Random random, int height, int width) {
        byte[] pixels = new byte[height * width * 3];
        random.nextBytes(pixels);
        Mat result = new Mat(height, width, CvType.CV_8UC3);
        result.put(0, 0, pixels);
        return result;
    }

    private static double originalPeak(Mat image, Mat template) {
        Mat heatmap = new Mat();
        try {
            Imgproc.matchTemplate(image, template, heatmap, Imgproc.TM_CCOEFF_NORMED);
            return Core.minMaxLoc(heatmap).maxVal;
        } finally { heatmap.release(); }
    }
}
