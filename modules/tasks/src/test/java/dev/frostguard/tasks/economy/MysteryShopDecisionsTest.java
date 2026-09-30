package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.tasks.economy.MysteryShopDecisions.RefreshChoice;

class MysteryShopDecisionsTest {

    private static final LocalDateTime RESET = LocalDateTime.of(2026, 9, 29, 2, 0);

    @Test
    void parsesBadgeCountersAndRejectsUnreadableText() {
        assertEquals(8350, MysteryShopDecisions.parseBalance("8,350"));
        assertEquals(8350, MysteryShopDecisions.parseBalance("8350"));
        assertEquals(8350, MysteryShopDecisions.parseBalance("8 350"));
        assertEquals(0, MysteryShopDecisions.parseBalance("0"));
        assertNull(MysteryShopDecisions.parseBalance(""));
        assertNull(MysteryShopDecisions.parseBalance("abc"));
        assertNull(MysteryShopDecisions.parseBalance(null));
        assertNull(MysteryShopDecisions.parseBalance("1000000"));
    }

    @Test
    void freeRewardCostsNothingAndAChestCosts250() {
        assertTrue(MysteryShopDecisions.canBuy(null, 0));
        assertTrue(MysteryShopDecisions.canBuy(0, 0));
        assertFalse(MysteryShopDecisions.canBuy(null, MysteryShopDecisions.BADGE_PRICE));
        assertFalse(MysteryShopDecisions.isAffordable(249));
        assertTrue(MysteryShopDecisions.isAffordable(250));
        assertEquals(8100, MysteryShopDecisions.afterPurchase(8350));
        assertTrue(MysteryShopDecisions.isChest250(true, true));
        assertFalse(MysteryShopDecisions.isChest250(true, false));
        assertTrue(MysteryShopDecisions.isShard250(true, true));
        assertFalse(MysteryShopDecisions.isShard250(false, true));
        assertTrue(MysteryShopDecisions.isFreeReward(true));
        assertTrue(MysteryShopDecisions.isSoldOut(true));
        assertFalse(MysteryShopDecisions.isFreeRefresh(false));
    }

    @Test
    void pairsTheMeasuredChestIconWithIts250Price() {
        assertTrue(MysteryShopDecisions.priceOnSameCard(371, 734, 363, 859));
        assertFalse(MysteryShopDecisions.priceOnSameCard(371, 734, 363 + 200, 859));
        assertEquals(291, MysteryShopDecisions.priceWindowTopLeft(371, 734).getX());
        assertEquals(824, MysteryShopDecisions.priceWindowTopLeft(371, 734).getY());
        assertEquals(451, MysteryShopDecisions.priceWindowBottomRight(371, 734).getX());
        assertEquals(899, MysteryShopDecisions.priceWindowBottomRight(371, 734).getY());
    }

    @Test
    void keepsAnUnaffordableTargetOnScreen() {
        assertEquals(RefreshChoice.WAIT_FOR_BADGES, MysteryShopDecisions.choose(true, true));
        assertEquals(RefreshChoice.WAIT_FOR_BADGES, MysteryShopDecisions.choose(true, false));
    }

    @Test
    void refreshesAsSoonAsNoTargetRemainsAndAFreeRefreshIsVisible() {
        assertEquals(RefreshChoice.REFRESH, MysteryShopDecisions.choose(false, true));
        assertEquals(RefreshChoice.DAY_COMPLETE, MysteryShopDecisions.choose(false, false));
    }

    @Test
    void schedulesEarningAndUnconfirmedVisitsBeforeTheReset() {
        LocalDateTime evening = LocalDateTime.of(2026, 9, 28, 23, 0);
        LocalDateTime morning = LocalDateTime.of(2026, 9, 28, 10, 0);
        LocalDateTime late = LocalDateTime.of(2026, 9, 29, 1, 30);
        LocalDateTime lastWindow = LocalDateTime.of(2026, 9, 29, 1, 58);

        assertEquals(LocalDateTime.of(2026, 9, 29, 1, 55),
                MysteryShopDecisions.earnMoneyVisit(evening, RESET));
        assertEquals(LocalDateTime.of(2026, 9, 28, 14, 0),
                MysteryShopDecisions.earnMoneyVisit(morning, RESET));
        assertEquals(morning.plusMinutes(5),
                MysteryShopDecisions.unconfirmedVisit(morning, RESET, false));
        assertEquals(LocalDateTime.of(2026, 9, 29, 0, 30),
                MysteryShopDecisions.unconfirmedVisit(evening.plusMinutes(30), RESET, true));
        assertEquals(LocalDateTime.of(2026, 9, 29, 1, 55),
                MysteryShopDecisions.unconfirmedVisit(late, RESET, true));
        assertEquals(RESET.plusMinutes(1),
                MysteryShopDecisions.unconfirmedVisit(lastWindow, RESET, false));
        assertEquals(RESET.plusMinutes(1), MysteryShopDecisions.dayCompleteVisit(RESET));
        assertFalse(MysteryShopDecisions.repeatUnconfirmed(0));
        assertTrue(MysteryShopDecisions.repeatUnconfirmed(1));
    }

    @Test
    void gridFingerprintIgnoresAnIdenticalFrameAndRejectsARepaint() {
        RawImageData calm = solid(4, 4, 32, (byte) 10);
        RawImageData same = solid(4, 4, 32, (byte) 10);
        RawImageData repainted = solid(4, 4, 32, (byte) 200);

        assertEquals(0.0, MysteryShopDecisions.meanChannelDelta(calm, same, 0, 0, 3, 3, 1));
        assertTrue(MysteryShopDecisions.meanChannelDelta(calm, repainted, 0, 0, 3, 3, 1)
                >= MysteryShopDecisions.GRID_CHANGE_MEAN);
        assertEquals(-1.0, MysteryShopDecisions.meanChannelDelta(calm, solid(2, 2, 32, (byte) 10),
                0, 0, 3, 3, 1));
        assertFalse(MysteryShopDecisions.gridChanged(-1));
        assertFalse(MysteryShopDecisions.gridChanged(11.9));
        assertTrue(MysteryShopDecisions.gridChanged(12));
    }

    private static RawImageData solid(int width, int height, int bpp, byte channel) {
        byte[] pixels = new byte[width * height * 4];
        for (int index = 0; index < pixels.length; index += 4) {
            pixels[index] = channel;
            pixels[index + 1] = channel;
            pixels[index + 2] = channel;
            pixels[index + 3] = (byte) 255;
        }
        return RawImageData.capture(pixels, width, height, bpp);
    }
}
