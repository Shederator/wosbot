package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.frostguard.engine.nav.ShopTab;

class ShopNavigatorTest {

    @Test
    void selectsNomadicMerchantFromFreshViewportWithoutSwiping() {
        FakeInteractions fake = new FakeInteractions(ShopTab.MYSTERY_SHOP);

        assertTrue(new ShopNavigator(fake).navigateTo(ShopTab.NOMADIC_MERCHANT));
        assertEquals(List.of(), fake.swipes);
        assertEquals(1, fake.tappedSlot);
    }

    @Test
    void reReadsEveryPageUntilFoundryIsVisible() {
        FakeInteractions fake = new FakeInteractions(
                ShopTab.MYSTERY_SHOP, ShopTab.VIP_SHOP, ShopTab.STATE_OF_POWER_SHOP);

        assertTrue(new ShopNavigator(fake).navigateTo(ShopTab.FOUNDRY_SHOP));
        assertEquals(List.of(ShopNavigator.SwipeDirection.LATER,
                ShopNavigator.SwipeDirection.LATER), fake.swipes);
        assertEquals(1, fake.tappedSlot);
    }

    @Test
    void reversesWhenAForwardSwipeMovesPastTheTarget() {
        FakeInteractions fake = new FakeInteractions(
                ShopTab.MYSTERY_SHOP, ShopTab.STATE_OF_POWER_SHOP, ShopTab.VIP_SHOP);

        assertTrue(new ShopNavigator(fake).navigateTo(ShopTab.ALLIANCE_CHAMPIONSHIP_SHOP));
        assertEquals(List.of(ShopNavigator.SwipeDirection.LATER,
                ShopNavigator.SwipeDirection.EARLIER), fake.swipes);
        assertEquals(1, fake.tappedSlot);
    }

    @Test
    void failsWithoutClickingWhenViewportDoesNotMove() {
        FakeInteractions fake = new FakeInteractions(ShopTab.MYSTERY_SHOP, ShopTab.MYSTERY_SHOP);

        assertFalse(new ShopNavigator(fake).navigateTo(ShopTab.GEM_SHOP));
        assertEquals(-1, fake.tappedSlot);
    }

    @Test
    void failsWithoutClickingWhenOcrBecomesUnreadable() {
        FakeInteractions fake = new FakeInteractions(ShopTab.MYSTERY_SHOP, null);

        assertFalse(new ShopNavigator(fake).navigateTo(ShopTab.GEM_SHOP));
        assertEquals(-1, fake.tappedSlot);
    }

    @Test
    void rejectsUnexpectedInitialViewport() {
        FakeInteractions fake = new FakeInteractions(ShopTab.VIP_SHOP);

        assertFalse(new ShopNavigator(fake).navigateTo(ShopTab.VIP_SHOP));
        assertEquals(-1, fake.tappedSlot);
    }

    @Test
    void boundsOscillatingNavigation() {
        ShopTab[] observations = new ShopTab[ShopNavigator.MAX_SWIPE_ATTEMPTS + 1];
        observations[0] = ShopTab.MYSTERY_SHOP;
        for (int index = 1; index < observations.length; index++) {
            observations[index] = index % 2 == 1 ? ShopTab.CANYON_SHOP : ShopTab.MYSTERY_SHOP;
        }
        FakeInteractions fake = new FakeInteractions(observations);

        assertFalse(new ShopNavigator(fake).navigateTo(ShopTab.ALLIANCE_CHAMPIONSHIP_SHOP));
        assertEquals(ShopNavigator.MAX_SWIPE_ATTEMPTS, fake.swipes.size());
        assertEquals(-1, fake.tappedSlot);
    }

    private static final class FakeInteractions implements ShopNavigator.Interactions {
        private final Deque<Optional<ShopTab>> observations = new ArrayDeque<>();
        private final List<ShopNavigator.SwipeDirection> swipes = new ArrayList<>();
        private int tappedSlot = -1;

        private FakeInteractions(ShopTab... tabs) {
            Arrays.stream(tabs)
                    .map(Optional::ofNullable)
                    .forEach(observations::addLast);
        }

        @Override
        public boolean openShop() {
            return true;
        }

        @Override
        public Optional<ShopTab> readLeftmostTab(boolean initialViewport) {
            return observations.isEmpty() ? Optional.empty() : observations.removeFirst();
        }

        @Override
        public boolean swipe(ShopNavigator.SwipeDirection direction) {
            swipes.add(direction);
            return true;
        }

        @Override
        public void tapSlot(int visibleSlot) {
            tappedSlot = visibleSlot;
        }
    }
}
