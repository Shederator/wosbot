package dev.frostguard.tasks.economy;

/**
 * Progress remembered on the reused Nomadic Merchant task between visits.
 *
 * <p>The scheduler keeps the same {@link NomadicMerchantRoutine} instance for a
 * profile, so this value survives from one visit to the next. It is lost when
 * the process restarts. It exists so a later visit can explain why it was
 * woken. It never skips the scan: every execution opens the shop and reads
 * the screen again.</p>
 *
 * @see NomadicMerchantPhase
 */
public enum NomadicMerchantProgress {

    /**
     * No remembered restriction. The last visit either has not run or ended
     * on a confirmed action that did not close the day.
     */
    READY,

    /**
     * The last visit could not prove what the screen did: navigation failed,
     * a VIP or refresh tap was not confirmed, or the two-minute limit was
     * reached. The next visit is a short retry until the retry budget is
     * exhausted, then a scan after the daily reset.
     */
    UNCONFIRMED,

    /**
     * The last visit found no remaining resource take, VIP, or free refresh.
     * The shop is treated as clear until one minute after the daily reset.
     * A visit that runs before that moment still scans.
     */
    COMPLETE_UNTIL_RESET
}
