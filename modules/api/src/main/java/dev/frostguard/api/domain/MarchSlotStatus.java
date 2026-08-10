package dev.frostguard.api.domain;

/**
 * Occupancy of a single row of the wilderness March Queue panel.
 *
 * <p>Only {@link #GATHERING} and {@link #RETURNING} carry a countdown that describes when the slot
 * frees up. A rally or an attack also shows a countdown, but it measures the preparation or the
 * outbound march, not the return - those land in {@link #BUSY_UNKNOWN}.
 */
public enum MarchSlotStatus {

    /** Free: a march can be deployed right now. */
    IDLE,

    /** "Unlock" or "Unavailable": the slot never frees up on its own. */
    LOCKED,

    /** Encamped or reinforcing: occupied, and the panel shows no countdown at all. */
    STATIONED,

    /** Walking to a resource node or gathering: the countdown is a lower bound on the return. */
    GATHERING,

    /** Marching home: the countdown is the exact arrival time. */
    RETURNING,

    /** Occupied with a countdown that does not describe the return (rally, attack, unrecognised). */
    BUSY_UNKNOWN
}
