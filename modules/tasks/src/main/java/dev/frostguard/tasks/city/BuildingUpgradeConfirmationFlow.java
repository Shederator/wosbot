package dev.frostguard.tasks.city;

final class BuildingUpgradeConfirmationFlow {

    enum Outcome {
        CONFIRMED,
        BUTTON_NOT_FOUND,
        POSTCONDITION_NOT_MET
    }

    interface Ui {
        boolean tapDetectedUpgrade();

        void waitForTransition();

        boolean isConfirmationPending();
    }

    private BuildingUpgradeConfirmationFlow() {
    }

    static Outcome run(Ui ui, int postconditionAttempts) {
        if (!ui.tapDetectedUpgrade()) {
            return Outcome.BUTTON_NOT_FOUND;
        }

        for (int attempt = 0; attempt < postconditionAttempts; attempt++) {
            ui.waitForTransition();
            if (!ui.isConfirmationPending()) {
                return Outcome.CONFIRMED;
            }
        }

        return Outcome.POSTCONDITION_NOT_MET;
    }
}
