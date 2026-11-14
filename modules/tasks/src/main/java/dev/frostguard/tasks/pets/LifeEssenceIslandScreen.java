package dev.frostguard.tasks.pets;

import java.awt.image.BufferedImage;

import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.vision.color.ColorComponents;
import dev.frostguard.vision.color.GameColors;

/**
 * Confirms that a frame shows the player's own island by its top-bar Life
 * Essence counter.
 *
 * <p>Without this check a blank transition frame, or whatever screen a failed
 * navigation left behind, has no markers and reads as an already collected
 * island. On the four saved island frames the counter crystal is one
 * vivid-green region of 674 to 684 pixels in a 28 x 40-41 box. The eighteen
 * saved non-island frames, including the world map, the city view, alliance
 * screens and a blank transition, have no vivid-green region of 100 pixels in
 * that area.
 */
public final class LifeEssenceIslandScreen {

    static final int MIN_COUNTER_PIXELS = 400;
    static final int MIN_COUNTER_WIDTH = 22;
    static final int MAX_COUNTER_WIDTH = 36;
    static final int MIN_COUNTER_HEIGHT = 32;
    static final int MAX_COUNTER_HEIGHT = 50;

    private LifeEssenceIslandScreen() {
    }

    public static boolean isConfirmed(BufferedImage frame) {
        return ColorComponents.find(frame, CommonGameAreas.ISLAND_ESSENCE_COUNTER,
                        GameColors::isVividGreen, MIN_COUNTER_PIXELS)
                .stream()
                .anyMatch(LifeEssenceIslandScreen::isCounterCrystal);
    }

    private static boolean isCounterCrystal(ColorComponents.Component component) {
        int width = component.bounds().bottomRight().getX() - component.bounds().topLeft().getX() + 1;
        int height = component.bounds().bottomRight().getY() - component.bounds().topLeft().getY() + 1;
        return width >= MIN_COUNTER_WIDTH && width <= MAX_COUNTER_WIDTH
                && height >= MIN_COUNTER_HEIGHT && height <= MAX_COUNTER_HEIGHT;
    }
}
