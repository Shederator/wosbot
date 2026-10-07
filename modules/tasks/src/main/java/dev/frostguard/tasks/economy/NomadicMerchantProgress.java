package dev.frostguard.tasks.economy;

/** Daily execution state held in memory by the reused task instance. */
public enum NomadicMerchantProgress {
    /** Reserved for a task that is not configured; configured routines are not constructed in this state. */
    INACTIVE,
    /** Initial state of a configured task. */
    READY,
    /** The shop was exhausted after at least one dispatched collection or refresh in this cycle. */
    COMPLETED_SUCCESS_RESCHEDULED,
    /** The shop was exhausted without a dispatched collection or refresh in this cycle. */
    COMPLETED_UNVERIFIED,
    /** The first or second timeout in a cycle; retry shortly. */
    TIMEOUT_RETRY,
    /** The third timeout in a cycle occurred after at least one dispatched collection. */
    PARTIAL_RESCHEDULED,
    /** The third timeout in a cycle occurred without a dispatched collection. */
    FAILED_RESCHEDULED
}
