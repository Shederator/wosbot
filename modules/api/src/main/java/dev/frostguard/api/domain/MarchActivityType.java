package dev.frostguard.api.domain;

/** What the march slot is being used for, when the queue panel exposes enough evidence. */
public enum MarchActivityType {
    NONE,
    GATHER,
    ATTACK,
    RALLY,
    INTEL,
    REINFORCEMENT,
    ENCAMPMENT,
    GARRISONED,
    UNKNOWN
}
