package dev.frostguard.api.domain;

/** Where the march is in its movement or work lifecycle. */
public enum MarchMovementPhase {
    NONE,
    OUTBOUND,
    WORKING,
    RETURNING,
    PREPARING,
    STATIONED,
    UNKNOWN
}
