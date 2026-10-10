package dev.frostguard.tasks.lifecycle;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.ocr.OcrEngine;
import dev.frostguard.vision.ocr.OcrException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrekExitHintClassifierTest {

    @Test
    void acceptsNormalizedOrderedTapThenExitVariants() {
        for (String variant : new String[] {
                "Tap anywhere to exit",
                " TAP   anywhere... to EXIT ",
                "Tap\nanywhere — to exit",
                "tap to exit" }) {
            assertTrue(TrekExitHintClassifier.inspect(variant).detected(), variant);
        }
    }

    @Test
    void requiresOrderedWholeWordsInsteadOfAGenericExitReference() {
        for (String nonHint : new String[] {
                "Exit now; then tap the reward",
                "tapping anywhere leaves this screen",
                "The exit is unavailable",
                "tapexit",
                "" }) {
            assertFalse(TrekExitHintClassifier.inspect(nonHint).detected(), nonHint);
        }
    }

    @Test
    void recognizesTheRedactedSavedTrekExitHint() throws IOException, OcrException {
        BufferedImage fixture = image("/startup/trek-exit-hint-20261010.png");
        String ocr = recognizeLowerBand(fixture, 0);

        TrekExitHintClassifier.Evidence evidence = TrekExitHintClassifier.inspect(ocr);
        assertTrue(evidence.detected(), () -> "OCR='" + ocr + "', normalized='"
                + evidence.normalizedText() + "'");
    }

    @Test
    void rejectsHigherPriorityStartupDialogLowerBands() throws IOException, OcrException {
        for (String fixture : new String[] {
                "/startup/mandatory-update-dialog-20260820.png",
                "/startup/resource-download-prompt-20260817.png",
                "/startup/welcome-back-dialog-20260821.png" }) {
            TrekExitHintClassifier.Evidence evidence = TrekExitHintClassifier.inspect(
                    recognizeLowerBand(image(fixture), 1160));
            assertFalse(evidence.detected(), fixture + ": " + evidence.normalizedText());
        }
    }

    private static String recognizeLowerBand(BufferedImage fixture, int top) throws OcrException {
        return OcrEngine.recognizeText(rawRgbaFrame(fixture),
                new PointData(0, top),
                new PointData(fixture.getWidth() - 1, fixture.getHeight() - 1),
                CommonOCRSettings.STARTUP_EXIT_HINT_SETTINGS);
    }

    private static BufferedImage image(String path) throws IOException {
        try (InputStream stream = TrekExitHintClassifierTest.class.getResourceAsStream(path)) {
            return ImageIO.read(Objects.requireNonNull(stream, "Missing fixture " + path));
        }
    }

    private static RawImageData rawRgbaFrame(BufferedImage frame) {
        byte[] pixels = new byte[frame.getWidth() * frame.getHeight() * 4];
        for (int y = 0, offset = 0; y < frame.getHeight(); y++) {
            for (int x = 0; x < frame.getWidth(); x++, offset += 4) {
                int argb = frame.getRGB(x, y);
                pixels[offset] = (byte) (argb >> 16);
                pixels[offset + 1] = (byte) (argb >> 8);
                pixels[offset + 2] = (byte) argb;
                pixels[offset + 3] = (byte) 0xFF;
            }
        }
        return RawImageData.capture(pixels, frame.getWidth(), frame.getHeight(), 32);
    }
}
