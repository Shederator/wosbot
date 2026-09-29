package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.vision.ocr.OcrEngine;

/**
 * Saved Mystery Shop frames from 2026-09-29. No frame in this set shows a
 * free reward, so the green outline around a free card is not verified here.
 * The free-reward template itself is unchanged.
 */
class MysteryShopFrameTest {

    private static final PointData ORIGIN = new PointData(0, 0);
    private static final PointData FULL = new PointData(720, 1280);

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void discountedChestAt250IsATargetAndTheBalanceReads8350() throws Exception {
        byte[] encoded = resource("/economy/mystery-shop-20260929T165500.png");

        assertTrue(paired(encoded, TemplatesEnum.MYSTERY_SHOP_CHEST_ICON, 90),
                () -> "Expected the discounted chest on its 250 price. " + describe(encoded));
        assertFalse(paired(encoded, TemplatesEnum.MYSTERY_SHOP_MYTHIC_SHARDS_BUTTON, 95));
        assertFalse(present(encoded, TemplatesEnum.MYSTERY_SHOP_SOLD_OUT, 90));
        assertFalse(present(encoded, TemplatesEnum.MYSTERY_SHOP_FREE_REWARD, 90));
        assertTrue(present(encoded, TemplatesEnum.MYSTERY_SHOP_DAILY_REFRESH, 90));
        assertEquals(8350, balance(encoded));
    }

    @Test
    void genericShardAt250IsATargetWhenTheChestIconIsAbsent() throws Exception {
        byte[] encoded = resource("/economy/mystery-shop-20260929T152039.png");

        assertFalse(present(encoded, TemplatesEnum.MYSTERY_SHOP_CHEST_ICON, 90),
                () -> describe(encoded));
        assertTrue(paired(encoded, TemplatesEnum.MYSTERY_SHOP_MYTHIC_SHARDS_BUTTON, 95),
                () -> "Expected the generic shard on its 250 price. " + describe(encoded));
        assertFalse(present(encoded, TemplatesEnum.MYSTERY_SHOP_SOLD_OUT, 90));
        assertFalse(present(encoded, TemplatesEnum.MYSTERY_SHOP_FREE_REWARD, 90));
        assertTrue(present(encoded, TemplatesEnum.MYSTERY_SHOP_DAILY_REFRESH, 90));
        assertEquals(8350, balance(encoded));
    }

    @Test
    void similarChestWithoutA250PriceIsNotATarget() throws Exception {
        byte[] encoded = resource("/economy/mystery-shop-20260929T075647.png");

        assertTrue(present(encoded, TemplatesEnum.MYSTERY_SHOP_CHEST_ICON, 90),
                () -> describe(encoded));
        assertFalse(paired(encoded, TemplatesEnum.MYSTERY_SHOP_CHEST_ICON, 90),
                () -> describe(encoded));
        assertFalse(paired(encoded, TemplatesEnum.MYSTERY_SHOP_MYTHIC_SHARDS_BUTTON, 95));
        assertFalse(present(encoded, TemplatesEnum.MYSTERY_SHOP_FREE_REWARD, 90));
        assertTrue(present(encoded, TemplatesEnum.MYSTERY_SHOP_DAILY_REFRESH, 90));
        assertEquals(8350, balance(encoded));
    }

    @Test
    void boughtCardShowsSoldOutAndTheFreeRefreshIsGone() throws Exception {
        byte[] encoded = resource("/economy/mystery-shop-bought.png");

        assertTrue(present(encoded, TemplatesEnum.MYSTERY_SHOP_SOLD_OUT, 90),
                () -> describe(encoded));
        assertFalse(present(encoded, TemplatesEnum.MYSTERY_SHOP_DAILY_REFRESH, 90));
        assertFalse(present(encoded, TemplatesEnum.MYSTERY_SHOP_FREE_REWARD, 90));
        assertFalse(paired(encoded, TemplatesEnum.MYSTERY_SHOP_CHEST_ICON, 90));
        assertEquals(8300, balance(encoded));
    }

    private static boolean paired(byte[] encoded, TemplatesEnum icon, int iconThreshold) {
        for (ImageSearchResultData candidate : hits(encoded, icon, iconThreshold)) {
            ImageSearchResultData price = OpenCvPatternLocator.locatePattern(
                    encoded,
                    TemplatesEnum.MYSTERY_SHOP_250_BADGES_BUTTON,
                    MysteryShopDecisions.priceWindowTopLeft(candidate.getX(), candidate.getY()),
                    MysteryShopDecisions.priceWindowBottomRight(candidate.getX(), candidate.getY()),
                    95);
            if (price.isFound() && MysteryShopDecisions.priceOnSameCard(
                    candidate.getX(), candidate.getY(), price.getX(), price.getY())) {
                return true;
            }
        }
        return false;
    }

    private static boolean present(byte[] encoded, TemplatesEnum template, int threshold) {
        return !hits(encoded, template, threshold).isEmpty();
    }

    private static List<ImageSearchResultData> hits(byte[] encoded, TemplatesEnum template, int threshold) {
        return OpenCvPatternLocator.locateAllPatterns(encoded, template, ORIGIN, FULL, threshold, 9)
                .stream()
                .filter(ImageSearchResultData::isFound)
                .toList();
    }

    private static Integer balance(byte[] encoded) throws Exception {
        RawImageData frame = rgbaFrame(ImageIO.read(new ByteArrayInputStream(encoded)));
        String raw = OcrEngine.recognizeText(
                frame,
                MysteryShopRoutine.BADGE_BALANCE_TOP_LEFT,
                MysteryShopRoutine.BADGE_BALANCE_BOTTOM_RIGHT,
                CommonOCRSettings.MYSTERY_BADGE_BALANCE_SETTINGS);
        return MysteryShopDecisions.parseBalance(raw);
    }

    private static String describe(byte[] encoded) {
        ImageSearchResultData chest = OpenCvPatternLocator.locatePattern(
                encoded, TemplatesEnum.MYSTERY_SHOP_CHEST_ICON, ORIGIN, FULL, 0);
        ImageSearchResultData price = OpenCvPatternLocator.locatePattern(
                encoded, TemplatesEnum.MYSTERY_SHOP_250_BADGES_BUTTON, ORIGIN, FULL, 0);
        ImageSearchResultData shard = OpenCvPatternLocator.locatePattern(
                encoded, TemplatesEnum.MYSTERY_SHOP_MYTHIC_SHARDS_BUTTON, ORIGIN, FULL, 0);
        ImageSearchResultData sold = OpenCvPatternLocator.locatePattern(
                encoded, TemplatesEnum.MYSTERY_SHOP_SOLD_OUT, ORIGIN, FULL, 0);
        return "chest " + chest + "; price " + price + "; shard " + shard + "; sold " + sold;
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream stream = MysteryShopFrameTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing " + path).readAllBytes();
        }
    }

    private static RawImageData rgbaFrame(BufferedImage image) {
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
}
