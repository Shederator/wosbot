package dev.frostguard.tools.detection;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Shared image loading, annotation, file selection, and benchmark support for detector tools. */
public final class DetectionToolSupport {

    private static final Color ACCEPTED_GREEN = new Color(40, 200, 70);
    private static final Color REJECTED_RED = new Color(230, 40, 40);
    private static final Color BOX_CENTER_ORANGE = new Color(255, 170, 0);
    private static final Color DETECTED_POINT_CYAN = new Color(0, 220, 255);

    private DetectionToolSupport() {
    }

    public static BufferedImage readFrame(Path image) throws IOException {
        BufferedImage frame = ImageIO.read(image.toFile());
        if (frame == null) {
            throw new IOException("unreadable image");
        }
        return frame;
    }

    public static BufferedImage readFrame(byte[] encodedImage) throws IOException {
        BufferedImage frame = ImageIO.read(new ByteArrayInputStream(encodedImage));
        if (frame == null) {
            throw new IOException("unreadable image");
        }
        return frame;
    }

    public static List<Path> imageFiles(List<Path> inputs, Path output) throws IOException {
        List<Path> images = new ArrayList<>();
        Path outputRoot = output.toAbsolutePath().normalize();
        for (Path input : inputs) {
            if (Files.isDirectory(input)) {
                try (Stream<Path> paths = Files.walk(input)) {
                    paths.filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                            .filter(path -> !path.toAbsolutePath().normalize().startsWith(outputRoot))
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

    public static Path writeAnnotation(BufferedImage frame, List<Mark> marks, Path destination, String legend)
            throws IOException {
        int legendHeight = 28;
        BufferedImage annotated = new BufferedImage(
                frame.getWidth(), frame.getHeight() + legendHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = annotated.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.drawImage(frame, 0, 0, null);
        graphics.setStroke(new BasicStroke(3f));
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        for (Mark mark : marks) {
            if (mark.bounds() != null) {
                int x = mark.bounds().topLeft().getX();
                int y = mark.bounds().topLeft().getY();
                int width = mark.bounds().bottomRight().getX() - x + 1;
                int height = mark.bounds().bottomRight().getY() - y + 1;
                Color color = mark.accepted() ? ACCEPTED_GREEN : REJECTED_RED;
                graphics.setColor(color);
                graphics.drawRect(x, y, Math.max(1, width - 1), Math.max(1, height - 1));
                drawLabel(graphics, x, y, mark.label(), color);
            }
            if (mark.reference() != null) {
                drawCross(graphics, mark.reference(), BOX_CENTER_ORANGE);
            }
            if (mark.point() != null) {
                drawCross(graphics, mark.point(), DETECTED_POINT_CYAN);
            }
        }
        graphics.setColor(new Color(16, 18, 24));
        graphics.fillRect(0, frame.getHeight(), frame.getWidth(), legendHeight);
        graphics.setColor(Color.WHITE);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        graphics.drawString(legend, 8, frame.getHeight() + 18);
        graphics.dispose();
        ImageIO.write(annotated, "png", destination.toFile());
        return destination;
    }

    public static Benchmark benchmark(int passes, Supplier<List<Mark>> search) {
        long detected = 0;
        long startedAt = System.nanoTime();
        for (int pass = 0; pass < passes; pass++) {
            detected += search.get().size();
        }
        double meanMillis = (System.nanoTime() - startedAt) / 1_000_000.0 / passes;
        return new Benchmark(meanMillis, (int) (detected / passes));
    }

    public static String outputName(String timestamp, Path image, String detectorName) {
        String fileName = image.getFileName().toString();
        int extension = fileName.lastIndexOf('.');
        String stem = extension > 0 ? fileName.substring(0, extension) : fileName;
        String safeStem = stem.replaceAll("[^A-Za-z0-9._-]", "_");
        return timestamp + "-" + safeStem + "-" + detectorName + "-detection_result.png";
    }

    private static void drawLabel(Graphics2D graphics, int x, int y, String text, Color color) {
        if (text == null || text.isBlank()) {
            return;
        }
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

    public record Mark(AreaData bounds, PointData point, PointData reference, boolean accepted, String label) {
        public static Mark point(PointData point, String label) {
            return new Mark(null, point, null, true, label);
        }
    }

    public record Benchmark(double meanMillis, int detections) {
    }
}
