package dev.frostguard.tasks.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * Pins the Tundra Albums hub coordinates to a real captured frame.
 *
 * <p>Written after a live failure: the milestone chest track was tapped three times a pass and never
 * claimed anything, because all three tap candidates -- (245,178), (340,178), (428,178) -- landed in
 * the GAPS BETWEEN chest sprites. Nothing caught it, because a tap into empty panel produces no
 * error, just no reward, and the routine then logged "No milestone chest currently ready".
 *
 * <p>The fixture is a native 720x1280 ADB capture of the hub with the account at 940/1347 and
 * milestones at 815/855/895/935/975/1015, so chest 4 (935) is genuinely claimable. It carries no
 * player name, alliance tag or account identifier -- the hub's top bar shows only the panel title.
 *
 * <p>Evidence level: saved real-frame verification.
 */
class MonumentAlbumsHubFrameTest {

    private static final String FIXTURE = "/monument/tundra-albums-hub-20260820.png";

    /** Copies of the production constants -- see MonumentRoutine.MILESTONE_CHEST_CANDIDATES. */
    private static final int[] CHEST_X = {191, 275, 365, 456, 547, 637};
    private static final int CHEST_Y = 173;

    private static BufferedImage frame() throws Exception {
        return ImageIO.read(Objects.requireNonNull(
                MonumentAlbumsHubFrameTest.class.getResourceAsStream(FIXTURE),
                "missing fixture " + FIXTURE));
    }

    /** Panel background sampled where no chest sits. */
    private static int[] panelBackground(BufferedImage img) {
        long r = 0, g = 0, b = 0;
        int n = 0;
        for (int x = 160; x < 175; x++) {
            int rgb = img.getRGB(x, 150);
            r += (rgb >> 16) & 0xFF;
            g += (rgb >> 8) & 0xFF;
            b += rgb & 0xFF;
            n++;
        }
        return new int[] {(int) (r / n), (int) (g / n), (int) (b / n)};
    }

    private static boolean isSprite(BufferedImage img, int x, int y, int[] bg) {
        int rgb = img.getRGB(x, y);
        int dr = Math.abs(((rgb >> 16) & 0xFF) - bg[0]);
        int dg = Math.abs(((rgb >> 8) & 0xFF) - bg[1]);
        int db = Math.abs((rgb & 0xFF) - bg[2]);
        return dr + dg + db > 90;
    }

    @Test
    void theFixtureIsANativeEmulatorFrame() throws Exception {
        BufferedImage img = frame();
        assertEquals(720, img.getWidth());
        assertEquals(1280, img.getHeight());
    }

    @Test
    void theChestTrackHasExactlySixSprites() throws Exception {
        BufferedImage img = frame();
        int[] bg = panelBackground(img);

        List<int[]> runs = new ArrayList<>();
        int[] current = null;
        for (int x = 150; x < 700; x++) {
            int hits = 0;
            for (int y = 145; y < 205; y++) {
                if (isSprite(img, x, y, bg)) hits++;
            }
            if (hits >= 12) {
                if (current == null) current = new int[] {x, x};
                else current[1] = x;
            } else {
                if (current != null && current[1] - current[0] >= 20) runs.add(current);
                current = null;
            }
        }
        if (current != null && current[1] - current[0] >= 20) runs.add(current);

        assertEquals(6, runs.size(), "the milestone track shows six chests on this frame");
    }

    @Test
    void everyChestCandidateLandsOnAChestSpriteNotInAGap() throws Exception {
        BufferedImage img = frame();
        int[] bg = panelBackground(img);

        for (int x : CHEST_X) {
            int hits = 0;
            for (int y = CHEST_Y - 20; y < CHEST_Y + 20; y++) {
                if (isSprite(img, x, y, bg)) hits++;
            }
            assertTrue(hits >= 25,
                    "candidate x=" + x + " should sit on a chest sprite, but only " + hits
                            + "/40 sampled rows differ from the panel background -- it is in a gap");
        }
    }

    @Test
    void theOldCandidatesAreProvablyInTheGaps() throws Exception {
        // Regression guard: this is the exact bug. If someone reinstates these, this fails.
        BufferedImage img = frame();
        int[] bg = panelBackground(img);

        for (int x : new int[] {245, 340, 428}) {
            int hits = 0;
            for (int y = 158; y < 198; y++) {
                if (isSprite(img, x, y, bg)) hits++;
            }
            assertTrue(hits < 25,
                    "old candidate x=" + x + " was expected to be in a gap, but hit a sprite "
                            + hits + "/40 rows -- re-measure before trusting this test");
        }
    }

    @Test
    void aCompletedAlbumsBookGlowsFarBrighterThanAnIncompleteOnes() throws Exception {
        BufferedImage img = frame();

        int ready = glowCount(img, 548, 644, 505, 590);      // Rekindled Flames 9/9
        int notReady = glowCount(img, 548, 644, 840, 925);   // Song of Heroes 7/9

        assertTrue(ready > 1400, "a completed album's book should blaze; counted " + ready);
        assertTrue(notReady < 900, "an incomplete album's book should not; counted " + notReady);
        assertTrue(ready > notReady * 2,
                "the two must be far apart to be a safe signal: ready=" + ready + " notReady=" + notReady);
    }

    @Test
    void theReadyBookThresholdSitsBetweenTheTwoMeasuredValues() throws Exception {
        BufferedImage img = frame();
        int ready = glowCount(img, 548, 644, 505, 590);
        int notReady = glowCount(img, 548, 644, 840, 925);

        int threshold = 1100; // MonumentRoutine.ALBUM_BOOK_MIN_GLOW_PX
        assertTrue(notReady < threshold && threshold < ready,
                "threshold " + threshold + " must separate " + notReady + " from " + ready);
    }

    private static int glowCount(BufferedImage img, int x0, int x1, int y0, int y1) {
        int glow = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                int hi = Math.max(r, b), lo = Math.min(r, b);
                if (hi >= 220 && g >= 170 && lo < 140) glow++;
            }
        }
        return glow;
    }

    @Test
    void theFragmentBackpackButtonSitsWhereTheRoutineTaps() throws Exception {
        BufferedImage img = frame();
        // ALBUMS_FRAGMENT_BACKPACK_BTN = (626,1197). The bottom nav is on a light wood strip; the
        // backpack icon itself is markedly darker and browner than that strip.
        int rgb = img.getRGB(626, 1197);
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        assertNotNull(img);
        assertTrue(r > g && g > b,
                "expected the warm brown backpack icon at (626,1197), got rgb(" + r + "," + g + "," + b + ")");
    }
}
