package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.IntPredicate;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.MarchActivityType;
import dev.frostguard.api.domain.MarchCountdownKind;
import dev.frostguard.api.domain.MarchMovementPhase;
import dev.frostguard.api.domain.MarchResourceType;
import dev.frostguard.api.domain.MarchSlotReleaseConfidence;
import dev.frostguard.api.domain.MarchSlotState;
import dev.frostguard.api.domain.MarchSlotStatus;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.color.PixelStats;

class MarchQueueSlotClassifierFrameTest {

    private static final int CROP_TOP = 300;
    private static final int[] ROW_Y = { 375, 448, 521, 594, 667, 740 };

    @Test
    void classifiesRealMixedMarchQueueFrameConservatively() throws IOException {
        BufferedImage frame = ImageIO.read(Objects.requireNonNull(getClass()
                .getResourceAsStream("/marchqueue/mixed-march-queue-20260709.png")));

        MarchSlotState encamped = classify(frame, 0, null, null);
        MarchSlotState meatOutbound = classify(frame, 1, Duration.ofSeconds(59), MarchResourceType.MEAT);
        MarchSlotState attack = classify(frame, 2, Duration.ofSeconds(113), null);
        MarchSlotState rally = classify(frame, 3, Duration.ofSeconds(105), null);
        MarchSlotState woodGathering = classify(frame, 4, Duration.ofSeconds(61), MarchResourceType.WOOD);
        MarchSlotState locked = classify(frame, 5, null, null);

        assertEquals(MarchSlotStatus.STATIONED, encamped.status());
        assertEquals(MarchActivityType.ENCAMPMENT, encamped.activityType());
        assertEquals(MarchMovementPhase.STATIONED, encamped.movementPhase());
        assertEquals(MarchSlotStatus.GATHERING, meatOutbound.status());
        assertEquals(MarchActivityType.GATHER, meatOutbound.activityType());
        assertEquals(MarchResourceType.MEAT, meatOutbound.resourceType());
        assertEquals(MarchSlotStatus.BUSY_UNKNOWN, attack.status());
        assertEquals(MarchActivityType.ATTACK, attack.activityType());
        assertEquals(MarchMovementPhase.OUTBOUND, attack.movementPhase());
        assertEquals(MarchCountdownKind.ARRIVAL, attack.countdownKind());
        assertEquals(MarchSlotReleaseConfidence.LOWER_BOUND, attack.releaseConfidence());
        assertEquals(MarchSlotStatus.BUSY_UNKNOWN, rally.status());
        assertEquals(MarchActivityType.RALLY, rally.activityType());
        assertEquals(MarchMovementPhase.PREPARING, rally.movementPhase());
        assertEquals(MarchCountdownKind.RALLY_START, rally.countdownKind());
        assertEquals(MarchSlotReleaseConfidence.LOWER_BOUND, rally.releaseConfidence());
        assertEquals(MarchSlotStatus.GATHERING, woodGathering.status());
        assertEquals(MarchMovementPhase.WORKING, woodGathering.movementPhase());
        assertEquals(MarchCountdownKind.WORK_REMAINING, woodGathering.countdownKind());
        assertEquals(MarchResourceType.WOOD, woodGathering.resourceType());
        assertEquals(MarchSlotStatus.LOCKED, locked.status());
    }

    @Test
    void classifiesRealCityAttackFrameAsNeutralAttack() throws IOException {
        BufferedImage frame = ImageIO.read(Objects.requireNonNull(getClass()
                .getResourceAsStream("/marchqueue/city-attack-outbound-20260709.png")));

        MarchSlotState attack = MarchQueueSlotClassifier.classify(new MarchQueueSlotClassifier.Signals(
                1,
                orangeCount(frame, 0),
                redCount(frame, 0),
                whiteCount(frame, 0),
                greenCount(frame, 0),
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                iconPresent(frame, 0),
                Duration.ofSeconds(4),
                null));

        assertEquals(MarchSlotStatus.BUSY_UNKNOWN, attack.status());
        assertEquals(MarchActivityType.ATTACK, attack.activityType());
        assertEquals(MarchMovementPhase.OUTBOUND, attack.movementPhase());
        assertEquals(MarchCountdownKind.ARRIVAL, attack.countdownKind());
        assertEquals(MarchSlotReleaseConfidence.LOWER_BOUND, attack.releaseConfidence());
    }

    @Test
    void classifiesRealGarrisonedAndReturningFrame() throws IOException {
        BufferedImage frame = ImageIO.read(Objects.requireNonNull(getClass()
                .getResourceAsStream("/marchqueue/garrisoned-returning-20260714.png")));

        MarchSlotState coalGathering = classifyGarrisonedFrame(frame, 0, Duration.ofMinutes(52).plusSeconds(54),
                MarchResourceType.COAL);
        MarchSlotState encamped = classifyGarrisonedFrame(frame, 1, null, null);
        MarchSlotState garrisoned = classifyGarrisonedFrame(frame, 2, null, null);
        MarchSlotState reinforced = classifyGarrisonedFrame(frame, 3, null, null);
        MarchSlotState returning = classifyGarrisonedFrame(frame, 4, Duration.ofMinutes(2).plusSeconds(37), null);
        MarchSlotState locked = classifyGarrisonedFrame(frame, 5, null, null);

        assertEquals(MarchActivityType.GATHER, coalGathering.activityType());
        assertEquals(MarchMovementPhase.WORKING, coalGathering.movementPhase());
        assertEquals(MarchResourceType.COAL, coalGathering.resourceType());
        assertEquals(MarchActivityType.ENCAMPMENT, encamped.activityType());
        assertEquals(MarchActivityType.GARRISONED, garrisoned.activityType());
        assertEquals(MarchMovementPhase.STATIONED, garrisoned.movementPhase());
        assertEquals(MarchActivityType.REINFORCEMENT, reinforced.activityType());
        assertEquals(MarchSlotStatus.RETURNING, returning.status());
        assertEquals(MarchCountdownKind.RETURN, returning.countdownKind());
        assertEquals(MarchSlotReleaseConfidence.EXACT, returning.releaseConfidence());
        assertEquals(MarchSlotStatus.LOCKED, locked.status());
    }

    @Test
    void fixtureContainsTheExpectedSignalShape() throws IOException {
        BufferedImage frame = ImageIO.read(Objects.requireNonNull(getClass()
                .getResourceAsStream("/marchqueue/mixed-march-queue-20260709.png")));

        assertTrue(greenCount(frame, 1) > 1_000, "meat gather row should have a green icon");
        assertEquals(0, greenCount(frame, 2), "attack row should not look like gather");
        assertEquals(0, greenCount(frame, 3), "rally row should not look like gather");
        assertTrue(greenCount(frame, 4) > 1_000, "wood gather row should have a green icon");
        assertTrue(orangeCount(frame, 5) > 250, "locked row should expose orange Unlock text");
    }

    private static MarchSlotState classify(BufferedImage frame, int index, Duration countdown,
                                           MarchResourceType resourceType) {
        return MarchQueueSlotClassifier.classify(new MarchQueueSlotClassifier.Signals(
                index + 1,
                orangeCount(frame, index),
                redCount(frame, index),
                whiteCount(frame, index),
                greenCount(frame, index),
                false,
                index == 3,
                index == 2,
                index == 0,
                false,
                false,
                false,
                false,
                false,
                index == 1,
                index == 4,
                false,
                iconPresent(frame, index),
                countdown,
                resourceType));
    }

    private static MarchSlotState classifyGarrisonedFrame(BufferedImage frame, int index, Duration countdown,
                                                          MarchResourceType resourceType) {
        return MarchQueueSlotClassifier.classify(new MarchQueueSlotClassifier.Signals(
                index + 1,
                orangeCount(frame, index),
                redCount(frame, index),
                whiteCount(frame, index),
                greenCount(frame, index),
                index == 4,
                false,
                false,
                index == 1,
                index == 3,
                index == 2,
                false,
                false,
                false,
                false,
                index == 0,
                false,
                iconPresent(frame, index),
                countdown,
                resourceType));
    }

    private static int whiteCount(BufferedImage frame, int index) {
        return countStatus(frame, index, GameColors::isLabelWhite);
    }

    private static int orangeCount(BufferedImage frame, int index) {
        return countStatus(frame, index, GameColors::isActionOrange);
    }

    private static int redCount(BufferedImage frame, int index) {
        return countStatus(frame, index, GameColors::isBlockedRed);
    }

    private static int greenCount(BufferedImage frame, int index) {
        return PixelStats.count(frame, iconArea(index), GameColors::isVividGreen);
    }

    private static boolean iconPresent(BufferedImage frame, int index) {
        return PixelStats.count(frame, iconArea(index), MarchQueueSlotClassifierFrameTest::isIconColor) >= 500;
    }

    private static int countStatus(BufferedImage frame, int index, IntPredicate predicate) {
        return PixelStats.count(frame, statusArea(index), predicate);
    }

    private static boolean isIconColor(int rgb) {
        return GameColors.isLabelWhite(rgb)
                || GameColors.isVividGreen(rgb)
                || GameColors.isActionOrange(rgb)
                || GameColors.isBlockedRed(rgb)
                || GameColors.isMarchQueueIconBlue(rgb);
    }

    private static AreaData statusArea(int index) {
        int y = ROW_Y[index] - CROP_TOP;
        return area(150, y, 300, y + 28);
    }

    private static AreaData iconArea(int index) {
        int y = ROW_Y[index] - CROP_TOP;
        return area(18, y - 27, 72, y + 27);
    }

    private static AreaData area(int x1, int y1, int x2, int y2) {
        return new AreaData(new PointData(x1, y1), new PointData(x2, y2));
    }
}
