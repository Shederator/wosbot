package dev.frostguard.engine.helper;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.nav.RallyFlagCoordinates;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.color.PixelStats;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import static dev.frostguard.engine.helper.FormationSlotStateClassifier.State.EMPTY_OR_MISSING;
import static dev.frostguard.engine.helper.FormationSlotStateClassifier.State.LOCKED;
import static dev.frostguard.engine.helper.FormationSlotStateClassifier.State.SAVED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormationSlotStateClassifierFrameTest {

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded the native library in this JVM.
        }
    }

    @Test
    void distinguishesSavedSlotNineAtRightEndFromEmptyPartialInitialSlot() throws IOException {
        BufferedImage initial = loadFrame("/formations/formation-slot-9-empty.png");
        BufferedImage rightEnd = loadFrame("/formations/formation-slots-right-end.png");
        int savedWhitePixels = whitePixels(rightEnd, 9);
        int emptyWhitePixels = PixelStats.count(initial, AreaData.of(629, 92, 683, 150),
                GameColors::isLabelWhite);

        assertEquals(SAVED, FormationSlotStateClassifier.classify(false, savedWhitePixels));
        assertEquals(EMPTY_OR_MISSING, FormationSlotStateClassifier.classify(false, emptyWhitePixels));
        assertTrue(savedWhitePixels > emptyWhitePixels * 4,
                () -> "Expected saved flag evidence to dominate the empty slot: saved="
                        + savedWhitePixels + ", empty=" + emptyWhitePixels);
    }

    @Test
    void padlockEvidenceAlwaysFailsClosed() {
        assertEquals(LOCKED, FormationSlotStateClassifier.classify(true, 1_000));
    }

    @Test
    void detectsLockedSlotsTenThroughTwelveInRealRightEndFrame() throws IOException {
        List<ImageSearchResultData> padlocks = OpenCvPatternLocator.locateAllPatterns(
                resource("/formations/formation-slots-right-end.png"),
                "/templates/rally/rallyLockedFlagSlot.png",
                new PointData(0, 88), new PointData(700, 158), 85, 12);

        assertEquals(3, padlocks.size());
        assertTrue(padlocks.stream().anyMatch(hit -> near(hit, 409)));
        assertTrue(padlocks.stream().anyMatch(hit -> near(hit, 482)));
        assertTrue(padlocks.stream().anyMatch(hit -> near(hit, 556)));
    }

    private BufferedImage loadFrame(String path) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Missing formation slot frame: " + path);
            }
            return ImageIO.read(stream);
        }
    }

    private int whitePixels(BufferedImage frame, int slot) {
        return PixelStats.count(frame, RallyFlagCoordinates.areaForFlag(slot), GameColors::isLabelWhite);
    }

    private byte[] resource(String path) throws IOException {
        try (var stream = getClass().getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }

    private boolean near(ImageSearchResultData hit, int x) {
        return Math.abs(hit.getPoint().getX() - x) <= 35;
    }
}
