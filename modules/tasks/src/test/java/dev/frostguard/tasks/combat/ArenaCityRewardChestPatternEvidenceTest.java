package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saved-frame evidence for the chest the arena leaves over the city once the
 * day's attempts are spent.
 *
 * <p>The chest is a small graphic inside a white speech bubble floating above
 * the Arena building, on a city view full of pale snow and other bubbles, and
 * it drifts a few pixels from side to side. The risk is therefore in both
 * directions: too strict a threshold and a genuine chest goes uncollected for
 * a day, too loose and the run taps a building and leaves the city screen
 * behind.</p>
 *
 * <p>Measured over the 744 debug frames on the machine these were captured on,
 * the chest template peaked at 0.713 where no chest was present and never fell
 * below 0.849 where one was, which is where the runtime threshold of 80 comes
 * from. The frames below hold the two ends of that gap, plus the Rewards panel
 * the chest opens. Each is redacted over the chat ticker and the account
 * avatar; the bubble itself is untouched.</p>
 */
class ArenaCityRewardChestPatternEvidenceTest {

    private static final PointData FRAME_TOP_LEFT = new PointData(0, 0);
    private static final PointData FRAME_BOTTOM_RIGHT = new PointData(720, 1280);

    private static final String CHEST_TEMPLATE = "/templates/arena/arenaCityRewardChest.png";
    private static final String BANNER_TEMPLATE = "/templates/arena/arenaRewardsBanner.png";

    private static final String CHEST_PRESENT = "/arena/city-arena-reward-chest-present-20261022.png";
    private static final String CHEST_CLAIMED = "/arena/city-arena-reward-chest-claimed-20261022.png";
    private static final String REWARDS_PANEL = "/arena/arena-rewards-panel-20261022.png";

    private static final int CHEST_THRESHOLD = 80;
    private static final int BANNER_THRESHOLD = 85;

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void findsTheChestBubbleAboveTheArenaBuilding() throws IOException {
        ImageSearchResultData hit = locate(CHEST_PRESENT, CHEST_TEMPLATE, CHEST_THRESHOLD);

        assertTrue(hit.isFound(), "The waiting chest should be located in the city view: " + hit);
        assertTrue(hit.getMatchScore() >= CHEST_THRESHOLD,
                "The chest should clear the runtime threshold: " + hit);
    }

    @Test
    void findsNoChestOnceItHasBeenClaimed() throws IOException {
        ImageSearchResultData hit = locate(CHEST_CLAIMED, CHEST_TEMPLATE, CHEST_THRESHOLD);

        assertFalse(hit.isFound(),
                "A collected chest must not be re-detected, or the run taps the building under it: " + hit);
    }

    @Test
    void doesNotMistakeTheOpenRewardsPanelForAnotherChest() throws IOException {
        ImageSearchResultData hit = locate(REWARDS_PANEL, CHEST_TEMPLATE, CHEST_THRESHOLD);

        assertFalse(hit.isFound(),
                "The blurred city behind the Rewards panel must not read as a second chest: " + hit);
    }

    @Test
    void confirmsAClaimOnlyWhenTheRewardsPanelIsOpen() throws IOException {
        ImageSearchResultData open = locate(REWARDS_PANEL, BANNER_TEMPLATE, BANNER_THRESHOLD);

        assertTrue(open.isFound(), "The Rewards banner should be located once the chest is opened: " + open);
        assertTrue(open.getMatchScore() >= BANNER_THRESHOLD,
                "The banner should clear the runtime threshold: " + open);
    }

    @Test
    void doesNotReportARewardsPanelOnTheCityView() throws IOException {
        assertFalse(locate(CHEST_PRESENT, BANNER_TEMPLATE, BANNER_THRESHOLD).isFound(),
                "An unopened chest must not count as a claim.");
        assertFalse(locate(CHEST_CLAIMED, BANNER_TEMPLATE, BANNER_THRESHOLD).isFound(),
                "The city view after collection must not count as a claim.");
    }

    private ImageSearchResultData locate(String frameResource, String templateResource, int threshold)
            throws IOException {
        return OpenCvPatternLocator.locatePattern(
                resource(frameResource),
                templateResource,
                FRAME_TOP_LEFT,
                FRAME_BOTTOM_RIGHT,
                threshold);
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = ArenaCityRewardChestPatternEvidenceTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
