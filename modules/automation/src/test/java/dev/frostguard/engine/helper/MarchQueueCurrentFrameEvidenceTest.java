package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;

import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.MarchResourceType;
import dev.frostguard.api.domain.MarchSlotStatus;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.color.PixelStats;
import dev.frostguard.vision.match.OpenCvPatternLocator;

class MarchQueueCurrentFrameEvidenceTest {

    private static final String FRAME = "/marchqueue/current-account-2-of-3-20260818.png";

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void exposesTwoGatheringOneIdleAndThreeLockedRows() throws IOException {
        byte[] frame = resource(FRAME);
        BufferedImage image = ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(FRAME)));

        assertMatch(frame, TemplatesEnum.MARCH_QUEUE_MEAT_ICON, CommonGameAreas.MARCH_QUEUE_ICON[0], 80);
        assertMatch(frame, TemplatesEnum.MARCH_QUEUE_WOOD_ICON, CommonGameAreas.MARCH_QUEUE_ICON[1], 80);
        assertMatch(frame, TemplatesEnum.MARCH_QUEUE_STATUS_IDLE_CURRENT, CommonGameAreas.MARCH_QUEUE_STATUS[2], 90);

        assertEquals(MarchSlotStatus.GATHERING, classify(image, 0, Duration.ofHours(2), MarchResourceType.MEAT));
        assertEquals(MarchSlotStatus.GATHERING, classify(image, 1, Duration.ofHours(2), MarchResourceType.WOOD));
        assertEquals(MarchSlotStatus.IDLE, classify(image, 2, null, null));
        assertEquals(MarchSlotStatus.LOCKED, classify(image, 3, null, null));
        assertEquals(MarchSlotStatus.LOCKED, classify(image, 4, null, null));
        assertEquals(MarchSlotStatus.LOCKED, classify(image, 5, null, null));
    }

    private MarchSlotStatus classify(BufferedImage frame, int index, Duration countdown,
                                     MarchResourceType resourceType) {
        AreaData status = CommonGameAreas.MARCH_QUEUE_STATUS[index];
        AreaData icon = CommonGameAreas.MARCH_QUEUE_ICON[index];
        int iconColour = PixelStats.count(frame, icon, rgb -> GameColors.isLabelWhite(rgb)
                || GameColors.isVividGreen(rgb)
                || GameColors.isActionOrange(rgb)
                || GameColors.isBlockedRed(rgb)
                || GameColors.isMarchQueueIconBlue(rgb));
        return MarchQueueSlotClassifier.classify(new MarchQueueSlotClassifier.Signals(
                index + 1,
                PixelStats.count(frame, status, GameColors::isActionOrange),
                PixelStats.count(frame, status, GameColors::isBlockedRed),
                PixelStats.count(frame, status, GameColors::isLabelWhite),
                PixelStats.count(frame, icon, GameColors::isVividGreen),
                false, false, false, false, false, false,
                index == 2, false, false, false, index < 2, false,
                iconColour >= 500, countdown, resourceType)).status();
    }

    private void assertMatch(byte[] frame, TemplatesEnum template, AreaData area, double threshold) {
        ImageSearchResultData result = OpenCvPatternLocator.locatePattern(
                frame, template, area.topLeft(), area.bottomRight(), threshold);
        assertTrue(result.isFound(), () -> "Expected " + template + " in " + area + ": " + result);
    }

    private byte[] resource(String path) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}
