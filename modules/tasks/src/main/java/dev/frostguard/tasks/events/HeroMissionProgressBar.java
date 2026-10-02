package dev.frostguard.tasks.events;

import java.awt.Color;
import java.awt.image.BufferedImage;

/** Reads the filled state of the Hero's Mission reward progress bar. */
final class HeroMissionProgressBar {
    private static final int START_X = 185;
    private static final int END_X = 645;
    private static final int TOP_Y = 1041;
    private static final int BOTTOM_Y = 1047;
    private static final int ANCHOR_END_X = 215;
    private static final int END_ANCHOR_START_X = 635;
    private static final int MAX_UNCERTAIN_COLUMNS = 8;

    enum State {
        COMPLETE,
        IN_PROGRESS,
        UNKNOWN
    }

    private enum Column {
        ORANGE,
        GREY,
        OTHER
    }

    private HeroMissionProgressBar() {
    }

    static State read(BufferedImage frame) {
        if (frame == null || frame.getWidth() <= END_X || frame.getHeight() <= BOTTOM_Y) {
            return State.UNKNOWN;
        }

        Column[] columns = new Column[END_X - START_X + 1];
        for (int x = START_X; x <= END_X; x++) {
            columns[x - START_X] = classifyColumn(frame, x);
        }
        if (continuous(columns, Column.GREY)) {
            return State.IN_PROGRESS;
        }
        if (!mostly(columns, START_X, ANCHOR_END_X, Column.ORANGE, 0.9)) {
            return State.UNKNOWN;
        }

        if (continuous(columns, Column.ORANGE)) {
            return State.COMPLETE;
        }

        if (!mostly(columns, END_ANCHOR_START_X, END_X, Column.GREY, 0.9)) {
            return State.UNKNOWN;
        }
        int transition = -1;
        for (int x = ANCHOR_END_X + 1; x <= END_X; x++) {
            if (columns[x - START_X] == Column.GREY) {
                transition = x;
                break;
            }
        }
        if (transition < 0) {
            return State.UNKNOWN;
        }

        int uncertain = 0;
        for (int x = START_X; x < transition; x++) {
            if (columns[x - START_X] != Column.ORANGE) {
                uncertain++;
            }
        }
        for (int x = transition; x <= END_X; x++) {
            if (columns[x - START_X] != Column.GREY) {
                uncertain++;
            }
        }
        return uncertain <= MAX_UNCERTAIN_COLUMNS ? State.IN_PROGRESS : State.UNKNOWN;
    }

    private static boolean continuous(Column[] columns, Column expected) {
        int mismatches = 0;
        int gap = 0;
        int longestGap = 0;
        for (Column column : columns) {
            if (column == expected) {
                gap = 0;
            } else {
                mismatches++;
                longestGap = Math.max(longestGap, ++gap);
            }
        }
        return mismatches <= 5 && longestGap <= 3;
    }

    private static Column classifyColumn(BufferedImage frame, int x) {
        int orange = 0;
        int grey = 0;
        int majority = (BOTTOM_Y - TOP_Y + 1) / 2 + 1;
        for (int y = TOP_Y; y <= BOTTOM_Y; y++) {
            int rgb = frame.getRGB(x, y);
            float[] hsv = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
            float hue = hsv[0] * 360;
            if (hue >= 25 && hue <= 50 && hsv[1] >= 0.5 && hsv[2] >= 0.55) {
                orange++;
            } else if (hsv[1] <= 0.4 && hsv[2] >= 0.3 && hsv[2] <= 0.8) {
                grey++;
            }
        }
        if (orange >= majority) {
            return Column.ORANGE;
        }
        if (grey >= majority) {
            return Column.GREY;
        }
        return Column.OTHER;
    }

    private static boolean mostly(Column[] columns, int fromX, int toX, Column expected, double proportion) {
        int matches = 0;
        for (int x = fromX; x <= toX; x++) {
            if (columns[x - START_X] == expected) {
                matches++;
            }
        }
        return matches >= Math.ceil((toX - fromX + 1) * proportion);
    }
}
