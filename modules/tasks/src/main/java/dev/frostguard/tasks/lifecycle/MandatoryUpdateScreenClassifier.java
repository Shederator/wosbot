package dev.frostguard.tasks.lifecycle;

import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * Complements stable in-game title and button identity patterns with the
 * measured single-action major-update layout. The two-action resource-download
 * dialog is excluded.
 */
final class MandatoryUpdateScreenClassifier {

    private static final double MIN_HEADER_BLUE_RATIO = 0.45;
    private static final double MIN_PANEL_RATIO = 0.70;
    private static final double MIN_ACTION_BLUE_RATIO = 0.45;
    private static final double MAX_OUTSIDE_ACTION_RATIO = 0.08;

    private MandatoryUpdateScreenClassifier() {
    }

    static Evidence inspect(BufferedImage image,
            boolean titlePatternFound, double titlePatternScore,
            boolean buttonPatternFound, double buttonPatternScore) {
        if (image == null || image.getWidth() < 100 || image.getHeight() < 100) {
            return new Evidence(false, titlePatternFound, titlePatternScore,
                    buttonPatternFound, buttonPatternScore, 0, 0, 0, 0);
        }

        double headerBlue = ratio(image, 0.075, 0.207, 0.925, 0.258,
                MandatoryUpdateScreenClassifier::isDialogBlue);
        double panel = ratio(image, 0.075, 0.270, 0.925, 0.695,
                MandatoryUpdateScreenClassifier::isPanelBlue);
        double actionBlue = ratio(image, 0.300, 0.715, 0.700, 0.775,
                MandatoryUpdateScreenClassifier::isActionBlue);
        double outsideAction = Math.max(
                ratio(image, 0.075, 0.715, 0.270, 0.775,
                        MandatoryUpdateScreenClassifier::isActionBlueOrOrange),
                ratio(image, 0.730, 0.715, 0.925, 0.775,
                        MandatoryUpdateScreenClassifier::isActionBlueOrOrange));

        boolean detected = titlePatternFound
                && buttonPatternFound
                && headerBlue >= MIN_HEADER_BLUE_RATIO
                && panel >= MIN_PANEL_RATIO
                && actionBlue >= MIN_ACTION_BLUE_RATIO
                && outsideAction <= MAX_OUTSIDE_ACTION_RATIO;
        return new Evidence(detected, titlePatternFound, titlePatternScore,
                buttonPatternFound, buttonPatternScore,
                headerBlue, panel, actionBlue, outsideAction);
    }

    private static double ratio(BufferedImage image, double left, double top,
            double right, double bottom, PixelMatcher matcher) {
        int x1 = (int) Math.round(image.getWidth() * left);
        int y1 = (int) Math.round(image.getHeight() * top);
        int x2 = Math.min(image.getWidth(), (int) Math.round(image.getWidth() * right));
        int y2 = Math.min(image.getHeight(), (int) Math.round(image.getHeight() * bottom));
        int matched = 0;
        int total = Math.max(1, (x2 - x1) * (y2 - y1));

        for (int y = y1; y < y2; y++) {
            for (int x = x1; x < x2; x++) {
                if (matcher.matches(image.getRGB(x, y))) {
                    matched++;
                }
            }
        }
        return (double) matched / total;
    }

    private static boolean isDialogBlue(int rgb) {
        int red = red(rgb);
        int green = green(rgb);
        int blue = blue(rgb);
        return red >= 55 && red <= 135
                && green >= 105 && green <= 175
                && blue >= 170 && blue <= 235;
    }

    private static boolean isPanelBlue(int rgb) {
        int red = red(rgb);
        int green = green(rgb);
        int blue = blue(rgb);
        return red >= 185 && green >= 205 && blue >= 225
                && blue - red >= 10 && blue - green >= 5;
    }

    private static boolean isActionBlue(int rgb) {
        int red = red(rgb);
        int green = green(rgb);
        int blue = blue(rgb);
        return red <= 115 && green >= 125 && green <= 205 && blue >= 210;
    }

    private static boolean isActionBlueOrOrange(int rgb) {
        int red = red(rgb);
        int green = green(rgb);
        int blue = blue(rgb);
        return isActionBlue(rgb)
                || red >= 190 && green >= 45 && green <= 150 && blue <= 70;
    }

    private static int red(int rgb) {
        return rgb >> 16 & 0xFF;
    }

    private static int green(int rgb) {
        return rgb >> 8 & 0xFF;
    }

    private static int blue(int rgb) {
        return rgb & 0xFF;
    }

    record Evidence(boolean detected, boolean titlePatternFound, double titlePatternScore,
            boolean buttonPatternFound, double buttonPatternScore,
            double headerBlueRatio, double panelRatio, double actionBlueRatio,
            double outsideActionRatio) {

        String technicalSummary() {
            return String.format(Locale.ROOT,
                    "Update title pattern: %s (%.1f%%); update button pattern: %s (%.1f%%); "
                            + "header blue: %.1f%%; panel: %.1f%%; action blue: %.1f%%; "
                            + "outside action colors: %.1f%%",
                    matchLabel(titlePatternFound), titlePatternScore,
                    matchLabel(buttonPatternFound), buttonPatternScore,
                    headerBlueRatio * 100, panelRatio * 100,
                    actionBlueRatio * 100, outsideActionRatio * 100);
        }

        private static String matchLabel(boolean found) {
            return found ? "matched" : "not matched";
        }
    }

    @FunctionalInterface
    private interface PixelMatcher {
        boolean matches(int rgb);
    }
}
