package dev.frostguard.app.bootstrap;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

import java.io.IOException;
import java.util.Optional;

final class ChannelOnboarding {
    private ChannelOnboarding() {
    }

    static boolean showBeforeStartup() {
        Optional<WorkspaceSettingsSnapshot> available = WorkspaceSettingsSnapshot.forCurrentNightly();
        if (available.isEmpty()) {
            return true;
        }
        WorkspaceSettingsSnapshot snapshot = available.orElseThrow();
        try {
            if (snapshot.isCompleted() || !snapshot.isTargetFresh()) {
                return true;
            }
            if (!snapshot.hasStableSettings()) {
                explainFreshNightly(snapshot);
                return true;
            }
            if (!snapshot.sourceIsAvailable()) {
                return explainBusyStable(snapshot);
            }
            return offerStableSnapshot(snapshot);
        } catch (IOException failure) {
            showFailure(failure);
            return false;
        }
    }

    private static void explainFreshNightly(WorkspaceSettingsSnapshot snapshot) throws IOException {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Set up Frostguard Nightly");
        alert.setHeaderText("Nightly keeps its own settings");
        alert.setContentText("No Stable workspace was found. Frostguard Nightly will start with new profiles, "
                + "tasks, schedules, and Telegram settings. Stable remains unchanged.");
        alert.showAndWait();
        snapshot.startFresh();
    }

    private static boolean explainBusyStable(WorkspaceSettingsSnapshot snapshot) throws IOException {
        ButtonType fresh = new ButtonType("Start fresh", ButtonBar.ButtonData.OK_DONE);
        ButtonType retryLater = new ButtonType("Exit and retry later", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "", fresh, retryLater);
        alert.setTitle("Set up Frostguard Nightly");
        alert.setHeaderText("Stable is currently using its settings");
        alert.setContentText("Close Frostguard Stable and its Telegram watcher, then restart Nightly to copy a "
                + "safe snapshot. You can also start Nightly with separate, empty settings now.");
        if (alert.showAndWait().filter(fresh::equals).isPresent()) {
            snapshot.startFresh();
            return true;
        }
        return false;
    }

    private static boolean offerStableSnapshot(WorkspaceSettingsSnapshot snapshot) throws IOException {
        ButtonType copy = new ButtonType("Copy Stable settings", ButtonBar.ButtonData.OK_DONE);
        ButtonType fresh = new ButtonType("Start fresh", ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType("Exit", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "", copy, fresh, cancel);
        alert.setTitle("Set up Frostguard Nightly");
        alert.setHeaderText("Nightly keeps an independent copy of your settings");
        alert.setContentText("Copying creates a one-time snapshot of Stable profiles, tasks, schedules, "
                + "Telegram configuration, custom tasks, and desktop preferences. Later changes are not "
                + "synchronized, and Nightly data is never copied back into Stable automatically.");
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.filter(copy::equals).isPresent()) {
            snapshot.copyFromStable();
            return true;
        }
        if (choice.filter(fresh::equals).isPresent()) {
            snapshot.startFresh();
            return true;
        }
        return false;
    }

    private static void showFailure(IOException failure) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Nightly settings setup failed");
        alert.setHeaderText("Stable settings were not changed");
        alert.setContentText(failure.getMessage() == null
                ? "Could not prepare the Nightly workspace. Restart Frostguard Nightly to try again."
                : failure.getMessage());
        alert.showAndWait();
    }
}
