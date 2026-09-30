package dev.frostguard.tasks.economy;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;

/**
 * Pure decisions for one Nomadic Merchant grid. The routine performs the
 * taps; this type answers which of the six cards to take on the non-VIP
 * pass, where to tap, and whether a slot changed after the tap.
 *
 * <p>Geometry was measured on the 720x1280 frames from 2026-09-30. Three
 * columns sit at x 47 / 268 / 489. The top row occupies y 420-685 and the
 * bottom row y 720-980. The price strip is the bottom 60 pixels of the
 * card. A gem on that strip is paid; its absence is a natural-resource
 * price. VIP is identified by the product icon, not by the price.</p>
 */
final class NomadicMerchantDecisions {

    static final int SLOT_COUNT = 6;
    static final int COLUMNS = 3;
    static final int SLOT_LEFT = 47;
    static final int SLOT_WIDTH = 184;
    static final int SLOT_PITCH_X = 221;
    static final int TOP_SLOT_TOP = 420;
    static final int TOP_SLOT_BOTTOM = 685;
    static final int BOTTOM_SLOT_TOP = 720;
    static final int BOTTOM_SLOT_BOTTOM = 980;
    static final int PRICE_HEIGHT = 60;

    /**
     * Mean per-channel change that counts as the slot replacing after a
     * take. Reuses the Mystery Shop grid-change cut: an untouched card
     * stays near zero.
     */
    static final double SLOT_CHANGE_MEAN = 12.0;

    private NomadicMerchantDecisions() {
    }

    static int column(int slot) {
        return slot % COLUMNS;
    }

    static int row(int slot) {
        return slot / COLUMNS;
    }

    static int slotLeft(int slot) {
        return SLOT_LEFT + column(slot) * SLOT_PITCH_X;
    }

    static int slotTop(int slot) {
        return row(slot) == 0 ? TOP_SLOT_TOP : BOTTOM_SLOT_TOP;
    }

    static int slotBottom(int slot) {
        return row(slot) == 0 ? TOP_SLOT_BOTTOM : BOTTOM_SLOT_BOTTOM;
    }

    static PointData priceTopLeft(int slot) {
        return new PointData(slotLeft(slot), slotBottom(slot) - PRICE_HEIGHT);
    }

    static PointData priceBottomRight(int slot) {
        return new PointData(slotLeft(slot) + SLOT_WIDTH, slotBottom(slot));
    }

    static PointData productTopLeft(int slot) {
        return new PointData(slotLeft(slot), slotTop(slot));
    }

    static PointData productBottomRight(int slot) {
        return new PointData(slotLeft(slot) + SLOT_WIDTH, slotBottom(slot) - PRICE_HEIGHT);
    }

    /** Centre of the product body. Tapping the price strip does not buy. */
    static PointData productTap(int slot) {
        int top = slotTop(slot);
        int priceTop = slotBottom(slot) - PRICE_HEIGHT;
        return new PointData(slotLeft(slot) + SLOT_WIDTH / 2, (top + priceTop) / 2);
    }

    /**
     * Non-VIP pass: take when the price is not gems and the product is not
     * VIP. VIP cards stay for the second pass even with a resource price.
     */
    static boolean takeNonVip(boolean gemOnPrice, boolean vipOnProduct) {
        return !gemOnPrice && !vipOnProduct;
    }

    static boolean slotChanged(double meanDelta) {
        return meanDelta >= SLOT_CHANGE_MEAN;
    }

    static double meanChannelDelta(RawImageData before, RawImageData after,
            PointData topLeft, PointData bottomRight, int step) {
        if (topLeft == null || bottomRight == null) {
            return -1;
        }
        return MysteryShopDecisions.meanChannelDelta(before, after,
                topLeft.getX(), topLeft.getY(), bottomRight.getX(), bottomRight.getY(), step);
    }
}
