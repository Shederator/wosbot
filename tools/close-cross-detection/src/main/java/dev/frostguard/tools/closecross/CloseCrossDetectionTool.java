package dev.frostguard.tools.closecross;

import dev.frostguard.tools.detection.DetectionToolSupport;
import dev.frostguard.tools.detection.DetectionToolSupport.Benchmark;
import dev.frostguard.tools.detection.DetectionToolSupport.Mark;
import dev.frostguard.vision.detection.CloseCrossDetector;
import dev.frostguard.vision.detection.CloseCrossDetector.Detection;
import dev.frostguard.vision.detection.CloseCrossDetector.Region;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Annotates close-cross candidates or measures the detector over saved screenshots. */
public final class CloseCrossDetectionTool {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Path DEFAULT_OUTPUT = Path.of("tools/close-cross-detection/target/detections");

    private CloseCrossDetectionTool() {
    }

    public static void main(String[] args) throws IOException {
        Arguments arguments = Arguments.parse(args);
        if (arguments.help()) {
            System.out.println(Arguments.USAGE);
            return;
        }
        List<Path> images = DetectionToolSupport.imageFiles(arguments.inputs(), arguments.output());
        if (images.isEmpty()) {
            throw new IllegalArgumentException("No PNG inputs.");
        }
        if (!arguments.benchmark()) {
            Files.createDirectories(arguments.output());
        }
        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        int failures = 0;
        for (Path image : images) {
            try {
                BufferedImage frame = DetectionToolSupport.readFrame(image);
                if (arguments.benchmark()) {
                    System.out.println(benchmark(image, frame, arguments.region(), arguments.passes()));
                } else {
                    Path written = annotate(image, frame, arguments.output(), timestamp, arguments.region());
                    System.out.println(written);
                }
            } catch (IOException | RuntimeException ex) {
                failures++;
                System.err.println(image + ": " + ex.getMessage());
            }
        }
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static String benchmark(Path image, BufferedImage frame, Region region, int passes) {
        Benchmark result = DetectionToolSupport.benchmark(passes,
                () -> toMarks(CloseCrossDetector.locate(frame, region)));
        return image.getFileName()
                + "  detector=opencv-multiscale"
                + "  region=" + region
                + "  passes=" + passes
                + "  mean=" + String.format(Locale.ROOT, "%.3f", result.meanMillis()) + " ms"
                + "  matches=" + result.detections();
    }

    private static Path annotate(Path image, BufferedImage frame, Path output, String timestamp,
            Region region) throws IOException {
        List<Detection> candidates = CloseCrossDetector.locate(frame, region);
        for (Detection detection : candidates) {
            System.out.println(image.getFileName() + "  center=" + detection.center().getX() + ","
                    + detection.center().getY() + " score=" + String.format(Locale.ROOT, "%.1f", detection.score())
                    + "% bounds=" + detection.width() + "x" + detection.height());
        }
        if (candidates.isEmpty()) {
            System.out.println(image.getFileName() + "  no close-cross candidates at 55 percent");
        }
        List<Mark> marks = candidates.stream().map(detection -> {
            String label = String.format(Locale.ROOT, "close %.1f%%", detection.score());
            return new Mark(detection.bounds(), detection.center(), null, true, label);
        }).toList();
        Path destination = output.resolve(DetectionToolSupport.outputName(timestamp, image,
                "close-cross-" + region.name().toLowerCase(Locale.ROOT)));
        return DetectionToolSupport.writeAnnotation(frame, marks, destination,
                "green box = close-cross match, cyan cross = screen-relative center, region=" + region
                        + ", threshold 55 percent");
    }

    private static List<Mark> toMarks(List<Detection> detections) {
        return detections.stream().map(detection -> Mark.point(detection.center(), "close cross")).toList();
    }

    private record Arguments(Path output, List<Path> inputs, boolean benchmark, int passes,
            Region region, boolean help) {
        static final String USAGE = """
                Usage: detect.sh [--region REGION] [--output dir] [--do-benchmark] [--passes N] <image-or-directory>...

                Writes an annotated PNG for each input. The default output is
                tools/close-cross-detection/target/detections. The default region is HALF_RIGHT.
                Regions: UPPER_LEFT_QUARTER, UPPER_RIGHT_QUARTER, LOWER_LEFT_QUARTER,
                LOWER_RIGHT_QUARTER, HALF_RIGHT, MIDDLE, FULL_SCREEN.
                All annotations show coordinates relative to the full input frame.
                --do-benchmark reads each image once, runs the detector --passes times (default 100),
                and prints mean time per pass without writing PNGs.
                """;

        static Arguments parse(String[] args) {
            Path output = DEFAULT_OUTPUT;
            List<Path> inputs = new ArrayList<>();
            boolean help = args.length == 0;
            boolean benchmark = false;
            int passes = 100;
            Region region = Region.HALF_RIGHT;
            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    help = true;
                } else if ("--do-benchmark".equals(arg)) {
                    benchmark = true;
                } else if ("--region".equals(arg)) {
                    if (index + 1 >= args.length) {
                        throw new IllegalArgumentException("--region requires a region name");
                    }
                    String value = args[++index].replace('-', '_').toUpperCase(Locale.ROOT);
                    try {
                        region = Region.valueOf(value);
                    } catch (IllegalArgumentException ex) {
                        throw new IllegalArgumentException("Unknown region: " + value);
                    }
                } else if ("--passes".equals(arg)) {
                    if (index + 1 >= args.length) {
                        throw new IllegalArgumentException("--passes requires a positive count");
                    }
                    try {
                        passes = Integer.parseInt(args[++index]);
                    } catch (NumberFormatException ex) {
                        throw new IllegalArgumentException("--passes requires a positive count");
                    }
                    if (passes < 1) {
                        throw new IllegalArgumentException("--passes requires a positive count");
                    }
                } else if ("--output".equals(arg) || "-o".equals(arg)) {
                    if (index + 1 >= args.length) {
                        throw new IllegalArgumentException("--output requires a directory");
                    }
                    output = Path.of(args[++index]);
                } else if (arg.startsWith("-")) {
                    throw new IllegalArgumentException("Unknown option: " + arg);
                } else {
                    inputs.add(Path.of(arg));
                }
            }
            return new Arguments(output, List.copyOf(inputs), benchmark, passes, region, help);
        }
    }
}
