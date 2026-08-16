package dev.frostguard.vision.ocr;

import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.vision.convert.ImageConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class OcrDiagnosticWriter {

    private static final Logger log = LoggerFactory.getLogger(OcrDiagnosticWriter.class);

    private OcrDiagnosticWriter() {}

    static Path write(RawImageData capture, BufferedImage processed,
            int x, int y, int width, int height, OcrSettingsData settings, String text) throws IOException {
        Path directory = WorkspacePaths.current().cache().resolve("ocr");
        Files.createDirectories(directory);
        Path output = Files.createTempFile(directory, "ocr-", "-debug.png");
        BufferedImage full = ImageConverter.toBufferedImage(capture);
        ImageIO.write(composePanel(full, processed, x, y, width, height, settings, text), "png", output.toFile());
        log.debug("OCR diagnostic image saved to {}", output);
        return output;
    }

    private static BufferedImage composePanel(BufferedImage full, BufferedImage processed,
            int x, int y, int width, int height, OcrSettingsData settings, String text) {
        final int gap = 20;
        final int header = 40;
        final int infoHeight = 180;
        int rightWidth = Math.max(processed.getWidth(), 500);
        int totalWidth = full.getWidth() + gap + rightWidth;
        int totalHeight = Math.max(full.getHeight() + header,
                processed.getHeight() + header + infoHeight + gap);

        BufferedImage canvas = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = canvas.createGraphics();
        enableSmoothing(graphics);
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, totalWidth, totalHeight);

        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        graphics.drawString("Full image with region", 10, 20);
        graphics.drawImage(annotateRegion(full, x, y, width, height, text), 0, header, null);

        int rightX = full.getWidth() + gap;
        graphics.drawString("Processed region", rightX + 10, 20);
        graphics.drawImage(processed, rightX, header, null);

        int infoY = header + processed.getHeight() + gap;
        graphics.setColor(new Color(240, 240, 240));
        graphics.fillRect(rightX, infoY, rightWidth, infoHeight);
        graphics.setColor(Color.GRAY);
        graphics.drawRect(rightX, infoY, rightWidth, infoHeight);
        graphics.setColor(Color.BLACK);
        graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        int lineY = infoY + 20;
        for (String line : settingsSummary(settings, text).split("\n")) {
            graphics.drawString(line, rightX + 10, lineY);
            lineY += 18;
        }
        graphics.dispose();
        return canvas;
    }

    private static BufferedImage annotateRegion(BufferedImage full,
            int x, int y, int width, int height, String text) {
        BufferedImage copy = new BufferedImage(full.getWidth(), full.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = copy.createGraphics();
        enableSmoothing(graphics);
        graphics.drawImage(full, 0, 0, null);
        graphics.setColor(Color.RED);
        graphics.setStroke(new BasicStroke(3));
        graphics.drawRect(x, y, width, height);

        String label = text == null ? "" : text;
        graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        FontMetrics metrics = graphics.getFontMetrics();
        int labelX = x + 5;
        int labelY = y > metrics.getHeight() + 10 ? y - 10 : y + height + metrics.getHeight();
        graphics.setColor(new Color(0, 0, 0, 180));
        graphics.fillRect(labelX - 5, labelY - metrics.getHeight() + 5,
                metrics.stringWidth(label) + 10, metrics.getHeight());
        graphics.setColor(Color.RED);
        graphics.drawString(label, labelX, labelY);
        graphics.dispose();
        return copy;
    }

    private static String settingsSummary(OcrSettingsData settings, String text) {
        return "OCR settings:"
                + "\n  Layout: " + (settings.hasTextLayout() ? settings.textLayout() : "provider default")
                + "\n  Allowed glyphs: " + (settings.hasGlyphFilter() ? settings.allowedGlyphs() : "all")
                + "\n  Isolate foreground: " + settings.isolateForeground()
                + "\n  Target color: " + (settings.targetColor() == null ? "automatic" : settings.targetColor())
                + "\n\nRecognized: \"" + text + "\"";
    }

    private static void enableSmoothing(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
    }
}
