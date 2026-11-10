package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class LifeEssenceIslandScreenTest {

    @Test
    void confirmsEverySavedIslandFrame() throws IOException {
        for (String resource : List.of(
                "/live-regressions-20260818/life-essence-claim.png",
                "/live-regressions-20260922/life-essence-available-marker.png",
                "/live-regressions-20260923/life-essence-partially-filled.png",
                "/pets/my-island-claim-badges-20260823.png")) {
            assertTrue(LifeEssenceIslandScreen.isConfirmed(load(resource)), resource);
        }
    }

    @Test
    void rejectsScreensTheClaimRoutineCanLandOnInstead() throws IOException {
        for (String resource : List.of(
                "/pets/world-map-not-island-20260823.png",
                "/pets/city-view-not-island-20260823.png",
                "/live-regressions-20260818/life-essence-caring.png",
                "/alliance/tech-battle-recommendation-20260810.png",
                "/pets/map-occupied-and-idle.png")) {
            assertFalse(LifeEssenceIslandScreen.isConfirmed(load(resource)), resource);
        }
    }

    @Test
    void rejectsTheBlankFrameShownMidTransition() throws IOException {
        // This frame has no markers, so without the counter check it reads as an empty island.
        BufferedImage blank = load("/pets/screen-transition-blank-20260823.png");
        assertFalse(LifeEssenceIslandScreen.isConfirmed(blank));
        assertTrue(LifeEssenceMarkerDetector.locate(blank).isEmpty());
    }

    private BufferedImage load(String resource) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Missing fixture " + resource);
            }
            return ImageIO.read(in);
        }
    }
}
