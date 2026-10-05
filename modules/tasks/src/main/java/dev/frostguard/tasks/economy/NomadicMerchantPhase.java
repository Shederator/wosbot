package dev.frostguard.tasks.economy;

/**
 * Phase of the visit that is running now.
 *
 * <p>This value lives only for the current {@code execute()} call. It is set
 * back to {@link #OPENING} at the start of every visit and is discarded with
 * the call stack. Logs name it so an unconfirmed exit shows which step could
 * not be proved.</p>
 *
 * @see NomadicMerchantProgress
 */
public enum NomadicMerchantPhase {

    /** Opening the Nomadic Merchant tab. */
    OPENING,

    /** Searching product icons for resource-priced cards. */
    SEARCHING_RESOURCES,

    /** Tapping one resource-priced product. */
    CLAIMING_RESOURCE,

    /** Waiting for the claim animation before the card is gone. */
    WAITING_CLAIM_ANIMATION,

    /** Searching for a VIP product. */
    SEARCHING_VIP,

    /** Buying the VIP product with gems. */
    BUYING_VIP,

    /** Looking for the free refresh control. */
    SEARCHING_FREE_REFRESH,

    /** Tapping the free refresh control. */
    CLAIMING_FREE_REFRESH,

    /** Waiting briefly after dispatching one Free Refresh tap. */
    WAITING_REFRESH,

    /** The visit has chosen its exit and is leaving the shop. */
    FINISHED
}
