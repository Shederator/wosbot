package dev.frostguard.tasks.pets;

import java.awt.image.BufferedImage;
import java.util.List;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.vision.color.ColorBlobFinder;
import dev.frostguard.vision.color.GameColors;

/**
 * Candidate claim-marker search that identifies the green Life Essence crystal
 * by size and fill, whatever colour its bubble has. It is selectable in the
 * detection tool for comparison with {@link LifeEssenceMarkerDetector}; the task
 * does not use it.
 *
 * <p>The orange-region detector only accepts a fully orange bubble. On the
 * 2026-08-23 island frame all three bubbles are white with a small orange tail,
 * and that run claimed all three. This search finds them; it misses the larger
 * 50 x 70-72 crystals on the 2026-08-18 frame, which exceed its pixel-sized
 * window.
 *
 * <p>The crystal covers 913-1476 green pixels in a 35-42 x 37-59 box on the
 * frames it was calibrated on, the height varying because the bubble bounces.
 * Width carries the identification: a permanent 50x62 grass patch on that
 * island at 0.33 fill is otherwise badge-sized. The fill floor stays low enough
 * for a tree badge partly covered by the tutorial hand, measured at 0.486.
 * Shape alone does not reject off-island decoys such as the world map's
 * gathering icon or the city Infirmary cross; {@link LifeEssenceIslandScreen}
 * does.
 */
final class IslandClaimBadges {

    private static final int MIN_CRYSTAL_PIXELS = 600;
    private static final int CRYSTAL_MIN_WIDTH = 28;
    private static final int CRYSTAL_MAX_WIDTH = 46;
    private static final int CRYSTAL_MIN_HEIGHT = 30;
    private static final int CRYSTAL_MAX_HEIGHT = 70;
    private static final double CRYSTAL_MIN_FILL_RATIO = 0.38;
    private static final int SETTLED_RADIUS = 15;

    private IslandClaimBadges() {
    }

    static List<PointData> locate(BufferedImage frame) {
        return candidates(frame).stream()
                .filter(IslandClaimBadges::isClaimBadge)
                .map(ColorBlobFinder.Blob::centre)
                .toList();
    }

    /** Every green blob in the island window large enough to judge, largest first. */
    static List<ColorBlobFinder.Blob> candidates(BufferedImage frame) {
        return ColorBlobFinder.find(frame, CommonGameAreas.ISLAND_CLAIM_BADGE_AREA,
                GameColors::isVividGreen, MIN_CRYSTAL_PIXELS);
    }

    static boolean isClaimBadge(ColorBlobFinder.Blob blob) {
        return blob.width() >= CRYSTAL_MIN_WIDTH && blob.width() <= CRYSTAL_MAX_WIDTH
                && blob.height() >= CRYSTAL_MIN_HEIGHT && blob.height() <= CRYSTAL_MAX_HEIGHT
                && blob.fillRatio() >= CRYSTAL_MIN_FILL_RATIO;
    }

    /**
     * Keeps the badges that held still between two captures.
     *
     * <p>A claimed reward crystal flies from its badge to the counter without a
     * bubble, so a single capture taken mid-flight would show a badge-shaped
     * blob in empty sky. A badge bounces at most 8px between captures 700ms
     * apart; a reward crystal covers roughly 30px. A flying crystal has no orange
     * bubble, so the orange-region detector should not see it, but no saved
     * mid-flight frame confirms that. This check is kept for the two-capture
     * comparison and is not wired into the task.
     */
    static List<ColorBlobFinder.Blob> settled(List<ColorBlobFinder.Blob> earlier,
            List<ColorBlobFinder.Blob> later) {
        List<ColorBlobFinder.Blob> earlierBadges = earlier.stream().filter(IslandClaimBadges::isClaimBadge).toList();
        return later.stream()
                .filter(IslandClaimBadges::isClaimBadge)
                .filter(blob -> earlierBadges.stream().anyMatch(
                        before -> before.centre().manhattanDistanceTo(blob.centre()) <= SETTLED_RADIUS))
                .toList();
    }
}
