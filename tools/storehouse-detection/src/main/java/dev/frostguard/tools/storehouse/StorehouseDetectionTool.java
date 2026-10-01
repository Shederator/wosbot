package dev.frostguard.tools.storehouse;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.tasks.economy.StorehouseBubbleDetector;
import dev.frostguard.tasks.economy.StorehouseBubbleDetector.Candidate;
import dev.frostguard.tasks.economy.StorehouseIconSearchKind;
import dev.frostguard.tools.detection.DetectionToolSupport;
import dev.frostguard.tools.detection.DetectionToolSupport.Mark;

/**
 * Writes one annotated PNG per input frame from the Storehouse colour or
 * template search. The picture is for inspection. It does not change which
 * icons the task taps.
 */
public final class StorehouseDetectionTool {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Path DEFAULT_OUTPUT = Path.of("tools/storehouse-detection/target/detections");

    private StorehouseDetectionTool() {
    }

    public static void main(String[] args) throws IOException {
        Arguments arguments = Arguments.parse(args);
        if (arguments.help()) {
            System.out.println(Arguments.USAGE);
            return;
        }
        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        Path output = arguments.output();
        List<Path> images = DetectionToolSupport.imageFiles(arguments.inputs(), output);
        if (images.isEmpty()) {
            throw new IllegalArgumentException("No PNG inputs.");
        }
        if (!arguments.doBenchmark()) {
            Files.createDirectories(output);
        }
        int failures = 0;
        for (Path image : images) {
            try {
                if (arguments.doBenchmark()) {
                    System.out.println(benchmark(image, arguments.passes(), arguments.search()));
                } else {
                    Path written = writeDetection(image, output, timestamp, arguments.search());
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

    static String benchmark(Path image, int passes, StorehouseIconSearchKind searchKind) throws IOException {
        byte[] encodedPng = Files.readAllBytes(image);
        BufferedImage frame = DetectionToolSupport.readFrame(encodedPng);
        var search = searchKind.open(encodedPng);
        DetectionToolSupport.Benchmark benchmark = DetectionToolSupport.benchmark(
                passes, () -> search.find(frame).stream().map(point -> Mark.point(point, "icon")).toList());
        return image.getFileName()
                + "  search=" + searchKind.name().toLowerCase(Locale.ROOT)
                + "  passes=" + passes
                + "  mean=" + String.format(Locale.ROOT, "%.3f", benchmark.meanMillis()) + " ms"
                + "  points=" + benchmark.detections();
    }

    static Path writeDetection(Path image, Path output, String timestamp, StorehouseIconSearchKind searchKind)
            throws IOException {
        byte[] encodedPng = Files.readAllBytes(image);
        BufferedImage frame = DetectionToolSupport.readFrame(encodedPng);
        if (searchKind == StorehouseIconSearchKind.TEMPLATE) {
            List<PointData> points = searchKind.open(encodedPng).find(frame);
            for (PointData point : points) {
                System.out.println(image.getFileName() + "  template center=" + point.getX() + "," + point.getY());
            }
            if (points.isEmpty()) {
                System.out.println(image.getFileName() + "  template found nothing");
            }
            List<Mark> marks = points.stream().map(point -> Mark.point(point, "template")).toList();
            Path destination = output.resolve(outputName(timestamp, image, searchKind));
            return DetectionToolSupport.writeAnnotation(frame, marks, destination,
                    "template search, cyan cross = match center, chest 75 / stamina 90");
        }
        List<Candidate> candidates = StorehouseBubbleDetector.assess(frame);
        for (Candidate candidate : candidates) {
            System.out.println(image.getFileName() + "  " + summary(candidate));
        }
        if (candidates.isEmpty()) {
            System.out.println(image.getFileName() + "  no white region above the assessment floor");
        }
        List<Mark> marks = candidates.stream()
                .map(candidate -> new Mark(candidate.bounds(), candidate.center(), null,
                        candidate.accepted(), label(candidate)))
                .toList();
        Path destination = output.resolve(outputName(timestamp, image, StorehouseIconSearchKind.COLOR));
        return DetectionToolSupport.writeAnnotation(frame, marks, destination,
                "green box accepted, red rejected, cyan cross = bubble center");
    }

    static String outputName(String timestamp, Path image, StorehouseIconSearchKind searchKind) {
        return DetectionToolSupport.outputName(timestamp, image, searchKind.name().toLowerCase(Locale.ROOT));
    }

    private static String summary(Candidate candidate) {
        String kind = candidate.kind() == null ? "none" : candidate.kind().name().toLowerCase(Locale.ROOT);
        String state = candidate.accepted() ? "accepted" : "rejected";
        String reason = candidate.rejection() == null ? "" : " reason=" + candidate.rejection();
        return state
                + " kind=" + kind
                + " center=(" + candidate.center().getX() + "," + candidate.center().getY() + ")"
                + " white=" + candidate.whitePixels()
                + " wood=" + candidate.woodPixels()
                + " copper=" + candidate.copperPixels()
                + " size=" + candidate.width() + "x" + candidate.height()
                + reason;
    }

    private static String label(Candidate candidate) {
        String kind = candidate.kind() == null ? "none" : candidate.kind().name().toLowerCase(Locale.ROOT);
        String text = (candidate.accepted() ? "accepted " : "rejected ")
                + kind + " " + candidate.width() + "x" + candidate.height();
        return text.length() <= 48 ? text : text.substring(0, 48);
    }

    private record Arguments(Path output, List<Path> inputs, boolean doBenchmark, int passes,
            StorehouseIconSearchKind search, boolean help) {
        static final String USAGE = """
                Usage: detect.sh [--output dir] [--search color|template] [--do-benchmark] [--passes N] <image-or-directory>...

                Writes timestamp-fixture_name-search-detection_result.png for each PNG.
                The default search is color. The live task still uses template.
                The default directory is tools/storehouse-detection/target/detections.
                --do-benchmark reads each image once, runs the selected search --passes times
                (default 1000), prints the mean time, and writes no PNG.
                """;

        static Arguments parse(String[] args) {
            Path output = DEFAULT_OUTPUT;
            List<Path> inputs = new ArrayList<>();
            boolean help = args.length == 0;
            boolean doBenchmark = false;
            int passes = 1000;
            StorehouseIconSearchKind search = StorehouseIconSearchKind.COLOR;
            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    help = true;
                } else if ("--search".equals(arg)) {
                    if (index + 1 >= args.length) {
                        throw new IllegalArgumentException("--search must be color or template");
                    }
                    search = StorehouseIconSearchKind.parse(args[++index]);
                } else if ("--do-benchmark".equals(arg)) {
                    doBenchmark = true;
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
            return new Arguments(output, List.copyOf(inputs), doBenchmark, passes, search, help);
        }
    }
}
