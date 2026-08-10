package dev.frostguard.api.domain;

/** How confidently the visible countdown predicts when the physical march slot is usable again. */
public enum MarchSlotReleaseConfidence {
    NOW,
    EXACT,
    LOWER_BOUND,
    MANUAL_OR_UNKNOWN,
    NEVER,
    UNKNOWN
}
