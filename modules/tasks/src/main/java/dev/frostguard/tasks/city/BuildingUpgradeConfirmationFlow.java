package dev.frostguard.tasks.city;

final class BuildingUpgradeConfirmationFlow {

    /** The greyed Upgrade caption scores ~80 against the blue template; unrelated screens score far lower. */
    static final double GREYED_UPGRADE_MIN_SCORE = 70;

    enum Outcome {
        CONFIRMED,
        BUTTON_NOT_FOUND,
        PREREQUISITE_BLOCKED,
        POSTCONDITION_NOT_MET
    }

    interface Ui {
        boolean tapDetectedUpgrade();

        void waitForTransition();

        boolean isConfirmationPending();

        /** One fresh frame: Go button in the Requires list and greyed Upgrade score. */
        boolean isPrerequisiteBlocked();
    }

    private BuildingUpgradeConfirmationFlow() {
    }

    static Outcome run(Ui ui, int postconditionAttempts) {
        if (!ui.tapDetectedUpgrade()) {
            return ui.isPrerequisiteBlocked() ? Outcome.PREREQUISITE_BLOCKED : Outcome.BUTTON_NOT_FOUND;
        }

        for (int attempt = 0; attempt < postconditionAttempts; attempt++) {
            ui.waitForTransition();
            if (!ui.isConfirmationPending()) {
                return Outcome.CONFIRMED;
            }
        }

        return Outcome.POSTCONDITION_NOT_MET;
    }

    /** An unmet building requirement shows a Go button and greys the Upgrade button (e.g. Furnace Lv 16 while it is being built). */
    static boolean isPrerequisiteBlocked(boolean requirementGoFound, double upgradeScore) {
        return requirementGoFound && upgradeScore >= GREYED_UPGRADE_MIN_SCORE;
    }
}
