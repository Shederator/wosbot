package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class NomadicMerchantDecisionsTest {

    @Test
    void firstPassTakesAnyCardWithoutAGemIncludingAResourcePricedVip() {
        boolean[] gemOnPrice = { true, true, false, false, false, true };

        List<Integer> takes = new ArrayList<>();
        for (int slot = 0; slot < NomadicMerchantDecisions.SLOT_COUNT; slot++) {
            if (NomadicMerchantDecisions.takeIfNotGemPriced(gemOnPrice[slot])) {
                takes.add(slot);
            }
        }

        assertEquals(List.of(2, 3, 4), takes);
        assertTrue(NomadicMerchantDecisions.takeIfNotGemPriced(false));
        assertFalse(NomadicMerchantDecisions.takeIfNotGemPriced(true));
    }

    @Test
    void productTapSitsInTheCardBodyAboveThePriceStrip() {
        for (int slot = 0; slot < NomadicMerchantDecisions.SLOT_COUNT; slot++) {
            var tap = NomadicMerchantDecisions.productTap(slot);
            var priceTop = NomadicMerchantDecisions.priceTopLeft(slot);
            var productBottom = NomadicMerchantDecisions.productBottomRight(slot);

            assertTrue(tap.getY() < priceTop.getY(), "slot " + slot);
            assertTrue(tap.getY() <= productBottom.getY(), "slot " + slot);
            assertEquals(tap.getX(), NomadicMerchantDecisions.slotLeft(slot)
                    + NomadicMerchantDecisions.SLOT_WIDTH / 2);
        }
    }

    @Test
    void slotChangedFollowsTheMeanCut() {
        assertFalse(NomadicMerchantDecisions.slotChanged(11.9));
        assertTrue(NomadicMerchantDecisions.slotChanged(12.0));
    }
}
