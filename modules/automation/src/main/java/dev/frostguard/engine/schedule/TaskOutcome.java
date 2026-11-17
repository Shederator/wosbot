package dev.frostguard.engine.schedule;

/**
 * Execution outcome reported by a {@link DelayedTask} to {@link TaskQueue}.
 *
 * <p>Determines whether post-execution handling advances daily completion history
 * or records a schedule adjustment, and whether failure incidents are recorded,
 * preserved, or recovered.
 */
public enum TaskOutcome {

    /**
     * The task completed its primary objective successfully.
     * Advances daily completion history and resets failure streaks.
     */
    SUCCESS,

    /**
     * The task encountered an internally caught/handled failure and requested a retry.
     * Records a failure streak (escalating to Action Required on threshold) and
     * preserves daily completion history.
     */
    HANDLED_FAILURE,

    /**
     * The task encountered an unconfirmed or ambiguous state and requested a retry.
     * Preserves daily completion history and retains failure streaks without resetting.
     */
    UNKNOWN,

    /**
     * The task deferred its execution (e.g. for stamina regeneration or event windows).
     * Preserves daily completion history and retains failure streaks without resetting.
     */
    DEFERRED
}
