import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Standalone PNG tool that redacts cyan text printed inside horizontal nameplates. */
public final class CityLabelRedactor {

    private static final int REFERENCE_WIDTH = 720;
    private static final int REFERENCE_HEIGHT = 1280;
    private static final Color CITY_LABEL_REDACTION = new Color(91, 186, 231);
    private static final Color COORDINATES_REDACTION = new Color(52, 65, 87);
    private static final Color CHAT_REDACTION = new Color(55, 72, 102);
    private static final Color PRIVATE_OVERLAY_REDACTION = new Color(55, 72, 102);
    private static final RedactionArea COORDINATES_TEXT_AREA =
            new RedactionArea(220, 1052, 270, 31);
    private static final RedactionArea CHAT_AREA =
            new RedactionArea(0, 1105, 720, 74);
    private static final List<RedactionArea> PRIVATE_OVERLAY_AREAS = List.of(
            new RedactionArea(0, 92, 220, 20),
            new RedactionArea(704, 125, 16, 50));
    private static final List<RedactionArea> RIGHT_BUTTON_AREAS = List.of(
            new RedactionArea(625, 95, 95, 410),
            new RedactionArea(625, 820, 95, 285));
    private static final int TEXT_DILATION_X = 5;
    private static final int TEXT_DILATION_Y = 2;
    private static final int MIN_TEXT_WIDTH = 18;
    private static final int MAX_TEXT_HEIGHT = 34;
    private static final double MIN_NAMEPLATE_BACKGROUND_RATIO = 0.22;
    private static final double MAX_UNREDACTED_TEXT_DENSITY = 0.45;

    private CityLabelRedactor() {}

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 2) {
            System.err.println("Usage: java CityLabelRedactor.java <input.png> <output.png>");
            System.exit(2);
        }

        Path input = Path.of(arguments[0]);
        Path output = Path.of(arguments[1]);
        if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Input and output must be different files");
        }
        BufferedImage source = ImageIO.read(input.toFile());
        if (source == null) {
            throw new IOException("Unsupported or unreadable image: " + input);
        }

        RedactionResult result = redact(source);
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!ImageIO.write(result.image(), "png", output.toFile())) {
            throw new IOException("PNG writer is unavailable");
        }
        System.out.println("Redacted " + result.areas().size() + " nameplate(s): " + output);
    }

    static RedactionResult redact(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        boolean[][] cyanText = new boolean[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                cyanText[y][x] = isCyanNameText(source.getRGB(x, y));
            }
        }

        boolean[][] groupedText = dilate(cyanText, TEXT_DILATION_X, TEXT_DILATION_Y);
        List<RedactionArea> areas = connectedAreas(groupedText).stream()
                .filter(area -> area.width() >= MIN_TEXT_WIDTH)
                .filter(area -> area.height() <= MAX_TEXT_HEIGHT)
                .filter(area -> area.width() >= area.height() * 2)
                .filter(area -> hasUnredactedTextDensity(cyanText, area))
                .filter(area -> hasDarkNameplate(source, area))
                .map(area -> excludeRightButtonColumn(area, width, height))
                .filter(area -> area.width() > 0)
                .toList();

        BufferedImage redacted = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = redacted.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
            graphics.setColor(CITY_LABEL_REDACTION);
            for (RedactionArea area : areas) {
                graphics.fillRect(area.x(), area.y(), area.width(), area.height());
            }
            fillScaled(graphics, COORDINATES_TEXT_AREA, width, height, COORDINATES_REDACTION);
            fillScaled(graphics, CHAT_AREA, width, height, CHAT_REDACTION);
            for (RedactionArea area : PRIVATE_OVERLAY_AREAS) {
                fillScaled(graphics, area, width, height, PRIVATE_OVERLAY_REDACTION);
            }
        } finally {
            graphics.dispose();
        }
        return new RedactionResult(redacted, areas);
    }

    private static void fillScaled(Graphics2D graphics, RedactionArea referenceArea,
                                   int imageWidth, int imageHeight, Color color) {
        int x = referenceArea.x() * imageWidth / REFERENCE_WIDTH;
        int y = referenceArea.y() * imageHeight / REFERENCE_HEIGHT;
        int width = referenceArea.width() * imageWidth / REFERENCE_WIDTH;
        int height = referenceArea.height() * imageHeight / REFERENCE_HEIGHT;
        graphics.setColor(color);
        graphics.fillRect(x, y, width, height);
    }

    private static boolean isCyanNameText(int argb) {
        int red = (argb >>> 16) & 0xff;
        int green = (argb >>> 8) & 0xff;
        int blue = argb & 0xff;
        float[] hsb = Color.RGBtoHSB(red, green, blue, null);
        return hsb[0] >= 0.50f && hsb[0] <= 0.58f
                && hsb[1] >= 0.45f
                && hsb[2] >= 0.65f;
    }

    private static boolean[][] dilate(boolean[][] source, int radiusX, int radiusY) {
        int height = source.length;
        int width = height == 0 ? 0 : source[0].length;
        int[][] integral = new int[height + 1][width + 1];
        for (int y = 0; y < height; y++) {
            int rowTotal = 0;
            for (int x = 0; x < width; x++) {
                rowTotal += source[y][x] ? 1 : 0;
                integral[y + 1][x + 1] = integral[y][x + 1] + rowTotal;
            }
        }

        boolean[][] result = new boolean[height][width];
        for (int y = 0; y < height; y++) {
            int top = Math.max(0, y - radiusY);
            int bottom = Math.min(height, y + radiusY + 1);
            for (int x = 0; x < width; x++) {
                int left = Math.max(0, x - radiusX);
                int right = Math.min(width, x + radiusX + 1);
                int pixels = integral[bottom][right] - integral[top][right]
                        - integral[bottom][left] + integral[top][left];
                result[y][x] = pixels > 0;
            }
        }
        return result;
    }

    private static List<RedactionArea> connectedAreas(boolean[][] mask) {
        int height = mask.length;
        int width = height == 0 ? 0 : mask[0].length;
        boolean[][] visited = new boolean[height][width];
        List<RedactionArea> areas = new ArrayList<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();

        for (int startY = 0; startY < height; startY++) {
            for (int startX = 0; startX < width; startX++) {
                if (!mask[startY][startX] || visited[startY][startX]) {
                    continue;
                }

                int minX = startX;
                int maxX = startX;
                int minY = startY;
                int maxY = startY;
                visited[startY][startX] = true;
                queue.add(startY * width + startX);
                while (!queue.isEmpty()) {
                    int encoded = queue.removeFirst();
                    int x = encoded % width;
                    int y = encoded / width;
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);

                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nextX = x + dx;
                            int nextY = y + dy;
                            if (nextX < 0 || nextX >= width || nextY < 0 || nextY >= height
                                    || visited[nextY][nextX] || !mask[nextY][nextX]) {
                                continue;
                            }
                            visited[nextY][nextX] = true;
                            queue.add(nextY * width + nextX);
                        }
                    }
                }
                areas.add(new RedactionArea(minX, minY, maxX - minX + 1, maxY - minY + 1));
            }
        }
        return areas;
    }

    private static boolean hasDarkNameplate(BufferedImage source, RedactionArea text) {
        int left = Math.max(0, text.x() - 3);
        int right = Math.min(source.getWidth(), text.x() + text.width() + 3);
        int top = Math.max(100, text.y() - 5);
        int bottom = Math.min(source.getHeight() - 100, text.y() + text.height() + 5);
        if (top >= bottom) {
            return false;
        }

        int darkPixels = 0;
        int pixels = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int argb = source.getRGB(x, y);
                int red = (argb >>> 16) & 0xff;
                int green = (argb >>> 8) & 0xff;
                int blue = argb & 0xff;
                float[] hsb = Color.RGBtoHSB(red, green, blue, null);
                if (hsb[2] >= 0.12f && hsb[2] <= 0.65f && blue >= red) {
                    darkPixels++;
                }
                pixels++;
            }
        }
        return pixels > 0 && (double) darkPixels / pixels >= MIN_NAMEPLATE_BACKGROUND_RATIO;
    }

    private static boolean hasUnredactedTextDensity(boolean[][] cyanText, RedactionArea area) {
        int cyanPixels = 0;
        for (int y = area.y(); y < area.y() + area.height(); y++) {
            for (int x = area.x(); x < area.x() + area.width(); x++) {
                if (cyanText[y][x]) {
                    cyanPixels++;
                }
            }
        }
        double density = (double) cyanPixels / (area.width() * area.height());
        return density <= MAX_UNREDACTED_TEXT_DENSITY;
    }

    private static RedactionArea excludeRightButtonColumn(RedactionArea area,
                                                           int imageWidth, int imageHeight) {
        RedactionArea clipped = area;
        for (RedactionArea referenceButtons : RIGHT_BUTTON_AREAS) {
            RedactionArea buttons = scaled(referenceButtons, imageWidth, imageHeight);
            int areaBottom = clipped.y() + clipped.height();
            int buttonsBottom = buttons.y() + buttons.height();
            boolean overlapsVertically = clipped.y() < buttonsBottom && areaBottom > buttons.y();
            if (overlapsVertically && clipped.x() + clipped.width() > buttons.x()) {
                clipped = new RedactionArea(clipped.x(), clipped.y(),
                        Math.max(0, buttons.x() - clipped.x()), clipped.height());
            }
        }
        return clipped;
    }

    private static RedactionArea scaled(RedactionArea referenceArea, int imageWidth, int imageHeight) {
        return new RedactionArea(
                referenceArea.x() * imageWidth / REFERENCE_WIDTH,
                referenceArea.y() * imageHeight / REFERENCE_HEIGHT,
                referenceArea.width() * imageWidth / REFERENCE_WIDTH,
                referenceArea.height() * imageHeight / REFERENCE_HEIGHT);
    }

    record RedactionArea(int x, int y, int width, int height) {}

    record RedactionResult(BufferedImage image, List<RedactionArea> areas) {
        RedactionResult {
            areas = List.copyOf(areas);
        }
    }
}
