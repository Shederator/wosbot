package dev.frostguard.tools.lifeessence;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.tasks.pets.LifeEssenceMarkerDetector;
import dev.frostguard.tasks.pets.LifeEssenceMarkerDetector.Candidate;

/**
 * Writes one annotated PNG per input frame from {@link LifeEssenceMarkerDetector}.
 * The picture is for inspection. It does not change which markers the task taps.
 */
public final class LifeEssenceDetectionTool {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Path DEFAULT_OUTPUT = Path.of("tools/life-essence-detection/target/detections");
    private static final Color ACCEPTED_GREEN = new Color(40, 200, 70);
    private static final Color REJECTED_RED = new Color(230, 40, 40);
    private static final Color BOX_CENTER_ORANGE = new Color(255, 170, 0);
    private static final Color TAP_CYAN = new Color(0, 220, 255);

    private LifeEssenceDetectionTool() {
    }

    public static void main(String[] args) throws IOException {
        Arguments arguments = Arguments.parse(args);
        if (arguments.help()) {
            System.out.println(Arguments.USAGE);
            return;
        }
        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        Path output = arguments.output();
        List<Path> images = imageFiles(arguments.inputs(), output);
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
                    System.out.println(benchmark(image, arguments.passes()));
                } else {
                    Path written = writeDetection(image, output, timestamp);
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

    static String benchmark(Path image, int passes) throws IOException {
        BufferedImage frame = readFrame(image);
        long regions = 0;
        long startedAt = System.nanoTime();
        for (int pass = 0; pass < passes; pass++) {
            regions += LifeEssenceMarkerDetector.assess(frame).size();
        }
        double meanMillis = (System.nanoTime() - startedAt) / 1_000_000.0 / passes;
        return image.getFileName()
                + "  passes=" + passes
                + "  mean=" + String.format(Locale.ROOT, "%.3f", meanMillis) + " ms"
                + "  regions=" + (regions / passes);
    }

    private static BufferedImage readFrame(Path image) throws IOException {
        BufferedImage frame = ImageIO.read(image.toFile());
        if (frame == null) {
            throw new IOException("unreadable image");
        }
        return frame;
    }

    static Path writeDetection(Path image, Path output, String timestamp) throws IOException {
        BufferedImage frame = readFrame(image);
        List<Candidate> candidates = LifeEssenceMarkerDetector.assess(frame);
        for (Candidate candidate : candidates) {
            System.out.println(image.getFileName() + "  " + summary(candidate));
        }
        if (candidates.isEmpty()) {
            System.out.println(image.getFileName() + "  no orange region above the assessment floor");
        }
        BufferedImage annotated = render(frame, candidates);
        Path destination = output.resolve(outputName(timestamp, image));
        ImageIO.write(annotated, "png", destination.toFile());
        return destination;
    }

    static String outputName(String timestamp, Path image) {
        String fileName = image.getFileName().toString();
        int extension = fileName.lastIndexOf('.');
        String stem = extension > 0 ? fileName.substring(0, extension) : fileName;
        String safeStem = stem.replaceAll("[^A-Za-z0-9._-]", "_");
        return timestamp + "-" + safeStem + "-detection_result.png";
    }

    private static String summary(Candidate candidate) {
        PointData green = candidate.greenCenter();
        String leaf = green == null
                ? "leaf=none"
                : "leaf=" + (green.getX() - candidate.center().getX())
                        + "," + (green.getY() - candidate.center().getY())
                        + " tap=(" + green.getX() + "," + green.getY() + ")";
        String state = candidate.accepted() ? "accepted" : "rejected";
        String reason = candidate.rejection() == null ? "" : " reason=" + candidate.rejection();
        return state
                + " center=(" + candidate.center().getX() + "," + candidate.center().getY() + ")"
                + " " + leaf
                + " orange=" + candidate.orangePixels()
                + " green=" + candidate.greenPixels()
                + " size=" + candidate.width() + "x" + candidate.height()
                + reason;
    }

    private static BufferedImage render(BufferedImage frame, List<Candidate> candidates) {
        int legendHeight = 28;
        BufferedImage image = new BufferedImage(
                frame.getWidth(), frame.getHeight() + legendHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.drawImage(frame, 0, 0, null);
        graphics.setStroke(new BasicStroke(3f));
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        for (Candidate candidate : candidates) {
            Color boxColor = candidate.accepted() ? ACCEPTED_GREEN : REJECTED_RED;
            int x = candidate.bounds().topLeft().getX();
            int y = candidate.bounds().topLeft().getY();
            graphics.setColor(boxColor);
            graphics.drawRect(x, y, Math.max(1, candidate.width() - 1), Math.max(1, candidate.height() - 1));
            drawCross(graphics, candidate.center(), BOX_CENTER_ORANGE);
            if (candidate.greenCenter() != null) {
                drawCross(graphics, candidate.greenCenter(), TAP_CYAN);
            }
            drawLabel(graphics, x, y, label(candidate), boxColor);
        }
        graphics.setColor(new Color(16, 18, 24));
        graphics.fillRect(0, frame.getHeight(), frame.getWidth(), legendHeight);
        graphics.setColor(Color.WHITE);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        graphics.drawString(
                "green box accepted, red rejected, cyan cross = tap (leaf), orange cross = box center",
                8, frame.getHeight() + 18);
        graphics.dispose();
        return image;
    }

    private static String label(Candidate candidate) {
        PointData green = candidate.greenCenter();
        String leaf = green == null
                ? ""
                : " leaf " + (green.getX() - candidate.center().getX())
                        + "," + (green.getY() - candidate.center().getY());
        String text = (candidate.accepted() ? "accepted " : "rejected ")
                + candidate.width() + "x" + candidate.height() + leaf;
        return text.length() <= 48 ? text : text.substring(0, 48);
    }

    private static void drawLabel(Graphics2D graphics, int x, int y, String text, Color color) {
        int textWidth = graphics.getFontMetrics().stringWidth(text);
        int textHeight = graphics.getFontMetrics().getHeight();
        int top = y >= textHeight + 4 ? y - textHeight - 2 : y + 4;
        graphics.setColor(new Color(0, 0, 0, 180));
        graphics.fillRect(x, top, textWidth + 6, textHeight);
        graphics.setColor(color);
        graphics.drawString(text, x + 3, top + graphics.getFontMetrics().getAscent());
    }

    private static void drawCross(Graphics2D graphics, PointData point, Color color) {
        graphics.setColor(color);
        graphics.drawLine(point.getX() - 8, point.getY(), point.getX() + 8, point.getY());
        graphics.drawLine(point.getX(), point.getY() - 8, point.getX(), point.getY() + 8);
    }

    private static List<Path> imageFiles(List<Path> inputs, Path output) throws IOException {
        List<Path> images = new ArrayList<>();
        Path outputRoot = output.toAbsolutePath().normalize();
        for (Path input : inputs) {
            if (Files.isDirectory(input)) {
                try (var paths = Files.walk(input)) {
                    paths.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                            .filter(path -> !path.toAbsolutePath().normalize().startsWith(outputRoot))
                            .sorted()
                            .forEach(images::add);
                }
            } else if (Files.isRegularFile(input)) {
                images.add(input);
            } else {
                throw new IllegalArgumentException("Missing input: " + input);
            }
        }
        images.sort(Comparator.naturalOrder());
        return images;
    }

    private record Arguments(Path output, List<Path> inputs, boolean doBenchmark, int passes, boolean help) {
        static final String USAGE = """
                Usage: detect.sh [--output dir] [--do-benchmark] [--passes N] <image-or-directory>...

                Writes timestamp-fixture_name-detection_result.png for each PNG.
                The default directory is tools/life-essence-detection/target/detections.
                A negative leaf dy means the green centroid is above the box center.
                --do-benchmark reads each image once, runs detection --passes times
                (default 1000), prints the mean time, and writes no PNG.
                """;

        static Arguments parse(String[] args) {
            Path output = DEFAULT_OUTPUT;
            List<Path> inputs = new ArrayList<>();
            boolean help = args.length == 0;
            boolean doBenchmark = false;
            int passes = 1000;
            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                if ("--help".equals(arg) || "-h".equals(arg)) {
                    help = true;
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
            return new Arguments(output, List.copyOf(inputs), doBenchmark, passes, help);
        }
    }
}
