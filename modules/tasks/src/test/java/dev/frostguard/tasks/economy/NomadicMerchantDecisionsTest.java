package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class NomadicMerchantDecisionsTest {

    @Test
    void takesNonVipCardsWhenThePriceIsNotGemsThenLeavesVipForTheSecondPass() {
        boolean[] gemOnPrice = { true, true, false, false, false, true };
        boolean[] vipOnProduct = { true, false, false, false, false, false };

        List<Integer> takes = new ArrayList<>();
        for (int slot = 0; slot < NomadicMerchantDecisions.SLOT_COUNT; slot++) {
            if (NomadicMerchantDecisions.takeNonVip(gemOnPrice[slot], vipOnProduct[slot])) {
                takes.add(slot);
            }
        }

        assertEquals(List.of(2, 3, 4), takes);
        assertFalse(NomadicMerchantDecisions.takeNonVip(false, true));
        assertTrue(vipOnProduct[0]);
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
