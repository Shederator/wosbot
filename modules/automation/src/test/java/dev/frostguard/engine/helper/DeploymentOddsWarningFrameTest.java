package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @Test
    void onlyTheSurvivablePhraseAllowsAMarch() {
        assertEquals(DeploymentHelper.OddsWarning.UNLIKELY,
                DeploymentHelper.classifyOddsLine("You are not likely to prevail"));
    }

    @Test
    void theStrongerWarningIsNeverTreatedAsSurvivable() {
        assertEquals(DeploymentHelper.OddsWarning.CERTAIN_FAILURE,
                DeploymentHelper.classifyOddsLine("This deployment is almost certain to fail"));
        assertEquals(DeploymentHelper.OddsWarning.CERTAIN_FAILURE,
                DeploymentHelper.classifyOddsLine("You are almost certain to fail"));
    }

    /** An unreadable red line is fatal, not survivable: a march is worth more than a retry. */
    @Test
    void anUnreadableRedLineIsTreatedAsFatal() {
        assertEquals(DeploymentHelper.OddsWarning.CERTAIN_FAILURE, DeploymentHelper.classifyOddsLine(""));
        assertEquals(DeploymentHelper.OddsWarning.CERTAIN_FAILURE, DeploymentHelper.classifyOddsLine(null));
    }

    private int redPixels(String resource) throws Exception {
        return PixelStats.count(image(resource),
                CommonGameAreas.DEPLOY_ODDS_WARNING_AREA, GameColors::isBlockedRed);
    }

    private BufferedImage image(String resource) throws Exception {
        return ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(resource)));
    }

    @Test
    void anUnreadableCheckIsItsOwnVerdictNotANonWarning() {
        // readOddsWarning used to return NONE when the capture or the pixel scan threw, which let a
        // march the game may have been calling almost certain to fail deploy on a dropped frame --
        // the opposite of the contract classifyOddsLine states, where an unreadable RED line is
        // deliberately fatal because being wrong one way costs a retry and the other way an army.
        //
        // It cannot simply become CERTAIN_FAILURE either: IntelligenceRoutine treats that as the
        // game passing judgement and abandons Fire Beasts for the whole run, so one bad frame would
        // have cost every remaining beast. UNREADABLE refuses the march and keeps the run.
        // The property that matters is that it is neither of the two it used to be conflated with.
        assertNotEquals(DeploymentHelper.OddsWarning.NONE, DeploymentHelper.OddsWarning.UNREADABLE);
        assertNotEquals(DeploymentHelper.OddsWarning.CERTAIN_FAILURE,
                DeploymentHelper.OddsWarning.UNREADABLE);

        // And a red line that OCR could not read is still fatal -- that path is unchanged, and is
        // the one the "cost of being wrong is an army" comment is about.
        assertEquals(DeploymentHelper.OddsWarning.CERTAIN_FAILURE, DeploymentHelper.classifyOddsLine(""));
    }
}
