package dev.frostguard.api.domain;

/** Whether a physical march slot can be used for a new deployment. */
public enum MarchSlotAvailability {
    IDLE,
    OCCUPIED,
    LOCKED,
    UNKNOWN
}
