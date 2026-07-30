package dev.frostguard.engine.helper;

/** Evidence-backed outcome of one Chief Stamina top-up attempt. */
public record StaminaTopUpResult(
        Status status,
        Integer observedStamina,
        Integer itemCount,
        int itemsNeeded,
        Integer finalStamina) {

    public enum Status {
        TOPPED_UP,
        ALREADY_SUFFICIENT,
        INSUFFICIENT_ITEMS,
        READ_FAILED,
        UI_NOT_FOUND,
        TOP_UP_NOT_CONFIRMED
    }

    public boolean successful() {
        return status == Status.TOPPED_UP || status == Status.ALREADY_SUFFICIENT;
    }

    public boolean confirmedItemShortage() {
        return status == Status.INSUFFICIENT_ITEMS;
    }

    public static StaminaTopUpResult uiNotFound() {
        return new StaminaTopUpResult(Status.UI_NOT_FOUND, null, null, 0, null);
    }
}
