package dev.frostguard.vision.color;

/**
 * The palette the game speaks in: one definition per colour, for everyone.
 *
 * <p>These predicates classify UI text and icons, not artwork. White marks ordinary labels and
 * counters, orange an action the player may still take, red one that is out of reach, and a saturated
 * green a positive state such as an active tick or a resource icon. Widen a threshold here rather than
 * introducing a private variant next to a caller.
 */
public final class GameColors {

    private GameColors() {}

    /** Ordinary white UI text, e.g. "Idle" or a countdown. */
    public static boolean isLabelWhite(int rgb) {
        return red(rgb) > 225 && green(rgb) > 225 && blue(rgb) > 225;
    }

    /** Orange call-to-action text, e.g. an "Unlock" prompt. */
    public static boolean isActionOrange(int rgb) {
        return red(rgb) > 200 && green(rgb) > 120 && green(rgb) < 190 && blue(rgb) < 90;
    }

    /** Red text marking something unavailable or unaffordable. */
    public static boolean isBlockedRed(int rgb) {
        return red(rgb) > 190 && green(rgb) < 100 && blue(rgb) < 100;
    }

    /** Saturated green, e.g. an active tick or a gather icon's disc. */
    public static boolean isVividGreen(int rgb) {
        return green(rgb) > 110 && green(rgb) > red(rgb) + 30 && green(rgb) > blue(rgb) + 30;
    }

    /** Blue circular march-queue activity icons such as stationed, attack, and rally rows. */
    public static boolean isMarchQueueIconBlue(int rgb) {
        return blue(rgb) > 150 && green(rgb) > 90 && red(rgb) < 120;
    }

    /** Green arena power text, indicating a weaker opponent. */
    public static boolean isArenaPowerGreen(int rgb) {
        return green(rgb) > Math.max(red(rgb), blue(rgb)) * 1.2 && green(rgb) > 100;
    }

    /** Red arena power text, indicating a stronger opponent. */
    public static boolean isArenaPowerRed(int rgb) {
        return red(rgb) > Math.max(green(rgb), blue(rgb)) * 1.2 && red(rgb) > 100;
    }

    /** Muted blue backing pill behind arena score/power values. */
    public static boolean isArenaBlueGrey(int rgb) {
        return Math.abs(red(rgb) - 127) < 20
                && Math.abs(green(rgb) - 173) < 20
                && Math.abs(blue(rgb) - 205) < 20;
    }

    /** Saturated blue arena challenge button, available to tap. */
    public static boolean isArenaChallengeBlue(int rgb) {
        return blue(rgb) > 145
                && red(rgb) > 70
                && red(rgb) < 150
                && green(rgb) > 100
                && green(rgb) < 190
                && blue(rgb) > red(rgb) + 35
                && blue(rgb) > green(rgb) + 5;
    }

    /** Greyed arena challenge button, unavailable without buying attempts. */
    public static boolean isArenaChallengeGrey(int rgb) {
        return Math.abs(red(rgb) - green(rgb)) < 22
                && Math.abs(green(rgb) - blue(rgb)) < 35
                && red(rgb) > 55
                && red(rgb) < 190;
    }

    /** Dark button shadow/background pixels inside disabled arena challenge buttons. */
    public static boolean isArenaChallengeDark(int rgb) {
        return red(rgb) < 80 && green(rgb) < 90 && blue(rgb) < 110;
    }

    /** Yellow star icon next to arena score/power values. */
    public static boolean isArenaStarYellow(int rgb) {
        return red(rgb) > 170
                && green(rgb) > 120
                && blue(rgb) < 125
                && red(rgb) > blue(rgb) + 70
                && green(rgb) > blue(rgb) + 45;
    }

    private static int red(int rgb)   { return (rgb >> 16) & 0xFF; }
    private static int green(int rgb) { return (rgb >> 8) & 0xFF; }
    private static int blue(int rgb)  { return rgb & 0xFF; }
}
