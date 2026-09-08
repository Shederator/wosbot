package dev.frostguard.vision.color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AreaData;

class ColorBlobFinderTest {

    private static final int MARKER = new Color(60, 200, 90).getRGB();
    private static final int BACKGROUND = new Color(120, 150, 200).getRGB();
    private static final IntPredicate IS_MARKER = rgb -> rgb == MARKER;

    @Test
    void reportsCentreOfEachSeparateMarker() {
        BufferedImage frame = blankFrame(200, 200);
        fill(frame, 20, 20, 40, 40);
        fill(frame, 140, 150, 160, 170);

        List<ColorBlobFinder.Blob> blobs =
                ColorBlobFinder.find(frame, AreaData.of(0, 0, 200, 200), IS_MARKER, 1);

        assertEquals(2, blobs.size());
        assertTrue(blobs.stream().anyMatch(blob -> blob.centre().col() == 29 && blob.centre().row() == 29));
        assertTrue(blobs.stream().anyMatch(blob -> blob.centre().col() == 149 && blob.centre().row() == 159));
    }

    @Test
    void joinsDiagonallyTouchingRunsIntoSeparateBlobs() {
        BufferedImage frame = blankFrame(60, 60);
        fill(frame, 10, 10, 20, 20);
        fill(frame, 20, 20, 30, 30);

        List<ColorBlobFinder.Blob> blobs =
                ColorBlobFinder.find(frame, AreaData.of(0, 0, 60, 60), IS_MARKER, 1);

        assertEquals(2, blobs.size(), "4-connectivity must not bridge a corner-only touch");
    }

    @Test
    void discardsRunsBelowTheNoiseFloor() {
        BufferedImage frame = blankFrame(100, 100);
        fill(frame, 10, 10, 40, 40);
        fill(frame, 80, 80, 82, 82);

        List<ColorBlobFinder.Blob> blobs =
                ColorBlobFinder.find(frame, AreaData.of(0, 0, 100, 100), IS_MARKER, 50);

        assertEquals(1, blobs.size());
        assertEquals(900, blobs.getFirst().pixelCount());
    }

    @Test
    void separatesCompactIconsFromThinBackgroundSlivers() {
        BufferedImage frame = blankFrame(400, 200);
        fill(frame, 20, 20, 70, 70);
        fill(frame, 100, 100, 380, 106);

        List<ColorBlobFinder.Blob> blobs =
                ColorBlobFinder.find(frame, AreaData.of(0, 0, 400, 200), IS_MARKER, 100);

        ColorBlobFinder.Blob icon = blobs.stream()
                .filter(blob -> blob.width() == blob.height())
                .findFirst()
                .orElseThrow();
        ColorBlobFinder.Blob sliver = blobs.stream()
                .filter(blob -> blob.width() > blob.height() * 4)
                .findFirst()
                .orElseThrow();

        assertEquals(1.0, icon.fillRatio(), 0.001);
        assertEquals(50, icon.width());
        assertEquals(50, icon.height());
        assertEquals(280, sliver.width());
        assertEquals(6, sliver.height());
    }

    @Test
    void ignoresMarkersOutsideTheSearchArea() {
        BufferedImage frame = blankFrame(300, 300);
        fill(frame, 10, 10, 40, 40);
        fill(frame, 200, 200, 240, 240);

        List<ColorBlobFinder.Blob> blobs =
                ColorBlobFinder.find(frame, AreaData.of(150, 150, 300, 300), IS_MARKER, 1);

        assertEquals(1, blobs.size());
        assertEquals(219, blobs.getFirst().centre().col());
    }

    @Test
    void clampsSearchAreaToTheFrameInsteadOfFailing() {
        BufferedImage frame = blankFrame(100, 100);
        fill(frame, 60, 60, 90, 90);

        List<ColorBlobFinder.Blob> blobs =
                ColorBlobFinder.find(frame, AreaData.of(-50, -50, 4000, 4000), IS_MARKER, 1);

        assertEquals(1, blobs.size());
        assertEquals(900, blobs.getFirst().pixelCount());
    }

    @Test
    void returnsLargestBlobFirst() {
        BufferedImage frame = blankFrame(200, 200);
        fill(frame, 10, 10, 20, 20);
        fill(frame, 100, 100, 150, 150);
        fill(frame, 60, 10, 90, 40);

        List<ColorBlobFinder.Blob> blobs =
                ColorBlobFinder.find(frame, AreaData.of(0, 0, 200, 200), IS_MARKER, 1);

        assertEquals(2500, blobs.get(0).pixelCount());
        assertEquals(900, blobs.get(1).pixelCount());
        assertEquals(100, blobs.get(2).pixelCount());
    }

    private static BufferedImage blankFrame(int width, int height) {
        BufferedImage frame = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                frame.setRGB(col, row, BACKGROUND);
            }
        }
        return frame;
    }

    private static void fill(BufferedImage frame, int fromCol, int fromRow, int toCol, int toRow) {
        for (int row = fromRow; row < toRow; row++) {
            for (int col = fromCol; col < toCol; col++) {
                frame.setRGB(col, row, MARKER);
            }
        }
    }
}
