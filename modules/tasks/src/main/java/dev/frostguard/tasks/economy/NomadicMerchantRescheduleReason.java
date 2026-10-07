package dev.frostguard.tasks.economy;

/** Diagnostic code for the retryable exit that selected the current schedule. */
public enum NomadicMerchantRescheduleReason {
    NAVIGATION_ERROR,
    ADB_ERROR,
    NO_COLLECT_ERROR,
    TIMEOUT;

    public String code() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
