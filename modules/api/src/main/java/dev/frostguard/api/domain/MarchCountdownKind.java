package dev.frostguard.api.domain;

/** What the visible march queue countdown is believed to measure. */
public enum MarchCountdownKind {
    NONE,
    ARRIVAL,
    WORK_REMAINING,
    RETURN,
    RALLY_START,
    UNKNOWN
}
