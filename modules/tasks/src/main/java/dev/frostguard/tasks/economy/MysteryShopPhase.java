package dev.frostguard.tasks.economy;

/**
 * Phase of the visit that is running now.
 *
 * <p>This value lives only for the current {@code execute()} call. It is set
 * back to {@link #OPENING} at the start of every visit and is discarded with
 * the call stack. Logs name it so an unconfirmed exit shows which step could
 * not be proved.</p>
 *
 * @see MysteryShopProgress
 */
public enum MysteryShopPhase {

    /** Opening the Mystery Shop tab. */
    OPENING,

    /** Reading the badge counter under the hat at the top right. */
    READING_BALANCE,

    /** Claiming a visible free reward. A free reward costs nothing. */
    CLAIMING_FREE,

    /** Buying one enabled hero-gear chest priced at 250 badges. */
    BUYING_CHEST,

    /** Buying one enabled generic shard priced at 250 badges. */
    BUYING_SHARD,

    /** Deciding whether a free refresh is safe, then tapping it. */
    REFRESHING,

    /** The visit has chosen its exit and is leaving the shop. */
    FINISHED
}
