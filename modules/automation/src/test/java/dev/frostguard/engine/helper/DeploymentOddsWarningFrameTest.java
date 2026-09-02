package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.color.PixelStats;

/**
 * Frames for the deploy screen's odds line, which sits between the hero cards and the first troop
 * row and is red when the march loses and green when it wins. {@code isUnlikelyToPrevail()} needs an
 * emulator to fetch the frame, so these exercise the area and the predicate it counts with.
 */
class DeploymentOddsWarningFrameTest {

    /** The captured Fire Beast deploy screen printing "You are not likely to prevail" in red. */
    private static final String WARNED = "/deployment/intel-fire-beast-not-likely-to-prevail-20260830.png";

    /** The same screen printing "You are quite likely to prevail" in green, at the same coordinates. */
    private static final String GREEN_ODDS = "/deployment/intel-beast-formation-20260819.png";

    /** A rally deploy screen, to show the band is not a beast-screen accident. */
    private static final String RALLY = "/deployment/polar-after-equalize-20260709.png";

    @Test
    void redWarningIsDetectedOnTheFireBeastFrame() throws Exception {
        assertTrue(redPixels(WARNED) >= DeploymentHelper.ODDS_WARNING_PIXEL_MIN);
    }

    @Test
    void greenOddsOnTheSameLineAreNotReadAsAWarning() throws Exception {
        assertEquals(0, redPixels(GREEN_ODDS));
        assertFalse(redPixels(GREEN_ODDS) >= DeploymentHelper.ODDS_WARNING_PIXEL_MIN);
    }

    @Test
    void rallyDeployScreenWithoutAnOddsLineIsQuiet() throws Exception {
        assertEquals(0, redPixels(RALLY));
    }

    /**
     * The band clears the orange hero card's bottom edge above it. That edge counts as blocked red, so
     * a band starting any higher would warn on every screen with an orange-carded hero.
     */
    @Test
    void bandStaysBelowTheHeroCardEdge() throws Exception {
        BufferedImage warned = image(WARNED);
        int cardEdgeRow = PixelStats.count(warned,
                dev.frostguard.api.domain.AreaData.of(150, 570, 570, 585), GameColors::isBlockedRed);
        assertTrue(cardEdgeRow > 0, "fixture no longer shows the orange card edge this band avoids");
        assertEquals(590, CommonGameAreas.DEPLOY_ODDS_WARNING_AREA.topLeft().getY());
    }

    private int redPixels(String resource) throws Exception {
        return PixelStats.count(image(resource),
                CommonGameAreas.DEPLOY_ODDS_WARNING_AREA, GameColors::isBlockedRed);
    }

    private BufferedImage image(String resource) throws Exception {
        return ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(resource)));
    }
}
