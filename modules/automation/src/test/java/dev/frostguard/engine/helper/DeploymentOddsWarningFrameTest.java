package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.color.PixelStats;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;

/**
 * Frames for the deploy screen's odds line, which sits between the hero cards and the first troop
 * row and is red when the odds are against the march and green when they favour it.
 * {@code readOddsWarning()} needs an emulator to fetch the frame, so these exercise the area and
 * the predicate it counts with.
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
                AreaData.of(150, 570, 570, 585), GameColors::isBlockedRed);
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

    /**
     * A capture that throws is neither "no warning" (which would deploy into a warning nobody read)
     * nor a fatal verdict (which would abandon Fire Beasts for the run on one dropped frame).
     */
    @Test
    void aFailedCaptureIsUnreadableRatherThanAVerdict() {
        AccountDescriptor profile = new AccountDescriptor(1L);
        profile.setDisplayName("test");
        DeploymentHelper helper = new DeploymentHelper(null, "test", null,
                new ResilientOcrExecutor<Integer>((config, topLeft, bottomRight) -> ""),
                new ResilientOcrExecutor<Duration>((config, topLeft, bottomRight) -> ""), profile);

        assertEquals(DeploymentHelper.OddsWarning.UNREADABLE, helper.readOddsWarning());
    }
}
