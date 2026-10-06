package dev.frostguard.tasks.economy;

/** Daily execution state held in memory by the reused task instance. */
public enum NomadicMerchantProgress {
    /** Reserved for a task that is not configured; configured routines are not constructed in this state. */
    INACTIVE,
    /** Initial state of a configured task. */
    READY,
    /** Execution timed out without a confirmed collection; retry shortly. */
    FAILED_RETRY,
    /** A scan or action failed and the visit will retry shortly. */
    ERROR_RETRY,
    /** The shop scan found no eligible operation remaining. */
    COMPLETED,
    /** Execution timed out after at least one confirmed collection in this cycle. */
    PARTIAL_RESCHEDULED,
    /** The third timeout in a cycle occurred without a confirmed collection. */
    FAILED_RESCHEDULED
}
