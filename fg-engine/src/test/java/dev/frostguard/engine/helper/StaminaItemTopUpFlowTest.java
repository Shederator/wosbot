package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class StaminaItemTopUpFlowTest {

    @Test
    void synchronizesObservedAndVerifiedFinalStamina() {
        List<Integer> synchronizedLevels = new ArrayList<>();
        FakeDialog dialog = new FakeDialog(155, 67, 147);

        StaminaTopUpResult result = new StaminaItemTopUpFlow(synchronizedLevels::add)
                .topUp(dialog, 145, 0);

        assertEquals(StaminaTopUpResult.Status.TOPPED_UP, result.status());
        assertEquals(8, result.itemsNeeded());
        assertEquals(8, dialog.itemsUsed);
        assertEquals(List.of(67, 147), synchronizedLevels);
        assertEquals(147, result.finalStamina());
    }

    @Test
    void unreadableItemCountKeepsAuthoritativeStaminaAndDoesNotClick() {
        List<Integer> synchronizedLevels = new ArrayList<>();
        FakeDialog dialog = new FakeDialog(null, 67);

        StaminaTopUpResult result = new StaminaItemTopUpFlow(synchronizedLevels::add)
                .topUp(dialog, 145, 1);

        assertEquals(StaminaTopUpResult.Status.READ_FAILED, result.status());
        assertEquals(8, result.itemsNeeded());
        assertEquals(0, dialog.itemsUsed);
        assertEquals(List.of(67), synchronizedLevels);
    }

    @Test
    void zeroReserveCanTopUpWithoutReadableItemCount() {
        List<Integer> synchronizedLevels = new ArrayList<>();
        FakeDialog dialog = new FakeDialog(null, 67, 147);

        StaminaTopUpResult result = new StaminaItemTopUpFlow(synchronizedLevels::add)
                .topUp(dialog, 145, 0);

        assertEquals(StaminaTopUpResult.Status.TOPPED_UP, result.status());
        assertEquals(8, dialog.itemsUsed);
        assertEquals(List.of(67, 147), synchronizedLevels);
    }

    @Test
    void zeroReserveIgnoresPlausibleZeroFromTruncatedItemCount() {
        List<Integer> synchronizedLevels = new ArrayList<>();
        FakeDialog dialog = new FakeDialog(0, 126, 146);

        StaminaTopUpResult result = new StaminaItemTopUpFlow(synchronizedLevels::add)
                .topUp(dialog, 145, 0);

        assertEquals(StaminaTopUpResult.Status.TOPPED_UP, result.status());
        assertEquals(2, dialog.itemsUsed);
        assertEquals(0, dialog.itemCountReads);
        assertEquals(List.of(126, 146), synchronizedLevels);
        assertNull(result.itemCount());
    }

    @Test
    void confirmedShortageHonorsItemReserve() {
        FakeDialog dialog = new FakeDialog(155, 67);

        StaminaTopUpResult result = new StaminaItemTopUpFlow(level -> {})
                .topUp(dialog, 145, 150);

        assertEquals(StaminaTopUpResult.Status.INSUFFICIENT_ITEMS, result.status());
        assertEquals(8, result.itemsNeeded());
        assertEquals(0, dialog.itemsUsed);
    }

    @Test
    void missingFinalReadNeverCreditsItemsOptimistically() {
        List<Integer> synchronizedLevels = new ArrayList<>();
        FakeDialog dialog = new FakeDialog(155, 67, null);

        StaminaTopUpResult result = new StaminaItemTopUpFlow(synchronizedLevels::add)
                .topUp(dialog, 145, 0);

        assertEquals(StaminaTopUpResult.Status.TOP_UP_NOT_CONFIRMED, result.status());
        assertEquals(8, dialog.itemsUsed);
        assertEquals(List.of(67), synchronizedLevels);
        assertNull(result.finalStamina());
    }

    @Test
    void finalReadBelowTargetIsSynchronizedButNotAccepted() {
        List<Integer> synchronizedLevels = new ArrayList<>();
        FakeDialog dialog = new FakeDialog(155, 67, 137);

        StaminaTopUpResult result = new StaminaItemTopUpFlow(synchronizedLevels::add)
                .topUp(dialog, 145, 0);

        assertEquals(StaminaTopUpResult.Status.TOP_UP_NOT_CONFIRMED, result.status());
        assertEquals(List.of(67, 137), synchronizedLevels);
        assertEquals(137, result.finalStamina());
    }

    private static final class FakeDialog implements StaminaItemTopUpFlow.Dialog {
        private final List<Integer> staminaReads;
        private final Integer itemCount;
        private int readIndex;
        private int itemCountReads;
        private int itemsUsed;

        private FakeDialog(Integer itemCount, Integer... staminaReads) {
            this.staminaReads = Arrays.asList(staminaReads);
            this.itemCount = itemCount;
        }

        @Override
        public Integer readCurrentStamina() {
            return staminaReads.get(readIndex++);
        }

        @Override
        public Integer readItemCount() {
            itemCountReads++;
            return itemCount;
        }

        @Override
        public boolean useItem() {
            itemsUsed++;
            return true;
        }
    }
}
