package dev.frostguard.tasks.city;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildingUpgradeConfirmationFlowTest {

    @Test
    void confirmsAfterDetectedButtonClosesDialog() {
        RecordingUi ui = new RecordingUi(true, true, false);

        BuildingUpgradeConfirmationFlow.Outcome outcome = BuildingUpgradeConfirmationFlow.run(ui, 3);

        assertEquals(BuildingUpgradeConfirmationFlow.Outcome.CONFIRMED, outcome);
        assertEquals(1, ui.taps);
        assertEquals(2, ui.waits);
        assertEquals(2, ui.postconditionChecks);
    }

    @Test
    void rejectsMissingUpgradeButtonWithoutBlindTap() {
        RecordingUi ui = new RecordingUi(false);

        BuildingUpgradeConfirmationFlow.Outcome outcome = BuildingUpgradeConfirmationFlow.run(ui, 3);

        assertEquals(BuildingUpgradeConfirmationFlow.Outcome.BUTTON_NOT_FOUND, outcome);
        assertEquals(1, ui.taps);
        assertEquals(0, ui.waits);
        assertEquals(0, ui.postconditionChecks);
    }

    @Test
    void boundsPostconditionChecksWhenDialogDoesNotClose() {
        RecordingUi ui = new RecordingUi(true, true, true, true);

        BuildingUpgradeConfirmationFlow.Outcome outcome = BuildingUpgradeConfirmationFlow.run(ui, 3);

        assertEquals(BuildingUpgradeConfirmationFlow.Outcome.POSTCONDITION_NOT_MET, outcome);
        assertEquals(1, ui.taps);
        assertEquals(3, ui.waits);
        assertEquals(3, ui.postconditionChecks);
    }

    private static final class RecordingUi implements BuildingUpgradeConfirmationFlow.Ui {
        private final boolean buttonDetected;
        private final Deque<Boolean> dialogStates = new ArrayDeque<>();
        private int taps;
        private int waits;
        private int postconditionChecks;

        private RecordingUi(boolean buttonDetected, Boolean... dialogStates) {
            this.buttonDetected = buttonDetected;
            this.dialogStates.addAll(java.util.List.of(dialogStates));
        }

        @Override
        public boolean tapDetectedUpgrade() {
            taps++;
            return buttonDetected;
        }

        @Override
        public void waitForTransition() {
            waits++;
        }

        @Override
        public boolean isConfirmationPending() {
            postconditionChecks++;
            return dialogStates.removeFirst();
        }
    }
}
