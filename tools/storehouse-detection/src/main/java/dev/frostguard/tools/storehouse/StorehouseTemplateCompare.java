package dev.frostguard.tools.storehouse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * Prints matchTemplate scores for BGR, grey, dropped-blue, RG-mean, and CLAHE
 * on the four Storehouse templates. Used by {@code detect.sh --compare}.
 */
final class StorehouseTemplateCompare {

    private static final Path TEMPLATE_DIR = Path.of("modules/vision/src/main/resources/templates/storehouse");
    private static final String[] TEMPLATE_FILES = { "chest.png", "chest2.png", "chest3.png", "stamina.png" };

    enum Mode {
        BGR, GRAY, DROP_B, RG_MEAN, CLAHE
    }

    private StorehouseTemplateCompare() {
    }

    static void print(List<Path> images) throws Exception {
        OpenCvPatternLocator.loadNativeLibrary();
        System.out.printf(Locale.ROOT, "%-28s %-8s %8s %8s %8s %8s%n",
                "frame", "mode", "chest", "chest2", "chest3", "stamina");
        for (Path image : images) {
            Mat frameBgr = Imgcodecs.imread(image.toString());
            if (frameBgr.empty()) {
                System.out.println(image.getFileName() + "  unreadable");
                continue;
            }
            for (Mode mode : Mode.values()) {
                Mat frame = preprocess(frameBgr, mode);
                double[] scores = new double[TEMPLATE_FILES.length];
                for (int i = 0; i < TEMPLATE_FILES.length; i++) {
                    Mat templateBgr = Imgcodecs.imread(TEMPLATE_DIR.resolve(TEMPLATE_FILES[i]).toString());
                    Mat template = preprocess(templateBgr, mode);
                    scores[i] = score(frame, template);
                    templateBgr.release();
                    template.release();
                }
                frame.release();
                System.out.printf(Locale.ROOT, "%-28s %-8s %8.2f %8.2f %8.2f %8.2f%n",
                        stripPng(image.getFileName().toString()), mode, scores[0], scores[1], scores[2], scores[3]);
            }
            frameBgr.release();
            System.out.println();
        }
    }

    private static Mat preprocess(Mat bgr, Mode mode) {
        return switch (mode) {
            case BGR -> bgr.clone();
            case GRAY -> {
                Mat grey = new Mat();
                Imgproc.cvtColor(bgr, grey, Imgproc.COLOR_BGR2GRAY);
                yield grey;
            }
            case DROP_B -> {
                List<Mat> channels = new ArrayList<>();
                Core.split(bgr, channels);
                channels.get(0).setTo(new Scalar(0));
                Mat out = new Mat();
                Core.merge(channels, out);
                yield out;
            }
            case RG_MEAN -> {
                List<Mat> channels = new ArrayList<>();
                Core.split(bgr, channels);
                Mat grey = new Mat();
                Core.addWeighted(channels.get(2), 0.5, channels.get(1), 0.5, 0, grey);
                yield grey;
            }
            case CLAHE -> {
                Mat grey = new Mat();
                Imgproc.cvtColor(bgr, grey, Imgproc.COLOR_BGR2GRAY);
                CLAHE clahe = Imgproc.createCLAHE(2.0, new Size(8, 8));
                Mat equalized = new Mat();
                clahe.apply(grey, equalized);
                grey.release();
                yield equalized;
            }
        };
    }

    private static double score(Mat frame, Mat template) {
        int cols = frame.cols() - template.cols() + 1;
        int rows = frame.rows() - template.rows() + 1;
        if (cols <= 0 || rows <= 0) {
            return Double.NaN;
        }
        Mat heatmap = new Mat(rows, cols, CvType.CV_32FC1);
        Imgproc.matchTemplate(frame, template, heatmap, Imgproc.TM_CCOEFF_NORMED);
        Core.MinMaxLocResult peak = Core.minMaxLoc(heatmap);
        heatmap.release();
        double bounded = Math.max(-1.0, Math.min(1.0, peak.maxVal));
        return bounded * 100.0;
    }

    private static String stripPng(String name) {
        return name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
    }
}
