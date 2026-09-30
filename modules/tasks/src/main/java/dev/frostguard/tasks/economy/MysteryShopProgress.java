package dev.frostguard.tasks.economy;

/**
 * Progress remembered on the reused Mystery Shop task between visits.
 *
 * <p>The scheduler keeps the same {@link MysteryShopRoutine} instance for a
 * profile, so this value survives from one visit to the next. It is lost when
 * the process restarts. It exists so a later visit can explain why it was
 * woken. It never skips the scan: every execution opens the shop and reads
 * the screen again.</p>
 *
 * <p>A confirmed free reward, chest, shard, or refresh clears an
 * {@link #UNCONFIRMED} streak. A clean exit replaces this value with the exit
 * it actually took.</p>
 *
 * @see MysteryShopPhase
 */
public enum MysteryShopProgress {

    /**
     * No remembered restriction. The last visit either has not run or ended
     * on a confirmed action that did not close the day.
     */
    READY,

    /**
     * The last visit left a 250-badge target it could not pay for. The free
     * refresh was left unused on purpose so that card is not replaced. The
     * next visit is scheduled before the daily reset so badges earned in the
     * meantime can still be spent. The visit still scans; a newly affordable
     * target is bought. An empty target list never waits: a visible free
     * refresh is spent immediately.
     */
    WAITING_FOR_BADGES,

    /**
     * The last visit could not prove what the screen did: navigation failed,
     * the badge counter could not be read, a tap was not confirmed, a refresh
     * left the grid unchanged, or the two-minute limit was reached. The next
     * visit is a short retry. The first retry in a streak waits five minutes.
     * A later retry in the same streak waits one hour. Both are kept at least
     * five minutes before the daily reset.
     */
    UNCONFIRMED,

    /**
     * The last visit found no enabled target and no free refresh. The shop is
     * treated as clear until one minute after the daily reset. A visit that
     * runs before that moment still scans; this value does not mean the day
     * was skipped.
     */
    COMPLETE_UNTIL_RESET
}
