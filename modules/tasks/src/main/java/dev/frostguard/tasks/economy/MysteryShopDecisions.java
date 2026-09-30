package dev.frostguard.tasks.economy;

import java.time.LocalDateTime;

import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;

/**
 * Pure decisions for one Mystery Shop visit. The routine performs the taps;
 * this type answers whether a card is a target, whether it can be paid for,
 * whether a refresh is allowed, and when the next visit should run.
 *
 * <p>Geometry and the grid-change threshold were measured on the 720x1280
 * shop frames from 2026-09-29. The chest icon sits about 125 pixels above
 * its 250-badge price, and neighbouring cards are about 200 pixels apart.</p>
 */
final class MysteryShopDecisions {

    static final int BADGE_PRICE = 250;
    static final int MAX_BALANCE = 999_999;

    /** Price centre may sit this far left or right of the icon centre. */
    static final int PRICE_DX_LIMIT = 80;
    /** Price centre is at least this far below the icon centre. */
    static final int PRICE_DY_MIN = 90;
    /** Price centre is at most this far below the icon centre. */
    static final int PRICE_DY_MAX = 165;

    static final int SOLD_OUT_DX = 140;
    static final int SOLD_OUT_ABOVE = 40;
    static final int SOLD_OUT_BELOW = 70;

    /** Two matches closer than this are the same card, not a neighbour. */
    static final int SAME_CARD_PX = 40;

    static final int GRID_LEFT = 20;
    static final int GRID_TOP = 430;
    static final int GRID_RIGHT = 700;
    static final int GRID_BOTTOM = 1180;
    static final int GRID_STEP = 8;

    /**
     * Mean per-channel change that counts as a new grid. No before/after pair
     * of the same shop was captured, so this is a first cut: an untouched
     * grid stays near zero and a replaced card moves the sample well past it.
     */
    static final double GRID_CHANGE_MEAN = 12.0;

    private static final int EARN_BADGES_HOURS = 4;
    private static final int FIRST_RETRY_MINUTES = 5;
    private static final int REPEAT_RETRY_HOURS = 1;
    private static final int BEFORE_RESET_MINUTES = 5;
    private static final int RESET_SETTLE_MINUTES = 1;

    private MysteryShopDecisions() {
    }

    /** What to do once every enabled card that can be paid for is gone. */
    enum RefreshChoice {
        /** No target remains, and a free refresh may be spent. */
        REFRESH,
        /** Keep a target that cannot be paid for yet. */
        WAIT_FOR_BADGES,
        /** Nothing left to buy and no free refresh remains. */
        DAY_COMPLETE
    }

    static boolean isFreeReward(boolean templateFound) {
        return templateFound;
    }

    /** A hero-gear chest is a target only when its own card shows the 250 price. */
    static boolean isChest250(boolean chestIconFound, boolean priceOnCard) {
        return chestIconFound && priceOnCard;
    }

    /** A generic shard is a target only when its own card shows the 250 price. */
    static boolean isShard250(boolean shardIconFound, boolean priceOnCard) {
        return shardIconFound && priceOnCard;
    }

    static boolean isSoldOut(boolean templateFound) {
        return templateFound;
    }

    static boolean isFreeRefresh(boolean templateFound) {
        return templateFound;
    }

    /** A free reward costs nothing, so an unknown balance still allows it. */
    static boolean canBuy(Integer balance, int cost) {
        if (cost <= 0) {
            return true;
        }
        return balance != null && balance >= cost;
    }

    static boolean isAffordable(Integer balance) {
        return canBuy(balance, BADGE_PRICE);
    }

    static int afterPurchase(int balance) {
        return balance - BADGE_PRICE;
    }

    /**
     * Reads a badge counter such as {@code 8,350}. Commas and spaces are
     * ignored. Empty text, non-digits, and values outside {@code 0} to
     * {@link #MAX_BALANCE} are unreadable rather than invented.
     */
    static Integer parseBalance(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            int value = Integer.parseInt(digits);
            if (value < 0 || value > MAX_BALANCE) {
                return null;
            }
            return value;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    static boolean priceOnSameCard(int iconX, int iconY, int priceX, int priceY) {
        int dx = priceX - iconX;
        int dy = priceY - iconY;
        return Math.abs(dx) <= PRICE_DX_LIMIT && dy >= PRICE_DY_MIN && dy <= PRICE_DY_MAX;
    }

    static PointData priceWindowTopLeft(int iconX, int iconY) {
        return new PointData(iconX - PRICE_DX_LIMIT, iconY + PRICE_DY_MIN);
    }

    static PointData priceWindowBottomRight(int iconX, int iconY) {
        return new PointData(iconX + PRICE_DX_LIMIT, iconY + PRICE_DY_MAX);
    }

    static PointData soldOutWindowTopLeft(int priceX, int priceY) {
        return new PointData(priceX - SOLD_OUT_DX, priceY - SOLD_OUT_ABOVE);
    }

    static PointData soldOutWindowBottomRight(int priceX, int priceY) {
        return new PointData(priceX + SOLD_OUT_DX, priceY + SOLD_OUT_BELOW);
    }

    static boolean sameCard(int x1, int y1, int x2, int y2) {
        return Math.abs(x1 - x2) <= SAME_CARD_PX && Math.abs(y1 - y2) <= SAME_CARD_PX;
    }

    /**
     * Refresh as soon as no enabled target remains and a free refresh is
     * visible. The badge balance does not delay that refresh: a balance under
     * 250 with an empty target list spends the free refresh and the visit
     * scans the new grid. An unaffordable chest or shard stays on screen and
     * blocks the refresh, because refreshing would discard it. Without a free
     * refresh the shop is clear for the day.
     */
    static RefreshChoice choose(boolean targetsRemain, boolean refreshVisible) {
        if (targetsRemain) {
            return RefreshChoice.WAIT_FOR_BADGES;
        }
        if (!refreshVisible) {
            return RefreshChoice.DAY_COMPLETE;
        }
        return RefreshChoice.REFRESH;
    }

    /**
     * Next visit while badges may still be earned. Four hours ahead, but no
     * later than five minutes before the reset. Inside that last window the
     * visit is one minute after the reset.
     */
    static LocalDateTime earnMoneyVisit(LocalDateTime now, LocalDateTime reset) {
        return capBeforeReset(now, reset, now.plusHours(EARN_BADGES_HOURS));
    }

    /**
     * Next visit after an unproved action. The first failure in a streak
     * waits five minutes. A repeated failure waits one hour. The same reset
     * cap as {@link #earnMoneyVisit} applies.
     */
    static LocalDateTime unconfirmedVisit(LocalDateTime now, LocalDateTime reset, boolean repeated) {
        LocalDateTime candidate = repeated
                ? now.plusHours(REPEAT_RETRY_HOURS)
                : now.plusMinutes(FIRST_RETRY_MINUTES);
        return capBeforeReset(now, reset, candidate);
    }

    static boolean repeatUnconfirmed(int consecutiveUnconfirmed) {
        return consecutiveUnconfirmed >= 1;
    }

    /** One minute after the reset, matching the nomadic merchant settle. */
    static LocalDateTime dayCompleteVisit(LocalDateTime reset) {
        return reset.plusMinutes(RESET_SETTLE_MINUTES);
    }

    /**
     * Mean absolute channel change inside the rectangle, sampled every
     * {@code step} pixel. Returns {@code -1} when the two frames cannot be
     * compared. A value of {@code -1} is not a changed grid.
     */
    static double meanChannelDelta(RawImageData before, RawImageData after,
            int x0, int y0, int x1, int y1, int step) {
        if (!comparable(before, after) || step < 1) {
            return -1;
        }
        int width = before.getWidth();
        int height = before.getHeight();
        int bytes = bytesPerPixel(before.getBpp());
        if (bytes < 3) {
            return -1;
        }
        int left = Math.max(0, Math.min(x0, x1));
        int right = Math.min(width - 1, Math.max(x0, x1));
        int top = Math.max(0, Math.min(y0, y1));
        int bottom = Math.min(height - 1, Math.max(y0, y1));
        if (left > right || top > bottom) {
            return -1;
        }
        byte[] first = before.getData();
        byte[] second = after.getData();
        long sum = 0;
        int count = 0;
        for (int y = top; y <= bottom; y += step) {
            for (int x = left; x <= right; x += step) {
                int offset = (y * width + x) * bytes;
                if (offset + 2 >= first.length || offset + 2 >= second.length) {
                    return -1;
                }
                int delta = Math.abs((first[offset] & 0xFF) - (second[offset] & 0xFF))
                        + Math.abs((first[offset + 1] & 0xFF) - (second[offset + 1] & 0xFF))
                        + Math.abs((first[offset + 2] & 0xFF) - (second[offset + 2] & 0xFF));
                sum += delta / 3;
                count++;
            }
        }
        if (count == 0) {
            return -1;
        }
        return sum / (double) count;
    }

    static boolean gridChanged(double meanDelta) {
        return meanDelta >= GRID_CHANGE_MEAN;
    }

    private static LocalDateTime capBeforeReset(LocalDateTime now, LocalDateTime reset,
            LocalDateTime candidate) {
        LocalDateTime chosen = candidate.isBefore(reset)
                ? candidate
                : reset.minusMinutes(BEFORE_RESET_MINUTES);
        if (!chosen.isAfter(now)) {
            return reset.plusMinutes(RESET_SETTLE_MINUTES);
        }
        return chosen;
    }

    private static boolean comparable(RawImageData before, RawImageData after) {
        return before != null && after != null
                && before.getData() != null && after.getData() != null
                && before.getWidth() > 0 && before.getHeight() > 0
                && before.getWidth() == after.getWidth()
                && before.getHeight() == after.getHeight()
                && before.getBpp() == after.getBpp();
    }

    private static int bytesPerPixel(int bitsOrBytes) {
        if (bitsOrBytes == 4 || bitsOrBytes == 32) {
            return 4;
        }
        return -1;
    }
}
