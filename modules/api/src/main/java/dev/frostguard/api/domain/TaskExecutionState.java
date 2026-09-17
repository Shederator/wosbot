package dev.frostguard.api.domain;

public enum TaskExecutionState {
    IDLE,
    PAUSED,
    RUNNING,
    PAUSE_REQUESTED,
    STOPPING,
    COMPLETED,
    FAILED,
    STOPPED;

    public boolean isActive() {
        return this == PAUSED || this == RUNNING || this == PAUSE_REQUESTED || this == STOPPING;
    }
}
