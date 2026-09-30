package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * Saved Nomadic Merchant grids from 2026-09-30. The non-VIP pass takes a
 * card when its price strip has no gem and the product is not VIP.
 */
class NomadicMerchantFrameTest {

    private static final PointData SCREEN_ORIGIN = new PointData(0, 0);
    private static final PointData SCREEN_LIMIT = new PointData(720, 1280);

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded the native library.
        }
    }

    @Test
    void typicalGridTakesResourcePricedNonVipThenLeavesVip() throws IOException {
        byte[] encoded = encoded("nomadic-grid-vip-resource.png");
        List<Integer> takes = takes(encoded);

        assertEquals(List.of(2, 3, 4), takes, () -> describe(encoded));
        assertTrue(vipInSlot(encoded, 0), () -> describe(encoded));
        assertFalse(NomadicMerchantDecisions.takeNonVip(gemInSlot(encoded, 0), true));
    }

    @Test
    void bottomRightVipIsLeftForTheVipPass() throws IOException {
        byte[] encoded = encoded("nomadic-grid-vip-bottom.png");
        List<Integer> takes = takes(encoded);

        assertEquals(List.of(4), takes, () -> describe(encoded));
        assertTrue(vipInSlot(encoded, 5), () -> describe(encoded));
    }

    @Test
    void gemPricedAcceleratorsAreSkippedAndResourcePricedPilesAreTaken() throws IOException {
        byte[] encoded = encoded("nomadic-grid-accelerators.png");
        List<Integer> takes = takes(encoded);

        assertEquals(List.of(2, 5), takes, () -> describe(encoded));
        assertFalse(vipInSlot(encoded, 0));
    }

    private List<Integer> takes(byte[] encoded) {
        List<Integer> takes = new ArrayList<>();
        for (int slot = 0; slot < NomadicMerchantDecisions.SLOT_COUNT; slot++) {
            boolean gem = gemInSlot(encoded, slot);
            boolean vip = vipInSlot(encoded, slot);
            if (NomadicMerchantDecisions.takeNonVip(gem, vip)) {
                takes.add(slot);
            }
        }
        return takes;
    }

    private boolean gemInSlot(byte[] encoded, int slot) {
        return present(encoded, TemplatesEnum.NOMADIC_MERCHANT_GEM_PRICE,
                NomadicMerchantDecisions.priceTopLeft(slot),
                NomadicMerchantDecisions.priceBottomRight(slot),
                NomadicMerchantRoutine.GEM_PRICE_THRESHOLD);
    }

    private boolean vipInSlot(byte[] encoded, int slot) {
        return present(encoded, TemplatesEnum.NOMADIC_MERCHANT_VIP,
                NomadicMerchantDecisions.productTopLeft(slot),
                NomadicMerchantDecisions.productBottomRight(slot),
                90);
    }

    private boolean present(byte[] encoded, TemplatesEnum template, PointData topLeft,
            PointData bottomRight, double threshold) {
        ImageSearchResultData hit = OpenCvPatternLocator.locatePattern(
                encoded, template, topLeft, bottomRight, threshold);
        return hit != null && hit.isFound();
    }

    private String describe(byte[] encoded) {
        StringBuilder text = new StringBuilder();
        for (int slot = 0; slot < NomadicMerchantDecisions.SLOT_COUNT; slot++) {
            text.append("slot ").append(slot)
                    .append(" gem=").append(gemInSlot(encoded, slot))
                    .append(" vip=").append(vipInSlot(encoded, slot))
                    .append("; ");
        }
        return text.toString();
    }

    private byte[] encoded(String frame) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/economy/" + frame)) {
            return Objects.requireNonNull(stream, "Missing nomadic frame: " + frame).readAllBytes();
        }
    }
}
