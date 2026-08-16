package dev.frostguard.tasks.combat;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.vision.color.GameColors;
import dev.frostguard.vision.color.PixelStats;
import dev.frostguard.vision.ocr.OcrEngine;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaRoutinePowerFrameTest {

    private static final int FIRST_OPPONENT_Y = 376;
    private static final int OPPONENT_SPACING = 128;

    @Test
    void readsAndRanksWeakerOpponentsFromAnonymizedServerRowFrame() throws Exception {
        BufferedImage image = loadFrame();
        RawImageData frame = rgbaFrame(image);

        assertEquals(2_800_000D, readPower(frame, 1));
        assertRedPower(image, 2);
        List<FrameOpponent> weaker = new ArrayList<>(List.of(
                new FrameOpponent(1, readPower(frame, 1), false),
                new FrameOpponent(3, readPower(frame, 3), true),
                new FrameOpponent(4, readPower(frame, 4), false),
                new FrameOpponent(5, readPower(frame, 5), false)));

        FrameOpponent selected = weaker.stream()
                .filter(opponent -> !opponent.profileAlliance())
                .min(Comparator.comparing(FrameOpponent::power, ArenaRoutine::comparePowerValues))
                .orElseThrow();

        assertEquals(5, selected.number());
        assertEquals(2_400_000D, selected.power());
    }

    @Test
    void readsProfileAlliancePowerWithoutMakingItEligibleInRankingFixture() throws Exception {
        RawImageData frame = rgbaFrame(loadFrame());

        assertEquals(3_000_000D, readPower(frame, 3));
    }

    @Test
    void rejectsClippedAndImplausiblyUndelimitedMagnitudeReads() {
        assertNull(ArenaRoutine.parsePowerValue("1,"));
        assertNull(ArenaRoutine.parsePowerValue("1"));
        assertNull(ArenaRoutine.parsePowerValue("24M"));
        assertNull(ArenaRoutine.parsePowerValue("B"));
        assertEquals(2_400_000D, ArenaRoutine.parsePowerValue("2.4M"));

        List<Double> values = new ArrayList<>(Arrays.asList(null, 2_800_000D, 2_400_000D));
        values.sort(ArenaRoutine::comparePowerValues);
        assertEquals(Arrays.asList(2_400_000D, 2_800_000D, null), values);
    }

    private Double readPower(RawImageData frame, int opponentNumber) throws Exception {
        AreaData area = ArenaRoutine.serverPowerTextArea(opponentY(opponentNumber));
        String text = OcrEngine.recognizeText(
                frame, area.topLeft(), area.bottomRight(), ArenaRoutine.powerOcrSettings());
        return ArenaRoutine.parsePowerValue(text);
    }

    private void assertRedPower(BufferedImage image, int opponentNumber) {
        AreaData area = ArenaRoutine.serverPowerTextArea(opponentY(opponentNumber));
        int green = PixelStats.count(image, area, GameColors::isArenaPowerGreen);
        int red = PixelStats.count(image, area, GameColors::isArenaPowerRed);
        assertTrue(red > green * 1.5, () -> "Expected red power dominance but got green=" + green + ", red=" + red);
    }

    private int opponentY(int opponentNumber) {
        return FIRST_OPPONENT_Y + ((opponentNumber - 1) * OPPONENT_SPACING);
    }

    private BufferedImage loadFrame() throws Exception {
        return ImageIO.read(Objects.requireNonNull(getClass()
                .getResourceAsStream("/arena/server-row-opponents-anonymized.png")));
    }

    private RawImageData rgbaFrame(BufferedImage image) {
        byte[] rgba = new byte[image.getWidth() * image.getHeight() * 4];
        int offset = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                rgba[offset++] = (byte) ((rgb >> 16) & 0xFF);
                rgba[offset++] = (byte) ((rgb >> 8) & 0xFF);
                rgba[offset++] = (byte) (rgb & 0xFF);
                rgba[offset++] = (byte) 0xFF;
            }
        }
        return RawImageData.capture(rgba, image.getWidth(), image.getHeight(), 32);
    }

    private record FrameOpponent(int number, Double power, boolean profileAlliance) {}
}
