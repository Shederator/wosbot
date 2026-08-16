package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.api.domain.ResearchBadgeData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.vision.ocr.ResearchBadgeReader;
import dev.frostguard.vision.ocr.OcrEngine;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import javax.imageio.ImageIO;
import dev.frostguard.vision.ocr.OcrException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ResearchBadgeReaderFrameTest {

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another saved-frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void readsProgressFromARealResearchBadge() throws IOException, OcrException {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(getClass()
                .getResourceAsStream("/research/resource-shortfall-20260717.png")));

        var badges = ResearchBadgeReader.read(
                rgbaFrame(image), new PointData(80, 300), new PointData(210, 480));

        assertTrue(badges.stream().anyMatch(badge ->
                        badge.currentLevel() == 2 && badge.maximumLevel() == 3),
                "The real Food Gathering I detail should expose its 2/3 badge.");
    }

    @Test
    void readsEveryIncompleteBadgeFromARealBattleTree() throws IOException, OcrException {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(getClass()
                .getResourceAsStream("/research/battle-progress-badges-20260718.png")));

        var badges = ResearchBadgeReader.read(
                rgbaFrame(image), new PointData(0, 0),
                new PointData(image.getWidth(), image.getHeight()));

        assertEquals(5, badges.size());
        assertEquals(2, badges.stream()
                .filter(badge -> badge.currentLevel() == 1 && badge.maximumLevel() == 4)
                .count());
        assertEquals(1, badges.stream()
                .filter(badge -> badge.currentLevel() == 2 && badge.maximumLevel() == 4)
                .count());
        assertEquals(2, badges.stream()
                .filter(badge -> badge.currentLevel() == 0 && badge.maximumLevel() == 4)
                .count());
    }

    @Test
    void readsOneOfThreeBadgesAfterCompletedResearch() throws IOException, OcrException {
        var badges = readTreeFrame("/research/help-completed-20260716.png");

        assertEquals(3, badges.size());
        assertEquals(2, badges.stream()
                .filter(badge -> badge.currentLevel() == 1 && badge.maximumLevel() == 3)
                .count());
        assertEquals(1, badges.stream()
                .filter(badge -> badge.currentLevel() == 0 && badge.maximumLevel() == 3)
                .count());
    }

    @Test
    void readsMixedBadgesWhileResearchHelpIsVisible() throws IOException, OcrException {
        var badges = readTreeFrame("/research/help-short-20260716.png");

        assertEquals(3, badges.size());
        assertEquals(1, badges.stream()
                .filter(badge -> badge.currentLevel() == 1 && badge.maximumLevel() == 3)
                .count());
        assertEquals(2, badges.stream()
                .filter(badge -> badge.currentLevel() == 0 && badge.maximumLevel() == 3)
                .count());
    }

    @Test
    void recoversEveryOneOfFiveBadgeFromTheLiveBattleTree()
            throws IOException, OcrException {
        var badges = readTreeFrame("/research/battle-progress-badges-5-20260718.png");

        assertEquals(4, badges.size());
        assertEquals(3, badges.stream()
                .filter(badge -> badge.currentLevel() == 1 && badge.maximumLevel() == 5)
                .count());
        assertEquals(1, badges.stream()
                .filter(badge -> badge.currentLevel() == 4 && badge.maximumLevel() == 5)
                .count());
    }

    @Test
    void readsEverySixLevelBadgeFromTheLiveBattleTree()
            throws IOException, OcrException {
        var badges = readTreeFrame("/research/battle-progress-badges-6-20260720.png");

        assertEquals(7, badges.size());
        assertEquals(5, badges.stream()
                .filter(badge -> badge.currentLevel() == 0 && badge.maximumLevel() == 6)
                .count());
        assertEquals(1, badges.stream()
                .filter(badge -> badge.currentLevel() == 1 && badge.maximumLevel() == 6)
                .count());
        assertEquals(1, badges.stream()
                .filter(badge -> badge.currentLevel() == 3 && badge.maximumLevel() == 6)
                .count());
    }

    @Test
    void doesNotInventResearchBadgesOnAnUnrelatedGameScreen()
            throws IOException, OcrException {
        var badges = readTreeFrame("/dailymission/selected-daily-claims-20260716.png");

        assertTrue(badges.isEmpty());
    }

    @Test
    void researchTitleOcrVerifiesTheTreeAndRejectsAnUnrelatedScreen()
            throws IOException, OcrException {
        OcrSettingsData settings = OcrSettingsData.assembler()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
                .build();

        String researchTitle = readTitle("/research/battle-progress-badges-6-20260720.png", settings);
        String unrelatedTitle = readTitle("/dailymission/selected-daily-claims-20260716.png", settings);

        assertTrue(researchTitle.toLowerCase().contains("research"),
                "The real Research tree title should be recognized: " + researchTitle);
        assertFalse(unrelatedTitle.toLowerCase().contains("research"),
                "An unrelated screen must not pass the Research tree gate: " + unrelatedTitle);
    }

    private static String readTitle(String resource, OcrSettingsData settings)
            throws IOException, OcrException {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(
                ResearchBadgeReaderFrameTest.class.getResourceAsStream(resource)));
        return OcrEngine.recognizeText(
                rgbaFrame(image), new PointData(80, 0), new PointData(360, 80), settings).trim();
    }

    private static List<ResearchBadgeData> readTreeFrame(
            String resource) throws IOException, OcrException {
        BufferedImage image = ImageIO.read(Objects.requireNonNull(
                ResearchBadgeReaderFrameTest.class.getResourceAsStream(resource)));
        return ResearchBadgeReader.read(
                rgbaFrame(image), new PointData(0, 150), new PointData(720, 1180));
    }

    private static RawImageData rgbaFrame(BufferedImage image) {
        byte[] rgba = new byte[image.getWidth() * image.getHeight() * 4];
        int offset = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                rgba[offset++] = (byte) ((argb >> 16) & 0xff);
                rgba[offset++] = (byte) ((argb >> 8) & 0xff);
                rgba[offset++] = (byte) (argb & 0xff);
                rgba[offset++] = (byte) ((argb >> 24) & 0xff);
            }
        }
        return RawImageData.capture(rgba, image.getWidth(), image.getHeight(), 32);
    }
}
