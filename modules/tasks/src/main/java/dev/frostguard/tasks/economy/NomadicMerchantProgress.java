package dev.frostguard.tasks.economy;

/** Daily execution state held in memory by the reused task instance. */
public enum NomadicMerchantProgress {
    /** Reserved for a task that is not configured; configured routines are not constructed in this state. */
    INACTIVE,
    /** Initial state of a configured task. */
    READY,
    /** A visit ended with an unknown outcome and will retry shortly. */
    FAILED_RETRY,
    /** The shop scan found no eligible operation remaining. */
    COMPLETED_SUCCESS,
    /** Some offers were confirmed, but later visits remained unknown. */
    PARTIAL_RESCHEDULED,
    /** The daily retry budget was exhausted without confirming an offer this cycle. */
    FAILED_RESCHEDULED
}
