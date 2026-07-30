package dev.frostguard.engine.helper;

import java.util.function.IntConsumer;

final class StaminaItemTopUpFlow {

    static final int STAMINA_PER_ITEM = 10;

    interface Dialog {
        Integer readCurrentStamina();
        Integer readItemCount();
        boolean useItem();
    }

    private final IntConsumer synchronizeStamina;

    StaminaItemTopUpFlow(IntConsumer synchronizeStamina) {
        this.synchronizeStamina = synchronizeStamina;
    }

    StaminaTopUpResult topUp(Dialog dialog, int targetStamina, int itemReserve) {
        if (targetStamina < 0 || itemReserve < 0) {
            throw new IllegalArgumentException("Stamina target and item reserve must not be negative");
        }

        Integer current = dialog.readCurrentStamina();
        if (current == null) {
            return new StaminaTopUpResult(
                    StaminaTopUpResult.Status.READ_FAILED, null, null, 0, null);
        }
        synchronizeStamina.accept(current);

        int deficit = targetStamina - current;
        if (deficit <= 0) {
            return new StaminaTopUpResult(
                    StaminaTopUpResult.Status.ALREADY_SUFFICIENT, current, null, 0, current);
        }

        int itemsNeeded = (deficit + STAMINA_PER_ITEM - 1) / STAMINA_PER_ITEM;
        Integer itemCount = null;
        // With no reserve, the count provides no safety value and can be dangerously plausible
        // when OCR crops leading digits (for example, 110 as 0). The dialog adapter verifies the
        // Use button before every click and the final stamina read proves whether the top-up worked.
        if (itemReserve > 0) {
            itemCount = dialog.readItemCount();
            if (itemCount == null) {
                return new StaminaTopUpResult(
                        StaminaTopUpResult.Status.READ_FAILED, current, null, itemsNeeded, null);
            }

            int usableItems = Math.max(0, itemCount - itemReserve);
            if (usableItems < itemsNeeded) {
                return new StaminaTopUpResult(
                        StaminaTopUpResult.Status.INSUFFICIENT_ITEMS,
                        current, itemCount, itemsNeeded, current);
            }
        }

        for (int used = 0; used < itemsNeeded; used++) {
            if (!dialog.useItem()) {
                break;
            }
        }

        Integer finalStamina = dialog.readCurrentStamina();
        if (finalStamina == null) {
            return new StaminaTopUpResult(
                    StaminaTopUpResult.Status.TOP_UP_NOT_CONFIRMED,
                    current, itemCount, itemsNeeded, null);
        }
        synchronizeStamina.accept(finalStamina);

        StaminaTopUpResult.Status status = finalStamina >= targetStamina
                ? StaminaTopUpResult.Status.TOPPED_UP
                : StaminaTopUpResult.Status.TOP_UP_NOT_CONFIRMED;
        return new StaminaTopUpResult(status, current, itemCount, itemsNeeded, finalStamina);
    }
}
