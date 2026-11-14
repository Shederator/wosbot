package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.color.ColorBlobFinder;
import dev.frostguard.vision.color.GameColors;

/**
 * Saved-frame coverage for the crystal-blob candidate search. The island frame
 * carries three white bubbles, two over crafting stations and one over the tree
 * partly covered by the tutorial hand. The two wrong-screen frames are reduced
 * to the regions under test.
 */
class IslandClaimBadgesFrameTest {

    @Test
    void findsEveryBadgeIncludingTheOneOverTheTree() throws Exception {
        List<ColorBlobFinder.Blob> found = badges(island());

        assertEquals(3, found.size());
        assertTrue(found.stream().anyMatch(blob -> near(blob, 659, 611)), "right station");
        assertTrue(found.stream().anyMatch(blob -> near(blob, 538, 696)), "left station");
        assertTrue(found.stream().anyMatch(blob -> near(blob, 357, 349)), "tree");
    }

    @Test
    void rejectsEveryOtherGreenThingOnTheIsland() throws Exception {
        List<ColorBlobFinder.Blob> rejected = IslandClaimBadges.candidates(island()).stream()
                .filter(blob -> !IslandClaimBadges.isClaimBadge(blob))
                .toList();

        assertTrue(rejected.isEmpty(), "nothing else on the island should look like a crystal: " + rejected);
    }

    @Test
    void acceptsTheTreeBadgeWithTheTutorialHandOverIt() throws Exception {
        // The tutorial hand points at a claimable badge, so a partly covered tree badge is normal.
        // It measures 0.486 fill here and must clear the 0.38 floor by a real margin.
        ColorBlobFinder.Blob tree = badges(island()).stream()
                .filter(blob -> blob.centre().col() < 500)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the tree badge was not detected at all"));

        assertTrue(tree.fillRatio() >= 0.48, "unexpected fill, recheck the fixture: " + tree);
        assertTrue(IslandClaimBadges.isClaimBadge(tree));
    }

    @Test
    void rejectsThePermanentGrassPatchOnTheIsland() throws Exception {
        // A 50x62 patch of island grass at 0.33 fill sits at (545,637) on this island. It is
        // badge-sized, so width is what rules it out.
        assertTrue(badges(island()).stream().noneMatch(
                blob -> Math.abs(blob.centre().col() - 545) <= 25 && Math.abs(blob.centre().row() - 637) <= 25),
                "the island's grass patch must never be taken for a badge");
    }

    @Test
    void keepsTheTreeBadgeWhollyInsideTheSearchWindow() throws Exception {
        // The badge over the tree floats higher than any other and is the one the window can clip.
        // A clipped crystal loses a fifth of its pixels and shrinks toward the height floor, so every
        // badge found must still be full height.
        for (ColorBlobFinder.Blob blob : badges(island())) {
            assertTrue(blob.height() >= 50, "badge is clipped by the search window: " + blob);
        }
    }

    @Test
    void theWorldMapCarriesABadgeShapedIconThatOnlyTheScreenCheckRejects() throws Exception {
        // The marching panel's gathering icon passes every crystal rule; tapping it starts a march.
        assertFalse(badges(worldMap()).isEmpty(), "the decoy must still be present for this to prove anything");
        assertFalse(LifeEssenceIslandScreen.isConfirmed(worldMap()));
    }

    @Test
    void theCityViewCarriesADifferentBadgeShapedIconThatOnlyTheScreenCheckRejects() throws Exception {
        // The Infirmary's green cross, 36x33 at 0.58 fill.
        assertFalse(badges(cityView()).isEmpty(), "the decoy must still be present for this to prove anything");
        assertFalse(LifeEssenceIslandScreen.isConfirmed(cityView()));
    }

    @Test
    void theHudCounterWouldPassTheShapeRulesIfTheWindowLetItThrough() throws Exception {
        List<ColorBlobFinder.Blob> hud = ColorBlobFinder.find(
                island(), AreaData.of(0, 0, 720, 200), GameColors::isVividGreen, 400);

        assertTrue(hud.stream().anyMatch(IslandClaimBadges::isClaimBadge),
                "expected the HUD crystal to look exactly like a badge");
        assertTrue(badges(island()).stream().allMatch(blob -> blob.centre().row() > 100));
    }

    @Test
    void dropsARewardCrystalInFlightAndKeepsABouncingBadge() {
        ColorBlobFinder.Blob badgeBefore = blobAt(400, 500);
        ColorBlobFinder.Blob badgeAfter = blobAt(404, 502);
        ColorBlobFinder.Blob flyerBefore = blobAt(560, 300);
        ColorBlobFinder.Blob flyerAfter = blobAt(566, 260);

        List<ColorBlobFinder.Blob> settled = IslandClaimBadges.settled(
                List.of(badgeBefore, flyerBefore), List.of(badgeAfter, flyerAfter));

        assertEquals(List.of(badgeAfter), settled);
    }

    private static ColorBlobFinder.Blob blobAt(int col, int row) {
        return new ColorBlobFinder.Blob(new PointData(col, row), 1300, 40, 57, 0.59);
    }

    private List<ColorBlobFinder.Blob> badges(BufferedImage frame) {
        return IslandClaimBadges.candidates(frame).stream()
                .filter(IslandClaimBadges::isClaimBadge)
                .toList();
    }

    private static boolean near(ColorBlobFinder.Blob blob, int col, int row) {
        return Math.abs(blob.centre().col() - col) <= 5 && Math.abs(blob.centre().row() - row) <= 5;
    }

    private BufferedImage island() throws Exception {
        return load("/pets/my-island-claim-badges-20260823.png");
    }

    private BufferedImage worldMap() throws Exception {
        return load("/pets/world-map-not-island-20260823.png");
    }

    private BufferedImage cityView() throws Exception {
        return load("/pets/city-view-not-island-20260823.png");
    }

    private BufferedImage load(String resource) throws Exception {
        return ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(resource)));
    }
}
