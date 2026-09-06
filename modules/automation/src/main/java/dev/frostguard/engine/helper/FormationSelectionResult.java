package dev.frostguard.engine.helper;

/**
 * Explainable result of inspecting and optionally selecting a saved formation.
 */
public record FormationSelectionResult(Status status, Integer formation, String detail) {

    public enum Status {
        SELECTED,
        NOT_CONFIGURED,
        UNSUPPORTED,
        LOCKED,
        EMPTY_OR_MISSING,
        SCREEN_UNREADABLE
    }

    public boolean successful() {
        return status == Status.SELECTED || status == Status.NOT_CONFIGURED;
    }
}
