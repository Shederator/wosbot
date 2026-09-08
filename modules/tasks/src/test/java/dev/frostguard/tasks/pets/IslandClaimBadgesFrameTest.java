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
 * Real-frame coverage for My Island claim detection.
 *
 * <p>Every frame is a live 720x1280 capture taken while the routine itself was running. The two
 * wrong-screen frames are reduced to the regions under test, which also removes the account's
 * avatar, power and chat rather than masking them. The island
 * frame carries three badges - two over crafting stations and one over the tree, partly covered by
 * the game's own tutorial hand - at positions no fixed-coordinate version of this task could have
 * hit: the routine used to tap (362,488), (501,501) and (570,550) blind.
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
        // The game's tutorial hand points at a claimable badge, so a partly covered tree badge is the
        // normal case rather than a rare one. It must clear the fill floor by a real margin and not
        // by a hair: on this frame it measures 0.486, and an earlier 0.45 floor left it 0.036 of room.
        ColorBlobFinder.Blob tree = badges(island()).stream()
                .filter(blob -> blob.centre().col() < 500)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the tree badge was not detected at all"));

        assertTrue(tree.fillRatio() >= 0.48, "unexpected fill, recheck the fixture: " + tree);
        assertTrue(IslandClaimBadges.isClaimBadge(tree));
    }

    @Test
    void rejectsThePermanentGrassPatchOnTheIsland() throws Exception {
        // A 50x62 patch of island grass at 0.33 fill sits at (545,637) in every frame of this screen.
        // It is badge-sized, so width is what rules it out; if that ever stops being true the routine
        // would tap the same piece of scenery on every single run.
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
    void recognisesTheIslandScreenAndRefusesTheWorldMap() throws Exception {
        assertTrue(IslandClaimBadges.onIslandScreen(island()));
        assertFalse(IslandClaimBadges.onIslandScreen(worldMap()));
    }

    @Test
    void theWorldMapCarriesABadgeShapedIconThatOnlyTheScreenCheckRejects() throws Exception {
        // A failed navigation leaves the world map up, where the marching panel's gathering icon
        // passes every crystal rule. Tapping it starts a march, so the screen check - not the shape
        // rules - is what makes a wrong screen harmless.
        assertFalse(badges(worldMap()).isEmpty(), "the decoy must still be present for this to prove anything");
        assertFalse(IslandClaimBadges.onIslandScreen(worldMap()));
    }

    @Test
    void theCityViewCarriesADifferentBadgeShapedIconThatOnlyTheScreenCheckRejects() throws Exception {
        // The Infirmary's green cross is a second, unrelated decoy, and the city view matters more
        // than the world map because it is where the bot idles between tasks. Caught live at
        // (611,484), 36x33 at 0.58 fill.
        assertFalse(badges(cityView()).isEmpty(), "the decoy must still be present for this to prove anything");
        assertFalse(IslandClaimBadges.onIslandScreen(cityView()));
    }

    @Test
    void theHudCounterWouldPassTheShapeRulesIfTheWindowLetItThrough() throws Exception {
        // The counter in the top bar is the same artwork as a badge. Only the search window keeps it
        // from becoming a tap target, so that exclusion must never be widened past it.
        List<ColorBlobFinder.Blob> hud = ColorBlobFinder.find(
                island(), AreaData.of(0, 0, 720, 200), GameColors::isVividGreen, 400);

        assertTrue(hud.stream().anyMatch(IslandClaimBadges::isClaimBadge),
                "expected the HUD crystal to look exactly like a badge");
        assertTrue(badges(island()).stream().allMatch(blob -> blob.centre().row() > 100));
    }

    @Test
    void refusesTheBlankFrameTheGameShowsMidTransition() throws Exception {
        // Claiming triggers a screen transition whose frame is nearly blank. It carries no blobs at
        // all, so treating it as a reading would report an island with badges still on it as empty -
        // which is how a real badge was logged as "Claimed: 0" on a live run before the caller
        // learned to tell unreadable from empty.
        assertFalse(IslandClaimBadges.onIslandScreen(transition()));
        assertTrue(badges(transition()).isEmpty());
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

    private BufferedImage transition() throws Exception {
        return load("/pets/screen-transition-blank-20260823.png");
    }

    private BufferedImage load(String resource) throws Exception {
        return ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(resource)));
    }
}
