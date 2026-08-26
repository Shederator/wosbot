package dev.frostguard.vision.color;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Groups pixels of one colour into connected blobs and reports where each one sits.
 *
 * <p>Template matching identifies a fixed picture; this answers the other question, "where are the
 * things of this colour, and what shape are they". It exists because game badges move, animate, and
 * sit at player-specific positions, so a screen may hold zero, one, or several of the same marker
 * with no fixed coordinate to search. Callers get every blob above {@code minPixels} together with
 * its geometry and decide themselves which are real, so the size and shape rules that separate a
 * genuine marker from same-coloured artwork stay next to the game knowledge that justifies them.
 */
public final class ColorBlobFinder {

    private ColorBlobFinder() {}

    /**
     * One connected run of matching pixels.
     *
     * @param centre     centroid in absolute screen coordinates, suitable as a tap target
     * @param pixelCount matching pixels in the blob
     * @param width      bounding-box width
     * @param height     bounding-box height
     * @param fillRatio  {@code pixelCount} over the bounding-box area; compact icons score high,
     *                   thin slivers of background artwork score low
     */
    public record Blob(PointData centre, int pixelCount, int width, int height, double fillRatio) {}

    private static final int[] NEIGHBOUR_COL = {-1, 1, 0, 0};
    private static final int[] NEIGHBOUR_ROW = {0, 0, -1, 1};

    /**
     * Finds every 4-connected blob of {@code colour} inside {@code area}, largest first.
     *
     * @param minPixels blobs smaller than this are discarded as noise before they are returned
     */
    public static List<Blob> find(BufferedImage image, AreaData area, IntPredicate colour, int minPixels) {
        int left = Math.max(0, area.topLeft().col());
        int top = Math.max(0, area.topLeft().row());
        int right = Math.min(image.getWidth(), area.bottomRight().col());
        int bottom = Math.min(image.getHeight(), area.bottomRight().row());

        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0) {
            return List.of();
        }

        boolean[] matching = new boolean[width * height];
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                if (colour.test(image.getRGB(left + col, top + row))) {
                    matching[row * width + col] = true;
                }
            }
        }

        boolean[] visited = new boolean[width * height];
        int[] stackCol = new int[width * height];
        int[] stackRow = new int[width * height];
        List<Blob> blobs = new ArrayList<>();

        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int seed = row * width + col;
                if (!matching[seed] || visited[seed]) {
                    continue;
                }
                Blob blob = growFrom(col, row, width, height, matching, visited, stackCol, stackRow, left, top);
                if (blob.pixelCount() >= minPixels) {
                    blobs.add(blob);
                }
            }
        }

        blobs.sort(Comparator.comparingInt(Blob::pixelCount).reversed());
        return blobs;
    }

    private static Blob growFrom(int seedCol, int seedRow, int width, int height,
            boolean[] matching, boolean[] visited, int[] stackCol, int[] stackRow, int left, int top) {
        int stackSize = 0;
        stackCol[stackSize] = seedCol;
        stackRow[stackSize] = seedRow;
        stackSize++;
        visited[seedRow * width + seedCol] = true;

        long sumCol = 0;
        long sumRow = 0;
        int pixelCount = 0;
        int minCol = seedCol;
        int maxCol = seedCol;
        int minRow = seedRow;
        int maxRow = seedRow;

        while (stackSize > 0) {
            stackSize--;
            int col = stackCol[stackSize];
            int row = stackRow[stackSize];
            sumCol += col;
            sumRow += row;
            pixelCount++;
            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);

            for (int direction = 0; direction < NEIGHBOUR_COL.length; direction++) {
                int nextCol = col + NEIGHBOUR_COL[direction];
                int nextRow = row + NEIGHBOUR_ROW[direction];
                if (nextCol < 0 || nextCol >= width || nextRow < 0 || nextRow >= height) {
                    continue;
                }
                int next = nextRow * width + nextCol;
                if (matching[next] && !visited[next]) {
                    visited[next] = true;
                    stackCol[stackSize] = nextCol;
                    stackRow[stackSize] = nextRow;
                    stackSize++;
                }
            }
        }

        int boxWidth = maxCol - minCol + 1;
        int boxHeight = maxRow - minRow + 1;
        PointData centre = new PointData(
                left + (int) (sumCol / pixelCount),
                top + (int) (sumRow / pixelCount));
        return new Blob(centre, pixelCount, boxWidth, boxHeight, (double) pixelCount / (boxWidth * boxHeight));
    }
}
