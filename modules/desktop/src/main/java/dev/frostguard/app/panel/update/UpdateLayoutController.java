package dev.frostguard.app.panel.update;

import dev.frostguard.api.runtime.RuntimeChannel;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.app.BuildMetadata;
import dev.frostguard.app.bootstrap.ApplicationLifecycle;
import dev.frostguard.update.InstallerHandoff;
import dev.frostguard.update.PreparedUpdate;
import dev.frostguard.update.ProjectUpdateKey;
import dev.frostguard.update.RunningBuild;
import dev.frostguard.update.SemanticVersion;
import dev.frostguard.update.UpdateCandidate;
import dev.frostguard.update.UpdateEndpointResolver;
import dev.frostguard.update.UpdateException;
import dev.frostguard.update.UpdateManager;
import dev.frostguard.update.UpdatePlatform;
import dev.frostguard.update.WindowsAuthenticodeVerifier;
import dev.frostguard.update.WindowsInstallerHandoff;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public final class UpdateLayoutController {
    @FXML private Label labelCurrentVersion;
    @FXML private Label labelChannel;
    @FXML private Label labelStatus;
    @FXML private VBox availableUpdatePane;
    @FXML private Label labelAvailableVersion;
    @FXML private Label labelArtifactDetails;
    @FXML private Hyperlink linkReleaseNotes;
    @FXML private Button buttonCheck;
    @FXML private Button buttonDownloadInstall;
    @FXML private ProgressIndicator progress;
    @FXML private VBox channelSwitchPane;
    @FXML private Label labelChannelSwitch;
    @FXML private Button buttonSwitchChannel;

    private final UpdateEndpointResolver endpoints = new UpdateEndpointResolver();
    private final UpdateManager manager = new UpdateManager(
            new WindowsAuthenticodeVerifier(), new WindowsInstallerHandoff());
    private final ChannelSwitchService channelSwitcher = new ChannelSwitchService();
    private RunningBuild runningBuild;
    private UpdateCandidate candidate;
    private RuntimeChannel switchTarget;

    @FXML
    private void initialize() {
        runningBuild = runningBuild();
        labelCurrentVersion.setText("Current version: " + runningBuild.version());
        labelChannel.setText("Channel: " + runningBuild.channel().directoryName());
        setAvailableUpdateVisible(false);
        progress.setVisible(false);
        configureChannelSwitch();

        if (!runningBuild.permitsAutomaticUpdates()) {
            buttonCheck.setDisable(true);
            if (runningBuild.pullRequestBuild()) {
                labelStatus.setText("PR-test builds cannot use automatic updates.");
            } else if (runningBuild.channel() == RuntimeChannel.DEVELOPMENT) {
                labelStatus.setText("Development builds do not use release update feeds.");
            } else {
                labelStatus.setText("This build has no valid pinned project update key.");
            }
        } else if (runningBuild.platform().operatingSystem() != UpdatePlatform.OperatingSystem.WINDOWS) {
            buttonCheck.setDisable(true);
            labelStatus.setText("Automatic installer handoff is currently available on Windows only.");
        } else {
            labelStatus.setText("Check the configured " + runningBuild.channel().directoryName() + " release feed.");
        }
    }

    @FXML
    private void handleSwitchChannel() {
        if (switchTarget == null) {
            return;
        }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Open Frostguard " + switchTarget.displayName());
        confirmation.setHeaderText(switchTarget == RuntimeChannel.NIGHTLY
                ? "Try Frostguard Nightly as a separate installation?"
                : "Return to Frostguard Stable?");
        confirmation.setContentText("Stable and Nightly keep separate profiles, tasks, schedules, Telegram "
                + "settings, and databases. Both can run side by side. A first Nightly launch can copy a "
                + "one-time Stable snapshot, but later changes are not synchronized.");
        if (confirmation.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
            return;
        }
        try {
            ChannelSwitchService.Result result = channelSwitcher.open(switchTarget);
            labelStatus.setText(result == ChannelSwitchService.Result.LAUNCHED_INSTALLED
                    ? "Frostguard " + switchTarget.displayName() + " was launched separately."
                    : "Opened the Frostguard " + switchTarget.displayName() + " release page.");
        } catch (Exception failure) {
            showFailure(new UpdateException("Could not open Frostguard " + switchTarget.displayName()
                    + ": " + failure.getMessage(), failure));
        }
    }

    @FXML
    private void handleCheck() {
        setBusy(true, "Checking for updates...");
        runAsync(() -> {
            URI endpoint = endpoints.resolve(runningBuild.channel())
                    .orElseThrow(() -> new UpdateException("No update feed is configured for this channel"));
            return manager.check(endpoint, runningBuild);
        }, this::showCheckResult);
    }

    @FXML
    private void handleDownloadInstall() {
        if (candidate == null) {
            return;
        }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Install Frostguard update");
        confirmation.setHeaderText("Install Frostguard " + candidate.version() + "?");
        confirmation.setContentText("Channel: " + candidate.channel().directoryName() + "\n"
                + "Download: " + formatSize(candidate.artifact().size()) + "\n\n"
                + "Frostguard will verify the project-signed release, stop automation, close its workspace, "
                + "exit, and then launch the installer. Windows may show an unknown-publisher warning.");
        if (confirmation.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
            return;
        }
        setBusy(true, "Downloading and verifying the authenticated installer...");
        runAsync(() -> manager.prepare(candidate, WorkspacePaths.current().cache()), this::beginHandoff);
    }

    @FXML
    private void openReleaseNotes() {
        if (candidate == null || !Desktop.isDesktopSupported()) {
            return;
        }
        try {
            Desktop.getDesktop().browse(candidate.releaseNotes());
        } catch (Exception exception) {
            showFailure(new UpdateException("Could not open release notes: " + exception.getMessage(), exception));
        }
    }

    private void showCheckResult(Optional<UpdateCandidate> result) {
        setBusy(false, result.isPresent() ? "An update is available." : "Frostguard is up to date.");
        candidate = result.orElse(null);
        setAvailableUpdateVisible(candidate != null);
        if (candidate != null) {
            labelAvailableVersion.setText("Frostguard " + candidate.version());
            labelArtifactDetails.setText(candidate.channel().directoryName() + " / " + candidate.platform().key()
                    + " / " + formatSize(candidate.artifact().size()));
            linkReleaseNotes.setText(candidate.releaseNotes().toString());
        }
    }

    private void beginHandoff(PreparedUpdate prepared) {
        labelStatus.setText("Installer verified. Stopping Frostguard safely...");
        InstallerHandoff.HandoffSession session = null;
        try {
            session = manager.stageHandoff(prepared, ProcessHandle.current().pid());
            UpdateExitCoordinator coordinator = new UpdateExitCoordinator(
                    ApplicationLifecycle::stopForUpdate,
                    ApplicationLifecycle::exitAfterUpdateHandoff,
                    ApplicationLifecycle::exitAfterCancelledUpdate);
            coordinator.execute(session);
        } catch (Exception exception) {
            if (session != null) {
                session.cancel();
            }
            showFailure(exception);
        }
    }

    private <T> void runAsync(Callable<T> operation, Consumer<T> success) {
        Thread.ofVirtual().name("frostguard-update-operation").start(() -> {
            try {
                T result = operation.call();
                Platform.runLater(() -> success.accept(result));
            } catch (Exception exception) {
                Platform.runLater(() -> showFailure(exception));
            }
        });
    }

    private void showFailure(Exception exception) {
        setBusy(false, exception.getMessage() == null ? "Update operation failed." : exception.getMessage());
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Frostguard update failed");
        alert.setHeaderText("The current installation was not replaced.");
        alert.setContentText(labelStatus.getText());
        alert.show();
    }

    private void setBusy(boolean busy, String status) {
        progress.setVisible(busy);
        buttonCheck.setDisable(busy || !runningBuild.permitsAutomaticUpdates());
        buttonDownloadInstall.setDisable(busy);
        labelStatus.setText(status);
    }

    private void setAvailableUpdateVisible(boolean visible) {
        availableUpdatePane.setVisible(visible);
        availableUpdatePane.setManaged(visible);
    }

    private void configureChannelSwitch() {
        boolean supported = supportsChannelSwitch(runningBuild.channel());
        channelSwitchPane.setVisible(supported);
        channelSwitchPane.setManaged(supported);
        if (!supported) {
            switchTarget = null;
            return;
        }
        switchTarget = runningBuild.channel().alternateRelease();
        buttonSwitchChannel.setText(switchTarget == RuntimeChannel.NIGHTLY ? "Try Nightly" : "Return to Stable");
        labelChannelSwitch.setText("Frostguard " + switchTarget.displayName()
                + " is a separate application with its own settings and workspace.");
    }

    static boolean supportsChannelSwitch(RuntimeChannel channel) {
        return channel != null && channel.isPublicRelease();
    }

    static RunningBuild runningBuild() {
        String version = Optional.ofNullable(UpdateLayoutController.class.getPackage().getImplementationVersion())
                .filter(value -> !value.isBlank())
                .orElseGet(() -> System.getProperty("frostguard.version", "2.1.0"));
        BuildMetadata metadata = BuildMetadata.current();
        return new RunningBuild(SemanticVersion.parse(version), SemanticVersion.parse(version),
                WorkspacePaths.current().channel(), UpdatePlatform.current(),
                metadata.pullRequestBuild(), ProjectUpdateKey.current(), metadata.authenticodePublisher());
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes / 1024.0;
        if (value < 1024) return String.format(java.util.Locale.ROOT, "%.1f KiB", value);
        value /= 1024.0;
        if (value < 1024) return String.format(java.util.Locale.ROOT, "%.1f MiB", value);
        return String.format(java.util.Locale.ROOT, "%.1f GiB", value / 1024.0);
    }
}
